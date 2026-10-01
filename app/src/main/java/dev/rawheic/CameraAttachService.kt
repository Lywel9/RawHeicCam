package dev.rawheic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Foreground camera service that "attaches" to the camera pipeline and keeps
 * a warm capture session. Captures RAW (DNG) + JPEG, transcodes to HEIC with
 * the HDR gain map attached, and publishes the result to MediaStore.
 */
class CameraAttachService : LifecycleService() {

    private var controller: CameraController? = null

    private val captureMutex = Mutex()
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var captureBusy = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startAsForeground()
        controller = CameraController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        currentInstance = null
        controller?.shutdown()
        controller = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                }
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun enqueueCapture(callback: (Boolean) -> Unit) {
        synchronized(pendingCallbacks) { pendingCallbacks.add(callback) }
        if (captureBusy) return
        captureBusy = true
        lifecycleScope.launch(Dispatchers.Main) {
            var ok = false
            captureMutex.withLock {
                ok = runCapture()
            }
            captureBusy = false
            val listeners: List<(Boolean) -> Unit>
            synchronized(pendingCallbacks) {
                listeners = pendingCallbacks.toList()
                pendingCallbacks.clear()
            }
            listeners.forEach { runCatching { it(ok) } }
        }
    }

    private suspend fun runCapture(): Boolean = try {
        val ctrl = controller ?: return false
        val rawDir = File(cacheDir, "raw").apply { mkdirs() }
        val jpegDir = File(cacheDir, "jpeg").apply { mkdirs() }
        val (dng, jpeg) = withTimeout(CAPTURE_TIMEOUT_MS) {
            ctrl.captureRawPlusJpeg(this@CameraAttachService, rawDir, jpegDir)
        }
        val jpegFile = jpeg ?: run {
            Log.w(TAG, "No JPEG produced; cannot transcode")
            return false
        }
        val result = HeicTranscoder.transcode(this, dng, jpegFile) ?: run {
            Log.e(TAG, "Transcode failed")
            return false
        }
        val saved = PhotoSaver.saveToGallery(this, result.file)
        // Clean temp outputs; optionally keep the DNG alongside the HEIC.
        val keptDng = dng?.takeIf { Prefs.keepDng(this) && saved != null }
        keptDng?.let { PhotoSaver.saveDng(this, it) }
        dng?.delete()
        jpegFile.delete()
        result.file.delete()
        saved != null
    } catch (t: Throwable) {
        Log.e(TAG, "capture failed", t)
        false
    }

    companion object {
        private const val TAG = "CameraAttachService"
        private const val CHANNEL_ID = "rawheic_attach"
        private const val NOTIF_ID = 1001
        private const val CAPTURE_TIMEOUT_MS = 45_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CameraAttachService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraAttachService::class.java))
        }

        /** Fire one capture+transcode+save cycle; [callback] runs on a main thread. */
        fun captureOnce(callback: (Boolean) -> Unit) {
            val svc = currentInstance
            if (!isRunning || svc == null) {
                callback(false)
                return
            }
            svc.enqueueCapture(callback)
        }

        @Volatile
        private var currentInstance: CameraAttachService? = null
    }

    init {
        currentInstance = this
    }
}

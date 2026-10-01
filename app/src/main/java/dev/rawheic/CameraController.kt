package dev.rawheic

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX 1.5 wrapper that captures RAW (DNG) + JPEG simultaneously using
 * OUTPUT_FORMAT_RAW_JPEG. Falls back to RAW-only, then JPEG-only, depending
 * on what the device camera HAL reports (e.g. Samsung Galaxy flagships).
 */
class CameraController(private val context: Context) {

    enum class RawMode { RAW_PLUS_JPEG, RAW_ONLY, JPEG_ONLY }

    data class Capabilities(
        val cameraId: String,
        val rawCaptureMode: RawMode,
        val ultraHdrSupported: Boolean
    )

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    suspend fun probeCapabilities(): Capabilities {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val cameraInfo = provider.availableCameraInfos.firstOrNull {
            it.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        val id = cameraInfo?.cameraId ?: "?"
        val caps = cameraInfo?.let { ImageCapture.getImageCaptureCapabilities(it) }
        val formats = caps?.supportedOutputFormats.orEmpty()
        val mode = when {
            formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) -> RawMode.RAW_PLUS_JPEG
            formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_ONLY) -> RawMode.RAW_ONLY
            else -> RawMode.JPEG_ONLY
        }
        val ultraHdr = formats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
        return Capabilities(id, mode, ultraHdr)
    }

    /**
     * Binds Preview + ImageCapture to [lifecycleOwner] (the foreground service)
     * and takes one picture. Returns Pair(rawDng, jpeg) — either side may be
     * null when the device only supports one format or one output failed.
     */
    suspend fun captureRawPlusJpeg(
        lifecycleOwner: LifecycleOwner,
        rawDir: File,
        jpegDir: File
    ): Pair<File?, File?> {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val cameraInfo = provider.availableCameraInfos.firstOrNull {
            it.lensFacing == CameraSelector.LENS_FACING_BACK
        } ?: return null to null
        val formats = ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats

        val builder = ImageCapture.Builder().apply {
            setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(4080, 3060),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            when {
                formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) ->
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_ONLY) ->
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_ONLY)
            }
        }
        val imageCapture = builder.build()

        // ImageCapture-only binding is valid in CameraX and is what a
        // headless capture service wants — no preview surface needed.
        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture
        )

        val stamp = System.currentTimeMillis()
        rawDir.mkdirs(); jpegDir.mkdirs()
        val rawFile = File(rawDir, "capture_$stamp.dng")
        val jpegFile = File(jpegDir, "capture_$stamp.jpg")

        val rawOutput = ImageCapture.OutputFileOptions.Builder(rawFile).build()
        val jpegOutput = ImageCapture.OutputFileOptions.Builder(jpegFile).build()

        return try {
            suspendCancellableCoroutine { cont ->
                var settled = 0

                fun maybeFinish() {
                    // OnImageSavedCallback fires once per requested format, so
                    // exactly two callbacks are expected for RAW+JPEG.
                    if (settled >= 2 && cont.isActive) {
                        val raw: File? = rawFile.takeIf { it.length() > 0 }
                        val jpeg: File? = jpegFile.takeIf { it.length() > 0 }
                        cont.resume(raw to jpeg)
                    }
                }

                imageCapture.takePicture(
                    rawOutput, jpegOutput, cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                            settled++
                            maybeFinish()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            settled++
                            Log.e(TAG, "capture error", exception)
                            maybeFinish()
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "captureRawPlusJpeg failed", t)
            null to null
        } finally {
            provider.unbindAll()
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraController"
    }
}

package dev.rawheic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var attachToggle: Button
    private lateinit var shootButton: Button
    private lateinit var keepDng: CheckBox

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.FOREGROUND_SERVICE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                onPermissionsGranted()
            } else {
                statusText.text = getString(R.string.status_permissions_needed)
                if (!grants.getOrDefault(Manifest.permission.CAMERA, false)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status_text)
        attachToggle = findViewById(R.id.attach_toggle)
        shootButton = findViewById(R.id.shoot_button)
        keepDng = findViewById(R.id.keep_dng)
        keepDng.isChecked = Prefs.keepDng(this)
        keepDng.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepDng(this, checked)
        }

        attachToggle.setOnClickListener {
            Prefs.setAttachOnBoot(this, !CameraAttachService.isRunning)
            toggleAttach()
        }
        shootButton.setOnClickListener { takePhotoNow() }

        shootButton.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions()) {
            onPermissionsGranted()
        } else {
            statusText.text = getString(R.string.status_permissions_needed)
            permissionLauncher.launch(requiredPermissions)
        }
        attachToggle.text =
            getString(if (CameraAttachService.isRunning) R.string.action_detach else R.string.action_attach)
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun onPermissionsGranted() {
        statusText.text = getString(R.string.status_probe_running)
        shootButton.isEnabled = true
        lifecycleScope.launch {
            try {
                val camera = CameraController(this@MainActivity)
                val probe = camera.probeCapabilities()
                camera.shutdown()
                statusText.text = buildString {
                    append(getString(R.string.status_probe_done, probe.cameraId))
                    if (probe.rawCaptureMode == CameraController.RawMode.JPEG_ONLY) {
                        append('\n')
                        append(getString(R.string.status_probe_no_raw))
                    }
                }
            } catch (t: Throwable) {
                statusText.text = getString(R.string.status_capture_failed)
            }
        }
    }

    private fun toggleAttach() {
        if (CameraAttachService.isRunning) {
            CameraAttachService.stop(this)
            attachToggle.text = getString(R.string.action_attach)
            statusText.text = getString(R.string.status_detached)
        } else {
            CameraAttachService.start(this)
            attachToggle.text = getString(R.string.action_detach)
            statusText.text = getString(R.string.status_attached)
        }
    }

    private fun takePhotoNow() {
        val v: View = findViewById(R.id.status_text)
        statusText.text = getString(R.string.status_capturing)
        CameraAttachService.captureOnce {
            runOnUiThread {
                v.alpha = 1f
                statusText.text =
                    getString(if (it) R.string.status_capture_saved else R.string.status_capture_failed)
            }
        }
    }
}

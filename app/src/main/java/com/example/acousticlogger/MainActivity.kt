package com.example.acousticlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var coreController: CoreController
    private lateinit var statusText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton

    private var permissionsGranted = false

    private val runtimePermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            requestManageStorageIfNeeded()
        } else {
            permissionsGranted = false
            updateStatus(getString(R.string.status_permissions_required))
            showPermissionRationale()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionsGranted = hasAllRequiredPermissions()
        if (permissionsGranted) {
            updateStatus(getString(R.string.status_permissions_granted))
        } else {
            updateStatus(getString(R.string.status_permissions_required))
            Toast.makeText(this, R.string.manage_storage_rationale, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val previewView = findViewById<PreviewView>(R.id.cameraPreview)
        coreController = CoreController(this, this, previewView)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }

        requestPermissionsOnLaunch()
    }

    override fun onPause() {
        super.onPause()
        if (coreController.isRecording) {
            coreController.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (coreController.isRecording) {
            coreController.resume()
        } else {
            permissionsGranted = hasAllRequiredPermissions()
        }
    }

    override fun onDestroy() {
        coreController.release()
        super.onDestroy()
    }

    private fun requestPermissionsOnLaunch() {
        val missingPermissions = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            requestManageStorageIfNeeded()
        } else {
            updateStatus(getString(R.string.status_permissions_required))
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun requestManageStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.manage_storage_rationale)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        manageStorageLauncher.launch(intent)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        permissionsGranted = false
                        updateStatus(getString(R.string.status_permissions_required))
                    }
                    .show()
                return
            }
        }

        permissionsGranted = true
        updateStatus(getString(R.string.status_permissions_granted))
    }

    private fun hasAllRequiredPermissions(): Boolean {
        val runtimeOk = runtimePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val storageOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        return runtimeOk && storageOk
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setMessage(R.string.permission_rationale)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                requestPermissionsOnLaunch()
            }
            .show()
    }

    private fun onStartClicked() {
        if (!permissionsGranted && !hasAllRequiredPermissions()) {
            requestPermissionsOnLaunch()
            return
        }

        permissionsGranted = true
        startButton.isEnabled = false
        stopButton.isEnabled = false
        updateStatus(getString(R.string.status_recording))

        lifecycleScope.launch {
            coreController.startRecording()
                .onSuccess {
                    startButton.isEnabled = false
                    stopButton.isEnabled = true
                    updateStatus(getString(R.string.status_recording))
                }
                .onFailure { error ->
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    updateStatus(getString(R.string.status_error, error.message ?: "unknown"))
                }
        }
    }

    private fun onStopClicked() {
        startButton.isEnabled = false
        stopButton.isEnabled = false
        updateStatus(getString(R.string.status_saving))

        lifecycleScope.launch {
            coreController.stopRecordingAndExport()
                .onSuccess { result ->
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    updateStatus(getString(R.string.status_saved))
                    startActivity(ResultsActivity.createIntent(this@MainActivity, result.results))
                }
                .onFailure { error ->
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    updateStatus(getString(R.string.status_error, error.message ?: "unknown"))
                }
        }
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }
}

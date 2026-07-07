package com.example.documenttransfer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.documenttransfer.databinding.ActivityCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    
    private lateinit var networkManager: NetworkManager
    private lateinit var sharedPreferences: SharedPreferences
    
    private var activeServerUrl: String? = null
    private var activeClientMode: String = "offline"

    // SharedPreferences keys
    private val PREFS_NAME = "doc_transfer_prefs"
    private val KEY_SERVER_IP = "server_ip"

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        networkManager = NetworkManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check and request camera permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Set up click listeners
        binding.btnCapture.setOnClickListener { takePhotoAndUpload() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnRefresh.setOnClickListener {
            scanServer()
            Toast.makeText(this, "Scanning for laptop server...", Toast.LENGTH_SHORT).show()
        }

        // Start initial server connectivity scan
        scanServer()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Set scaleType to FIT_CENTER so the preview boundaries match the captured output exactly
            binding.viewFinder.scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER

            // Preview View config
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // Image Capture config - MAXIMIZE_QUALITY to ensure document readability
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera lifecycle
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to initialize camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun scanServer() {
        val configuredIp = sharedPreferences.getString(KEY_SERVER_IP, "") ?: ""
        binding.textServerStatus.text = "Searching laptop..."
        binding.textServerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))

        networkManager.detectActiveServer(configuredIp) { url, mode ->
            activeServerUrl = url
            if (url != null && mode != null) {
                activeClientMode = mode
                binding.textServerStatus.text = "Server: Connected (${mode.uppercase()})"
                binding.textServerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                
                // Save discovered Wi-Fi IP back to settings so it is immediately pinged next time
                if (mode == "wifi") {
                    val cleanHost = url.replace("http://", "").replace("https://", "").split(":")[0].replace("/", "")
                    sharedPreferences.edit().putString(KEY_SERVER_IP, cleanHost).apply()
                }
            } else {
                activeClientMode = "offline"
                binding.textServerStatus.text = "Server: Offline"
                binding.textServerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            }
        }
    }

    private fun takePhotoAndUpload() {
        val imageCapture = imageCapture ?: return
        
        if (activeServerUrl == null) {
            Toast.makeText(this, "Error: Laptop server is offline. Connect Wi-Fi or plug in USB.", Toast.LENGTH_LONG).show()
            scanServer() // Scan again
            return
        }

        // Disable shutter button during capture/upload
        binding.btnCapture.isEnabled = false

        // Create temporary photo file in cache directory
        val photoFile = File(
            cacheDir,
            "scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Capture photo
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@CameraActivity, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                    binding.btnCapture.isEnabled = true
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    uploadPhoto(photoFile)
                }
            }
        )
    }

    private fun uploadPhoto(photoFile: File) {
        val serverUrl = activeServerUrl ?: return
        
        // Show status overlay
        binding.statusCard.visibility = View.VISIBLE
        binding.statusProgressBar.isIndeterminate = false
        binding.statusProgressBar.progress = 0
        binding.textStatusMessage.text = "Uploading: 0%"

        networkManager.uploadImage(
            file = photoFile,
            serverUrl = serverUrl,
            clientMode = activeClientMode,
            onProgress = { progress ->
                // Update progress bar & label
                binding.statusProgressBar.progress = progress
                binding.textStatusMessage.text = "Uploading: $progress%"
            },
            onComplete = { success, info ->
                binding.btnCapture.isEnabled = true
                
                if (success) {
                    binding.textStatusMessage.text = "Success!"
                    binding.statusProgressBar.progress = 100
                    triggerVibration()
                    
                    // Hide overlay after delay
                    binding.statusCard.postDelayed({
                        binding.statusCard.visibility = View.GONE
                    }, 1500)
                } else {
                    binding.textStatusMessage.text = "Failed!"
                    Toast.makeText(this@CameraActivity, "Upload failed: $info", Toast.LENGTH_LONG).show()
                    
                    binding.statusCard.postDelayed({
                        binding.statusCard.visibility = View.GONE
                    }, 3000)
                }
                
                // Securely clean up the temporary file in cache to prevent storage leaks
                if (photoFile.exists()) {
                    photoFile.delete()
                }
            }
        )
    }

    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            // Safe fallback if permission/device doesn't support vibration
        }
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Configure Laptop Wi-Fi IP")
        
        val input = EditText(this)
        input.hint = "e.g., 192.168.1.15"
        val savedIp = sharedPreferences.getString(KEY_SERVER_IP, "") ?: ""
        input.setText(savedIp)
        builder.setView(input)

        builder.setPositiveButton("Save") { dialog, _ ->
            val ipText = input.text.toString().trim()
            sharedPreferences.edit().putString(KEY_SERVER_IP, ipText).apply()
            dialog.dismiss()
            scanServer() // Scan connection with new configuration
        }
        
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    override fun onResume() {
        super.onResume()
        scanServer() // Re-verify server status when reopening app
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

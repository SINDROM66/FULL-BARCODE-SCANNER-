package com.ugid.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isProcessing = false

    companion object {
        private const val TAG = "UgandaIDScanner"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage, imageProxy.imageInfo.rotationDegrees
        )
        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.format == Barcode.FORMAT_PDF417) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank() && rawValue.contains(";")) {
                            isProcessing = true
                            sendToParser(rawValue)
                            break
                        }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun sendToParser(payload: String) {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.parseBarcode(ParseRequest(payload))
                if (response.isSuccessful) {
                    response.body()?.let { showResult(it) }
                } else {
                    val err = response.errorBody()?.string() ?: "Unknown error"
                    showError("Server error: $err")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                showError("Network error. Is backend running at ${RetrofitClient.BASE_URL}?")
            } finally {
                isProcessing = false
            }
        }
    }

    private fun showResult(record: ParseResponse) {
        val status = if (record.is_expired) "⚠️ EXPIRED" else "✅ Valid"
        val warningsText = if (record.warnings.isNotEmpty())
            "\n\nWarnings:\n${record.warnings.joinToString("\n") { "• $it" }}"
        else ""

        val message = """
            Full Name: ${record.full_name}
            NIN: ${record.nin}
            Sex: ${record.sex}
            Date of Birth: ${record.date_of_birth} (Age: ${record.age})
            Card Number: ${record.card_number}
            Issued: ${record.issue_date}
            Expires: ${record.expiry_date}
            Status: $status$warningsText
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("ID Scanned Successfully")
            .setMessage(message)
            .setPositiveButton("Scan Another") { _, _ -> isProcessing = false }
            .setCancelable(false)
            .show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Scan Failed")
            .setMessage(message)
            .setPositiveButton("Try Again") { _, _ -> isProcessing = false }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

package com.ugid.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

    private lateinit var recordManager: RecordManager
    private lateinit var recordAdapter: RecordAdapter
    private var currentScannedResponse: ParseResponse? = null

    // UI Elements
    private lateinit var captureContainer: View
    private lateinit var recordsContainer: View
    private lateinit var reviewForm: View
    private lateinit var loadingOverlay: View

    private lateinit var etName: TextInputEditText
    private lateinit var etNin: TextInputEditText
    private lateinit var etDob: TextInputEditText
    private lateinit var etSex: TextInputEditText
    private lateinit var etPhoneNumber: TextInputEditText

    private lateinit var recyclerView: RecyclerView

    companion object {
        private const val TAG = "UgandaIDScanner"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordManager = RecordManager(this)

        previewView = findViewById(R.id.previewView)
        captureContainer = findViewById(R.id.capture_container)
        recordsContainer = findViewById(R.id.records_container)
        reviewForm = findViewById(R.id.review_form)
        loadingOverlay = findViewById(R.id.loading_overlay)

        etName = findViewById(R.id.etName)
        etNin = findViewById(R.id.etNin)
        etDob = findViewById(R.id.etDob)
        etSex = findViewById(R.id.etSex)
        etPhoneNumber = findViewById(R.id.etPhoneNumber)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recordAdapter = RecordAdapter(emptyList())
        recyclerView.adapter = recordAdapter

        findViewById<Button>(R.id.btnTabCapture).setOnClickListener {
            captureContainer.visibility = View.VISIBLE
            recordsContainer.visibility = View.GONE
            if (reviewForm.visibility == View.VISIBLE) {
                // Keep review form open if they were editing
            } else {
                startCamera()
            }
        }

        findViewById<Button>(R.id.btnTabRecords).setOnClickListener {
            captureContainer.visibility = View.GONE
            recordsContainer.visibility = View.VISIBLE
            loadRecords()
        }

        findViewById<Button>(R.id.btnCapturePhoto).setOnClickListener {
            takePhotoAndProcess()
        }

        findViewById<Button>(R.id.btnEnterManually).setOnClickListener {
            currentScannedResponse = null
            etName.setText("")
            etNin.setText("")
            etDob.setText("")
            etSex.setText("")
            etPhoneNumber.setText("")
            reviewForm.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnSaveRecord).setOnClickListener {
            val name = etName.text.toString().trim()
            val nin = etNin.text.toString().trim()
            val dob = etDob.text.toString().trim()
            val sex = etSex.text.toString().trim()
            val phone = etPhoneNumber.text.toString().trim()

            if (name.isEmpty() || nin.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Please fill in all required fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // If manually entered without scan, create a dummy ParseResponse
            val response = currentScannedResponse ?: ParseResponse(
                surname = "",
                given_name = name,
                other_name = "",
                full_name = name,
                date_of_birth = dob,
                issue_date = "",
                expiry_date = "",
                nin = nin,
                sex = sex,
                card_number = nin,
                age = 0,
                is_expired = false,
                fingerprint = FingerprintModel(0, 0, 0, 0),
                warnings = emptyList()
            )

            val record = ScannedRecord(response, phone)
            recordManager.saveRecord(record)
            Toast.makeText(this, "Record saved successfully", Toast.LENGTH_SHORT).show()
            closeReviewForm()
        }

        findViewById<Button>(R.id.btnCancelRecord).setOnClickListener {
            closeReviewForm()
        }

        findViewById<Button>(R.id.btnExport).setOnClickListener {
            exportToCsv()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun loadRecords() {
        val records = recordManager.loadRecords()
        recordAdapter.updateRecords(records)
    }

    private fun closeReviewForm() {
        reviewForm.visibility = View.GONE
        currentScannedResponse = null
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

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun takePhotoAndProcess() {
        val imageCapture = imageCapture ?: return

        loadingOverlay.visibility = View.VISIBLE

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processCapturedImage(imageProxy)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    loadingOverlay.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processCapturedImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            loadingOverlay.visibility = View.GONE
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                var found = false
                for (barcode in barcodes) {
                    if (barcode.format == Barcode.FORMAT_PDF417) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank() && rawValue.contains(";")) {
                            found = true
                            sendToParser(rawValue)
                            break
                        }
                    }
                }
                if (!found) {
                    loadingOverlay.visibility = View.GONE
                    Toast.makeText(this, "No valid ID barcode found. Try again or enter manually.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                loadingOverlay.visibility = View.GONE
                Toast.makeText(this, "Barcode processing failed.", Toast.LENGTH_SHORT).show()
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
                    response.body()?.let { 
                        currentScannedResponse = it
                        etName.setText(it.full_name)
                        etNin.setText(it.nin)
                        etDob.setText(it.date_of_birth)
                        etSex.setText(it.sex)
                        etPhoneNumber.setText("")
                        
                        loadingOverlay.visibility = View.GONE
                        reviewForm.visibility = View.VISIBLE
                    }
                } else {
                    val err = response.errorBody()?.string() ?: "Unknown error"
                    loadingOverlay.visibility = View.GONE
                    showError("Server error: $err")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                loadingOverlay.visibility = View.GONE
                showError("Network error. Is backend running at ${RetrofitClient.BASE_URL}?")
            }
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Scan Failed")
            .setMessage(message)
            .setPositiveButton("Try Again") { _, _ -> /* nothing */ }
            .setCancelable(false)
            .show()
    }

    private fun exportToCsv() {
        val records = recordManager.loadRecords()
        if (records.isEmpty()) {
            Toast.makeText(this, "No records to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val csvFile = File(cacheDir, "records.csv")
            FileWriter(csvFile).use { writer ->
                // Header
                writer.append("Name,NIN,Phone Number,DOB,Sex,Card Number,Issue Date,Expiry Date\n")
                // Data
                for (record in records) {
                    val r = record.response
                    writer.append("${escapeCsv(r.full_name)},")
                        .append("${escapeCsv(r.nin)},")
                        .append("${escapeCsv(record.phoneNumber)},")
                        .append("${escapeCsv(r.date_of_birth)},")
                        .append("${escapeCsv(r.sex)},")
                        .append("${escapeCsv(r.card_number)},")
                        .append("${escapeCsv(r.issue_date)},")
                        .append("${escapeCsv(r.expiry_date)}\n")
                }
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                csvFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Export Records"))
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun escapeCsv(value: String?): String {
        if (value == null) return ""
        var escaped = value
        if (escaped.contains("\"")) {
            escaped = escaped.replace("\"", "\"\"")
        }
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")) {
            escaped = "\"$escaped\""
        }
        return escaped
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

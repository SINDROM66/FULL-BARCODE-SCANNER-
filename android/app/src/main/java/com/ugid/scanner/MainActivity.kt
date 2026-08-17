package com.ugid.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private var isProcessing = false

    private lateinit var recordManager: RecordManager
    private lateinit var recordAdapter: RecordAdapter
    private var currentScannedResponse: ParseResponse? = null

    // UI Elements
    private lateinit var captureContainer: ConstraintLayout
    private lateinit var recordsContainer: LinearLayout
    private lateinit var reviewFormOverlay: ScrollView
    private lateinit var tvScannedData: TextView
    private lateinit var etPhoneNumber: EditText
    private lateinit var btnSaveRecord: Button
    private lateinit var btnCancelRecord: Button
    private lateinit var btnTabCapture: Button
    private lateinit var btnTabRecords: Button
    private lateinit var recyclerRecords: RecyclerView
    private lateinit var btnExportRecords: Button

    companion object {
        private const val TAG = "UgandaIDScanner"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordManager = RecordManager(this)

        initViews()
        setupListeners()
        setupRecyclerView()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        captureContainer = findViewById(R.id.capture_container)
        recordsContainer = findViewById(R.id.records_container)
        reviewFormOverlay = findViewById(R.id.review_form_overlay)
        tvScannedData = findViewById(R.id.tv_scanned_data)
        etPhoneNumber = findViewById(R.id.et_phone_number)
        btnSaveRecord = findViewById(R.id.btn_save_record)
        btnCancelRecord = findViewById(R.id.btn_cancel_record)
        btnTabCapture = findViewById(R.id.btn_tab_capture)
        btnTabRecords = findViewById(R.id.btn_tab_records)
        recyclerRecords = findViewById(R.id.recycler_records)
        btnExportRecords = findViewById(R.id.btn_export_records)
    }

    private fun setupListeners() {
        btnTabCapture.setOnClickListener {
            captureContainer.visibility = View.VISIBLE
            recordsContainer.visibility = View.GONE
        }

        btnTabRecords.setOnClickListener {
            captureContainer.visibility = View.GONE
            recordsContainer.visibility = View.VISIBLE
            loadRecords()
        }

        btnSaveRecord.setOnClickListener {
            currentScannedResponse?.let { response ->
                val phone = etPhoneNumber.text.toString().trim()
                val record = ScannedRecord(response, phone)
                recordManager.saveRecord(record)
                Toast.makeText(this, "Record saved successfully", Toast.LENGTH_SHORT).show()
                hideReviewForm()
            }
        }

        btnCancelRecord.setOnClickListener {
            hideReviewForm()
        }

        btnExportRecords.setOnClickListener {
            exportToCsv()
        }
    }

    private fun setupRecyclerView() {
        recordAdapter = RecordAdapter(emptyList())
        recyclerRecords.layoutManager = LinearLayoutManager(this)
        recyclerRecords.adapter = recordAdapter
    }

    private fun loadRecords() {
        val records = recordManager.loadRecords()
        recordAdapter.updateRecords(records)
    }

    private fun showReviewForm(record: ParseResponse) {
        currentScannedResponse = record
        val status = if (record.is_expired) "⚠️ EXPIRED" else "✅ Valid"
        val message = """
            Full Name: ${record.full_name}
            NIN: ${record.nin}
            Sex: ${record.sex}
            DOB: ${record.date_of_birth}
            Status: $status
        """.trimIndent()

        tvScannedData.text = message
        etPhoneNumber.setText("")
        reviewFormOverlay.visibility = View.VISIBLE
    }

    private fun hideReviewForm() {
        reviewFormOverlay.visibility = View.GONE
        currentScannedResponse = null
        etPhoneNumber.setText("")
        isProcessing = false
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
        if (isProcessing || reviewFormOverlay.visibility == View.VISIBLE || captureContainer.visibility != View.VISIBLE) {
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
                    response.body()?.let { showReviewForm(it) }
                } else {
                    val err = response.errorBody()?.string() ?: "Unknown error"
                    showError("Server error: $err")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                showError("Network error. Is backend running at ${RetrofitClient.BASE_URL}?")
            }
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Scan Failed")
            .setMessage(message)
            .setPositiveButton("Try Again") { _, _ -> isProcessing = false }
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

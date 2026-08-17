package com.ugid.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

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
    private lateinit var adapter: RecordsAdapter

    private var currentRecord: ParsedRecord? = null

    companion object {
        private const val TAG = "UgandaIDScanner"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
            currentRecord = null
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

            // Fallback card number to NIN if manually entered
            val cardNumber = currentRecord?.cardNumber ?: nin
            val fullRecord = Record(name, dob, nin, cardNumber, sex, phone)
            
            RecordManager.saveRecord(this, fullRecord)
            Toast.makeText(this, "Record saved successfully", Toast.LENGTH_SHORT).show()
            closeReviewForm()
        }

        findViewById<Button>(R.id.btnCancelRecord).setOnClickListener {
            closeReviewForm()
        }

        findViewById<Button>(R.id.btnExport).setOnClickListener {
            exportToCsv()
        }

        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            RecordManager.clearAllRecords(this)
            loadRecords()
            Toast.makeText(this, "All records cleared", Toast.LENGTH_SHORT).show()
        }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RecordsAdapter(emptyList()) { recordToDelete ->
            RecordManager.deleteRecord(this, recordToDelete)
            loadRecords()
            Toast.makeText(this, "Record deleted", Toast.LENGTH_SHORT).show()
        }
        recyclerView.adapter = adapter

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun loadRecords() {
        val records = RecordManager.getRecords(this)
        adapter.updateData(records)
    }

    private fun exportToCsv() {
        try {
            val records = RecordManager.getRecords(this)
            val csvHeader = "Name,NIN,DOB,Sex,CardNumber,PhoneNumber\n"
            val csvContent = records.joinToString("\n") {
                "${it.name.replace(",", " ")},${it.nin},${it.dob},${it.sex},${it.cardNumber},${it.phoneNumber.replace(",", " ")}"
            }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "NSSF_Records_${System.currentTimeMillis()}.csv")
            file.writeText(csvHeader + csvContent)

            Toast.makeText(this, "Exported successfully to Downloads folder", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeReviewForm() {
        reviewForm.visibility = View.GONE
        currentRecord = null
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
        Thread {
            val parsed = UgandaIdParser.parse(payload)
            runOnUiThread {
                loadingOverlay.visibility = View.GONE
                if (parsed != null) {
                    currentRecord = parsed
                    etName.setText(parsed.name)
                    etNin.setText(parsed.nin)
                    etDob.setText(parsed.dob)
                    etSex.setText(parsed.sex)
                    etPhoneNumber.setText("")
                    reviewForm.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "Could not decode barcode data", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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

class RecordsAdapter(private var records: List<Record>, private val onDeleteClick: (Record) -> Unit) : RecyclerView.Adapter<RecordsAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvNin: TextView = view.findViewById(R.id.tvNin)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
        val tvPhone: TextView = view.findViewById(R.id.tvPhone)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rec = records[position]
        holder.tvName.text = rec.name
        holder.tvNin.text = rec.nin
        holder.tvDetails.text = "DOB: ${rec.dob} | Sex: ${rec.sex} | Card: ${rec.cardNumber}"
        holder.tvPhone.text = "Phone: ${rec.phoneNumber}"
        holder.btnDelete.setOnClickListener {
            onDeleteClick(rec)
        }
    }

    override fun getItemCount() = records.size

    fun updateData(newRecords: List<Record>) {
        records = newRecords
        notifyDataSetChanged()
    }
}

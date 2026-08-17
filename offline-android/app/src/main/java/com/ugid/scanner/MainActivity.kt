package com.ugid.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isProcessing = false

    private lateinit var captureContainer: View
    private lateinit var recordsContainer: View
    private lateinit var reviewForm: View

    private lateinit var tvReviewName: TextView
    private lateinit var tvReviewNin: TextView
    private lateinit var tvReviewDob: TextView
    private lateinit var tvReviewSex: TextView
    private lateinit var etPhoneNumber: EditText

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

        tvReviewName = findViewById(R.id.tvReviewName)
        tvReviewNin = findViewById(R.id.tvReviewNin)
        tvReviewDob = findViewById(R.id.tvReviewDob)
        tvReviewSex = findViewById(R.id.tvReviewSex)
        etPhoneNumber = findViewById(R.id.etPhoneNumber)

        findViewById<Button>(R.id.btnTabCapture).setOnClickListener {
            captureContainer.visibility = View.VISIBLE
            recordsContainer.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnTabRecords).setOnClickListener {
            captureContainer.visibility = View.GONE
            recordsContainer.visibility = View.VISIBLE
            loadRecords()
        }

        findViewById<Button>(R.id.btnSaveRecord).setOnClickListener {
            val rec = currentRecord ?: return@setOnClickListener
            val phone = etPhoneNumber.text.toString().trim()
            val fullRecord = Record(rec.name, rec.dob, rec.nin, rec.cardNumber, rec.sex, phone)
            RecordManager.saveRecord(this, fullRecord)
            Toast.makeText(this, "Record saved", Toast.LENGTH_SHORT).show()
            closeReviewForm()
        }

        findViewById<Button>(R.id.btnCancelRecord).setOnClickListener {
            closeReviewForm()
        }

        findViewById<Button>(R.id.btnExport).setOnClickListener {
            exportToCsv()
        }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RecordsAdapter(emptyList())
        recyclerView.adapter = adapter

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
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
            val file = File(cacheDir, "records.csv")
            file.writeText(csvHeader + csvContent)

            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Uganda ID Records")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export Records"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeReviewForm() {
        reviewForm.visibility = View.GONE
        isProcessing = false
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
        if (isProcessing || reviewForm.visibility == View.VISIBLE) {
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
        val parsed = UgandaIdParser.parse(payload)
        if (parsed != null) {
            runOnUiThread {
                currentRecord = parsed
                tvReviewName.text = "Name: ${parsed.name}"
                tvReviewNin.text = "NIN: ${parsed.nin}"
                tvReviewDob.text = "DOB: ${parsed.dob}"
                tvReviewSex.text = "Sex: ${parsed.sex}"
                etPhoneNumber.text.clear()
                reviewForm.visibility = View.VISIBLE
            }
        } else {
            isProcessing = false
        }
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

class RecordsAdapter(private var records: List<Record>) : RecyclerView.Adapter<RecordsAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvNin: TextView = view.findViewById(R.id.tvNin)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
        val tvPhone: TextView = view.findViewById(R.id.tvPhone)
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
    }

    override fun getItemCount() = records.size

    fun updateData(newRecords: List<Record>) {
        records = newRecords
        notifyDataSetChanged()
    }
}

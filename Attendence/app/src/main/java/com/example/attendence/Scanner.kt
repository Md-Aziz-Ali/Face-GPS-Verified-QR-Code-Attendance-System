package com.example.attendence

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.attendence.databinding.ActivityScannerBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.*

class Scanner : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var scannedOnce = false

    // Permissions launcher
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) startCamera()
            else binding.tvResult.text = "Camera permission denied."
        }

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) binding.tvResult.text = "Location permission denied."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Request location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("CameraX", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (scannedOnce) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { value ->
                            scannedOnce = true
//                            Log.d("QR_SCAN", "Scanned QR: $value")
                            binding.tvResult.text = "Scanned Data: $value"

                            verifyAttendance(value)
                        }
                    }
                }
                .addOnFailureListener {
                    Log.e("CameraX", "Barcode scan failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun verifyAttendance(qrData: String) {
        try {
            // Split into key-value pairs
            val parts = qrData.split("|")
            val dataMap = mutableMapOf<String, String>()

            for (part in parts) {
                val keyValue = part.split(":", limit = 2)
                if (keyValue.size == 2) {
                    dataMap[keyValue[0].trim()] = keyValue[1].trim()
                }
            }

            // Ensure all required keys exist
            val requiredKeys = listOf("SEM", "SUB", "DATE", "TIME", "LAT", "LON")
            if (!requiredKeys.all { it in dataMap }) {
                binding.tvResult.text = "Invalid QR format"
                return
            }

            val sem = dataMap["SEM"]!!
            val subject = dataMap["SUB"]!!
            val qrDate = dataMap["DATE"]!!
            val qrTimeStr = dataMap["TIME"]!!
            val teacherLat = dataMap["LAT"]!!.toDouble()
            val teacherLon = dataMap["LON"]!!.toDouble()

            // Combine date and time
            val dateTimeString = "$qrDate $qrTimeStr"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val qrTime = sdf.parse(dateTimeString) ?: return
            val currentTime = Date()
            val timeDiffMinutes = (currentTime.time - qrTime.time) / (1000 * 60)

            // Get student's location
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                binding.tvResult.text = "Location permission denied."
                return
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val distance = calculateDistance(
                        location.latitude, location.longitude,
                        teacherLat, teacherLon
                    )

                    when {
                        timeDiffMinutes > 100 -> {
                            binding.tvResult.text = "Attendance not done: Late in time"
                        }
                        distance > 5000 -> {
                            binding.tvResult.text = "Attendance not done: Out of range"
                        }
                        else -> {
                            val studentId = FirebaseAuth.getInstance().currentUser?.uid ?: return@addOnSuccessListener
                            markAttendanceBatch(studentId, sem, subject, qrDate) { success, message ->
                                if (success) {
//                                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                                    binding.tvResult.text =
                                        "Verified - Attendance Done\nSEM: $sem\nSubject: $subject\nDate: $qrDate $qrTimeStr"
                                } else {
//                                    Toast.makeText(this, "Failed: $message", Toast.LENGTH_LONG).show()
                                    binding.tvResult.text =
                                        "Gandu - lauda Done\nSEM: $sem\nSubject: $subject\nDate: $qrDate $qrTimeStr"
                                }
                            }

                        }
                    }
                } else {
                    binding.tvResult.text = "Unable to get current location"
                }
            }
        } catch (e: Exception) {
            binding.tvResult.text = "Error: ${e.message}"
        }
    }


    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    fun markAttendanceBatch(
        studentId: String,
        sem: String,
        subjectName: String,
        classDate: String, // format: YYYY-MM-DD or timestamp string
        onComplete: (Boolean, String) -> Unit
    ) {
        val firestore = FirebaseFirestore.getInstance()

        // Prepare subjectId without spaces
        val subjectId = "${sem}_${subjectName.replace(" ", "_")}"

        // Create a batch
        val batch = firestore.batch()

        // 1️⃣ Update attendance in the class document
        val classDocRef = firestore
            .collection("Subjects")
            .document(subjectId)
            .collection("Classes")
            .document(classDate)

        val attendanceUpdate = mapOf("attendance.$studentId" to "present")
        batch.set(classDocRef, attendanceUpdate, SetOptions.merge())

        // 2️⃣ Increment student's attended count for that subject
        val studentSubjectDocRef = firestore
            .collection("Students")
            .document(studentId)
            .collection("subjects")
            .document(subjectId)

        batch.set(studentSubjectDocRef, mapOf("attended" to 0), SetOptions.merge()) // ensures doc exists
        batch.update(studentSubjectDocRef, "attended", com.google.firebase.firestore.FieldValue.increment(1))

        // Commit batch
        batch.commit()
            .addOnSuccessListener {
                onComplete(true, "Attendance marked successfully")
            }
            .addOnFailureListener { e ->
                onComplete(false, "Error marking attendance: ${e.message}")
            }
    }
}

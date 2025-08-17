package com.example.teacherspanel

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.teacherspanel.databinding.ActivityGenerateQrBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.getValue

class GenerateQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGenerateQrBinding

//    private lateinit var spinnerSemester: Spinner
//    private lateinit var spinnerSubject: Spinner
//    private lateinit var btnGenerate: Button
//    private lateinit var imageQr: ImageView
//    private lateinit var progressBar: ProgressBar

    private val dbRef = FirebaseDatabase.getInstance().getReference("cse_subjects")
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    // permission launcher
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) {
                // proceed
                generateQrAfterPermission()
            } else {
                toast("Location permission is required to include coordinates")
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityGenerateQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

//        spinnerSemester = findViewById(R.id.spinnerSemester)
//        spinnerSubject = findViewById(R.id.spinnerSubject)
//        btnGenerate = findViewById(R.id.btnGenerate)
//        imageQr = findViewById(R.id.imageQr)
//        progressBar = findViewById(R.id.progressBar)

        loadSemestersFromDb()

        binding.btnGenerate.setOnClickListener {
            // validate selection
            val sem = binding.spinnerSemester.selectedItem as? String
            val subj = binding.spinnerSubject.selectedItem as? String
            if (sem.isNullOrBlank() || subj.isNullOrBlank()) {
                toast("Select semester and subject")
                return@setOnClickListener
            }

            // Check permissions first
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) {
                // already permitted
                generateQrFlow(sem, subj)
            } else {
                // request permissions
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    private fun loadSemestersFromDb() {
        binding.progressBar.visibility = View.VISIBLE
        dbRef.get().addOnSuccessListener { snapshot ->
            val semesters = snapshot.children.mapNotNull { it.key }.sorted()
            if (semesters.isEmpty()) {
                toast("No semesters found in database")
                binding.progressBar.visibility = View.GONE
                return@addOnSuccessListener
            }

            val semAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, semesters)
            semAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerSemester.adapter = semAdapter

            // load subjects for first item by default
            binding.spinnerSemester.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    val selected = semesters[position]
                    loadSubjectsForSemester(selected)
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
            // preselect position 0 -> triggers loadSubjectsForSemester
            binding.spinnerSemester.setSelection(0)
            binding.progressBar.visibility = View.GONE
        }.addOnFailureListener {
            binding.progressBar.visibility = View.GONE
            toast("Failed to load semesters: ${it.message}")
        }
    }

    private fun loadSubjectsForSemester(semester: String) {
        binding.progressBar.visibility = View.VISIBLE
        dbRef.child(semester).get().addOnSuccessListener { snapshot ->
            val subjects = snapshot.children.mapNotNull { it.getValue(String::class.java) }
            if (subjects.isEmpty()) {
                toast("No subjects found for $semester")
            }
            val subjAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, subjects)
            subjAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerSubject.adapter = subjAdapter
            if (subjects.isNotEmpty()) binding.spinnerSubject.setSelection(0)
            binding.progressBar.visibility = View.GONE
        }.addOnFailureListener {
            binding.progressBar.visibility = View.GONE
            toast("Failed to load subjects: ${it.message}")
        }
    }

    private fun generateQrAfterPermission() {
        // called after permission grant - just perform button click action
        val sem = binding.spinnerSemester.selectedItem as? String
        val subj = binding.spinnerSubject.selectedItem as? String
        if (sem == null || subj == null) {
            toast("Select semester and subject")
            return
        }
        generateQrFlow(sem, subj)
    }

    @SuppressLint("MissingPermission")
    private fun generateQrFlow(sem: String, subj: String) {
        binding.progressBar.visibility = View.VISIBLE
        // try lastLocation first
        fusedLocationClient.lastLocation.addOnSuccessListener { last ->
//            if (last != null) {
////                finalizeAndShowQr(sem, subj, last.latitude, last.longitude)
//            } else {
                // fallback to current high-accuracy single result
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { fresh ->
                        if (fresh != null) {
                            generateQrAndStoreData(sem, subj, fresh.latitude, fresh.longitude)
//                            finalizeAndShowQr(sem, subj, fresh.latitude, fresh.longitude)
                        } else {
                            // couldn't get location — still build QR with 0,0 or notify user
                            binding.progressBar.visibility = View.GONE
                            toast("Unable to get location. Try again or enable GPS.")
                        }
                    }
                    .addOnFailureListener { ex ->
                        binding.progressBar.visibility = View.GONE
                        toast("Location error: ${ex.message}")
                    }
//            }
        }.addOnFailureListener { ex ->
            binding.progressBar.visibility = View.GONE
            toast("Location error: ${ex.message}")
        }
    }

    private fun finalizeAndShowQr(sem: String, subj: String, lat: Double, lon: Double) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val payload = "SEM:$sem|SUB:$subj|DATE:$date|TIME:$time|LAT:$lat|LON:$lon"

        // generate QR bitmap
        try {
            val bitMatrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 800, 800)
            val bitmap: Bitmap = BarcodeEncoder().createBitmap(bitMatrix)
            binding.imageQr.setImageBitmap(bitmap)
        } catch (e: Exception) {
            toast("QR generation error: ${e.message}")
        } finally {
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    fun generateQrAndStoreData1(
        subjectName: String,
        sem: String,
        teacherLat: Double,
        teacherLon: Double
    ) {
        val firestore = FirebaseFirestore.getInstance()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        val currentTime = timeFormat.format(Date())

        // Create QR code data
        val qrData = "SEM:$sem|SUB:$subjectName|DATE:$currentDate|TIME:$currentTime|LAT:$teacherLat|LON:$teacherLon"

        // Check if the subject already exists for this sem
        val subjectQuery = firestore.collection("Subjects")
            .whereEqualTo("name", subjectName)
            .whereEqualTo("sem", sem)
            .limit(1)

        subjectQuery.get().addOnSuccessListener { querySnapshot ->
            val subjectDocRef: DocumentReference
            if (querySnapshot.isEmpty) {
                // Subject not found → create new
                subjectDocRef = firestore.collection("Subjects").document()
                subjectDocRef.set(
                    mapOf(
                        "name" to subjectName,
                        "sem" to sem,
                        "totalClasses" to 0
                    )
                )
            } else {
                subjectDocRef = querySnapshot.documents[0].reference
            }

            firestore.runTransaction { transaction ->
                val subjectSnap = transaction.get(subjectDocRef)
                val currentTotal = subjectSnap.getLong("totalClasses") ?: 0
                transaction.update(subjectDocRef, "totalClasses", currentTotal + 1)

                // Create empty attendance record for today
                val classRef = subjectDocRef.collection("Classes").document(currentDate)
                val classData = mapOf(
                    "date" to currentDate,
                    "time" to currentTime,
                    "attendance" to emptyMap<String, String>()
                )
                transaction.set(classRef, classData)

                null
            }.addOnSuccessListener {
                // Update all students in that semester
                firestore.collection("Students")
                    .whereEqualTo("sem", sem)
                    .get()
                    .addOnSuccessListener { studentsSnapshot ->
                        val batch = firestore.batch()
                        for (studentDoc in studentsSnapshot) {
                            val studentId = studentDoc.id
                            val studentSubjectRef = firestore.collection("Students")
                                .document(studentId)
                                .collection("subjects")
                                .document(subjectDocRef.id)

                            batch.set(
                                studentSubjectRef,
                                mapOf(
                                    "attended" to (studentDoc.get("subjects.${subjectDocRef.id}.attended") ?: 0),
                                    "total" to FieldValue.increment(1)
                                ),
                                SetOptions.merge()
                            )
                        }
                        batch.commit().addOnSuccessListener {
                            // QR ready
//                            binding.tvResult.text = "QR Generated:\n$qrData"
                            finalizeAndShowQr(sem, subjectName, teacherLat, teacherLon)
                            Log.d("QR", "Generated: $qrData")
                        }
                    }
            }
        }.addOnFailureListener { e ->
            Log.e("QR", "Error: ${e.message}")
        }
    }

    fun generateQrAndStoreData(
        subjectName: String,
        sem: String,
        teacherLat: Double,
        teacherLon: Double
    ) {
        val firestore = FirebaseFirestore.getInstance()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        val currentTime = timeFormat.format(Date())

        // QR code format
        val qrData = "SEM:$sem|SUB:$subjectName|DATE:$currentDate|TIME:$currentTime|LAT:$teacherLat|LON:$teacherLon"

        // Reference for the subject (pre-generate ID if new)
        val subjectDocRef = firestore.collection("Subjects")
            .document("${sem}_${subjectName.replace(" ", "_")}")

        subjectDocRef.get().addOnSuccessListener { docSnap ->
            if (!docSnap.exists()) {
                // Create new subject doc
                subjectDocRef.set(
                    mapOf(
                        "name" to subjectName,
                        "sem" to sem,
                        "totalClasses" to 0
                    )
                )
            }

            // Now safe to run transaction
            firestore.runTransaction { transaction ->
                val snap = transaction.get(subjectDocRef)
                val currentTotal = snap.getLong("totalClasses") ?: 0
                transaction.update(subjectDocRef, "totalClasses", currentTotal + 1)

                // Create class record for this date
                val classRef = subjectDocRef.collection("Classes").document(currentDate)
                val classData = mapOf(
                    "date" to currentDate,
                    "time" to currentTime,
                    "attendance" to emptyMap<String, String>()
                )
                transaction.set(classRef, classData)
            }.addOnSuccessListener {
                // Update student totals if students exist
                firestore.collection("Students")
                    .whereEqualTo("sem", sem)
                    .get()
                    .addOnSuccessListener { studentsSnapshot ->
                        val batch = firestore.batch()
                        for (studentDoc in studentsSnapshot) {
                            val studentId = studentDoc.id
                            val studentSubjectRef = firestore.collection("Students")
                                .document(studentId)
                                .collection("subjects")
                                .document(subjectDocRef.id)

                            batch.set(
                                studentSubjectRef,
                                mapOf(
                                    "attended" to (studentDoc.get("subjects.${subjectDocRef.id}.attended") ?: 0),
                                    "total" to FieldValue.increment(1)
                                ),
                                SetOptions.merge()
                            )
                        }
                        batch.commit().addOnSuccessListener {
                            finalizeAndShowQr(sem, subjectName, teacherLat, teacherLon)
//                            Log.d("QR", "Generated: $qrData")
                            Toast.makeText(this@GenerateQrActivity,"Qr generated", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }.addOnFailureListener { e ->
            Log.e("QR", "Error: ${e.message}")
        }
    }



}
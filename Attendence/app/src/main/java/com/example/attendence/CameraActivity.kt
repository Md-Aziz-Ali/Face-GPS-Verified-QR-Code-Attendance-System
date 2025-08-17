package com.example.attendence

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.attendence.databinding.ActivityCameraBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import com.google.mlkit.vision.face.FaceDetector
import java.nio.ByteBuffer
import kotlin.math.sqrt


class CameraActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityCameraBinding

    private var imageCapture: ImageCapture? = null

    var lensFacing = CameraSelector.LENS_FACING_BACK
    private lateinit var cameraSelector: CameraSelector
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var cameraExecutor: ExecutorService

    // c........
//    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null


    private lateinit var faceDetector: FaceDetector

    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
//            .enableTracking() // optional: helps detect same face across frames
            .build()

        faceDetector = FaceDetection.getClient(options)
//        val faceDetector = FaceDetection.getClient(options)
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewBinding = ActivityCameraBinding.inflate(layoutInflater)

        setContentView(viewBinding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val verify = intent.getBooleanExtra("verify", false)
        if(verify) {
            viewBinding.Upload.text = "Verify"
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            // ✅ Initialize faceDetector here
            setupFaceDetector()
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener {
//            takePhoto()
            capturePhotoInMemory()
        }

        viewBinding.Retake.setOnClickListener {
            viewBinding.imageView.visibility = View.GONE
            viewBinding.Retake.visibility = View.GONE
            viewBinding.Upload.visibility = View.GONE

            viewBinding.viewFinder.visibility = View.VISIBLE
            viewBinding.imageCaptureButton.visibility = View.VISIBLE
            viewBinding.switchCamera.visibility = View.VISIBLE

            startCamera()
        }

        viewBinding.Upload.setOnClickListener {
            if (capturedBitmap == null) {
                Toast.makeText(this, "Please capture a face first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Initialize FaceNet model
            val faceNet = FaceNetModel(this)

            // Get face embedding (128-d float array)
            val faceEmbedding: FloatArray = faceNet.getFaceEmbedding(capturedBitmap!!)
            if(!verify) {

                // (Optional) Print or log the face embedding
                Log.d("FaceEmbedding", faceEmbedding.joinToString())

                // TODO: Save `faceEmbedding` to Firebase or local DB (if you wish to compare later)
                saveFaceDataToFirebase(this, faceEmbedding)

                // Just showing a toast for now
                Toast.makeText(this, "Face embedding generated and ready!", Toast.LENGTH_SHORT).show()

                // You can navigate or finish here if needed
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()

//                val userId = FirebaseAuth.getInstance().currentUser?.uid
            }
            else {
                val userId = FirebaseAuth.getInstance().currentUser
                if (userId != null) {
                    val userId = userId.uid
                    checkFaceMatchFromFirebase(userId, faceEmbedding, this) { isMatch ->
                        if (isMatch) {
                            // Proceed to login
                            Toast.makeText(this, "Face verified successfully", Toast.LENGTH_SHORT)
                                .show()
                            val intent = Intent(this, Scanner::class.java)
                            startActivity(intent)
                            finish()
                        } else {
                            // Block login
                            Toast.makeText(this, "Face mismatch! Try again.", Toast.LENGTH_SHORT)
                                .show()
                            val intent = Intent(this, MainActivity::class.java)
                            startActivity(intent)
                            finish()
                        }
                    }

                } else {
                    Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
                }
            }


        }
//        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        viewBinding.switchCamera.setOnClickListener {
            lensFacing = if(lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            }
            else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
//            bindCameraUserCases()
//            Toast.makeText(this, "Hello1", Toast.LENGTH_SHORT).show()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            // Image analysis use case for face detection
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer())
                }

            // Select back camera as a default
            cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                /// Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview,imageCapture, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                setupFaceDetector()
                startCamera()
            }
        }

    inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image ?: run {
                imageProxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    val faceFound = faces.size == 1 && isFaceFrontal(faces[0])
                    runOnUiThread {
//                        viewBinding.greenTick.visibility = if (faceFound) View.VISIBLE else View.GONE
//                        viewBinding.imageCaptureButton.visibility = View.VISIBLE
//                        viewBinding.imageCaptureButton.isEnabled = faceFound
                        if (viewBinding.imageCaptureButton.visibility == View.VISIBLE) {
                            viewBinding.imageCaptureButton.isEnabled = faceFound
                        }
                    }
                }
                .addOnFailureListener {
                    runOnUiThread {
//                        viewBinding.greenTick.visibility = View.GONE
                        viewBinding.imageCaptureButton.isEnabled = false
//                        viewBinding.imageCaptureButton.visibility = View.GONE
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun isFaceFrontal(face: Face): Boolean {
        val headEulerAngleY = face.headEulerAngleY  // Head turned left/right
        val headEulerAngleZ = face.headEulerAngleZ  // Head tilted sideways

        // Allow only small rotations to consider it frontal
        val isFrontalY = headEulerAngleY in -10.0..10.0
        val isFrontalZ = headEulerAngleZ in -10.0..10.0

        return isFrontalY && isFrontalZ
    }


    private fun capturePhotoInMemory() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    runOnUiThread {
                        capturedBitmap = bitmap
                        viewBinding.imageView.setImageBitmap(bitmap)

                        cameraProvider.unbindAll()

                        // Toggle visibility
                        viewBinding.imageView.visibility = View.VISIBLE
                        viewBinding.Retake.visibility = View.VISIBLE
                        viewBinding.Upload.visibility = View.VISIBLE

                        viewBinding.viewFinder.visibility = View.GONE
                        viewBinding.imageCaptureButton.visibility = View.GONE
                        viewBinding.switchCamera.visibility = View.GONE
                    }
                    image.close()
//                    viewBinding.imageCaptureButton.visibility = View.GONE
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@CameraActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val planeProxy = imageProxy.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun saveFaceDataToFirebase(context: Context, faceEmbedding: FloatArray) {
        val faceDataString = faceEmbedding.joinToString(",")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val uid = currentUser.uid
            val databaseRef = FirebaseDatabase.getInstance().getReference("users").child(uid)

            val updates = mapOf<String, Any>(
                "faceDate" to faceDataString
            )

            databaseRef.updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(context, "Face data saved successfully", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "Failed to save face data: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(context, "User not logged in", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkFaceMatchFromFirebase(
        userId: String,
        newEmbedding: FloatArray,
        context: Context,
        onResult: (Boolean) -> Unit
    ) {
        val database = FirebaseDatabase.getInstance()
        val reference = database.getReference("users").child(userId).child("faceDate")

        reference.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val storedEmbeddingString = snapshot.getValue(String::class.java)
                if (storedEmbeddingString != null) {
                    val storedEmbedding = parseEmbeddingString(storedEmbeddingString)
                    val similarity = cosineSimilarity(storedEmbedding, newEmbedding)

                    val threshold = 0.7f
                    val isMatch = similarity >= threshold

                    onResult(isMatch)
                } else {
                    Toast.makeText(context, "No face data found for user", Toast.LENGTH_SHORT).show()
                    onResult(false)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Error fetching data: ${error.message}", Toast.LENGTH_SHORT).show()
                onResult(false)
            }
        })
    }

    fun parseEmbeddingString(embeddingString: String): FloatArray {
        return embeddingString
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().toFloat() }
            .toFloatArray()
    }

    fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "Vectors must be the same length" }

        var dotProduct = 0f
        var normVec1 = 0f
        var normVec2 = 0f

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            normVec1 += vec1[i] * vec1[i]
            normVec2 += vec2[i] * vec2[i]
        }

        return dotProduct / (sqrt(normVec1) * sqrt(normVec2))
    }


}
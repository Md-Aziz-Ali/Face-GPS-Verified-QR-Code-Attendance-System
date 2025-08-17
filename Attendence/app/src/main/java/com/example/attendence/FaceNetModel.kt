package com.example.attendence

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import android.graphics.Bitmap
import kotlin.collections.toFloatArray
import kotlin.math.sqrt
import androidx.core.graphics.scale

class FaceNetModel(context: Context) {
    private var interpreter: Interpreter
    private val inputImageSize = 112
    private val modelPath = "mobile_face_net.tflite"
    private val embeddingDim = 192

    init {
        val model = loadModelFile(context)
        interpreter = Interpreter(model)
    }

    private fun loadModelFile(context: Context): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun getFaceEmbedding(bitmap: Bitmap): FloatArray {
        val resized = bitmap.scale(inputImageSize, inputImageSize)
        val input = preprocessImage(resized)
        val embedding = Array(1) { FloatArray(embeddingDim) }
        interpreter.run(input, embedding)
        return l2Normalize(embedding[0])
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputImageSize * inputImageSize * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputImageSize * inputImageSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixelValue in intValues) {
            val r = ((pixelValue shr 16) and 0xFF) / 128.0f - 1.0f
            val g = ((pixelValue shr 8) and 0xFF) / 128.0f - 1.0f
            val b = (pixelValue and 0xFF) / 128.0f - 1.0f

            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        return byteBuffer
    }

    private fun l2Normalize(embedding: FloatArray): FloatArray {
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        return embedding.map { it / norm }.toFloatArray()
    }

}

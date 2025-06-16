package com.example.roadradar

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.tasks.await

class ObjectDetector() {
    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)  // For real-time
        .enableClassification()  // Enable vehicle/car classification
        .build()

    private val detector = ObjectDetection.getClient(options)

    /**
     * Detect vehicles in the provided bitmap image
     * @param bitmap The image to analyze
     * @return List of detected objects
     */
    @OptIn(ExperimentalGetImage::class)
    suspend fun detectVehicles(bitmap: Bitmap): List<DetectedObject> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            detector.process(image).await()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
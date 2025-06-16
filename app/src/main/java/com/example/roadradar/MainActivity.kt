package com.example.roadradar

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.roadradar.ui.theme.RoadRadarTheme
import com.google.mlkit.vision.objects.DetectedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetector: ObjectDetector
    private val vehicleSpeed = MutableStateFlow(0.0)
    private val speedCalculator = SpeedCalculator()
    private lateinit var overlay: BoundingBoxOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()
        objectDetector = ObjectDetector(this)
        overlay = BoundingBoxOverlay(this)

        setContent {
            RoadRadarTheme {
                var showCalibrationScreen by remember { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize()){ innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        CameraPreview(
                            cameraExecutor = cameraExecutor,
                            objectDetector = objectDetector,
                            speedCalculator = speedCalculator,
                            vehicleSpeed = vehicleSpeed,
                            overlay = overlay
                        )
                        AndroidView(
                            factory = { context -> overlay },
                            modifier = Modifier.matchParentSize() // Make overlay fill the same space as the preview
                        )
                        SpeedDisplay(vehicleSpeed)
                        // Calibration button
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        ) {
                            IconButton(onClick = { showCalibrationScreen = !showCalibrationScreen }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Calibration Settings",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        // Calibration Settings Screen (conditionally displayed)
                        AnimatedVisibility(visible = showCalibrationScreen) {
                            // Ensure you have defined CalibrationSettingsScreen composable
                            CalibrationSettingsScreen(
                                speedCalculator = speedCalculator,
                                onBackPressed = { showCalibrationScreen = false }

                            )
                        }
                    }
                }
            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CalibrationControls(
    speedCalculator: SpeedCalculator,
    modifier: Modifier = Modifier
) {
    var calibrationValue by remember { mutableStateOf(100f) }

    Column(modifier = modifier.background(Color.White.copy(alpha = 0.7f))) {
        Text("Calibration (px/meter)")
        Slider(
            value = calibrationValue,
            onValueChange = {
                calibrationValue = it
                speedCalculator.setCalibration(it)
            },
            valueRange = 50f..500f
        )
        Text("%.1f px/m".format(calibrationValue))
    }
}

@Composable
private fun CameraPreview(
    cameraExecutor: ExecutorService,
    objectDetector: ObjectDetector,
    speedCalculator: SpeedCalculator,
    vehicleSpeed: MutableStateFlow<Double>,
    overlay: BoundingBoxOverlay
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Remember the camera provider to properly manage its lifecycle
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            // Ensure we clean up when this composable is disposed
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)


            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()

                    // Set up the preview use case
                    val preview = CameraPreview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    // Set up the image analysis use case
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, VehicleAnalyzer(objectDetector, speedCalculator, vehicleSpeed, overlay))
                        }

                    // Unbind any bound use cases before rebinding
                    cameraProvider.unbindAll()

                    // Bind use cases to camera
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
@SuppressLint("DefaultLocale")
@Composable
fun SpeedDisplay(vehicleSpeed: StateFlow<Double>) {
    val speed by vehicleSpeed.collectAsState()

    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "${String.format("%.1f", speed)} km/h",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}

class VehicleAnalyzer(
    private val objectDetector: ObjectDetector,
    private val speedCalculator: SpeedCalculator,
    private val vehicleSpeed: MutableStateFlow<Double>,
    private val overlay: BoundingBoxOverlay
) : ImageAnalysis.Analyzer {

    private var lastFrameTimestamp: Long = 0
    private var lastVehicleBoundingBox: Rect? = null


    override fun analyze(imageProxy: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        val bitmap = imageProxy.toBitmap()  // Convert ImageProxy to Bitmap
        val imageWidth = imageProxy.width
        val imageHeight = imageProxy.height

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val detectedObjects = objectDetector.detectVehicles(bitmap)
                overlay.setDetectedObjects(detectedObjects, imageWidth, imageHeight)
                // Look for the largest vehicle in the frame (likely the closest one)
                val largestVehicle = findLargestVehicle(detectedObjects)

                // If we found a vehicle, calculate its speed
                largestVehicle?.let { vehicle : DetectedObject ->
                    val boundingBox = vehicle.boundingBox

                    // If we have a previous bounding box, we can calculate speed
                    lastVehicleBoundingBox?.let { lastBox ->
                        if (lastFrameTimestamp > 0) {
                            val timeDelta = (currentTimestamp - lastFrameTimestamp) / 1000.0 // Convert to seconds

                            // Calculate speed based on change in bounding box
                            val speed = speedCalculator.calculateSpeed(
                                previousBox = lastBox,
                                currentBox = boundingBox,
                                imageWidth = imageProxy.width,
                                imageHeight = imageProxy.height,
                                timeDeltaSeconds = timeDelta
                            )

                            vehicleSpeed.value = speed
                        }
                    }

                    // Update last bounding box and timestamp
                    lastVehicleBoundingBox = boundingBox
                    lastFrameTimestamp = currentTimestamp
                } ?: run {
                    // No vehicle detected in this frame
                    if (currentTimestamp - lastFrameTimestamp > 2000) { // Reset after 2 seconds with no detection
                        lastVehicleBoundingBox = null
                        vehicleSpeed.value = 0.0
                    }
                }
            } catch (e: Exception) {
                Log.e("VehicleAnalyzer", "Error analyzing image", e)
            } finally {
                imageProxy.close()  // Always close the image!
            }
        }
    }

    // Find the largest detected vehicle in the frame (likely the closest one)
    private fun findLargestVehicle(objects: List<DetectedObject>): DetectedObject? {
        var largestVehicle: DetectedObject? = null
        var largestArea = 0

        objects.forEach { obj ->
            // Check if this is a vehicle
            val isVehicle = obj.labels.any {
                (it.text.lowercase() == "object" ||
                        it.text.lowercase() == "truck" ||
                        it.text.lowercase() == "bus") &&
                        it.confidence > 0.6f
            }

            if (isVehicle) {
                val box = obj.boundingBox
                val area = box.width() * box.height()

                if (area > largestArea) {
                    largestArea = area
                    largestVehicle = obj
                }
            }
        }

        return largestVehicle
    }

}

@Composable
fun PreviewCameraPreview() {
    // Simulated camera preview area
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Camera Preview Simulation",
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

@Preview(showBackground = true, device = "id:pixel_5")
@Composable
fun DefaultPreview() {
    val simulatedSpeed = remember { MutableStateFlow(72.5) }

    RoadRadarTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            // Simulated camera preview
            PreviewCameraPreview()

            // Speed display overlay
            SpeedDisplay(vehicleSpeed = simulatedSpeed)

            // Add additional UI elements that exist in your real app
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            ) {
                Text(
                    text = "RoadRadar",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Add calibration mock button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(
                        Color.White.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Text(
                    text = "Calibrate",
                    modifier = Modifier.padding(8.dp),
                    color = Color.White
                )
            }
        }
    }
}
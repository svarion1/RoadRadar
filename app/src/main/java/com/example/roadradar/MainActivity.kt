package com.example.roadradar

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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

    // Shared mutable state so VehicleAnalyzer can push a frozen frame into the wizard
    val frozenFrame = MutableStateFlow<Bitmap?>(null)
    val captureNextFrame = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()
        objectDetector = ObjectDetector()
        overlay = BoundingBoxOverlay(this)

        // Load the active calibration profile on startup
        CalibrationProfileStore.loadActive(this)?.let { profile ->
            speedCalculator.loadCalibrationProfile(profile)
        }

        setContent {
            RoadRadarTheme {
                val context = LocalContext.current
                var showCalibrationSettings by remember { mutableStateOf(false) }
                var showCalibrationWizard   by remember { mutableStateOf(false) }
                val frozen by frozenFrame.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {

                        // Camera preview
                        CameraPreviewComposable(
                            cameraExecutor = cameraExecutor,
                            objectDetector = objectDetector,
                            speedCalculator = speedCalculator,
                            vehicleSpeed = vehicleSpeed,
                            overlay = overlay,
                            captureNextFrame = captureNextFrame,
                            onFrameCaptured = { bmp -> frozenFrame.value = bmp }
                        )
                        AndroidView(
                            factory = { _ -> overlay },
                            modifier = Modifier.matchParentSize()
                        )
                        SpeedDisplay(vehicleSpeed)

                        // ── Top-right toolbar ──────────────────────────────────────
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        ) {
                            // Wizard button
                            IconButton(
                                onClick = { showCalibrationWizard = true },
                                modifier = Modifier.padding(end = 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Calibration Wizard",
                                    tint = Color.Yellow,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            // Legacy settings button
                            IconButton(onClick = { showCalibrationSettings = !showCalibrationSettings }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Calibration Settings",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        // ── Legacy calibration sliders ─────────────────────────────
                        AnimatedVisibility(visible = showCalibrationSettings) {
                            CalibrationSettingsScreen(
                                speedCalculator = speedCalculator,
                                onBackPressed = { showCalibrationSettings = false }
                            )
                        }

                        // ── New calibration wizard ─────────────────────────────────
                        AnimatedVisibility(visible = showCalibrationWizard) {
                            CalibrationWizardScreen(
                                frozenFrame = frozen,
                                onCaptureFrame = { captureNextFrame.value = true },
                                onSaveProfile = { profile ->
                                    speedCalculator.loadCalibrationProfile(profile)
                                    showCalibrationWizard = false
                                },
                                onDismiss = {
                                    frozenFrame.value = null
                                    showCalibrationWizard = false
                                }
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
private fun CameraPreviewComposable(
    cameraExecutor: ExecutorService,
    objectDetector: ObjectDetector,
    speedCalculator: SpeedCalculator,
    vehicleSpeed: MutableStateFlow<Double>,
    overlay: BoundingBoxOverlay,
    captureNextFrame: MutableStateFlow<Boolean>,
    onFrameCaptured: (Bitmap) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try { cameraProviderFuture.get().unbindAll() } catch (e: Exception) { e.printStackTrace() }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = CameraPreview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(
                                cameraExecutor,
                                VehicleAnalyzer(
                                    objectDetector, speedCalculator, vehicleSpeed, overlay,
                                    captureNextFrame, onFrameCaptured
                                )
                            )
                        }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
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
    private val overlay: BoundingBoxOverlay,
    private val captureNextFrame: MutableStateFlow<Boolean>,
    private val onFrameCaptured: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastFrameTimestamp: Long = 0
    private var lastVehicleBoundingBox: Rect? = null

    override fun analyze(imageProxy: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        val bitmap = imageProxy.toBitmap()
        val imageWidth = imageProxy.width

        // Capture frame for wizard if requested
        if (captureNextFrame.value) {
            captureNextFrame.value = false
            onFrameCaptured(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false))
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val detectedObjects = objectDetector.detectVehicles(bitmap)
                overlay.setDetectedObjects(detectedObjects, imageWidth, imageProxy.height)

                val largestVehicle = findLargestVehicle(detectedObjects)
                largestVehicle?.let { vehicle ->
                    val boundingBox = vehicle.boundingBox
                    lastVehicleBoundingBox?.let { lastBox ->
                        if (lastFrameTimestamp > 0) {
                            val timeDelta = (currentTimestamp - lastFrameTimestamp) / 1000.0
                            val speed = speedCalculator.calculateSpeed(
                                previousBox = lastBox,
                                currentBox = boundingBox,
                                imageWidth = imageWidth,
                                timeDeltaSeconds = timeDelta
                            )
                            vehicleSpeed.value = speed
                        }
                    }
                    lastVehicleBoundingBox = boundingBox
                    lastFrameTimestamp = currentTimestamp
                } ?: run {
                    if (currentTimestamp - lastFrameTimestamp > 2000) {
                        lastVehicleBoundingBox = null
                        vehicleSpeed.value = 0.0
                    }
                }
            } catch (e: Exception) {
                Log.e("VehicleAnalyzer", "Error analyzing image", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun findLargestVehicle(objects: List<DetectedObject>): DetectedObject? {
        var largestVehicle: DetectedObject? = null
        var largestArea = 0
        objects.forEach { obj ->
            val isVehicle = obj.labels.any {
                (it.text.lowercase() == "object" ||
                        it.text.lowercase() == "truck" ||
                        it.text.lowercase() == "bus") && it.confidence > 0.6f
            }
            if (isVehicle) {
                val area = obj.boundingBox.width() * obj.boundingBox.height()
                if (area > largestArea) { largestArea = area; largestVehicle = obj }
            }
        }
        return largestVehicle
    }
}

@Composable
fun PreviewCameraPreview() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text("Camera Preview Simulation", color = Color.White, fontSize = 16.sp)
    }
}

@Preview(showBackground = true, device = "id:pixel_5")
@Composable
fun DefaultPreview() {
    val simulatedSpeed = remember { MutableStateFlow(72.5) }
    RoadRadarTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            PreviewCameraPreview()
            SpeedDisplay(vehicleSpeed = simulatedSpeed)
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
            ) {
                Text("RoadRadar", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(Color.White.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp))
            ) {
                Text("Calibrate", modifier = Modifier.padding(8.dp), color = Color.White)
            }
        }
    }
}

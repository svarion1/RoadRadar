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
    private val vehicleSpeed = MutableStateFlow(0.0)   // max speed across all tracks (HUD)
    private val vehicleTracker = VehicleTracker()
    private lateinit var overlay: BoundingBoxOverlay

    val frozenFrame = MutableStateFlow<Bitmap?>(null)
    val captureNextFrame = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()
        objectDetector = ObjectDetector()
        overlay = BoundingBoxOverlay(this)

        // Load active calibration profile into the tracker on startup
        CalibrationProfileStore.loadActive(this)?.let { profile ->
            vehicleTracker.loadCalibrationProfile(profile)
        }

        setContent {
            RoadRadarTheme {
                val context = LocalContext.current
                var showCalibrationSettings by remember { mutableStateOf(false) }
                var showCalibrationWizard   by remember { mutableStateOf(false) }
                val frozen by frozenFrame.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {

                        CameraPreviewComposable(
                            cameraExecutor = cameraExecutor,
                            objectDetector = objectDetector,
                            vehicleTracker = vehicleTracker,
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

                        // ── Top-right toolbar ──────────────────────────────────────────
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

                        // ── Legacy calibration sliders ─────────────────────────────────
                        AnimatedVisibility(visible = showCalibrationSettings) {
                            // Create a thin SpeedCalculator facade so CalibrationSettingsScreen
                            // still compiles unchanged; its pixel-ratio changes are minor vs.
                            // the homography but useful as a rough fallback.
                            val facadeCalc = remember { SpeedCalculator() }
                            CalibrationSettingsScreen(
                                speedCalculator = facadeCalc,
                                onBackPressed = { showCalibrationSettings = false }
                            )
                        }

                        // ── New calibration wizard ─────────────────────────────────────
                        AnimatedVisibility(visible = showCalibrationWizard) {
                            CalibrationWizardScreen(
                                frozenFrame = frozen,
                                onCaptureFrame = { captureNextFrame.value = true },
                                onSaveProfile = { profile ->
                                    vehicleTracker.loadCalibrationProfile(profile)
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
    vehicleTracker: VehicleTracker,
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
                                    objectDetector, vehicleTracker, vehicleSpeed, overlay,
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
    private val vehicleTracker: VehicleTracker,
    private val vehicleSpeed: MutableStateFlow<Double>,
    private val overlay: BoundingBoxOverlay,
    private val captureNextFrame: MutableStateFlow<Boolean>,
    private val onFrameCaptured: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        val timestampMs = System.currentTimeMillis()
        val bitmap = imageProxy.toBitmap()
        val imageWidth  = imageProxy.width
        val imageHeight = imageProxy.height

        // Capture frame for wizard if requested
        if (captureNextFrame.value) {
            captureNextFrame.value = false
            onFrameCaptured(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false))
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val detectedObjects = objectDetector.detectVehicles(bitmap)

                // Build (trackingId, boundingBox) pairs — only for vehicle-classified objects
                val vehicleDetections = detectedObjects
                    .filter { obj ->
                        obj.labels.any {
                            (it.text.lowercase() == "object" ||
                                    it.text.lowercase() == "truck" ||
                                    it.text.lowercase() == "bus") && it.confidence > 0.6f
                        }
                    }
                    .mapNotNull { obj ->
                        val id = obj.trackingId ?: return@mapNotNull null
                        Pair(id, obj.boundingBox)
                    }

                // Update tracker — get per-vehicle speeds
                val trackSpeeds = vehicleTracker.update(
                    detections = vehicleDetections,
                    imageWidth  = imageWidth,
                    timestampMs = timestampMs
                )

                // HUD shows the highest speed currently detected
                val maxSpeed = trackSpeeds.values.maxOrNull() ?: 0.0
                vehicleSpeed.value = maxSpeed

                // Pass full object list + speeds to overlay
                overlay.setDetectedObjects(
                    objects     = detectedObjects,
                    imageWidth  = imageWidth,
                    imageHeight = imageHeight,
                    speeds      = trackSpeeds
                )

            } catch (e: Exception) {
                Log.e("VehicleAnalyzer", "Error analyzing image", e)
            } finally {
                imageProxy.close()
            }
        }
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
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
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

package com.example.roadradar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val PREFS_UI    = "road_radar_ui_prefs"
private const val KEY_SHOW_GRID = "show_calibration_grid"

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetector: ObjectDetector
    private val vehicleSpeed   = MutableStateFlow(0.0)
    private val vehicleTracker = VehicleTracker()
    private lateinit var overlay: BoundingBoxOverlay

    val frozenFrame      = MutableStateFlow<Bitmap?>(null)
    val captureNextFrame = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()
        objectDetector = ObjectDetector()
        overlay        = BoundingBoxOverlay(this)

        CalibrationProfileStore.loadActive(this)?.let { profile ->
            vehicleTracker.loadCalibrationProfile(profile)
        }

        setContent {
            RoadRadarTheme {
                val context = LocalContext.current
                val uiPrefs = remember { context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE) }

                var showCalibrationSettings  by remember { mutableStateOf(false) }
                var showCalibrationWizard    by remember { mutableStateOf(false) }
                var showProfileManager       by remember { mutableStateOf(false) }
                var showCalibrationGrid      by remember { mutableStateOf(uiPrefs.getBoolean(KEY_SHOW_GRID, false)) }
                var activeCalibrationProfile by remember { mutableStateOf<CalibrationProfile?>(null) }
                val frozen by frozenFrame.collectAsState()

                LaunchedEffect(Unit) {
                    activeCalibrationProfile = CalibrationProfileStore.loadActive(context)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {

                        CameraPreviewComposable(
                            cameraExecutor   = cameraExecutor,
                            objectDetector   = objectDetector,
                            vehicleTracker   = vehicleTracker,
                            vehicleSpeed     = vehicleSpeed,
                            overlay          = overlay,
                            captureNextFrame = captureNextFrame,
                            onFrameCaptured  = { bmp -> frozenFrame.value = bmp }
                        )

                        AndroidView(
                            factory  = { _ -> overlay },
                            modifier = Modifier.matchParentSize()
                        )

                        if (showCalibrationGrid) {
                            CalibrationGridOverlay(
                                profile  = activeCalibrationProfile,
                                modifier = Modifier.matchParentSize()
                            )
                        }

                        SpeedDisplay(vehicleSpeed)

                        activeCalibrationProfile?.let { profile ->
                            CalibrationConfidenceBadge(
                                profile  = profile,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(top = 12.dp, start = 12.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp)
                        ) {
                            Row {
                                IconButton(
                                    onClick = {
                                        val next = !showCalibrationGrid
                                        showCalibrationGrid = next
                                        uiPrefs.edit().putBoolean(KEY_SHOW_GRID, next).apply()
                                    },
                                    enabled = activeCalibrationProfile != null
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GridOn,
                                        contentDescription = "Toggle calibration grid",
                                        tint = when {
                                            activeCalibrationProfile == null -> Color.Gray
                                            showCalibrationGrid             -> Color.Cyan
                                            else                            -> Color.White
                                        },
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                IconButton(onClick = { showProfileManager = true }) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = "Manage profiles",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                IconButton(onClick = { showCalibrationWizard = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Calibration Wizard",
                                        tint = Color.Yellow,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                IconButton(onClick = { showCalibrationSettings = !showCalibrationSettings }) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Calibration Settings",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = showCalibrationSettings) {
                            val facadeCalc = remember { SpeedCalculator() }
                            CalibrationSettingsScreen(
                                speedCalculator = facadeCalc,
                                onBackPressed   = { showCalibrationSettings = false }
                            )
                        }

                        AnimatedVisibility(visible = showCalibrationWizard) {
                            CalibrationWizardScreen(
                                frozenFrame    = frozen,
                                onCaptureFrame = { captureNextFrame.value = true },
                                onSaveProfile  = { profile ->
                                    vehicleTracker.loadCalibrationProfile(profile)
                                    activeCalibrationProfile = profile
                                    showCalibrationWizard    = false
                                },
                                onDismiss = {
                                    frozenFrame.value     = null
                                    showCalibrationWizard = false
                                }
                            )
                        }

                        AnimatedVisibility(visible = showProfileManager) {
                            CalibrationProfileManagerScreen(
                                activeProfileName = activeCalibrationProfile?.name,
                                onActivate        = { profile ->
                                    vehicleTracker.loadCalibrationProfile(profile)
                                    activeCalibrationProfile = profile
                                    showProfileManager       = false
                                },
                                onBack = { showProfileManager = false }
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

// ── Confidence badge ───────────────────────────────────────────────────────────────

@Composable
fun CalibrationConfidenceBadge(profile: CalibrationProfile, modifier: Modifier = Modifier) {
    val color = qualityColor(profile.reprojectionErrorPx)
    val label = qualityLabel(profile.reprojectionErrorPx)
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(profile.name,  fontSize = 11.sp, color = Color.White,  fontWeight = FontWeight.Medium,    maxLines = 1)
        Text("· $label",    fontSize = 11.sp, color = color,         fontWeight = FontWeight.SemiBold)
    }
}

// ── Camera preview ─────────────────────────────────────────────────────────────────

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

// ── Speed HUD ──────────────────────────────────────────────────────────────────────

@SuppressLint("DefaultLocale")
@Composable
fun SpeedDisplay(vehicleSpeed: StateFlow<Double>) {
    val speed by vehicleSpeed.collectAsState()
    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
        Text(
            text = "${String.format("%.1f", speed)} km/h",
            color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}

// ── Vehicle analyzer ────────────────────────────────────────────────────────────────

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
        val bitmap      = imageProxy.toBitmap()
        val imageWidth  = imageProxy.width
        val imageHeight = imageProxy.height

        if (captureNextFrame.value) {
            captureNextFrame.value = false
            // Rotate the bitmap so it is upright on the portrait screen.
            // CameraX reports rotation in degrees; we apply the same rotation
            // to the captured Bitmap so the wizard always shows a portrait image.
            val rotated = rotateBitmapForDisplay(
                bitmap,
                imageProxy.imageInfo.rotationDegrees
            )
            onFrameCaptured(rotated)
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val detectedObjects = objectDetector.detectVehicles(bitmap)
                val vehicleDetections = detectedObjects
                    .filter { obj ->
                        obj.labels.any {
                            (it.text.lowercase() == "object" ||
                             it.text.lowercase() == "truck"  ||
                             it.text.lowercase() == "bus")   && it.confidence > 0.6f
                        }
                    }
                    .mapNotNull { obj ->
                        val id = obj.trackingId ?: return@mapNotNull null
                        Pair(id, obj.boundingBox)
                    }
                val trackSpeeds = vehicleTracker.update(
                    detections  = vehicleDetections,
                    imageWidth  = imageWidth,
                    timestampMs = timestampMs
                )
                vehicleSpeed.value = trackSpeeds.values.maxOrNull() ?: 0.0
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

/**
 * Rotates [bitmap] by [rotationDegrees] (as reported by CameraX ImageProxy)
 * so the image is correctly oriented for portrait display.
 * Returns the original bitmap unchanged if rotation is 0.
 */
fun rotateBitmapForDisplay(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

// ── Compose previews ─────────────────────────────────────────────────────────────────

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
                    .align(Alignment.BottomStart).padding(16.dp)
                    .background(Color.White.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp))
            ) {
                Text("Calibrate", modifier = Modifier.padding(8.dp), color = Color.White)
            }
        }
    }
}

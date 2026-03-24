package com.example.roadradar

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 4-step calibration wizard:
 *
 *  Step 0 – Capture: freeze the live camera frame
 *  Step 1 – Pin:     user drags 4 pins onto known road features
 *  Step 2 – Measure: user enters the real-world distances (metres)
 *  Step 3 – Validate: show reprojection error + save / discard
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationWizardScreen(
    frozenFrame: Bitmap?,
    onCaptureFrame: () -> Unit,
    onSaveProfile: (CalibrationProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    var pins by remember {
        mutableStateOf(
            listOf(
                HomographyCalibrator.Point2f(0.2f, 0.2f),
                HomographyCalibrator.Point2f(0.8f, 0.2f),
                HomographyCalibrator.Point2f(0.8f, 0.8f),
                HomographyCalibrator.Point2f(0.2f, 0.8f)
            )
        )
    }

    var topWidthStr    by remember { mutableStateOf("") }
    var bottomWidthStr by remember { mutableStateOf("") }
    var leftDepthStr   by remember { mutableStateOf("") }

    var computedProfile by remember { mutableStateOf<CalibrationProfile?>(null) }
    var profileName     by remember { mutableStateOf("My Setup") }
    var inputError      by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration Wizard — Step ${step + 1} / 4") },
                navigationIcon = {
                    IconButton(onClick = { if (step == 0) onDismiss() else step-- }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.padding(padding),
            label = "wizard_step"
        ) { currentStep ->
            when (currentStep) {
                0 -> CaptureStep(
                    frozenFrame = frozenFrame,
                    onCapture = { onCaptureFrame() },
                    onNext = { if (frozenFrame != null) step++ }
                )
                1 -> PinStep(
                    bitmap = frozenFrame!!,
                    pins = pins,
                    onPinsChanged = { pins = it },
                    onNext = { step++ }
                )
                2 -> MeasureStep(
                    topWidthStr = topWidthStr,
                    bottomWidthStr = bottomWidthStr,
                    leftDepthStr = leftDepthStr,
                    onTopWidthChange = { topWidthStr = it },
                    onBottomWidthChange = { bottomWidthStr = it },
                    onLeftDepthChange = { leftDepthStr = it },
                    error = inputError,
                    onNext = {
                        inputError = null
                        val tw = topWidthStr.toFloatOrNull()
                        val bw = bottomWidthStr.toFloatOrNull()
                        val ld = leftDepthStr.toFloatOrNull()
                        if (tw == null || bw == null || ld == null || tw <= 0 || bw <= 0 || ld <= 0) {
                            inputError = "Please enter valid positive numbers for all fields."
                        } else {
                            val worldPts = listOf(
                                HomographyCalibrator.Point2f(0f, 0f),
                                HomographyCalibrator.Point2f(tw, 0f),
                                HomographyCalibrator.Point2f(bw, ld),
                                HomographyCalibrator.Point2f(0f, ld)
                            )
                            val bitmapW = frozenFrame!!.width.toFloat()
                            val bitmapH = frozenFrame.height.toFloat()
                            val imagePts = pins.map {
                                HomographyCalibrator.Point2f(it.x * bitmapW, it.y * bitmapH)
                            }
                            val H = HomographyCalibrator.compute(imagePts, worldPts)
                            if (H == null) {
                                inputError = "Could not compute homography. Try repositioning your pins."
                            } else {
                                val error = HomographyCalibrator.reprojectionError(H, imagePts, worldPts)
                                computedProfile = CalibrationProfile(
                                    name = profileName,
                                    homography = H,
                                    imagePoints = imagePts,
                                    worldPoints = worldPts,
                                    reprojectionErrorPx = error
                                )
                                step++
                            }
                        }
                    }
                )
                3 -> ValidateStep(
                    profile = computedProfile,
                    profileName = profileName,
                    onProfileNameChange = { profileName = it },
                    onSave = {
                        computedProfile?.let {
                            val finalProfile = it.copy(name = profileName)
                            CalibrationProfileStore.save(context, finalProfile)
                            CalibrationProfileStore.setActive(context, profileName)
                            onSaveProfile(finalProfile)
                        }
                    },
                    onDiscard = onDismiss
                )
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────────
// Step composables
// ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun CaptureStep(
    frozenFrame: Bitmap?,
    onCapture: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeader(
            title = "Step 1: Capture a Frame",
            description = "Point the camera at the road and tap Capture when the scene looks good. " +
                    "Aim to include visible lane markings, road edge, or any feature whose real size you know."
        )
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .background(Color.DarkGray, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (frozenFrame != null) {
                Image(
                    bitmap = frozenFrame.asImageBitmap(),
                    contentDescription = "Captured frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("Live preview — tap Capture", color = Color.White)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCapture, modifier = Modifier.weight(1f)) {
                Text(if (frozenFrame == null) "Capture" else "Re-Capture")
            }
            Button(onClick = onNext, enabled = frozenFrame != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Next")
            }
        }
    }
}

@Composable
private fun PinStep(
    bitmap: Bitmap,
    pins: List<HomographyCalibrator.Point2f>,
    onPinsChanged: (List<HomographyCalibrator.Point2f>) -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        StepHeader(
            title = "Step 2: Place Calibration Pins",
            description = "Drag each coloured pin to a known point on the road. " +
                    "\nRed=Top-Left  Green=Top-Right  Blue=Bottom-Right  Yellow=Bottom-Left",
            modifier = Modifier.padding(16.dp)
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
            CalibrationTouchOverlay(pins = pins, onPinsChanged = onPinsChanged)
        }
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Next — Enter Measurements")
        }
    }
}

@Composable
private fun MeasureStep(
    topWidthStr: String,
    bottomWidthStr: String,
    leftDepthStr: String,
    onTopWidthChange: (String) -> Unit,
    onBottomWidthChange: (String) -> Unit,
    onLeftDepthChange: (String) -> Unit,
    error: String?,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StepHeader(
            title = "Step 3: Enter Real-World Measurements",
            description = "Measure the distances between the pins you placed on the road and enter them below (in metres). " +
                    "For example: a standard Italian lane is 3.5 m wide."
        )
        MeasureField("Top Edge Width (Red → Green, metres)",   "e.g. 3.5", topWidthStr,    onTopWidthChange)
        MeasureField("Bottom Edge Width (Yellow → Blue, metres)", "e.g. 3.5", bottomWidthStr, onBottomWidthChange)
        MeasureField("Left Edge Depth (Red → Yellow, metres)",  "e.g. 6.0", leftDepthStr,   onLeftDepthChange)
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Compute Calibration")
        }
    }
}

@Composable
private fun ValidateStep(
    profile: CalibrationProfile?,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeader(
            title = "Step 4: Review & Save",
            description = "Check the calibration confidence score below. If the quality is Poor, " +
                    "go back and reposition your pins — ideally on clearly visible, well-spaced road markings."
        )

        profile?.let { p ->
            ConfidenceScoreCard(p.reprojectionErrorPx)
        }

        OutlinedTextField(
            value = profileName,
            onValueChange = onProfileNameChange,
            label = { Text("Profile Name") },
            placeholder = { Text("e.g. Window 2nd floor, angle 30\u00b0") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Save & Activate")
        }
        OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
            Text("Discard")
        }
    }
}

/**
 * Richer confidence score card reused in both wizard Step 4 and can
 * be imported in the profile manager card.
 */
@Composable
fun ConfidenceScoreCard(errorPx: Float, modifier: Modifier = Modifier) {
    val color = qualityColor(errorPx)
    val label = qualityLabel(errorPx)
    val explanation = when {
        errorPx < 5f  -> "The homography fits the 4 reference points very accurately. Speed readings will be reliable."
        errorPx < 15f -> "The fit is acceptable. Speed readings will be reasonably accurate. Consider re-calibrating for better results."
        else          -> "The fit is poor. Real-world measurements or pin placement may be off. Go back and try again."
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = color
                )
                Text(
                    text = "(%.2f px error)".format(errorPx),
                    fontSize = 14.sp,
                    color = color
                )
            }
            ConfidenceBar(errorPx = errorPx)
            Text(explanation, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

// ── Shared small composables ─────────────────────────────────────────────────────────────────

@Composable
private fun StepHeader(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(description, fontSize = 13.sp, color = Color.Gray, lineHeight = 18.sp)
    }
}

@Composable
private fun MeasureField(label: String, hint: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        placeholder = { Text(hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

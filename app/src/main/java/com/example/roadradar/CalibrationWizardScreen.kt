package com.example.roadradar

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
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

    // Step 1 – pins in fraction coords (0..1); default spread corners
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

    // Step 2 – real-world distances entered by user
    // We ask for: top edge width (TL→TR), bottom edge width (BL→BR), left edge depth (TL→BL)
    var topWidthStr   by remember { mutableStateOf("") }
    var bottomWidthStr by remember { mutableStateOf("") }
    var leftDepthStr  by remember { mutableStateOf("") }

    // Step 3 – computed result
    var computedProfile by remember { mutableStateOf<CalibrationProfile?>(null) }
    var profileName by remember { mutableStateOf("My Setup") }
    var inputError  by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration Wizard — Step ${step + 1} / 4") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step == 0) onDismiss() else step--
                    }) {
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
                // ── Step 0: Capture ──────────────────────────────────────────────────
                0 -> CaptureStep(
                    frozenFrame = frozenFrame,
                    onCapture = {
                        onCaptureFrame()
                        // advance automatically once frame is available
                    },
                    onNext = { if (frozenFrame != null) step++ }
                )

                // ── Step 1: Pin placement ────────────────────────────────────────────
                1 -> PinStep(
                    bitmap = frozenFrame!!,
                    pins = pins,
                    onPinsChanged = { pins = it },
                    onNext = { step++ }
                )

                // ── Step 2: Measure ──────────────────────────────────────────────────
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
                            // Build world points from the distances.
                            // Place TL at origin: (0, 0)
                            val worldPts = listOf(
                                HomographyCalibrator.Point2f(0f, 0f),           // TL
                                HomographyCalibrator.Point2f(tw, 0f),            // TR
                                HomographyCalibrator.Point2f(bw, ld),            // BR
                                HomographyCalibrator.Point2f(0f, ld)             // BL
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

                // ── Step 3: Validate & Save ──────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────────────
// Step composables
// ─────────────────────────────────────────────────────────────────────────────

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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
            Button(
                onClick = onNext,
                enabled = frozenFrame != null,
                modifier = Modifier.weight(1f)
            ) {
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
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StepHeader(
            title = "Step 2: Place Calibration Pins",
            description = "Drag each coloured pin to a known point on the road. " +
                    "\nRed=Top-Left  Green=Top-Right  Blue=Bottom-Right  Yellow=Bottom-Left",
            modifier = Modifier.padding(16.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
            CalibrationTouchOverlay(
                pins = pins,
                onPinsChanged = onPinsChanged
            )
        }
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StepHeader(
            title = "Step 3: Enter Real-World Measurements",
            description = "Measure the distances between the pins you placed on the road and enter them below (in metres). " +
                    "For example: the width of a lane is typically 3.5 m, a standard parking space is 2.5 m wide."
        )

        MeasureField(
            label = "Top Edge Width (Red → Green, metres)",
            hint = "e.g. 3.5",
            value = topWidthStr,
            onValueChange = onTopWidthChange
        )
        MeasureField(
            label = "Bottom Edge Width (Yellow → Blue, metres)",
            hint = "e.g. 3.5",
            value = bottomWidthStr,
            onValueChange = onBottomWidthChange
        )
        MeasureField(
            label = "Left Edge Depth (Red → Yellow, metres)",
            hint = "e.g. 6.0",
            value = leftDepthStr,
            onValueChange = onLeftDepthChange
        )

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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeader(
            title = "Step 4: Review & Save",
            description = "Check the reprojection error. Below 5 pixels is excellent; below 15 is good. " +
                    "If it's high, go back and reposition your pins more carefully."
        )

        profile?.let { p ->
            val errorColor = when {
                p.reprojectionErrorPx < 5f  -> Color(0xFF2E7D32)  // green
                p.reprojectionErrorPx < 15f -> Color(0xFFF57F17)  // amber
                else                        -> MaterialTheme.colorScheme.error
            }
            val quality = when {
                p.reprojectionErrorPx < 5f  -> "Excellent ✓"
                p.reprojectionErrorPx < 15f -> "Good"
                else                        -> "Poor — consider re-calibrating"
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Calibration Quality", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Reprojection error: ${"%.2f".format(p.reprojectionErrorPx)} px — $quality",
                        color = errorColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Homography matrix computed successfully.", fontSize = 13.sp)
                }
            }
        }

        OutlinedTextField(
            value = profileName,
            onValueChange = onProfileNameChange,
            label = { Text("Profile Name") },
            placeholder = { Text("e.g. Window 2nd floor") },
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

// ─────────────────────────────────────────────────────────────────────────────
// Shared small composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepHeader(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(description, fontSize = 13.sp, color = Color.Gray, lineHeight = 18.sp)
    }
}

@Composable
private fun MeasureField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        placeholder = { Text(hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

package com.example.roadradar

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
 * 4-step calibration wizard.
 *
 *  Step 0 – Capture   : freeze the live camera frame
 *  Step 1 – Pin       : IMMERSIVE fullscreen pin placement
 *  Step 2 – Measure   : enter real-world distances
 *  Step 3 – Validate  : review confidence score + save
 *
 * Layout adapts to landscape via [BoxWithConstraints] in Steps 0 and 2.
 * Step 1 is always fullscreen (no TopAppBar, no bottom bar).
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

    // Hide the TopAppBar during immersive PinStep so nothing obscures the image
    val showTopBar = step != 1

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = showTopBar,
                enter   = fadeIn(),
                exit    = fadeOut()
            ) {
                TopAppBar(
                    title = { Text("Calibration Wizard — Step ${step + 1} / 4") },
                    navigationIcon = {
                        IconButton(onClick = { if (step == 0) onDismiss() else step-- }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            // Only apply scaffold padding when TopAppBar is showing
            modifier = if (showTopBar) Modifier.padding(padding) else Modifier,
            label = "wizard_step"
        ) { currentStep ->
            when (currentStep) {
                0 -> CaptureStep(
                    frozenFrame = frozenFrame,
                    onCapture   = { onCaptureFrame() },
                    onNext      = { if (frozenFrame != null) step++ }
                )
                1 -> PinStepImmersive(
                    bitmap        = frozenFrame!!,
                    pins          = pins,
                    onPinsChanged = { pins = it },
                    onBack        = { step-- },
                    onNext        = { step++ }
                )
                2 -> MeasureStep(
                    topWidthStr       = topWidthStr,
                    bottomWidthStr    = bottomWidthStr,
                    leftDepthStr      = leftDepthStr,
                    onTopWidthChange  = { topWidthStr = it },
                    onBottomWidthChange = { bottomWidthStr = it },
                    onLeftDepthChange = { leftDepthStr = it },
                    error             = inputError,
                    onNext = {
                        inputError = null
                        val tw = topWidthStr.toFloatOrNull()
                        val bw = bottomWidthStr.toFloatOrNull()
                        val ld = leftDepthStr.toFloatOrNull()
                        if (tw == null || bw == null || ld == null || tw <= 0 || bw <= 0 || ld <= 0) {
                            inputError = "Please enter valid positive numbers for all fields."
                        } else {
                            val worldPts = listOf(
                                HomographyCalibrator.Point2f(0f,  0f),
                                HomographyCalibrator.Point2f(tw,  0f),
                                HomographyCalibrator.Point2f(bw,  ld),
                                HomographyCalibrator.Point2f(0f,  ld)
                            )
                            val bitmapW  = frozenFrame!!.width.toFloat()
                            val bitmapH  = frozenFrame.height.toFloat()
                            val imagePts = pins.map {
                                HomographyCalibrator.Point2f(it.x * bitmapW, it.y * bitmapH)
                            }
                            val H = HomographyCalibrator.compute(imagePts, worldPts)
                            if (H == null) {
                                inputError = "Could not compute homography. Try repositioning your pins."
                            } else {
                                val error = HomographyCalibrator.reprojectionError(H, imagePts, worldPts)
                                computedProfile = CalibrationProfile(
                                    name                = profileName,
                                    homography          = H,
                                    imagePoints         = imagePts,
                                    worldPoints         = worldPts,
                                    reprojectionErrorPx = error
                                )
                                step++
                            }
                        }
                    }
                )
                3 -> ValidateStep(
                    profile             = computedProfile,
                    profileName         = profileName,
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
// Step 1 — Immersive fullscreen pin placement
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fullscreen pin step with no chrome.
 *
 * Layout:
 *  - Image + [CalibrationTouchOverlay] fill the entire screen
 *  - Semi-transparent back button anchored top-start
 *  - Hint chip anchored top-center (fades to 40% alpha so it's readable
 *    but doesn't compete with the road image)
 *  - FAB anchored bottom-end to proceed
 */
@Composable
private fun PinStepImmersive(
    bitmap: Bitmap,
    pins: List<HomographyCalibrator.Point2f>,
    onPinsChanged: (List<HomographyCalibrator.Point2f>) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Full-bleed image — Fit keeps aspect ratio; black bars are fine
        Image(
            bitmap           = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier         = Modifier.fillMaxSize(),
            contentScale     = ContentScale.Fit
        )

        // Pin drag canvas on top
        CalibrationTouchOverlay(
            pins          = pins,
            onPinsChanged = onPinsChanged,
            modifier      = Modifier.fillMaxSize()
        )

        // ── Back button ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint               = Color.White
                )
            }
        }

        // ── Hint strip ─────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text       = "🔴 TL  🟢 TR  🔵 BR  🟡 BL — drag to road corners",
                fontSize   = 12.sp,
                color      = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium
            )
        }

        // ── FAB — bottom-end ────────────────────────────────────────────────
        ExtendedFloatingActionButton(
            onClick            = onNext,
            modifier           = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor     = MaterialTheme.colorScheme.primary,
            contentColor       = MaterialTheme.colorScheme.onPrimary,
            icon = {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            },
            text = { Text("Next") }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 0 — Capture (landscape-adaptive)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CaptureStep(
    frozenFrame: Bitmap?,
    onCapture: () -> Unit,
    onNext: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            // ── Landscape: image left, controls right ───────────────────────
            Row(
                modifier            = Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Preview
                Box(
                    modifier        = Modifier.weight(1f).fillMaxHeight()
                        .background(Color.DarkGray, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (frozenFrame != null) {
                        Image(
                            bitmap             = frozenFrame.asImageBitmap(),
                            contentDescription = "Captured frame",
                            modifier           = Modifier.fillMaxSize(),
                            contentScale       = ContentScale.Fit
                        )
                    } else {
                        Text("Live preview — tap Capture", color = Color.White, fontSize = 13.sp)
                    }
                }
                // Controls
                Column(
                    modifier              = Modifier.width(160.dp).fillMaxHeight(),
                    verticalArrangement   = Arrangement.Center,
                    horizontalAlignment   = Alignment.CenterHorizontally
                ) {
                    StepHeader(
                        title       = "Step 1: Capture",
                        description = "Point at the road and tap Capture when the scene looks good."
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onCapture, modifier = Modifier.fillMaxWidth()) {
                        Text(if (frozenFrame == null) "Capture" else "Re-Capture", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick  = onNext,
                        enabled  = frozenFrame != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Next", fontSize = 13.sp)
                    }
                }
            }
        } else {
            // ── Portrait: original vertical layout ─────────────────────────
            Column(
                modifier              = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment   = Alignment.CenterHorizontally,
                verticalArrangement   = Arrangement.spacedBy(16.dp)
            ) {
                StepHeader(
                    title       = "Step 1: Capture a Frame",
                    description = "Point the camera at the road and tap Capture when the scene looks good. " +
                            "Aim to include visible lane markings, road edge, or any feature whose real size you know."
                )
                Box(
                    modifier         = Modifier.fillMaxWidth().weight(1f)
                        .background(Color.DarkGray, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (frozenFrame != null) {
                        Image(
                            bitmap             = frozenFrame.asImageBitmap(),
                            contentDescription = "Captured frame",
                            modifier           = Modifier.fillMaxSize(),
                            contentScale       = ContentScale.Fit
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
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 2 — Measure (landscape-adaptive)
// ─────────────────────────────────────────────────────────────────────────────

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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            // ── Landscape: fields left, action right ────────────────────────
            Row(
                modifier              = Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Fields column
                Column(
                    modifier            = Modifier.weight(1f).fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StepHeader(
                        title       = "Step 3: Measurements",
                        description = "Enter distances between pins (metres)."
                    )
                    MeasureField("Top Edge Width (Red→Green)",    "e.g. 3.5", topWidthStr,    onTopWidthChange)
                    MeasureField("Bottom Edge Width (Yellow→Blue)", "e.g. 3.5", bottomWidthStr, onBottomWidthChange)
                    MeasureField("Left Edge Depth (Red→Yellow)",  "e.g. 6.0", leftDepthStr,   onLeftDepthChange)
                }
                // Action column
                Column(
                    modifier              = Modifier.width(160.dp).fillMaxHeight(),
                    verticalArrangement   = Arrangement.Center,
                    horizontalAlignment   = Alignment.CenterHorizontally
                ) {
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Compute", fontSize = 13.sp)
                    }
                }
            }
        } else {
            // ── Portrait: original scrollable column ────────────────────────
            Column(
                modifier            = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StepHeader(
                    title       = "Step 3: Enter Real-World Measurements",
                    description = "Measure the distances between the pins you placed on the road and enter them below (in metres). " +
                            "For example: a standard Italian lane is 3.5 m wide."
                )
                MeasureField("Top Edge Width (Red → Green, metres)",      "e.g. 3.5", topWidthStr,    onTopWidthChange)
                MeasureField("Bottom Edge Width (Yellow → Blue, metres)",  "e.g. 3.5", bottomWidthStr, onBottomWidthChange)
                MeasureField("Left Edge Depth (Red → Yellow, metres)",     "e.g. 6.0", leftDepthStr,   onLeftDepthChange)
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
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 3 — Validate & Save
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ValidateStep(
    profile: CalibrationProfile?,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    Column(
        modifier            = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StepHeader(
            title       = "Step 4: Review & Save",
            description = "Check the calibration confidence score below. If the quality is Poor, " +
                    "go back and reposition your pins — ideally on clearly visible, well-spaced road markings."
        )
        profile?.let { p -> ConfidenceScoreCard(p.reprojectionErrorPx) }

        OutlinedTextField(
            value          = profileName,
            onValueChange  = onProfileNameChange,
            label          = { Text("Profile Name") },
            placeholder    = { Text("e.g. Window 2nd floor, angle 30°") },
            modifier       = Modifier.fillMaxWidth()
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
// Confidence score card (reused in profile manager)
// ─────────────────────────────────────────────────────────────────────────────

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
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        border   = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(text = label,                           fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color)
                Text(text = "(%.2f px error)".format(errorPx), fontSize = 14.sp, color = color)
            }
            ConfidenceBar(errorPx = errorPx)
            Text(explanation, fontSize = 13.sp, lineHeight = 18.sp)
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
private fun MeasureField(label: String, hint: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value          = value,
        onValueChange  = onValueChange,
        label          = { Text(label, fontSize = 13.sp) },
        placeholder    = { Text(hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier       = Modifier.fillMaxWidth()
    )
}

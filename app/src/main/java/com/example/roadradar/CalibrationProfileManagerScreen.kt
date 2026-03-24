package com.example.roadradar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen profile manager.
 *
 * Lists every saved [CalibrationProfile] with:
 *  - Reprojection error badge (green / amber / red)
 *  - Activate button (sets as active profile)
 *  - Delete button (with confirmation dialog)
 *
 * [activeProfileName] is the currently active profile name so we can
 * highlight it with a filled star.
 *
 * [onActivate] is called when the user taps Activate — the caller
 * should propagate the profile to [VehicleTracker] and update the HUD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationProfileManagerScreen(
    activeProfileName: String?,
    onActivate: (CalibrationProfile) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(CalibrationProfileStore.loadAll(context)) }
    var deleteCandidate by remember { mutableStateOf<CalibrationProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibration Profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No calibration profiles saved yet.\nUse the \u2605 wizard to create one.",
                    color = Color.Gray,
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(profiles, key = { it.name }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = profile.name == activeProfileName,
                        onActivate = {
                            CalibrationProfileStore.setActive(context, profile.name)
                            onActivate(profile)
                        },
                        onDeleteRequest = { deleteCandidate = profile }
                    )
                }
            }
        }
    }

    // Confirm-delete dialog
    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete profile?") },
            text = { Text("\u201c${candidate.name}\u201d will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    CalibrationProfileStore.delete(context, candidate.name)
                    profiles = CalibrationProfileStore.loadAll(context)
                    deleteCandidate = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProfileCard(
    profile: CalibrationProfile,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val qualityColor = qualityColor(profile.reprojectionErrorPx)
    val qualityLabel = qualityLabel(profile.reprojectionErrorPx)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = null,
                    tint = if (isActive) Color(0xFFFFC107) else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    profile.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                // Quality badge
                Box(
                    modifier = Modifier
                        .background(qualityColor.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "%.1f px\u2009\u00b7\u2009$qualityLabel".format(profile.reprojectionErrorPx),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = qualityColor
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Confidence progress bar
            ConfidenceBar(errorPx = profile.reprojectionErrorPx)

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) {
                    Button(
                        onClick = onActivate,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Activate", fontSize = 13.sp)
                    }
                } else {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                            Text("Active", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onDeleteRequest,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", fontSize = 13.sp)
                }
            }
        }
    }
}

/** Maps reprojection error to a progress fraction in [0,1]; 0 = perfect, 1 = 20 px+ = worst. */
private fun errorFraction(errorPx: Float): Float = (errorPx / 20f).coerceIn(0f, 1f)

/** Green < 5, Amber < 15, Red >= 15 */
internal fun qualityColor(errorPx: Float): Color = when {
    errorPx < 5f  -> Color(0xFF2E7D32)
    errorPx < 15f -> Color(0xFFF57F17)
    else          -> Color(0xFFC62828)
}

internal fun qualityLabel(errorPx: Float): String = when {
    errorPx < 5f  -> "Excellent"
    errorPx < 15f -> "Good"
    else          -> "Poor"
}

@Composable
internal fun ConfidenceBar(errorPx: Float, modifier: Modifier = Modifier) {
    val fraction = errorFraction(errorPx)
    val barColor = qualityColor(errorPx)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Confidence", fontSize = 11.sp, color = Color.Gray)
            Text("%.1f / 20 px".format(errorPx), fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
        ) {
            // Invert: low error = wide filled bar (high confidence)
            Box(
                modifier = Modifier
                    .fillMaxWidth(1f - fraction)
                    .fillMaxHeight()
                    .background(barColor, RoundedCornerShape(3.dp))
            )
        }
    }
}

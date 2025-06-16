package com.example.roadradar

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Settings screen for calibrating the speed calculator
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationSettingsScreen(
    speedCalculator: SpeedCalculator,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { getSharedPreferences(context) }

    // Load saved values from preferences
    val savedPixelToMeterRatio = prefs.getFloat("PIXEL_TO_METER_RATIO", 100f)
    val savedVehicleWidth = prefs.getFloat("VEHICLE_WIDTH_METERS", 1.8f)
    val savedCameraHeight = prefs.getFloat("CAMERA_HEIGHT_METERS", 1.5f)
    val savedSmoothingFactor = prefs.getFloat("SMOOTHING_FACTOR", 0.3f)

    // State for current values
    var pixelToMeterRatio by remember { mutableStateOf(savedPixelToMeterRatio) }
    var vehicleWidth by remember { mutableStateOf(savedVehicleWidth) }
    var cameraHeight by remember { mutableStateOf(savedCameraHeight) }
    var smoothingFactor by remember { mutableStateOf(savedSmoothingFactor) }

    // Help dialog state
    var showHelpDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speed Calibration") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Help")
                    }
                    IconButton(
                        onClick = {
                            // Save all settings to preferences
                            with(prefs.edit()) {
                                putFloat("PIXEL_TO_METER_RATIO", pixelToMeterRatio)
                                putFloat("VEHICLE_WIDTH_METERS", vehicleWidth)
                                putFloat("CAMERA_HEIGHT_METERS", cameraHeight)
                                putFloat("SMOOTHING_FACTOR", smoothingFactor)
                                apply()
                            }

                            // Apply settings to speed calculator
                            speedCalculator.setCalibration(pixelToMeterRatio)
                            speedCalculator.setVehicleWidth(vehicleWidth)
                            speedCalculator.setCameraHeight(cameraHeight)
                            speedCalculator.setSmoothingFactor(smoothingFactor)

                            // Return to main screen
                            onBackPressed()
                        }
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Calibration instruction card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Calibration Instructions",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "For accurate speed measurements, calibrate the app by " +
                                "measuring known distances in your camera view. Place an object " +
                                "of known width (like your car) at a specific distance and adjust " +
                                "the pixel-to-meter ratio accordingly.",
                        fontSize = 14.sp
                    )
                }
            }

            // Pixel to Meter Ratio
            CalibrationSlider(
                title = "Pixels Per Meter",
                value = pixelToMeterRatio,
                onValueChange = { pixelToMeterRatio = it },
                valueRange = 50f..500f,
                description = "Higher values for zoomed-in views, lower for wide-angle",
                valueText = "%.1f px/m".format(pixelToMeterRatio)
            )

            // Vehicle width
            CalibrationSlider(
                title = "Average Vehicle Width",
                value = vehicleWidth,
                onValueChange = { vehicleWidth = it },
                valueRange = 1.2f..2.5f,
                description = "Standard car width is around 1.8m, SUVs closer to 2m",
                valueText = "%.1f meters".format(vehicleWidth)
            )

            // Camera height
            CalibrationSlider(
                title = "Camera Height",
                value = cameraHeight,
                onValueChange = { cameraHeight = it },
                valueRange = 0.5f..3.0f,
                description = "Height of your camera from the ground",
                valueText = "%.1f meters".format(cameraHeight)
            )

            // Smoothing factor
            CalibrationSlider(
                title = "Smoothing Factor",
                value = smoothingFactor,
                onValueChange = { smoothingFactor = it },
                valueRange = 0.1f..1.0f,
                description = "Lower values for smoother speeds, higher for more responsive readings",
                valueText = "%.1f".format(smoothingFactor)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    speedCalculator.reset()
                    // Apply current settings for testing
                    speedCalculator.setCalibration(pixelToMeterRatio)
                    speedCalculator.setVehicleWidth(vehicleWidth)
                    speedCalculator.setCameraHeight(cameraHeight)
                    speedCalculator.setSmoothingFactor(smoothingFactor)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset & Test Settings")
            }
        }

        // Help Dialog
        if (showHelpDialog) {
            Dialog(onDismissRequest = { showHelpDialog = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Calibration Help",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Text(
                            "How to calibrate:",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        Text(
                            "1. Place your phone at your typical measurement position\n" +
                                    "2. Put an object of known size (like a vehicle) at a known distance\n" +
                                    "3. Count how many pixels wide the object appears on screen\n" +
                                    "4. Divide that by the real-world width to get pixels per meter\n" +
                                    "5. Adjust other settings based on your setup\n\n" +
                                    "For best results:\n" +
                                    "- Calibrate in good lighting conditions\n" +
                                    "- Use the Reset & Test function to verify your settings\n" +
                                    "- Recalibrate when changing camera position or angle",
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )

                        Button(
                            onClick = { showHelpDialog = false },
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 16.dp)
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun CalibrationSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String,
    valueText: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
            Text(
                valueText,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = 20,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Text(
            description,
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        HorizontalDivider(Modifier.padding(8.dp), thickness = 1.dp, color = Color.Gray)
    }

}


private fun getSharedPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences("road_radar_settings", Context.MODE_PRIVATE)
}
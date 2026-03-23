package com.frontieraudio.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.frontieraudio.app.service.BatteryOptimizationHelper
import com.frontieraudio.app.service.OemDetector
import com.frontieraudio.app.service.OemType

private enum class OnboardingStep {
    MICROPHONE,
    LOCATION,
    NOTIFICATIONS,
    BATTERY,
    OEM_GUIDANCE,
    DONE,
}

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity

    var currentStep by remember { mutableStateOf(resolveInitialStep(activity)) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var batteryWhitelisted by remember {
        mutableStateOf(BatteryOptimizationHelper.isWhitelisted(context))
    }

    val oem = remember { OemDetector.detect() }
    val oemInstructions = remember { OemDetector.getInstructions(oem) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) currentStep = OnboardingStep.LOCATION
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        locationGranted = fine
        if (fine) currentStep = OnboardingStep.NOTIFICATIONS
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
        currentStep = OnboardingStep.BATTERY
    }

    // Re-check battery whitelist when returning from settings
    var resumeCounter by remember { mutableIntStateOf(0) }
    LaunchedEffect(resumeCounter) {
        if (currentStep == OnboardingStep.BATTERY) {
            val whitelisted = BatteryOptimizationHelper.isWhitelisted(context)
            batteryWhitelisted = whitelisted
            if (whitelisted) {
                currentStep = if (oemInstructions != null) OnboardingStep.OEM_GUIDANCE else OnboardingStep.DONE
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Setup Selective Speaker",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "We need a few permissions to keep recording in the background.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        PermissionCard(
            icon = Icons.Default.Mic,
            title = "Microphone",
            description = "Required to capture your speech for transcription.",
            granted = micGranted,
            isCurrent = currentStep == OnboardingStep.MICROPHONE,
            onRequest = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Default.LocationOn,
            title = "Location",
            description = "Tags each transcript with GPS coordinates.",
            granted = locationGranted,
            isCurrent = currentStep == OnboardingStep.LOCATION,
            onRequest = {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            description = "Shows a persistent notification while recording.",
            granted = notificationsGranted,
            isCurrent = currentStep == OnboardingStep.NOTIFICATIONS,
            onRequest = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Default.BatteryAlert,
            title = "Battery Optimization",
            description = "Prevents the system from killing the recording service.",
            granted = batteryWhitelisted,
            isCurrent = currentStep == OnboardingStep.BATTERY,
            onRequest = {
                BatteryOptimizationHelper.requestWhitelist(activity)
                resumeCounter++
            },
        )

        if (oemInstructions != null) {
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (currentStep == OnboardingStep.OEM_GUIDANCE)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = oemInstructions.title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = oemInstructions.steps,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        val allDone = micGranted && locationGranted && notificationsGranted && batteryWhitelisted
        Button(
            onClick = {
                if (currentStep == OnboardingStep.OEM_GUIDANCE) {
                    currentStep = OnboardingStep.DONE
                }
                onComplete()
            },
            enabled = allDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    isCurrent: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                granted -> MaterialTheme.colorScheme.primaryContainer
                isCurrent -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else icon,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isCurrent && !granted) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onRequest) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

private fun resolveInitialStep(activity: Activity): OnboardingStep {
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return OnboardingStep.MICROPHONE
    }
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return OnboardingStep.LOCATION
    }
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        return OnboardingStep.NOTIFICATIONS
    }
    if (!BatteryOptimizationHelper.isWhitelisted(activity)) {
        return OnboardingStep.BATTERY
    }
    val oem = OemDetector.detect()
    if (OemDetector.getInstructions(oem) != null) {
        return OnboardingStep.OEM_GUIDANCE
    }
    return OnboardingStep.DONE
}

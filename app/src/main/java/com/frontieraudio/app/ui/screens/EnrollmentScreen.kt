package com.frontieraudio.app.ui.screens

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.frontieraudio.app.domain.model.AudioChunk
import com.frontieraudio.app.service.audio.AudioCaptureManager
import com.frontieraudio.app.service.audio.AudioConfig
import com.frontieraudio.app.service.audio.SileroVadProcessor
import com.frontieraudio.app.service.speaker.EnrollmentManager
import com.frontieraudio.app.service.speaker.EnrollmentQualityException
import com.frontieraudio.app.service.speaker.SherpaOnnxVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val TAG = "EnrollmentScreen"

private val ENROLLMENT_PROMPTS = listOf(
    "Please read aloud:\n\"The quick brown fox jumps over the lazy dog near the river bank.\"",
    "Please read aloud (same sentence):\n\"The quick brown fox jumps over the lazy dog near the river bank.\"",
    "Please read aloud (one more time):\n\"The quick brown fox jumps over the lazy dog near the river bank.\"",
)

private enum class EnrollmentState {
    READY,
    RECORDING,
    PROCESSING,
    SUCCESS,
    FAILED,
}

@Composable
fun EnrollmentScreen(
    audioCaptureManager: AudioCaptureManager,
    vadProcessor: SileroVadProcessor,
    verifier: SherpaOnnxVerifier,
    enrollmentManager: EnrollmentManager,
    onEnrollmentComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentStep by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(EnrollmentState.READY) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val capturedUtterances = remember { mutableStateListOf<AudioChunk>() }
    var recordingJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        verifier.init()
        vadProcessor.init()
        onDispose {
            audioCaptureManager.stop()
        }
    }

    // Accumulate all raw frames during recording for manual stop
    val recordedFrames = remember { mutableListOf<ShortArray>() }

    fun stopAndCapture() {
        recordingJob?.cancel()
        audioCaptureManager.stop()
        if (recordedFrames.isNotEmpty()) {
            val totalSamples = recordedFrames.sumOf { it.size }
            val pcmData = ByteArray(totalSamples * AudioConfig.BYTES_PER_SAMPLE)
            var offset = 0
            for (frame in recordedFrames) {
                for (sample in frame) {
                    pcmData[offset++] = (sample.toInt() and 0xFF).toByte()
                    pcmData[offset++] = (sample.toInt() shr 8 and 0xFF).toByte()
                }
            }
            val durationMs = (totalSamples * 1000) / audioCaptureManager.currentSampleRate
            if (durationMs >= MIN_UTTERANCE_MS) {
                val chunk = AudioChunk(
                    pcmData = pcmData,
                    startTimestamp = System.currentTimeMillis(),
                    durationMs = durationMs,
                    sampleRate = audioCaptureManager.currentSampleRate,
                    isSpeakerVerified = false,
                )
                capturedUtterances.add(chunk)
                currentStep++
                state = if (capturedUtterances.size >= EnrollmentManager.REQUIRED_UTTERANCES) {
                    EnrollmentState.PROCESSING
                } else {
                    EnrollmentState.READY
                }
            } else {
                state = EnrollmentState.READY
                errorMessage = "Too short — please speak for at least 2 seconds."
            }
            recordedFrames.clear()
        } else {
            state = EnrollmentState.READY
        }
    }

    fun startRecording() {
        state = EnrollmentState.RECORDING
        errorMessage = null
        audioLevel = 0f
        recordedFrames.clear()

        recordingJob = scope.launch {
            try {
                val audioFlow = audioCaptureManager.start()

                audioFlow.collect { frame ->
                    audioLevel = computeRms(frame)
                    recordedFrames.add(frame.copyOf())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e(TAG, "Recording failed", e)
                state = EnrollmentState.FAILED
                errorMessage = "Recording failed: ${e.message}"
            }
        }
    }

    // Trigger enrollment when all 3 utterances are captured
    LaunchedEffect(capturedUtterances.size) {
        if (capturedUtterances.size >= EnrollmentManager.REQUIRED_UTTERANCES &&
            state == EnrollmentState.PROCESSING
        ) {
            val result = withContext(Dispatchers.Default) {
                enrollmentManager.enroll(capturedUtterances.toList())
            }
            result.fold(
                onSuccess = {
                    state = EnrollmentState.SUCCESS
                },
                onFailure = { error ->
                    state = EnrollmentState.FAILED
                    errorMessage = when (error) {
                        is EnrollmentQualityException -> error.message
                        else -> "Enrollment failed: ${error.message}"
                    }
                },
            )
        }
    }

    fun retry() {
        recordingJob?.cancel()
        audioCaptureManager.stop()
        capturedUtterances.clear()
        currentStep = 0
        state = EnrollmentState.READY
        errorMessage = null
        audioLevel = 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Voice Enrollment",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Record 3 voice samples so we can recognize you.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        StepIndicator(
            totalSteps = EnrollmentManager.REQUIRED_UTTERANCES,
            completedSteps = capturedUtterances.size,
            currentStep = currentStep,
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (state) {
            EnrollmentState.READY -> {
                if (currentStep < ENROLLMENT_PROMPTS.size) {
                    PromptCard(prompt = ENROLLMENT_PROMPTS[currentStep])
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Button(
                    onClick = { startRecording() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Recording")
                }
            }

            EnrollmentState.RECORDING -> {
                if (currentStep < ENROLLMENT_PROMPTS.size) {
                    PromptCard(prompt = ENROLLMENT_PROMPTS[currentStep])
                    Spacer(modifier = Modifier.height(16.dp))
                }
                AudioLevelIndicator(level = audioLevel)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Listening... Tap Done when finished.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { stopAndCapture() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Done Speaking")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        recordingJob?.cancel()
                        audioCaptureManager.stop()
                        recordedFrames.clear()
                        state = EnrollmentState.READY
                    },
                ) {
                    Text("Cancel")
                }
            }

            EnrollmentState.PROCESSING -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Verifying voice samples...",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            EnrollmentState.SUCCESS -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Voice enrolled successfully!",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "We can now recognize your voice and only transcribe your speech.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onEnrollmentComplete,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start Monitoring")
                }
            }

            EnrollmentState.FAILED -> {
                Text(
                    text = errorMessage ?: "Enrollment failed.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { retry() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Try Again")
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(
    totalSteps: Int,
    completedSteps: Int,
    currentStep: Int,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until totalSteps) {
            val color by animateColorAsState(
                targetValue = when {
                    i < completedSteps -> MaterialTheme.colorScheme.primary
                    i == currentStep -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                label = "step_color_$i",
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            if (i < totalSteps - 1) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(2.dp)
                        .background(
                            if (i < completedSteps) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                )
            }
        }
    }
}

@Composable
private fun AudioLevelIndicator(level: Float) {
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 100),
        label = "audio_level",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { animatedLevel },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun PromptCard(prompt: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(
            text = prompt,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun computeRms(frame: ShortArray): Float {
    if (frame.isEmpty()) return 0f
    var sum = 0.0
    for (sample in frame) {
        val normalized = sample / 32768.0
        sum += normalized * normalized
    }
    val rms = sqrt(sum / frame.size).toFloat()
    // Scale to 0..1 range (typical speech RMS is ~0.02-0.15)
    return (rms * 8f).coerceIn(0f, 1f)
}

private const val MIN_UTTERANCE_MS = 500

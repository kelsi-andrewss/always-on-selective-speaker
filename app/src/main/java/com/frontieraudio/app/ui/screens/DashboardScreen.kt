package com.frontieraudio.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frontieraudio.app.data.repository.FirestoreTranscript
import android.app.Application
import com.frontieraudio.app.data.repository.TranscriptRepository
import com.frontieraudio.app.service.RecordingForegroundService
import com.frontieraudio.app.service.speaker.EmbeddingStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: TranscriptRepository,
    private val embeddingStore: EmbeddingStore,
    private val app: Application,
) : ViewModel() {
    val transcripts = repository.transcripts
    val isServiceRunning = RecordingForegroundService.isRunning
    val pipelineState = RecordingForegroundService.pipelineState
    val micState = RecordingForegroundService.micState

    fun toggleRecording() {
        if (isServiceRunning.value) {
            RecordingForegroundService.stop(app)
        } else {
            RecordingForegroundService.start(app)
        }
    }

    fun startRecording() {
        RecordingForegroundService.start(app)
    }

    fun stopRecording() {
        RecordingForegroundService.stop(app)
    }

    suspend fun clearEnrollment() {
        embeddingStore.clear()
    }
}

@Composable
fun DashboardScreen(
    onReEnroll: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle(initialValue = emptyList())
    val isRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
    val micState by viewModel.micState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.startRecording()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        // Mic level — always visible
        MicLevelBar(isRunning, micState, onToggle = { viewModel.toggleRecording() })

        Spacer(modifier = Modifier.height(8.dp))

        // Verification status — separate from mic
        VerificationStatusBar(pipelineState)

        Spacer(modifier = Modifier.height(8.dp))

        val scope = rememberCoroutineScope()
        TextButton(
            onClick = {
                scope.launch {
                    viewModel.stopRecording()
                    viewModel.clearEnrollment()
                    onReEnroll()
                }
            },
        ) {
            Text("Re-enroll voice", style = MaterialTheme.typography.labelMedium)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Transcripts",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (transcripts.isEmpty()) {
            Text(
                text = "No transcripts yet. Recording is active in the background.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val listState = rememberLazyListState()

            LaunchedEffect(transcripts.firstOrNull()?.chunkId) {
                listState.animateScrollToItem(0)
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(transcripts, key = { it.chunkId }) { transcript ->
                    TranscriptCard(transcript)
                }
            }
        }
    }
}

@Composable
private fun MicLevelBar(isRunning: Boolean, micState: String, onToggle: () -> Unit) {
    val isHearing = micState.startsWith("Hearing")
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val dotColor by animateColorAsState(
        targetValue = when {
            !isRunning -> MaterialTheme.colorScheme.error
            isHearing -> Color(0xFFFFA726) // orange when hearing voice
            else -> Color(0xFF4CAF50) // green when listening
        },
        label = "dotColor",
    )

    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                Color(0xFF1B5E20).copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(if (isRunning) pulseAlpha else 1f)
                    .clip(CircleShape)
                    .background(dotColor),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = if (isRunning) micState else "Not recording",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = if (isRunning) "Tap to stop" else "Tap to start",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VerificationStatusBar(pipelineState: String) {
    val isVerified = pipelineState.startsWith("YOU")
    val isOther = pipelineState.startsWith("Other")
    val isBuffering = pipelineState.startsWith("Buffering")
    val isVerifying = pipelineState.startsWith("Verifying")
    val isActive = isVerified || isOther || isBuffering || isVerifying

    val bgColor by animateColorAsState(
        targetValue = when {
            isVerified -> Color(0xFF1B5E20).copy(alpha = 0.2f)
            isOther -> Color(0xFFB71C1C).copy(alpha = 0.15f)
            isBuffering || isVerifying -> Color(0xFFF57F17).copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        label = "verifyBg",
    )

    val textColor by animateColorAsState(
        targetValue = when {
            isVerified -> Color(0xFF2E7D32)
            isOther -> Color(0xFFC62828)
            isBuffering || isVerifying -> Color(0xFFF9A825)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "verifyText",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isActive) pipelineState else "Waiting for speech...",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun TranscriptCard(transcript: FirestoreTranscript) {
    val dateFormat = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
    val isPending = transcript.status == "pending"
    val displayText = if (isPending) "Transcribing..." else transcript.text

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPending) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPending) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (transcript.createdAt > 0L) {
                    Text(
                        text = dateFormat.format(Date(transcript.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (transcript.speakerConfidence != null) {
                    Text(
                        text = "Speaker Confidence: ${(transcript.speakerConfidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (transcript.latitude != null && transcript.longitude != null) {
                    Text(
                        text = "%.4f, %.4f".format(transcript.latitude, transcript.longitude),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

package com.frontieraudio.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.frontieraudio.app.service.RecordingForegroundService
import com.frontieraudio.app.service.audio.AudioCaptureManager
import com.frontieraudio.app.service.audio.SileroVadProcessor
import com.frontieraudio.app.service.speaker.EmbeddingStore
import com.frontieraudio.app.service.speaker.EnrollmentManager
import com.frontieraudio.app.service.speaker.SherpaOnnxVerifier
import com.frontieraudio.app.ui.navigation.AppNavGraph
import com.frontieraudio.app.ui.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var audioCaptureManager: AudioCaptureManager
    @Inject lateinit var vadProcessor: SileroVadProcessor
    @Inject lateinit var verifier: SherpaOnnxVerifier
    @Inject lateinit var enrollmentManager: EnrollmentManager
    @Inject lateinit var embeddingStore: EmbeddingStore

    private var startDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val enrolled = embeddingStore.isEnrolled()
            val serviceRunning = RecordingForegroundService.isRunning.value
            startDestination = when {
                serviceRunning -> Routes.DASHBOARD
                enrolled -> Routes.DASHBOARD
                else -> Routes.ONBOARDING
            }
        }

        setContent {
            MaterialTheme {
                val dest = startDestination
                if (dest != null) {
                    val navController = rememberNavController()
                    AppNavGraph(
                        navController = navController,
                        startDestination = dest,
                        audioCaptureManager = audioCaptureManager,
                        vadProcessor = vadProcessor,
                        verifier = verifier,
                        enrollmentManager = enrollmentManager,
                        onStartService = {
                            RecordingForegroundService.start(this@MainActivity)
                        },
                    )
                }
            }
        }
    }
}

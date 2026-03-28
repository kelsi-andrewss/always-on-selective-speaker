package com.frontieraudio.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.frontieraudio.app.domain.model.AudioChunk
import com.frontieraudio.app.domain.model.SpeakerProfile
import com.frontieraudio.app.data.local.dao.RecordingDao
import com.frontieraudio.app.data.local.entity.AudioChunkEntity
import com.frontieraudio.app.data.local.entity.SyncStatus
import com.frontieraudio.app.service.audio.AudioCaptureManager
import com.frontieraudio.app.service.sync.WavConverter
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.frontieraudio.app.service.audio.AudioConfig
import com.frontieraudio.app.service.audio.BluetoothAudioRouter
import com.frontieraudio.app.service.audio.RoutingEvent
import com.frontieraudio.app.service.audio.SileroVadProcessor
import com.frontieraudio.app.service.location.GpsTracker
import com.frontieraudio.app.service.location.LocationBatchManager
import com.frontieraudio.app.service.speaker.EmbeddingStore
import com.frontieraudio.app.service.speaker.SherpaOnnxVerifier
import java.util.UUID
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@AndroidEntryPoint
class RecordingForegroundService : Service() {

    @Inject lateinit var audioCaptureManager: AudioCaptureManager
    @Inject lateinit var vadProcessor: SileroVadProcessor
    @Inject lateinit var sherpaOnnxVerifier: SherpaOnnxVerifier
    @Inject lateinit var embeddingStore: EmbeddingStore
    @Inject lateinit var gpsTracker: GpsTracker
    @Inject lateinit var locationBatchManager: LocationBatchManager
    @Inject lateinit var notificationManager: ServiceNotificationManager
    @Inject lateinit var bluetoothAudioRouter: BluetoothAudioRouter
    @Inject lateinit var recordingDao: RecordingDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pipelineJob: Job? = null
    private var locationJob: Job? = null
    private var notificationJob: Job? = null
    private var bluetoothJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startTimeMs: Long = 0L
    private var sessionId: String = UUID.randomUUID().toString()

    private val firestore by lazy { Firebase.firestore }
    private val cloudStorage by lazy { Firebase.storage }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        notificationManager.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startTimeMs = System.currentTimeMillis()

        val notification = notificationManager.createNotification(0.milliseconds)
        startForeground(
            ServiceNotificationManager.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        _isRunning.value = true
        startPipeline()
        startBluetoothRouting()
        startLocationTracking()
        startNotificationUpdater()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _isRunning.value = false
        pipelineJob?.cancel()
        locationJob?.cancel()
        notificationJob?.cancel()
        bluetoothJob?.cancel()

        try {
            audioCaptureManager.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio capture", e)
        }
        try {
            bluetoothAudioRouter.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing BT audio router", e)
        }
        try {
            vadProcessor.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VAD processor", e)
        }
        try {
            sherpaOnnxVerifier.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing speaker verifier", e)
        }
        try {
            gpsTracker.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GPS tracker", e)
        }

        locationBatchManager.clear()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed — service will rely on START_STICKY and WatchdogWorker for restart")
    }

    private fun startPipeline() {
        pipelineJob = serviceScope.launch {
            try {
                vadProcessor.init()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init VAD processor — pipeline cannot start", e)
                return@launch
            }

            try {
                sherpaOnnxVerifier.init()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init speaker verifier — pipeline cannot start", e)
                return@launch
            }

            val profile = try {
                embeddingStore.load()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load speaker profile", e)
                null
            }

            if (profile == null) {
                Log.w(TAG, "No enrolled speaker profile — running VAD only, verification skipped")
            }

            try {
                val audioFrames = audioCaptureManager.start()
                val speechSegments = vadProcessor.collectSpeechSegment(audioFrames)

                speechSegments.collect { chunk ->
                    processChunk(chunk, profile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio pipeline error", e)
            }
        }
    }

    private fun startBluetoothRouting() {
        bluetoothJob = serviceScope.launch {
            try {
                bluetoothAudioRouter.observeRouting().collect { event ->
                    when (event) {
                        is RoutingEvent.BluetoothRouted -> {
                            Log.i(TAG, "BT routed: sampleRate=${event.sampleRate}")
                            restartPipelineWithDevice(event.sampleRate, event.audioSource)
                        }
                        is RoutingEvent.FallbackToBuiltIn -> {
                            Log.i(TAG, "BT disconnected, reverting to built-in mic")
                            restartPipelineWithDevice(
                                sampleRate = AudioConfig.SAMPLE_RATE,
                                audioSource = AudioConfig.PRIMARY_AUDIO_SOURCE,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth routing observation failed", e)
            }
        }
    }

    private fun restartPipelineWithDevice(sampleRate: Int, audioSource: Int) {
        pipelineJob?.cancel()
        audioCaptureManager.recreateForDevice(sampleRate, audioSource)
        vadProcessor.currentSampleRate = sampleRate
        startPipeline()
    }

    private suspend fun processChunk(
        chunk: AudioChunk,
        profile: SpeakerProfile?,
    ) {
        val verifiedChunk = if (profile != null) {
            try {
                val result = sherpaOnnxVerifier.verify(chunk, profile)
                if (result.isMatch) {
                    Log.d(TAG, "Speaker verified (similarity=${result.similarity})")
                    chunk.copy(isSpeakerVerified = true)
                } else {
                    Log.d(TAG, "Speaker not matched (similarity=${result.similarity})")
                    chunk
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speaker verification failed — passing chunk unverified", e)
                chunk
            }
        } else {
            chunk
        }

        val location = try {
            locationBatchManager.getNearestLocation(verifiedChunk.startTimestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Location lookup failed", e)
            null
        }

        val chunkId = UUID.randomUUID().toString()

        val entity = AudioChunkEntity(
            chunkId = chunkId,
            sessionId = sessionId,
            audioData = verifiedChunk.pcmData,
            startTimestamp = verifiedChunk.startTimestamp,
            durationMs = verifiedChunk.durationMs,
            sampleRate = verifiedChunk.sampleRate,
            isSpeakerVerified = verifiedChunk.isSpeakerVerified,
            syncStatus = SyncStatus.PENDING,
            latitude = location?.latitude,
            longitude = location?.longitude,
            locationAccuracy = location?.accuracy,
        )

        recordingDao.insertChunk(entity)
        Log.d(
            TAG,
            "Chunk persisted: id=${entity.chunkId}, verified=${entity.isSpeakerVerified}, " +
                "duration=${entity.durationMs}ms, hasLocation=${location != null}",
        )

        // Upload WAV to Cloud Storage and create Firestore pending doc
        try {
            val user = Firebase.auth.currentUser
                ?: throw IllegalStateException("No authenticated user")

            val wavData = WavConverter.pcmToWav(
                pcmData = entity.audioData,
                sampleRate = entity.sampleRate,
                channels = 1,
                bitsPerSample = 16,
            )

            val storagePath = "audio-chunks/${user.uid}/${chunkId}.wav"
            val storageRef = cloudStorage.reference.child(storagePath)

            Log.d(TAG, "Uploading ${wavData.size} bytes to $storagePath")
            storageRef.putBytes(wavData).await()
            Log.d(TAG, "Upload complete for chunk $chunkId")

            // Create pending Firestore doc so the dashboard can show "transcribing..."
            firestore.collection("transcripts").document(chunkId).set(
                hashMapOf(
                    "chunkId" to chunkId,
                    "userId" to user.uid,
                    "status" to "pending",
                    "latitude" to entity.latitude,
                    "longitude" to entity.longitude,
                    "createdAt" to com.google.firebase.Timestamp.now(),
                )
            ).await()

            Log.d(TAG, "Firestore pending doc created for chunk $chunkId")
        } catch (e: Exception) {
            Log.e(TAG, "Cloud Storage upload or Firestore write failed for chunk $chunkId", e)
        }
    }

    private fun startLocationTracking() {
        locationJob = serviceScope.launch {
            try {
                gpsTracker.start().collect { point ->
                    try {
                        locationBatchManager.addLocation(point)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to buffer location point", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GPS tracking failed — continuing without location", e)
            }
        }
    }

    private fun startNotificationUpdater() {
        notificationJob = serviceScope.launch {
            while (true) {
                delay(1.minutes)
                try {
                    val elapsed = (System.currentTimeMillis() - startTimeMs).milliseconds
                    val notification = notificationManager.createNotification(elapsed)
                    val systemNm = getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    systemNm.notify(ServiceNotificationManager.NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update notification", e)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "RecordingFGService"
        private const val WAKE_LOCK_TAG = "com.frontieraudio.app:recording"
        const val ACTION_STOP = "com.frontieraudio.app.action.STOP_RECORDING"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

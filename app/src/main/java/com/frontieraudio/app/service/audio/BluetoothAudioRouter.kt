package com.frontieraudio.app.service.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

sealed class RoutingEvent {
    data class BluetoothRouted(val sampleRate: Int, val audioSource: Int) : RoutingEvent()
    data object FallbackToBuiltIn : RoutingEvent()
}

@Singleton
class BluetoothAudioRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceMonitor: AudioDeviceMonitor,
) {
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var currentBtDevice: AudioDeviceInfo? = null

    fun observeRouting(): Flow<RoutingEvent> = flow {
        // Check for an already-connected BT device at startup
        val existing = deviceMonitor.findConnectedBluetoothInput()
        if (existing != null) {
            val event = tryRouteToDevice(existing)
            if (event != null) emit(event)
        }

        deviceMonitor.observeBluetoothDevices().collect { btEvent ->
            when (btEvent) {
                is BluetoothDeviceEvent.Connected -> {
                    val current = currentBtDevice
                    if (current != null &&
                        AudioDeviceMonitor.devicePriority(btEvent.device) <=
                        AudioDeviceMonitor.devicePriority(current)
                    ) {
                        Log.d(TAG, "Ignoring lower-priority BT device: type=${btEvent.device.type}")
                        return@collect
                    }
                    val event = tryRouteToDevice(btEvent.device)
                    if (event != null) emit(event)
                }

                is BluetoothDeviceEvent.Disconnected -> {
                    if (currentBtDevice?.id == btEvent.device.id) {
                        Log.i(TAG, "Active BT device disconnected, falling back to built-in mic")
                        clearRouting()
                        emit(RoutingEvent.FallbackToBuiltIn)
                    }
                }
            }
        }
    }

    private suspend fun tryRouteToDevice(device: AudioDeviceInfo): RoutingEvent? {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val routed = retryCommunicationDevice(device)
        if (!routed) {
            Log.w(TAG, "Failed to set communication device after retries")
            audioManager.mode = AudioManager.MODE_NORMAL
            return null
        }

        currentBtDevice = device

        // Stabilization delay — Samsung S23-S26 need 200-500ms after Connected state
        delay(STABILIZATION_DELAY_MS)

        val sampleRate = AudioDeviceMonitor.preferredSampleRate(device)
        val audioSource = AudioConfig.PRIMARY_AUDIO_SOURCE
        Log.i(TAG, "BT audio routed: type=${device.type}, sampleRate=$sampleRate")

        return RoutingEvent.BluetoothRouted(sampleRate = sampleRate, audioSource = audioSource)
    }

    private suspend fun retryCommunicationDevice(device: AudioDeviceInfo): Boolean {
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            val available = audioManager.availableCommunicationDevices
            val match = available.find { it.id == device.id }
            if (match != null) {
                val success = audioManager.setCommunicationDevice(match)
                if (success) {
                    Log.d(TAG, "setCommunicationDevice succeeded on attempt ${attempt + 1}")
                    return true
                }
                Log.w(TAG, "setCommunicationDevice returned false on attempt ${attempt + 1}")
            } else {
                Log.d(TAG, "Device not yet in availableCommunicationDevices (attempt ${attempt + 1})")
            }
            delay(RETRY_INTERVAL_MS)
        }
        return false
    }

    private fun clearRouting() {
        currentBtDevice = null
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "Communication device cleared, mode set to NORMAL")
    }

    fun release() {
        clearRouting()
    }

    companion object {
        private const val TAG = "BluetoothAudioRouter"
        private const val STABILIZATION_DELAY_MS = 600L
        private const val RETRY_INTERVAL_MS = 500L
        private const val MAX_RETRY_ATTEMPTS = 5
    }
}

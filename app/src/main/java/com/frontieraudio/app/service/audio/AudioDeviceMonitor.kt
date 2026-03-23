package com.frontieraudio.app.service.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class BluetoothDeviceEvent {
    data class Connected(val device: AudioDeviceInfo) : BluetoothDeviceEvent()
    data class Disconnected(val device: AudioDeviceInfo) : BluetoothDeviceEvent()
}

@Singleton
class AudioDeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun observeBluetoothDevices(): Flow<BluetoothDeviceEvent> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                for (device in addedDevices) {
                    if (device.isSource && isBluetoothInput(device)) {
                        Log.i(TAG, "BT input connected: type=${device.type}, id=${device.id}")
                        trySend(BluetoothDeviceEvent.Connected(device))
                    }
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                for (device in removedDevices) {
                    if (device.isSource && isBluetoothInput(device)) {
                        Log.i(TAG, "BT input disconnected: type=${device.type}, id=${device.id}")
                        trySend(BluetoothDeviceEvent.Disconnected(device))
                    }
                }
            }
        }

        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        Log.i(TAG, "Registered audio device callback")

        awaitClose {
            audioManager.unregisterAudioDeviceCallback(callback)
            Log.i(TAG, "Unregistered audio device callback")
        }
    }

    fun findConnectedBluetoothInput(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { isBluetoothInput(it) }
            .sortedByDescending { devicePriority(it) }
            .firstOrNull()
    }

    companion object {
        private const val TAG = "AudioDeviceMonitor"

        fun isBluetoothInput(device: AudioDeviceInfo): Boolean =
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        fun devicePriority(device: AudioDeviceInfo): Int = when (device.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> 2
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
            else -> 0
        }

        fun preferredSampleRate(device: AudioDeviceInfo): Int = when (device.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> 32_000
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 16_000
            else -> AudioConfig.SAMPLE_RATE
        }
    }
}

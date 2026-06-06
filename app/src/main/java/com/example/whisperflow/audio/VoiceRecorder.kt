package com.example.whisperflow.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VoiceRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    @Volatile
    private var isRecording = false

    companion object {
        private val cleanupStarted = AtomicBoolean(false)

        // Maps MediaRecorder audio source constants to their display names
        private val audioSourceInfoMap = mapOf(
            MediaRecorder.AudioSource.MIC to "Built-in Microphone",
            MediaRecorder.AudioSource.CAMCORDER to "Camcorder Microphone",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice Recognition",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Voice Communication",
            MediaRecorder.AudioSource.UNPROCESSED to "Unprocessed Audio",
            MediaRecorder.AudioSource.VOICE_UPLINK to "Voice Uplink",
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "Voice Downlink",
            MediaRecorder.AudioSource.REMOTE_SUBMIX to "Remote Submix",
            MediaRecorder.AudioSource.DEFAULT to "Default"
        )

        /**
         * Enumerates all available audio input devices on the device
         * and maps them to the appropriate MediaRecorder.AudioSource constants.
         */
        fun getAvailableAudioSources(context: Context): List<Pair<Int, String>> {
            val sources = mutableListOf<Pair<Int, String>>()
            val addedSources = mutableSetOf<Int>()

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            if (inputDevices.isNotEmpty()) {
                for (device in inputDevices) {
                    val source = getBestAudioSourceForDevice(device)
                    if (!addedSources.contains(source)) {
                        val name = getDeviceDisplayName(device)
                        sources.add(source to name)
                        addedSources.add(source)
                    }
                }
            }

            // Fallback: if no devices found via AudioManager, use standard defaults
            if (sources.isEmpty()) {
                sources.add(MediaRecorder.AudioSource.MIC to "Microphone (Default)")
                sources.add(MediaRecorder.AudioSource.CAMCORDER to "Camcorder")
                sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice Recognition")
            }

            return sources
        }

        private fun getBestAudioSourceForDevice(device: AudioDeviceInfo): Int {
            return when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> MediaRecorder.AudioSource.MIC
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> MediaRecorder.AudioSource.MIC
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> MediaRecorder.AudioSource.MIC
                AudioDeviceInfo.TYPE_TELEPHONY -> MediaRecorder.AudioSource.VOICE_UPLINK
                AudioDeviceInfo.TYPE_HDMI -> MediaRecorder.AudioSource.REMOTE_SUBMIX
                AudioDeviceInfo.TYPE_HEARING_AID -> MediaRecorder.AudioSource.MIC
                else -> MediaRecorder.AudioSource.MIC
            }
        }

        private fun getDeviceDisplayName(device: AudioDeviceInfo): String {
            val productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                device.productName?.toString() ?: ""
            } else ""

            val typeName = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Speaker"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog Line"
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI Audio"
                AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
                AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
                AudioDeviceInfo.TYPE_FM -> "FM Tuner"
                AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
                AudioDeviceInfo.TYPE_DOCK -> "Dock"
                AudioDeviceInfo.TYPE_IP -> "IP Audio"
                AudioDeviceInfo.TYPE_BUS -> "Bus Audio"
                else -> "Unknown Device"
            }

            return if (productName.isNotEmpty()) "$typeName ($productName)" else typeName
        }
    }

    init {
        if (cleanupStarted.compareAndSet(false, true)) {
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    appCtx.cacheDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("whisper_dictation_") && file.name.endsWith(".m4a")) {
                            file.delete()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VoiceRecorder", "Failed to clean legacy audio files: ${e.message}")
                }
            }
        }
    }

    fun startRecording(audioSource: Int = MediaRecorder.AudioSource.MIC): File? {
        outputFile = File(context.cacheDir, "whisper_dictation_${System.currentTimeMillis()}.m4a")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            try {
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(16000)

                setOutputFile(outputFile?.absolutePath)
                prepare()
                start()
                isRecording = true
            } catch (e: Exception) {
                Log.e("VoiceRecorder", "Failed to start recording: ${e.message}")
                releaseRecorder()
                isRecording = false
                return null
            }
        }
        return outputFile
    }

    fun stopRecording(): File? {
        isRecording = false
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Failed to stop recording: ${e.message}")
        } finally {
            mediaRecorder = null
        }
        return outputFile
    }

    fun cancelRecording() {
        isRecording = false
        stopRecording()
        outputFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        outputFile = null
    }

    fun getMaxAmplitude(): Int {
        if (!isRecording || mediaRecorder == null) return 0
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Failed to get max amplitude: ${e.message}")
            isRecording = false
            0
        }
    }

    private fun releaseRecorder() {
        mediaRecorder?.release()
        mediaRecorder = null
    }
}

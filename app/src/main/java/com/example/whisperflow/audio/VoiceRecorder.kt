package com.example.whisperflow.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class VoiceRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    init {
        // Run low-priority background thread to clean up legacy temporary audio files
        try {
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("whisper_dictation_") && file.name.endsWith(".m4a")) {
                        file.delete()
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Failed to start legacy audio cleanup thread: ${e.message}")
        }
    }

    // Setup for lightweight, high-compression format
    fun startRecording(): File? {
        // Output format: MPEG_4, Audio encoder: AAC ensures very small file sizes 
        // which makes the upload to Groq API incredibly fast.
        outputFile = File(context.cacheDir, "whisper_dictation_${System.currentTimeMillis()}.m4a")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000) // 64kbps is plenty for voice
                setAudioSamplingRate(16000)    // 16kHz is ideal for Whisper
                
                setOutputFile(outputFile?.absolutePath)
                prepare()
                start()
            } catch (e: Exception) {
                Log.e("VoiceRecorder", "Failed to start recording: ${e.message}")
                releaseRecorder()
                return null
            }
        }
        return outputFile
    }

    fun stopRecording(): File? {
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
        stopRecording()
        outputFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        outputFile = null
    }

    fun getMaxAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun releaseRecorder() {
        mediaRecorder?.release()
        mediaRecorder = null
    }
}

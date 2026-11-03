package com.msi.openchat.feature.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class AudioRecorderHelper(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var timerJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    fun startRecording(): Boolean {
        try {
            val file = File(context.cacheDir, "record_${System.currentTimeMillis()}.m4a")
            outputFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            _isRecording.value = true
            _recordingDurationSeconds.value = 0

            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive && _isRecording.value) {
                    delay(1000)
                    _recordingDurationSeconds.value += 1
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("AudioRecorderHelper", "Failed to start recording: ${e.message}")
            stopRecording(discard = true)
            return false
        }
    }

    fun stopRecording(discard: Boolean = false): File? {
        timerJob?.cancel()
        _isRecording.value = false

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderHelper", "Error stopping recorder: ${e.message}")
        } finally {
            mediaRecorder = null
        }

        if (discard) {
            outputFile?.delete()
            outputFile = null
            _recordingDurationSeconds.value = 0
            return null
        }

        val result = outputFile
        outputFile = null
        return result
    }
}

package com.msi.openchat.feature.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
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

class AudioPlayerHelper(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
) {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _currentlyPlayingId = MutableStateFlow<String?>(null)
    val currentlyPlayingId: StateFlow<String?> = _currentlyPlayingId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    fun playAudio(messageId: String, audioUrl: String, onCompletion: () -> Unit = {}) {
        stopAudio()

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                if (audioUrl.startsWith("content://")) {
                    val uri = android.net.Uri.parse(audioUrl)
                    val ctx = com.msi.openchat.core.di.ServiceLocator.context
                    if (!com.msi.openchat.core.media.PersistableUriManager.isUriAccessible(ctx, uri)) {
                        android.widget.Toast.makeText(ctx, "Audio file is no longer accessible on this device", android.widget.Toast.LENGTH_SHORT).show()
                        stopAudio()
                        return
                    }
                    setDataSource(ctx, uri)
                } else if (audioUrl.startsWith("data:")) {
                    val base64Data = audioUrl.substringAfter("base64,")
                    val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    val tempAudioFile = java.io.File.createTempFile("voice_note_", ".m4a")
                    tempAudioFile.deleteOnExit()
                    tempAudioFile.writeBytes(decodedBytes)
                    setDataSource(tempAudioFile.absolutePath)
                } else if (audioUrl.startsWith("file://")) {
                    setDataSource(audioUrl.removePrefix("file://"))
                } else {
                    setDataSource(audioUrl)
                }
                prepareAsync()
            }

            player.setOnPreparedListener { mp ->
                mp.start()
                _currentlyPlayingId.value = messageId
                val duration = mp.duration.toFloat()

                progressJob?.cancel()
                progressJob = scope.launch {
                    while (isActive && mp.isPlaying) {
                        val current = mp.currentPosition.toFloat()
                        _playbackProgress.value = if (duration > 0) (current / duration).coerceIn(0f, 1f) else 0f
                        delay(100)
                    }
                }
            }

            player.setOnCompletionListener {
                stopAudio()
                onCompletion()
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e("AudioPlayerHelper", "Playback error: $what, $extra")
                stopAudio()
                true
            }

            mediaPlayer = player
        } catch (e: Exception) {
            Log.e("AudioPlayerHelper", "Failed to play audio: ${e.message}")
            stopAudio()
        }
    }

    fun stopAudio() {
        progressJob?.cancel()
        _currentlyPlayingId.value = null
        _playbackProgress.value = 0f
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}

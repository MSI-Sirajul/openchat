package com.msi.openchat.service.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioSwitchManager(context: Context) {
    private val tag = "AudioSwitchManager"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerOn = false
    private var audioFocusRequest: AudioFocusRequest? = null

    fun startCallAudio() {
        try {
            val am = audioManager ?: return
            savedAudioMode = am.mode
            savedIsSpeakerOn = am.isSpeakerphoneOn

            am.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { /* Handle focus loss if needed */ }
                    .build()

                audioFocusRequest = focusRequest
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to configure call audio focus: ${t.message}")
        }
    }

    fun setSpeakerphoneOn(on: Boolean) {
        try {
            audioManager?.isSpeakerphoneOn = on
        } catch (t: Throwable) {
            Log.w(tag, "Failed to set speakerphone: ${t.message}")
        }
    }

    fun isSpeakerphoneOn(): Boolean = try {
        audioManager?.isSpeakerphoneOn ?: false
    } catch (_: Throwable) {
        false
    }

    fun setMicrophoneMute(muted: Boolean) {
        try {
            audioManager?.isMicrophoneMute = muted
        } catch (t: Throwable) {
            Log.w(tag, "Failed to set mic mute: ${t.message}")
        }
    }

    fun isMicrophoneMuted(): Boolean = try {
        audioManager?.isMicrophoneMute ?: false
    } catch (_: Throwable) {
        false
    }

    fun stopCallAudio() {
        try {
            val am = audioManager ?: return
            am.isSpeakerphoneOn = savedIsSpeakerOn
            am.isMicrophoneMute = false
            am.mode = savedAudioMode

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to restore audio settings: ${t.message}")
        }
    }
}

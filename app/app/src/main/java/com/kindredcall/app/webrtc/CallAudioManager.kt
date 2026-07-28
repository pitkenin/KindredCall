package com.kindredcall.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class CallAudioManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalMode: Int = AudioManager.MODE_NORMAL
    private var originalIsSpeakerphoneOn: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null

    fun startCallAudio() {
        Log.d(TAG, "Starting call audio: requesting focus and setting MODE_IN_COMMUNICATION")
        originalMode = audioManager.mode
        originalIsSpeakerphoneOn = audioManager.isSpeakerphoneOn

        requestAudioFocus()

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    fun stopCallAudio() {
        Log.d(TAG, "Stopping call audio: abandoning focus and restoring mode $originalMode")
        abandonAudioFocus()
        audioManager.mode = originalMode
        audioManager.isSpeakerphoneOn = originalIsSpeakerphoneOn
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus change: $focusChange")
                }
                .build()
            
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange -> Log.d(TAG, "Audio focus change: $focusChange") },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus { focusChange ->
                Log.d(TAG, "Audio focus change: $focusChange")
            }
        }
    }

    companion object {
        private const val TAG = "CallAudioManager"
    }
}

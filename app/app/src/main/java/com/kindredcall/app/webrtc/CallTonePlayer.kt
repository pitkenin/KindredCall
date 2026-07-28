package com.kindredcall.app.webrtc

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.util.Log

class CallTonePlayer(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null

    fun startRingtone() {
        Log.d(TAG, "Starting ringtone")
        stopAll()
        val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, notificationUri)?.apply {
            play()
        }
    }

    fun startRingback() {
        Log.d(TAG, "Starting ringback beeps")
        stopAll()
        toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        // Standard ringback tone (US/standard)
        toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
    }

    fun stopAll() {
        Log.d(TAG, "Stopping all tones")
        ringtone?.stop()
        ringtone = null
        
        toneGenerator?.stopTone()
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        private const val TAG = "CallTonePlayer"
    }
}

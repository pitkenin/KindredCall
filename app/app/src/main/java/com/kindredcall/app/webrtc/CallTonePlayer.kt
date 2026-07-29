package com.kindredcall.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log

class CallTonePlayer(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null

    fun startRingtone() {
        Log.d(TAG, "Starting ringtone")
        stopAll()
        val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ringtone = RingtoneManager.getRingtone(context, notificationUri)?.apply {
                isLooping = true
                play()
            }
        } else {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, notificationUri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start looping ringtone with MediaPlayer", e)
                // Fallback to non-looping Ringtone if MediaPlayer fails
                ringtone = RingtoneManager.getRingtone(context, notificationUri)?.apply {
                    play()
                }
            }
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

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        toneGenerator?.stopTone()
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        private const val TAG = "CallTonePlayer"
    }
}

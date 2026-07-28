package com.kindredcall.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kindredcall.app.CallActivity
import com.kindredcall.app.KindredCallApplication
import com.kindredcall.app.notification.CallNotificationHelper
import com.kindredcall.app.signaling.SignalingClient
import com.kindredcall.app.webrtc.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class SignalingService : Service(), SignalingClient.Listener {
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRtcClient: WebRtcClient
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        CallNotificationHelper.createNotificationChannels(this)

        val app = application as KindredCallApplication
        signalingClient = app.signalingClient
        webRtcClient = app.webRtcClient
        signalingClient.addListener(this)

        startForeground(
            CallNotificationHelper.SERVICE_NOTIFICATION_ID,
            CallNotificationHelper.buildServiceNotification(this),
        )
        signalingClient.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        signalingClient.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConnected() {
        Log.d(TAG, "Signaling connected")
    }

    override fun onMessage(message: JSONObject) {
        val type = message.optString("type")
        Log.d(TAG, "Processing message type: $type")
        when (type) {
            "offer" -> handleIncomingOffer(message.getString("sdp"))
            "answer" -> webRtcClient.handleRemoteAnswer(message.getString("sdp"))
            "candidate" -> webRtcClient.handleRemoteCandidate(message)
            "hangup" -> {
                Log.d(TAG, "Received hangup signal from remote, ending call locally")
                webRtcClient.endCall(shouldSendSignal = false)
            }
        }
    }

    override fun onDisconnected() {
        Log.d(TAG, "Signaling disconnected, reconnecting in 3s...")
        serviceScope.launch {
            delay(3000)
            signalingClient.connect()
        }
    }

    private fun handleIncomingOffer(sdp: String) {
        if (webRtcClient.callRole.value != WebRtcClient.CallRole.NONE) {
            Log.w(TAG, "Ignored incoming offer: already in a call")
            return
        }

        webRtcClient.storeIncomingOffer(sdp)
        webRtcClient.callTonePlayer.startRingtone()

        // Always post the notification with fullScreenIntent. 
        // This is the most reliable way to wake the device and show the call UI 
        // regardless of lock state or background restrictions.
        Log.d(TAG, "Posting incoming call notification with fullScreenIntent")
        val notification = CallNotificationHelper.buildIncomingCallNotification(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(CallNotificationHelper.INCOMING_CALL_NOTIFICATION_ID, notification)

        if (shouldWakeForIncomingCall()) {
            wakeScreen()
        }

        // We still attempt to launch the activity directly as a secondary measure
        val callIntent = Intent(this, CallActivity::class.java).apply {
            action = "INCOMING_CALL" // Custom action to distinguish from notification
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching {
            startActivity(callIntent)
            Log.d(TAG, "CallActivity launched successfully")
        }.onFailure {
            Log.e(TAG, "Failed to start CallActivity directly", it)
        }
    }

    private fun shouldWakeForIncomingCall(): Boolean {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val isAppInForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isScreenOn = powerManager.isInteractive
        return !isAppInForeground || !isScreenOn
    }

    private fun wakeScreen() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$TAG:IncomingCallWakeLock",
        )
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    companion object {
        private const val TAG = "SignalingService"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, SignalingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

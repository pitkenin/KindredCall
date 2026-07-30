package com.kindredcall.app.service

import android.app.Service
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class SignalingService : Service(), SignalingClient.Listener {
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRtcClient: WebRtcClient
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
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
        scheduleWatchdog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        scheduleWatchdog(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Service onTaskRemoved - restarting in 1s")
        val restartServiceIntent = Intent(applicationContext, this.javaClass)
        restartServiceIntent.setPackage(packageName)
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext, 1, restartServiceIntent, 
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        signalingClient.removeListener(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.w(TAG, "Foreground service timeout reached for fgsType: $fgsType. Stopping service.")
            stopSelf()
        }
    }

    override fun onConnected() {
        Log.d(TAG, "Signaling connected")
    }

    override fun onMessage(message: JSONObject) {
        val type = message.optString("type")
        Log.d(TAG, "Processing message type: $type")
        when (type) {
            "offer" -> {
                val sdp = message.optString("sdp")
                if (sdp.isBlank()) {
                    Log.w(TAG, "Received offer with blank SDP")
                    return
                }
                handleIncomingOffer(sdp)
            }
            "answer" -> {
                val sdp = message.optString("sdp")
                if (sdp.isBlank()) {
                    Log.w(TAG, "Received answer with blank SDP")
                    return
                }
                webRtcClient.handleRemoteAnswer(sdp)
            }
            "candidate" -> webRtcClient.handleRemoteCandidate(message)
            "hangup" -> {
                Log.d(TAG, "Received hangup signal from remote, ending call locally")
                CallNotificationHelper.cancelIncomingCallNotification(this)
                webRtcClient.endCall(shouldSendSignal = false)
            }
            else -> {
                Log.w(TAG, "Unknown signaling message type: $type")
            }
        }
    }

    override fun onDisconnected() {
        val delayMs = (3000..8000).random().toLong()
        Log.d(TAG, "Signaling disconnected, reconnecting in ${delayMs}ms...")
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            signalingClient.connect()
        }
    }

    private fun handleIncomingOffer(sdp: String) {
        if (webRtcClient.callRole.value != WebRtcClient.CallRole.NONE) {
            Log.w(TAG, "Ignored incoming offer: already in a call")
            return
        }

        webRtcClient.storeIncomingOffer(sdp)

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
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        fun start(context: Context) {
            val intent = Intent(context, SignalingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun scheduleWatchdog(context: Context) {
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = WatchdogReceiver.ACTION_WATCHDOG_TIMER
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            
            try {
                // On Android 12+, we should ideally check canScheduleExactAlarms()
                // but for now we catch SecurityException to prevent crashes.
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                Log.d(TAG, "Watchdog scheduled in 15m")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: Cannot schedule exact alarm. Falling back to non-exact.", e)
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog", e)
            }
        }
    }
}

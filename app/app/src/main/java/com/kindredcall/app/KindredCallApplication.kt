package com.kindredcall.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.kindredcall.app.gallery.GalleryRepository
import com.kindredcall.app.service.SignalingService
import com.kindredcall.app.signaling.SignalingClient
import com.kindredcall.app.webrtc.WebRtcClient
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class KindredCallApplication : Application(), ImageLoaderFactory {
    lateinit var signalingClient: SignalingClient
        private set

    lateinit var webRtcClient: WebRtcClient
        private set

    private val authInterceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${CallConfig.SHARED_TOKEN}")
                .build()
        )
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    val galleryRepository: GalleryRepository = GalleryRepository(okHttpClient, Gson())

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecoveryHandler()
        signalingClient = SignalingClient(okHttpClient)
        webRtcClient = WebRtcClient(this, signalingClient)
    }

    private fun installCrashRecoveryHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Attempt to schedule a restart of the SignalingService after a crash.
            // NOTE: This does not survive an OEM force-stop, since a force-stopped 
            // package receives no alarms, broadcasts, or jobs. It only recovers 
            // ordinary background/foreground crashes.
            runCatching {
                val intent = Intent(this, SignalingService::class.java)
                val pendingIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getService(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                alarmManager.set(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + 3000,
                    pendingIntent
                )
                Log.i("KindredCallApp", "Scheduled SignalingService recovery alarm for crash on thread: ${thread.name}")
            }.onFailure {
                Log.e("KindredCallApp", "Failed to schedule crash recovery alarm", it)
            }

            // Always delegate to the default handler so the crash is reported and the process dies.
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient(okHttpClient).build()

    companion object {
        lateinit var instance: KindredCallApplication
            private set
    }
}

package com.kindredcall.app

import android.app.Application
import com.kindredcall.app.gallery.GalleryRepository
import com.kindredcall.app.signaling.SignalingClient
import com.kindredcall.app.webrtc.WebRtcClient
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class KindredCallApplication : Application() {
    lateinit var signalingClient: SignalingClient
        private set

    lateinit var webRtcClient: WebRtcClient
        private set

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    val galleryRepository: GalleryRepository = GalleryRepository(okHttpClient, Gson())

    override fun onCreate() {
        super.onCreate()
        instance = this
        signalingClient = SignalingClient(okHttpClient)
        webRtcClient = WebRtcClient(this, signalingClient)
    }

    companion object {
        lateinit var instance: KindredCallApplication
            private set
    }
}

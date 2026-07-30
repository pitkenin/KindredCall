package com.kindredcall.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.kindredcall.app.gallery.GalleryRepository
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
        signalingClient = SignalingClient(okHttpClient)
        webRtcClient = WebRtcClient(this, signalingClient)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient(okHttpClient).build()

    companion object {
        lateinit var instance: KindredCallApplication
            private set
    }
}

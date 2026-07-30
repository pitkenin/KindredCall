package com.kindredcall.app.signaling

import android.util.Log
import com.kindredcall.app.BuildConfig
import com.kindredcall.app.CallConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SignalingClient(
    private val okHttpClient: OkHttpClient,
) {
    private val endpoints = CallConfig.SIGNALING_URLS
    private var endpointIndex = 0

    private val clientId = UUID.randomUUID().toString()
    interface Listener {
        fun onConnected()
        fun onMessage(message: JSONObject)
        fun onDisconnected()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var webSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun connect() {
        if (webSocket != null) return
        if (endpoints.isEmpty()) {
            Log.e(TAG, "No signaling endpoints configured")
            return
        }

        val url = endpoints[endpointIndex % endpoints.size]
        Log.d(TAG, "Connecting to $url")
        val request = Request.Builder()
            .url("$url?role=${BuildConfig.USER_TYPE}")
            .build()

        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected")
                    _isConnected.value = true
                    listeners.forEach { 
                        runCatching { it.onConnected() }
                            .onFailure { Log.e(TAG, "Error in onConnected listener", it) }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.v(TAG, "Raw message: $text")
                    runCatching { JSONObject(text) }
                        .onSuccess { json ->
                            val type = json.optString("type")
                            val senderId = json.optString("senderId")
                            Log.d(TAG, "Received message: $type from $senderId")
                            
                            if (senderId != clientId) {
                                listeners.forEach { 
                                    runCatching { it.onMessage(json) }
                                        .onFailure { Log.e(TAG, "Error in onMessage listener", it) }
                                }
                            } else {
                                Log.v(TAG, "Ignored loopback message: $type")
                            }
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Failed to parse signaling message: $text", error)
                        }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $reason")
                    handleDisconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: Ping timeout - connection considered dead, reconnecting", t)
                    handleDisconnect()
                }
            },
        )
    }

    fun send(message: JSONObject) {
        message.put("senderId", clientId)
        val type = message.optString("type")
        Log.d(TAG, "Sending message: $type")
        webSocket?.send(message.toString())
            ?: Log.w(TAG, "Attempted to send while disconnected: $message")
    }

    fun sendOffer(sdp: String) {
        send(
            JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp)
            },
        )
    }

    fun sendAnswer(sdp: String) {
        send(
            JSONObject().apply {
                put("type", "answer")
                put("sdp", sdp)
            },
        )
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        send(
            JSONObject().apply {
                put("type", "candidate")
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
                put("candidate", candidate)
            },
        )
    }

    fun sendHangup() {
        send(
            JSONObject().apply {
                put("type", "hangup")
            },
        )
    }

    fun sendRefreshGallery() {
        send(
            JSONObject().apply {
                put("type", "refresh_gallery")
            },
        )
    }

    fun disconnect() {
        webSocket?.close(NORMAL_CLOSURE, "Client disconnect")
        webSocket = null
    }

    private fun handleDisconnect() {
        endpointIndex++
        webSocket = null
        _isConnected.value = false
        listeners.forEach { 
            runCatching { it.onDisconnected() }
                .onFailure { Log.e(TAG, "Error in onDisconnected listener", it) }
        }
    }

    companion object {
        private const val TAG = "SignalingClient"
        private const val NORMAL_CLOSURE = 1000
    }
}

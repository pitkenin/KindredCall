package com.kindredcall.app.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kindredcall.app.CallConfig
import com.kindredcall.app.notification.CallNotificationHelper
import com.kindredcall.app.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcClient(
    private val context: Context,
    private val signalingClient: SignalingClient,
) {
    private val callAudioManager = CallAudioManager(context)
    val callTonePlayer = CallTonePlayer(context)

    enum class CallRole {
        NONE,
        OUTGOING,
        INCOMING,
    }

    enum class ConnectionState {
        IDLE,
        CONNECTING,
        ACTIVE,
        FAILED,
    }

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false

    private val _callRole = MutableStateFlow(CallRole.NONE)
    val callRole: StateFlow<CallRole> = _callRole.asStateFlow()

    private val _isCallAnswered = MutableStateFlow(false)
    val isCallAnswered: StateFlow<Boolean> = _isCallAnswered.asStateFlow()

    private val clientScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var callTimeoutJob: Job? = null

    val eglBaseContext: EglBase.Context
        get() = eglBase.eglBaseContext

    private val eglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrackInternal: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: Camera2Capturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var pendingOfferSdp: String? = null
    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        audioDeviceModule = adm

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        isInitialized = true
    }

    fun startOutgoingCall() {
        Log.d(TAG, "startOutgoingCall() called")
        initialize()
        resetPeerConnection()
        _callRole.value = CallRole.OUTGOING
        _isCallAnswered.value = true
        _connectionState.value = ConnectionState.CONNECTING
        broadcastCallIntent("com.kindredcall.CALL_ACTIVE")
        callTonePlayer.startRingback()
        setupLocalMedia()
        createOffer()
        armCallTimeout()
    }

    fun storeIncomingOffer(sdp: String) {
        _callRole.value = CallRole.INCOMING
        pendingOfferSdp = sdp
        broadcastCallIntent("com.kindredcall.CALL_ACTIVE")
        callTonePlayer.startRingtone()
        armCallTimeout()
    }

    fun answerIncomingCall() {
        callTimeoutJob?.cancel()
        val offerSdp = pendingOfferSdp ?: return
        Log.d(TAG, "answerIncomingCall() called with pending offer")
        initialize()
        resetPeerConnection()
        _callRole.value = CallRole.INCOMING
        _isCallAnswered.value = true
        _connectionState.value = ConnectionState.CONNECTING
        setupLocalMedia()
        setRemoteDescription(offerSdp, SessionDescription.Type.OFFER) {
            createAnswer()
        }
    }

    fun declineIncomingCall() {
        pendingOfferSdp = null
        if (_callRole.value == CallRole.INCOMING) {
            endCall()
        }
    }

    fun handleSignalingMessage(message: JSONObject) {
        when (message.getString("type")) {
            "offer" -> Unit
            "answer" -> handleRemoteAnswer(message.getString("sdp"))
            "candidate" -> handleRemoteCandidate(message)
            "hangup" -> endCall()
        }
    }

    fun handleRemoteAnswer(sdp: String) {
        if (_callRole.value != CallRole.OUTGOING) return
        setRemoteDescription(sdp, SessionDescription.Type.ANSWER)
    }

    fun handleRemoteCandidate(message: JSONObject) {
        val candidate = IceCandidate(
            message.getString("sdpMid"),
            message.getInt("sdpMLineIndex"),
            message.getString("candidate"),
        )
        if (peerConnection == null || !isRemoteDescriptionSet) {
            Log.d(TAG, "Buffering remote ICE candidate (isRemoteDescriptionSet=$isRemoteDescriptionSet)")
            pendingIceCandidates.add(candidate)
        } else {
            Log.d(TAG, "Adding remote ICE candidate immediately")
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun endCall(shouldSendSignal: Boolean = true) {
        callTimeoutJob?.cancel()
        Log.d(TAG, "Ending call, role: ${_callRole.value}, shouldSendSignal: $shouldSendSignal")
        if (shouldSendSignal && _callRole.value != CallRole.NONE) {
            signalingClient.sendHangup()
        }
        broadcastCallIntent("com.kindredcall.CALL_ENDED")
        callTonePlayer.stopAll()
        callAudioManager.stopCallAudio()
        runCatching { CallNotificationHelper.cancelIncomingCallNotification(context) }
        
        pendingOfferSdp = null
        pendingIceCandidates.clear()
        isRemoteDescriptionSet = false
        _callRole.value = CallRole.NONE
        _isCallAnswered.value = false
        _connectionState.value = ConnectionState.IDLE
        _remoteVideoTrack.value = null
        _localVideoTrack.value = null
        releaseLocalMedia()
        peerConnection?.let {
            Log.d(TAG, "Closing and disposing peer connection")
            runCatching { it.close() }
            runCatching { it.dispose() }
        }
        peerConnection = null
    }

    fun dispose() {
        endCall()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        eglBase.release()
        isInitialized = false
    }

    private fun createPeerConnection(): PeerConnection? {
        val iceServers = CallConfig.TURN_HOSTS.flatMap { host ->
            listOf(
                "turn:$host:3478?transport=udp",
                "turns:$host:443?transport=tcp",
            )
        }.map { uri ->
            PeerConnection.IceServer.builder(uri)
                .setUsername(CallConfig.TURN_USERNAME)
                .setPassword(CallConfig.TURN_CREDENTIAL)
                .createIceServer()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }

        return peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state change: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            _connectionState.value = ConnectionState.ACTIVE
                            callTonePlayer.stopAll()
                            callAudioManager.startCallAudio()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _connectionState.value = ConnectionState.FAILED
                            // Automatically clean up after a failure
                            Log.e(TAG, "ICE connection failed, ending call")
                            endCall()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            Log.w(TAG, "ICE disconnected")
                            // We don't necessarily end call immediately on disconnected as it might reconnect
                        }
                        else -> Unit
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering state change: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    Log.d(TAG, "New ICE Candidate: ${candidate.sdp}")
                    signalingClient.sendIceCandidate(
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                        candidate.sdp,
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit

                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit

                override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit

                override fun onRenegotiationNeeded() = Unit

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        _remoteVideoTrack.value = track
                    }
                }
            },
        )
    }

    private fun resetPeerConnection() {
        Log.d(TAG, "resetPeerConnection() - closing previous connection if exists")
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = createPeerConnection()
        Log.d(TAG, "resetPeerConnection() - peerConnection created: ${peerConnection != null}")
    }

    private fun setupLocalMedia() {
        val factory = peerConnectionFactory ?: return
        val connection = peerConnection ?: return

        val audioConstraints = MediaConstraints()
        val audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).also { track ->
            connection.addTrack(track, listOf(STREAM_ID))
        }

        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

        localVideoTrackInternal = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource).also { track ->
            connection.addTrack(track, listOf(STREAM_ID))
            _localVideoTrack.value = track
        }
    }

    private fun createCameraCapturer(): Camera2Capturer? {
        val enumerator = Camera2Enumerator(context)
        val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null
        return Camera2Capturer(context, frontCamera, null)
    }

    private fun createOffer() {
        Log.d(TAG, "Creating offer...")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(
            object : SimpleSdpObserver("createOffer") {
                override fun onCreateSuccess(description: SessionDescription?) {
                    Log.d(TAG, "createOffer onCreateSuccess")
                    description ?: return
                    val sdpWithBitrate = setMaxBitrate(description.description, 800)
                    val newDescription = SessionDescription(description.type, sdpWithBitrate)
                    
                    peerConnection?.setLocalDescription(
                        object : SimpleSdpObserver("setLocalOffer") {
                            override fun onSetSuccess() {
                                Log.d(TAG, "setLocalOffer onSetSuccess - signaling offer")
                                signalingClient.sendOffer(newDescription.description)
                            }
                        },
                        newDescription,
                    )
                }
            },
            constraints,
        )
    }

    private fun createAnswer() {
        Log.d(TAG, "Creating answer...")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(
            object : SimpleSdpObserver("createAnswer") {
                override fun onCreateSuccess(description: SessionDescription?) {
                    Log.d(TAG, "createAnswer onCreateSuccess")
                    description ?: return
                    val sdpWithBitrate = setMaxBitrate(description.description, 800)
                    val newDescription = SessionDescription(description.type, sdpWithBitrate)

                    peerConnection?.setLocalDescription(
                        object : SimpleSdpObserver("setLocalAnswer") {
                            override fun onSetSuccess() {
                                Log.d(TAG, "setLocalAnswer onSetSuccess - signaling answer")
                                signalingClient.sendAnswer(newDescription.description)
                            }
                        },
                        newDescription,
                    )
                }
            },
            constraints,
        )
    }

    private fun setMaxBitrate(sdp: String, maxBitrateKbps: Int): String {
        val lines = sdp.split("\r\n").toMutableList()
        var videoLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].startsWith("m=video")) {
                videoLineIndex = i
                break
            }
        }
        if (videoLineIndex == -1) return sdp

        // Find the next line after m=video that starts with c= or a=
        var i = videoLineIndex + 1
        while (i < lines.size && !lines[i].startsWith("m=")) {
            if (lines[i].startsWith("b=AS:")) {
                lines[i] = "b=AS:$maxBitrateKbps"
                return lines.joinToString("\r\n")
            }
            i++
        }
        
        // If no b=AS line found, insert one
        lines.add(videoLineIndex + 1, "b=AS:$maxBitrateKbps")
        return lines.joinToString("\r\n")
    }

    private fun setRemoteDescription(
        sdp: String,
        type: SessionDescription.Type,
        onSuccess: (() -> Unit)? = null,
    ) {
        Log.d(TAG, "Setting remote description: $type")
        val description = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription") {
                override fun onSetSuccess() {
                    Log.d(TAG, "setRemoteDescription Success. Draining ${pendingIceCandidates.size} candidates.")
                    isRemoteDescriptionSet = true
                    pendingIceCandidates.forEach { 
                        Log.d(TAG, "Adding buffered candidate: ${it.sdpMid}")
                        peerConnection?.addIceCandidate(it) 
                    }
                    pendingIceCandidates.clear()
                    onSuccess?.invoke()
                }

                override fun onSetFailure(error: String?) {
                    super.onSetFailure(error)
                    Log.e(TAG, "setRemoteDescription Failed: $error")
                }
            },
            description,
        )
    }

    private fun releaseLocalMedia() {
        Log.d(TAG, "Releasing local media resources")
        runCatching {
            videoCapturer?.stopCapture()
        }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        
        runCatching { localVideoTrackInternal?.dispose() }
        localVideoTrackInternal = null
        
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        Log.d(TAG, "Local media resources released")
    }

    private open class SimpleSdpObserver(
        private val label: String,
    ) : org.webrtc.SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$label onCreateFailure: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$label onSetFailure: $error")
        }
    }

    private fun broadcastCallIntent(action: String) {
        Log.d(TAG, "Broadcasting intent: $action")
        val intent = Intent(action)
        context.sendBroadcast(intent)
    }

    private fun armCallTimeout() {
        callTimeoutJob?.cancel()
        callTimeoutJob = clientScope.launch {
            delay(CALL_TIMEOUT_MS)
            if (_callRole.value != CallRole.NONE && _connectionState.value != ConnectionState.ACTIVE) {
                Log.w(TAG, "Call timeout reached (role=${_callRole.value}, state=${_connectionState.value}). Ending call.")
                endCall(shouldSendSignal = true)
            }
        }
    }

    companion object {
        private const val TAG = "WebRtcClient"
        private const val CALL_TIMEOUT_MS = 60_000L
        private const val STREAM_ID = "kindredcall"
        private const val AUDIO_TRACK_ID = "audio0"
        private const val VIDEO_TRACK_ID = "video0"
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480
        private const val CAPTURE_FPS = 20
    }
}

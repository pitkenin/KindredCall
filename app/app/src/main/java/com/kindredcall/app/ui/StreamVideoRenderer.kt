package com.kindredcall.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

@Composable
fun StreamVideoRenderer(
    videoTrack: VideoTrack,
    eglBaseContext: EglBase.Context,
    modifier: Modifier = Modifier,
    isMirrored: Boolean = false,
) {
    var boundTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var rendererView by remember { mutableStateOf<VideoTextureViewRenderer?>(null) }

    DisposableEffect(videoTrack) {
        onDispose {
            rendererView?.let { view -> boundTrack?.removeSink(view) }
            boundTrack = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoTextureViewRenderer(context).apply {
                init(
                    eglBaseContext,
                    object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() = Unit

                        override fun onFrameResolutionChanged(
                            videoWidth: Int,
                            videoHeight: Int,
                            rotation: Int,
                        ) = Unit
                    },
                )
                setMirror(isMirrored)
                rendererView = this
                if (boundTrack != videoTrack) {
                    boundTrack?.removeSink(this)
                    boundTrack = videoTrack
                    videoTrack.addSink(this)
                }
            }
        },
        update = { view ->
            if (boundTrack != videoTrack) {
                boundTrack?.removeSink(view)
                boundTrack = videoTrack
                videoTrack.addSink(view)
            }
        },
    )
}

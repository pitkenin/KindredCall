package com.kindredcall.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.kindredcall.app.notification.CallNotificationHelper
import com.kindredcall.app.ui.StreamVideoRenderer
import com.kindredcall.app.ui.theme.KindredCallTheme
import com.kindredcall.app.webrtc.WebRtcClient
import com.kindredcall.app.BuildConfig

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as KindredCallApplication
        val webRtcClient = app.webRtcClient

        // Stop notification immediately on entry
        CallNotificationHelper.cancelIncomingCallNotification(this)

        Log.d("CallActivity", "onCreate: role=${webRtcClient.callRole.value}, action=${intent.action}")
        if (webRtcClient.callRole.value == WebRtcClient.CallRole.NONE) {
            Log.d("CallActivity", "Call already ended, finishing immediately")
            finish()
            return
        }

        // Handle notification actions
        when (intent.action) {
            "ANSWER_CALL" -> {
                Log.d("CallActivity", "Action: ANSWER_CALL")
                webRtcClient.callTonePlayer.stopAll()
                webRtcClient.answerIncomingCall()
            }
            "DECLINE_CALL" -> {
                Log.d("CallActivity", "Action: DECLINE_CALL")
                webRtcClient.endCall()
                finish()
                return
            }
        }

        setContent {
            KindredCallTheme {
                val remoteVideoTrack by webRtcClient.remoteVideoTrack.collectAsState()
                val localVideoTrack by webRtcClient.localVideoTrack.collectAsState()
                val callRole by webRtcClient.callRole.collectAsState()
                val connectionState by webRtcClient.connectionState.collectAsState()
                val isCallAnswered by webRtcClient.isCallAnswered.collectAsState()

                Log.d("CallActivity", "UI Update: role=$callRole, state=$connectionState, answered=$isCallAnswered")

                // Automatically close the activity ONLY if the call is ended (NONE)
                androidx.compose.runtime.LaunchedEffect(callRole) {
                    Log.d("CallActivity", "LaunchedEffect: role changed to $callRole")
                    if (callRole == WebRtcClient.CallRole.NONE) {
                        Log.d("CallActivity", "Finishing activity due to NONE role")
                        finish()
                    }
                }

                CallScreen(
                    callRole = callRole,
                    isCallAnswered = isCallAnswered,
                    connectionState = connectionState,
                    remoteVideoTrack = remoteVideoTrack,
                    localVideoTrack = localVideoTrack,
                    eglBaseContext = webRtcClient.eglBaseContext,
                    onAnswer = {
                        Log.d("CallActivity", "User clicked ANSWER")
                        webRtcClient.callTonePlayer.stopAll()
                        webRtcClient.answerIncomingCall()
                    },
                    onDecline = {
                        Log.d("CallActivity", "User clicked DECLINE/END")
                        webRtcClient.endCall()
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        CallNotificationHelper.cancelIncomingCallNotification(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val webRtcClient = (application as KindredCallApplication).webRtcClient

        // Stop notification immediately
        CallNotificationHelper.cancelIncomingCallNotification(this)
        
        when (intent.action) {
            "ANSWER_CALL" -> {
                Log.d("CallActivity", "onNewIntent: ANSWER_CALL")
                webRtcClient.callTonePlayer.stopAll()
                webRtcClient.answerIncomingCall()
            }
            "DECLINE_CALL" -> {
                Log.d("CallActivity", "onNewIntent: DECLINE_CALL")
                webRtcClient.endCall()
                finish()
            }
        }
    }
}

@Composable
private fun CallScreen(
    callRole: WebRtcClient.CallRole,
    isCallAnswered: Boolean,
    connectionState: WebRtcClient.ConnectionState,
    remoteVideoTrack: org.webrtc.VideoTrack?,
    localVideoTrack: org.webrtc.VideoTrack?,
    eglBaseContext: org.webrtc.EglBase.Context,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)), // Unified dark background
    ) {
        // If it's an outgoing call OR we have already answered an incoming call
        if (callRole == WebRtcClient.CallRole.OUTGOING || isCallAnswered) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Remote Video (Full Screen)
                if (connectionState == WebRtcClient.ConnectionState.ACTIVE && remoteVideoTrack != null) {
                    StreamVideoRenderer(
                        videoTrack = remoteVideoTrack,
                        eglBaseContext = eglBaseContext,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (connectionState == WebRtcClient.ConnectionState.FAILED) stringResource(R.string.calling_error) else stringResource(R.string.calling_connection),
                            color = Color.White,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                        if (connectionState != WebRtcClient.ConnectionState.FAILED) {
                            CircularProgressIndicator(
                                color = Color(0xFF00C853),
                                strokeWidth = 8.dp,
                                modifier = Modifier.size(80.dp)
                            )
                        }
                    }
                }

                // Local Video (Self View - Small window)
                if (localVideoTrack != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .fillMaxWidth(0.35f)
                            .aspectRatio(0.7f)
                            .shadow(8.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.DarkGray),
                    ) {
                        StreamVideoRenderer(
                            videoTrack = localVideoTrack,
                            eglBaseContext = eglBaseContext,
                            modifier = Modifier.fillMaxSize(),
                            isMirrored = true,
                        )
                    }
                }

                // End Call Button
                Button(
                    onClick = onDecline,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp, start = 32.dp, end = 32.dp)
                        .fillMaxWidth()
                        .height(90.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828), // Consistent red
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.btn_decline),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                }
            }
        } else {
            // Incoming Call UI (Ringing)
            val isYulia = BuildConfig.USER_TYPE == "YULIA"
            val callerName = if (isYulia) stringResource(R.string.caller_grandma) else stringResource(R.string.caller_yulia)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    var nameFontSize by remember { mutableStateOf(80.sp) }
                    Text(
                        text = callerName,
                        color = Color.White,
                        fontSize = nameFontSize,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        lineHeight = nameFontSize,
                        maxLines = 1,
                        softWrap = false,
                        onTextLayout = { textLayoutResult ->
                            if (textLayoutResult.didOverflowWidth) {
                                nameFontSize *= 0.9f
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.status_ringing),
                        color = Color(0xFFBDBDBD),
                        fontSize = 48.sp, // Clear and big
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(
                        onClick = onAnswer,
                        modifier = Modifier
                            .weight(1f)
                            .height(160.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp), // Even less padding
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(28.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.btn_answer),
                                fontSize = 18.sp, // Smaller to ensure fit
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Button(
                        onClick = onDecline,
                        modifier = Modifier
                            .weight(1f)
                            .height(160.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp), // Even less padding
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(28.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.btn_decline),
                                fontSize = 18.sp, // Smaller to ensure fit
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

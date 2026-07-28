package com.kindredcall.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.kindredcall.app.gallery.GalleryRepository
import com.kindredcall.app.service.SignalingService
import com.kindredcall.app.ui.theme.KindredCallTheme
import com.kindredcall.app.webrtc.WebRtcClient
import com.kindredcall.app.BuildConfig
import com.kindredcall.app.signaling.SignalingClient
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignalingService.start(this)
        enableEdgeToEdge()

        val app = application as KindredCallApplication
        val webRtcClient = app.webRtcClient
        val signalingClient = app.signalingClient
        val galleryRepository = app.galleryRepository

        setContent {
            KindredCallTheme {
                MainScreen(
                    galleryRepository = galleryRepository,
                    signalingClient = signalingClient,
                    onCallAleksanteri = {
                        webRtcClient.startOutgoingCall()
                        val intent = Intent(this, CallActivity::class.java)
                        startActivity(intent)
                    },
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    galleryRepository: GalleryRepository,
    signalingClient: SignalingClient,
    onCallAleksanteri: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var imageUrls by remember { mutableStateOf<List<String>?>(null) }
    
    // Permission States
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasMicPermission by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasStoragePermission by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var isBatteryOptimized by remember { mutableStateOf(false) }

    fun updatePermissions() {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasOverlayPermission = Settings.canDrawOverlays(context)
        isBatteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun refreshGallery() {
        Log.d("MainActivity", "Refreshing gallery...")
        coroutineScope.launch {
            galleryRepository.fetchImageUrls()
                .onSuccess { 
                    Log.d("MainActivity", "Gallery fetched successfully: ${it.size} images")
                    imageUrls = it 
                }
                .onFailure { error ->
                    Log.e("MainActivity", "Gallery fetch failed", error)
                    Toast.makeText(context, context.getString(R.string.connection_error_prefix, error.localizedMessage), Toast.LENGTH_LONG).show()
                }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            updatePermissions()
            refreshGallery() // Initial fetch
            
            // Safety backup: Refresh every 60 seconds while app is open
            while (isActive) {
                delay(60_000)
                Log.d("MainActivity", "Scheduled background refresh...")
                refreshGallery()
            }
        }
    }

    DisposableEffect(signalingClient) {
        val listener = object : SignalingClient.Listener {
            override fun onConnected() {
                Log.d("MainActivity", "Signaling connected")
            }
            override fun onMessage(message: JSONObject) {
                val type = message.optString("type")
                Log.d("MainActivity", "Received signaling message: $type")
                if (type == "refresh_gallery") {
                    refreshGallery()
                }
            }
            override fun onDisconnected() {
                Log.d("MainActivity", "Signaling disconnected")
            }
        }
        signalingClient.addListener(listener)
        onDispose {
            signalingClient.removeListener(listener)
        }
    }

    val isSetupComplete = hasCameraPermission && hasMicPermission && hasNotificationPermission && hasOverlayPermission && !isBatteryOptimized && hasStoragePermission
    val isYulia = BuildConfig.USER_TYPE == "YULIA"
    val isConnected by signalingClient.isConnected.collectAsState()
    var isUploading by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            coroutineScope.launch {
                isUploading = true
                galleryRepository.uploadImage(context, it)
                    .onSuccess {
                        Toast.makeText(context, "Фото загружено успешно!", Toast.LENGTH_SHORT).show()
                        Log.d("MainActivity", "Sending refresh_gallery signal...")
                        signalingClient.sendRefreshGallery()
                        refreshGallery()
                    }
                    .onFailure { error ->
                        Log.e("MainActivity", "Upload failed", error)
                        Toast.makeText(context, "Ошибка загрузки: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                isUploading = false
            }
        }
    }

    fun uploadLatestPhoto() {
        val uri = galleryRepository.getLatestImageUri(context)
        if (uri == null) {
            Toast.makeText(context, "Сначала сделайте фото на камеру!", Toast.LENGTH_LONG).show()
            return
        }

        coroutineScope.launch {
            isUploading = true
            galleryRepository.uploadImage(context, uri)
                .onSuccess {
                    Toast.makeText(context, "Фото отправлено Юле!", Toast.LENGTH_SHORT).show()
                    signalingClient.sendRefreshGallery()
                    refreshGallery()
                }
                .onFailure { error ->
                    Toast.makeText(context, "Ошибка отправки: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            isUploading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A))
        ) {
            if (!isSetupComplete) {
                PermissionSetupCenter(
                    hasCamera = hasCameraPermission,
                    hasMic = hasMicPermission,
                    hasNotif = hasNotificationPermission,
                    hasStorage = hasStoragePermission,
                    hasOverlay = hasOverlayPermission,
                    isBatteryOptimized = isBatteryOptimized,
                    onUpdate = { updatePermissions() }
                )
            }

            // Upper part: The Call Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (isSetupComplete) 0.35f else 0.25f) // Adjusted for 2 buttons
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Button(
                    onClick = onCallAleksanteri,
                    modifier = Modifier.fillMaxSize(),
                    enabled = isSetupComplete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        contentColor = Color.White,
                        disabledContainerColor = Color.DarkGray
                    ),
                    shape = RoundedCornerShape(32.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = if (isSetupComplete) {
                                if (isYulia) stringResource(R.string.call_grandma) else stringResource(R.string.call_yulia)
                            } else stringResource(R.string.setup_needed),
                            fontSize = 32.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Grandma's One-Tap Share Button
            if (!isYulia && isSetupComplete) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp) // Large but smaller than call
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        onClick = { uploadLatestPhoto() },
                        modifier = Modifier.fillMaxSize(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00796B), // Teal color
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.btn_send_photo),
                                fontSize = 24.sp,
                                lineHeight = 28.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Lower part: The Modern Photo Frame (Expands to fill everything else)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                PhotoFrame(
                    imageUrl = imageUrls?.firstOrNull(),
                    isLoading = imageUrls == null || isUploading,
                    isYulia = isYulia,
                    onEdit = { pickerLauncher.launch("image/*") }
                )
            }
        }

        // Connection Status Indicator (Diagnostics)
        Text(
            text = if (isConnected) stringResource(R.string.signal_connected) else stringResource(R.string.signal_disconnected),
            color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
        )
    }
}

@Composable
private fun PermissionSetupCenter(
    hasCamera: Boolean,
    hasMic: Boolean,
    hasNotif: Boolean,
    hasStorage: Boolean,
    hasOverlay: Boolean,
    isBatteryOptimized: Boolean,
    onUpdate: () -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onUpdate()
    }

    val isXiaomi = remember { 
        Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) || 
        Build.MANUFACTURER.contains("Redmi", ignoreCase = true)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF37474F)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB300))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.phone_setup_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.phone_setup_desc),
                color = Color.LightGray,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!hasCamera || !hasMic || !hasNotif || !hasStorage) {
                SetupButton(text = stringResource(R.string.btn_grant_access)) {
                    val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    launcher.launch(perms.toTypedArray())
                }
            }

            if (!hasOverlay) {
                SetupButton(text = stringResource(R.string.btn_overlay)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            }

            if (isBatteryOptimized) {
                SetupButton(text = stringResource(R.string.btn_background)) {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            }

            if (isXiaomi) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.xiaomi_header),
                    color = Color(0xFFFFB300),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                SetupButton(text = stringResource(R.string.btn_xiaomi_settings)) {
                    val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        putExtra("extra_pkgname", context.packageName)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(fallbackIntent)
                    }
                }
                Text(
                    text = stringResource(R.string.xiaomi_desc),
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SetupButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF546E7A)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PhotoFrame(
    imageUrl: String?,
    isLoading: Boolean,
    isYulia: Boolean = false,
    onEdit: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp) // The "Mat" effect
                .background(Color(0xFFF9F9F9)),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(color = Color(0xFF2E7D32))
                }
                imageUrl != null -> {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Family photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    Text(
                        text = if (isYulia) stringResource(R.string.add_photo_for_grandma) else stringResource(R.string.waiting_for_photos),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (isYulia) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(8.dp, RoundedCornerShape(32.dp))
                            .background(Color(0xFF2E7D32), RoundedCornerShape(32.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Change photo",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

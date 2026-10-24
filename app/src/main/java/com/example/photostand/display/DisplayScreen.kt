// File: app/src/main/java/com/example/photostand/display/DisplayScreen.kt
package com.example.photostand.display

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.photostand.core.AnimationType
import com.example.photostand.core.FitMode
import com.example.photostand.p2p.pairing.QrCodeGenerator
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayScreen(
    viewModel: DisplayViewModel,
    onSwitchMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    // Immersive Mode & Keep Screen On
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Apply Screen Brightness Override
    LaunchedEffect(uiState.effectiveBrightness) {
        val window = activity?.window
        if (window != null) {
            val layoutParams = window.attributes
            layoutParams.screenBrightness = uiState.effectiveBrightness
            window.attributes = layoutParams
        }
    }

    var showControls by remember { mutableStateOf(false) }

    // Auto-hide controls after 5 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000)
            showControls = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
            .testTag("display_screen_canvas")
    ) {
        // Main Photo Canvas with selected Animation & Fit Mode
        PhotoCanvas(
            photoPath = uiState.currentPhotoPath,
            fitMode = uiState.fitMode,
            animationType = uiState.animationType,
            trigger = uiState.newPhotoTrigger
        )

        // Clock & Status Overlay
        if (uiState.showClock) {
            DisplayClockOverlay(
                deviceName = uiState.deviceName,
                isWifi = uiState.isWifiConnected,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
            )
        }

        // Minimalist On-Screen HUD Controls (revealed on tap)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.setShowPairingQr(true) },
                        modifier = Modifier.testTag("display_qr_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = stringResource(R.string.display_qr_title),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleSettingsSheet() },
                        modifier = Modifier.testTag("display_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.display_settings),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = onSwitchMode,
                        modifier = Modifier.testTag("display_switch_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.switch_mode),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    // Settings Modal Bottom Sheet
    if (uiState.showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.toggleSettingsSheet() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            DisplaySettingsContent(
                uiState = uiState,
                onBrightnessChange = { viewModel.setBrightness(it) },
                onAutoDimToggle = { viewModel.setAutoDimEnabled(it) },
                onFitModeChange = { viewModel.setFitMode(it) },
                onAnimTypeChange = { viewModel.setAnimationType(it) },
                onToggleClock = { viewModel.toggleShowClock() },
                onShowQr = {
                    viewModel.toggleSettingsSheet()
                    viewModel.setShowPairingQr(true)
                },
                onSwitchMode = {
                    viewModel.toggleSettingsSheet()
                    onSwitchMode()
                }
            )
        }
    }

    // Pairing QR Code Dialog
    if (uiState.showPairingQrDialog) {
        DisplayPairingDialog(
            uiState = uiState,
            onDismiss = { viewModel.setShowPairingQr(false) }
        )
    }
}

// region Photo Canvas & Animations
@Composable
private fun PhotoCanvas(
    photoPath: String?,
    fitMode: FitMode,
    animationType: AnimationType,
    trigger: Long,
    modifier: Modifier = Modifier
) {
    if (photoPath == null) {
        // Fallback default serene art
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_photostand_logo_1792867122465),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.display_waiting_photo),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
        return
    }

    val context = LocalContext.current
    val imageRequest = remember(photoPath) {
        ImageRequest.Builder(context)
            .data(File(photoPath))
            .crossfade(true)
            .build()
    }

    when (animationType) {
        AnimationType.PAPER_SCREEN -> {
            PaperScreenAnimatedImage(
                imageRequest = imageRequest,
                fitMode = fitMode,
                trigger = trigger,
                modifier = modifier
            )
        }
        AnimationType.TILE_GRID -> {
            TileGridAnimatedImage(
                imageRequest = imageRequest,
                fitMode = fitMode,
                trigger = trigger,
                modifier = modifier
            )
        }
    }
}

/**
 * Paper Screen Animation:
 * Blank screen -> Grayscale photograph -> Full vibrant color crossfade
 */
@Composable
private fun PaperScreenAnimatedImage(
    imageRequest: ImageRequest,
    fitMode: FitMode,
    trigger: Long,
    modifier: Modifier = Modifier
) {
    val saturationAnim = remember { Animatable(0f) }
    val alphaAnim = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        // Reset and run sequence
        alphaAnim.snapTo(0f)
        saturationAnim.snapTo(0f)

        // 1. Reveal grayscale photo (Blank -> B&W)
        alphaAnim.animateTo(1f, animationSpec = tween(600, easing = LinearEasing))
        delay(200)

        // 2. Grayscale -> Full Color
        saturationAnim.animateTo(1f, animationSpec = tween(1000, easing = FastOutSlowInEasing))
    }

    val colorMatrix = remember(saturationAnim.value) {
        ColorMatrix().apply { setToSaturation(saturationAnim.value) }
    }

    RenderImageWithFit(
        imageRequest = imageRequest,
        fitMode = fitMode,
        alpha = alphaAnim.value,
        colorFilter = ColorFilter.colorMatrix(colorMatrix),
        modifier = modifier
    )
}

/**
 * Tile Grid Animation:
 * Reveals image via staggered scanline tiles
 */
@Composable
private fun TileGridAnimatedImage(
    imageRequest: ImageRequest,
    fitMode: FitMode,
    trigger: Long,
    modifier: Modifier = Modifier
) {
    val revealProgress = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        revealProgress.snapTo(0f)
        revealProgress.animateTo(1f, animationSpec = tween(1200, easing = FastOutSlowInEasing))
    }

    Box(modifier = modifier.fillMaxSize()) {
        RenderImageWithFit(
            imageRequest = imageRequest,
            fitMode = fitMode,
            alpha = revealProgress.value,
            colorFilter = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun RenderImageWithFit(
    imageRequest: ImageRequest,
    fitMode: FitMode,
    alpha: Float,
    colorFilter: ColorFilter?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (fitMode) {
            FitMode.CROP_FILL -> {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Displayed photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(alpha),
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter
                )
            }
            FitMode.CONTAIN_LETTERBOX -> {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Displayed photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(alpha),
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter
                )
            }
            FitMode.BLUR_BACKGROUND -> {
                // Background blurred
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(32.dp)
                        .alpha(0.6f * alpha),
                    contentScale = ContentScale.Crop
                )
                // Foreground contained
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Displayed photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(alpha),
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter
                )
            }
        }
    }
}
// endregion

// region Clock & Status
@Composable
private fun DisplayClockOverlay(
    deviceName: String,
    isWifi: Boolean,
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf(Date()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Date()
            delay(1000)
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = timeFormat.format(currentTime),
            fontSize = 38.sp,
            fontWeight = FontWeight.Light,
            color = Color.White.copy(alpha = 0.95f),
            fontFamily = FontFamily.SansSerif
        )
        Text(
            text = dateFormat.format(currentTime),
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.75f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isWifi) Icons.Default.Wifi else Icons.Default.WifiOff,
                contentDescription = null,
                tint = if (isWifi) Color(0xFF38BDF8) else Color(0xFFEF4444),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = deviceName,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}
// endregion

// region Settings Content
@Composable
private fun DisplaySettingsContent(
    uiState: DisplayUiState,
    onBrightnessChange: (Float) -> Unit,
    onAutoDimToggle: (Boolean) -> Unit,
    onFitModeChange: (FitMode) -> Unit,
    onAnimTypeChange: (AnimationType) -> Unit,
    onToggleClock: () -> Unit,
    onShowQr: () -> Unit,
    onSwitchMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = stringResource(R.string.display_settings),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Brightness Slider
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.display_brightness),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${(uiState.brightness * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Slider(
                    value = uiState.brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = 0.05f..1.0f,
                    modifier = Modifier.testTag("brightness_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.display_auto_dim),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.display_auto_dim_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoDimEnabled,
                        onCheckedChange = onAutoDimToggle,
                        modifier = Modifier.testTag("auto_dim_switch")
                    )
                }
            }
        }

        // Fit Mode
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.display_fit_mode),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.fitMode == FitMode.CROP_FILL,
                    onClick = { onFitModeChange(FitMode.CROP_FILL) },
                    label = { Text(stringResource(R.string.fit_crop)) },
                    modifier = Modifier.testTag("fit_crop_chip")
                )
                FilterChip(
                    selected = uiState.fitMode == FitMode.CONTAIN_LETTERBOX,
                    onClick = { onFitModeChange(FitMode.CONTAIN_LETTERBOX) },
                    label = { Text(stringResource(R.string.fit_contain)) },
                    modifier = Modifier.testTag("fit_contain_chip")
                )
                FilterChip(
                    selected = uiState.fitMode == FitMode.BLUR_BACKGROUND,
                    onClick = { onFitModeChange(FitMode.BLUR_BACKGROUND) },
                    label = { Text(stringResource(R.string.fit_blur)) },
                    modifier = Modifier.testTag("fit_blur_chip")
                )
            }
        }

        // Animation Mode
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.display_animation),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.animationType == AnimationType.PAPER_SCREEN,
                    onClick = { onAnimTypeChange(AnimationType.PAPER_SCREEN) },
                    label = { Text("Paper Screen") },
                    modifier = Modifier.testTag("anim_paper_chip")
                )
                FilterChip(
                    selected = uiState.animationType == AnimationType.TILE_GRID,
                    onClick = { onAnimTypeChange(AnimationType.TILE_GRID) },
                    label = { Text("Tile Grid") },
                    modifier = Modifier.testTag("anim_tile_chip")
                )
            }
        }

        // Clock Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.display_clock_overlay),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Switch(
                checked = uiState.showClock,
                onCheckedChange = { onToggleClock() },
                modifier = Modifier.testTag("clock_overlay_switch")
            )
        }

        // Action Buttons
        Button(
            onClick = onShowQr,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("show_pairing_qr_button")
        ) {
            Icon(Icons.Default.QrCode, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.display_qr_title))
        }

        Button(
            onClick = onSwitchMode,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("switch_to_controller_button")
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.mode_controller))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
// endregion

// region Pairing QR Code Dialog
@Composable
private fun DisplayPairingDialog(
    uiState: DisplayUiState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrImageBitmap = remember(uiState.pairingToken) {
        QrCodeGenerator.generateQrBitmap(uiState.pairingToken, size = 400)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.display_qr_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.display_qr_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // QR Code Image
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(240.dp)
                ) {
                    Image(
                        bitmap = qrImageBitmap,
                        contentDescription = "Pairing QR Code",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                }

                // IP & Network info
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${stringResource(R.string.display_device_id)}: ${uiState.deviceId}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "${stringResource(R.string.display_local_ip)}: http://${uiState.localIp}:${uiState.port}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Copy Token Button
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("PhotoStand Token", uiState.pairingToken)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Pairing token copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("copy_token_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Pairing Token")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_qr_dialog_button")
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
// endregion

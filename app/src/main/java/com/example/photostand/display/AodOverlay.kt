// File: app/src/main/java/com/example/photostand/display/AodOverlay.kt
package com.example.photostand.display

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photostand.core.Constants
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real Always-On Display ambient surface.
 *
 * - Pure #000000 background so AMOLED pixels draw essentially zero power.
 * - Monochrome clock/date/status, dimmed to ambient brightness by the host screen.
 * - Anti burn-in pixel shift: the whole cluster drifts between five small offsets on a
 *   fixed period so no static pixel stays lit in the same place indefinitely.
 * - Optional red night-vision palette during the configured night window.
 */
@Composable
fun AodOverlay(
    deviceName: String,
    isWifi: Boolean,
    bluetoothOn: Boolean,
    batteryPercent: Int?,
    nightRed: Boolean,
    isNight: Boolean,
    modifier: Modifier = Modifier
) {
    val contentColor = if (nightRed && isNight) Color(Constants.AOD_NIGHT_RED_COLOR) else Color(0xCCE2E8F0)
    val secondaryColor = if (nightRed && isNight) Color(0x99FF6B6B) else Color(0x8094A3B8)

    // Five deterministic drift positions — each is only a few pixels from centre.
    val offsets = remember {
        listOf(
            IntOffset(0, 0),
            IntOffset(3, -2),
            IntOffset(-3, 2),
            IntOffset(2, 3),
            IntOffset(-2, -3)
        )
    }
    var positionIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Constants.AOD_PIXEL_SHIFT_PERIOD_MS)
            positionIndex = (positionIndex + 1) % offsets.size
        }
    }
    val animatedOffset by animateIntOffsetAsState(
        targetValue = offsets[positionIndex],
        animationSpec = tween(durationMillis = 2_500, easing = LinearEasing),
        label = "aodPixelShift"
    )

    var now by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            now++
            delay(1_000L)
        }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val current = Date()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { animatedOffset }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = timeFormat.format(current),
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                color = contentColor
            )
            Text(
                text = dateFormat.format(current),
                fontSize = 16.sp,
                color = secondaryColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (isWifi) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = secondaryColor,
                    modifier = Modifier.size(16.dp)
                )
                if (bluetoothOn) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = secondaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                batteryPercent?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            tint = secondaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "$it%",
                            fontSize = 13.sp,
                            color = secondaryColor,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = deviceName,
                fontSize = 13.sp,
                color = secondaryColor
            )
        }
    }
}

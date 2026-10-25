// File: app/src/main/java/com/example/MainActivity.kt
package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.photostand.controller.ControllerScreen
import com.example.photostand.controller.ControllerViewModel
import com.example.photostand.core.AppMode
import com.example.photostand.display.DisplayScreen
import com.example.photostand.display.DisplayViewModel
import com.example.photostand.p2p.pairing.PairingStore
import com.example.photostand.ui.ModeSelectionScreen
import com.example.ui.theme.PhotoStandTheme

class MainActivity : ComponentActivity() {
    private var sharedImageUriState = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingShareIntent(intent)

        setContent {
            PhotoStandTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhotoStandApp(
                        initialSharedUri = sharedImageUriState.value,
                        onClearSharedUri = { sharedImageUriState.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        if (Intent.ACTION_SEND == intent.action && intent.type?.startsWith("image/") == true) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } ?: intent.clipData?.getItemAt(0)?.uri

            if (uri != null) {
                sharedImageUriState.value = uri
            }
        }
    }
}

@Composable
fun PhotoStandApp(
    initialSharedUri: Uri?,
    onClearSharedUri: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pairingStore = remember { PairingStore(context) }
    var currentMode by remember { mutableStateOf(pairingStore.getAppMode()) }

    val displayViewModel: DisplayViewModel = viewModel()
    val controllerViewModel: ControllerViewModel = viewModel()

    // Request Runtime Permissions for BLE & Nearby WiFi Devices
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Restart discovery if permissions granted
        controllerViewModel.startDiscovery()
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        // Camera is used by the in-app QR scanner (also requested lazily inside the scanner).
        permissionsToRequest.add(android.Manifest.permission.CAMERA)
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    // Auto switch to controller when an external image is shared
    LaunchedEffect(initialSharedUri) {
        if (initialSharedUri != null) {
            currentMode = AppMode.CONTROLLER
            controllerViewModel.onSharedImageReceived(initialSharedUri)
            onClearSharedUri()
        }
    }

    when (currentMode) {
        AppMode.UNSELECTED -> {
            ModeSelectionScreen(
                onSelectMode = { mode ->
                    pairingStore.setAppMode(mode)
                    currentMode = mode
                }
            )
        }

        AppMode.DISPLAY -> {
            BackHandler {
                currentMode = AppMode.UNSELECTED
            }
            DisplayScreen(
                viewModel = displayViewModel,
                onSwitchMode = {
                    currentMode = AppMode.CONTROLLER
                    pairingStore.setAppMode(AppMode.CONTROLLER)
                }
            )
        }

        AppMode.CONTROLLER -> {
            BackHandler {
                currentMode = AppMode.UNSELECTED
            }
            ControllerScreen(
                viewModel = controllerViewModel,
                onSwitchMode = {
                    currentMode = AppMode.DISPLAY
                    pairingStore.setAppMode(AppMode.DISPLAY)
                }
            )
        }
    }
}

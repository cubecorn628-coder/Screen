// File: app/src/main/java/com/example/photostand/controller/ControllerScreen.kt
package com.example.photostand.controller

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.photostand.core.ConnectionType
import com.example.photostand.core.DisplayDevice
import com.example.photostand.core.TransferResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen(
    viewModel: ControllerViewModel,
    onSwitchMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var targetDisplayForPhoto by remember { mutableStateOf<DisplayDevice?>(null) }

    // Zero-permission Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && targetDisplayForPhoto != null) {
            viewModel.sendPhotoToDisplay(targetDisplayForPhoto!!, uri)
        }
    }

    // Show transfer snackbar or result
    LaunchedEffect(uiState.lastTransferResult) {
        val result = uiState.lastTransferResult
        if (result != null) {
            when (result) {
                is TransferResult.Success -> {
                    snackbarHostState.showSnackbar(result.message)
                }
                is TransferResult.Failure -> {
                    snackbarHostState.showSnackbar(result.errorMessage)
                }
            }
            viewModel.dismissTransferResult()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.controller_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (uiState.isScanning) stringResource(R.string.controller_discovering) else "${uiState.pairedDisplays.size} paired",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.startDiscovery() },
                        modifier = Modifier.testTag("refresh_discovery_button")
                    ) {
                        if (uiState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Scan"
                            )
                        }
                    }

                    IconButton(
                        onClick = onSwitchMode,
                        modifier = Modifier.testTag("controller_switch_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.switch_mode)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.setShowPairingDialog(true) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.controller_pair_button)) },
                modifier = Modifier.testTag("pair_display_fab")
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.pairedDisplays.isEmpty()) {
                EmptyDisplaysView(
                    onPairClick = { viewModel.setShowPairingDialog(true) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.controller_displays),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    items(uiState.pairedDisplays, key = { it.id }) { display ->
                        DisplayDeviceCard(
                            display = display,
                            onSendPhotoClick = {
                                targetDisplayForPhoto = display
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onRenameClick = { viewModel.setRenameDialogDevice(display) },
                            onForgetClick = { viewModel.removeDisplay(display.id) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }

            // Sending Overlay Banner
            AnimatedVisibility(
                visible = uiState.isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = uiState.sendingMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Pairing Modal Dialog
    if (uiState.showPairingDialog) {
        PairingModalDialog(
            discoveredDisplays = uiState.discoveredDisplays,
            onPairDiscovered = { viewModel.pairDiscoveredDisplay(it) },
            onPairToken = { viewModel.pairFromToken(it) },
            onPairManual = { ip, port, name -> viewModel.pairManualIp(ip, port, name) },
            onDismiss = { viewModel.setShowPairingDialog(false) }
        )
    }

    // Rename Display Dialog
    val renameTarget = uiState.renameDialogDevice
    if (renameTarget != null) {
        RenameDisplayDialog(
            currentName = renameTarget.name,
            onConfirm = { newName -> viewModel.renameDisplay(renameTarget.id, newName) },
            onDismiss = { viewModel.setRenameDialogDevice(null) }
        )
    }

    // Shared Image Picker Sheet (from external share intent)
    if (uiState.showPickerSheet && uiState.sharedImageUri != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setShowPickerSheet(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SharePickerContent(
                displays = uiState.pairedDisplays,
                onSelectDisplay = { display ->
                    viewModel.sendPhotoToDisplay(display, uiState.sharedImageUri!!)
                },
                onCancel = { viewModel.setShowPickerSheet(false) }
            )
        }
    }
}

// region Display Device Card
@Composable
private fun DisplayDeviceCard(
    display: DisplayDevice,
    onSendPhotoClick: () -> Unit,
    onRenameClick: () -> Unit,
    onForgetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("display_card_${display.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column {
                        Text(
                            text = display.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Status Pill
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (display.isOnline) Color(0xFF10B981) else Color(0xFF94A3B8))
                            )
                            Text(
                                text = if (display.isOnline) stringResource(R.string.controller_status_online) else stringResource(R.string.controller_status_offline),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (display.isOnline) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (display.ipAddress.isNotBlank()) {
                                Text(
                                    text = "•  ${display.ipAddress}:${display.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRenameClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Forget") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onForgetClick()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSendPhotoClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_photo_button_${display.id}")
            ) {
                Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.controller_send_button))
            }
        }
    }
}
// endregion

// region Empty State
@Composable
private fun EmptyDisplaysView(
    onPairClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Text(
            text = stringResource(R.string.controller_no_displays),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(R.string.controller_no_displays_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Button(
            onClick = onPairClick,
            modifier = Modifier.testTag("empty_pair_button")
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.controller_pair_button))
        }
    }
}
// endregion

// region Pairing Modal Dialog
@Composable
private fun PairingModalDialog(
    discoveredDisplays: List<DisplayDevice>,
    onPairDiscovered: (DisplayDevice) -> Unit,
    onPairToken: (String) -> Boolean,
    onPairManual: (ip: String, port: Int, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var tokenInput by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.pairing_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.pairing_discovered_tab)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.pairing_scan_tab)) }
                    )
                }

                if (selectedTab == 0) {
                    // Discovered Tab
                    if (discoveredDisplays.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Text(
                                text = "Searching local WiFi and BLE…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (disp in discoveredDisplays) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = disp.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${disp.ipAddress}:${disp.port}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { onPairDiscovered(disp) },
                                            modifier = Modifier.testTag("pair_discovered_${disp.id}")
                                        ) {
                                            Text("Pair")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Manual / Token Tab
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("Pairing Token JSON") },
                            placeholder = { Text("{\"id\":\"...\",\"ip\":\"...\"}") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pairing_token_input"),
                            maxLines = 3
                        )
                        Button(
                            onClick = {
                                if (onPairToken(tokenInput.trim())) {
                                    tokenInput = ""
                                }
                            },
                            enabled = tokenInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.pairing_connect))
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Or enter direct IP:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("Display IP Address") },
                            placeholder = { Text("192.168.1.100") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = manualName,
                            onValueChange = { manualName = it },
                            label = { Text("Display Label (Optional)") },
                            placeholder = { Text("Living Room Frame") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedButton(
                            onClick = {
                                onPairManual(manualIp.trim(), 9876, manualName.trim())
                                manualIp = ""
                                manualName = ""
                            },
                            enabled = manualIp.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add via IP")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
// endregion

// region Rename Dialog
@Composable
private fun RenameDisplayDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nameInput by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.controller_rename_dialog)) },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(nameInput.trim()) },
                enabled = nameInput.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
// endregion

// region Share Picker Content
@Composable
private fun SharePickerContent(
    displays: List<DisplayDevice>,
    onSelectDisplay: (DisplayDevice) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Select Target Display",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(displays) { display ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectDisplay(display) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(display.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (display.isOnline) "Online" else "Offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (display.isOnline) Color(0xFF10B981) else Color.Gray
                            )
                        }
                        Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cancel))
        }
    }
}
// endregion

package com.vortex.emulator.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.hilt.navigation.compose.hiltViewModel
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.vortex.emulator.emulation.AudioLatency
import com.vortex.emulator.emulation.EmulationEngine
import com.vortex.emulator.emulation.VortexNative
import com.vortex.emulator.core.ControllerLayout
import com.vortex.emulator.core.FaceButtonStyle
import com.vortex.emulator.core.defaultControllerLayout
import com.vortex.emulator.ui.theme.*
import com.vortex.emulator.ui.viewmodel.EmulationState
import com.vortex.emulator.ui.viewmodel.EmulationViewModel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulationScreen(
    gameId: Long,
    onBack: () -> Unit,
    onNavigateToMultiplayer: (() -> Unit)? = null,
    viewModel: EmulationViewModel = hiltViewModel()
) {
    val game by viewModel.game.collectAsState()
    val emulationState by viewModel.emulationState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val error by viewModel.error.collectAsState()
    val frameBitmap by viewModel.frameBitmap.collectAsState()
    val currentFps by viewModel.currentFps.collectAsState()
    val fastForwardEnabled by viewModel.fastForwardEnabled.collectAsState()
    val rewindEnabled by viewModel.rewindEnabled.collectAsState()
    val audioEnabled by viewModel.audioEnabled.collectAsState()
    val audioVolume by viewModel.audioVolume.collectAsState()
    val audioLatency by viewModel.audioLatency.collectAsState()
    val quickSaveSlot by viewModel.quickSaveSlot.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    var showCoreSelector by remember { mutableStateOf(false) }
    var showFps by remember { mutableStateOf(true) }
    var showSaveSlotPicker by remember { mutableStateOf(false) }
    var showLocalMultiplayer by remember { mutableStateOf(false) }
    val multiplayerLocalIndex by viewModel.localPlayerIndex.collectAsState()
    var activePlayer by remember { mutableIntStateOf(0) } // 0 = P1, 1 = P2

    // In multiplayer, default the active player to the local player index
    LaunchedEffect(multiplayerLocalIndex) {
        if (viewModel.isMultiplayerActive) {
            activePlayer = multiplayerLocalIndex
        }
    }

    // Gamepad detection — hide on-screen controls when physical controller is connected
    val gamepadConnected = viewModel.isGamepadConnected
    val effectiveShowControls = showControls && !gamepadConnected

    // SAF launchers for save state import/export
    val exportStateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) viewModel.exportSaveState(uri)
    }
    val importStateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importSaveState(uri)
    }
    val saveDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setCustomSaveDirectory(uri)
    }

    // ── Immersive mode: hide status bar + navigation bar ──
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val decorView = window?.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window?.insetsController
            controller?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            decorView?.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
        onDispose {
            // Restore system bars when leaving emulation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.insetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    val gameTitle = game?.title ?: "Loading…"
    val selectedCore by viewModel.selectedCore.collectAsState()
    val coreName = selectedCore?.displayName ?: "No core"
    val isPaused = emulationState == EmulationState.PAUSED

    // Derive controller layout from the game's platform
    val controllerLayout = remember(game?.platform) {
        game?.platform?.defaultControllerLayout() ?: com.vortex.emulator.core.Platform.NES.defaultControllerLayout()
    }

    // Loading state
    if (emulationState == EmulationState.LOADING) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = VortexCyan)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexOnSurfaceVariant
                )
            }
        }
        return@EmulationScreen
    }

    // Error state
    if (emulationState == EmulationState.ERROR) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = VortexRed,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexOnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = onBack) {
                    Text("Go Back", color = VortexCyan)
                }
            }
        }
        return@EmulationScreen
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val portraitLayout = maxHeight > maxWidth
        val screenW = maxWidth
        val screenH = maxHeight

        if (portraitLayout) {
            // ── Portrait: game top ~50%, controls bottom ~50% ──────
            Column(modifier = Modifier.fillMaxSize()) {
                // Game surface area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.50f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    // Render emulation frame
                    val bmp = frameBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Game",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.Low
                        )
                    }
                    // Touch overlay for NDS/3DS
                    if (controllerLayout.showTouchOverlay) {
                        TouchScreenOverlay(
                            engine = viewModel.engine,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Pause overlay
                    if (isPaused) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Pause, "Paused", tint = VortexCyan, modifier = Modifier.size(64.dp))
                        }
                    }

                    // FPS counter
                    if (showFps) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 44.dp, end = 12.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = "${currentFps.toInt()} FPS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    currentFps >= 55 -> VortexGreen
                                    currentFps >= 30 -> VortexOrange
                                    else -> VortexRed
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Top action bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart)
                            .padding(top = 36.dp, start = 4.dp, end = 4.dp)
                            .alpha(if (showControls) 0.9f else 0f),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Row {
                            IconButton(onClick = { viewModel.togglePause() }) {
                                Icon(
                                    if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = if (isPaused) "Resume" else "Pause",
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, "Menu", tint = Color.White)
                            }
                        }
                    }
                }

                // Controls area (hidden when physical gamepad is connected)
                if (effectiveShowControls) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.50f)
                            .background(Color.Black)
                    ) {
                        LayoutAwareControls(
                            layout = controllerLayout,
                            engine = viewModel.engine,
                            activePlayer = activePlayer,
                            modifier = Modifier.fillMaxSize(),
                            isPortrait = true
                        )
                    }
                } else if (gamepadConnected) {
                    // When gamepad is connected, give game surface more room
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.50f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.7f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.SportsEsports,
                                    contentDescription = null,
                                    tint = VortexGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = viewModel.gamepadName ?: "Controller",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VortexGreen
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ── Landscape: full-screen overlay (original) ─────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val bmp = frameBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Game",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.Low
                    )
                }
                // Touch overlay for NDS/3DS
                if (controllerLayout.showTouchOverlay) {
                    TouchScreenOverlay(
                        engine = viewModel.engine,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (isPaused) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Pause, "Paused", tint = VortexCyan, modifier = Modifier.size(80.dp))
                    }
                }
            }

            // FPS Counter
            if (showFps && !isPaused) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 48.dp, end = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = "${currentFps.toInt()} FPS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            currentFps >= 55 -> VortexGreen
                            currentFps >= 30 -> VortexOrange
                            else -> VortexRed
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Top action bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = 40.dp, start = 8.dp, end = 8.dp)
                    .alpha(if (showControls) 0.9f else 0f),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Row {
                    IconButton(onClick = { viewModel.togglePause() }) {
                        Icon(
                            if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, "Menu", tint = Color.White)
                    }
                }
            }

            // On-screen controls (landscape — hidden when physical gamepad connected)
            if (effectiveShowControls) {
                LayoutAwareControls(
                    layout = controllerLayout,
                    engine = viewModel.engine,
                    activePlayer = activePlayer,
                    modifier = Modifier.fillMaxSize(),
                    isPortrait = false
                )
            }

            // Gamepad connected indicator (landscape)
            if (gamepadConnected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.SportsEsports,
                            contentDescription = null,
                            tint = VortexGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = viewModel.gamepadName ?: "Controller",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexGreen
                        )
                    }
                }
            }
        }

        // Status message overlay
        val msg = statusMessage
        if (msg.isNotBlank() && emulationState != EmulationState.LOADING) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.8f)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = VortexCyan,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Fast Forward indicator
        if (fastForwardEnabled && emulationState == EmulationState.RUNNING) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 44.dp),
                shape = RoundedCornerShape(8.dp),
                color = VortexOrange.copy(alpha = 0.85f)
            ) {
                Text(
                    text = ">> FAST FORWARD",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        // Local multiplayer indicator
        if (showLocalMultiplayer) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (fastForwardEnabled) 70.dp else 44.dp),
                shape = RoundedCornerShape(8.dp),
                color = VortexMagenta.copy(alpha = 0.85f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "2P MODE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Surface(
                        onClick = { activePlayer = if (activePlayer == 0) 1 else 0 },
                        shape = RoundedCornerShape(4.dp),
                        color = if (activePlayer == 0) VortexCyan else VortexOrange
                    ) {
                        Text(
                            text = "P${activePlayer + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Quick menu bottom sheet
        if (showMenu) {
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = VortexSurfaceContainer,
                contentColor = VortexOnSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Quick Menu",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = VortexCyan
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Save States ──
                    Text(
                        text = "SAVE STATES",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexCyan.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                    QuickMenuItem(Icons.Filled.Save, "Save (Slot $quickSaveSlot)") {
                        viewModel.quickSave(); showMenu = false
                    }
                    QuickMenuItem(Icons.Filled.FileOpen, "Load (Slot $quickSaveSlot)") {
                        viewModel.quickLoad(); showMenu = false
                    }
                    QuickMenuItem(Icons.Filled.SwapHoriz, "Change Slot ($quickSaveSlot)") {
                        showSaveSlotPicker = true
                    }
                    QuickMenuItem(Icons.Filled.Upload, "Export Save") {
                        showMenu = false
                        val gameName = game?.title?.replace(Regex("[^a-zA-Z0-9_-]"), "_") ?: "save"
                        exportStateLauncher.launch("${gameName}.state")
                    }
                    QuickMenuItem(Icons.Filled.Download, "Import Save") {
                        showMenu = false
                        importStateLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Emulation Controls ──
                    Text(
                        text = "EMULATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexCyan.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                    QuickMenuToggleItem(
                        icon = Icons.Filled.FastRewind,
                        title = "Rewind",
                        enabled = rewindEnabled
                    ) {
                        viewModel.toggleRewind()
                    }
                    QuickMenuToggleItem(
                        icon = Icons.Filled.FastForward,
                        title = "Fast Forward",
                        enabled = fastForwardEnabled
                    ) {
                        viewModel.toggleFastForward()
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Audio Settings ──
                    Text(
                        text = "AUDIO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexCyan.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                    QuickMenuToggleItem(
                        icon = if (audioEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        title = "Audio",
                        enabled = audioEnabled
                    ) {
                        viewModel.toggleAudio()
                    }
                    // Volume slider
                    QuickMenuSliderItem(
                        icon = Icons.Filled.VolumeDown,
                        title = "Volume",
                        value = audioVolume,
                        onValueChange = { viewModel.setAudioVolume(it) },
                        valueLabel = "${(audioVolume * 100).roundToInt()}%",
                        enabled = audioEnabled
                    )
                    // Latency selector
                    QuickMenuSelectorItem(
                        icon = Icons.Filled.Speed,
                        title = "Latency",
                        options = AudioLatency.entries.map { it.label },
                        selectedIndex = AudioLatency.entries.indexOf(audioLatency),
                        onSelect = { viewModel.setAudioLatency(AudioLatency.entries[it]) },
                        enabled = audioEnabled
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Multiplayer ──
                    Text(
                        text = "MULTIPLAYER",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexCyan.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                    QuickMenuToggleItem(
                        icon = Icons.Filled.People,
                        title = "Local 2-Player",
                        enabled = showLocalMultiplayer
                    ) {
                        showLocalMultiplayer = !showLocalMultiplayer
                        if (!showLocalMultiplayer) activePlayer = 0
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── System ──
                    Text(
                        text = "SYSTEM",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexCyan.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                    QuickMenuItem(Icons.Filled.Refresh, "Reset Game") {
                        viewModel.resetGame(); showMenu = false
                    }
                    QuickMenuItem(Icons.Filled.Screenshot, "Screenshot") {
                        viewModel.takeScreenshot(); showMenu = false
                    }
                    QuickMenuItem(Icons.Filled.Memory, "Switch Core") {
                        showMenu = false
                        showCoreSelector = true
                    }
                    QuickMenuItem(Icons.Filled.Gamepad, "Toggle Controls") {
                        showControls = !showControls
                        showMenu = false
                    }
                    QuickMenuItem(Icons.AutoMirrored.Filled.ExitToApp, "Quit Game") {
                        showMenu = false
                        onBack()
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        // Save slot picker dialog
        if (showSaveSlotPicker) {
            AlertDialog(
                onDismissRequest = { showSaveSlotPicker = false },
                title = { Text("Select Save Slot", color = VortexCyan) },
                text = {
                    Column {
                        (0..9).forEach { slot ->
                            Surface(
                                onClick = {
                                    viewModel.setQuickSaveSlot(slot)
                                    showSaveSlotPicker = false
                                },
                                color = if (slot == quickSaveSlot) VortexCyan.copy(alpha = 0.15f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (slot == quickSaveSlot) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (slot == quickSaveSlot) VortexCyan else Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Slot $slot", color = Color.White)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSaveSlotPicker = false }) {
                        Text("Close", color = VortexCyan)
                    }
                },
                containerColor = VortexSurfaceContainer
            )
        }

        // Core selector bottom sheet
        if (showCoreSelector) {
            val availableCores = remember { viewModel.getAvailableCores() }
            ModalBottomSheet(
                onDismissRequest = { showCoreSelector = false },
                containerColor = VortexSurfaceContainer,
                contentColor = VortexOnSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Select Core",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = VortexCyan
                    )
                    Text(
                        text = game?.platform?.displayName ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    availableCores.forEach { core ->
                        val isSelected = core.id == selectedCore?.id
                        Surface(
                            onClick = {
                                viewModel.switchCore(core)
                                showCoreSelector = false
                            },
                            color = if (isSelected) VortexCyan.copy(alpha = 0.12f) else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSelected) VortexCyan else Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = core.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) VortexCyan else Color.White
                                    )
                                    Text(
                                        text = core.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.5f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun DPad(
    onButtonPress: (Int) -> Unit = {},
    onButtonRelease: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 148.dp
) {
    var pressedDirs by remember { mutableStateOf(emptySet<Int>()) }
    val sizePx = with(LocalDensity.current) { size.toPx() }
    val halfPx = sizePx / 2f
    val deadZone = sizePx * 0.08f

    fun directions(x: Float, y: Float): Set<Int> {
        val dx = x - halfPx
        val dy = y - halfPx
        if (sqrt(dx * dx + dy * dy) < deadZone) return emptySet()
        val a = atan2(dy.toDouble(), dx.toDouble())
        val dirs = mutableSetOf<Int>()
        // 8-direction zones: each cardinal spans 135°, diagonals are 45° overlaps
        if (a > -PI * 3 / 8 && a < PI * 3 / 8) dirs += VortexNative.RETRO_DEVICE_ID_JOYPAD_RIGHT
        if (a > PI / 8 && a < PI * 7 / 8) dirs += VortexNative.RETRO_DEVICE_ID_JOYPAD_DOWN
        if (a > PI * 5 / 8 || a < -PI * 5 / 8) dirs += VortexNative.RETRO_DEVICE_ID_JOYPAD_LEFT
        if (a > -PI * 7 / 8 && a < -PI / 8) dirs += VortexNative.RETRO_DEVICE_ID_JOYPAD_UP
        return dirs
    }

    val isUp = VortexNative.RETRO_DEVICE_ID_JOYPAD_UP in pressedDirs
    val isDown = VortexNative.RETRO_DEVICE_ID_JOYPAD_DOWN in pressedDirs
    val isLeft = VortexNative.RETRO_DEVICE_ID_JOYPAD_LEFT in pressedDirs
    val isRight = VortexNative.RETRO_DEVICE_ID_JOYPAD_RIGHT in pressedDirs

    val btnSize = size * 0.34f
    val cornerR = size * 0.086f
    val activeColor = VortexCyan.copy(alpha = 0.35f)
    val idleColor = Color.White.copy(alpha = 0.12f)

    Box(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val init = directions(down.position.x, down.position.y)
                    pressedDirs = init
                    init.forEach { onButtonPress(it) }
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        c.consume()
                        val upd = directions(c.position.x, c.position.y)
                        (pressedDirs - upd).forEach { onButtonRelease(it) }
                        (upd - pressedDirs).forEach { onButtonPress(it) }
                        pressedDirs = upd
                    }
                    pressedDirs.forEach { onButtonRelease(it) }
                    pressedDirs = emptySet()
                }
            }
    ) {
        // Up
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(btnSize)
                .clip(RoundedCornerShape(topStart = cornerR, topEnd = cornerR))
                .background(if (isUp) activeColor else idleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, "Up",
                tint = if (isUp) VortexCyan else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(btnSize * 0.6f))
        }
        // Down
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(btnSize)
                .clip(RoundedCornerShape(bottomStart = cornerR, bottomEnd = cornerR))
                .background(if (isDown) activeColor else idleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "Down",
                tint = if (isDown) VortexCyan else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(btnSize * 0.6f))
        }
        // Left
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(btnSize)
                .clip(RoundedCornerShape(topStart = cornerR, bottomStart = cornerR))
                .background(if (isLeft) activeColor else idleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left",
                tint = if (isLeft) VortexCyan else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(btnSize * 0.6f))
        }
        // Right
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(btnSize)
                .clip(RoundedCornerShape(topEnd = cornerR, bottomEnd = cornerR))
                .background(if (isRight) activeColor else idleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right",
                tint = if (isRight) VortexCyan else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(btnSize * 0.6f))
        }
        // Center
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(btnSize)
                .background(idleColor)
        )
        // Diagonal indicators
        val diagSize = size * 0.11f
        val diagPad = btnSize * 0.15f
        val diagActive = VortexCyan.copy(alpha = 0.22f)
        val diagIdle = Color.White.copy(alpha = 0.03f)
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = diagPad, top = diagPad)
            .size(diagSize).clip(CircleShape).background(if (isUp && isLeft) diagActive else diagIdle))
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = diagPad, top = diagPad)
            .size(diagSize).clip(CircleShape).background(if (isUp && isRight) diagActive else diagIdle))
        Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = diagPad, bottom = diagPad)
            .size(diagSize).clip(CircleShape).background(if (isDown && isLeft) diagActive else diagIdle))
        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = diagPad, bottom = diagPad)
            .size(diagSize).clip(CircleShape).background(if (isDown && isRight) diagActive else diagIdle))
    }
}

@Composable
fun ActionButtons(
    onButtonPress: (Int) -> Unit = {},
    onButtonRelease: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 140.dp
) {
    val labels = listOf("A", "B", "X", "Y")
    val textColors = listOf(VortexCyan, VortexMagenta, VortexGreen, VortexOrange)
    val buttonIds = listOf(
        VortexNative.RETRO_DEVICE_ID_JOYPAD_A,
        VortexNative.RETRO_DEVICE_ID_JOYPAD_B,
        VortexNative.RETRO_DEVICE_ID_JOYPAD_X,
        VortexNative.RETRO_DEVICE_ID_JOYPAD_Y
    )
    val btnDiameter = size * 0.36f

    Box(modifier = modifier.size(size)) {
        // A (right)
        ActionButton(
            label = labels[0], color = Color.Transparent, textColor = textColors[0],
            modifier = Modifier.align(Alignment.CenterEnd), diameter = btnDiameter,
            onPress = { onButtonPress(buttonIds[0]) }, onRelease = { onButtonRelease(buttonIds[0]) }
        )
        // B (bottom)
        ActionButton(
            label = labels[1], color = Color.Transparent, textColor = textColors[1],
            modifier = Modifier.align(Alignment.BottomCenter), diameter = btnDiameter,
            onPress = { onButtonPress(buttonIds[1]) }, onRelease = { onButtonRelease(buttonIds[1]) }
        )
        // X (top)
        ActionButton(
            label = labels[2], color = Color.Transparent, textColor = textColors[2],
            modifier = Modifier.align(Alignment.TopCenter), diameter = btnDiameter,
            onPress = { onButtonPress(buttonIds[2]) }, onRelease = { onButtonRelease(buttonIds[2]) }
        )
        // Y (left)
        ActionButton(
            label = labels[3], color = Color.Transparent, textColor = textColors[3],
            modifier = Modifier.align(Alignment.CenterStart), diameter = btnDiameter,
            onPress = { onButtonPress(buttonIds[3]) }, onRelease = { onButtonRelease(buttonIds[3]) }
        )
    }
}

@Composable
fun ActionButton(
    label: String,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = if (isPressed) listOf(
                        textColor.copy(alpha = 0.50f),
                        textColor.copy(alpha = 0.18f)
                    ) else listOf(
                        textColor.copy(alpha = 0.22f),
                        textColor.copy(alpha = 0.06f)
                    )
                )
            )
            .border(
                width = if (isPressed) 2.dp else 1.dp,
                color = if (isPressed) textColor.copy(alpha = 0.8f) else textColor.copy(alpha = 0.3f),
                shape = CircleShape
            )
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    onPress()
                    tryAwaitRelease()
                    isPressed = false
                    onRelease()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = if (isPressed) textColor else textColor.copy(alpha = 0.85f)
        )
    }
}

@Composable
fun SmallControlButton(
    label: String,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (isPressed) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (isPressed) VortexCyan.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(13.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    onPress()
                    tryAwaitRelease()
                    isPressed = false
                    onRelease()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) VortexCyan else Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun ShoulderButton(
    label: String,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(80.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPressed) VortexCyan.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (isPressed) VortexCyan.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    onPress()
                    tryAwaitRelease()
                    isPressed = false
                    onRelease()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) VortexCyan else Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun AnalogStick(
    onAxisChanged: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    size: Dp = 90.dp
) {
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = size.value * 0.39f
    val stickDiameter = size * 0.49f
    val thumbDiameter = size * 0.18f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Base
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
        )

        // Stick
        Box(
            modifier = Modifier
                .size(stickDiameter)
                .offset {
                    IntOffset(stickOffset.x.roundToInt(), stickOffset.y.roundToInt())
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            VortexCyan.copy(alpha = 0.3f),
                            VortexCyan.copy(alpha = 0.1f)
                        )
                    )
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            stickOffset = Offset.Zero
                            onAxisChanged(0, 0)
                        },
                        onDragCancel = {
                            stickOffset = Offset.Zero
                            onAxisChanged(0, 0)
                        }
                    ) { _, dragAmount ->
                        val newOffset = stickOffset + dragAmount
                        val distance = sqrt(newOffset.x * newOffset.x + newOffset.y * newOffset.y)
                        stickOffset = if (distance <= maxRadius) {
                            newOffset
                        } else {
                            newOffset * (maxRadius / distance)
                        }
                        val axisX = (stickOffset.x / maxRadius * 32767).toInt().coerceIn(-32767, 32767)
                        val axisY = (stickOffset.y / maxRadius * 32767).toInt().coerceIn(-32767, 32767)
                        onAxisChanged(axisX, axisY)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(thumbDiameter)
                    .clip(CircleShape)
                    .background(VortexCyan.copy(alpha = 0.4f))
            )
        }
    }
}

@Composable
fun QuickMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = VortexCyan,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun QuickMenuToggleItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        color = if (enabled) VortexCyan.copy(alpha = 0.08f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (enabled) VortexCyan else VortexCyan.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VortexCyan,
                    checkedTrackColor = VortexCyan.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@Composable
fun QuickMenuSliderItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(alpha)
                .padding(vertical = 8.dp, horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = VortexCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexCyan
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = VortexCyan,
                    activeTrackColor = VortexCyan,
                    inactiveTrackColor = VortexCyan.copy(alpha = 0.2f)
                )
            )
        }
    }
}

@Composable
fun QuickMenuSelectorItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(alpha)
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = VortexCyan,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEachIndexed { index, label ->
                    val isSelected = index == selectedIndex
                    Surface(
                        onClick = { if (enabled) onSelect(index) },
                        color = if (isSelected) VortexCyan else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = if (isSelected) VortexCyan else VortexCyan.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) Color.Black else VortexCyan.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// Touch Screen Overlay — captures touch events for NDS/3DS touch screens
// ════════════════════════════════════════════════════════════════════════
//
// NDS (melonDS) and 3DS (Citra) render both screens stacked vertically.
// The touch screen is the BOTTOM half of the frame buffer.
// Touch coordinates are mapped from screen-space to libretro pointer range:
//   X: [-0x7FFF .. 0x7FFF]  (left to right)
//   Y: [-0x7FFF .. 0x7FFF]  (top to bottom, but only over the bottom screen)
// Touches on the top screen half are ignored.

@Composable
private fun TouchScreenOverlay(
    engine: EmulationEngine,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val w = size.width.toFloat()
                    val h = size.height.toFloat()

                    fun mapToPointer(x: Float, y: Float) {
                        // Normalise to 0..1 over the full display area
                        val nx = (x / w).coerceIn(0f, 1f)
                        val ny = (y / h).coerceIn(0f, 1f)

                        // Only respond to touches on the bottom half (touch screen)
                        if (ny < 0.5f) {
                            engine.setPointer(0, 0, false)
                            return
                        }

                        // Map bottom-half Y (0.5..1.0) to full libretro range (-0x7FFF..0x7FFF)
                        val touchY = ((ny - 0.5f) / 0.5f)
                        val px = ((nx * 2f - 1f) * 0x7FFF).toInt().coerceIn(-0x7FFF, 0x7FFF)
                        val py = ((touchY * 2f - 1f) * 0x7FFF).toInt().coerceIn(-0x7FFF, 0x7FFF)
                        engine.setPointer(px, py, true)
                    }

                    mapToPointer(down.position.x, down.position.y)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        mapToPointer(change.position.x, change.position.y)
                    }
                    // Release touch when finger lifts
                    engine.setPointer(0, 0, false)
                }
            }
    )
}

// ════════════════════════════════════════════════════════════════════════
// Layout-Aware Controls — adapts on-screen buttons to the platform
// ════════════════════════════════════════════════════════════════════════

@Composable
fun LayoutAwareControls(
    layout: ControllerLayout,
    engine: EmulationEngine,
    activePlayer: Int,
    modifier: Modifier = Modifier,
    isPortrait: Boolean
) {
    val topPad = if (isPortrait) 4.dp else 84.dp
    val botPad = if (isPortrait) 56.dp else 50.dp
    val sidePad = if (isPortrait) 14.dp else 24.dp
    val midBotPad = if (isPortrait) 16.dp else 14.dp
    val dpadSize = if (isPortrait) 130.dp else 148.dp
    val actionSize = if (isPortrait) 120.dp else 140.dp
    val analogSize = if (isPortrait) 70.dp else 80.dp

    Box(modifier = modifier) {
        // ── Shoulder buttons row ────────────────────────────────
        if (layout.showShoulderL || layout.showShoulderR ||
            layout.showShoulderL2 || layout.showShoulderR2 || layout.showZTrigger
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = topPad, start = sidePad + 2.dp, end = sidePad + 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left shoulder cluster
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (layout.showShoulderL2) {
                        ShoulderButton(
                            label = "L2",
                            onPress = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_L2, true) },
                            onRelease = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_L2, false) }
                        )
                    }
                    if (layout.showShoulderL) {
                        ShoulderButton(
                            label = "L",
                            onPress = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_L, true) },
                            onRelease = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_L, false) }
                        )
                    }
                    if (layout.showZTrigger) {
                        ShoulderButton(
                            label = "Z",
                            onPress = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_L2, true) },
                            onRelease = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_L2, false) }
                        )
                    }
                }
                // Right shoulder cluster
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (layout.showShoulderR) {
                        ShoulderButton(
                            label = "R",
                            onPress = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_R, true) },
                            onRelease = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_R, false) }
                        )
                    }
                    if (layout.showShoulderR2) {
                        ShoulderButton(
                            label = "R2",
                            onPress = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_R2, true) },
                            onRelease = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_R2, false) }
                        )
                    }
                }
            }
        }

        // ── D-Pad — bottom-left ─────────────────────────────────
        if (layout.showDpad) {
            DPad(
                onButtonPress = { id -> engine.setButton(activePlayer, id, true) },
                onButtonRelease = { id -> engine.setButton(activePlayer, id, false) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = sidePad, bottom = botPad),
                size = dpadSize
            )
        }

        // ── Left analog stick (below/beside D-Pad) ─────────────
        if (layout.showAnalogLeft) {
            AnalogStick(
                onAxisChanged = { x, y ->
                    engine.setAnalog(activePlayer, 0, 0, x)
                    engine.setAnalog(activePlayer, 0, 1, y)
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = if (isPortrait) 140.dp else 175.dp,
                        bottom = if (isPortrait) 70.dp else 20.dp
                    ),
                size = analogSize
            )
        }

        // ── Right analog stick (below/beside action buttons) ────
        if (layout.showAnalogRight) {
            AnalogStick(
                onAxisChanged = { x, y ->
                    engine.setAnalog(activePlayer, 1, 0, x)
                    engine.setAnalog(activePlayer, 1, 1, y)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = if (isPortrait) 130.dp else 175.dp,
                        bottom = if (isPortrait) 70.dp else 20.dp
                    ),
                size = analogSize
            )
        }

        // ── Face buttons — changes per platform ─────────────────
        when (layout.faceButtons) {
            FaceButtonStyle.AB_ONLY -> {
                ABButtons(
                    onButtonPress = { id -> engine.setButton(activePlayer, id, true) },
                    onButtonRelease = { id -> engine.setButton(activePlayer, id, false) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = sidePad, bottom = botPad),
                    size = actionSize
                )
            }
            FaceButtonStyle.PSX_SYMBOLS -> {
                PSXButtons(
                    onButtonPress = { id -> engine.setButton(activePlayer, id, true) },
                    onButtonRelease = { id -> engine.setButton(activePlayer, id, false) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = sidePad, bottom = botPad),
                    size = actionSize
                )
            }
            FaceButtonStyle.SIX_BUTTON -> {
                SixButtonCluster(
                    onButtonPress = { id -> engine.setButton(activePlayer, id, true) },
                    onButtonRelease = { id -> engine.setButton(activePlayer, id, false) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = sidePad, bottom = botPad),
                    size = actionSize
                )
            }
            FaceButtonStyle.GAMECUBE -> {
                GameCubeButtons(
                    onButtonPress = { id -> engine.setButton(activePlayer, id, true) },
                    onButtonRelease = { id -> engine.setButton(activePlayer, id, false) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = sidePad, bottom = botPad),
                    size = actionSize
                )
            }
            FaceButtonStyle.ABXY -> {
                ActionButtons(
                    onButtonPress = { id -> engine.setButton(activePlayer, id, true) },
                    onButtonRelease = { id -> engine.setButton(activePlayer, id, false) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = sidePad, bottom = botPad),
                    size = actionSize
                )
            }
        }

        // ── Start / Select — bottom center ──────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = midBotPad),
            horizontalArrangement = Arrangement.spacedBy(if (isPortrait) 16.dp else 20.dp)
        ) {
            if (layout.showSelect) {
                SmallControlButton(
                    label = "SELECT",
                    onPress = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_SELECT, true) },
                    onRelease = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_SELECT, false) }
                )
            }
            if (layout.showStart) {
                SmallControlButton(
                    label = "START",
                    onPress = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_START, true) },
                    onRelease = { engine.setButton(activePlayer, VortexNative.RETRO_DEVICE_ID_JOYPAD_START, false) }
                )
            }
        }
    }
}

// ── AB-Only Buttons (NES, GBA, GBC) ────────────────────────────────

@Composable
fun ABButtons(
    onButtonPress: (Int) -> Unit = {},
    onButtonRelease: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 120.dp
) {
    val btnDiam = size * 0.42f
    Box(modifier = modifier.size(size)) {
        ActionButton(
            label = "A", color = Color.Transparent, textColor = VortexCyan,
            modifier = Modifier.align(Alignment.CenterEnd), diameter = btnDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_A) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_A) }
        )
        ActionButton(
            label = "B", color = Color.Transparent, textColor = VortexMagenta,
            modifier = Modifier.align(Alignment.CenterStart), diameter = btnDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_B) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_B) }
        )
    }
}

// ── PSX Symbol Buttons (Cross/Circle/Square/Triangle) ──────────────

@Composable
fun PSXButtons(
    onButtonPress: (Int) -> Unit = {},
    onButtonRelease: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 140.dp
) {
    val btnDiam = size * 0.36f
    Box(modifier = modifier.size(size)) {
        // Circle (right) → maps to A
        ActionButton(
            label = "○", color = Color.Transparent, textColor = VortexRed,
            modifier = Modifier.align(Alignment.CenterEnd), diameter = btnDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_A) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_A) }
        )
        // Cross (bottom) → maps to B
        ActionButton(
            label = "✕", color = Color.Transparent, textColor = VortexCyan,
            modifier = Modifier.align(Alignment.BottomCenter), diameter = btnDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_B) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_B) }
        )
        // Triangle (top) → maps to X
        ActionButton(
            label = "△", color = Color.Transparent, textColor = VortexGreen,
            modifier = Modifier.align(Alignment.TopCenter), diameter = btnDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_X) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_X) }
        )
        // Square (left) → maps to Y
        ActionButton(
            label = "□", color = Color.Transparent, textColor = VortexMagenta,
            modifier = Modifier.align(Alignment.CenterStart), diameter = btnDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_Y) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_Y) }
        )
    }
}

// ── 6-Button Cluster (Genesis, Saturn, Arcade) ─────────────────────

@Composable
fun SixButtonCluster(
    onButtonPress: (Int) -> Unit = {},
    onButtonRelease: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 160.dp
) {
    // Two rows of 3 buttons:
    // Top row:  X (L) · Y (M) · R (shoulder mapped to face)
    // Bottom:   A      · B     · C (X in libretro)
    val btnDiam = size * 0.28f
    val spacing = 4.dp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        // Top row: X, Y, L (mapped as extra face buttons)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            ActionButton(
                label = "X", color = Color.Transparent, textColor = VortexGreen,
                diameter = btnDiam,
                onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_X) },
                onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_X) }
            )
            ActionButton(
                label = "Y", color = Color.Transparent, textColor = VortexOrange,
                diameter = btnDiam,
                onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_Y) },
                onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_Y) }
            )
            ActionButton(
                label = "Z", color = Color.Transparent, textColor = Color.White.copy(alpha = 0.7f),
                diameter = btnDiam,
                onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_L) },
                onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_L) }
            )
        }
        // Bottom row: A, B, C
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            ActionButton(
                label = "A", color = Color.Transparent, textColor = VortexCyan,
                diameter = btnDiam,
                onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_A) },
                onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_A) }
            )
            ActionButton(
                label = "B", color = Color.Transparent, textColor = VortexMagenta,
                diameter = btnDiam,
                onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_B) },
                onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_B) }
            )
            ActionButton(
                label = "C", color = Color.Transparent, textColor = VortexRed,
                diameter = btnDiam,
                onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_R) },
                onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_R) }
            )
        }
    }
}

// ── GameCube Buttons (big A, small B, X, Y) ────────────────────────

@Composable
fun GameCubeButtons(
    onButtonPress: (Int) -> Unit = {},
    onButtonRelease: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 150.dp
) {
    val bigDiam = size * 0.42f
    val smallDiam = size * 0.26f

    Box(modifier = modifier.size(size)) {
        // A — big center button
        ActionButton(
            label = "A", color = Color.Transparent, textColor = VortexGreen,
            modifier = Modifier.align(Alignment.Center), diameter = bigDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_A) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_A) }
        )
        // B — small, bottom-left of A
        ActionButton(
            label = "B", color = Color.Transparent, textColor = VortexRed,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp), diameter = smallDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_B) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_B) }
        )
        // X — right of A
        ActionButton(
            label = "X", color = Color.Transparent, textColor = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.CenterEnd), diameter = smallDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_X) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_X) }
        )
        // Y — top-left of A
        ActionButton(
            label = "Y", color = Color.Transparent, textColor = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp), diameter = smallDiam,
            onPress = { onButtonPress(VortexNative.RETRO_DEVICE_ID_JOYPAD_Y) },
            onRelease = { onButtonRelease(VortexNative.RETRO_DEVICE_ID_JOYPAD_Y) }
        )
    }
}

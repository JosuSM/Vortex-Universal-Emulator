package com.vortex.emulator.ui.screens

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.ui.components.*
import com.vortex.emulator.ui.theme.*
import com.vortex.emulator.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onGameClick: (Long) -> Unit,
    onViewAllRecent: () -> Unit,
    onManageCoresClick: () -> Unit,
    onPerformanceClick: () -> Unit,
    onMultiplayerClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val recentGames by viewModel.recentGames.collectAsState()
    val favoriteGames by viewModel.favoriteGames.collectAsState()
    val gameCount by viewModel.gameCount.collectAsState()
    val chipsetTier by viewModel.chipsetTier.collectAsState()
    val coreCount by viewModel.coreCount.collectAsState()
    val installedCoreCount by viewModel.installedCoreCount.collectAsState()
    val multiplayerCoreCount by viewModel.multiplayerReadyCoreCount.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanFilesScanned by viewModel.scanFilesScanned.collectAsState()
    val isOffline = viewModel.isOffline()

    val snackbarHostState = remember { SnackbarHostState() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current

    // MANAGE_EXTERNAL_STORAGE permission state
    var hasFileAccess by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        )
    }
    var launchFolderPickerAfterPermission by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.scanDirectory(it) }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasFileAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        if (hasFileAccess && launchFolderPickerAfterPermission) {
            launchFolderPickerAfterPermission = false
            folderPickerLauncher.launch(null)
        }
    }

    // Helper to launch scan, requesting file access first if needed
    val launchScan: () -> Unit = {
        if (isScanning) { /* already scanning */ }
        else if (hasFileAccess) {
            folderPickerLauncher.launch(null)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchFolderPickerAfterPermission = true
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            manageStorageLauncher.launch(intent)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scanResult.collect { result ->
            val message = when {
                result.totalFound == 0 -> "No ROM files found in selected folder"
                result.newAdded == 0 -> "Found ${result.totalFound} ROMs (all already in library)"
                else -> "Found ${result.totalFound} ROMs, added ${result.newAdded} new games!"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scanError.collect { error ->
            snackbarHostState.showSnackbar("Scan error: $error")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { scaffoldPadding ->
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
    ) {
        val compactLayout = maxWidth < 420.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
        // Hero Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isLandscape) 100.dp else if (compactLayout) 160.dp else 200.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            VortexCyan.copy(alpha = 0.08f),
                            VortexPurple.copy(alpha = 0.06f),
                            VortexMagenta.copy(alpha = 0.04f),
                            MaterialTheme.colorScheme.surface
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(800f, 400f)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (compactLayout) 18.dp else 24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                if (isLandscape) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "VORTEX",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = VortexCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "EMULATOR",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Performance. Compatibility. Style.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                Text(
                    text = "VORTEX",
                    style = if (compactLayout) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = VortexCyan
                )
                Text(
                    text = "EMULATOR",
                    style = if (compactLayout) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Performance. Compatibility. Style.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Offline banner
        if (isOffline) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = VortexOrange.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Filled.WifiOff,
                        contentDescription = null,
                        tint = VortexOrange,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Offline Mode",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = VortexOrange
                        )
                        Text(
                            text = "$installedCoreCount of $coreCount cores ready • Games with downloaded cores are playable",
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexOrange.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (compactLayout) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        icon = Icons.Filled.SportsEsports,
                        value = "$gameCount",
                        label = "Games",
                        gradientColors = listOf(VortexCyan.copy(alpha = 0.15f), VortexCyan.copy(alpha = 0.05f)),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = if (installedCoreCount == coreCount) Icons.Filled.CloudDone else Icons.Filled.CloudDownload,
                        value = "$installedCoreCount/$coreCount",
                        label = "Offline",
                        gradientColors = listOf(VortexPurple.copy(alpha = 0.15f), VortexPurple.copy(alpha = 0.05f)),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        icon = Icons.Filled.Wifi,
                        value = "$multiplayerCoreCount",
                        label = "Netplay",
                        gradientColors = listOf(VortexMagenta.copy(alpha = 0.15f), VortexMagenta.copy(alpha = 0.05f)),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Filled.Speed,
                        value = chipsetTier,
                        label = "Tier",
                        gradientColors = listOf(VortexGreen.copy(alpha = 0.15f), VortexGreen.copy(alpha = 0.05f)),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    icon = Icons.Filled.SportsEsports,
                    value = "$gameCount",
                    label = "Games",
                    gradientColors = listOf(VortexCyan.copy(alpha = 0.15f), VortexCyan.copy(alpha = 0.05f)),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = if (installedCoreCount == coreCount) Icons.Filled.CloudDone else Icons.Filled.CloudDownload,
                    value = "$installedCoreCount/$coreCount",
                    label = "Offline",
                    gradientColors = listOf(VortexPurple.copy(alpha = 0.15f), VortexPurple.copy(alpha = 0.05f)),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Filled.Wifi,
                    value = "$multiplayerCoreCount",
                    label = "Netplay",
                    gradientColors = listOf(VortexMagenta.copy(alpha = 0.15f), VortexMagenta.copy(alpha = 0.05f)),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Filled.Speed,
                    value = chipsetTier,
                    label = "Tier",
                    gradientColors = listOf(VortexGreen.copy(alpha = 0.15f), VortexGreen.copy(alpha = 0.05f)),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Recent Games
        if (recentGames.isNotEmpty()) {
            SectionTitle(
                title = "Continue Playing",
                action = "View All",
                onAction = onViewAllRecent
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recentGames, key = { it.id }) { game ->
                    GameCard(
                        game = game,
                        onClick = { onGameClick(game.id) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Favorites
        if (favoriteGames.isNotEmpty()) {
            SectionTitle(title = "Favorites")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(favoriteGames, key = { it.id }) { game ->
                    GameCard(
                        game = game,
                        onClick = { onGameClick(game.id) },
                        onFavoriteToggle = { viewModel.toggleFavorite(game) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Quick Actions
        SectionTitle(title = "Quick Actions")
        if (isLandscape) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionCard(
                        icon = Icons.Filled.FolderOpen,
                        title = if (isScanning) "Scanning… $scanFilesScanned files" else "Scan for ROMs",
                        description = "Select a folder to find game files",
                        accentColor = VortexCyan,
                        onClick = { launchScan() },
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        icon = Icons.Filled.Download,
                        title = "Manage Cores",
                        description = "Download and update cores",
                        accentColor = VortexPurple,
                        onClick = onManageCoresClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionCard(
                        icon = Icons.Filled.Wifi,
                        title = "Multiplayer Hub",
                        description = "Host or join sessions",
                        accentColor = VortexMagenta,
                        onClick = onMultiplayerClick,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        icon = Icons.Filled.Tune,
                        title = "Device Tuning",
                        description = "Optimize for your device",
                        accentColor = VortexGreen,
                        onClick = onPerformanceClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionCard(
                icon = Icons.Filled.FolderOpen,
                title = if (isScanning) "Scanning… $scanFilesScanned files" else "Scan for ROMs",
                description = "Select a folder to find game files",
                accentColor = VortexCyan,
                onClick = { launchScan() }
            )
            QuickActionCard(
                icon = Icons.Filled.Download,
                title = "Manage Cores",
                description = "Download and update emulation cores",
                accentColor = VortexPurple,
                onClick = onManageCoresClick
            )
            QuickActionCard(
                icon = Icons.Filled.Wifi,
                title = "Multiplayer Hub",
                description = "Host or join netplay-ready sessions",
                accentColor = VortexMagenta,
                onClick = onMultiplayerClick
            )
            QuickActionCard(
                icon = Icons.Filled.Tune,
                title = "Device Tuning",
                description = "Review performance and optimize for your device",
                accentColor = VortexGreen,
                onClick = onPerformanceClick
            )
        }
        }

        Spacer(modifier = Modifier.height(32.dp))
        }
    }
    }
}

@Composable
fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    gradientColors: List<androidx.compose.ui.graphics.Color>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(gradientColors)
                )
                .padding(16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun QuickActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

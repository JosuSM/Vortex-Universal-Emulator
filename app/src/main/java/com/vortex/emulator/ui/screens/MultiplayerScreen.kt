package com.vortex.emulator.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalWifiStatusbar4Bar
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.core.CoreFeature
import com.vortex.emulator.game.Game
import com.vortex.emulator.netplay.NetplayState
import com.vortex.emulator.ui.components.VortexHeader
import com.vortex.emulator.ui.theme.VortexCyan
import com.vortex.emulator.ui.theme.VortexGreen
import com.vortex.emulator.ui.theme.VortexMagenta
import com.vortex.emulator.ui.theme.VortexOrange
import com.vortex.emulator.ui.theme.VortexRed
import com.vortex.emulator.ui.viewmodel.MultiplayerMode
import com.vortex.emulator.ui.viewmodel.MultiplayerViewModel

@Composable
fun MultiplayerScreen(
    onGameClick: (Long) -> Unit = {},
    viewModel: MultiplayerViewModel = hiltViewModel()
) {
    val netplayCores by viewModel.netplayCores.collectAsState()
    val netplayGames by viewModel.netplayGames.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()
    val selectedCoreId by viewModel.selectedCoreId.collectAsState()
    val netplayPlatforms by viewModel.netplayPlatformList.collectAsState()
    val multiplayerMode by viewModel.multiplayerMode.collectAsState()
    val netplayState by viewModel.netplayState.collectAsState()
    val netplaySession by viewModel.netplaySession.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val joinIpAddress by viewModel.joinIpAddress.collectAsState()
    var roomCode by remember { mutableStateOf("") }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactLayout = maxWidth < 420.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header ──────────────────────────────────────────────
            item {
                VortexHeader(
                    title = "Multiplayer",
                    subtitle = "Netplay-ready cores and session presets"
                )
            }

            // ── Connection status banner ────────────────────────────
            item {
                ConnectionStatusBanner(modifier = Modifier.padding(horizontal = 20.dp))
            }

            // ── Mode cards ──────────────────────────────────────────
            item {
                if (compactLayout) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MultiplayerModeCard(
                            title = "Host Session",
                            description = "Create a room for local Wi-Fi or remote friends.",
                            accent = VortexCyan,
                            icon = Icons.Filled.Wifi,
                            selected = multiplayerMode == MultiplayerMode.HOST,
                            onClick = {
                                viewModel.setMultiplayerMode(if (multiplayerMode == MultiplayerMode.HOST) MultiplayerMode.NONE else MultiplayerMode.HOST)
                            }
                        )
                        MultiplayerModeCard(
                            title = "Join Session",
                            description = "Enter a room code or address to join a game.",
                            accent = VortexMagenta,
                            icon = Icons.Filled.Link,
                            selected = multiplayerMode == MultiplayerMode.JOIN,
                            onClick = {
                                viewModel.setMultiplayerMode(if (multiplayerMode == MultiplayerMode.JOIN) MultiplayerMode.NONE else MultiplayerMode.JOIN)
                            }
                        )
                        MultiplayerModeCard(
                            title = "Public Lobby",
                            description = "Browse open sessions from other players.",
                            accent = VortexGreen,
                            icon = Icons.Filled.Public,
                            selected = multiplayerMode == MultiplayerMode.LOBBY,
                            onClick = {
                                viewModel.setMultiplayerMode(if (multiplayerMode == MultiplayerMode.LOBBY) MultiplayerMode.NONE else MultiplayerMode.LOBBY)
                            }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MultiplayerModeCard(
                            title = "Host Session",
                            description = "Create a room for local Wi-Fi or remote friends.",
                            accent = VortexCyan,
                            icon = Icons.Filled.Wifi,
                            selected = multiplayerMode == MultiplayerMode.HOST,
                            onClick = {
                                viewModel.setMultiplayerMode(if (multiplayerMode == MultiplayerMode.HOST) MultiplayerMode.NONE else MultiplayerMode.HOST)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MultiplayerModeCard(
                            title = "Join Session",
                            description = "Enter a room code or address to join a game.",
                            accent = VortexMagenta,
                            icon = Icons.Filled.Link,
                            selected = multiplayerMode == MultiplayerMode.JOIN,
                            onClick = {
                                viewModel.setMultiplayerMode(if (multiplayerMode == MultiplayerMode.JOIN) MultiplayerMode.NONE else MultiplayerMode.JOIN)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MultiplayerModeCard(
                            title = "Public Lobby",
                            description = "Browse open sessions from other players.",
                            accent = VortexGreen,
                            icon = Icons.Filled.Public,
                            selected = multiplayerMode == MultiplayerMode.LOBBY,
                            onClick = {
                                viewModel.setMultiplayerMode(if (multiplayerMode == MultiplayerMode.LOBBY) MultiplayerMode.NONE else MultiplayerMode.LOBBY)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Expanded panel for selected mode ────────────────────
            if (multiplayerMode == MultiplayerMode.HOST) {
                item {
                    HostPanel(
                        viewModel = viewModel,
                        onSearchGame = { viewModel.setMultiplayerMode(MultiplayerMode.NONE) },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            if (multiplayerMode == MultiplayerMode.JOIN) {
                item {
                    JoinPanel(
                        viewModel = viewModel,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            if (multiplayerMode == MultiplayerMode.LOBBY) {
                item {
                    LobbyPanel(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }

            // ── Search Game section ────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Search Game",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = VortexMagenta.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "${netplayGames.size}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = VortexMagenta
                        )
                    }
                }
            }

            // ── Search by name ──────────────────────────────────────
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search by game name…") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VortexMagenta,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            }

            // ── Filter by platform ──────────────────────────────────
            item {
                ScrollableTabRow(
                    selectedTabIndex = if (selectedPlatform == null) 0
                        else netplayPlatforms.indexOf(selectedPlatform) + 1,
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 20.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedPlatform == null,
                        onClick = { viewModel.setSelectedPlatform(null) }
                    ) {
                        Text(
                            text = "All",
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            fontWeight = if (selectedPlatform == null) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedPlatform == null) VortexMagenta
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    netplayPlatforms.forEach { platform ->
                        Tab(
                            selected = selectedPlatform == platform,
                            onClick = { viewModel.setSelectedPlatform(platform) }
                        ) {
                            Text(
                                text = platform.abbreviation,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                fontWeight = if (selectedPlatform == platform) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedPlatform == platform) VortexMagenta
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Results ─────────────────────────────────────────────
            if (netplayGames.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.SportsEsports,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "No games match your search"
                                    else "No compatible games found",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (searchQuery.isNotBlank()) "Try a different name or clear filters"
                                    else "Scan ROMs for netplay-supported platforms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            items(netplayGames, key = { it.id }) { game ->
                NetplayGameCard(
                    game = game,
                    onClick = { onGameClick(game.id) },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Connection status
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun ConnectionStatusBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = VortexGreen.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.SignalWifiStatusbar4Bar,
                contentDescription = null,
                tint = VortexGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Network Ready",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = VortexGreen,
                    maxLines = 1
                )
                Text(
                    text = "LAN netplay via UDP • Host or join a session on same network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Expanded panels
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun HostPanel(
    viewModel: MultiplayerViewModel,
    onSearchGame: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val session by viewModel.netplaySession.collectAsState()
    val netplayState by viewModel.netplayState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = VortexCyan.copy(alpha = 0.06f)
        ),
        border = BorderStroke(1.dp, VortexCyan.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Host Configuration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = VortexCyan,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (session != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Room Code",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = session?.roomCode ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = VortexCyan
                        )
                        Text(
                            text = "IP: ${session?.hostAddress ?: "unknown"}:${session?.port ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(
                        label = "Players: ${session?.playerCount ?: 1}/2",
                        color = VortexCyan
                    )
                    StatusChip(
                        label = when (netplayState) {
                            NetplayState.WAITING -> "Waiting…"
                            NetplayState.CONNECTED -> "Connected"
                            else -> "Ready"
                        },
                        color = when (netplayState) {
                            NetplayState.CONNECTED -> VortexGreen
                            NetplayState.WAITING -> VortexOrange
                            else -> VortexCyan
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, VortexRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = VortexRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Hosting", color = VortexRed, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    text = "Select a game from below, then start hosting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.hostSession("Multiplayer Game") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, VortexCyan.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = VortexCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Hosting", color = VortexCyan, fontWeight = FontWeight.Bold)
                }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexOrange
                )
            }
        }
    }
}

@Composable
private fun JoinPanel(
    viewModel: MultiplayerViewModel,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val joinIp by viewModel.joinIpAddress.collectAsState()
    val netplayState by viewModel.netplayState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = VortexMagenta.copy(alpha = 0.06f)
        ),
        border = BorderStroke(1.dp, VortexMagenta.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Join a Session",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = VortexMagenta
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (netplayState == NetplayState.CONNECTED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(label = "Connected", color = VortexGreen)
                    if (latencyMs > 0) {
                        StatusChip(label = "${latencyMs}ms", color = VortexCyan)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, VortexRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = VortexRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect", color = VortexRed, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedTextField(
                    value = joinIp,
                    onValueChange = { viewModel.setJoinIpAddress(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Host IP Address") },
                    placeholder = { Text("e.g. 192.168.1.100") },
                    singleLine = true,
                    trailingIcon = {
                        if (joinIp.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setJoinIpAddress("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        focusManager.clearFocus()
                        viewModel.joinSession()
                    }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VortexMagenta,
                        cursorColor = VortexMagenta,
                        focusedLabelColor = VortexMagenta
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.joinSession()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = joinIp.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (joinIp.isNotBlank()) VortexMagenta.copy(alpha = 0.5f) else Color.Transparent)
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, tint = VortexMagenta)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect", color = VortexMagenta, fontWeight = FontWeight.Bold)
                }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexOrange
                )
            }
        }
    }
}

@Composable
private fun LobbyPanel(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = VortexGreen.copy(alpha = 0.06f)
        ),
        border = BorderStroke(1.dp, VortexGreen.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Public Sessions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = VortexGreen
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Placeholder — no server backend yet
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        tint = VortexGreen.copy(alpha = 0.4f),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No public sessions available right now",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh", color = VortexGreen, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Reusable pieces
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun NetplayGameCard(
    game: Game,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
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
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = VortexMagenta,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = game.platform.abbreviation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = VortexGreen.copy(alpha = 0.12f)
            ) {
                Text(
                    text = "Play",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = VortexGreen
                )
            }
        }
    }
}

@Composable
private fun MultiplayerModeCard(
    title: String,
    description: String,
    accent: Color,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) accent else Color.Transparent,
        animationSpec = tween(250),
        label = "border"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(250),
        label = "bg"
    )

    Card(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.5.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
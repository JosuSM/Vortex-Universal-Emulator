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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalWifiStatusbar4Bar
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.core.CoreFeature
import com.vortex.emulator.game.Game
import com.vortex.emulator.netplay.LobbyPlayer
import com.vortex.emulator.netplay.LobbyRoom
import com.vortex.emulator.netplay.LobbyState
import com.vortex.emulator.netplay.NetplayState
import com.vortex.emulator.ui.components.VortexHeader
import com.vortex.emulator.ui.theme.VortexCyan
import com.vortex.emulator.ui.theme.VortexGreen
import com.vortex.emulator.ui.theme.VortexMagenta
import com.vortex.emulator.ui.theme.VortexOrange
import com.vortex.emulator.ui.theme.VortexPurple
import com.vortex.emulator.ui.theme.VortexRed
import com.vortex.emulator.ui.viewmodel.LobbyViewMode
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

    // Navigate to emulation when lobby game starts and game is resolved
    val gameStartId by viewModel.gameStartId.collectAsState()
    LaunchedEffect(gameStartId) {
        val id = gameStartId
        if (id != null) {
            viewModel.clearGameStartId()
            onGameClick(id)
        }
    }

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
                            description = "Encrypted global internet multiplayer.",
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
                            description = "Encrypted global internet multiplayer.",
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
                    LobbyPanel(
                        viewModel = viewModel,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
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
    val joinCode by viewModel.joinRoomCode.collectAsState()
    val netplayState by viewModel.netplayState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val localSessions by viewModel.localSessions.collectAsState()
    val playerName by viewModel.playerName.collectAsState()

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
                // ── Your Name ──
                OutlinedTextField(
                    value = playerName,
                    onValueChange = { viewModel.setPlayerName(it) },
                    label = { Text("Your Name") },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VortexMagenta,
                        cursorColor = VortexMagenta,
                        focusedLabelColor = VortexMagenta
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))

                // ── Join by Room Code ──
                Text(
                    text = "JOIN BY ROOM CODE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = VortexMagenta.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { viewModel.setJoinRoomCode(it.uppercase().take(9)) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("e.g. VRTX-AB12") },
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            focusManager.clearFocus()
                            viewModel.joinByRoomCode()
                        }),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VortexMagenta,
                            cursorColor = VortexMagenta,
                            focusedLabelColor = VortexMagenta
                        )
                    )
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.joinByRoomCode()
                        },
                        enabled = joinCode.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VortexMagenta)
                    ) {
                        Text("Join", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Join by IP Address ──
                Text(
                    text = "JOIN BY IP (LAN)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = VortexMagenta.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = joinIp,
                        onValueChange = { viewModel.setJoinIpAddress(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("e.g. 192.168.1.100") },
                        leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                        singleLine = true,
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
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.joinSession()
                        },
                        enabled = joinIp.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (joinIp.isNotBlank()) VortexMagenta.copy(alpha = 0.5f) else Color.Transparent)
                    ) {
                        Text("Connect", color = VortexMagenta, fontWeight = FontWeight.Bold)
                    }
                }

                // ── Local Sessions ──
                if (localSessions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "LOCAL SESSIONS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexMagenta.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        localSessions.forEach { session ->
                            LocalSessionCard(
                                session = session,
                                onJoin = {
                                    viewModel.setJoinRoomCode(session.roomCode)
                                    viewModel.joinByRoomCode()
                                }
                            )
                        }
                    }
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
private fun LobbyPanel(
    viewModel: MultiplayerViewModel,
    modifier: Modifier = Modifier
) {
    val lobbyState by viewModel.lobbyState.collectAsState()
    val rooms by viewModel.filteredRooms.collectAsState()
    val featuredRooms by viewModel.featuredRooms.collectAsState()
    val currentRoom by viewModel.currentRoom.collectAsState()
    val players by viewModel.lobbyPlayers.collectAsState()
    val chatMessages by viewModel.lobbyChatMessages.collectAsState()
    val playerName by viewModel.playerName.collectAsState()
    val lobbySearch by viewModel.lobbySearchQuery.collectAsState()
    val showCreateRoom by viewModel.showCreateRoom.collectAsState()
    val isReady by viewModel.isReady.collectAsState()
    val chatInput by viewModel.chatInput.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isGameActive by viewModel.isGameActive.collectAsState()
    val netplayPlatforms by viewModel.netplayPlatformList.collectAsState()
    val selectedLobbyPlatform by viewModel.selectedLobbyPlatform.collectAsState()
    val lobbyViewMode by viewModel.lobbyViewMode.collectAsState()

    // Password dialog state for joining locked rooms
    var pendingJoinRoom by remember { mutableStateOf<LobbyRoom?>(null) }
    var joinPassword by remember { mutableStateOf("") }

    // Password dialog
    if (pendingJoinRoom != null) {
        val room = pendingJoinRoom!!
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = VortexOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Password Required",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexOrange
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${room.gameName} — hosted by ${room.hostName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = joinPassword,
                    onValueChange = { joinPassword = it },
                    label = { Text("Room Password") },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VortexOrange,
                        cursorColor = VortexOrange,
                        focusedLabelColor = VortexOrange
                    )
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            pendingJoinRoom = null
                            joinPassword = ""
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            viewModel.setRoomPassword(joinPassword)
                            viewModel.joinLobbyRoom(room)
                            pendingJoinRoom = null
                            joinPassword = ""
                        },
                        modifier = Modifier.weight(1f),
                        enabled = joinPassword.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VortexOrange)
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Join", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = VortexGreen.copy(alpha = 0.06f)
        ),
        border = BorderStroke(1.dp, VortexGreen.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header with encryption badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        tint = VortexGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Public Lobbies",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexGreen
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = VortexCyan.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = VortexCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "AES-256 E2E",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = VortexCyan
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "End-to-end encrypted internet play — zero-knowledge relay",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Player name field
            OutlinedTextField(
                value = playerName,
                onValueChange = { viewModel.setPlayerName(it) },
                label = { Text("Your Name") },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VortexGreen,
                    cursorColor = VortexGreen,
                    focusedLabelColor = VortexGreen
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            when {
                // ── In a room ───────────────────────────────────
                currentRoom != null -> {
                    RoomDetailView(
                        room = currentRoom!!,
                        players = players,
                        chatMessages = chatMessages,
                        chatInput = chatInput,
                        isReady = isReady,
                        isGameActive = isGameActive,
                        isHost = players.any { it.playerId == viewModel.internetNetplay.lobbyClient.playerId && it.isHost },
                        onChatInputChange = { viewModel.setChatInput(it) },
                        onSendChat = { viewModel.sendChatMessage() },
                        onToggleReady = { viewModel.toggleReady() },
                        onStartGame = { viewModel.startGameAsHost() },
                        onLeaveRoom = { viewModel.leaveLobbyRoom() }
                    )
                }

                // ── Create room form ────────────────────────────
                showCreateRoom -> {
                    CreateRoomForm(
                        viewModel = viewModel,
                        platforms = netplayPlatforms,
                        onCancel = { viewModel.setShowCreateRoom(false) }
                    )
                }

                // ── Not connected ───────────────────────────────
                lobbyState == LobbyState.DISCONNECTED || lobbyState == LobbyState.ERROR -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.Public,
                                contentDescription = null,
                                tint = VortexGreen.copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Connect to the global lobby to browse and create rooms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.connectToLobby() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VortexGreen)
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect to Global Lobby", fontWeight = FontWeight.Bold)
                    }
                }

                // ── Connecting ──────────────────────────────────
                lobbyState == LobbyState.CONNECTING -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = VortexGreen,
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Connecting to lobby…",
                                style = MaterialTheme.typography.bodySmall,
                                color = VortexGreen
                            )
                        }
                    }
                }

                // ── Connected — show lobby browser ──────────────
                else -> {
                    // Action bar: Create Room + Refresh + Disconnect
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.setShowCreateRoom(true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VortexGreen)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Room", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.refreshRooms() },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, VortexGreen.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = VortexGreen)
                        }
                        OutlinedButton(
                            onClick = { viewModel.disconnectFromLobby() },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, VortexRed.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = VortexRed)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ── View Mode Tabs: Featured / Search / All ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LobbyViewTab(
                            label = "Featured",
                            selected = lobbyViewMode == LobbyViewMode.FEATURED,
                            color = VortexGreen,
                            onClick = { viewModel.setLobbyViewMode(LobbyViewMode.FEATURED) },
                            modifier = Modifier.weight(1f)
                        )
                        LobbyViewTab(
                            label = "Search",
                            selected = lobbyViewMode == LobbyViewMode.SEARCH,
                            color = VortexCyan,
                            onClick = { viewModel.setLobbyViewMode(LobbyViewMode.SEARCH) },
                            modifier = Modifier.weight(1f)
                        )
                        LobbyViewTab(
                            label = "Show All",
                            selected = lobbyViewMode == LobbyViewMode.ALL,
                            color = VortexPurple,
                            onClick = {
                                viewModel.setLobbyViewMode(LobbyViewMode.ALL)
                                viewModel.requestAllPublicRooms()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    when (lobbyViewMode) {
                        // ── FEATURED: Top 5 active lobbies ──
                        LobbyViewMode.FEATURED -> {
                            Text(
                                text = "POPULAR LOBBIES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = VortexGreen.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (featuredRooms.isEmpty()) {
                                EmptyLobbyPlaceholder(message = "No active lobbies — create one!")
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    featuredRooms.forEach { room ->
                                        LobbyRoomCard(
                                            room = room,
                                            onJoin = {
                                                if (room.isPasswordProtected) {
                                                    pendingJoinRoom = room
                                                } else {
                                                    viewModel.joinLobbyRoom(room)
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Quick stats
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusChip(label = "${featuredRooms.size} featured", color = VortexGreen)
                                StatusChip(label = "Encrypted", color = VortexCyan)
                            }
                        }

                        // ── SEARCH: Advanced search with filters ──
                        LobbyViewMode.SEARCH -> {
                            Text(
                                text = "SEARCH LOBBIES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = VortexCyan.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Enhanced search bar
                            OutlinedTextField(
                                value = lobbySearch,
                                onValueChange = { viewModel.setLobbySearchQuery(it) },
                                placeholder = { Text("Game, core, host, playability…") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (lobbySearch.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.setLobbySearchQuery("") }) {
                                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = VortexCyan,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Search hint chips
                            Text(
                                text = "Search by: game name, core, emulator, title, playability, host, region",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Platform filter chips
                            if (netplayPlatforms.isNotEmpty()) {
                                ScrollableTabRow(
                                    selectedTabIndex = if (selectedLobbyPlatform == null) 0
                                        else netplayPlatforms.indexOf(selectedLobbyPlatform) + 1,
                                    modifier = Modifier.fillMaxWidth(),
                                    edgePadding = 0.dp,
                                    containerColor = Color.Transparent,
                                    contentColor = VortexCyan,
                                    divider = {}
                                ) {
                                    Tab(
                                        selected = selectedLobbyPlatform == null,
                                        onClick = { viewModel.setSelectedLobbyPlatform(null) }
                                    ) {
                                        Text(
                                            text = "All",
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                                            fontWeight = if (selectedLobbyPlatform == null) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedLobbyPlatform == null) VortexCyan
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    netplayPlatforms.forEach { p ->
                                        Tab(
                                            selected = selectedLobbyPlatform == p,
                                            onClick = { viewModel.setSelectedLobbyPlatform(p) }
                                        ) {
                                            Text(
                                                text = p.abbreviation,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                                                fontWeight = if (selectedLobbyPlatform == p) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selectedLobbyPlatform == p) VortexCyan
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Results count
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusChip(label = "${rooms.size} results", color = VortexCyan)
                                if (lobbySearch.isNotBlank() || selectedLobbyPlatform != null) {
                                    Surface(
                                        onClick = {
                                            viewModel.setLobbySearchQuery("")
                                            viewModel.setSelectedLobbyPlatform(null)
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        color = VortexRed.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = "Clear Filters",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = VortexRed
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Search results
                            if (rooms.isEmpty()) {
                                EmptyLobbyPlaceholder(
                                    message = if (lobbySearch.isNotBlank()) "No lobbies match your search"
                                        else "No rooms found"
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rooms.forEach { room ->
                                        LobbyRoomCard(
                                            room = room,
                                            onJoin = {
                                                if (room.isPasswordProtected) {
                                                    pendingJoinRoom = room
                                                } else {
                                                    viewModel.joinLobbyRoom(room)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // ── ALL: Show every public lobby ──
                        LobbyViewMode.ALL -> {
                            Text(
                                text = "ALL PUBLIC LOBBIES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = VortexPurple.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusChip(label = "${rooms.size} lobbies", color = VortexPurple)
                                StatusChip(label = "Encrypted", color = VortexCyan)
                                Surface(
                                    onClick = { viewModel.requestAllPublicRooms() },
                                    shape = RoundedCornerShape(999.dp),
                                    color = VortexPurple.copy(alpha = 0.12f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Filled.Refresh,
                                            contentDescription = null,
                                            tint = VortexPurple,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Refresh",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = VortexPurple
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (rooms.isEmpty()) {
                                EmptyLobbyPlaceholder(message = "No public lobbies available — create the first!")
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rooms.forEach { room ->
                                        LobbyRoomCard(
                                            room = room,
                                            onJoin = {
                                                if (room.isPasswordProtected) {
                                                    pendingJoinRoom = room
                                                } else {
                                                    viewModel.joinLobbyRoom(room)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Status message
            if (statusMessage.isNotBlank() && currentRoom == null) {
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

// ═══════════════════════════════════════════════════════════════════
// Lobby Room Card — shows game, core, host, and playability
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun LobbyRoomCard(
    room: LobbyRoom,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playabilityColor = when (room.playability.lowercase()) {
        "playable", "in-game" -> VortexGreen
        "waiting", "waiting for players" -> VortexOrange
        "full" -> VortexRed
        else -> VortexCyan
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Game name row with icons
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = VortexMagenta,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = room.gameName.ifBlank { "Untitled Game" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (room.isPasswordProtected) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Password protected",
                        tint = VortexOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (room.isEncrypted) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = "Encrypted",
                        tint = VortexCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Host + Core + Platform info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Host
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = room.hostName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Core
                if (room.coreName.isNotBlank()) {
                    Text(
                        text = room.coreName,
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexCyan,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                // Platform
                if (room.platform != null) {
                    Text(
                        text = room.platform.abbreviation,
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexPurple,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom row: players, playability, region, join button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Player count
                    StatusChip(
                        label = "${room.playerCount}/${room.maxPlayers}",
                        color = if (room.playerCount < room.maxPlayers) VortexGreen else VortexRed
                    )
                    // Playability
                    StatusChip(label = room.playability, color = playabilityColor)
                    // Region
                    if (room.region.isNotBlank()) {
                        StatusChip(label = room.region, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (room.playerCount < room.maxPlayers) {
                    OutlinedButton(
                        onClick = onJoin,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, VortexGreen.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Join", color = VortexGreen, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = VortexRed.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "Full",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = VortexRed
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Local Session Card — LAN sessions with room code and game name
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun LocalSessionCard(
    session: LobbyRoom,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = VortexMagenta,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.gameName.ifBlank { "Multiplayer Game" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Host: ${session.hostName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Code: ${session.roomCode}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = VortexMagenta
                    )
                }
                Text(
                    text = session.playability,
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexOrange
                )
            }
            OutlinedButton(
                onClick = onJoin,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, VortexMagenta.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("Join", color = VortexMagenta, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Lobby View Mode Tab
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun LobbyViewTab(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) color else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Empty lobby placeholder
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun EmptyLobbyPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Create Room Form
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun CreateRoomForm(
    viewModel: MultiplayerViewModel,
    platforms: List<com.vortex.emulator.core.Platform>,
    onCancel: () -> Unit
) {
    var gameName by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf(platforms.firstOrNull()) }
    var maxPlayers by remember { mutableStateOf("2") }
    var password by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    // Get game suggestions based on selected platform and typed text
    val allGames by viewModel.netplayGames.collectAsState()
    val suggestions = remember(gameName, selectedPlatform, allGames) {
        if (gameName.length < 1) {
            // Show recent/popular games for the selected platform
            allGames
                .filter { selectedPlatform == null || it.platform == selectedPlatform }
                .sortedByDescending { it.lastPlayed ?: 0L }
                .take(5)
        } else {
            allGames
                .filter { selectedPlatform == null || it.platform == selectedPlatform }
                .filter { it.title.contains(gameName, ignoreCase = true) }
                .sortedByDescending { it.lastPlayed ?: 0L }
                .take(5)
        }
    }

    Text(
        text = "Create Encrypted Room",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = VortexGreen
    )
    Spacer(modifier = Modifier.height(12.dp))

    // Game Name with suggestions
    OutlinedTextField(
        value = gameName,
        onValueChange = {
            gameName = it
            showSuggestions = true
        },
        label = { Text("Game Name") },
        placeholder = { Text("e.g. Super Mario 64") },
        leadingIcon = { Icon(Icons.Filled.SportsEsports, contentDescription = null) },
        trailingIcon = {
            if (gameName.isNotEmpty()) {
                IconButton(onClick = { gameName = ""; showSuggestions = false }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VortexGreen,
            cursorColor = VortexGreen,
            focusedLabelColor = VortexGreen
        )
    )

    // Game suggestions dropdown
    if (showSuggestions && suggestions.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 4.dp
        ) {
            Column {
                suggestions.forEach { game ->
                    Surface(
                        onClick = {
                            gameName = game.title
                            selectedPlatform = game.platform
                            showSuggestions = false
                        },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.SportsEsports,
                                contentDescription = null,
                                tint = VortexGreen.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = game.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = game.platform.abbreviation,
                                style = MaterialTheme.typography.labelSmall,
                                color = VortexPurple,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Platform selector
    if (platforms.isNotEmpty()) {
        Text(
            text = "Platform",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        ScrollableTabRow(
            selectedTabIndex = platforms.indexOf(selectedPlatform).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            platforms.forEach { p ->
                Tab(
                    selected = selectedPlatform == p,
                    onClick = {
                        selectedPlatform = p
                        showSuggestions = true
                    }
                ) {
                    Text(
                        text = p.abbreviation,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                        fontWeight = if (selectedPlatform == p) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedPlatform == p) VortexGreen
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = maxPlayers,
            onValueChange = { maxPlayers = it.filter { c -> c.isDigit() }.take(1) },
            label = { Text("Max Players") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VortexGreen,
                cursorColor = VortexGreen,
                focusedLabelColor = VortexGreen
            )
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (optional)") },
            leadingIcon = {
                Icon(
                    if (password.isBlank()) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = null
                )
            },
            modifier = Modifier.weight(2f),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VortexOrange,
                cursorColor = VortexOrange,
                focusedLabelColor = VortexOrange
            )
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Encryption info
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = VortexCyan.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Security,
                contentDescription = null,
                tint = VortexCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "All game data is encrypted end-to-end with AES-256-GCM. The relay server never sees your inputs.",
                style = MaterialTheme.typography.bodySmall,
                color = VortexCyan
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text("Cancel")
        }
        Button(
            onClick = {
                viewModel.setRoomPassword(password)
                viewModel.createRoom(
                    gameName = gameName.ifBlank { "Multiplayer Game" },
                    platform = selectedPlatform ?: platforms.first(),
                    coreName = "",
                    maxPlayers = maxPlayers.toIntOrNull()?.coerceIn(2, 8) ?: 2
                )
            },
            modifier = Modifier.weight(2f),
            shape = RoundedCornerShape(12.dp),
            enabled = gameName.isNotBlank() || selectedPlatform != null,
            colors = ButtonDefaults.buttonColors(containerColor = VortexGreen)
        ) {
            Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Create Encrypted Room", fontWeight = FontWeight.Bold)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Room Detail View (in-room)
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun RoomDetailView(
    room: LobbyRoom,
    players: List<LobbyPlayer>,
    chatMessages: List<Pair<String, String>>,
    chatInput: String,
    isReady: Boolean,
    isGameActive: Boolean,
    isHost: Boolean,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onToggleReady: () -> Unit,
    onStartGame: () -> Unit,
    onLeaveRoom: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    // Room header
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = room.gameName.ifBlank { "Room" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(label = "Code: ${room.roomCode}", color = VortexGreen)
                if (room.isEncrypted) {
                    StatusChip(label = "E2E Encrypted", color = VortexCyan)
                }
            }
        }
        OutlinedButton(
            onClick = onLeaveRoom,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, VortexRed.copy(alpha = 0.5f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Filled.ExitToApp, contentDescription = null, tint = VortexRed, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Leave", color = VortexRed, style = MaterialTheme.typography.labelMedium)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Players list
    Text(
        text = "Players (${players.size}/${room.maxPlayers})",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(8.dp))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        players.forEach { player ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (player.isHost) VortexGreen.copy(alpha = 0.15f)
                            else VortexPurple.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "P${player.playerIndex + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (player.isHost) VortexGreen else VortexPurple
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = player.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (player.isHost) {
                                Spacer(modifier = Modifier.width(6.dp))
                                StatusChip(label = "Host", color = VortexGreen)
                            }
                        }
                        if (player.latencyMs > 0) {
                            Text(
                                text = "${player.latencyMs}ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (player.isReady) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Ready",
                            tint = VortexGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Chat area
    Text(
        text = "Chat",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(6.dp))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp, max = 120.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (chatMessages.isEmpty()) {
                Text(
                    text = "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            } else {
                chatMessages.forEach { (from, msg) ->
                    Row {
                        Text(
                            text = "$from: ",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = VortexCyan
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = chatInput,
            onValueChange = onChatInputChange,
            placeholder = { Text("Message…") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                focusManager.clearFocus()
                onSendChat()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VortexCyan,
                cursorColor = VortexCyan
            )
        )
        IconButton(
            onClick = {
                focusManager.clearFocus()
                onSendChat()
            }
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send", tint = VortexCyan)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Action buttons: Ready / Start
    if (isGameActive) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = VortexGreen.copy(alpha = 0.12f)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = VortexGreen)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Encrypted session active",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = VortexGreen
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onToggleReady,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (isReady) VortexGreen else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (isReady) VortexGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isReady) "Ready!" else "Ready Up",
                    color = if (isReady) VortexGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
            if (isHost) {
                Button(
                    onClick = onStartGame,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = players.all { it.isReady || it.isHost },
                    colors = ButtonDefaults.buttonColors(containerColor = VortexGreen)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Game", fontWeight = FontWeight.Bold)
                }
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
package com.vortex.emulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortex.emulator.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "What's New",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ── Hero banner ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    VortexCyan.copy(alpha = 0.25f),
                                    VortexPurple.copy(alpha = 0.20f)
                                )
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.RocketLaunch,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = VortexCyan
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Version 2.1-Galaxy",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = VortexCyan
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "March 2026",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Multiplayer section ──
            ChangelogSection(
                icon = Icons.Filled.Wifi,
                title = "Global Online Multiplayer",
                items = listOf(
                    "Public lobby server — browse, create, and join rooms worldwide",
                    "Password-protected rooms with secure join dialog",
                    "Room codes for private sessions — share a code and play instantly",
                    "Featured, Search, and All Public lobby tabs for easy browsing",
                    "Real-time lobby chat between players",
                    "24/7 server uptime with automatic keep-alive"
                )
            )

            // ── Immersive Gaming ──
            ChangelogSection(
                icon = Icons.Filled.Fullscreen,
                title = "Immersive Gaming Experience",
                items = listOf(
                    "Full immersive mode — status bar and navigation bar are hidden during gameplay",
                    "App registered as a Game on Android — Game Mode and optimizations enabled",
                    "Smoother exit handling — no more crashes when leaving N64 or heavy games",
                    "Improved frame pacing for a more consistent experience"
                )
            )

            // ── Emulation Improvements ──
            ChangelogSection(
                icon = Icons.Filled.Memory,
                title = "Emulation Improvements",
                items = listOf(
                    "N64 stability fix — resolved crash on exit caused by thread race condition",
                    "Enhanced audio engine with low-latency mode and volume control",
                    "Auto frame-skip for weaker devices — keeps games playable",
                    "NDS/3DS touch screen overlay for stylus-based games",
                    "Physical gamepad support — on-screen controls hide automatically"
                )
            )

            // ── UI & Core Management ──
            ChangelogSection(
                icon = Icons.Filled.Palette,
                title = "Interface & Core Management",
                items = listOf(
                    "Redesigned game library with long-press core selector",
                    "Game suggestions when creating multiplayer rooms",
                    "Scrollable lobby panels — no more overflow on smaller screens",
                    "Offline core downloading with automatic platform detection",
                    "Save state import/export via SAF — backup anywhere"
                )
            )

            // ── What's Next ──
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = VortexPurple,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "More coming soon",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = VortexPurple
                        )
                        Text(
                            text = "Stay tuned for achievements, cloud saves, and new platform support.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ChangelogSection(
    icon: ImageVector,
    title: String,
    items: List<String>
) {
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = VortexCyan,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = VortexCyan
            )
        }
        items.forEach { item ->
            Row(
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(VortexCyan.copy(alpha = 0.7f))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

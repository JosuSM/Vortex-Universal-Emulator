package com.vortex.emulator.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.ui.components.*
import com.vortex.emulator.ui.theme.*
import com.vortex.emulator.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onGameClick: (Long) -> Unit,
    onViewAllRecent: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val recentGames by viewModel.recentGames.collectAsState()
    val favoriteGames by viewModel.favoriteGames.collectAsState()
    val gameCount by viewModel.gameCount.collectAsState()
    val chipsetTier by viewModel.chipsetTier.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Hero Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
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
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "VORTEX",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = VortexCyan
                )
                Text(
                    text = "EMULATOR",
                    style = MaterialTheme.typography.headlineSmall,
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

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Stats Row
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
                icon = Icons.Filled.Memory,
                value = "14",
                label = "Cores",
                gradientColors = listOf(VortexPurple.copy(alpha = 0.15f), VortexPurple.copy(alpha = 0.05f)),
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
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionCard(
                icon = Icons.Filled.FolderOpen,
                title = "Scan for ROMs",
                description = "Search your device for game files",
                accentColor = VortexCyan,
                onClick = { viewModel.scanDefaultPaths() }
            )
            QuickActionCard(
                icon = Icons.Filled.Download,
                title = "Manage Cores",
                description = "Download and update emulation cores",
                accentColor = VortexPurple,
                onClick = { }
            )
            QuickActionCard(
                icon = Icons.Filled.Tune,
                title = "Auto-Configure",
                description = "Optimize settings for your device",
                accentColor = VortexGreen,
                onClick = { }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
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
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

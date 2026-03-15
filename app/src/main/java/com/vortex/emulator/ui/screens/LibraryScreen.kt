package com.vortex.emulator.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.core.Platform
import com.vortex.emulator.ui.components.*
import com.vortex.emulator.ui.theme.VortexCyan
import com.vortex.emulator.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onGameClick: (Long) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val games by viewModel.filteredGames.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val compactLayout = maxWidth < 420.dp

        Column(modifier = Modifier.fillMaxSize()) {
        if (!isLandscape) {
            VortexHeader(
                title = "Game Library",
                subtitle = "${games.size} games available"
            )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 12.dp))

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Search games...") },
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
                focusedBorderColor = VortexCyan,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Platform filter chips + view toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // View mode toggle
            Row {
                IconButton(onClick = { viewModel.setViewMode(ViewMode.GRID) }) {
                    Icon(
                        Icons.Filled.GridView,
                        contentDescription = "Grid",
                        tint = if (viewMode == ViewMode.GRID) VortexCyan
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.setViewMode(ViewMode.LIST) }) {
                    Icon(
                        Icons.Filled.ViewList,
                        contentDescription = "List",
                        tint = if (viewMode == ViewMode.LIST) VortexCyan
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Platform filter
        ScrollableTabRow(
            selectedTabIndex = Platform.entries.indexOf(selectedPlatform) + 1,
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
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                    fontWeight = if (selectedPlatform == null) FontWeight.Bold else FontWeight.Normal,
                    color = if (selectedPlatform == null) VortexCyan
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Platform.entries.forEach { platform ->
                Tab(
                    selected = selectedPlatform == platform,
                    onClick = { viewModel.setSelectedPlatform(platform) }
                ) {
                    Text(
                        text = platform.abbreviation,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                        fontWeight = if (selectedPlatform == platform) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedPlatform == platform) platform.toColor()
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Games list
        if (games.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No games found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Scan a folder to add ROMs to your library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            if (viewMode == ViewMode.GRID) {
                val gridMinSize = when {
                    isLandscape -> 160.dp
                    compactLayout -> 148.dp
                    else -> 180.dp
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(gridMinSize),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(games, key = { it.id }) { game ->
                        GameCard(
                            game = game,
                            onClick = { onGameClick(game.id) },
                            cardWidth = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(games, key = { it.id }) { game ->
                        GameListItem(
                            game = game,
                            onClick = { onGameClick(game.id) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
        }
    }
}

enum class ViewMode { GRID, LIST }

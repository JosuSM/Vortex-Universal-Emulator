package com.vortex.emulator.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : Screen(
        route = "home",
        title = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    )
    data object Library : Screen(
        route = "library",
        title = "Library",
        selectedIcon = Icons.Filled.Gamepad,
        unselectedIcon = Icons.Outlined.Gamepad
    )
    data object Cores : Screen(
        route = "cores",
        title = "Cores",
        selectedIcon = Icons.Filled.Memory,
        unselectedIcon = Icons.Outlined.Memory
    )
    data object Performance : Screen(
        route = "performance",
        title = "Device",
        selectedIcon = Icons.Filled.Speed,
        unselectedIcon = Icons.Outlined.Speed
    )
    data object Multiplayer : Screen(
        route = "multiplayer",
        title = "Online",
        selectedIcon = Icons.Filled.Wifi,
        unselectedIcon = Icons.Filled.Wifi
    )
    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
    data object Emulation : Screen(
        route = "emulation/{gameId}",
        title = "Play",
        selectedIcon = Icons.Filled.Gamepad,
        unselectedIcon = Icons.Outlined.Gamepad
    ) {
        fun createRoute(gameId: Long) = "emulation/$gameId"
    }

    companion object {
        val bottomNavItems = listOf(Home, Library, Cores, Multiplayer, Settings)
    }
}

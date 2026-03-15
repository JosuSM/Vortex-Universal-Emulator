package com.vortex.emulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vortex.emulator.ui.theme.*
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulationScreen(
    gameId: Long,
    onBack: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    var showFps by remember { mutableStateOf(true) }
    var currentFps by remember { mutableFloatStateOf(60f) }
    var isPaused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Emulation surface placeholder (in production: SurfaceView / TextureView)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            VortexSurfaceVariant,
                            Color.Black
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = VortexCyan.copy(alpha = 0.3f),
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Emulation Surface",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VortexOnSurfaceVariant.copy(alpha = 0.4f)
                )
                Text(
                    text = "Game #$gameId",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexOnSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }

        // FPS Counter
        if (showFps) {
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
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Row {
                IconButton(onClick = { isPaused = !isPaused }) {
                    Icon(
                        if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Menu",
                        tint = Color.White
                    )
                }
            }
        }

        // On-screen controls
        if (showControls) {
            // D-Pad (left side)
            DPad(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 30.dp, bottom = 60.dp)
            )

            // Action buttons (right side)
            ActionButtons(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 30.dp, bottom = 60.dp)
            )

            // Center buttons (Start, Select)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SmallControlButton(label = "SELECT")
                SmallControlButton(label = "START")
            }

            // Shoulder buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ShoulderButton(label = "L")
                ShoulderButton(label = "R")
            }

            // Analog stick (for N64, PSX, etc.)
            AnalogStick(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 160.dp, bottom = 30.dp)
            )
        }

        // Quick menu bottom sheet
        if (showMenu) {
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                containerColor = VortexSurfaceContainer,
                contentColor = VortexOnSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Quick Menu",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = VortexCyan
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    QuickMenuItem(Icons.Filled.Save, "Save State") { }
                    QuickMenuItem(Icons.Filled.FileOpen, "Load State") { }
                    QuickMenuItem(Icons.Filled.FastRewind, "Rewind") { }
                    QuickMenuItem(Icons.Filled.FastForward, "Fast Forward") { }
                    QuickMenuItem(Icons.Filled.Refresh, "Reset") { }
                    QuickMenuItem(Icons.Filled.Screenshot, "Screenshot") { }
                    QuickMenuItem(Icons.Filled.Code, "Cheats") { }
                    QuickMenuItem(Icons.Filled.AutoAwesome, "Shaders") { }
                    QuickMenuItem(Icons.Filled.Gamepad, "Toggle Controls") {
                        showControls = !showControls
                        showMenu = false
                    }
                    QuickMenuItem(Icons.Filled.ExitToApp, "Quit Game") {
                        showMenu = false
                        onBack()
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun DPad(modifier: Modifier = Modifier) {
    val buttonColor = Color.White.copy(alpha = 0.12f)
    val pressedColor = VortexCyan.copy(alpha = 0.3f)

    Box(modifier = modifier.size(140.dp)) {
        // Up
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(48.dp, 48.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(buttonColor)
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, "Up", tint = Color.White.copy(alpha = 0.7f))
        }
        // Down
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(48.dp, 48.dp)
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(buttonColor)
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "Down", tint = Color.White.copy(alpha = 0.7f))
        }
        // Left
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp, 48.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                .background(buttonColor)
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowLeft, "Left", tint = Color.White.copy(alpha = 0.7f))
        }
        // Right
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(48.dp, 48.dp)
                .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                .background(buttonColor)
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowRight, "Right", tint = Color.White.copy(alpha = 0.7f))
        }
        // Center
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .background(buttonColor)
        )
    }
}

@Composable
fun ActionButtons(modifier: Modifier = Modifier) {
    val colors = listOf(
        VortexCyan.copy(alpha = 0.25f),
        VortexMagenta.copy(alpha = 0.25f),
        VortexGreen.copy(alpha = 0.25f),
        VortexOrange.copy(alpha = 0.25f)
    )
    val labels = listOf("A", "B", "X", "Y")
    val textColors = listOf(VortexCyan, VortexMagenta, VortexGreen, VortexOrange)

    Box(modifier = modifier.size(130.dp)) {
        // A (right)
        ActionButton(
            label = labels[0],
            color = colors[0],
            textColor = textColors[0],
            modifier = Modifier.align(Alignment.CenterEnd)
        )
        // B (bottom)
        ActionButton(
            label = labels[1],
            color = colors[1],
            textColor = textColors[1],
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // X (top)
        ActionButton(
            label = labels[2],
            color = colors[2],
            textColor = textColors[2],
            modifier = Modifier.align(Alignment.TopCenter)
        )
        // Y (left)
        ActionButton(
            label = labels[3],
            color = colors[3],
            textColor = textColors[3],
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

@Composable
fun ActionButton(
    label: String,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = textColor
        )
    }
}

@Composable
fun SmallControlButton(label: String) {
    Box(
        modifier = Modifier
            .width(60.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun ShoulderButton(label: String) {
    Box(
        modifier = Modifier
            .width(80.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun AnalogStick(modifier: Modifier = Modifier) {
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 35f

    Box(
        modifier = modifier.size(90.dp),
        contentAlignment = Alignment.Center
    ) {
        // Base
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
        )

        // Stick
        Box(
            modifier = Modifier
                .size(44.dp)
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
                        onDragEnd = { stickOffset = Offset.Zero },
                        onDragCancel = { stickOffset = Offset.Zero }
                    ) { _, dragAmount ->
                        val newOffset = stickOffset + dragAmount
                        val distance = sqrt(newOffset.x * newOffset.x + newOffset.y * newOffset.y)
                        stickOffset = if (distance <= maxRadius) {
                            newOffset
                        } else {
                            newOffset * (maxRadius / distance)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
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

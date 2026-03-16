package com.vortex.emulator.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.patcher.PatchInfo
import com.vortex.emulator.patcher.PatchResult
import com.vortex.emulator.patcher.PatchType
import com.vortex.emulator.ui.theme.*
import com.vortex.emulator.ui.viewmodel.PatcherViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherScreen(
    onBack: () -> Unit,
    viewModel: PatcherViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val romPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.setRomUri(it) }
    }

    val patchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.addPatchUri(it) }
    }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        viewModel.setOutputUri(uri)
    }

    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                            tint = VortexCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "ROM Patcher",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                "IPS · UPS · BPS · xdelta · PPF · APS",
                                fontSize = 10.sp,
                                color = VortexOnSurfaceVariant,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (state.backupCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = VortexMagenta) {
                                    Text("${state.backupCount}")
                                }
                            }
                        ) {
                            IconButton(onClick = { showBackupDialog = true }) {
                                Icon(
                                    Icons.Filled.Restore,
                                    contentDescription = "Backups",
                                    tint = VortexCyan
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Reset",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Hero banner ---
            item {
                PatcherHeroBanner()
            }

            // --- ROM Selection ---
            item {
                SectionLabel(icon = Icons.Filled.SdCard, title = "ROM File")
            }

            item {
                FilePickerCard(
                    label = if (state.romInfo != null) state.romInfo!!.fileName else "Select ROM file",
                    subtitle = state.romInfo?.let {
                        buildString {
                            append(formatFileSize(it.fileSize))
                            if (it.checksumCrc32.isNotEmpty()) append(" · CRC: ${it.checksumCrc32}")
                        }
                    },
                    icon = Icons.Filled.SdCard,
                    accentColor = VortexCyan,
                    isSelected = state.romUri != null,
                    isLoading = state.isAnalyzing,
                    onClick = { romPicker.launch(arrayOf("*/*")) }
                )
            }

            // ROM checksums
            if (state.romInfo != null && state.romInfo!!.checksumMd5.isNotEmpty()) {
                item {
                    ChecksumCard(
                        md5 = state.romInfo!!.checksumMd5,
                        crc32 = state.romInfo!!.checksumCrc32
                    )
                }
            }

            // --- Patch Files ---
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionLabel(icon = Icons.Filled.Healing, title = "Patch Files")
                    if (state.patchUris.size > 1) {
                        Text(
                            "Applied in order ↓",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexOnSurfaceVariant
                        )
                    }
                }
            }

            // Patch list
            if (state.patchUris.isEmpty()) {
                item {
                    FilePickerCard(
                        label = "Add patch file",
                        subtitle = "IPS, UPS, BPS, xdelta3, PPF, APS",
                        icon = Icons.Filled.Healing,
                        accentColor = VortexPurple,
                        isSelected = false,
                        onClick = { patchPicker.launch(arrayOf("*/*")) }
                    )
                }
            } else {
                itemsIndexed(state.patchUris) { index, _ ->
                    val patchInfo = state.patchInfos.getOrNull(index)
                    PatchItemCard(
                        index = index,
                        patchInfo = patchInfo,
                        totalPatches = state.patchUris.size,
                        onRemove = { viewModel.removePatch(index) },
                        onMoveUp = { if (index > 0) viewModel.movePatch(index, index - 1) },
                        onMoveDown = {
                            if (index < state.patchUris.size - 1) viewModel.movePatch(index, index + 1)
                        }
                    )
                }

                item {
                    OutlinedButton(
                        onClick = { patchPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VortexPurple),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, VortexPurple.copy(alpha = 0.3f)
                        )
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add another patch", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // --- Options ---
            item {
                SectionLabel(icon = Icons.Filled.Tune, title = "Options")
            }

            item {
                OptionsCard(
                    createBackup = state.createBackup,
                    onBackupChange = { viewModel.setCreateBackup(it) },
                    hasOutputFile = state.outputUri != null,
                    canRestore = state.canRestore,
                    onPickOutput = { outputPicker.launch("patched_rom") },
                    onClearOutput = { viewModel.setOutputUri(null) },
                    onRestore = { showRestoreConfirm = true }
                )
            }

            // --- Progress ---
            if (state.isPatching) {
                item {
                    PatchingProgressCard(progress = state.patchProgress)
                }
            }

            // --- Results ---
            if (state.results.isNotEmpty()) {
                item {
                    SectionLabel(icon = Icons.Filled.Assessment, title = "Results")
                }
                state.results.forEachIndexed { index, result ->
                    item(key = "result_$index") {
                        ResultCard(result = result, index = index)
                    }
                }
            }

            // --- Error ---
            state.error?.let { error ->
                item {
                    ErrorCard(message = error, onDismiss = { viewModel.dismissError() })
                }
            }

            // --- Apply Button ---
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { viewModel.applyPatches() },
                    enabled = state.romUri != null && state.patchUris.isNotEmpty() && !state.isPatching,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VortexCyan,
                        disabledContainerColor = VortexCyan.copy(alpha = 0.2f)
                    )
                ) {
                    if (state.isPatching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = VortexSurface,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Patching…",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = VortexSurface
                        )
                    } else {
                        Icon(
                            Icons.Filled.FlashOn,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = VortexSurface
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            if (state.patchUris.size > 1) "Apply ${state.patchUris.size} Patches"
                            else "Apply Patch",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = VortexSurface
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // Backup management dialog
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            icon = { Icon(Icons.Filled.Restore, contentDescription = null, tint = VortexCyan) },
            title = { Text("ROM Backups") },
            text = {
                Text("You have ${state.backupCount} backup(s). Clear all backups to free storage space.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearBackups()
                        showBackupDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = VortexRed)
                ) {
                    Text("Clear All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Restore confirmation
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            icon = { Icon(Icons.Filled.RestorePage, contentDescription = null, tint = VortexOrange) },
            title = { Text("Restore ROM?") },
            text = {
                Text("This will overwrite the current ROM with the backup. Any patches will be removed.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.restoreBackup()
                        showRestoreConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = VortexOrange)
                ) {
                    Text("Restore", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// --- Sub-composables ---

@Composable
private fun PatcherHeroBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "heroPulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

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
                            VortexPurple.copy(alpha = 0.15f),
                            VortexCyan.copy(alpha = 0.08f),
                            VortexMagenta.copy(alpha = 0.12f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            VortexPurple.copy(alpha = glowAlpha * 0.5f),
                            VortexCyan.copy(alpha = glowAlpha * 0.3f),
                            VortexMagenta.copy(alpha = glowAlpha * 0.5f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                VortexCyan.copy(alpha = 0.15f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.AutoFixHigh,
                            contentDescription = null,
                            tint = VortexCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            "Vortex Patcher",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = VortexOnSurface
                        )
                        Text(
                            "Patch, stack, verify, restore",
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexOnSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FormatBadge("IPS", VortexCyan)
                    FormatBadge("UPS", VortexPurple)
                    FormatBadge("BPS", VortexMagenta)
                    FormatBadge("xdelta", VortexGreen)
                    FormatBadge("PPF", VortexOrange)
                    FormatBadge("APS", VortexCyanDark)
                }
            }
        }
    }
}

@Composable
private fun FormatBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.height(22.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SectionLabel(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = VortexCyan,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = VortexCyan
        )
    }
}

@Composable
private fun FilePickerCard(
    label: String,
    subtitle: String? = null,
    icon: ImageVector,
    accentColor: Color,
    isSelected: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) accentColor.copy(alpha = 0.08f)
                             else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            1.dp, accentColor.copy(alpha = 0.3f)
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = accentColor,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        if (isSelected) Icons.Filled.CheckCircle else icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                Icons.Filled.FileOpen,
                contentDescription = "Browse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ChecksumCard(md5: String, crc32: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Checksums",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = VortexOnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("CRC32", style = MaterialTheme.typography.labelSmall, color = VortexOnSurfaceVariant)
                Text(
                    crc32,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = VortexCyan
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("MD5", style = MaterialTheme.typography.labelSmall, color = VortexOnSurfaceVariant)
                Text(
                    md5,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = VortexPurple,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp)
                )
            }
        }
    }
}

@Composable
private fun PatchItemCard(
    index: Int,
    patchInfo: PatchInfo?,
    totalPatches: Int,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val typeColor = when (patchInfo?.type) {
        PatchType.IPS -> VortexCyan
        PatchType.UPS -> VortexPurple
        PatchType.BPS -> VortexMagenta
        PatchType.XDELTA -> VortexGreen
        PatchType.PPF -> VortexOrange
        PatchType.APS -> VortexCyanDark
        else -> VortexOnSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = typeColor.copy(alpha = 0.06f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, typeColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index badge
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(typeColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = typeColor
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = patchInfo?.fileName ?: "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (patchInfo != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = typeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = patchInfo.type.displayName.substringBefore(" ("),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = typeColor,
                                fontSize = 9.sp
                            )
                        }
                        Text(
                            text = formatFileSize(patchInfo.fileSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexOnSurfaceVariant
                        )
                    }
                }
            }
            // Reorder & remove
            if (totalPatches > 1) {
                Column {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Move up",
                            modifier = Modifier.size(18.dp),
                            tint = if (index > 0) VortexOnSurface else VortexOnSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = index < totalPatches - 1,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Move down",
                            modifier = Modifier.size(18.dp),
                            tint = if (index < totalPatches - 1) VortexOnSurface
                                   else VortexOnSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(18.dp),
                    tint = VortexRed.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun OptionsCard(
    createBackup: Boolean,
    onBackupChange: (Boolean) -> Unit,
    hasOutputFile: Boolean,
    canRestore: Boolean,
    onPickOutput: () -> Unit,
    onClearOutput: () -> Unit,
    onRestore: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Auto backup toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto Backup",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Save original ROM before patching",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = createBackup,
                    onCheckedChange = onBackupChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VortexCyan,
                        checkedTrackColor = VortexCyan.copy(alpha = 0.3f)
                    )
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
            )

            // Output file
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPickOutput),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Output to new file",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (hasOutputFile) "Custom output selected"
                        else "Overwrite original ROM (default)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasOutputFile) VortexGreen
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hasOutputFile) {
                    IconButton(onClick = onClearOutput, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear",
                            tint = VortexRed.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = VortexOnSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Restore button
            if (canRestore) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRestore),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Restore from backup",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = VortexOrange
                        )
                        Text(
                            "Undo all patches applied to this ROM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.Restore,
                        contentDescription = null,
                        tint = VortexOrange,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchingProgressCard(progress: Pair<Int, Int>?) {
    val infiniteTransition = rememberInfiniteTransition(label = "patchSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing)
        ),
        label = "spin"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = VortexCyan.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = VortexCyan,
                modifier = Modifier
                    .size(28.dp)
                    .rotate(rotation)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Patching in progress…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = VortexCyan
                )
                if (progress != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Patch ${progress.first} of ${progress.second}",
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexOnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.first.toFloat() / progress.second },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = VortexCyan,
                        trackColor = VortexCyan.copy(alpha = 0.15f),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: PatchResult, index: Int) {
    val color = if (result.success) VortexGreen else VortexRed
    val icon = if (result.success) Icons.Filled.CheckCircle else Icons.Filled.Error

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.06f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                if (result.success && result.outputSize > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Output: ${formatFileSize(result.outputSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexOnSurfaceVariant
                        )
                        if (result.checksumAfter != 0L) {
                            Text(
                                "CRC: ${result.checksumAfter.toString(16).uppercase().padStart(8, '0')}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = VortexOnSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = VortexRed.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = VortexRed,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = VortexRed,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = VortexRed.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

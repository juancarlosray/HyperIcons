package de.bitbin.iconpacker.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.bitbin.iconpacker.iconpack.ComponentKey
import de.bitbin.iconpacker.picker.IconPickerContract
import de.bitbin.iconpacker.picker.PickerResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var pendingPick by remember { mutableStateOf<ComponentKey?>(null) }

    val ctx = LocalContext.current
    val currentPack = rememberUpdatedState(state.selectedPack ?: "")
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = remember { IconPickerContract { currentPack.value } }
    ) { result ->
        val key = pendingPick
        pendingPick = null
        if (key == null) return@rememberLauncherForActivityResult
        when (result) {
            is PickerResult.FromBitmap -> vm.onIconPicked(key, result.bitmap)
            is PickerResult.FromUri -> {
                runCatching {
                    ctx.contentResolver.openInputStream(result.uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()?.let { vm.onIconPicked(key, it) }
            }
            PickerResult.None -> Unit
        }
    }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "HyperIcons",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menú")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Exportar .mtz a Descargas") },
                            onClick = { menuOpen = false; vm.exportTheme() },
                        )
                        DropdownMenuItem(
                            text = { Text("Restablecer este paquete") },
                            onClick = { menuOpen = false; vm.clearAssignmentsForCurrentPack() },
                        )
                        DropdownMenuItem(
                            text = { Text("Recargar") },
                            onClick = { menuOpen = false; vm.reload() },
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Aplicar") },
                icon = { Icon(Icons.Default.Check, contentDescription = null) },
                onClick = { vm.applyTheme(ctx) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PackHeader(state, onSelect = { vm.selectPack(it) })
            state.statusMessage?.let { msg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            when {
                state.loading -> CenteredText("Cargando…")
                state.noPacksInstalled -> CenteredText(
                    "No se detectó ningún paquete de iconos. Instala uno (ej. Retro Icon Pack) y vuelve a abrir la app.",
                )
                state.iconPack == null -> CenteredText("No se pudo cargar el paquete de iconos.")
                else -> {
                    SummaryRow(state)
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.rows, key = { it.app.key.toString() }) { row ->
                            AppRowCard(
                                row = row,
                                onPick = {
                                    pendingPick = row.app.key
                                    pickerLauncher.launch(Unit)
                                },
                                onSkip = { vm.onSkip(row.app.key) },
                                onReset = { vm.onReset(row.app.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackHeader(state: UiState, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.availablePacks.firstOrNull { it.packageName == state.selectedPack }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Paquete de iconos",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    current?.label ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box {
                FilledTonalButton(onClick = { open = true }) { Text("Cambiar") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (state.availablePacks.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No se encontraron paquetes") },
                            onClick = { open = false },
                            enabled = false,
                        )
                    }
                    state.availablePacks.forEach { pack ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(pack.label)
                                    Text(
                                        pack.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                open = false
                                onSelect(pack.packageName)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(state: UiState) {
    val counts = state.rows.groupingBy { it.status }.eachCount()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SummaryPill(counts[RowStatus.UNMATCHED] ?: 0, "sin asignar", statusColor(RowStatus.UNMATCHED))
        SummaryPill(
            (counts[RowStatus.MATCHED] ?: 0) + (counts[RowStatus.MANUAL] ?: 0),
            "con icono",
            statusColor(RowStatus.MATCHED),
        )
        SummaryPill(counts[RowStatus.SKIPPED] ?: 0, "omitidos", statusColor(RowStatus.SKIPPED))
    }
}

@Composable
private fun SummaryPill(count: Int, label: String, accent: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$count $label",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppRowCard(
    row: AppRow,
    onPick: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit,
) {
    val themed = rememberThemedIcon(row.assignment)
    val original = if (row.assignment == null) rememberAppIcon(row.app.key.pkg) else null
    val accent = statusColor(row.status)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp, 36.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(12.dp))
            IconBox(themed ?: original)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.app.kind == de.bitbin.iconpacker.apps.AppKind.SHARE_TARGET) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                "compartir",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                StatusLine(row)
            }
            Spacer(Modifier.width(8.dp))
            RowActions(row.status, onPick, onSkip, onReset)
        }
    }
}

@Composable
private fun StatusLine(row: AppRow) {
    val (icon: ImageVector, text: String) = when (row.status) {
        RowStatus.UNMATCHED -> Icons.Outlined.HelpOutline to "sin coincidencia"
        RowStatus.MATCHED -> Icons.Outlined.AutoAwesome to (row.assignment?.drawable ?: "auto")
        RowStatus.MANUAL -> Icons.Outlined.Brush to "icono personalizado"
        RowStatus.SKIPPED -> Icons.Outlined.VisibilityOff to "omitido"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowActions(
    status: RowStatus,
    onPick: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit,
) {
    when (status) {
        RowStatus.UNMATCHED -> TextButton(onClick = onPick) { Text("Elegir") }
        RowStatus.MANUAL, RowStatus.SKIPPED -> TextButton(onClick = onReset) { Text("Restablecer") }
        RowStatus.MATCHED -> Row {
            TextButton(onClick = onPick) { Text("Elegir") }
            TextButton(onClick = onSkip) { Text("Omitir") }
        }
    }
}

@Composable
private fun IconBox(bitmap: ImageBitmap?) {
    Surface(
        modifier = Modifier.size(44.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun statusColor(status: RowStatus): Color = when (status) {
    RowStatus.UNMATCHED -> MaterialTheme.colorScheme.error
    RowStatus.MATCHED -> MaterialTheme.colorScheme.primary
    RowStatus.MANUAL -> MaterialTheme.colorScheme.tertiary
    RowStatus.SKIPPED -> MaterialTheme.colorScheme.outline
}

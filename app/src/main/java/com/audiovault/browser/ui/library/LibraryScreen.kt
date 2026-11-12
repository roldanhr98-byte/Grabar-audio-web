package com.audiovault.browser.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiovault.browser.data.db.AudioItemEntity
import com.audiovault.browser.data.db.CaptureStatus
import com.audiovault.browser.player.PlayerUi
import com.audiovault.browser.player.WaveformCache
import com.audiovault.browser.player.WaveformExtractor
import com.audiovault.browser.ui.components.Badge
import com.audiovault.browser.ui.components.Fmt
import com.audiovault.browser.ui.components.Waveform
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.components.originColor
import com.audiovault.browser.ui.components.statusColor
import com.audiovault.browser.ui.theme.AvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Filter(val label: String) { ALL("Todos"), ORIGINAL("Originales"), CAPTURED("Capturados"), INCOMPLETE("Incompletos") }

/** D. MI BIBLIOTECA */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(showMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val items by c.library.all.collectAsStateWithLifecycle(emptyList())
    val total by c.library.totalSize.collectAsStateWithLifecycle(0L)
    val player by c.player.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var site by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val selected = remember { mutableStateMapOf<Long, Boolean>() }
    val selecting = selected.isNotEmpty()
    var renameItem by remember { mutableStateOf<AudioItemEntity?>(null) }
    var infoItem by remember { mutableStateOf<AudioItemEntity?>(null) }
    var deleteItems by remember { mutableStateOf<List<AudioItemEntity>?>(null) }
    var exportItem by remember { mutableStateOf<AudioItemEntity?>(null) }
    var zipItems by remember { mutableStateOf<List<AudioItemEntity>>(emptyList()) }

    BackHandler(enabled = selecting) { selected.clear() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        val it = exportItem ?: return@rememberLauncherForActivityResult
        exportItem = null
        if (uri != null) scope.launch { showMessage(if (c.export.exportTo(it, uri)) "Exportado" else "No se pudo exportar") }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val list = zipItems
        zipItems = emptyList()
        if (uri != null) scope.launch {
            c.export.exportZip(list, uri).onSuccess { showMessage("ZIP creado con $it audio(s)") }.onFailure { showMessage("Error al crear ZIP: ${it.message}") }
            selected.clear()
        }
    }

    val sites = items.map { it.siteLabel }.distinct().sorted()
    val visible = items.filter { i ->
        (query.isBlank() || listOf(i.displayName, i.fileName, i.siteLabel, i.sourcePageUrl ?: "", i.sourcePageTitle ?: "").any { it.contains(query, true) }) &&
            (site == null || i.siteLabel == site) &&
            when (filter) {
                Filter.ALL -> true
                Filter.ORIGINAL -> !i.origin.isCapture
                Filter.CAPTURED -> i.origin.isCapture
                Filter.INCOMPLETE -> i.captureStatus != CaptureStatus.COMPLETE
            }
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, "Cancelar") }
                    Text("${selected.size} seleccionados", modifier = Modifier.weight(1f))
                    IconButton(onClick = { visible.forEach { selected[it.id] = true } }) { Icon(Icons.Default.SelectAll, "Todos") }
                    IconButton(onClick = {
                        val list = items.filter { selected.containsKey(it.id) }
                        ctx.startActivity(c.export.shareIntent(list))
                    }) { Icon(Icons.Default.Share, "Compartir") }
                    IconButton(onClick = {
                        zipItems = items.filter { selected.containsKey(it.id) }
                        zipLauncher.launch("AudioVault_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".zip")
                    }) { Icon(Icons.Default.Archive, "Exportar ZIP") }
                    IconButton(onClick = { deleteItems = items.filter { selected.containsKey(it.id) } }) { Icon(Icons.Default.Delete, "Eliminar") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                Text("MI BIBLIOTECA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${items.size} audios · ${Fmt.size(total)} · almacenamiento local", color = AvColors.Muted, fontSize = 13.sp)
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    placeholder = { Text("Buscar por nombre, sitio o página") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(24.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
                }
                if (sites.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sites.forEach { s -> FilterChip(selected = site == s, onClick = { site = if (site == s) null else s }, label = { Text(s) }) }
                }
            }
            if (visible.isEmpty()) item {
                Text(if (items.isEmpty()) "La biblioteca está vacía." else "Ningún audio coincide con el filtro.", color = AvColors.Muted, modifier = Modifier.padding(top = 24.dp))
            }
            items(visible, key = { it.id }) { item ->
                val isSel = selected.containsKey(item.id)
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (isSel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                        .combinedClickable(
                            onClick = {
                                if (selecting) { if (isSel) selected.remove(item.id) else selected[item.id] = true }
                                else expanded = if (expanded == item.id) null else item.id
                            },
                            onLongClick = { selected[item.id] = true },
                        ),
                    colors = CardDefaults.cardColors(containerColor = if (expanded == item.id) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) Checkbox(checked = isSel, onCheckedChange = { if (it) selected[item.id] = true else selected.remove(item.id) })
                            else FilledIconButton(onClick = { c.player.play(item) }) {
                                Icon(if (player.itemId == item.id && player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${item.folder}/${item.fileName}", fontSize = 12.sp, color = AvColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${Fmt.date(item.createdAt)} · ${Fmt.duration(item.durationMs)} · ${Fmt.size(item.sizeBytes)} · ${item.format.uppercase()}", fontSize = 12.sp)
                            }
                        }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Badge(item.origin.label, originColor(item.origin))
                            Badge(item.captureStatus.label, statusColor(item.captureStatus))
                        }
                        item.sourcePageUrl?.let { Text(it, fontSize = 11.sp, color = AvColors.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                        if (expanded == item.id && !selecting) {
                            PlayerPanel(item, player)
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                IconButton(onClick = { renameItem = item }) { Icon(Icons.Default.Edit, "Renombrar") }
                                IconButton(onClick = { ctx.startActivity(c.export.shareIntent(listOf(item))) }) { Icon(Icons.Default.Share, "Compartir") }
                                IconButton(onClick = { exportItem = item; exportLauncher.launch(item.fileName) }) { Icon(Icons.Default.Save, "Exportar") }
                                IconButton(onClick = { infoItem = item }) { Icon(Icons.Default.Info, "Información") }
                                IconButton(onClick = { deleteItems = listOf(item) }) { Icon(Icons.Default.Delete, "Eliminar", tint = AvColors.Red) }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        if (player.itemId != null && expanded != player.itemId) MiniPlayer(player, onToggle = { c.player.toggle() }, onClose = { c.player.stop() })
    }

    renameItem?.let { it -> RenameDialog(it, onDismiss = { renameItem = null }) { name, file ->
        renameItem = null
        scope.launch { c.library.rename(it, name, file); showMessage("Renombrado") }
    } }
    infoItem?.let { InfoDialog(it) { infoItem = null } }
    deleteItems?.let { list ->
        AlertDialog(
            onDismissRequest = { deleteItems = null },
            title = { Text("Eliminar ${list.size} audio(s)") },
            text = { Text("Se borrarán definitivamente del dispositivo. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteItems = null
                    scope.launch {
                        list.forEach { c.player.onItemDeleted(it.id); c.library.delete(it) }
                        selected.clear()
                        showMessage("Eliminado")
                    }
                }) { Text("Eliminar", color = AvColors.Red) }
            },
            dismissButton = { TextButton(onClick = { deleteItems = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun PlayerPanel(item: AudioItemEntity, player: PlayerUi) {
    val ctx = LocalContext.current
    val c = ctx.container
    var peaks by remember(item.id) { mutableStateOf<FloatArray?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        peaks = withContext(Dispatchers.IO) {
            WaveformCache.load(ctx, item.id) ?: WaveformExtractor.extract(File(item.filePath))?.also { WaveformCache.save(ctx, item.id, it) }
        }
    }
    val active = player.itemId == item.id
    val dur = if (active && player.durationMs > 0) player.durationMs else item.durationMs
    val progress = if (active && dur > 0) (player.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(top = 10.dp)) {
        Waveform(peaks, progress, onSeek = { f ->
            if (!active) c.player.play(item)
            c.player.seekTo((f * dur).toLong())
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.duration(if (active) player.positionMs else 0).let { if (it == "—") "0:00" else it }, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (active) c.player.seekBy(-10_000) }) { Icon(Icons.Default.Replay10, "Retroceder") }
            FilledIconButton(onClick = { c.player.play(item) }) { Icon(if (active && player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
            IconButton(onClick = { if (active) c.player.seekBy(10_000) }) { Icon(Icons.Default.Forward10, "Adelantar") }
            Column {
                TextButton(onClick = { speedMenu = true }) { Icon(Icons.Default.Speed, null); Text(" ${player.speed}x") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                        DropdownMenuItem(text = { Text("${s}x") }, onClick = { c.player.setSpeed(s); speedMenu = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(Fmt.duration(dur), fontSize = 12.sp)
        }
        player.error?.takeIf { active }?.let { Text(it, color = AvColors.Red, fontSize = 12.sp) }
    }
}

@Composable
private fun MiniPlayer(p: PlayerUi, onToggle: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) { Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
            Text(p.title ?: "", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Fmt.duration(p.positionMs)} / ${Fmt.duration(p.durationMs)}", fontSize = 12.sp)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) }
        }
    }
}

@Composable
private fun RenameDialog(item: AudioItemEntity, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(item.displayName) }
    var file by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renombrar") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(file, { file = it })
                    Text("Renombrar también el archivo (${item.fileName})", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, file) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun InfoDialog(i: AudioItemEntity, onDismiss: () -> Unit) {
    val rows = listOf(
        "Nombre" to i.displayName, "Archivo" to i.filePath, "Formato" to "${i.format.uppercase()} (${i.mimeType})",
        "Duración" to Fmt.duration(i.durationMs), "Tamaño" to "${Fmt.size(i.sizeBytes)} (${i.sizeBytes} bytes)",
        "Frecuencia" to (i.sampleRate?.let { "$it Hz" } ?: "—"), "Canales" to (i.channels?.toString() ?: "—"),
        "Tasa de bits" to (i.bitrate?.let { "${it / 1000} kbps" } ?: "—"),
        "Origen" to i.origin.label, "Estado" to i.captureStatus.label, "Sitio" to i.siteLabel,
        "Página" to (i.sourcePageUrl ?: "—"), "Título de página" to (i.sourcePageTitle ?: "—"),
        "URL del medio" to (i.mediaUrl ?: "—"), "Fecha" to Fmt.date(i.createdAt), "SHA-256" to (i.sha256 ?: "—"),
        "Notas" to (i.notes ?: "—"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null); Text("  Información técnica") } },
        text = {
            LazyColumn {
                items(rows) { (k, v) ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text(k, fontSize = 11.sp, color = AvColors.Muted)
                        Text(v, fontSize = 13.sp, fontFamily = if (k == "SHA-256" || k == "Archivo") FontFamily.Monospace else FontFamily.Default)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

package com.audiovault.browser.ui.misc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.audiovault.browser.AudioVaultApp
import com.audiovault.browser.browser.BrowserTab
import com.audiovault.browser.data.db.DownloadState
import com.audiovault.browser.ui.Routes
import com.audiovault.browser.ui.components.Badge
import com.audiovault.browser.ui.components.Fmt
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.go
import com.audiovault.browser.ui.home.openUrl
import com.audiovault.browser.ui.theme.AvColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, nav: NavHostController, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            actions = { actions() },
        )
        content()
    }
}

@Composable
fun TabsScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val tabs by c.tabs.tabs.collectAsStateWithLifecycle()
    val cur by c.tabs.currentId.collectAsStateWithLifecycle()
    SubScreen("Pestañas (${tabs.size})", nav, actions = {
        IconButton(onClick = { c.tabs.newTab(null); nav.go(Routes.BROWSER) }) { Icon(Icons.Default.Add, "Nueva") }
        IconButton(onClick = { c.tabs.newTab(null, private = true); nav.go(Routes.BROWSER) }) { Icon(Icons.Default.VisibilityOff, "Privada") }
    }) {
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tabs, key = { it.id }) { t -> TabRow(t, t.id == cur, onOpen = { c.tabs.select(t.id); nav.go(Routes.BROWSER) }, onClose = { c.tabs.close(t.id) }) }
            item {
                if (!c.tabs.privateProfilesSupported) Text(
                    "Nota: el WebView de este dispositivo no admite perfiles separados; las pestañas privadas no guardan historial ni caché, " +
                        "pero comparten las cookies con las normales.", fontSize = 12.sp, color = AvColors.Amber,
                )
                TextButton(onClick = { c.tabs.closeAll(); c.tabs.newTab(null) }) { Text("Cerrar todas") }
            }
        }
    }
}

@Composable
private fun TabRow(t: BrowserTab, selected: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    val title by t.title.collectAsStateWithLifecycle()
    val url by t.url.collectAsStateWithLifecycle()
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.isPrivate) Icon(Icons.Default.VisibilityOff, null, tint = AvColors.Blue)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(title.ifBlank { "Nueva pestaña" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = AvColors.Muted)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar") }
        }
    }
}

@Composable
fun HistoryScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val flow = androidx.compose.runtime.remember { c.db.history().observe() }
    val list by flow.collectAsStateWithLifecycle(emptyList())
    SubScreen("Historial", nav, actions = {
        IconButton(onClick = { scope.launch { c.db.history().clear() } }) { Icon(Icons.Default.Delete, "Borrar") }
    }) {
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            if (list.isEmpty()) item { Text("Sin historial.", color = AvColors.Muted) }
            items(list, key = { it.id }) { h ->
                Row(Modifier.fillMaxWidth().clickable { openUrl(ctx.applicationContext as AudioVaultApp, nav, h.url) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Fmt.date(h.visitedAt)} · ${h.url}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = AvColors.Muted)
                    }
                    IconButton(onClick = { scope.launch { c.db.history().delete(h.id) } }) { Icon(Icons.Default.Close, null) }
                }
            }
        }
    }
}

@Composable
fun BookmarksScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val flow = androidx.compose.runtime.remember { c.db.bookmarks().observe() }
    val list by flow.collectAsStateWithLifecycle(emptyList())
    SubScreen("Marcadores", nav) {
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            if (list.isEmpty()) item { Text("Sin marcadores. Usa ☆ en el menú del navegador.", color = AvColors.Muted) }
            items(list, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().clickable { openUrl(ctx.applicationContext as AudioVaultApp, nav, b.url) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = AvColors.Muted)
                    }
                    IconButton(onClick = { scope.launch { c.db.bookmarks().delete(b.id) } }) { Icon(Icons.Default.Delete, null) }
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val flow = androidx.compose.runtime.remember { c.db.downloads().observe() }
    val list by flow.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(Unit) { runCatching { c.downloads.refresh() } }
    SubScreen("Descargas", nav, actions = {
        IconButton(onClick = { scope.launch { runCatching { c.downloads.refresh() } } }) { Icon(Icons.Default.Refresh, "Actualizar") }
        IconButton(onClick = { scope.launch { c.db.downloads().clearFinished() } }) { Icon(Icons.Default.Delete, "Limpiar") }
    }) {
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("Los audios detectados se guardan en Mi Biblioteca. Aquí aparecen las demás descargas (Descargas/AudioVault).",
                    fontSize = 12.sp, color = AvColors.Muted)
            }
            items(list, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth().clickable { c.downloads.openIntentFor(d)?.let { runCatching { ctx.startActivity(it) } } }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.fileName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            val (l, col) = when (d.state) {
                                DownloadState.QUEUED -> "En cola" to AvColors.Muted
                                DownloadState.RUNNING -> "Descargando" to AvColors.Amber
                                DownloadState.DONE -> "Completada" to AvColors.Mint
                                DownloadState.FAILED -> "Fallida" to AvColors.Red
                            }
                            Badge(l, col)
                        }
                        Text("${Fmt.date(d.createdAt)} · ${Fmt.size(d.sizeBytes)} · ${d.mimeType ?: ""}", fontSize = 12.sp, color = AvColors.Muted)
                        Text(d.url, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AvColors.Muted)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

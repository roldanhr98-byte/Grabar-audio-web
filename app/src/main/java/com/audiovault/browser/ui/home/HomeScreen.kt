package com.audiovault.browser.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.audiovault.browser.AudioVaultApp
import com.audiovault.browser.capture.EngineState
import com.audiovault.browser.capture.SessionUi
import com.audiovault.browser.data.db.CaptureSource
import com.audiovault.browser.data.db.SessionState
import com.audiovault.browser.ui.Routes
import com.audiovault.browser.ui.components.Badge
import com.audiovault.browser.ui.components.Fmt
import com.audiovault.browser.ui.components.SectionTitle
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.components.originColor
import com.audiovault.browser.ui.components.rememberCaptureStarter
import com.audiovault.browser.ui.go
import com.audiovault.browser.ui.theme.AvColors

data class QuickLink(val label: String, val url: String, val color: Color)

val quickLinks = listOf(
    QuickLink("Claude", "https://claude.ai", Color(0xFFD97757)),
    QuickLink("ChatGPT", "https://chatgpt.com", Color(0xFF10A37F)),
    QuickLink("ElevenLabs", "https://elevenlabs.io/app/speech-synthesis", Color(0xFFB9B9C8)),
    QuickLink("Gemini", "https://gemini.google.com", Color(0xFF7C9CFF)),
    QuickLink("Google", "https://www.google.com", Color(0xFF4285F4)),
    QuickLink("Traductor", "https://translate.google.com", Color(0xFF34A853)),
)

/** Abre una URL en la pestaña actual si está vacía o en una nueva. */
fun openUrl(ctxApp: AudioVaultApp, nav: NavHostController, url: String, private: Boolean = false) {
    val tabs = ctxApp.container.tabs
    val cur = tabs.current()
    if (!private && cur != null && !cur.isPrivate && cur.url.value.isBlank() && cur.pendingUrl == null) tabs.load(cur, url)
    else tabs.newTab(url, private)
    nav.go(Routes.BROWSER)
}

@Composable
fun HomeScreen(nav: NavHostController, showMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as AudioVaultApp
    val c = ctx.container
    val tabs by c.tabs.tabs.collectAsStateWithLifecycle()
    val engine by c.capture.engine.collectAsStateWithLifecycle()
    val session by c.capture.session.collectAsStateWithLifecycle()
    val recentFlow = remember { c.library.recent(6) }
    val recent by recentFlow.collectAsStateWithLifecycle(emptyList())
    val sessions by c.cache.sessions.collectAsStateWithLifecycle(emptyList())
    val bookmarksFlow = remember { c.db.bookmarks().observe() }
    val bookmarks by bookmarksFlow.collectAsStateWithLifecycle(emptyList())
    val player by c.player.state.collectAsState()
    val settings by c.settings.settings.collectAsStateWithLifecycle(com.audiovault.browser.data.settings.AppSettings())
    val starter = rememberCaptureStarter(showMessage)
    var query by remember { mutableStateOf("") }
    val interrupted = sessions.count { it.state == SessionState.INTERRUPTED }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AudioVault", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Badge("BROWSER", MaterialTheme.colorScheme.primary)
            }
            Text("Navega, detecta y conserva los audios de la web. Todo se guarda solo en tu dispositivo.",
                color = AvColors.Muted, fontSize = 13.sp)
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("Buscar en Google o escribir dirección") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (query.isNotBlank()) openUrl(app, nav, query); query = "" }),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav.go(Routes.TABS) }, label = { Text("${tabs.size} pestañas") }, leadingIcon = { Icon(Icons.Default.Tab, null) })
                AssistChip(onClick = { c.tabs.newTab(null); nav.go(Routes.BROWSER) }, label = { Text("Nueva") }, leadingIcon = { Icon(Icons.Default.Add, null) })
                AssistChip(onClick = { c.tabs.newTab(null, private = true); nav.go(Routes.BROWSER) }, label = { Text("Privada") }, leadingIcon = { Icon(Icons.Default.VisibilityOff, null) })
            }
        }
        if (interrupted > 0) item {
            Card(
                Modifier.fillMaxWidth().clickable { nav.go(Routes.CACHE) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2E10)),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Restore, null, tint = AvColors.Amber)
                    Spacer(Modifier.width(12.dp))
                    Text("$interrupted grabación(es) interrumpida(s) en la caché. Toca para recuperarlas.", color = AvColors.Amber)
                }
            }
        }
        item { DetectorCard(engine, session, settings.autoRecord, settings.autoDownload, onStart = { starter.start(null) }, onStop = starter.stop) }
        item { SectionTitle("Accesos rápidos") }
        item {
            val links = quickLinks + bookmarks.take(6).map { QuickLink(it.title.take(14), it.url, AvColors.Blue) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                links.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { l -> QuickTile(l, Modifier.weight(1f)) { openUrl(app, nav, l.url) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Últimos audios", Modifier.weight(1f))
                if (recent.isNotEmpty()) Text("Ver todo", color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { nav.go(Routes.LIBRARY) }.padding(8.dp))
            }
        }
        if (recent.isEmpty()) item {
            Text("Aún no hay audios. Abre Claude, ChatGPT o ElevenLabs, activa la captura y reproduce una lectura.",
                color = AvColors.Muted, fontSize = 13.sp)
        }
        items(recent, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { c.player.play(item) }) {
                        Icon(if (player.itemId == item.id && player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${item.folder}/${item.fileName} · ${Fmt.duration(item.durationMs)} · ${Fmt.size(item.sizeBytes)}",
                            fontSize = 12.sp, color = AvColors.Muted, maxLines = 1)
                    }
                    Badge(if (item.origin.isCapture) "CAPTURADO" else "ORIGINAL", originColor(item.origin))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QuickTile(l: QuickLink, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(l.color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text(l.label.take(1).uppercase(), color = l.color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(l.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DetectorCard(engine: EngineState, session: SessionUi, autoRecord: Boolean, autoDownload: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(AvColors.Mint))
                Spacer(Modifier.width(8.dp))
                Text("Detector de audio activo", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Badge(if (autoDownload) "Descarga auto" else "Descarga manual", if (autoDownload) AvColors.Mint else AvColors.Muted)
            }
            val (label, color) = when (engine) {
                is EngineState.Active -> {
                    val what = if (engine.source == CaptureSource.MICROPHONE) "Micrófono" else "Audio interno"
                    when (session.state) {
                        SessionUi.Kind.RECORDING -> "● Grabando ($what) · ${session.siteLabel ?: ""} · ${Fmt.duration(session.recordedMs)}" to AvColors.Red
                        SessionUi.Kind.PAUSED -> "⏸ Lectura en pausa · ${session.siteLabel ?: ""}" to AvColors.Amber
                        SessionUi.Kind.IDLE -> "$what preparado · ${if (autoRecord) "grabará automáticamente cada lectura" else "grabación automática desactivada"}" to AvColors.Mint
                    }
                }
                EngineState.Starting -> "Iniciando captura…" to AvColors.Amber
                is EngineState.Error -> "Captura no disponible: ${engine.message}" to AvColors.Red
                EngineState.Off -> "Captura desactivada. Actívala para grabar lecturas sin archivo descargable." to AvColors.Muted
            }
            Text(label, color = color, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (engine is EngineState.Active || engine is EngineState.Starting) {
                    OutlinedButton(onClick = onStop) { Text("Detener captura") }
                } else {
                    Button(onClick = onStart) {
                        Icon(Icons.Default.FiberManualRecord, null, tint = AvColors.Red)
                        Spacer(Modifier.width(6.dp))
                        Text("Activar captura")
                    }
                }
            }
        }
    }
}

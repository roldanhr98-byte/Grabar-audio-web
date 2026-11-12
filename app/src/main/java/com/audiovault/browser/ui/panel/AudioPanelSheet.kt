package com.audiovault.browser.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiovault.browser.browser.BrowserTab
import com.audiovault.browser.capture.EngineState
import com.audiovault.browser.capture.SessionStateMachine
import com.audiovault.browser.capture.SessionUi
import com.audiovault.browser.data.db.CaptureSource
import com.audiovault.browser.data.settings.AppSettings
import com.audiovault.browser.detector.AcquireStatus
import com.audiovault.browser.detector.DetectedMedia
import com.audiovault.browser.detector.PlaybackState
import com.audiovault.browser.ui.components.Badge
import com.audiovault.browser.ui.components.CaptureMode
import com.audiovault.browser.ui.components.Fmt
import com.audiovault.browser.ui.components.LevelMeter
import com.audiovault.browser.ui.components.SectionTitle
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.components.rememberCaptureStarter
import com.audiovault.browser.ui.theme.AvColors

/** C. PANEL DE AUDIO: lo detectado en la página, cómo obtenerlo y el estado de la grabación. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPanelSheet(tab: BrowserTab, onDismiss: () -> Unit, showMessage: (String) -> Unit, onOpenLibrary: () -> Unit) {
    val c = LocalContext.current.container
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val all by c.detection.items.collectAsStateWithLifecycle()
    val engine by c.capture.engine.collectAsStateWithLifecycle()
    val session by c.capture.session.collectAsStateWithLifecycle()
    val level by c.capture.levelDb.collectAsStateWithLifecycle()
    val warning by c.capture.warning.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val starter = rememberCaptureStarter(showMessage)
    var allTabs by remember { mutableStateOf(false) }
    val list = if (allTabs) all else all.filter { it.tabId == tab.id }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Panel de audio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(tab.displayTitle, color = AvColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // ------------------------------------------------ captura
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Grabación de la reproducción", fontWeight = FontWeight.SemiBold)
                        val stateText = when (val e = engine) {
                            is EngineState.Active -> {
                                val src = if (e.source == CaptureSource.MICROPHONE) "Micrófono" else "Audio interno (${e.scope.label})"
                                "$src · ${e.sampleRate} Hz · ${if (e.channels == 2) "estéreo" else "mono"}"
                            }
                            EngineState.Starting -> "Iniciando…"
                            is EngineState.Error -> "No disponible: ${e.message}"
                            EngineState.Off -> "Desactivada"
                        }
                        Text(stateText, fontSize = 13.sp, color = if (engine is EngineState.Error) AvColors.Red else AvColors.Muted)
                        if (engine is EngineState.Active) {
                            LevelMeter(level, Modifier.fillMaxWidth())
                            val st = when (session.state) {
                                SessionUi.Kind.RECORDING -> "● Grabando ${session.siteLabel ?: ""} · ${Fmt.duration(session.recordedMs)}"
                                SessionUi.Kind.PAUSED -> "⏸ En pausa (se continuará en el mismo archivo) · ${Fmt.duration(session.recordedMs)}"
                                SessionUi.Kind.IDLE -> if (settings.autoRecord) "Esperando inicio de reproducción…" else "Grabación automática desactivada"
                            }
                            Text(st, fontSize = 13.sp, color = if (session.state == SessionUi.Kind.RECORDING) AvColors.Red else AvColors.Text)
                        }
                        warning?.let { Text("⚠ $it", color = AvColors.Amber, fontSize = 12.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (engine is EngineState.Active) {
                                if (session.state == SessionUi.Kind.IDLE) {
                                    Button(onClick = {
                                        c.capture.manualStart(SessionStateMachine.Context(tabId = tab.id, pageUrl = tab.url.value, pageTitle = tab.title.value))
                                    }) { Text("Grabar manual") }
                                } else {
                                    Button(onClick = { c.capture.manualStop() }) { Text("Guardar ahora") }
                                }
                                OutlinedButton(onClick = starter.stop) { Text("Detener") }
                            } else {
                                Button(onClick = { starter.start(CaptureMode.INTERNAL) }, enabled = settings.internalCaptureEnabled) {
                                    Icon(Icons.Default.FiberManualRecord, null, tint = AvColors.Red); Spacer(Modifier.width(4.dp)); Text("Audio interno")
                                }
                                if (settings.microphoneEnabled) OutlinedButton(onClick = { starter.start(CaptureMode.MICROPHONE) }) {
                                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(4.dp)); Text("Micrófono")
                                }
                            }
                        }
                    }
                }
            }
            // ------------------------------------------------ detectados
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Audio detectado (${list.size})", Modifier.weight(1f))
                    FilterChip(selected = allTabs, onClick = { allTabs = !allTabs }, label = { Text("Todas las pestañas") })
                }
            }
            if (list.isEmpty()) item {
                Text(
                    "Todavía no se ha detectado audio en esta página. Reproduce una lectura: AudioVault detecta " +
                        "<audio>/<video>, Blob, Media Source, HLS/DASH, fetch/XHR y Web Audio.",
                    color = AvColors.Muted, fontSize = 13.sp,
                )
            }
            items(list, key = { it.id }) { m -> DetectedCard(m, onAcquire = { c.detector.acquire(m.id) }, onOpenLibrary = onOpenLibrary) }
            item {
                SectionTitle("Leyenda")
                Text(
                    "ORIGINAL: archivo obtenido tal como lo sirve la página (sin convertir).\n" +
                        "CAPTURADO: grabación de la reproducción (PCM WAV) cuando no hay archivo.\n" +
                        "INCOMPLETO: falta parte del audio (flujo sin terminar, captura cortada).\n" +
                        "NO CAPTURABLE: DRM, voz del sistema o política de captura que lo impide.",
                    fontSize = 12.sp, color = AvColors.Muted,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DetectedCard(m: DetectedMedia, onAcquire: () -> Unit, onOpenLibrary: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.kind.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val (pl, pc) = when (m.playback) {
                    PlaybackState.PLAYING -> "▶ " + m.playback.label to AvColors.Mint
                    PlaybackState.WAITING -> m.playback.label to AvColors.Amber
                    PlaybackState.PAUSED -> m.playback.label to AvColors.Amber
                    PlaybackState.ENDED -> m.playback.label to AvColors.Muted
                    PlaybackState.UNKNOWN -> m.playback.label to AvColors.Muted
                }
                Badge(pl, pc)
            }
            m.shortUrl?.let { Text(it, fontSize = 11.sp, color = AvColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(
                listOfNotNull(
                    m.mime?.substringBefore(';'), m.sizeBytes?.let { Fmt.size(it) }, m.durationSec?.let { Fmt.seconds(it) },
                    if (m.isVideo) "vídeo" else null, if (!m.complete) "flujo sin terminar" else null,
                ).joinToString(" · ").ifBlank { "Sin metadatos todavía" },
                fontSize = 12.sp,
            )
            Text("Método: ${m.kind.method}", fontSize = 12.sp, color = AvColors.Blue)
            m.captureNote?.let { Text(it, fontSize = 12.sp, color = AvColors.Amber) }
            when (val s = m.status) {
                AcquireStatus.Idle -> if (m.kind.hasFile && !m.isProtected) {
                    Button(onClick = onAcquire) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Guardar original") }
                }
                is AcquireStatus.Working -> {
                    Text(s.label + (s.progress?.let { " · ${(it * 100).toInt()} %" } ?: ""), fontSize = 12.sp)
                    if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is AcquireStatus.Saved -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(if (s.duplicate) "YA ESTABA GUARDADO" else "GUARDADO", AvColors.Mint)
                    Spacer(Modifier.width(8.dp))
                    Text(s.label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenLibrary) { Text("Ver") }
                }
                is AcquireStatus.Failed -> {
                    Text("Error: ${s.message}", color = AvColors.Red, fontSize = 12.sp)
                    if (m.kind.hasFile) OutlinedButton(onClick = onAcquire) { Text("Reintentar") }
                }
                is AcquireStatus.NotDownloadable -> Row {
                    Badge("NO DESCARGABLE", AvColors.Red)
                    Spacer(Modifier.width(8.dp))
                    Text(s.reason, fontSize = 12.sp, color = AvColors.Muted)
                }
            }
        }
    }
}

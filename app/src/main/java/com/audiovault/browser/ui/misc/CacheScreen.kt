package com.audiovault.browser.ui.misc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.audiovault.browser.cache.CacheManager
import com.audiovault.browser.data.db.SessionState
import com.audiovault.browser.ui.components.Badge
import com.audiovault.browser.ui.components.Fmt
import com.audiovault.browser.ui.components.SectionTitle
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.theme.AvColors
import kotlinx.coroutines.launch

@Composable
fun CacheScreen(nav: NavHostController, showMessage: (String) -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val sessions by c.cache.sessions.collectAsStateWithLifecycle(emptyList())
    var usage by remember { mutableStateOf<CacheManager.Usage?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val libSize by c.library.totalSize.collectAsStateWithLifecycle(0L)
    LaunchedEffect(tick, sessions.size) { usage = c.cache.usage() }

    SubScreen("Caché y recuperación", nav) {
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionTitle("Espacio utilizado")
                val u = usage
                Text("Capturas en caché: ${Fmt.size(u?.captureBytes)}")
                Text("Temporales de descargas: ${Fmt.size(u?.workBytes)}")
                Text("Caché del sistema/web: ${Fmt.size(u?.webCacheBytes)}")
                Text("Biblioteca (no es caché): ${Fmt.size(libSize)}", color = AvColors.Muted)
                Text("Cada captura se escribe en la caché al instante y la cabecera WAV se actualiza cada 2 s; " +
                    "si la app se cierra, la grabación se repara y se puede recuperar. Vaciar la caché nunca borra la biblioteca.",
                    fontSize = 12.sp, color = AvColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { scope.launch { c.cache.clearCache(false); tick++; showMessage("Temporales eliminados") } }) { Text("Vaciar temporales") }
                    OutlinedButton(onClick = { scope.launch { c.cache.clearCache(true); tick++; showMessage("Caché vaciada") } }) { Text("Vaciar todo (incl. interrumpidas)") }
                }
            }
            item { SectionTitle("Sesiones en caché (${sessions.size})") }
            if (sessions.isEmpty()) item { Text("No hay grabaciones pendientes.", color = AvColors.Muted) }
            items(sessions, key = { it.id }) { s ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(s.siteLabel, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            val (l, col) = when (s.state) {
                                SessionState.RECORDING -> "Grabando" to AvColors.Red
                                SessionState.PAUSED -> "En pausa" to AvColors.Amber
                                SessionState.INTERRUPTED -> "Interrumpida · incompleta" to AvColors.Amber
                                else -> s.state.name to AvColors.Muted
                            }
                            Badge(l, col)
                        }
                        val bps = (s.sampleRate * s.channels * 2).coerceAtLeast(1)
                        Text("${Fmt.date(s.startedAt)} · ${Fmt.duration(s.bytesWritten * 1000 / bps)} · ${Fmt.size(s.bytesWritten)} · ${s.source.name.lowercase()}", fontSize = 12.sp)
                        s.pageUrl?.let { Text(it, fontSize = 11.sp, color = AvColors.Blue, maxLines = 1) }
                        s.endReason?.let { Text(it, fontSize = 12.sp, color = AvColors.Muted) }
                        if (s.state == SessionState.INTERRUPTED) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { if (c.cache.recover(s)) showMessage("Recuperada en la biblioteca") else showMessage("No se pudo recuperar") } }) { Text("Recuperar") }
                            OutlinedButton(onClick = { scope.launch { c.cache.deleteSession(s); showMessage("Eliminada de la caché") } }) { Text("Eliminar") }
                        }
                    }
                }
            }
        }
    }
}

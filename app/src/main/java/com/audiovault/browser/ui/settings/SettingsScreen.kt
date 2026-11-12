package com.audiovault.browser.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.audiovault.browser.BuildConfig
import com.audiovault.browser.data.settings.AppSettings
import com.audiovault.browser.data.settings.CaptureScope
import com.audiovault.browser.data.settings.OutputFormat
import com.audiovault.browser.data.settings.StorageLocation
import com.audiovault.browser.ui.Routes
import com.audiovault.browser.ui.components.SectionTitle
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.theme.AvColors
import kotlinx.coroutines.launch

/** E. CONFIGURACIÓN */
@Composable
fun SettingsScreen(nav: NavHostController, showMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    fun upd(f: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(f) } }
    var permTick by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Configuración", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionTitle("Detector y descargas")
        Toggle("Descarga automática", "Guarda el archivo original cuando una página reproduce audio descargable (no vídeo, no DRM).", s.autoDownload) { v -> upd { it.copy(autoDownload = v) } }
        Choice("Tamaño mínimo de descarga", listOf(1024, 8 * 1024, 32 * 1024, 128 * 1024), s.minDownloadBytes, { "${it / 1024} KB" }) { v -> upd { it.copy(minDownloadBytes = v) } }

        SectionTitle("Grabación automática por sesiones")
        Toggle("Grabación automática", "Con la captura activada, cada reproducción detectada genera su propio archivo.", s.autoRecord) { v -> upd { it.copy(autoRecord = v) } }
        Toggle("Captura de audio interno", "MediaProjection + AudioPlaybackCapture (Android 10+). Requiere autorización del sistema cada vez.", s.internalCaptureEnabled) { v -> upd { it.copy(internalCaptureEnabled = v) } }
        Choice("Alcance de la captura interna", CaptureScope.entries.toList(), s.captureScope, { it.label }) { v -> upd { it.copy(captureScope = v) } }
        Toggle("Micrófono (alternativa)", "Modo independiente, desactivado por defecto. Nunca se mezcla con la captura interna.", s.microphoneEnabled) { v -> upd { it.copy(microphoneEnabled = v) } }
        Toggle("Iniciar por nivel de audio", "Empieza a grabar al oír sonido aunque la página no emita eventos (útil con «otras apps»).", s.levelTriggerEnabled) { v -> upd { it.copy(levelTriggerEnabled = v) } }

        SectionTitle("Calidad y formato")
        Choice("Frecuencia de muestreo", listOf(16000, 22050, 44100, 48000), s.sampleRate, { "$it Hz" }) { v -> upd { it.copy(sampleRate = v) } }
        Choice("Canales", listOf(1, 2), s.channels, { if (it == 1) "Mono" else "Estéreo" }) { v -> upd { it.copy(channels = v) } }
        Choice("Formato de salida de las capturas", OutputFormat.entries.toList(), s.outputFormat, { it.label }) { v -> upd { it.copy(outputFormat = v) } }
        if (s.outputFormat == OutputFormat.M4A) Choice("Tasa de bits AAC", listOf(96_000, 128_000, 192_000, 256_000), s.aacBitrate, { "${it / 1000} kbps" }) { v -> upd { it.copy(aacBitrate = v) } }
        Text("Los archivos originales descargados nunca se convierten.", fontSize = 12.sp, color = AvColors.Muted)

        SectionTitle("Detección de silencio")
        SliderRow("Umbral de silencio", s.silenceThresholdDb, -70f..-30f, "%.0f dBFS") { v -> upd { it.copy(silenceThresholdDb = v) } }
        SliderRow("Fin por silencio (sin reproducción activa)", s.silenceTimeoutSec, 2f..30f, "%.0f s") { v -> upd { it.copy(silenceTimeoutSec = v) } }
        SliderRow("Silencio máximo con la página reproduciendo", s.maxSilenceWhilePlayingSec, 10f..180f, "%.0f s") { v -> upd { it.copy(maxSilenceWhilePlayingSec = v) } }
        SliderRow("Duración mínima de una lectura", s.minSegmentSec, 0.5f..10f, "%.1f s") { v -> upd { it.copy(minSegmentSec = v) } }
        SliderRow("Pausa máxima para continuar en el mismo archivo", s.pauseResumeWindowMin, 1f..60f, "%.0f min") { v -> upd { it.copy(pauseResumeWindowMin = v) } }
        SliderRow("Pre-roll (audio previo al inicio)", s.preRollMs.toFloat(), 0f..3000f, "%.0f ms") { v -> upd { it.copy(preRollMs = v.toInt()) } }
        SliderRow("Cola tras el final", s.endTailMs.toFloat(), 0f..3000f, "%.0f ms") { v -> upd { it.copy(endTailMs = v.toInt()) } }
        Toggle("Recortar silencio final", "Elimina el silencio que queda al final de cada lectura (deja 0,5 s).", s.trimTrailingSilence) { v -> upd { it.copy(trimTrailingSilence = v) } }

        SectionTitle("Almacenamiento")
        Choice("Ubicación de la biblioteca", StorageLocation.entries.toList(), s.storageLocation, { it.label }) { v -> upd { it.copy(storageLocation = v) } }
        Toggle("Copiar también a Música/AudioVault", "Publica una copia en la carpeta pública de música (Android 10+).", s.publishToMusic) { v -> upd { it.copy(publishToMusic = v) } }
        Toggle("Recuperar automáticamente grabaciones interrumpidas", "Al reabrir la app, las sesiones cortadas se reparan y pasan a la biblioteca como «Recuperado».", s.autoRecoverInterrupted) { v -> upd { it.copy(autoRecoverInterrupted = v) } }
        Link("Caché y recuperación") { nav.navigate(Routes.CACHE) }

        SectionTitle("Navegador")
        OutlinedTextField(s.homePage, { v -> upd { it.copy(homePage = v) } }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Página de inicio (vacío = Inicio de AudioVault)") })
        Toggle("Cookies de terceros", "Necesarias para algunos inicios de sesión (Claude, ChatGPT, ElevenLabs).", s.thirdPartyCookies) { v -> upd { it.copy(thirdPartyCookies = v) } }
        Toggle("Permitir reproducción automática", "Las páginas pueden reproducir sin que toques la pantalla.", s.autoplayWithoutGesture) { v -> upd { it.copy(autoplayWithoutGesture = v) } }
        Toggle("Versión de escritorio", null, s.desktopMode) { v -> upd { it.copy(desktopMode = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.tabs.clearAllCookies { showMessage("Cookies eliminadas: se cerraron las sesiones") } }) { Text("Borrar cookies") }
            OutlinedButton(onClick = { c.tabs.clearWebCache(); showMessage("Caché web vaciada") }) { Text("Vaciar caché web") }
        }
        OutlinedButton(onClick = { scope.launch { c.db.history().clear(); showMessage("Historial borrado") } }) { Text("Borrar historial") }
        Link("Historial") { nav.navigate(Routes.HISTORY) }
        Link("Marcadores") { nav.navigate(Routes.BOOKMARKS) }
        Link("Descargas") { nav.navigate(Routes.DOWNLOADS) }

        SectionTitle("Permisos")
        val tick = permTick
        PermissionRow("Grabar audio (captura y micrófono)", Manifest.permission.RECORD_AUDIO, tick)
        if (Build.VERSION.SDK_INT >= 33) PermissionRow("Notificaciones (aviso de grabación)", Manifest.permission.POST_NOTIFICATIONS, tick)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                permLauncher.launch(buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray())
            }) { Text("Solicitar permisos") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Ajustes de Android") }
        }
        Text("La autorización de MediaProjection la concede Android en cada activación; puedes revocarla desde la notificación o los ajustes rápidos.",
            fontSize = 12.sp, color = AvColors.Muted)

        SectionTitle("Diagnóstico")
        Link("Prueba de captura y compatibilidad del dispositivo") { nav.navigate(Routes.DIAGNOSTICS) }

        SectionTitle("Privacidad")
        Text("• AudioVault no envía audios ni datos a servidores externos: no hay servidor.\n" +
            "• Mientras captura muestra una notificación persistente y un indicador rojo.\n" +
            "• No descifra DRM, no intercepta HTTPS y no usa APIs privadas de ningún servicio.",
            fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text("AudioVault Browser ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = AvColors.Muted)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, fontSize = 12.sp, color = AvColors.Muted) }
        }
        Switch(value, onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun <T> Choice(title: String, options: List<T>, value: T, label: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Column {
            Text(label(value), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(label(o)) }, onClick = { onChange(o); open = false }) }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f), fontSize = 14.sp)
            Text(fmt.format(local), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        }
        Slider(value = local, onValueChange = { local = it }, valueRange = range, onValueChangeFinished = { onChange(local) })
    }
}

@Composable
private fun Link(title: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(title, Modifier.fillMaxWidth()) }
}

@Composable
private fun PermissionRow(label: String, perm: String, tick: Int) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { granted = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(1f))
        Text(if (granted) "Concedido" else "No concedido", color = if (granted) AvColors.Mint else AvColors.Amber, fontSize = 13.sp)
    }
}

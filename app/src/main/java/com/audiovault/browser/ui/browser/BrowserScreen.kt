package com.audiovault.browser.ui.browser

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.audiovault.browser.AudioVaultApp
import com.audiovault.browser.browser.BrowserTab
import com.audiovault.browser.capture.EngineState
import com.audiovault.browser.capture.SessionStateMachine
import com.audiovault.browser.capture.SessionUi
import com.audiovault.browser.data.db.BookmarkEntity
import com.audiovault.browser.data.settings.AppSettings
import com.audiovault.browser.detector.PlaybackState
import com.audiovault.browser.ui.Routes
import com.audiovault.browser.ui.components.Fmt
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.components.rememberCaptureStarter
import com.audiovault.browser.ui.go
import com.audiovault.browser.ui.home.QuickLink
import com.audiovault.browser.ui.home.quickLinks
import com.audiovault.browser.ui.panel.AudioPanelSheet
import com.audiovault.browser.ui.theme.AvColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowserScreen(nav: NavHostController, showMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val tabs by c.tabs.tabs.collectAsStateWithLifecycle()
    val currentId by c.tabs.currentId.collectAsStateWithLifecycle()
    val tab = tabs.firstOrNull { it.id == currentId }

    LaunchedEffect(tab == null) { if (tab == null) c.tabs.newTab(null) }
    if (tab == null) return
    TabContent(tab, tabs.size, nav, showMessage)
}

@Composable
private fun TabContent(tab: BrowserTab, tabCount: Int, nav: NavHostController, showMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as AudioVaultApp
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val url by tab.url.collectAsStateWithLifecycle()
    val title by tab.title.collectAsStateWithLifecycle()
    val progress by tab.progress.collectAsStateWithLifecycle()
    val loading by tab.isLoading.collectAsStateWithLifecycle()
    val canBack by tab.canGoBack.collectAsStateWithLifecycle()
    val canFwd by tab.canGoForward.collectAsStateWithLifecycle()
    val secure by tab.isSecure.collectAsStateWithLifecycle()
    val detectorOn by tab.detectorActive.collectAsStateWithLifecycle()
    val engine by c.capture.engine.collectAsStateWithLifecycle()
    val session by c.capture.session.collectAsStateWithLifecycle()
    val detections by c.detection.items.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val tabDetections = detections.filter { it.tabId == tab.id }
    val playingHere = tabDetections.any { it.playback == PlaybackState.PLAYING }
    val starter = rememberCaptureStarter(showMessage)
    val focus = LocalFocusManager.current

    var showPanel by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var address by remember(tab.id) { mutableStateOf(TextFieldValue("")) }
    var bookmarked by remember(url) { mutableStateOf(false) }
    var suggestedCapture by remember(tab.id) { mutableStateOf(false) }

    LaunchedEffect(url) { bookmarked = withContext(Dispatchers.IO) { c.db.bookmarks().find(url) != null } }
    // Sugerencia: se detectó reproducción y la captura está apagada.
    LaunchedEffect(playingHere, engine) {
        if (playingHere && engine !is EngineState.Active && settings.autoRecord && !suggestedCapture) {
            suggestedCapture = true
            showMessage("Audio detectado. Toca ● para activar la captura y guardar esta lectura.")
        }
    }
    BackHandler(enabled = canBack) { tab.webView?.goBack() }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------- barra de direcciones
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when { tab.isPrivate -> Icons.Default.VisibilityOff; secure -> Icons.Default.Lock; else -> Icons.Default.LockOpen },
                    null, tint = if (tab.isPrivate) AvColors.Blue else if (secure) AvColors.Mint else AvColors.Muted,
                    modifier = Modifier.padding(horizontal = 6.dp).size(18.dp),
                )
                TextField(
                    value = if (editing) address else TextFieldValue(if (url == "about:blank") "" else url),
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f).height(52.dp).onFocusChanged { f ->
                        if (f.isFocused && !editing) {
                            val t = if (url == "about:blank") "" else url
                            address = TextFieldValue(t, TextRange(0, t.length))
                        }
                        editing = f.isFocused
                    },
                    placeholder = { Text("Buscar o escribir dirección", fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (address.text.isNotBlank()) c.tabs.load(tab, address.text)
                        focus.clearFocus()
                    }),
                )
                IconButton(onClick = { if (loading) tab.webView?.stopLoading() else tab.webView?.reload() }) {
                    Icon(if (loading) Icons.Default.Close else Icons.Default.Refresh, "Recargar")
                }
                CaptureButton(engine, session) {
                    when {
                        engine !is EngineState.Active -> starter.start(null)
                        session.state == SessionUi.Kind.IDLE -> c.capture.manualStart(
                            SessionStateMachine.Context(tabId = tab.id, pageUrl = url, pageTitle = title)
                        )
                        else -> c.capture.manualStop()
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        if (session.state != SessionUi.Kind.IDLE) RecordingBanner(session, onSave = { c.capture.manualStop() }, onStop = starter.stop)

        // ---------------------------------------------------- contenido
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val blank = (url.isBlank() || url == "about:blank") && tab.pendingUrl == null && tab.webView == null
            if (blank) {
                MiniHome(onOpen = { c.tabs.load(tab, it) })
            } else {
                WebViewHost(tab)
            }
            if (!blank && !detectorOn && !loading) {
                Text("Detector no inyectado aún en esta página", fontSize = 10.sp, color = AvColors.Muted,
                    modifier = Modifier.align(Alignment.TopEnd).background(Color(0xAA000000)).padding(4.dp))
            }
        }

        // ---------------------------------------------------- barra inferior
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { tab.webView?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                IconButton(onClick = { tab.webView?.goForward() }, enabled = canFwd) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Adelante") }
                IconButton(onClick = {
                    val home = settings.homePage
                    if (home.isNotBlank()) c.tabs.load(tab, home) else nav.go(Routes.HOME)
                }) { Icon(Icons.Default.Home, "Inicio") }
                IconButton(onClick = { nav.go(Routes.TABS) }) {
                    Box(Modifier.size(24.dp).background(Color.Transparent), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(5.dp), color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface)) {
                            Text("$tabCount", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 5.dp))
                        }
                    }
                }
                IconButton(onClick = { showPanel = true }) {
                    BadgedBox(badge = {
                        if (tabDetections.isNotEmpty()) androidx.compose.material3.Badge(
                            containerColor = if (playingHere) AvColors.Red else MaterialTheme.colorScheme.primary
                        ) { Text("${tabDetections.size}") }
                    }) { Icon(Icons.Default.GraphicEq, "Panel de audio", tint = if (playingHere) AvColors.Mint else MaterialTheme.colorScheme.onSurface) }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menú") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Nueva pestaña") }, onClick = { menu = false; c.tabs.newTab(null) })
                        DropdownMenuItem(text = { Text("Nueva pestaña privada") }, leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                            onClick = { menu = false; c.tabs.newTab(null, private = true) })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (bookmarked) "Quitar marcador" else "Añadir marcador") },
                            leadingIcon = { Icon(if (bookmarked) Icons.Default.Star else Icons.Default.StarBorder, null) },
                            enabled = url.startsWith("http"),
                            onClick = {
                                menu = false
                                scope.launch(Dispatchers.IO) {
                                    if (bookmarked) c.db.bookmarks().deleteByUrl(url)
                                    else c.db.bookmarks().insert(BookmarkEntity(url = url, title = title.ifBlank { url }, createdAt = System.currentTimeMillis()))
                                    bookmarked = !bookmarked
                                }
                            })
                        DropdownMenuItem(text = { Text("Marcadores") }, leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { menu = false; nav.navigate(Routes.BOOKMARKS) })
                        DropdownMenuItem(text = { Text("Historial") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { menu = false; nav.navigate(Routes.HISTORY) })
                        DropdownMenuItem(text = { Text("Descargas") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menu = false; nav.navigate(Routes.DOWNLOADS) })
                        DropdownMenuItem(text = { Text("Mi Biblioteca") }, leadingIcon = { Icon(Icons.Default.LibraryMusic, null) }, onClick = { menu = false; nav.go(Routes.LIBRARY) })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (settings.desktopMode) "Versión móvil" else "Versión de escritorio") },
                            leadingIcon = { Icon(Icons.Default.DesktopWindows, null) },
                            onClick = {
                                menu = false
                                scope.launch { c.settings.update { it.copy(desktopMode = !it.desktopMode) }; tab.webView?.reload() }
                            })
                        DropdownMenuItem(text = { Text("Compartir página") }, leadingIcon = { Icon(Icons.Default.Share, null) }, enabled = url.startsWith("http"),
                            onClick = {
                                menu = false
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Compartir"))
                            })
                        DropdownMenuItem(text = { Text("Borrar cookies de este sitio") }, leadingIcon = { Icon(Icons.Default.Cookie, null) }, enabled = url.startsWith("http"),
                            onClick = { menu = false; c.tabs.clearCookiesFor(url); showMessage("Cookies del sitio eliminadas (cerrarás sesión en él)") })
                        DropdownMenuItem(text = { Text("Ajustes") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menu = false; nav.go(Routes.SETTINGS) })
                    }
                }
            }
        }
    }

    if (showPanel) AudioPanelSheet(tab = tab, onDismiss = { showPanel = false }, showMessage = showMessage, onOpenLibrary = { showPanel = false; nav.go(Routes.LIBRARY) })
}

@Composable
private fun WebViewHost(tab: BrowserTab) {
    val ctx = LocalContext.current
    val c = ctx.container
    // El WebView vive en TabManager; aquí solo se re-asigna al contenedor visible.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { FrameLayout(it) },
        update = { frame ->
            val wv = c.tabs.webViewFor(tab)
            if (wv.parent !== frame) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                frame.removeAllViews()
                frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
    )
}

@Composable
private fun CaptureButton(engine: EngineState, session: SessionUi, onClick: () -> Unit) {
    val color = when {
        session.state == SessionUi.Kind.RECORDING -> AvColors.Red
        session.state == SessionUi.Kind.PAUSED -> AvColors.Amber
        engine is EngineState.Active -> AvColors.Mint
        else -> AvColors.Muted
    }
    IconButton(onClick = onClick) {
        Icon(if (session.state == SessionUi.Kind.RECORDING) Icons.Default.Stop else Icons.Default.FiberManualRecord, "Captura", tint = color)
    }
}

@Composable
private fun RecordingBanner(session: SessionUi, onSave: () -> Unit, onStop: () -> Unit) {
    val rec = session.state == SessionUi.Kind.RECORDING
    Row(
        Modifier.fillMaxWidth().background(if (rec) Color(0xFF3B1019) else Color(0xFF3A2E10)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.FiberManualRecord, null, tint = if (rec) AvColors.Red else AvColors.Amber, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            (if (rec) "Grabando" else "En pausa") + " · ${session.siteLabel ?: ""} · ${Fmt.duration(session.recordedMs)}" +
                if (session.manual) " · manual" else "",
            fontSize = 13.sp, modifier = Modifier.weight(1f), color = if (rec) AvColors.Red else AvColors.Amber,
        )
        TextButton(onClick = onSave) { Text("Guardar ya") }
        TextButton(onClick = onStop) { Text("Detener") }
    }
}

@Composable
private fun MiniHome(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nueva pestaña", style = MaterialTheme.typography.titleLarge)
        Text("Escribe una dirección arriba o abre un acceso rápido.", color = AvColors.Muted)
        quickLinks.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { l: QuickLink ->
                    TextButton(onClick = { onOpen(l.url) }, modifier = Modifier.weight(1f)) { Text(l.label, color = l.color) }
                }
            }
        }
    }
}

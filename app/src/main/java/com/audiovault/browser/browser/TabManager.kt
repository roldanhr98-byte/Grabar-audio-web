package com.audiovault.browser.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.audiovault.browser.data.db.AppDatabase
import com.audiovault.browser.data.db.HistoryEntity
import com.audiovault.browser.data.settings.AppSettings
import com.audiovault.browser.data.settings.SettingsRepository
import com.audiovault.browser.detector.MediaDetector
import com.audiovault.browser.detector.MediaKind
import com.audiovault.browser.detector.MediaUrlClassifier
import com.audiovault.browser.detector.TabAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.UUID

/** Callbacks que necesitan una Activity (permisos, selector de archivos, pantalla completa). */
interface BrowserUiHost {
    fun onWebPermissionRequest(request: PermissionRequest)
    fun onGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback)
    fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
    fun openExternal(intent: Intent): Boolean
    fun showMessage(msg: String)
    fun onTabOpenedFromPage()
}

/**
 * Gestor de pestañas. Los WebView viven aquí (no en la UI) para que el audio siga
 * sonando y capturándose al cambiar de pestaña o de pantalla.
 */
class TabManager(
    private val app: Context,
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val detector: MediaDetector,
    private val downloads: GeneralDownloads,
    private val scope: CoroutineScope,
) : TabAccess {

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()
    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    @Volatile var host: BrowserUiHost? = null
    private var activity: Activity? = null
    @Volatile private var settings = AppSettings()

    private val detectorScript: String by lazy {
        app.assets.open("audiovault_detector.js").bufferedReader().use { it.readText() }
    }

    val documentStartSupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    val privateProfilesSupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    init {
        scope.launch {
            settingsRepo.settings.collect { s ->
                val changed = s.desktopMode != settings.desktopMode || s.thirdPartyCookies != settings.thirdPartyCookies ||
                    s.autoplayWithoutGesture != settings.autoplayWithoutGesture
                settings = s
                if (changed) withContext(Dispatchers.Main) { _tabs.value.forEach { t -> t.webView?.let { applySettings(it, t) } } }
            }
        }
        detector.tabs = this
    }

    fun current(): BrowserTab? = _tabs.value.firstOrNull { it.id == _currentId.value }

    fun attachActivity(a: Activity) {
        activity = a
        _tabs.value.forEach { it.contextWrapper.baseContext = a }
    }

    fun detachActivity(a: Activity) {
        if (activity === a) {
            activity = null
            _tabs.value.forEach { it.contextWrapper.baseContext = app }
        }
    }

    // ------------------------------------------------------------- pestañas

    fun newTab(url: String? = null, private: Boolean = false, select: Boolean = true): BrowserTab {
        val wrapper = MutableContextWrapper(activity ?: app)
        val tab = BrowserTab(UUID.randomUUID().toString(), private, wrapper)
        tab.pendingUrl = url?.let { normalizeInput(it) }
        _tabs.value = _tabs.value + tab
        if (select) _currentId.value = tab.id
        return tab
    }

    fun select(id: String) {
        if (_tabs.value.any { it.id == id }) _currentId.value = id
    }

    fun close(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        val idx = _tabs.value.indexOf(tab)
        _tabs.value = _tabs.value - tab
        detector.onTabClosed(id)
        tab.webView?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
        tab.webView = null
        if (_currentId.value == id) {
            _currentId.value = _tabs.value.getOrNull((idx - 1).coerceAtLeast(0))?.id ?: _tabs.value.firstOrNull()?.id
        }
        if (tab.isPrivate && _tabs.value.none { it.isPrivate }) clearPrivateData()
    }

    fun closeAll() { _tabs.value.map { it.id }.forEach { close(it) } }

    /** Obtiene (o crea) el WebView de una pestaña. Debe llamarse en el hilo principal. */
    fun webViewFor(tab: BrowserTab): WebView {
        tab.webView?.let { return it }
        val wv = createWebView(tab)
        tab.webView = wv
        tab.pendingUrl?.let { wv.loadUrl(it) }
        tab.pendingUrl = null
        return wv
    }

    fun load(tab: BrowserTab, input: String) {
        val url = normalizeInput(input)
        val wv = tab.webView
        if (wv == null) tab.pendingUrl = url else wv.loadUrl(url)
        tab.url.value = url
    }

    fun normalizeInput(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("data:")) return t
        val looksLikeHost = !t.contains(' ') && (t.contains('.') || t.startsWith("localhost")) && URLUtil.isValidUrl("https://$t")
        return if (looksLikeHost) "https://$t" else "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    // --------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(tab: BrowserTab): WebView {
        val wv = WebView(tab.contextWrapper)
        if (tab.isPrivate && privateProfilesSupported) {
            runCatching {
                ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                WebViewCompat.setProfile(wv, PRIVATE_PROFILE)
            }
        }
        applySettings(wv, tab)
        wv.addJavascriptInterface(JsBridge(tab.id, detector), "AudioVaultNative")
        if (documentStartSupported) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, detectorScript, setOf("*")) }
        }
        wv.webViewClient = Client(tab)
        wv.webChromeClient = Chrome(tab)
        wv.setDownloadListener { url, ua, cd, mime, length -> onDownload(tab, url, ua, cd, mime, length) }
        return wv
    }

    private fun applySettings(wv: WebView, tab: BrowserTab) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = !settings.autoplayWithoutGesture
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = if (tab.isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        val base = WebSettings.getDefaultUserAgent(app)
        s.userAgentString = if (settings.desktopMode) desktopUa(base) else base
        val cm = cookieManagerFor(tab)
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, settings.thirdPartyCookies)
    }

    private fun desktopUa(base: String): String {
        val chrome = Regex("Chrome/[\\d.]+").find(base)?.value ?: "Chrome/124.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun cookieManagerFor(tab: BrowserTab?): CookieManager {
        if (tab?.isPrivate == true && privateProfilesSupported) {
            runCatching { return ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager }
        }
        return CookieManager.getInstance()
    }

    private fun clearPrivateData() {
        if (privateProfilesSupported) {
            runCatching {
                val store = ProfileStore.getInstance()
                store.getProfile(PRIVATE_PROFILE)?.let { p ->
                    p.cookieManager.removeAllCookies(null)
                    p.webStorage.deleteAllData()
                }
                store.deleteProfile(PRIVATE_PROFILE)
            }
        }
    }

    private fun onDownload(tab: BrowserTab, url: String, ua: String?, cd: String?, mime: String?, length: Long) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            detector.downloadPageUrl(tab.id, url, mime)
            host?.showMessage("Guardando el archivo generado por la página en la biblioteca…")
            return
        }
        val kind = MediaUrlClassifier.classify(url, mime)
        if (kind == MediaKind.AUDIO_FILE || kind == MediaKind.HLS || kind == MediaKind.DASH) {
            detector.onNetworkMedia(tab.id, url, mime, length.takeIf { it > 0 }, kind)
            detector.acquire("url:$url")
            host?.showMessage("Descargando audio a Mi Biblioteca…")
            return
        }
        scope.launch {
            runCatching {
                val name = downloads.enqueue(url, ua, cd, mime, cookieManagerFor(tab).getCookie(url), tab.url.value)
                host?.showMessage("Descargando $name en Descargas/AudioVault")
            }.onFailure { host?.showMessage("No se pudo descargar: ${it.message}") }
        }
    }

    private inner class Client(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
            val intent = runCatching {
                if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
            }.getOrNull() ?: return true
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            if (host?.openExternal(intent) != true && scheme == "intent") {
                intent.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url.value = url
            tab.isLoading.value = true
            tab.isSecure.value = url.startsWith("https://")
            tab.detectorActive.value = false
            tab.favicon.value = favicon
            if (!documentStartSupported) view.evaluateJavascript(detectorScript, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.isLoading.value = false
            tab.canGoBack.value = view.canGoBack()
            tab.canGoForward.value = view.canGoForward()
            if (!documentStartSupported) view.evaluateJavascript(detectorScript, null)
            view.evaluateJavascript("window.__audiovault && window.__audiovault.scan()", null)
            cookieManagerFor(tab).flush()
            if (!tab.isPrivate && url.startsWith("http")) {
                val title = view.title ?: url
                scope.launch(Dispatchers.IO) {
                    val last = db.history().last()
                    if (last?.url == url && System.currentTimeMillis() - last.visitedAt < 30 * 60_000) db.history().updateTitle(last.id, title)
                    else db.history().insert(HistoryEntity(url = url, title = title, visitedAt = System.currentTimeMillis()))
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url.value = url
            tab.canGoBack.value = view.canGoBack()
            tab.canGoForward.value = view.canGoForward()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Solo observación: nunca se modifica ni se intercepta el tráfico.
            val url = request.url.toString()
            if (url.startsWith("http")) {
                MediaUrlClassifier.classify(url)?.let { kind ->
                    val isMediaLoad = kind != MediaKind.VIDEO_FILE || request.requestHeaders.keys.any { it.equals("Range", true) }
                    if (isMediaLoad) detector.onNetworkMedia(tab.id, url, null, null, kind)
                }
            }
            return null
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // El renderizador murió: se descarta el WebView y se recrea al volver a mostrarse.
            val url = tab.url.value
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
            tab.webView = null
            tab.pendingUrl = url
            host?.showMessage("La página se cerró inesperadamente; se recargará.")
            return true
        }
    }

    private inner class Chrome(private val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress.value = newProgress }
        override fun onReceivedTitle(view: WebView, title: String?) { tab.title.value = title ?: "" }
        override fun onReceivedIcon(view: WebView, icon: Bitmap?) { tab.favicon.value = icon }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Ventanas emergentes (p. ej. inicios de sesión OAuth) → nueva pestaña.
            val newTab = newTab(null, tab.isPrivate, select = true)
            val wv = webViewFor(newTab)
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = wv
            resultMsg.sendToTarget()
            host?.onTabOpenedFromPage()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            _tabs.value.firstOrNull { it.webView === window }?.let { close(it.id) }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val h = host
            if (h == null) request.deny() else h.onWebPermissionRequest(request)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val h = host
            if (h == null) callback.invoke(origin, false, false) else h.onGeolocationRequest(origin, callback)
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean =
            host?.onFileChooser(filePathCallback, fileChooserParams) ?: false

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            host?.onShowCustomView(view, callback) ?: callback.onCustomViewHidden()
        }

        override fun onHideCustomView() { host?.onHideCustomView() }
    }

    // ------------------------------------------------------------- TabAccess

    private fun tab(id: String?) = _tabs.value.firstOrNull { it.id == id }

    override fun evaluateJs(tabId: String, script: String) {
        val wv = tab(tabId)?.webView ?: return
        wv.post { wv.evaluateJavascript(script, null) }
    }

    override fun cookiesFor(tabId: String?, url: String): String? = runCatching { cookieManagerFor(tab(tabId)).getCookie(url) }.getOrNull()

    override fun userAgent(tabId: String?): String? =
        if (settings.desktopMode) desktopUa(WebSettings.getDefaultUserAgent(app)) else WebSettings.getDefaultUserAgent(app)

    override fun pageInfo(tabId: String): Pair<String?, String?> {
        val t = tab(tabId) ?: return null to null
        return t.url.value.ifBlank { null } to t.title.value.ifBlank { null }
    }

    override fun onDetectorHello(tabId: String) {
        tab(tabId)?.detectorActive?.value = true
    }

    // ------------------------------------------------------------- cookies

    fun clearAllCookies(done: () -> Unit) {
        CookieManager.getInstance().removeAllCookies { done() }
        CookieManager.getInstance().flush()
    }

    /** Borra las cookies visibles para un sitio (las HttpOnly incluidas, por nombre). */
    fun clearCookiesFor(url: String) {
        val cm = CookieManager.getInstance()
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        val raw = cm.getCookie(url) ?: return
        val names = raw.split(";").map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        val domains = listOf(host, ".$host") + host.split('.').let { p -> if (p.size > 2) listOf("." + p.takeLast(2).joinToString(".")) else emptyList() }
        for (n in names) for (d in domains) {
            cm.setCookie("https://$host", "$n=; Max-Age=0; Path=/; Domain=$d")
            cm.setCookie("https://$host", "$n=; Max-Age=0; Path=/")
        }
        cm.flush()
    }

    fun cookieCountFor(url: String): Int =
        CookieManager.getInstance().getCookie(url)?.split(";")?.count { it.isNotBlank() } ?: 0

    fun clearWebCache() {
        _tabs.value.firstOrNull { it.webView != null }?.webView?.clearCache(true)
            ?: WebView(app).apply { clearCache(true); destroy() }
    }

    companion object {
        const val PRIVATE_PROFILE = "audiovault-private"
    }
}

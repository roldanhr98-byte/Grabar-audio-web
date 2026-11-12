package com.audiovault.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.audiovault.browser.browser.BrowserUiHost
import com.audiovault.browser.ui.AppRoot
import com.audiovault.browser.ui.theme.AudioVaultTheme

/** Solicitud de permiso web pendiente de que el usuario decida. */
data class WebPermissionAsk(
    val origin: String,
    val resources: List<String>,
    val onDecision: (Boolean) -> Unit,
)

class MainActivity : ComponentActivity(), BrowserUiHost {

    private val container get() = (application as AudioVaultApp).container

    val customView = mutableStateOf<View?>(null)
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    val webPermissionAsk = mutableStateOf<WebPermissionAsk?>(null)
    val openBrowserRequest = mutableStateOf(0)

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    private var runtimePermCallback: ((Boolean) -> Unit)? = null
    private val runtimePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { m ->
        runtimePermCallback?.invoke(m.values.all { it })
        runtimePermCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        container.tabs.attachActivity(this)
        container.tabs.host = this
        if (container.tabs.tabs.value.isEmpty()) container.tabs.newTab(null)
        handleIntent(intent)
        setContent {
            AudioVaultTheme {
                AppRoot(activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
            else -> null
        } ?: return
        container.tabs.newTab(url)
        openBrowserRequest.value++
    }

    override fun onPause() {
        super.onPause()
        android.webkit.CookieManager.getInstance().flush()
        container.capture.syncNow()
    }

    override fun onDestroy() {
        container.tabs.detachActivity(this)
        if (container.tabs.host === this) container.tabs.host = null
        super.onDestroy()
    }

    // ---------------------------------------------------------- BrowserUiHost

    override fun onWebPermissionRequest(request: PermissionRequest) {
        runOnUiThread {
            val res = request.resources.toList()
            val labels = res.map {
                when (it) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "micrófono"
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "cámara"
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "contenido protegido (DRM)"
                    PermissionRequest.RESOURCE_MIDI_SYSEX -> "MIDI"
                    else -> it
                }
            }
            webPermissionAsk.value = WebPermissionAsk(request.origin.toString(), labels) { allow ->
                webPermissionAsk.value = null
                if (!allow) { request.deny(); return@WebPermissionAsk }
                val android = buildList {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in res) add(Manifest.permission.RECORD_AUDIO)
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in res) add(Manifest.permission.CAMERA)
                }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                if (android.isEmpty()) request.grant(request.resources)
                else {
                    runtimePermCallback = { ok -> if (ok) request.grant(request.resources) else request.deny() }
                    runtimePermLauncher.launch(android.toTypedArray())
                }
            }
        }
    }

    override fun onGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback) {
        runOnUiThread {
            webPermissionAsk.value = WebPermissionAsk(origin, listOf("ubicación")) { allow ->
                webPermissionAsk.value = null
                if (!allow) { callback.invoke(origin, false, false); return@WebPermissionAsk }
                runtimePermCallback = { ok -> callback.invoke(origin, ok, false) }
                runtimePermLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
    }

    override fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            fileLauncher.launch(params.createIntent())
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            false
        }
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        customViewCallback?.onCustomViewHidden()
        customView.value = view
        customViewCallback = callback
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onHideCustomView() {
        customView.value = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    fun exitFullscreen() = onHideCustomView()

    override fun openExternal(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    override fun showMessage(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onTabOpenedFromPage() {
        openBrowserRequest.value++
    }
}

package com.audiovault.browser.browser

import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow

class BrowserTab(
    val id: String,
    val isPrivate: Boolean,
    val contextWrapper: MutableContextWrapper,
) {
    var webView: WebView? = null
    /** URL a cargar cuando se cree el WebView. */
    var pendingUrl: String? = null

    val url = MutableStateFlow("")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(100)
    val isLoading = MutableStateFlow(false)
    val canGoBack = MutableStateFlow(false)
    val canGoForward = MutableStateFlow(false)
    val detectorActive = MutableStateFlow(false)
    val isSecure = MutableStateFlow(false)
    val favicon = MutableStateFlow<Bitmap?>(null)
    val createdAt = System.currentTimeMillis()

    val displayTitle: String get() = title.value.ifBlank { url.value.ifBlank { "Nueva pestaña" } }
}

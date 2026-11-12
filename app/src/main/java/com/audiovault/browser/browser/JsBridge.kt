package com.audiovault.browser.browser

import android.webkit.JavascriptInterface
import com.audiovault.browser.detector.MediaDetector

/**
 * Puente expuesto al JavaScript del detector como `window.AudioVaultNative`.
 * Solo recibe datos: no expone ninguna capacidad nativa a la página.
 * Los trozos de transferencia solo se aceptan para identificadores que la app solicitó.
 */
class JsBridge(private val tabId: String, private val detector: MediaDetector) {
    @JavascriptInterface
    fun post(json: String) {
        if (json.length > 64 * 1024) return
        detector.onJsMessage(tabId, json)
    }

    @JavascriptInterface
    fun chunk(xid: String, seq: Int, b64: String) {
        detector.onJsChunk(xid, seq, b64)
    }
}

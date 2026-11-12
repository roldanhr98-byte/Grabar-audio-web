package com.audiovault.browser

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.audiovault.browser.capture.CaptureService
import com.audiovault.browser.core.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AudioVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Sesiones interrumpidas pendientes de revisar tras el último arranque. */
    val pendingRecoveries = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CaptureService.ensureChannel(this)
        CaptureService.allowOwnCapture(this)
        container.scope.launch {
            pendingRecoveries.value = runCatching { container.cache.recoverAfterRestart() }.getOrDefault(0)
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                container.scope.launch { runCatching { container.downloads.refresh() } }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }
}

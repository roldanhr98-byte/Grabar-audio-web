package com.audiovault.browser.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.audiovault.browser.AudioVaultApp
import com.audiovault.browser.capture.CaptureService
import com.audiovault.browser.core.AppContainer
import kotlinx.coroutines.launch

enum class CaptureMode { INTERNAL, MICROPHONE }

class CaptureStarter(
    val start: (CaptureMode?) -> Unit,
    val stop: () -> Unit,
)

val Context.container: AppContainer get() = (applicationContext as AudioVaultApp).container

fun projectionIntent(ctx: Context): Intent {
    val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
    // Android 14+: pedir pantalla completa para que la captura de audio no quede limitada a una app.
    return if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    else mpm.createScreenCaptureIntent()
}

/**
 * Flujo de autorización: permiso RECORD_AUDIO (+ notificaciones) → diálogo del sistema de
 * MediaProjection → servicio en primer plano. Nada se graba sin estas autorizaciones.
 */
@Composable
fun rememberCaptureStarter(onMessage: (String) -> Unit): CaptureStarter {
    val ctx = LocalContext.current
    val container = ctx.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<CaptureMode?>(null) }

    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        scope.launch {
            val s = container.settings.current()
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                CaptureService.startInternal(ctx, res.resultCode, data, s.captureScope, s.sampleRate, s.channels)
            } else {
                container.capture.engineFailed("Autorización de captura cancelada por el usuario.")
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mode = pending ?: return@rememberLauncherForActivityResult
        if (granted[Manifest.permission.RECORD_AUDIO] != true) {
            onMessage("Sin el permiso de audio Android no permite capturar ni grabar.")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val s = container.settings.current()
            when (mode) {
                CaptureMode.INTERNAL -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        onMessage("La captura de audio interno necesita Android 10+. Activa el modo micrófono en Ajustes.")
                    } else projLauncher.launch(projectionIntent(ctx))
                }
                CaptureMode.MICROPHONE -> CaptureService.startMic(ctx, s.sampleRate, 1)
            }
        }
    }

    return remember(permLauncher, projLauncher) {
        CaptureStarter(
            start = { requested ->
                scope.launch {
                    val s = container.settings.current()
                    val mode = requested ?: when {
                        s.internalCaptureEnabled -> CaptureMode.INTERNAL
                        s.microphoneEnabled -> CaptureMode.MICROPHONE
                        else -> null
                    }
                    if (mode == null) {
                        onMessage("Activa la captura interna o el micrófono en Ajustes.")
                        return@launch
                    }
                    if (mode == CaptureMode.MICROPHONE && !s.microphoneEnabled) {
                        onMessage("El modo micrófono está desactivado. Actívalo en Ajustes.")
                        return@launch
                    }
                    pending = mode
                    val perms = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permLauncher.launch(perms.toTypedArray())
                }
            },
            stop = { CaptureService.send(ctx, CaptureService.ACTION_STOP) },
        )
    }
}

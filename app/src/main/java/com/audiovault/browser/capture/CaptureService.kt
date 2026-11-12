package com.audiovault.browser.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.audiovault.browser.AudioVaultApp
import com.audiovault.browser.MainActivity
import com.audiovault.browser.R
import com.audiovault.browser.data.db.CaptureSource
import com.audiovault.browser.data.settings.CaptureScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano obligatorio para MediaProjection / micrófono.
 * Mientras está activo muestra una notificación persistente: AudioVault nunca graba a escondidas.
 */
class CaptureService : LifecycleService() {

    private val container get() = (application as AudioVaultApp).container
    private var projection: MediaProjection? = null
    private var engine: CaptureEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scope: CaptureScope = CaptureScope.APP_ONLY
    private var stopping = false

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val controller = container.capture
        when (intent?.action) {
            ACTION_START_INTERNAL -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                startInternal(code, data, intent)
            }
            ACTION_START_MIC -> startMic(intent)
            ACTION_STOP -> stopAll(null)
            ACTION_SAVE_NOW -> controller.manualStop()
            ACTION_MANUAL_START -> controller.manualStart(SessionStateMachine.Context())
        }
        return START_NOT_STICKY
    }

    private fun goForeground(type: Int) {
        ensureChannel(this)
        val n = buildNotification("Preparando captura…", null, recording = false)
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (t: Throwable) {
            container.capture.engineFailed("Android no permitió iniciar el servicio de captura: ${t.message}")
            stopSelf()
            throw t
        }
    }

    private fun startInternal(resultCode: Int, data: Intent?, intent: Intent) {
        val controller = container.capture
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            controller.engineFailed("La captura de audio interno requiere Android 10 o superior. Usa el modo micrófono.")
            stopSelf(); return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            controller.engineFailed("Permiso de captura denegado por el usuario.")
            stopSelf(); return
        }
        stopEngine()
        stopping = false
        controller.engineStarting()
        // Android 14+: startForeground(mediaProjection) ANTES de obtener el MediaProjection.
        runCatching { goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) }.onFailure { return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            controller.engineFailed("No se pudo obtener MediaProjection (el permiso puede haber caducado; vuelve a autorizar).")
            stopAll(null); return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // El usuario o el sistema revocó la proyección.
                Handler(Looper.getMainLooper()).post { stopAll("La autorización de captura fue revocada.") }
            }
        }, Handler(Looper.getMainLooper()))
        projection = proj
        scope = intent.getStringExtra(EXTRA_SCOPE)?.let { runCatching { CaptureScope.valueOf(it) }.getOrNull() } ?: CaptureScope.APP_ONLY
        val sr = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
        val ch = intent.getIntExtra(EXTRA_CHANNELS, 2)
        val result = CaptureEngine.playback(proj, scope, sr, ch, controller::onPcm) { err ->
            Handler(Looper.getMainLooper()).post { stopAll(err) }
        }
        result.onSuccess { e ->
            engine = e
            e.start()
            controller.engineStarted(CaptureSource.INTERNAL, scope, sr, ch)
            acquireWakeLock()
            observeForNotification()
        }.onFailure {
            stopAll(it.message ?: "No se pudo iniciar la captura interna")
        }
    }

    private fun startMic(intent: Intent) {
        val controller = container.capture
        stopEngine()
        stopping = false
        controller.engineStarting()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching { goForeground(type) }.onFailure { return }
        val sr = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
        val ch = intent.getIntExtra(EXTRA_CHANNELS, 1)
        CaptureEngine.microphone(sr, ch, controller::onPcm) { err ->
            Handler(Looper.getMainLooper()).post { stopAll(err) }
        }.onSuccess { e ->
            engine = e
            e.start()
            controller.engineStarted(CaptureSource.MICROPHONE, CaptureScope.APP_ONLY, sr, ch)
            acquireWakeLock()
            observeForNotification()
        }.onFailure { stopAll(it.message ?: "No se pudo abrir el micrófono") }
    }

    private var observing = false
    private fun observeForNotification() {
        if (observing) return
        observing = true
        val c = container.capture
        lifecycleScope.launch {
            combine(c.session, c.engine, c.warning) { s, e, w -> Triple(s, e, w) }.collect { (s, e, w) ->
                if (stopping) return@collect
                val src = (e as? EngineState.Active)?.source
                val what = if (src == CaptureSource.MICROPHONE) "micrófono" else "audio interno"
                val text = when (s.state) {
                    SessionUi.Kind.RECORDING -> "● GRABANDO ($what) · ${s.siteLabel ?: ""} · ${fmt(s.recordedMs)}"
                    SessionUi.Kind.PAUSED -> "⏸ Lectura en pausa · ${s.siteLabel ?: ""} · ${fmt(s.recordedMs)}"
                    SessionUi.Kind.IDLE -> "Captura de $what activa · esperando reproducción"
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(text, w, s.state != SessionUi.Kind.IDLE))
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun buildNotification(text: String, warning: String?, recording: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, CaptureService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(if (recording) "AudioVault está grabando" else "AudioVault: captura preparada")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (warning != null) "$text\n⚠ $warning" else text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Detener captura", action(ACTION_STOP, 1))
        if (recording) b.addAction(0, "Guardar ahora", action(ACTION_SAVE_NOW, 2))
        else b.addAction(0, "Grabar manualmente", action(ACTION_MANUAL_START, 3))
        return b.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioVault:capture").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
    }

    private fun stopAll(error: String?) {
        if (stopping) return
        stopping = true
        stopEngine()
        container.capture.engineStopped(error)
        runCatching { projection?.stop() }
        projection = null
        runCatching { wakeLock?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // El usuario cerró el navegador: si solo se captura el navegador, se cierra la
        // lectura actual de forma segura (queda guardada) y se detiene la captura.
        if (scope == CaptureScope.APP_ONLY || engine?.source == CaptureSource.MICROPHONE) {
            container.capture.finalizeNow(reasonStopped = true)
            stopAll(null)
        } else {
            container.capture.syncNow()
        }
    }

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            stopEngine()
            container.capture.engineStopped(null)
            runCatching { projection?.stop() }
            runCatching { wakeLock?.release() }
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "capture"
        const val NOTIF_ID = 42
        const val ACTION_START_INTERNAL = "com.audiovault.START_INTERNAL"
        const val ACTION_START_MIC = "com.audiovault.START_MIC"
        const val ACTION_STOP = "com.audiovault.STOP"
        const val ACTION_SAVE_NOW = "com.audiovault.SAVE_NOW"
        const val ACTION_MANUAL_START = "com.audiovault.MANUAL_START"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_SAMPLE_RATE = "sr"
        const val EXTRA_CHANNELS = "ch"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Captura de audio", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Aviso persistente mientras AudioVault captura o graba audio"
                    }
                )
            }
        }

        fun startInternal(ctx: Context, resultCode: Int, data: Intent, scope: CaptureScope, sr: Int, ch: Int) {
            val i = Intent(ctx, CaptureService::class.java).setAction(ACTION_START_INTERNAL)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_SCOPE, scope.name).putExtra(EXTRA_SAMPLE_RATE, sr).putExtra(EXTRA_CHANNELS, ch)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun startMic(ctx: Context, sr: Int, ch: Int) {
            val i = Intent(ctx, CaptureService::class.java).setAction(ACTION_START_MIC)
                .putExtra(EXTRA_SAMPLE_RATE, sr).putExtra(EXTRA_CHANNELS, ch)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(action))
        }

        @Suppress("unused")
        fun allowOwnCapture(ctx: Context) {
            // Refuerza que el audio del propio WebView sea capturable (por defecto ya lo es con targetSdk ≥ 29).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    ctx.getSystemService(AudioManager::class.java)
                        .setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                }
            }
        }
    }
}

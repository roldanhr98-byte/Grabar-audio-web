package com.audiovault.browser.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "audiovault_settings")

enum class CaptureScope(val label: String) {
    APP_ONLY("Solo el navegador AudioVault"),
    ALL_APPS("Navegador y otras apps que lo permitan"),
}

enum class OutputFormat(val label: String, val ext: String) {
    WAV("WAV (PCM 16-bit, sin pérdida)", "wav"),
    M4A("M4A (AAC, comprimido)", "m4a"),
}

enum class StorageLocation(val label: String) {
    APP_EXTERNAL("Almacenamiento de la app (Android/data)"),
    APP_INTERNAL("Almacenamiento interno privado"),
}

data class AppSettings(
    val autoDownload: Boolean = true,
    val autoRecord: Boolean = true,
    val internalCaptureEnabled: Boolean = true,
    val captureScope: CaptureScope = CaptureScope.APP_ONLY,
    val microphoneEnabled: Boolean = false,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val outputFormat: OutputFormat = OutputFormat.WAV,
    val aacBitrate: Int = 192_000,
    val silenceThresholdDb: Float = -50f,
    val silenceTimeoutSec: Float = 6f,
    val maxSilenceWhilePlayingSec: Float = 45f,
    val minSegmentSec: Float = 1.5f,
    val pauseResumeWindowMin: Float = 10f,
    val preRollMs: Int = 800,
    val endTailMs: Int = 700,
    val levelTriggerEnabled: Boolean = false,
    val trimTrailingSilence: Boolean = true,
    val storageLocation: StorageLocation = StorageLocation.APP_EXTERNAL,
    val publishToMusic: Boolean = false,
    val autoRecoverInterrupted: Boolean = true,
    val thirdPartyCookies: Boolean = true,
    val homePage: String = "",
    val desktopMode: Boolean = false,
    val autoplayWithoutGesture: Boolean = false,
    val minDownloadBytes: Int = 8 * 1024,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val autoDownload = booleanPreferencesKey("auto_download")
        val autoRecord = booleanPreferencesKey("auto_record")
        val internalCapture = booleanPreferencesKey("internal_capture")
        val captureScope = stringPreferencesKey("capture_scope")
        val microphone = booleanPreferencesKey("microphone")
        val sampleRate = intPreferencesKey("sample_rate")
        val channels = intPreferencesKey("channels")
        val outputFormat = stringPreferencesKey("output_format")
        val aacBitrate = intPreferencesKey("aac_bitrate")
        val silenceDb = floatPreferencesKey("silence_db")
        val silenceTimeout = floatPreferencesKey("silence_timeout")
        val maxSilencePlaying = floatPreferencesKey("max_silence_playing")
        val minSegment = floatPreferencesKey("min_segment")
        val pauseWindow = floatPreferencesKey("pause_window")
        val preRoll = intPreferencesKey("pre_roll")
        val endTail = intPreferencesKey("end_tail")
        val levelTrigger = booleanPreferencesKey("level_trigger")
        val trimTrailing = booleanPreferencesKey("trim_trailing")
        val storage = stringPreferencesKey("storage")
        val publishMusic = booleanPreferencesKey("publish_music")
        val autoRecover = booleanPreferencesKey("auto_recover")
        val thirdParty = booleanPreferencesKey("third_party_cookies")
        val homePage = stringPreferencesKey("home_page")
        val desktop = booleanPreferencesKey("desktop")
        val autoplay = booleanPreferencesKey("autoplay")
        val minDownload = intPreferencesKey("min_download")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            autoDownload = p[K.autoDownload] ?: d.autoDownload,
            autoRecord = p[K.autoRecord] ?: d.autoRecord,
            internalCaptureEnabled = p[K.internalCapture] ?: d.internalCaptureEnabled,
            captureScope = p[K.captureScope]?.let { runCatching { CaptureScope.valueOf(it) }.getOrNull() } ?: d.captureScope,
            microphoneEnabled = p[K.microphone] ?: d.microphoneEnabled,
            sampleRate = p[K.sampleRate] ?: d.sampleRate,
            channels = p[K.channels] ?: d.channels,
            outputFormat = p[K.outputFormat]?.let { runCatching { OutputFormat.valueOf(it) }.getOrNull() } ?: d.outputFormat,
            aacBitrate = p[K.aacBitrate] ?: d.aacBitrate,
            silenceThresholdDb = p[K.silenceDb] ?: d.silenceThresholdDb,
            silenceTimeoutSec = p[K.silenceTimeout] ?: d.silenceTimeoutSec,
            maxSilenceWhilePlayingSec = p[K.maxSilencePlaying] ?: d.maxSilenceWhilePlayingSec,
            minSegmentSec = p[K.minSegment] ?: d.minSegmentSec,
            pauseResumeWindowMin = p[K.pauseWindow] ?: d.pauseResumeWindowMin,
            preRollMs = p[K.preRoll] ?: d.preRollMs,
            endTailMs = p[K.endTail] ?: d.endTailMs,
            levelTriggerEnabled = p[K.levelTrigger] ?: d.levelTriggerEnabled,
            trimTrailingSilence = p[K.trimTrailing] ?: d.trimTrailingSilence,
            storageLocation = p[K.storage]?.let { runCatching { StorageLocation.valueOf(it) }.getOrNull() } ?: d.storageLocation,
            publishToMusic = p[K.publishMusic] ?: d.publishToMusic,
            autoRecoverInterrupted = p[K.autoRecover] ?: d.autoRecoverInterrupted,
            thirdPartyCookies = p[K.thirdParty] ?: d.thirdPartyCookies,
            homePage = p[K.homePage] ?: d.homePage,
            desktopMode = p[K.desktop] ?: d.desktopMode,
            autoplayWithoutGesture = p[K.autoplay] ?: d.autoplayWithoutGesture,
            minDownloadBytes = p[K.minDownload] ?: d.minDownloadBytes,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val cur = current()
        val n = transform(cur)
        context.dataStore.edit { p ->
            p[K.autoDownload] = n.autoDownload
            p[K.autoRecord] = n.autoRecord
            p[K.internalCapture] = n.internalCaptureEnabled
            p[K.captureScope] = n.captureScope.name
            p[K.microphone] = n.microphoneEnabled
            p[K.sampleRate] = n.sampleRate
            p[K.channels] = n.channels
            p[K.outputFormat] = n.outputFormat.name
            p[K.aacBitrate] = n.aacBitrate
            p[K.silenceDb] = n.silenceThresholdDb
            p[K.silenceTimeout] = n.silenceTimeoutSec
            p[K.maxSilencePlaying] = n.maxSilenceWhilePlayingSec
            p[K.minSegment] = n.minSegmentSec
            p[K.pauseWindow] = n.pauseResumeWindowMin
            p[K.preRoll] = n.preRollMs
            p[K.endTail] = n.endTailMs
            p[K.levelTrigger] = n.levelTriggerEnabled
            p[K.trimTrailing] = n.trimTrailingSilence
            p[K.storage] = n.storageLocation.name
            p[K.publishMusic] = n.publishToMusic
            p[K.autoRecover] = n.autoRecoverInterrupted
            p[K.thirdParty] = n.thirdPartyCookies
            p[K.homePage] = n.homePage
            p[K.desktop] = n.desktopMode
            p[K.autoplay] = n.autoplayWithoutGesture
            p[K.minDownload] = n.minDownloadBytes
        }
    }
}

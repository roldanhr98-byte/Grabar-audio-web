package com.audiovault.browser.core

import android.content.Context
import com.audiovault.browser.browser.GeneralDownloads
import com.audiovault.browser.browser.TabManager
import com.audiovault.browser.cache.CacheManager
import com.audiovault.browser.capture.CaptureController
import com.audiovault.browser.data.db.AppDatabase
import com.audiovault.browser.data.settings.SettingsRepository
import com.audiovault.browser.detector.DetectionRepository
import com.audiovault.browser.detector.MediaDetector
import com.audiovault.browser.export.ExportManager
import com.audiovault.browser.library.LibraryRepository
import com.audiovault.browser.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Inyección de dependencias manual (sin frameworks). */
class AppContainer(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.create(app)
    val settings = SettingsRepository(app)
    val library = LibraryRepository(app, db, settings)
    val detection = DetectionRepository()
    val capture = CaptureController(app, db, library, settings, detection, scope)
    val detector = MediaDetector(File(app.filesDir, "work"), detection, library, settings, capture, scope)
    val downloads = GeneralDownloads(app, db)
    val tabs = TabManager(app, db, settings, detector, downloads, scope)
    val cache = CacheManager(app, db, settings, capture)
    val player by lazy { PlayerController(app) }
    val export = ExportManager(app)

    init {
        capture.currentPageProvider = {
            val t = tabs.current()
            com.audiovault.browser.capture.SessionStateMachine.Context(
                tabId = t?.id, pageUrl = t?.url?.value?.takeIf { it.startsWith("http") }, pageTitle = t?.title?.value,
            )
        }
    }
}

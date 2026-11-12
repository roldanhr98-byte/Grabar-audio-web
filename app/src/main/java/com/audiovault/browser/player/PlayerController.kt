package com.audiovault.browser.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.audiovault.browser.data.db.AudioItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerUi(
    val itemId: Long? = null,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

/** Reproductor de la biblioteca basado en Media3 ExoPlayer (hilo principal). */
class PlayerController(private val context: Context) {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    private fun ensure(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (isPlaying) startTicker()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) _state.value = _state.value.copy(durationMs = p.duration.coerceAtLeast(0))
                if (playbackState == Player.STATE_ENDED) _state.value = _state.value.copy(isPlaying = false, positionMs = p.duration.coerceAtLeast(0))
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _state.value = _state.value.copy(error = "No se puede reproducir: ${error.errorCodeName}", isPlaying = false)
            }
        })
        player = p
        session = runCatching { MediaSession.Builder(context, p).setId("audiovault-library").build() }.getOrNull()
        return p
    }

    fun play(item: AudioItemEntity) {
        val p = ensure()
        if (_state.value.itemId == item.id) { toggle(); return }
        val mi = MediaItem.Builder()
            .setUri(Uri.fromFile(File(item.filePath)))
            .setMediaId(item.id.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.displayName).setArtist(item.siteLabel).build())
            .build()
        p.setMediaItem(mi)
        p.prepare()
        p.playbackParameters = PlaybackParameters(_state.value.speed)
        p.play()
        _state.value = PlayerUi(itemId = item.id, title = item.displayName, isPlaying = true, durationMs = item.durationMs, speed = _state.value.speed)
    }

    fun toggle() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)); _state.value = _state.value.copy(positionMs = ms) }
    fun seekBy(deltaMs: Long) { player?.let { seekTo((it.currentPosition + deltaMs).coerceIn(0, maxOf(0, it.duration))) } }

    fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun stop() {
        player?.stop()
        _state.value = PlayerUi(speed = _state.value.speed)
    }

    /** Si se elimina el audio que suena, se detiene. */
    fun onItemDeleted(id: Long) { if (_state.value.itemId == id) stop() }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                _state.value = _state.value.copy(positionMs = p.currentPosition, durationMs = p.duration.takeIf { it > 0 } ?: _state.value.durationMs)
                if (!p.isPlaying) break
                delay(200)
            }
        }
    }

    fun release() {
        session?.release(); session = null
        player?.release(); player = null
    }
}

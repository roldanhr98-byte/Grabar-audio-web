package com.audiovault.browser.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiovault.browser.data.db.AudioOrigin
import com.audiovault.browser.data.db.CaptureStatus
import com.audiovault.browser.ui.theme.AvColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun size(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024)
    }

    fun duration(ms: Long?): String {
        if (ms == null || ms <= 0) return "—"
        val s = ms / 1000
        val h = s / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun seconds(sec: Double?): String = if (sec == null || sec <= 0 || sec.isInfinite()) "—" else duration((sec * 1000).toLong())

    fun date(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

fun originColor(o: AudioOrigin): Color = when {
    o.isCapture -> AvColors.Blue
    o == AudioOrigin.MSE_RECONSTRUCTED || o == AudioOrigin.WEBAUDIO_RENDERED -> AvColors.Amber
    else -> AvColors.Mint
}

fun statusColor(s: CaptureStatus): Color = when (s) {
    CaptureStatus.COMPLETE -> AvColors.Mint
    CaptureStatus.INCOMPLETE -> AvColors.Amber
    CaptureStatus.RECOVERED -> AvColors.Amber
}

/** Forma de onda con progreso; tocar para buscar. */
@Composable
fun Waveform(
    peaks: FloatArray?,
    progress: Float,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
) {
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(onSeek) {
                if (onSeek != null) detectTapGestures { off -> onSeek((off.x / size.width).coerceIn(0f, 1f)) }
            }
    ) {
        val data = peaks
        if (data == null || data.isEmpty()) {
            drawLine(rest, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
            drawRect(played, size = Size(size.width * progress, 3f), topLeft = Offset(0f, size.height / 2 - 1.5f))
            return@Canvas
        }
        val bw = size.width / data.size
        for (i in data.indices) {
            val h = (data[i].coerceAtLeast(0.03f)) * size.height
            val x = i * bw
            val c = if (i.toFloat() / data.size <= progress) played else rest
            drawRoundRect(
                c, topLeft = Offset(x + bw * 0.15f, (size.height - h) / 2), size = Size(bw * 0.7f, h),
                cornerRadius = CornerRadius(bw * 0.35f, bw * 0.35f)
            )
        }
    }
}

/** Medidor de nivel en dBFS (-60..0). */
@Composable
fun LevelMeter(db: Float, modifier: Modifier = Modifier) {
    val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val color = when {
        db > -6 -> AvColors.Red
        db > -20 -> AvColors.Amber
        else -> AvColors.Mint
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            drawRect(Color(0xFF28303B))
            drawRect(color, size = Size(size.width * frac, size.height))
        }
        Row { Text(if (db <= -119) "silencio" else "%.0f dBFS".format(db), fontSize = 11.sp, color = AvColors.Muted) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
    )
}

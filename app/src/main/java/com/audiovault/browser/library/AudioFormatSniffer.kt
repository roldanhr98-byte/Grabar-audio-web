package com.audiovault.browser.library

/**
 * Valida contenido multimedia mirando los primeros bytes ("magic numbers").
 * Evita guardar HTML, JSON, archivos vacíos o fragmentos inválidos como audio.
 */
object AudioFormatSniffer {

    data class Sniff(val ext: String, val mime: String, val isAudioOnly: Boolean)

    fun sniff(head: ByteArray, declaredMime: String? = null): Sniff? {
        if (head.size < 4) return null
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        fun ascii(off: Int, s: String) = s.indices.all { at(off + it) == s[it].code }

        if (looksLikeText(head)) return null
        if (ascii(0, "RIFF") && ascii(8, "WAVE")) return Sniff("wav", "audio/wav", true)
        if (ascii(0, "ID3")) return Sniff("mp3", "audio/mpeg", true)
        if (ascii(0, "OggS")) {
            // Opus u Vorbis dentro de Ogg.
            val isOpus = findAscii(head, "OpusHead", 64)
            return if (isOpus) Sniff("opus", "audio/ogg; codecs=opus", true) else Sniff("ogg", "audio/ogg", true)
        }
        if (ascii(0, "fLaC")) return Sniff("flac", "audio/flac", true)
        if (ascii(0, "#!AMR")) return Sniff("amr", "audio/amr", true)
        if (ascii(0, "FORM") && (ascii(8, "AIFF") || ascii(8, "AIFC"))) return Sniff("aiff", "audio/aiff", true)
        if (ascii(4, "ftyp") || ascii(4, "styp") || ascii(4, "moov") || ascii(4, "moof")) {
            val brand = String(head.copyOfRange(8, minOf(12, head.size)), Charsets.US_ASCII)
            val audioBrand = brand.startsWith("M4A") || brand.startsWith("M4B") || brand == "dash" && declaredMime?.startsWith("audio") == true
            val audio = audioBrand || declaredMime?.startsWith("audio") == true
            return if (audio) Sniff("m4a", "audio/mp4", true) else Sniff("mp4", "video/mp4", false)
        }
        if (at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3) {
            val audio = declaredMime?.startsWith("audio") == true
            return if (audio) Sniff("webm", "audio/webm", true) else Sniff("webm", "video/webm", false)
        }
        if (at(0) == 0x47 && (head.size < 189 || at(188) == 0x47)) {
            return Sniff("ts", "video/mp2t", declaredMime?.startsWith("audio") == true)
        }
        // ADTS AAC: 0xFFF1 / 0xFFF9 (syncword 12 bits + layer 00)
        if (at(0) == 0xFF && (at(1) and 0xF6) == 0xF0) return Sniff("aac", "audio/aac", true)
        // MPEG audio frame sync (MP3 sin ID3): 11 bits a 1, layer != 00
        if (at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 && (at(1) and 0x06) != 0) return Sniff("mp3", "audio/mpeg", true)
        return null
    }

    private fun findAscii(head: ByteArray, s: String, limit: Int): Boolean {
        val b = s.toByteArray(Charsets.US_ASCII)
        val end = minOf(head.size - b.size, limit)
        for (i in 0..end) {
            var ok = true
            for (j in b.indices) if (head[i + j] != b[j]) { ok = false; break }
            if (ok) return true
        }
        return false
    }

    /** Detecta HTML/JSON/XML/texto plano. */
    fun looksLikeText(head: ByteArray): Boolean {
        val n = minOf(head.size, 512)
        if (n == 0) return true
        var i = 0
        // BOM UTF-8
        if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < n && (head[i] == ' '.code.toByte() || head[i] == '\n'.code.toByte() || head[i] == '\r'.code.toByte() || head[i] == '\t'.code.toByte())) i++
        if (i >= n) return true
        val c = head[i].toInt().toChar()
        if (c == '<' || c == '{' || c == '[') return true
        // Todos los bytes imprimibles ⇒ texto
        var printable = 0
        for (k in 0 until n) {
            val b = head[k].toInt() and 0xFF
            if (b == 9 || b == 10 || b == 13 || b in 32..126) printable++
        }
        return printable == n
    }

    fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a", "mp4a", "aac_mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/ogg; codecs=opus"
        "webm", "weba" -> "audio/webm"
        "flac" -> "audio/flac"
        "ts" -> "video/mp2t"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    fun extForMime(mime: String?): String? {
        val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return null
        return when (m) {
            "audio/mpeg", "audio/mp3", "audio/mpeg3" -> "mp3"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/aac", "audio/aacp", "audio/x-aac" -> "aac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/webm" -> "webm"
            "audio/flac", "audio/x-flac" -> "flac"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/mp2t" -> "ts"
            else -> null
        }
    }
}

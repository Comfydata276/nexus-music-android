package org.medianexus.music.jam

import org.medianexus.music.playback.Steered

/**
 * Covers of a jam's songs that this phone's library doesn't have: the relay passes them on
 * from the host's app (Windows' `Joined::cover`). Someone else's relay, passing on someone
 * else's app: what comes back is used only if it is a picture (a JPEG, PNG or WebP by its
 * first bytes, no more than the relay itself takes, and of a size a cover could be), and it is
 * asked for only by a cover id that is one (see [plainId]).
 */
object JamCovers {
    /** The cover a row stands for: [Steered.FOREIGN], then the relay, the jam and the cover's
     *  id, a line each (nothing after the mark: no cover). */
    fun mark(relay: String, jam: String, cover: String): String =
        if (cover.isEmpty() || !plainId(cover)) Steered.FOREIGN else "${Steered.FOREIGN}$relay\n$jam\n$cover"

    /** Where the relay serves the cover a mark names, at the small or large size; null for none. */
    fun address(mark: String, large: Boolean): Triple<String, String, String>? {
        if (!mark.startsWith(Steered.FOREIGN)) return null
        val parts = mark.removePrefix(Steered.FOREIGN).split('\n')
        if (parts.size != 3 || !plainId(parts[2]) || parts[1].isEmpty() || !parts[1].all { it.isLetterOrDigit() }) return null
        return Triple(parts[0], "api/jam/${parts[1]}/cover/${if (large) "l" else "s"}/${parts[2]}", parts[2])
    }

    /** Whether these bytes are a picture by their first bytes: JPEG, PNG or WebP. */
    fun isPicture(bytes: ByteArray): Boolean {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xff) ?: -1
        val jpeg = at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF
        val png = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).withIndex().all { (i, b) -> at(i) == b }
        val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        return jpeg || png || webp
    }

    /** The edge a cover may have at most, in pixels: more is not a cover. */
    const val MOST_EDGE = 4096

    @Volatile var http: okhttp3.OkHttpClient? = null

    /** The cover a mark names, fetched from the relay; null unless it is a picture. */
    suspend fun fetch(mark: String, large: Boolean): ByteArray? {
        val (relay, path, _) = address(mark, large) ?: return null
        val client = http ?: return null
        val bytes = RelayLine(relay, client).cover(path) ?: return null
        if (!isPicture(bytes)) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MOST_EDGE || bounds.outHeight > MOST_EDGE) return null
        return bytes
    }
}

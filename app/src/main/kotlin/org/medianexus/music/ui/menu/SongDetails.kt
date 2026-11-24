package org.medianexus.music.ui.menu

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.medianexus.music.R
import org.medianexus.music.downloads.SongState
import org.medianexus.music.library.Arrange
import org.medianexus.music.net.Song
import org.medianexus.music.ui.parts.clock
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.theme.Theme

/** Where a song is on the phone, for its details. */
sealed interface OnPhone {
    /** Downloaded, as a file of this kind ("flac") at about this rate. */
    data class Downloaded(val kind: String, val kbps: Int) : OnPhone
    data object Downloading : OnPhone
    data object Waiting : OnPhone
    /** Kept whole from playing it (the kept songs): it plays without the server. */
    data object Kept : OnPhone
    data object Nowhere : OnPhone
}

/**
 * Everything the server says about a song, laid out to be read, as Windows' song details
 * (`show_song_details` in src/actions.rs): its name, then what it is. A line with nothing to
 * say is left out. The phone adds where the song is on the phone: downloaded (and as what),
 * kept from playing it, or not here at all.
 */
object SongDetails {
    /** The lines, each a name and what it says. `zone` and `locale` say how dates read. */
    fun lines(res: Resources, song: Song, rating: Int, onPhone: OnPhone, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun line(name: Int, value: String) {
            if (value.isNotEmpty()) out += res.getString(name) to value
        }
        val day = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
        fun date(text: String): String {
            val at = Arrange.parseTime(text)
            return if (at > 0) Instant.ofEpochSecond(at).atZone(zone).format(day) else ""
        }
        val several = song.artists.size > 1
        line(if (several) R.string.detail_artists else R.string.detail_artist, if (several) song.artists.joinToString(", ") { it.name } else song.artistLabel)
        line(R.string.detail_album, song.album)
        line(
            R.string.detail_track,
            when {
                song.track == 0 -> ""
                song.discNumber > 1 -> res.getString(R.string.detail_track_disc, song.track, song.discNumber)
                else -> song.track.toString()
            },
        )
        line(R.string.detail_year, if (song.year > 0) song.year.toString() else "")
        line(R.string.detail_genre, song.genre)
        line(R.string.detail_length, if (song.duration > 0) clock(song.duration) else "")
        line(R.string.detail_format, format(song))
        line(R.string.detail_size, if (song.size > 0) res.getString(R.string.detail_size_mb, "%.1f".format(Locale.ROOT, song.size / (1024.0 * 1024.0))) else "")
        val gains = song.replayGain
        line(
            R.string.detail_levelling,
            if (gains == null) {
                res.getString(R.string.detail_levelling_none)
            } else {
                listOfNotNull(
                    gains.trackGain?.let { res.getString(R.string.detail_levelling_song, db(it)) },
                    gains.albumGain?.let { res.getString(R.string.detail_levelling_album, db(it)) },
                ).joinToString(", ")
            },
        )
        line(
            R.string.detail_played,
            when (song.playCount) {
                0 -> res.getString(R.string.detail_played_never)
                1 -> res.getString(R.string.detail_played_once, date(song.played))
                else -> res.getString(R.string.detail_played_times, song.playCount, date(song.played))
            },
        )
        line(
            R.string.detail_rating,
            when (rating) {
                0 -> ""
                1 -> res.getString(R.string.detail_rating_one)
                else -> res.getString(R.string.detail_rating_stars, rating)
            },
        )
        line(R.string.detail_added, date(song.created))
        when (onPhone) {
            is OnPhone.Downloaded -> line(R.string.detail_downloaded_as, res.getString(R.string.detail_downloaded_value, onPhone.kind.uppercase(Locale.ROOT), onPhone.kbps))
            OnPhone.Downloading -> line(R.string.detail_on_phone, res.getString(R.string.detail_on_phone_downloading))
            OnPhone.Waiting -> line(R.string.detail_on_phone, res.getString(R.string.detail_on_phone_waiting))
            OnPhone.Kept -> line(R.string.detail_on_phone, res.getString(R.string.detail_on_phone_kept))
            OnPhone.Nowhere -> if (song.radioUrl.isEmpty()) line(R.string.detail_on_phone, res.getString(R.string.detail_on_phone_none))
        }
        return out
    }

    /** "FLAC, 48 kHz, 24-bit, 1411 kbps", as Windows writes it. */
    fun format(song: Song): String {
        val parts = mutableListOf(song.suffix.uppercase(Locale.ROOT))
        if (song.samplingRate > 0) {
            val khz = song.samplingRate / 1000.0
            parts += if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else "%.1f kHz".format(Locale.ROOT, khz)
        }
        if (song.bitDepth > 0) parts += "${song.bitDepth}-bit"
        if (song.bitRate > 0) parts += "${song.bitRate} kbps"
        return parts.filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun db(gain: Float) = "%+.1f dB".format(Locale.ROOT, gain)

    /** What a downloaded file is, as Windows' `saved_as`: its kind from its name, and its rate
     *  from its size and the song's length. */
    fun downloaded(file: String, bytes: Long, seconds: Int): OnPhone.Downloaded =
        OnPhone.Downloaded(file.substringAfterLast('.', "").lowercase(Locale.ROOT), if (seconds > 0) (bytes * 8 / seconds / 1000).toInt() else 0)
}

/** The details sheet's words: the song's title as its heading, then each line. */
@Composable
fun SongDetailsContent(song: Song) {
    val ui = LocalUi.current
    val res = LocalResources.current
    val account = ui.account
    val state = org.medianexus.music.ui.downloads.downloadsNow().stateOf(song.id)
    val onPhone = remember(song, state, account) {
        val entry = account?.catalogue?.entry(song.id)
        when {
            state == SongState.Done && entry != null -> SongDetails.downloaded(entry.file, entry.bytes, song.duration)
            state is SongState.Fetching -> OnPhone.Downloading
            state == SongState.Waiting -> OnPhone.Waiting
            account != null && account.availability.isKept(song) -> OnPhone.Kept
            else -> OnPhone.Nowhere
        }
    }
    // Played and rated as the app last saw it: a page from the server, or what it has just
    // reported itself, being newer than the row the song was shown in (Freshest).
    val freshest = account?.freshest
    val seen by (freshest?.version ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsStateWithLifecycle()
    val newest = remember(song, seen, freshest) { freshest?.of(song) ?: song }
    val lines = remember(newest, onPhone, res) { SongDetails.lines(res, newest, newest.userRating, onPhone) }
    val colors = Theme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.dimens.pagePad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(song.title, Modifier.semantics { heading() }, style = Theme.type.heading.copy(color = colors.text))
        for ((name, value) in lines) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                BasicText(name, style = Theme.type.rowDetail.copy(color = colors.muted))
                BasicText(value, style = Theme.type.body.copy(color = colors.text))
            }
        }
    }
}

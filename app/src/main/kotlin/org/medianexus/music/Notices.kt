package org.medianexus.music

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.medianexus.music.store.SettingsDamage

/** Something the app needs to tell the person, shown until they dismiss it. The words are in
 *  the string resources; this says which. */
sealed interface Notice {
    data class SettingsDamaged(val damage: SettingsDamage) : Notice

    /** Signed in, but the password couldn't be kept: the next start asks for it again. */
    data object PasswordNotSaved : Notice

    /** Signed in over plain HTTP to somewhere beyond the home network. */
    data object SignedInWithoutEncryption : Notice

    /** Signing out didn't happen: the saved password couldn't be removed from the phone. */
    data object SignOutFailed : Notice

    /** The settings couldn't be written; they're tried again at the next change. */
    data object SettingsNotSaved : Notice

    /** The server refused the saved sign-in (its password was changed there): signed out. */
    data object SignInGone : Notice
}

/** The notices waiting to be seen, oldest first. */
class Notices {
    private val waiting = MutableStateFlow<List<Notice>>(emptyList())
    val state: StateFlow<List<Notice>> = waiting.asStateFlow()

    fun post(notice: Notice) = waiting.update { if (notice in it) it else it + notice }

    fun dismiss(notice: Notice) = waiting.update { it - notice }
}

/**
 * Toasts: a few words about something that just happened ("Added to queue"), or that happened
 * out of sight (a like the server refused), shown for a moment at the foot of the screen as
 * the Windows app's toast. Unlike a notice, nothing waits to be dismissed.
 */
class Toasts {
    /** One toast: its words, and what its one button says and does, if it has one (Windows'
     *  "Undo"). */
    data class Toast(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

    private val said = MutableSharedFlow<Toast>(extraBufferCapacity = 8)
    val all: SharedFlow<Toast> = said.asSharedFlow()

    /** The words of each toast, as they are shown. */
    val shown: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow { said.collect { emit(it.text) } }

    fun show(text: String) {
        said.tryEmit(Toast(text))
    }

    /** A toast with a button (shown for longer, so there's time to press it: Windows'
     *  `toast_with_undo`). */
    fun show(text: String, action: String, onAction: () -> Unit) {
        said.tryEmit(Toast(text, action, onAction))
    }
}

/**
 * What the person's own server said when it refused something asked of it from somewhere the
 * words can't be shown: a car above all. The car, and the media session every other app with
 * a controller can read, are told the kind of failure in the app's own words; what the server
 * itself said is kept here, inside the app and in memory only, for the phone's own screens to
 * show the person whose server it is (see docs/DESIGN.md, "What a server says is the
 * person's").
 */
class Refusals(private val clock: () -> Long = System::currentTimeMillis) {
    /** The words (as `ApiError` gives them, already stripped of anything that names the
     *  address, account or sign-in), and when. */
    data class Refusal(val words: String, val at: Long)

    private val said = MutableStateFlow<Refusal?>(null)

    /** The last thing the server refused, or null. */
    val last: StateFlow<Refusal?> = said.asStateFlow()

    fun said(words: String) {
        if (words.isNotBlank()) said.value = Refusal(words, clock())
    }

    fun clear() {
        said.value = null
    }
}

package org.medianexus.music.ui.shell

/**
 * The screen that is alive now: what a passing note's button acts on, **looked up at the
 * press**, not taken when the note was made. A note can outlive the activity that made it
 * (notes wait their turn, and the activity is made again when the phone is turned or the
 * type size changes); its button must open the page on the screen that is there, and act
 * for the account signed in there.
 */
class Live<T : Any> {
    @Volatile private var now: T? = null

    /** `screen` is up (the newest takes over, whatever came before). */
    fun take(screen: T) {
        now = screen
    }

    /** `screen` has gone: let go only if it is still the one held (a newer one may have
     *  taken over already). */
    fun let(screen: T) {
        synchronized(this) { if (now === screen) now = null }
    }

    /** Does `block` on the screen alive now; nothing if none is. */
    fun act(block: (T) -> Unit) {
        now?.let(block)
    }
}

/** The app's: the shell on screen now (see [Live]). */
val LiveScreen = Live<Ui>()

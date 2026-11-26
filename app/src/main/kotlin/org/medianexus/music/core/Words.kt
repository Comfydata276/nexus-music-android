package org.medianexus.music.core

/**
 * The words of requests and of the sign-in that can sit in front of Lidarr, **as the shared
 * library has them**: every app says these the same, so none of them is written on the
 * phone. Each is the library's own function or constant of the same name (`gate::`,
 * `lidarr::`, `requests::` in the Windows repository's `core/`), asked for when it is
 * wanted. Sentences that come with an answer (why Lidarr couldn't be reached, what a page
 * says is empty) arrive inside that answer and are not here.
 */
object Words {
    private fun say(what: String, vararg with: String): String = Core.say(what, with.toList())

    // ---- the sign-in in the browser ----

    /** How long the browser is waited for. */
    val signInWaitMillis: Long get() = say("gate.wait_millis").toLong()

    /** Said when nobody signed in within that time. */
    val nobodySignedIn: String get() = say("gate.nobody_signed_in")

    /** The pages the browser is shown when it comes back, as HTML. */
    val pageSignedIn: String get() = say("gate.page.signed_in")
    val pageNotThrough: String get() = say("gate.page.not_through")
    val pageNothingHere: String get() = say("gate.page.nothing_here")

    /** The service turned the sign-in down, with its reason. */
    fun turnedDown(why: String): String = say("gate.turned_down", why)

    /** There was nowhere for the browser to come back to. */
    fun notReady(why: String): String = say("gate.not_ready", why)

    /** Listening for the browser failed. */
    fun notHeard(why: String): String = say("gate.not_heard", why)

    /** The device had no random numbers to give. */
    val noRandomNumbers: String get() = say("gate.no_random_numbers", "phone")

    /** "Lidarr is behind the sign-in at auth.example.org." */
    fun behindSignIn(host: String): String = say("gate.behind_sign_in", host)

    /** Said while the browser is open at the sign-in. */
    fun finishInBrowser(host: String): String = say("gate.finish_in_browser", host)

    /** "Signed out." and where the sign-in is. */
    fun signedOut(host: String): String = say("gate.signed_out", behindSignIn(host))

    // ---- the connection to Lidarr ----

    fun connected(version: String): String = say("lidarr.connected", version)
    val notConnected: String get() = say("lidarr.not_connected")
    val checking: String get() = say("lidarr.checking")
    val keyForgotten: String get() = say("lidarr.key_forgotten")

    /** An address the app can't be sure of reaching as written. */
    val addressUnusable: String get() = say("lidarr.address_unusable")

    /** Lidarr is behind a sign-in, and nobody is signed in. (It names where the Windows app
     *  has the sign-in: "In Settings, under Requests". The phone's is in the same place.) */
    val signInNeeded: String get() = say("lidarr.needed")

    // ---- asking ----

    val notListed: String get() = say("requests.not_listed")
    val noLongerListed: String get() = say("requests.no_longer_listed")
    fun askingFor(title: String): String = say("requests.asking_for", title)
    fun askedFor(title: String): String = say("requests.asked", title)
    fun addingNow(name: String): String = say("requests.adding_now", name)

    /** Ends before where to see them, which each app adds for itself. */
    fun added(name: String): String = say("requests.added", name)
    fun offTheList(title: String): String = say("requests.off_the_list", title)
}

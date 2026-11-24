package org.medianexus.music.jam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.medianexus.music.MainActivity
import org.medianexus.music.graph

/**
 * Where a jam's link arrives from another app: shared to Nexus Music (a chat's Share, as
 * text), or opened (`https://…/j/<jam>`, where the app opening it lets the person choose an app
 * for it). The link is all that is read, as text of bounded length; it is put in the jam
 * page's box and nothing is joined until the person presses Join.
 *
 * Android opens a web link in an app by itself only for an address the app has proved it
 * owns (an App Link, checked against a file on that site). A relay's address is the person's
 * own, unknown here, so that can't be: from Android 12 such a link opens in the browser, which
 * shows the relay's own page, unless the app it is opened from offers a choice. Sharing it to
 * the app always works.
 */
class JamLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.take(4096)
        val link = text?.let(Links::findJamLink)
        if (link != null) graph.jamLinkArrived.value = link.text
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

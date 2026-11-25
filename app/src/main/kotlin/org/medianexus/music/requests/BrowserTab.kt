package org.medianexus.music.requests

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * The browser the person signs in with, over the app: a **Custom Tab** where the phone's
 * browser has them (it then shares the browser's own sign-ins, so someone already signed in
 * to the service is not asked again), and the browser itself where it doesn't.
 *
 * Opened with a plain "view" intent carrying the one extra that asks for a Custom Tab, so
 * no library is needed for it. It is opened **from the screen that asked, in that screen's
 * own task**: the tab then sits on top of the app, closing it shows the app again, and the
 * app may bring itself back in front of it when the sign-in comes home ([comeBack]), which
 * Android allows an app whose own task is the one in front.
 */
object BrowserTab {
    private const val CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"

    @Volatile private var opener: ComponentName? = null

    /** Opens `page` over `from`. False if the phone has nothing that can show a web page. */
    fun open(from: Activity, page: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(page)).apply {
            // (A null session is all a browser needs to open this as a Custom Tab.)
            putExtras(Bundle().apply { putBinder(CUSTOM_TAB_SESSION, null) })
        }
        return try {
            from.startActivity(view)
            opener = from.componentName
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Brings the screen that opened the tab back in front of it (the tab is closed). Does
     *  nothing if no tab was opened in this run of the app. */
    fun comeBack(context: Context) {
        val screen = opener ?: return
        val back = Intent().setComponent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(back)
        } catch (_: RuntimeException) {
            // (Android would not let it: the person comes back by closing the tab.)
        }
    }
}

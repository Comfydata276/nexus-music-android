package org.medianexus.music.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Something only this app knows, made afresh each time it starts, and never written down or
 * logged. The notification's own buttons carry it (inside a pending intent, which no other
 * app can read), and an intent that arrives at the exported playback service without it is
 * not one of this app's buttons, whatever it says it is.
 */
class OwnMark(random: SecureRandom = SecureRandom()) {
    private val secret = ByteArray(24).also(random::nextBytes)

    /** `extras`, with the mark added. */
    fun on(extras: Bundle): Bundle = Bundle(extras).apply { putByteArray(KEY, secret.copyOf()) }

    /** Whether these extras carry the mark. Anything else they hold is not looked at, and a
     *  bundle that can't be unpacked carries nothing. */
    fun isOn(extras: Bundle?): Boolean = try {
        val given = extras?.getByteArray(KEY)
        given != null && MessageDigest.isEqual(given, secret)
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val KEY = "org.medianexus.music.MARK"
    }
}

/**
 * The media notification: Media3's own, with two differences that close the playback
 * service's front door to other apps.
 *
 * The service is exported (a car must be able to find it), so any app can send it a start
 * intent, and Media3 carries out one shaped like a tap on a notification button as if it
 * were one: under its notification controller's name, to which like, shuffle and repeat
 * were granted. Pausing someone's music is a nuisance; changing the likes on their server is
 * not something another app may do.
 *
 * So the like, shuffle and repeat buttons this builds carry the app's [OwnMark]; and every
 * button intent that reaches the service, real or forged, is handed here by Media3 before
 * anything else is done with it (`MediaNotificationManager.onCustomAction`). One that carries
 * the mark is carried out. One that doesn't is dropped, and nothing is passed on to the
 * notification controller at all: so a start intent can press Play, Pause, Next and Previous
 * (Media3's media-button intents, which carry nothing to mark), and nothing else.
 */
@OptIn(UnstableApi::class)
class OwnButtons(
    private val media3: MediaNotification.Provider,
    private val mark: OwnMark,
    /** Carries out one of the notification's buttons, by its action. */
    private val press: (action: String) -> Unit,
    /** Notes something for Diagnostics. */
    private val note: (String) -> Unit,
) : MediaNotification.Provider {
    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification = media3.createNotification(mediaSession, ImmutableList.copyOf(mediaButtonPreferences.map(::marked)), actionFactory, onNotificationChangedCallback)

    /** The same button, its command carrying the mark: only in the notification, never in
     *  what is sent to the car or any other controller. */
    private fun marked(button: CommandButton): CommandButton {
        val command = button.sessionCommand ?: return button
        if (command.commandCode != SessionCommand.COMMAND_CODE_CUSTOM) return button
        val made = (if (button.icon != CommandButton.ICON_UNDEFINED) CommandButton.Builder(button.icon) else CommandButton.Builder().setCustomIconResId(button.iconResId))
            .setDisplayName(button.displayName)
            .setSessionCommand(SessionCommand(command.customAction, mark.on(command.customExtras)))
            .setEnabled(button.isEnabled)
            .setExtras(button.extras)
            .setSlots(*button.slots.toArray())
        button.iconUri?.let(made::setIconUri)
        return made.build()
    }

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo = media3.notificationChannelInfo

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean {
        // (What was pressed is said in a fixed set of words: the action's own name came with
        // the intent, from whoever sent it.)
        val known = action in Access.BUTTONS
        if (known && mark.isOn(extras)) {
            press(action)
        } else {
            note("a start request shaped like a notification button (${if (known) "like, shuffle or repeat" else "other"}), without this app's mark: not carried out")
        }
        // Handled, either way: nothing is left for Media3 to send as the notification's own.
        return true
    }
}

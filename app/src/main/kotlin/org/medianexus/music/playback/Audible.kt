package org.medianexus.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * What the app can find out for itself about whether it is being heard, in words for
 * Diagnostics. It is not much, and it is worth being exact about:
 *
 * - **the player's audio track is moving** is said by the player (the caller notes it);
 * - **in the foreground** is Android's word on the service;
 * - **what Android lists as sounding**: every app is shown the players that are sounding
 *   now, with who they belong to taken out. So this is a count of players playing media,
 *   ours among them if it is one, and the kind of output each is going to (the phone's
 *   speaker, Bluetooth, USB, the "remote submix" an app such as Android Auto takes the
 *   sound through);
 * - **the media volume**, since a volume of nothing sounds like a fault.
 *
 * What it cannot find out is whether Android has muted the player (Android 17 does that to
 * an app with no standing to be heard, and tells the app nothing): that is only in
 * `dumpsys audio`. So "moving, in the foreground, listed, going to the car" from a drive
 * where nothing was heard points at Android, and anything less points at the app.
 *
 * Nothing private is in it: no name of a device, only its kind.
 */
object Audible {
    fun describe(context: Context, inForeground: Boolean): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val parts = ArrayList<String>()
        parts += "In the foreground: ${if (inForeground) "yes" else "no"}"
        parts += try {
            val media = audio.activePlaybackConfigurations.filter { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }
            // (Where each is going is told from Android 12.)
            val going = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) media.mapNotNull { it.audioDeviceInfo?.type }.distinct().map(::kind) else emptyList()
            "Android lists ${listed(media.size)} sounding" + if (going.isEmpty()) "" else ", going to ${going.joinToString(" and ")}"
        } catch (_: RuntimeException) {
            "what Android lists as sounding could not be read"
        }
        parts += try {
            "media volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        } catch (_: RuntimeException) {
            "the media volume could not be read"
        }
        return parts.joinToString("; ")
    }

    internal fun listed(count: Int): String = when (count) {
        0 -> "no media player"
        1 -> "1 media player"
        else -> "$count media players"
    }

    /** The kind of output an `AudioDeviceInfo` type is, in a fixed set of words. */
    internal fun kind(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "the phone's speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_DOCK -> "USB"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "a wired output"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_BUS -> "a car's own audio bus"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "a remote submix (an app carrying the sound on, as Android Auto does)"
        AudioDeviceInfo.TYPE_HEARING_AID -> "a hearing aid"
        AudioDeviceInfo.TYPE_IP -> "a network output"
        AudioDeviceInfo.TYPE_TELEPHONY -> "a phone call"
        else -> "another kind of output (type $type)"
    }
}

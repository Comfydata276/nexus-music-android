package org.medianexus.music.ui.shell

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.medianexus.music.LocalAccess
import org.medianexus.music.store.SettingsStore

/** Whether the app holds a permission. */
fun Context.holds(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Asks for a permission, in context. If the phone won't ask any more (it was refused twice),
 * the app's page in the phone's settings is opened instead, where it can still be allowed.
 * `then` hears whether it was granted.
 */
@Composable
fun rememberAsker(permission: String, then: (Boolean) -> Unit = {}): () -> Unit {
    val activity = LocalActivity.current
    var askedAt by remember { mutableLongStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // An answer within a moment, with no reason to give, is the system answering by
        // itself: it was refused for good, and the settings page is the only place left to
        // allow it. (Android says no more than that about whether it asked.)
        val quick = android.os.SystemClock.elapsedRealtime() - askedAt < 400
        if (!granted && quick && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            openAppSettings(activity)
        }
        then(granted)
    }
    return {
        askedAt = android.os.SystemClock.elapsedRealtime()
        launcher.launch(permission)
    }
}

fun openAppSettings(activity: Activity) {
    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
}

/**
 * Notifications, from Android 13: asked for the first time something is played, when the
 * reason is plain (the player's controls live in a notification). Asked once; if refused,
 * music plays all the same, with no notification.
 */
@Composable
fun rememberNotificationAsk(settings: SettingsStore): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return {}
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (activity != null && !activity.holds(Manifest.permission.POST_NOTIFICATIONS) && !settings.value.askedNotifications) {
            settings.update { it.copy(askedNotifications = true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Asks for the local-network permission, then looks again at whether it's held. */
@Composable
fun rememberLocalNetworkAsk(access: LocalAccess, then: (Boolean) -> Unit = {}): () -> Unit =
    rememberAsker(LocalAccess.PERMISSION) { granted ->
        access.recheck()
        then(granted)
    }

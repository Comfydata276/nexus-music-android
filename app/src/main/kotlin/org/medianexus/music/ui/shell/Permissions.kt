package org.medianexus.music.ui.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.medianexus.music.LocalAccess

/** Whether the app holds a permission. */
fun Context.holds(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun openAppSettings(activity: Activity) {
    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
}

/**
 * Asks for the local-network permission. If the system has said it won't ask again, the
 * app's page in the phone's settings is opened instead, where it can still be allowed (and
 * is noticed on coming back). `then` hears the answer; it is kept by [LocalAccess], so it
 * still hears it if the activity is rebuilt while the system's question is showing.
 *
 * The launcher itself is registered again by the rebuilt activity under the same key, and
 * the system's answer is delivered to it there. Says whether the system's question was put
 * (false: the settings page was opened instead, or a question is already up), so a caller
 * holding something for `then` can let it go.
 */
@Composable
fun rememberLocalNetworkAsk(access: LocalAccess): (then: ((Boolean) -> Unit)?) -> Boolean {
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // After a refusal the system says whether it would ask again: that, not how quickly
        // it answered, is what decides where the next "Allow" goes.
        val again = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, LocalAccess.PERMISSION)
        access.answered(granted, canAskAgain = granted || again)
    }
    return { then ->
        if (access.cannotAsk && activity != null) {
            // Allowed there, it is noticed when the app comes back to the front.
            openAppSettings(activity)
            false
        } else if (access.asking(then)) {
            // Only one question at a time: a second tap while one is up asks nothing.
            try {
                launcher.launch(LocalAccess.PERMISSION)
                true
            } catch (_: android.content.ActivityNotFoundException) {
                access.abandoned()
                false
            }
        } else {
            false
        }
    }
}

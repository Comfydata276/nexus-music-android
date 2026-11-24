package org.medianexus.music.ui.downloads

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.medianexus.music.ui.shell.LocalUi
import org.medianexus.music.ui.shell.holds

/**
 * Runs a download the person asked for, having first asked (once, on Android 13 and later)
 * for the notifications permission its progress notification needs. Refused, the downloads
 * run all the same and show their progress in the app. Made once, by the shell (see
 * `Ui.askToDownload`): what asked may be gone by the time the answer comes.
 */
@Composable
fun rememberDownloadAsk(): (() -> Unit) -> Unit {
    val ui = LocalUi.current
    val context = LocalContext.current
    val waiting = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ui.graph.settings.update { s -> s.copy(downloadNotificationsAsked = true) }
        waiting[0]?.invoke()
        waiting[0] = null
    }
    return remember(ui, context, launcher) {
        { action ->
            val ask = Build.VERSION.SDK_INT >= 33 && !ui.graph.settings.value.downloadNotificationsAsked && !context.holds(Manifest.permission.POST_NOTIFICATIONS)
            if (ask && waiting[0] == null) {
                waiting[0] = action
                try {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: android.content.ActivityNotFoundException) {
                    waiting[0] = null
                    action()
                }
            } else {
                action()
            }
        }
    }
}

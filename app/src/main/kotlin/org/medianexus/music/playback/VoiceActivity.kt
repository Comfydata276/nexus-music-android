package org.medianexus.music.playback

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.medianexus.music.MainActivity

/**
 * Where "play X on Nexus Music" said to the phone's Assistant arrives (the car's requests go
 * straight to the playback service). It hands the request to the service, in the form the
 * car uses, opens the app, and is gone: it has no screen of its own.
 */
@OptIn(UnstableApi::class)
class VoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(asked: Intent?) {
        if (org.medianexus.music.BuildConfig.DEBUG) android.util.Log.d("NexusPlayback", "voice activity: ${asked?.action} ${asked?.extras?.keySet()}")
        if (asked?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = asked.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
            val extras = asked.extras ?: Bundle()
            val token = SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java))
            val future = MediaController.Builder(applicationContext, token).buildAsync()
            future.addListener({
                val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
                val item = MediaItem.Builder()
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build())
                    .build()
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
                // The service carries on by itself; this connection was only for asking, and
                // is let go once the request has had time to be looked up.
                android.os.Handler(mainLooper).postDelayed({ controller.release() }, RELEASE_AFTER_MILLIS)
            }, ContextCompat.getMainExecutor(applicationContext))
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private companion object {
        const val RELEASE_AFTER_MILLIS = 20_000L
    }
}

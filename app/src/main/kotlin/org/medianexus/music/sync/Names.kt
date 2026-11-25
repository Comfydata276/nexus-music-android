package org.medianexus.music.sync

/**
 * The playlist whose comment holds what the person's devices share (the shared core's
 * `PLAYLIST_NAME`; `CoreNamesTest` holds this to the core's own). Known here by name
 * because the phone never lists, plays, downloads or edits it, whether or not keeping in
 * step exists in this build, and a playlist of the person's own may not take the name.
 */
const val PLAYLIST_NAME = "Nexus Music sync"

/** A made-up id for this device, the first time one is wanted: eight hex digits, as the
 *  Windows app makes its own. (The name a device saves its queue under is made from it by
 *  the shared core.) */
fun newDeviceId(random: java.util.Random = java.security.SecureRandom()): String = "%08x".format(random.nextInt())

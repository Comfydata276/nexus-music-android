package org.medianexus.music.downloads

import android.content.Context
import java.io.File

/**
 * Debug builds only: the room the free-space check leaves spare, set by the test rig
 * (`DebugControl --es spare <megabytes>`, nothing to go back to half a gigabyte), so that the
 * check's refusal can be seen on an emulator without filling its disk. Kept in a file, so a
 * cold start still has it. A release build always leaves Windows' half a gigabyte.
 */
object DebugSpare {
    private fun file(context: Context) = File(context.filesDir, "debug-spare.txt")

    fun spare(context: Context): Long? = runCatching { file(context).readText().trim().toLongOrNull()?.let { it * 1024 * 1024 } }.getOrNull()

    fun set(context: Context, megabytes: String): String {
        val value = megabytes.trim().toLongOrNull()
        if (value == null) file(context).delete() else file(context).writeText(value.toString())
        return if (value == null) "spare room: the usual" else "spare room: $value MB"
    }
}

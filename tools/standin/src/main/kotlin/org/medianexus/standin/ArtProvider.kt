package org.medianexus.standin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * FIXTURE. Hands artwork to other apps, as the real app's cover provider will: the car
 * cannot fetch pictures from a server, it can only open content addresses. Here every
 * picture is a coloured square with a letter, drawn on first request.
 */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException()
        if ("art_private" in Faults.active(context)) throw SecurityException("artwork is not shared (fault switched on)")
        if (mode != "r") throw SecurityException("artwork is read-only")
        val key = uri.lastPathSegment?.takeIf { it.matches(Regex("[a-z0-9]+")) } ?: throw FileNotFoundException(uri.toString())
        val file = File(context.cacheDir, "art/$key.png")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val size = 400
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val hue = (key.hashCode() and 0xffff) % 360
            canvas.drawColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.6f, 0.75f)))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 220f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            val letter = key.first().uppercase() + key.last()
            canvas.drawText(letter, size / 2f, size / 2f + 80f, paint)
            val part = File(file.path + ".part")
            part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            part.renameTo(file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

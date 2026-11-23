package org.medianexus.music.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: hearing what the player plays, for a rig that can't record sound. A tap
 * at the end of the player's audio chain copies every sample on its way to the phone's audio
 * output into a WAV file while a capture is running, untouched, so `scripts/server
 * sweepcheck` can find any gap or jump at the joins between songs. Started and stopped by
 * [DebugControl] from adb (`scripts/emu capture start|stop|pull`).
 *
 * The tap sits after everything the player does to the sound (trimming the encoders'
 * padding for gapless joins among it), so the file is what reaches the output, sample for
 * sample. A change of format part-way (another sample rate) starts a new file, numbered on.
 */
@OptIn(UnstableApi::class)
object DebugAudio {
    private val sink = WavSink()

    fun processors(@Suppress("UNUSED_PARAMETER") context: Context): Array<AudioProcessor> = arrayOf(TeeAudioProcessor(sink))

    /** Starts writing to `<dir>/<name>.wav`; any capture already running is finished first. */
    fun start(dir: File, name: String) = sink.start(dir, name)

    /** Finishes the capture; returns the files written. */
    fun stop(): List<File> = sink.stop()

    private class WavSink : TeeAudioProcessor.AudioBufferSink {
        private var rate = 0
        private var channels = 0
        private var encoding = C.ENCODING_INVALID
        private var dir: File? = null
        private var name = ""
        private var out: RandomAccessFile? = null
        private var written = 0L
        private val files = mutableListOf<File>()

        @Synchronized
        fun start(dir: File, name: String) {
            stop()
            dir.mkdirs()
            this.dir = dir
            this.name = name
            files.clear()
            if (rate > 0) open()
            Log.d(TAG, "capture started: $name")
        }

        @Synchronized
        fun stop(): List<File> {
            close()
            val done = files.toList()
            if (dir != null) Log.d(TAG, "capture stopped: ${done.joinToString { "${it.name} (${it.length()} bytes)" }}")
            dir = null
            return done
        }

        @Synchronized
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            // Called at every reset of the chain (a seek, a new format). Only a new format
            // needs a new file: a WAV holds one.
            val changed = sampleRateHz != rate || channelCount != channels || encoding != this.encoding
            rate = sampleRateHz
            channels = channelCount
            this.encoding = encoding
            Log.d(TAG, "audio chain reset: $sampleRateHz Hz, $channelCount channels, encoding $encoding")
            if (dir != null && (changed || out == null)) {
                close()
                open()
            }
        }

        @Synchronized
        override fun handleBuffer(buffer: ByteBuffer) {
            val file = out ?: return
            try {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                file.write(bytes)
                written += bytes.size
            } catch (e: IOException) {
                Log.w(TAG, "capture failed: ${e.message}")
                close()
            }
        }

        private fun open() {
            val folder = dir ?: return
            val file = File(folder, if (files.isEmpty()) "$name.wav" else "$name-${files.size + 1}.wav")
            try {
                out = RandomAccessFile(file, "rw").apply { setLength(0); write(header(0)) }
                written = 0
                files += file
            } catch (e: IOException) {
                Log.w(TAG, "couldn't open ${file.name}: ${e.message}")
                out = null
            }
        }

        private fun close() {
            val file = out ?: return
            out = null
            try {
                file.seek(0)
                file.write(header(written))
                file.close()
            } catch (e: IOException) {
                Log.w(TAG, "couldn't finish the capture: ${e.message}")
            }
        }

        /** A plain RIFF/WAVE header for `dataBytes` of audio in the current format. */
        private fun header(dataBytes: Long): ByteArray {
            val float = encoding == C.ENCODING_PCM_FLOAT
            val bits = when (encoding) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                else -> 16
            }
            val blockAlign = channels * bits / 8
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(if (float) 3 else 1).putShort(channels.toShort())
            b.putInt(rate).putInt(rate * blockAlign).putShort(blockAlign.toShort()).putShort(bits.toShort())
            b.put("data".toByteArray()).putInt(dataBytes.toInt())
            return b.array()
        }
    }

    private const val TAG = "NexusCapture"
}

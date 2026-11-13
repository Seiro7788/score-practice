package com.scorepractice.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** sample-accurate metronome: clicks are written into one continuous audio stream. */
class Metronome(private val onBeat: (Int) -> Unit) {
    private val rate = 44100
    @Volatile var bpm = 80
    @Volatile var beats = 4
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    val isRunning get() = running

    private fun click(freq: Double, len: Int, vol: Double): ShortArray =
        ShortArray(len) { i ->
            val t = i.toDouble() / rate
            (sin(2 * PI * freq * t) * exp(-t * 60) * vol * Short.MAX_VALUE).toInt().toShort()
        }

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf).setTransferMode(AudioTrack.MODE_STREAM).build()
            val hi = click(1600.0, rate / 20, 0.9); val lo = click(1000.0, rate / 20, 0.6)
            track.play()
            var beat = 0
            while (running) {
                val n = (rate * 60.0 / bpm.coerceIn(20, 300)).toInt()
                val buf = ShortArray(n)
                val c = if (beat == 0) hi else lo
                System.arraycopy(c, 0, buf, 0, minOf(c.size, n))
                val b = beat; main.post { onBeat(b) }
                var off = 0
                while (off < n && running) { val w = track.write(buf, off, minOf(2048, n - off)); if (w <= 0) break; off += w }
                beat = (beat + 1) % beats.coerceAtLeast(1)
            }
            track.stop(); track.release()
        }.apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() { running = false; thread = null }
}

package com.scorepractice.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Sings the melody with note names (도 레 미 파 솔 라 시, fixed do: C = 도).
 * The phone's Korean voice says each syllable once; the sound is then tuned to every note's pitch
 * and stretched to its length. Without a Korean voice a plain "아" tone is used.
 */
object Singer {
    private val SYL = mapOf('C' to "도", 'D' to "레", 'E' to "미", 'F' to "파", 'G' to "솔", 'A' to "라", 'B' to "시")
    private const val RATE = 22050

    class Sample(val pcm: FloatArray, val rate: Int, val f0: Double)

    private var cache: Map<Char, Sample>? = null

    /** make the seven syllables with text-to-speech (blocks; call off the main thread) */
    fun prepare(c: Context): Map<Char, Sample>? {
        cache?.let { return it }
        val dir = File(c.cacheDir, "syl").apply { mkdirs() }
        val ready = CountDownLatch(1); var ok = false
        var tts: TextToSpeech? = null
        tts = TextToSpeech(c.applicationContext) { st ->
            ok = st == TextToSpeech.SUCCESS && tts!!.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
            ready.countDown()
        }
        ready.await(8, TimeUnit.SECONDS)
        val t = tts ?: return null
        if (!ok) { t.shutdown(); return null }
        val done = CountDownLatch(SYL.size)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done.countDown() }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { done.countDown() }
        })
        t.setSpeechRate(1.1f)
        for ((k, s) in SYL) t.synthesizeToFile(s, Bundle(), File(dir, "$k.wav"), "syl$k")
        done.await(20, TimeUnit.SECONDS); t.shutdown()
        val out = mutableMapOf<Char, Sample>()
        for (k in SYL.keys) readWav(File(dir, "$k.wav"))?.let { (pcm, rate) -> trim(pcm)?.let { out[k] = Sample(it, rate, pitchOf(it, rate)) } }
        return if (out.size == SYL.size) out.also { cache = it } else null
    }

    private fun readWav(f: File): Pair<FloatArray, Int>? {
        if (!f.exists() || f.length() < 100) return null
        val b = f.readBytes()
        fun u16(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        var i = 12; var rate = 22050; var ch = 1; var bits = 16
        while (i + 8 <= b.size) {
            val id = String(b, i, 4); val len = u32(i + 4)
            if (id == "fmt ") { ch = u16(i + 10); rate = u32(i + 12); bits = u16(i + 22) }
            if (id == "data") {
                if (bits != 16) return null
                val n = min(len, b.size - i - 8) / (2 * ch)
                return FloatArray(n) { k -> val o = i + 8 + k * 2 * ch; (u16(o).toShort()).toFloat() / 32768f } to rate
            }
            i += 8 + len + (len and 1)
        }
        return null
    }

    private fun trim(x: FloatArray): FloatArray? {
        val th = (x.maxOfOrNull { abs(it) } ?: 0f) * 0.06f
        val a = x.indexOfFirst { abs(it) > th }; val b = x.indexOfLast { abs(it) > th }
        return if (a < 0 || b <= a) null else x.copyOfRange(a, b + 1)
    }

    /** fundamental of the vowel (middle part), by autocorrelation in 70..450 Hz */
    private fun pitchOf(x: FloatArray, rate: Int): Double {
        val n = min(x.size / 3, (rate * 0.05).toInt()); val s = x.size / 2 - n / 2
        if (n < 100 || s < 0) return 200.0
        var bestLag = 0; var best = 0.0
        for (lag in rate / 450..rate / 70) {
            var c = 0.0; for (i in 0 until n - lag) c += x[s + i] * x[s + i + lag]
            if (c > best) { best = c; bestLag = lag }
        }
        return if (bestLag > 0) rate.toDouble() / bestLag else 200.0
    }

    /** resample (tunes the pitch), then fit to the note's length by looping the vowel */
    private fun note(sm: Sample, f: Double, len: Int): FloatArray {
        val r = f / sm.f0 * sm.rate / RATE
        val tuned = FloatArray(max(1, (sm.pcm.size / r).toInt())) { i ->
            val p = i * r; val k = p.toInt(); val fr = (p - k).toFloat()
            if (k + 1 < sm.pcm.size) sm.pcm[k] * (1 - fr) + sm.pcm[k + 1] * fr else 0f
        }
        val out = FloatArray(len)
        if (tuned.size >= len) System.arraycopy(tuned, 0, out, 0, len)
        else {
            // keep the consonant + vowel, then hold the vowel: loop one stretch of it with cross-fades
            val loopLen = max(64, (RATE * 0.06).toInt()); val ls = max(0, tuned.size - loopLen - (RATE * 0.03).toInt())
            System.arraycopy(tuned, 0, out, 0, tuned.size)
            var pos = ls + loopLen; val xf = loopLen / 4
            while (pos < len) {
                for (i in 0 until loopLen) {
                    if (pos + i >= len) break
                    val v = tuned[min(tuned.size - 1, ls + i)]
                    if (i < xf && pos + i < tuned.size + loopLen) out[pos + i] = out[pos + i] * (1 - i.toFloat() / xf) + v * (i.toFloat() / xf)
                    else out[pos + i] = v
                }
                pos += loopLen - xf
            }
        }
        val fade = min(len / 3, (RATE * 0.025).toInt())   // soft end
        for (i in 0 until fade) out[len - 1 - i] *= i.toFloat() / fade
        return out
    }

    /** plain sung "아" when no voice is available */
    private fun tone(f: Double, len: Int): FloatArray = FloatArray(len) { i ->
        val t = i.toDouble() / RATE; val vib = 1 + 0.006 * sin(2 * PI * 5.5 * t)
        val env = min(1.0, t / 0.03) * min(1.0, (len - i).toDouble() / (RATE * 0.03))
        (env * (0.6 * sin(2 * PI * f * vib * t) + 0.25 * sin(4 * PI * f * vib * t) + 0.12 * sin(6 * PI * f * vib * t))).toFloat()
    }

    /** render the melody to a WAV file; returns false if nothing to sing */
    fun render(c: Context, melody: List<TNote>, out: File): Boolean {
        if (melody.isEmpty()) return false
        val sm = prepare(c)
        val total = ((melody.last().t + melody.last().d + 0.5) * RATE).toInt()
        val mix = FloatArray(total)
        for (n in melody) {
            var f = 440.0 * 2.0.pow((n.midi - 69) / 12.0)
            val s = sm?.get(n.step)
            // keep the voice in a natural range (an octave up/down does not change the note name)
            val base = s?.f0 ?: 220.0
            while (f > base * 1.7) f /= 2; while (f < base * 0.62) f *= 2
            val len = max(1, (n.d * RATE).toInt()); val a = (n.t * RATE).toInt()
            val v = if (s != null) note(s, f, len) else tone(f, len)
            for (i in v.indices) if (a + i < total) mix[a + i] += v[i]
        }
        val peak = max(1e-6f, mix.maxOf { abs(it) }); val g = 0.85f / peak
        writeWav(out, FloatArray(total) { mix[it] * g })
        return true
    }

    private fun writeWav(f: File, x: FloatArray) {
        RandomAccessFile(f, "rw").use { o ->
            o.setLength(0)
            fun i32(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff); o.write((v shr 16) and 0xff); o.write((v shr 24) and 0xff) }
            fun i16(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
            o.writeBytes("RIFF"); i32(36 + x.size * 2); o.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
            o.writeBytes("data"); i32(x.size * 2)
            val buf = ByteArray(x.size * 2)
            for (i in x.indices) { val v = (x[i].coerceIn(-1f, 1f) * 32767).toInt(); buf[2 * i] = v.toByte(); buf[2 * i + 1] = (v shr 8).toByte() }
            o.write(buf)
        }
    }
}

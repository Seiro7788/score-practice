package com.scorepractice.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sings the melody with note names in a human voice:
 * the phone's text-to-speech says each syllable once (Italian voice if installed, otherwise Korean),
 * then every note is made from it by PSOLA — pitch periods are re-spaced to the exact note pitch
 * (the voice's own timbre and clear pronunciation stay), and the vowel is stretched smoothly to the note length.
 * A male voice is made by lowering the formants; without any voice a small synthesizer is used.
 * Syllables in Italian: do re mi fa sol la si (fixed do: C = do), clear machine voice.
 * Rules: soprano lines in a female voice, low lines in a male voice; the voice never changes inside a phrase;
 * notes too high/low to sing move by whole octaves for a whole phrase (not note by note); legato, no gaps.
 * Bach and Scarlatti keep their written octaves.
 */
object Singer {
    private const val RATE = 22050

    /** vowel formants (Hz) and bandwidths, mid voice */
    private class Vowel(val f: DoubleArray, val b: DoubleArray = doubleArrayOf(90.0, 110.0, 160.0), val a: DoubleArray = doubleArrayOf(1.0, 0.6, 0.25))
    private val A = Vowel(doubleArrayOf(750.0, 1250.0, 2700.0))
    private val E = Vowel(doubleArrayOf(480.0, 1900.0, 2600.0))
    private val I = Vowel(doubleArrayOf(300.0, 2300.0, 3000.0))
    private val O = Vowel(doubleArrayOf(450.0, 800.0, 2830.0))
    private val U = Vowel(doubleArrayOf(330.0, 900.0, 2400.0))
    private val L = Vowel(doubleArrayOf(350.0, 1000.0, 2500.0))   // l / r colour
    private val M = Vowel(doubleArrayOf(250.0, 1200.0, 2500.0), a = doubleArrayOf(1.0, 0.08, 0.03))   // nasal hum

    /** consonant kinds at the start of a syllable */
    private enum class C { NONE, D, R, M, P, S, SH, F, G, B, L }
    private class Syl(val c: C, val v: Vowel, val end: Vowel? = null, val pre: Vowel? = null)

    private fun syllable(step: Char): Syl = when (step) {   // do re mi fa sol la si
        'C' -> Syl(C.D, O); 'D' -> Syl(C.R, E); 'E' -> Syl(C.M, I); 'F' -> Syl(C.F, A)
        'G' -> Syl(C.S, O, end = L); 'A' -> Syl(C.L, A); else -> Syl(C.S, I)
    }

    /** female voice: formants about 17 % higher */
    private fun voiced(v: Vowel, female: Boolean) = if (!female) v else Vowel(DoubleArray(3) { v.f[it] * 1.17 }, v.b, v.a)

    /** spectral envelope of a vowel at frequency f */
    private fun env(v: Vowel, f: Double): Double {
        var s = 0.0
        for (i in 0..2) { val x = (f - v.f[i]) / (v.b[i] / 2); s += v.a[i] / sqrt(1 + x * x) }
        return s
    }

    private fun mix(a: Vowel, b: Vowel, t: Double) = Vowel(DoubleArray(3) { a.f[it] + (b.f[it] - a.f[it]) * t },
        DoubleArray(3) { a.b[it] + (b.b[it] - a.b[it]) * t }, DoubleArray(3) { a.a[it] + (b.a[it] - a.a[it]) * t })

    private val rnd = java.util.Random(7)

    /** one sung note: consonant, then the vowel held at pitch f for len samples */
    private fun synth(step: Char, f: Double, len: Int, female: Boolean): FloatArray {
        val s0 = syllable(step)
        val sy = Syl(s0.c, voiced(s0.v, female), s0.end?.let { voiced(it, female) })
        val out = FloatArray(len)
        val cons = when (sy.c) { C.NONE -> 0; C.D, C.G, C.B -> (0.018 * RATE).toInt(); C.P -> (0.03 * RATE).toInt()
            C.S, C.SH, C.F -> (0.07 * RATE).toInt(); C.R -> (0.035 * RATE).toInt(); C.M, C.L -> (0.06 * RATE).toInt() }
        val c0 = min(cons, len / 3)
        // noise part of s / sh / p / f (before the voice starts)
        if (sy.c == C.S || sy.c == C.SH || sy.c == C.P || sy.c == C.F) {
            var lp = 0.0; var prev = 0.0
            for (i in 0 until c0) {
                val n = rnd.nextGaussian()
                val hp = n - prev; prev = n                    // brighten
                lp += (hp - lp) * (if (sy.c == C.S) 0.9 else if (sy.c == C.SH) 0.55 else 0.35)
                val e = sin(PI * i / c0)
                out[i] = (lp * e * (if (sy.c == C.P) 0.25 else 0.12)).toFloat()
            }
        }
        // voiced part: additive harmonics shaped by the (moving) vowel envelope
        val vStart = if (sy.c == C.M || sy.c == C.L || sy.c == C.R) 0 else c0
        val nh = max(1, (4500 / f).toInt())
        val ph = DoubleArray(nh)
        var vib = 0.0
        val att = (0.025 * RATE).toInt(); val rel = min(len / 4, (0.06 * RATE).toInt())
        var i = vStart
        while (i < len) {
            val t = (i - vStart).toDouble() / RATE
            // vowel colour over time: m / l / r glide into the vowel, l at the end of 솔
            val v = when {
                sy.c == C.M && i < c0 -> M
                (sy.c == C.L || sy.c == C.R) && i < c0 -> mix(voiced(L, female), sy.v, (i.toDouble() / c0).pow(2))
                sy.end != null && i > len * 0.72 -> mix(sy.v, sy.end, min(1.0, (i - len * 0.72) / (len * 0.2)))
                else -> sy.v
            }
            // gentle vibrato after the start of long notes
            val depth = if (t > 0.25) min(0.006, (t - 0.25) * 0.02) else 0.0
            vib += 2 * PI * 5.2 / RATE
            val ff = f * (1 + depth * sin(vib))
            var s = 0.0
            for (k in 1..nh) {
                val fk = ff * k; if (fk > 5000) break
                ph[k - 1] += 2 * PI * fk / RATE
                s += sin(ph[k - 1]) * env(v, fk) / k.toDouble().pow(0.9)
            }
            // envelope: soft attack (d/g/b: quicker), release at the end, r = brief dip (tap)
            var e = min(1.0, (i - vStart + 1).toDouble() / (if (sy.c == C.D || sy.c == C.G || sy.c == C.B) att / 3 else att))
            if (sy.c == C.R && i < c0) e *= 0.35
            if (sy.c == C.M && i < c0) e *= 0.55
            if (i > len - rel) e *= (len - i).toDouble() / rel
            out[i] += (s * e * 0.22).toFloat()
            i++
        }
        return out
    }

    // ---------- human voice: TTS syllables + PSOLA
    class Voice(val pcm: FloatArray, val v0: Int, val marks: IntArray, val period: Int)
    private var voices: Map<Char, Voice>? = null
    private var triedTts = false

    private val IT = mapOf('C' to "do", 'D' to "re", 'E' to "mi", 'F' to "fa", 'G' to "sol", 'A' to "la", 'B' to "si")
    private val KO = mapOf('C' to "도", 'D' to "레", 'E' to "미", 'F' to "파", 'G' to "솔", 'A' to "라", 'B' to "씨")

    /** make the seven syllables once (blocks; call off the main thread) */
    private fun prepare(c: Context): Map<Char, Voice>? {
        voices?.let { return it }
        if (triedTts) return null
        triedTts = true
        val dir = File(c.cacheDir, "syl2").apply { mkdirs() }
        val ready = CountDownLatch(1); var status = -1
        var tts: TextToSpeech? = null
        tts = TextToSpeech(c.applicationContext) { st -> status = st; ready.countDown() }
        ready.await(8, TimeUnit.SECONDS)
        val t = tts ?: return null
        if (status != TextToSpeech.SUCCESS) { t.shutdown(); return null }
        val words = when {
            t.setLanguage(Locale.ITALIAN) >= TextToSpeech.LANG_AVAILABLE -> IT
            t.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE -> KO
            else -> { t.shutdown(); return null }
        }
        val done = CountDownLatch(words.size)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done.countDown() }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { done.countDown() }
        })
        t.setSpeechRate(0.85f)
        for ((k, w) in words) t.synthesizeToFile(w, Bundle(), File(dir, "$k.wav"), "s$k")
        done.await(25, TimeUnit.SECONDS); t.shutdown()
        val out = mutableMapOf<Char, Voice>()
        for (k in words.keys) readWav(File(dir, "$k.wav"))?.let { analyse(it) }?.let { out[k] = it }
        return if (out.size == words.size) out.also { voices = it } else null
    }

    private fun readWav(f: File): FloatArray? {
        if (!f.exists() || f.length() < 100) return null
        val b = f.readBytes()
        fun u16(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        var i = 12; var rate = RATE; var ch = 1; var bits = 16
        while (i + 8 <= b.size) {
            val id = String(b, i, 4); val len = u32(i + 4)
            if (id == "fmt ") { ch = u16(i + 10); rate = u32(i + 12); bits = u16(i + 22) }
            if (id == "data") {
                if (bits != 16) return null
                val n = min(len, b.size - i - 8) / (2 * ch)
                val x = FloatArray(n) { k -> u16(i + 8 + k * 2 * ch).toShort().toFloat() / 32768f }
                // to our rate
                if (rate == RATE) return x
                val r = rate.toDouble() / RATE
                return FloatArray((n / r).toInt()) { j -> val p = j * r; val k = p.toInt(); if (k + 1 < n) (x[k] + (x[k + 1] - x[k]) * (p - k)).toFloat() else 0f }
            }
            i += 8 + len + (len and 1)
        }
        return null
    }

    /** f0 per frame by autocorrelation; the first peak near the best avoids octave errors */
    private fun f0Track(x: FloatArray, hop: Int, win: Int): DoubleArray {
        val peak = x.maxOfOrNull { abs(it) } ?: 0f
        val n = max(0, (x.size - win) / hop)
        return DoubleArray(n) { fi ->
            val c = fi * hop
            var e = 0.0; for (i in 0 until win) e += x[c + i] * x[c + i]
            if (sqrt(e / win) < 0.02 * peak) return@DoubleArray 0.0
            val l0 = RATE / 1100; val l1 = RATE / 70
            val cs = DoubleArray(l1 - l0)
            for (j in cs.indices) {
                val lag = l0 + j; var ab = 0.0; var aa = 0.0; var bb = 0.0
                for (i in 0 until win - lag) { val a = x[c + i]; val b = x[c + i + lag]; ab += a * b; aa += a * a; bb += b * b }
                cs[j] = ab / sqrt(aa * bb + 1e-9)
            }
            val mx = cs.maxOrNull() ?: 0.0
            if (mx < 0.5) return@DoubleArray 0.0
            for (j in 1 until cs.size - 1) if (cs[j] >= 0.9 * mx && cs[j] >= cs[j - 1] && cs[j] >= cs[j + 1]) return@DoubleArray RATE.toDouble() / (l0 + j)
            0.0
        }
    }

    /** trim silence, normalise, find the voiced part and one pitch mark per period */
    private fun analyse(raw: FloatArray): Voice? {
        val pk = raw.maxOfOrNull { abs(it) } ?: return null
        if (pk <= 0f) return null
        val a = raw.indexOfFirst { abs(it) > pk * 0.03f }; val b = raw.indexOfLast { abs(it) > pk * 0.03f }
        if (a < 0 || b - a < RATE / 20) return null
        val x = FloatArray(b - a + 1) { raw[a + it] / pk }
        val hop = 110; val win = 600
        val fs = f0Track(x, hop, win)
        val voiced = fs.indices.filter { fs[it] > 0 }
        if (voiced.size < 3) return null
        val v0 = voiced.first() * hop + win / 2; val v1 = voiced.last() * hop + win / 2
        val mean = voiced.map { fs[it] }.average()
        val marks = mutableListOf<Int>()
        var T = (RATE / fs[voiced.first()]).toInt()
        var i = v0 + (0 until min(T, x.size - v0)).maxByOrNull { abs(x[v0 + it]) }!!
        val sign = if (x[i] >= 0) 1f else -1f
        while (i < v1 && i < x.size) {
            marks.add(i)
            val k = ((i - win / 2) / hop).coerceIn(0, fs.size - 1)
            T = (RATE / (if (fs[k] > 0) fs[k] else mean)).toInt()
            val lo = i + (T * 0.8).toInt(); val hi = min(x.size - 1, i + (T * 1.2).toInt())
            if (hi <= lo) break
            i = (lo..hi).maxByOrNull { x[it] * sign }!!
        }
        if (marks.size < 4) return null
        val per = marks.zipWithNext { p, q -> q - p }.sorted()[marks.size / 2]
        return Voice(x, v0, marks.toIntArray(), per)
    }

    /** PSOLA: consonant copied, voiced part re-spaced to pitch f and stretched to len samples */
    private fun psola(vo: Voice, f: Double, len: Int, female: Boolean): FloatArray {
        val out = FloatArray(len + 4 * vo.period)
        val cons = min(min(vo.v0, (0.12 * RATE).toInt()), len / 2)
        for (i in 0 until cons) out[i] = vo.pcm[vo.v0 - cons + i]
        val T = RATE / f
        val m = vo.marks; val L = vo.period
        val form = if (female) 1.0 else 1.16          // >1: grains read slower = lower formants (male timbre)
        val gl = (2 * L * form).toInt()
        val hann = FloatArray(gl) { (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / (gl - 1))).toFloat() }
        var t = cons.toDouble()
        val span = max(1.0, (len - cons).toDouble())
        while (t < len) {
            val frac = (t - cons) / span
            val ti = m.first() + frac * (m.last() - m.first())
            var k = 0; var bd = Int.MAX_VALUE
            for (j in m.indices) { val d = abs(m[j] - ti.toInt()); if (d < bd) { bd = d; k = j } }
            val c = m[k]; val s = t.toInt() - gl / 2
            for (g in 0 until gl) {
                val src = c - L + g / form                // read the grain (slower for a male voice)
                val si = src.toInt(); if (si < 0 || si + 1 >= vo.pcm.size) continue
                val v = vo.pcm[si] + (vo.pcm[si + 1] - vo.pcm[si]) * (src - si).toFloat()
                val o = s + g; if (o in 0 until out.size) out[o] += v * hann[g]
            }
            t += T
        }
        val res = out.copyOf(len)
        val fade = min(len / 4, (0.04 * RATE).toInt())
        for (i in 0 until fade) res[len - 1 - i] *= i.toFloat() / fade
        val att = min(len / 8, 120)
        for (i in 0 until att) res[i] *= i.toFloat() / att
        return res
    }

    /** consonant length of a syllable: it is sung this much before the beat */
    private fun preRoll(c: Context, step: Char): Int = prepare(c)?.get(step)?.let { min(it.v0, (0.12 * RATE).toInt()) } ?: 0

    private fun sing(c: Context, step: Char, f: Double, len: Int, female: Boolean): FloatArray {
        val vo = prepare(c)?.get(step) ?: return synth(step, f, len, female)
        val y = psola(vo, f, len, female)
        val pk = y.maxOfOrNull { abs(it) } ?: 1f
        if (pk > 0f) for (i in y.indices) y[i] = y[i] / pk * 0.5f
        return y
    }

    /** phrases = runs of notes without a rest between them */
    private fun phrases(m: List<TNote>): List<List<TNote>> {
        val out = mutableListOf<MutableList<TNote>>()
        for (n in m) {
            val last = out.lastOrNull()?.last()
            if (last == null || n.t - (last.t + last.d) > 0.08) out.add(mutableListOf(n)) else out.last().add(n)
        }
        return out
    }

    /** render the melody to a WAV file; returns false if nothing to sing */
    fun render(c: Context, melody: List<TNote>, out: File, composer: String = ""): Boolean {
        if (melody.isEmpty()) return false
        val keepOctave = Regex("바흐|bach|스칼라티|scarlatti", RegexOption.IGNORE_CASE).containsMatchIn(composer)
        prepare(c)   // make the voice first (TTS); also tells how early each consonant starts
        val total = ((melody.last().t + melody.last().d + 0.4) * RATE).toInt()
        val mixb = FloatArray(total)
        for (ph in phrases(melody)) {
            val mids = ph.map { it.midi }.sorted(); val med = mids[mids.size / 2]
            val female = med >= 60                                     // one voice for the whole phrase
            val lo = if (female) 60 else 43; val hi = if (female) 81 else 65
            // whole-octave shift for the phrase that puts most notes in the voice's range
            val shift = if (keepOctave) 0 else listOf(0, -12, 12, -24, 24).maxByOrNull { s -> ph.count { it.midi + s in lo..hi } * 10 - abs(s) / 12 } ?: 0
            for ((k, n) in ph.withIndex()) {
                val f = 440.0 * 2.0.pow((n.midi + shift - 69) / 12.0)
                // legato: hold until the next note of the phrase starts (small overlap for a smooth join)
                val endT = if (k + 1 < ph.size) ph[k + 1].t + 0.015 else n.t + n.d
                val pre = preRoll(c, n.step)
                val len = max(1, ((endT - n.t) * RATE).toInt() + pre); val a = max(0, (n.t * RATE).toInt() - pre)
                val v = sing(c, n.step, f, len, female)
                val loud = (0.4 + 0.6 * n.vel / 112.0).toFloat()          // sing softer in p, louder in f
                for (i in v.indices) v[i] *= loud
                for (i in v.indices) if (a + i < total) mixb[a + i] += v[i]
            }
        }
        val peak = max(1e-6f, mixb.maxOf { abs(it) }); val g = 0.85f / peak
        writeWav(out, FloatArray(total) { mixb[it] * g })
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

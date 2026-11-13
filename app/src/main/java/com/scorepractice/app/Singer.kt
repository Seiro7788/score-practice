package com.scorepractice.app

import android.content.Context
import java.io.File
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
 * Sings the melody with note names, made by a small voice synthesizer:
 * every note is generated directly at its exact pitch (no re-tuning of recorded speech),
 * and held smoothly for its whole length (no looping).
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
    private fun sing(step: Char, f: Double, len: Int, female: Boolean): FloatArray {
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
                val len = max(1, ((endT - n.t) * RATE).toInt()); val a = (n.t * RATE).toInt()
                val v = sing(n.step, f, len, female)
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

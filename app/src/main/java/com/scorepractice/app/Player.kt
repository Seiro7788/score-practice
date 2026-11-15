package com.scorepractice.app

import android.content.Context
import android.media.MediaPlayer
import java.io.ByteArrayOutputStream
import java.io.File

/** what to play from the MusicXML */
class PlayChoice(var hands: Int = 2,          // 0 = right, 1 = left, 2 = both
                 var from: Int = 0, var to: Int = 0,   // printed measure numbers, 0 = whole piece
                 var loop: Boolean = false,
                 var fromIdx: Int = -1, var toIdx: Int = -1)   // measure indices in the file (tapped on the score view)

/** an event on the play timeline (seconds) */
class TNote(val t: Double, val d: Double, val midi: Int, val step: Char, val hand: Int, val vel: Int = 80)

object Timeline {
    /** notes in play order, in seconds at the given tempo (quarter = bpm) */
    fun order(sc: XScore, ch: PlayChoice): List<Int> =
        if (ch.fromIdx >= 0) (ch.fromIdx..(if (ch.toIdx >= ch.fromIdx) ch.toIdx else sc.measures.size - 1)).toList()
        else if (ch.from > 0 || ch.to > 0) {
            val a = if (ch.from > 0) sc.indexOf(ch.from).coerceAtLeast(0) else 0
            val b = if (ch.to > 0) sc.indexOf(ch.to).let { if (it < 0) sc.measures.size - 1 else it } else sc.measures.size - 1
            (a..maxOf(a, b)).toList()
        } else sc.order()

    /** (measure index, start second) for following the music on the score */
    fun measureTimes(sc: XScore, ch: PlayChoice, bpm: Int): List<Pair<Int, Double>> {
        var t = 0.0; val spq = 60.0 / bpm
        return order(sc, ch).map { mi -> (mi to t).also { t += sc.measures[mi].length * spq } }
    }

    /** ornaments = true for the piano: grace notes, trills, mordents, turns and tremolos are played out
     *  (the singing voice leaves them out) */
    fun build(sc: XScore, ch: PlayChoice, bpm: Int, ornaments: Boolean = false): List<TNote> {
        val order = order(sc, ch)
        val byMeasure = sc.notes.groupBy { it.measure }
        val dyn = Dynamics.curve(sc)          // loudness from p / f marks and hairpins
        val acc = Dynamics.accents(sc).groupBy({ it.first }, { it.second })   // sf / fz: louder at that moment
        val spq = 60.0 / bpm; val out = mutableListOf<TNote>(); var t0 = 0.0
        for (mi in order) {
            for (n in byMeasure[mi] ?: emptyList()) {
                if (ch.hands != 2 && n.hand != ch.hands) continue
                val sfz = if (acc[mi]?.any { kotlin.math.abs(it - n.onset) < 0.13 } == true) 26 else 0
                val v = (Dynamics.at(dyn, mi, n.onset) - (if (n.hand == 1) 6 else 0) + sfz + n.accent).coerceIn(20, 127)
                if (ornaments) ornament(sc, n, t0 + n.onset * spq, n.dur * spq, spq, v, out)
                else out.add(TNote(t0 + n.onset * spq, n.dur * spq, n.midi, n.step, n.hand, v))
            }
            t0 += sc.measures[mi].length * spq
        }
        return out.sortedBy { it.t }
    }

    private val PC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
    private const val LETTERS = "CDEFGAB"

    /** the next written note above (dir 1) or below (dir -1): in the key, unless the ornament says otherwise */
    private fun helper(sc: XScore, n: XNote, dir: Int): Int {
        val oct = (n.midi - n.alter - (PC[n.step] ?: 0)) / 12 - 1
        var i = LETTERS.indexOf(n.step) + dir; var o = oct
        if (i > 6) { i = 0; o++ }; if (i < 0) { i = 6; o-- }
        val letter = LETTERS[i]
        val fifths = sc.measures.getOrNull(n.measure)?.fifths ?: 0
        val keyAlter = if (fifths > 0 && "FCGDAEB".indexOf(letter) < fifths) 1 else if (fifths < 0 && "BEADGCF".indexOf(letter) < -fifths) -1 else 0
        return 12 * (o + 1) + (PC[letter] ?: 0) + (n.ornAlter ?: keyAlter)
    }

    private fun ornament(sc: XScore, n: XNote, s: Double, d: Double, spq: Double, v: Int, out: MutableList<TNote>) {
        fun add(t: Double, len: Double, m: Int, vel: Int = v) { if (len > 0.005) out.add(TNote(maxOf(0.0, t), len, m, n.step, n.hand, vel.coerceIn(1, 127))) }
        // grace notes: quick, just before the beat
        if (n.graces.isNotEmpty()) {
            val g = (d * 0.25 / n.graces.size).coerceIn(0.035, 0.075)
            n.graces.forEachIndexed { i, m -> add(s - (n.graces.size - i) * g, g * 0.95, m, v - 6) }
        }
        val q = (0.125 * spq).coerceIn(0.055, 0.1)          // speed of a trill / tremolo note
        when {
            n.orn == "trill-mark" && d >= q * 3 -> {           // main, upper, main, upper … ending on the main note
                val up = helper(sc, n, 1); var t = s; var k = 0
                val count = maxOf(3, (d / q).toInt()).let { if (it % 2 == 0) it - 1 else it }
                val step = d / count
                while (k < count) { add(t, step * 0.97, if (k % 2 == 0) n.midi else up, if (k % 2 == 0) v else v - 8); t += step; k++ }
            }
            n.orn == "mordent" || n.orn == "inverted-mordent" || (n.orn == "trill-mark") -> {
                val aux = helper(sc, n, if (n.orn == "mordent") -1 else 1); val a = minOf(0.07, d / 4)
                add(s, a, n.midi); add(s + a, a, aux, v - 8); add(s + 2 * a, d - 2 * a, n.midi)
            }
            n.orn == "turn" || n.orn == "inverted-turn" -> {
                val first = helper(sc, n, if (n.orn == "turn") 1 else -1); val last = helper(sc, n, if (n.orn == "turn") -1 else 1)
                val a = minOf(0.08, d / 5)
                add(s, a, first, v - 6); add(s + a, a, n.midi); add(s + 2 * a, a, last, v - 6); add(s + 3 * a, d - 3 * a, n.midi)
            }
            n.trem > 0 -> {                                     // repeated notes
                val sub = (spq / (1 shl (n.trem - 1)) / 2).coerceAtLeast(0.05); var t = s
                while (t < s + d - 0.01) { add(t, minOf(sub, s + d - t) * 0.95, n.midi); t += sub }
            }
            else -> add(s, d, n.midi)
        }
    }

    /** one note per onset: the highest (melody) */
    fun melody(notes: List<TNote>): List<TNote> {
        val out = mutableListOf<TNote>()
        for (g in notes.groupBy { (it.t * 1000).toLong() }.toSortedMap().values) out.add(g.maxByOrNull { it.midi }!!)
        // a note may not ring into the next one
        for (i in 0 until out.size - 1) if (out[i].t + out[i].d > out[i + 1].t) out[i] = TNote(out[i].t, out[i + 1].t - out[i].t, out[i].midi, out[i].step, out[i].hand, out[i].vel)
        return out
    }
}

/** writes a standard MIDI file (piano) and plays it with the phone's built-in synthesizer */
object MidiPlayer {
    private fun vlq(o: ByteArrayOutputStream, v0: Int) {
        var v = v0; val b = mutableListOf(v and 0x7f); v = v shr 7
        while (v > 0) { b.add((v and 0x7f) or 0x80); v = v shr 7 }
        for (x in b.reversed()) o.write(x)
    }

    fun write(notes: List<TNote>, f: File) {
        val tpq = 480; val tps = tpq * 2.0      // 1 s = 2 quarters at 120 bpm: times are already in seconds
        data class E(val tick: Int, val on: Boolean, val midi: Int, val vel: Int)
        val ev = mutableListOf<E>()
        for (n in notes) {
            val a = (n.t * tps).toInt(); val b = maxOf(a + 1, ((n.t + n.d * 0.97) * tps).toInt())
            ev.add(E(a, true, n.midi, n.vel)); ev.add(E(b, false, n.midi, 0))
        }
        ev.sortWith(compareBy({ it.tick }, { if (it.on) 1 else 0 }))
        val tr = ByteArrayOutputStream()
        // tempo 120 (500000 us per quarter), piano
        tr.write(byteArrayOf(0, 0xFF.toByte(), 0x51, 3, 0x07, 0xA1.toByte(), 0x20)); tr.write(byteArrayOf(0, 0xC0.toByte(), 0))
        var last = 0
        for (e in ev) { vlq(tr, e.tick - last); last = e.tick; tr.write(if (e.on) 0x90 else 0x80); tr.write(e.midi.coerceIn(0, 127)); tr.write(e.vel) }
        vlq(tr, tpq); tr.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        val body = tr.toByteArray()
        val o = ByteArrayOutputStream()
        o.write("MThd".toByteArray()); o.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, (tpq shr 8).toByte(), (tpq and 0xff).toByte()))
        o.write("MTrk".toByteArray()); val L = body.size
        o.write(byteArrayOf((L shr 24).toByte(), (L shr 16).toByte(), (L shr 8).toByte(), L.toByte())); o.write(body)
        f.writeBytes(o.toByteArray())
    }

    fun player(c: Context, f: File, loop: Boolean): MediaPlayer =
        MediaPlayer().apply { setDataSource(f.absolutePath); isLooping = loop; prepare() }
}

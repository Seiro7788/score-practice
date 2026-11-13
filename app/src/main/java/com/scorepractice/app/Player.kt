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

    fun build(sc: XScore, ch: PlayChoice, bpm: Int): List<TNote> {
        val order = order(sc, ch)
        val byMeasure = sc.notes.groupBy { it.measure }
        val dyn = Dynamics.curve(sc)          // loudness from p / f marks and hairpins
        val spq = 60.0 / bpm; val out = mutableListOf<TNote>(); var t0 = 0.0
        for (mi in order) {
            for (n in byMeasure[mi] ?: emptyList()) {
                if (ch.hands != 2 && n.hand != ch.hands) continue
                val v = (Dynamics.at(dyn, mi, n.onset) - if (n.hand == 1) 6 else 0).coerceIn(20, 127)
                out.add(TNote(t0 + n.onset * spq, n.dur * spq, n.midi, n.step, n.hand, v))
            }
            t0 += sc.measures[mi].length * spq
        }
        return out.sortedBy { it.t }
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

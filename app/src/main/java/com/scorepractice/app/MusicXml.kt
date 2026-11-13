package com.scorepractice.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** a note from the MusicXML, times in quarter notes from the start of its measure */
class XNote(val measure: Int, val onset: Double, var dur: Double, val midi: Int, val step: Char, val alter: Int, val hand: Int)

/** one measure (index in file order) */
class XMeasure(val number: String, var length: Double = 0.0, var forward: Boolean = false, var backward: Boolean = false,
               var times: Int = 2, val endings: MutableSet<Int> = mutableSetOf(), var fifths: Int = 0)

class XScore(val measures: List<XMeasure>, val notes: List<XNote>) {
    /** play order of measure indices with repeats and 1st/2nd endings unfolded */
    fun order(): List<Int> {
        val out = mutableListOf<Int>(); var i = 0; var start = 0; var pass = 1
        val done = mutableMapOf<Int, Int>()
        while (i < measures.size && out.size < 5000) {
            val m = measures[i]
            if (m.forward && i != start) { start = i; pass = 1 }          // a new repeated section begins
            if (m.endings.isNotEmpty() && pass !in m.endings) { i++; continue }   // 1st / 2nd ending not for this pass
            out.add(i)
            if (m.backward) {
                val c = done[i] ?: 1
                if (c < m.times) { done[i] = c + 1; pass = c + 1; i = start; continue }
                done.remove(i); pass = 1; start = i + 1
            }
            i++
        }
        return out
    }

    /** index of the measure printed with this number (first one) */
    fun indexOf(number: Int): Int = measures.indexOfFirst { it.number.takeWhile { c -> c.isDigit() }.toIntOrNull() == number }
}

object MusicXml {
    /** .musicxml / .xml, or compressed .mxl (zip) */
    fun load(f: File): XScore = f.inputStream().use { inp ->
        val head = ByteArray(2); val bi = inp.buffered(); bi.mark(4); bi.read(head); bi.reset()
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) parse(unzipRoot(bi)) else parse(bi)
    }

    private fun unzipRoot(inp: InputStream): InputStream {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(inp).use { z -> while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) files[e.name] = z.readBytes() } }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        val root = container?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val bytes = files[root] ?: files.entries.firstOrNull { !it.key.startsWith("META-INF") && it.key.endsWith(".xml") }?.value
            ?: throw IllegalArgumentException("MXL 안에 악보가 없습니다")
        return bytes.inputStream()
    }

    private val STEP_PC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    fun parse(inp: InputStream): XScore {
        pendingEnding = emptySet()
        val p = Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(inp, null)
        val measures = mutableListOf<XMeasure>(); val notes = mutableListOf<XNote>()
        var part = -1; var mi = -1; var divisions = 1.0; var pos = 0.0; var lastStart = 0.0; var maxPos = 0.0; var fifths = 0
        // note being read
        var inNote = false; var isChord = false; var isRest = false; var isGrace = false
        var step = 'C'; var alter = 0; var octave = 4; var dur = 0.0; var staff = 1; var tieStop = false; var tieStart = false
        var inBackup = false; var inForward = false; var text = StringBuilder()
        val openTies = mutableMapOf<Int, XNote>()   // midi -> note waiting for its tie
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (p.name) {
                        "part" -> { part++; mi = -1; divisions = 1.0; openTies.clear() }
                        "measure" -> {
                            mi++; pos = 0.0; maxPos = 0.0; lastStart = 0.0
                            if (part == 0) measures.add(XMeasure(p.getAttributeValue(null, "number") ?: "${mi + 1}", fifths = fifths))
                        }
                        "note" -> { inNote = true; isChord = false; isRest = false; isGrace = false; alter = 0; dur = 0.0; staff = 1; tieStop = false; tieStart = false }
                        "chord" -> if (inNote) isChord = true
                        "rest" -> if (inNote) isRest = true
                        "grace" -> if (inNote) isGrace = true
                        "tie" -> if (inNote) { val t = p.getAttributeValue(null, "type"); if (t == "stop") tieStop = true; if (t == "start") tieStart = true }
                        "backup" -> inBackup = true
                        "forward" -> inForward = true
                        "repeat" -> if (part == 0 && mi >= 0) {
                            val m = measures[mi]
                            if (p.getAttributeValue(null, "direction") == "forward") m.forward = true
                            else { m.backward = true; p.getAttributeValue(null, "times")?.toIntOrNull()?.let { m.times = it } }
                        }
                        "ending" -> if (part == 0 && mi >= 0) {
                            p.getAttributeValue(null, "number")?.split(',', ' ')?.mapNotNull { it.trim().toIntOrNull() }?.let { measures[mi].endings.addAll(it) }
                            if (p.getAttributeValue(null, "type") == "start") pendingEnding = measures[mi].endings.toSet()
                            else pendingEnding = emptySet()
                        }
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (p.name) {
                        "divisions" -> t.toDoubleOrNull()?.let { if (it > 0) divisions = it }
                        "fifths" -> t.toIntOrNull()?.let { fifths = it; if (part == 0 && mi >= 0) measures[mi].fifths = it }
                        "step" -> if (inNote && t.isNotEmpty()) step = t[0]
                        "alter" -> if (inNote) alter = t.toDoubleOrNull()?.toInt() ?: 0
                        "octave" -> if (inNote) octave = t.toIntOrNull() ?: 4
                        "staff" -> if (inNote) staff = t.toIntOrNull() ?: 1
                        "duration" -> {
                            val d = (t.toDoubleOrNull() ?: 0.0) / divisions
                            when {
                                inNote -> dur = d
                                inBackup -> pos -= d
                                inForward -> { pos += d; maxPos = maxOf(maxPos, pos) }
                            }
                        }
                        "backup" -> inBackup = false
                        "forward" -> inForward = false
                        "note" -> {
                            inNote = false
                            if (!isGrace) {
                                val start = if (isChord) lastStart else pos
                                if (!isChord) { lastStart = pos; pos += dur; maxPos = maxOf(maxPos, pos) }
                                if (!isRest) {
                                    val midi = 12 * (octave + 1) + (STEP_PC[step] ?: 0) + alter
                                    val hand = if (part >= 1 || staff >= 2) 1 else 0
                                    val prev = openTies[midi]
                                    if (tieStop && prev != null) { prev.dur += dur; if (!tieStart) openTies.remove(midi) }
                                    else {
                                        val n = XNote(mi, start, dur, midi, step, alter, hand); notes.add(n)
                                        if (tieStart) openTies[midi] = n
                                    }
                                }
                            }
                        }
                        "measure" -> {
                            if (mi in measures.indices) measures[mi].length = maxOf(measures[mi].length, maxPos)
                            if (part == 0 && mi in measures.indices && pendingEnding.isNotEmpty() && measures[mi].endings.isEmpty())
                                measures[mi].endings.addAll(pendingEnding)
                        }
                    }
                    text.setLength(0)
                }
            }
            ev = p.next()
        }
        // ending brackets continue over the measures between start and stop
        return XScore(measures, notes)
    }

    private var pendingEnding: Set<Int> = emptySet()
}

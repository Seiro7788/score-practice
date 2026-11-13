package com.scorepractice.app

/**
 * Chord names and Roman numerals from the MusicXML notes (e.g. C♯m / i, F♯m / iv6, G♯7 / V7).
 * Each beat is matched against chord shapes; equal neighbours are merged.
 */
object Harmony {
    class Label(val measure: Int, val beat: Double, val name: String, val roman: String)

    private class Shape(val iv: IntArray, val suffix: String, val upper: Boolean, val romanSuffix: String, val seventh: Boolean)
    private val SHAPES = listOf(
        Shape(intArrayOf(0, 4, 7), "", true, "", false),
        Shape(intArrayOf(0, 3, 7), "m", false, "", false),
        Shape(intArrayOf(0, 3, 6), "dim", false, "°", false),
        Shape(intArrayOf(0, 4, 8), "aug", true, "+", false),
        Shape(intArrayOf(0, 4, 7, 10), "7", true, "", true),
        Shape(intArrayOf(0, 4, 7, 11), "M7", true, "M", true),
        Shape(intArrayOf(0, 3, 7, 10), "m7", false, "", true),
        Shape(intArrayOf(0, 3, 6, 10), "m7♭5", false, "ø", true),
        Shape(intArrayOf(0, 3, 6, 9), "dim7", false, "°", true),
    )
    private val LETTERS = "CDEFGAB"
    private val NAT = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MAJ_DEG = mapOf(0 to "I", 2 to "II", 4 to "III", 5 to "IV", 7 to "V", 9 to "VI", 11 to "VII")
    private val MIN_DEG = mapOf(0 to "I", 2 to "II", 3 to "III", 5 to "IV", 7 to "V", 8 to "VI", 10 to "VII", 11 to "VII", 9 to "VI")

    private fun acc(a: Int) = when (a) { 1 -> "♯"; 2 -> "𝄪"; -1 -> "♭"; -2 -> "𝄫"; else -> "" }

    /** tonic pitch class and spelled name for a key signature */
    fun keyName(fifths: Int, minor: Boolean): Pair<Int, String> {
        val majors = listOf("C♭", "G♭", "D♭", "A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯")
        val minors = listOf("a♭", "e♭", "b♭", "f", "c", "g", "d", "a", "e", "b", "f♯", "c♯", "g♯", "d♯", "a♯")
        val i = (fifths + 7).coerceIn(0, 14)
        val name = if (minor) minors[i] else majors[i]
        val pc = ((fifths * 7) % 12 + 12) % 12
        return (if (minor) (pc + 9) % 12 else pc) to name
    }

    private fun roman(rootPc: Int, sh: Shape, tonic: Int, minor: Boolean, inv: Int): String {
        val d = (rootPc - tonic + 12) % 12
        val table = if (minor) MIN_DEG else MAJ_DEG
        val base = table[d] ?: (table[(d + 1) % 12]?.let { "♭$it" } ?: table[(d + 11) % 12]?.let { "♯$it" } ?: "?")
        val r = if (sh.upper) base else base.lowercase()
        // figures for inversions: triad 6 / 64, seventh 7 / 65 / 43 / 42
        val fig = if (!sh.seventh) listOf("", "6", "64")[inv.coerceIn(0, 2)] else listOf("7", "65", "43", "42")[inv.coerceIn(0, 3)]
        return r + sh.romanSuffix + fig
    }

    fun analyse(sc: XScore): List<Label> {
        val out = mutableListOf<Label>()
        val byM = sc.notes.groupBy { it.measure }
        var prevKey = ""
        for ((mi, m) in sc.measures.withIndex()) {
            val notes = byM[mi] ?: continue
            val beatLen = if (m.beatType == 8 && m.beats % 3 == 0 && m.beats > 3) 1.5 else 4.0 / m.beatType
            val len = if (m.length > 0) m.length else m.beats * 4.0 / m.beatType
            val (tonic, kname) = keyName(m.fifths, m.minor)
            var b = 0.0
            while (b < len - 1e-6) {
                val e = b + beatLen
                val w = DoubleArray(12); var bass: XNote? = null; var total = 0.0
                for (n in notes) {
                    val s = maxOf(n.onset, b); val t = minOf(n.onset + n.dur, e)
                    if (t <= s) continue
                    val pc = ((n.midi % 12) + 12) % 12
                    w[pc] += t - s; total += t - s
                    if (bass == null || n.midi < bass.midi) bass = n
                }
                if (total > 0 && w.count { it > 0 } >= 2) {
                    var best = -1e9; var br = 0; var bs = SHAPES[0]
                    for (r in 0 until 12) for (sh in SHAPES) {
                        val inS = BooleanArray(12); for (iv in sh.iv) inS[(r + iv) % 12] = true
                        var sc0 = 0.0
                        for (pc in 0 until 12) sc0 += if (inS[pc]) w[pc] else -0.7 * w[pc]
                        if (w[(r + sh.iv[1]) % 12] == 0.0) continue                     // no third: not this chord (octaves, open fifths)
                        if (w[r] == 0.0) sc0 -= 0.25 * total                              // no root
                        if (bass != null && (bass.midi % 12 + 12) % 12 == r) sc0 += 0.2 * total
                        if (sh.seventh) sc0 -= 0.12 * total                               // prefer the plain triad
                        if (sc0 > best) { best = sc0; br = r; bs = sh }
                    }
                    if (best > 0.25 * total) {
                        // spell the root as written in the score when possible
                        val sp = notes.filter { ((it.midi % 12) + 12) % 12 == br }.groupingBy { it.step to it.alter }.eachCount().maxByOrNull { it.value }?.key
                        val rootName = if (sp != null) "${sp.first}${acc(sp.second)}" else {
                            val flats = m.fifths < 0
                            val nat = NAT.indexOf(br)
                            if (nat >= 0) "${LETTERS[nat]}" else if (flats) "${LETTERS[NAT.indexOf(br + 1)]}♭" else "${LETTERS[NAT.indexOf(br - 1)]}♯"
                        }
                        val bpc = bass?.let { ((it.midi % 12) + 12) % 12 } ?: br
                        val inv = bs.iv.indexOfFirst { (br + it) % 12 == bpc }.coerceAtLeast(0)
                        val bassName = if (inv > 0 && bass != null) "/${bass.step}${acc(bass.alter)}" else ""
                        val name = rootName + bs.suffix + bassName
                        val rn = roman(br, bs, tonic, m.minor, inv)
                        val last = out.lastOrNull()
                        if (last == null || last.name != name) out.add(Label(mi, b, name, (if (kname != prevKey) "$kname: " else "") + rn))
                        prevKey = kname
                    }
                }
                b = e
            }
        }
        return out
    }
}

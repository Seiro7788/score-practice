package com.scorepractice.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-phone semitone colouring of one page (no reference score):
 *  staves -> noteheads -> accidentals (♯ ♭ ♮) -> barlines -> pitch from staff position and clef
 *  -> alteration (printed accidental, carried in the bar, key signature) -> colour by key colour.
 * Upper staff of each pair = treble clef, lower = bass clef (unless told otherwise).
 */
object AutoColor {
    // colours agreed for the coloured scores
    private val SHARP_BLACK = Color.rgb(255, 125, 125); private val SHARP_WHITE = Color.rgb(110, 35, 160)
    private val DSHARP_BLACK = Color.rgb(255, 185, 185); private val DSHARP_WHITE = Color.rgb(115, 105, 115)
    private val FLAT_BLACK = Color.rgb(80, 135, 255); private val FLAT_WHITE = Color.rgb(70, 190, 100)

    class Staff(val lines: FloatArray, val treble: Boolean) { val top get() = lines[0]; val bottom get() = lines[4] }
    class Head(val x: Float, val y: Float, val step: Int, val hollow: Boolean, val staff: Int)
    class Result(val fixes: List<Fix>, val staves: Int, val heads: Int, val coloured: Int)

    // ---------- helpers
    internal class Mask(val w: Int, val h: Int, val a: BooleanArray) {
        operator fun get(x: Int, y: Int) = x in 0 until w && y in 0 until h && a[y * w + x]
        val sum: IntArray by lazy {   // integral image (w+1) x (h+1)
            val s = IntArray((w + 1) * (h + 1))
            for (y in 0 until h) { var row = 0; for (x in 0 until w) { if (a[y * w + x]) row++; s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + row } }
            s
        }
        fun count(x0: Int, y0: Int, x1: Int, y1: Int): Int {   // [x0,x1) x [y0,y1)
            val a0 = max(0, x0); val b0 = max(0, y0); val a1 = min(w, x1); val b1 = min(h, y1)
            if (a1 <= a0 || b1 <= b0) return 0
            val W = w + 1; return sum[b1 * W + a1] - sum[b0 * W + a1] - sum[b1 * W + a0] + sum[b0 * W + a0]
        }
    }

    internal fun lum(c: Int) = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10

    // ---------- staves
    internal fun findStaves(ink: Mask): Pair<List<FloatArray>, Float> {
        val w = ink.w; val h = ink.h; val prof = FloatArray(h)
        for (y in 0 until h) { var n = 0; for (x in w / 10 until w * 9 / 10) if (ink.a[y * w + x]) n++; prof[y] = n / (w * 0.8f) }
        val lines = mutableListOf<Float>(); val runs = mutableListOf<Pair<Int, Int>>(); var y = 0
        while (y < h) {
            if (prof[y] > 0.45f) {
                val y0 = y; while (y < h && prof[y] > 0.45f) y++
                // a thick or slightly tilted line can break into two runs a pixel or two apart: one line
                if (runs.isNotEmpty() && y0 - runs.last().second <= 3) runs[runs.size - 1] = runs.last().first to (y - 1) else runs.add(y0 to (y - 1))
            } else y++
        }
        for ((a, b) in runs) lines.add((a + b) / 2f)
        val gaps = lines.zipWithNext { a, b -> b - a }.filter { it > 4 && it < h / 20f }.sorted()
        val sp = if (gaps.isEmpty()) w * 0.0085f else gaps[gaps.size / 2]
        val staves = mutableListOf<FloatArray>(); var i = 0
        while (i + 4 < lines.size) {
            val g = lines.subList(i, i + 5)
            if (g.zipWithNext { a, b -> b - a }.all { it > 0.75f * sp && it < 1.3f * sp }) { staves.add(g.toFloatArray()); i += 5 } else i++
        }
        return staves to sp
    }

    /** removes thin horizontal staff-line pixels (keeps pixels that belong to something above/below) */
    internal fun removeLines(ink: Mask, staves: List<FloatArray>, sp: Float): Mask {
        val a = ink.a.copyOf(); val w = ink.w
        val t = max(1, (sp * 0.12f).roundToInt())
        for (st in staves) for (ly in st) {
            val y0 = (ly - t).roundToInt(); val y1 = (ly + t).roundToInt()
            for (x in 0 until w) {
                if (!ink[x, y0 - 1 - 1] && !ink[x, y1 + 1 + 1]) for (yy in y0..y1) if (yy in 0 until ink.h) a[yy * w + x] = false
            }
        }
        return Mask(w, ink.h, a)
    }

    // ---------- noteheads
    private const val ANG = -0.35
    private val C = kotlin.math.cos(ANG); private val S = kotlin.math.sin(ANG)

    internal fun ellipseScore(m: Mask, cx: Float, cy: Float, a: Float, b: Float): Triple<Float, Float, Float> {
        var nF = 0; var inF = 0; var nR = 0; var inR = 0; var nC = 0; var inC = 0
        val ia = (a * 1.15f).toInt() + 1
        for (yy in (cy - ia).toInt()..(cy + ia).toInt()) for (xx in (cx - ia).toInt()..(cx + ia).toInt()) {
            val dx = xx - cx; val dy = yy - cy
            val u = dx * C + dy * S; val v = -dx * S + dy * C
            val q = (u * u) / (a * a) + (v * v) / (b * b)
            val k = m[xx, yy]
            if (q <= 1.0) { nF++; if (k) inF++ }
            if (q in 0.5..1.2) { nR++; if (k) inR++ }
            if (q < 0.22) { nC++; if (k) inC++ }
        }
        return Triple(if (nF > 0) inF.toFloat() / nF else 0f, if (nR > 0) inR.toFloat() / nR else 0f, if (nC > 0) inC.toFloat() / nC else 1f)
    }

    internal fun findHeads(m: Mask, staves: List<Staff>, sp: Float, left: IntArray): List<Head> {
        val a = 0.62f * sp; val b = 0.45f * sp
        val bw = (0.5f * sp).toInt(); val bh = (0.3f * sp).toInt()
        val out = mutableListOf<Head>()
        for ((si, st) in staves.withIndex()) {
            for (step in -7..15) {                       // ledger lines: about 3 above / 3 below
                val cy = st.top + step * sp / 2f
                val cand = mutableListOf<Triple<Float, Float, Boolean>>()   // x, score, hollow
                var x = left[si] + (2.5f * sp).toInt()
                while (x < m.w - bw) {
                    val box = m.count(x - bw, (cy - bh).toInt(), x + bw, (cy + bh).toInt()).toFloat() / ((2 * bw) * (2 * bh))
                    if (box > 0.28f) {
                        val (f, ring, core) = ellipseScore(m, x.toFloat(), cy, a, b)
                        if (f > 0.8f) cand.add(Triple(x.toFloat(), f, false))
                        else if (ring > 0.6f && core < 0.15f && f > 0.35f) cand.add(Triple(x.toFloat(), ring * 0.9f, true))
                    }
                    x += 1
                }
                // local maxima along x
                var i = 0
                while (i < cand.size) {
                    var j = i; var best = i
                    while (j + 1 < cand.size && cand[j + 1].first - cand[j].first <= 2f) { j++; if (cand[j].second > cand[best].second) best = j }
                    out.add(Head(cand[best].first, cy, step, cand[best].third, si))
                    i = j + 1
                }
            }
        }
        // a head found on two neighbouring steps: keep the stronger (re-score)
        val keep = mutableListOf<Head>()
        for (h in out.sortedByDescending { ellipseScore(m, it.x, it.y, a, b).let { s -> if (it.hollow) s.second else s.first } }) {
            if (keep.none { it.staff == h.staff && abs(it.x - h.x) < 0.75f * sp && abs(it.y - h.y) < 0.7f * sp }) keep.add(h)
        }
        return keep
    }

    // ---------- accidentals
    private class Tpl(val w: Int, val h: Int, val ones: IntArray, val n1: Int)

    private fun template(rows: String, f: Float): Tpl {
        val r = rows.split('|'); val h0 = r.size; val w0 = r[0].length
        val w = max(3, (w0 * f).roundToInt()); val h = max(3, (h0 * f).roundToInt())
        val ones = mutableListOf<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (r[min(h0 - 1, (y / f).toInt())][min(w0 - 1, (x / f).toInt())] == '1') ones.add(y * w + x)
        }
        return Tpl(w, h, ones.toIntArray(), ones.size)
    }

    /** Pearson correlation of a binary template with the window at (x0,y0) */
    private fun corr(m: Mask, t: Tpl, x0: Int, y0: Int): Float {
        val N = t.w * t.h; val nW = m.count(x0, y0, x0 + t.w, y0 + t.h)
        if (nW == 0) return 0f
        var n11 = 0
        for (o in t.ones) { val x = x0 + o % t.w; val y = y0 + o / t.w; if (m[x, y]) n11++ }
        val pT = t.n1.toFloat() / N; val pW = nW.toFloat() / N
        val cov = n11.toFloat() / N - pT * pW
        val d = sqrt(pT * (1 - pT) * pW * (1 - pW))
        return if (d > 0) cov / d else 0f
    }

    /** -1 flat, 0 natural, +1 sharp, null = none, for the head */
    private fun accidentalOf(m: Mask, h: Head, sp: Float, tp: Map<Int, Tpl>, minX: Int, heads: List<Head>): Int? {
        var best = 0.0f; var kind: Int? = null
        for ((k, t) in tp) {
            val th = if (k == 1) 0.58f else 0.62f
            // vertical anchor: sharp/natural centred on the head; flat's bowl (lower part) on the head
            val yc = if (k == -1) h.y - (t.h - t.h * 0.38f) else h.y - t.h / 2f
            val xr = (h.x - 0.55f * sp - t.w).toInt(); val xl = (h.x - 3.6f * sp).toInt()
            var x = max(xl, minX)
            while (x <= xr) {
                var y = (yc - 0.35f * sp).toInt()
                while (y <= (yc + 0.35f * sp).toInt()) {
                    val c = corr(m, t, x, y)
                    if (c > th && c > best) {
                        // another head between the sign and this head on the same line blocks it
                        val acx = x + t.w / 2f
                        val blocked = heads.any { o -> o !== h && o.staff == h.staff && abs(o.y - h.y) < 0.3f * sp && o.x > acx && o.x < h.x - 0.3f * sp }
                        if (!blocked) { best = c; kind = k }
                    }
                    y += 2
                }
                x += 2
            }
        }
        return kind
    }

    // ---------- barlines
    internal fun barlines(m: Mask, sys: List<Staff>, sp: Float): List<Float> {
        val y0 = sys.first().top.toInt(); val y1 = sys.last().bottom.toInt()
        val cov = FloatArray(m.w) { x -> var n = 0; for (y in y0..y1) if (m[x, y]) n++; n.toFloat() / (y1 - y0 + 1) }
        val xs = mutableListOf<Float>(); var x = 0
        while (x < m.w) {
            if (cov[x] > 0.92f) {
                val a = x; while (x < m.w && cov[x] > 0.92f) x++
                val side = max(if (a - 4 >= 0) cov[a - 4] else 0f, if (x + 3 < m.w) cov[x + 3] else 0f)
                if (x - a <= 0.5f * sp && side < 0.5f) xs.add((a + x) / 2f)
            } else x++
        }
        return xs
    }

    // ---------- main
    /** keySig: +n sharps / -n flats. lowerTreble: lower staff also uses treble clef. */
    fun colourPage(bm: Bitmap, page: Int, keySig: Int, lowerTreble: Boolean = false): Result {
        val w = bm.width; val h = bm.height; val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h)
        val ink = Mask(w, h, BooleanArray(px.size) { HeadFix.ink(px[it]) })
        val (lines, sp) = findStaves(Mask(w, h, BooleanArray(px.size) { lum(px[it]) < 160 }))
        if (lines.isEmpty()) return Result(emptyList(), 0, 0, 0)
        val staves = lines.mapIndexed { i, l -> Staff(l, i % 2 == 0 || lines.size == 1 || lowerTreble) }
        val nol = removeLines(ink, lines, sp)
        // left edge of each staff (where its lines begin)
        val left = IntArray(staves.size) { i ->
            val yl = staves[i].lines[2].roundToInt(); var x = 0
            while (x < w && !(ink[x, yl] && ink[x + (sp * 2).toInt(), yl])) x++; x
        }
        val heads = findHeads(nol, staves, sp, left)
        val f = sp / AccTemplates.SP
        val tp = mapOf(1 to template(AccTemplates.SHARP, f), -1 to template(AccTemplates.FLAT, f), 0 to template(AccTemplates.NAT, f))
        // key signature order
        val sharpOrder = "FCGDAEB"; val flatOrder = "BEADGCF"
        val keyAlt = IntArray(7)   // by letter index C..B
        val letters = "CDEFGAB"
        for (i in 0 until min(7, abs(keySig))) {
            val l = if (keySig > 0) sharpOrder[i] else flatOrder[i]
            keyAlt[letters.indexOf(l)] = if (keySig > 0) 1 else -1
        }
        val fixes = mutableListOf<Fix>(); var coloured = 0
        // systems = pairs of staves
        val systems = staves.indices.chunked(2)
        for (sys in systems) {
            val bars = barlines(nol, sys.map { staves[it] }, sp)
            for (si in sys) {
                val st = staves[si]
                // skip clef + key signature at the start of the line
                val minX = left[si] + ((3.2f + abs(keySig) * 0.95f) * sp).toInt()
                val hs = heads.filter { it.staff == si }.sortedBy { it.x }
                val carry = mutableMapOf<Pair<Int, Int>, Int>()   // (bar, diatonic) -> alteration
                for (hd in hs) {
                    val bar = bars.count { it < hd.x }
                    // diatonic number: treble top line = F5, bass top line = A3
                    val dTop = if (st.treble) 5 * 7 + 3 else 3 * 7 + 5
                    val d = dTop - hd.step
                    val letter = ((d % 7) + 7) % 7
                    val acc = if (hd.x > minX) accidentalOf(nol, hd, sp, tp, minX, hs) else null
                    val alt = when {
                        acc != null -> { carry[bar to d] = acc; acc }
                        carry.containsKey(bar to d) -> carry[bar to d]!!
                        else -> keyAlt[letter]
                    }
                    if (alt == 0) continue
                    val pc = (intArrayOf(0, 2, 4, 5, 7, 9, 11)[letter] + alt + 12) % 12
                    val black = pc in intArrayOf(1, 3, 6, 8, 10)
                    val col = when {
                        alt >= 2 -> if (black) DSHARP_BLACK else DSHARP_WHITE
                        alt == 1 -> if (black) SHARP_BLACK else SHARP_WHITE
                        else -> if (black) FLAT_BLACK else FLAT_WHITE
                    }
                    fixes.add(Fix(page, hd.x / w, hd.y / h, 0.62f * sp / w, col, hd.hollow, 0.45f * sp / w, auto = true))
                    coloured++
                }
            }
        }
        return Result(fixes, staves.size, heads.size, coloured)
    }
}

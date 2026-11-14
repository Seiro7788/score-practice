package com.scorepractice.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fingering for the MusicXML score:
 *  - read from the PDF: printed fingering digits (recognised) and the numbers typed in with the fingering mode,
 *    found in the same measure of the PDF and put on the matching notes
 *  - copied from a passage that is the same or similar (also transposed) and already has fingering
 * Both only make proposals; the user accepts, changes or drops them.
 */
object FingerRead {
    class Digit(val x: Float, val y: Float, val n: Int, val manual: Boolean)
    /** one measure of one piano system on a PDF page (pixels of the scanned page) */
    class PMeasure(val page: Int, val x0: Float, val x1: Float, val staves: List<FloatArray>, val sp: Float) {
        val cols = arrayOf(mutableListOf<Float>(), mutableListOf<Float>())   // notehead columns per staff (x)
        val digits = mutableListOf<Digit>()
        fun allCols(): Int = (cols[0] + cols[1]).sorted().fold(mutableListOf<Float>()) { acc, x -> if (acc.isEmpty() || x - acc.last() > 0.8f * sp) acc.add(x); acc }.size
    }

    // ---------------------------------------------------------------- page scan
    fun scanPage(bm: Bitmap, page: Int, manual: List<Finger>): List<PMeasure> {
        val w = bm.width; val h = bm.height; val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h)
        val ink = AutoColor.Mask(w, h, BooleanArray(px.size) { HeadFix.ink(px[it]) })
        val (lines, sp) = AutoColor.findStaves(AutoColor.Mask(w, h, BooleanArray(px.size) { AutoColor.lum(px[it]) < 160 }))
        if (lines.size < 2) return emptyList()
        val left = IntArray(lines.size) { i ->
            val yl = lines[i][2].roundToInt(); var x = 0
            while (x < w && !(ink[x, yl] && ink[x + (sp * 2).toInt(), yl])) x++; x
        }
        val right = IntArray(lines.size) { i ->
            val yl = lines[i][2].roundToInt(); var x = w - 1
            while (x > 0 && !(ink[x, yl] && ink[x - (sp * 2).toInt(), yl])) x--; x
        }
        // piano systems: two staves joined by a line at their left end
        val systems = mutableListOf<Pair<Int, Int>>(); var i = 0
        while (i + 1 < lines.size) {
            val a = lines[i]; val b = lines[i + 1]
            val y0 = a[0].toInt(); val y1 = b[4].toInt()
            var joined = false
            for (x in max(0, left[i] - sp.toInt() * 2)..min(w - 1, left[i] + sp.toInt() * 2)) {
                var n = 0; for (y in y0..y1) if (ink[x, y]) n++
                if (n > (y1 - y0 + 1) * 0.9f) { joined = true; break }
            }
            if (joined || b[0] - a[4] < 8 * sp) { systems.add(i to i + 1); i += 2 } else i++
        }
        val nol = AutoColor.removeLines(ink, lines, sp)
        val staves = lines.mapIndexed { k, l -> AutoColor.Staff(l, k % 2 == 0) }
        val heads = AutoColor.findHeads(nol, staves, sp, left)
        val out = mutableListOf<PMeasure>()
        val sysOf = mutableListOf<Pair<Pair<Int, Int>, MutableList<PMeasure>>>()
        for (sy in systems) {
            val a = staves[sy.first]; val b = staves[sy.second]
            val bars = AutoColor.barlines(nol, listOf(a, b), sp)
            val l = left[sy.first].toFloat(); val r = right[sy.first].toFloat()
            val edges = mutableListOf(l); bars.filter { it > l + 1.5f * sp }.forEach { edges.add(it) }
            if (r - edges.last() > 2 * sp) edges.add(r)
            val ms = mutableListOf<PMeasure>()
            for (k in 0 until edges.size - 1) if (edges[k + 1] - edges[k] > 1.5f * sp) {
                val pm = PMeasure(page, edges[k], edges[k + 1], listOf(a.lines, b.lines), sp)
                for ((si, st) in listOf(sy.first, sy.second).withIndex()) {
                    val xs = heads.filter { it.staff == st && it.x >= pm.x0 && it.x < pm.x1 }.map { it.x }.sorted()
                    for (x in xs) if (pm.cols[si].isEmpty() || x - pm.cols[si].last() > 0.8f * sp) pm.cols[si].add(x)
                }
                ms.add(pm)
            }
            out.addAll(ms); sysOf.add(sy to ms)
        }
        // digits: printed ones (recognised) + numbers typed in on this page
        val found = printedDigits(nol, lines, sp, left).toMutableList()
        for (g in manual) if (g.page == page) {
            val gx = g.x * w; val gy = g.y * h
            found.removeAll { abs(it.x - gx) < 0.8f * sp && abs(it.y - gy) < 0.8f * sp }
            found.add(Digit(gx, gy, g.n, true))
        }
        for (d in found) {
            // the system it belongs to: the nearest by height, then the measure by x
            val best = sysOf.minByOrNull { (sy, _) ->
                val top = lines[sy.first][0] - 4 * sp; val bot = lines[sy.second][4] + 4 * sp
                if (d.y < top) top - d.y else if (d.y > bot) d.y - bot else 0f
            } ?: continue
            val top = lines[best.first.first][0] - 6 * sp; val bot = lines[best.first.second][4] + 6 * sp
            if (d.y < top || d.y > bot) continue
            best.second.firstOrNull { d.x >= it.x0 - 0.3f * sp && d.x < it.x1 }?.digits?.add(d)
        }
        return out
    }

    /** connected pieces of ink the size of a fingering digit, recognised against the digit shapes */
    private fun printedDigits(m: AutoColor.Mask, lines: List<FloatArray>, sp: Float, left: IntArray): List<Digit> {
        val w = m.w; val h = m.h; val seen = BooleanArray(w * h); val out = mutableListOf<Digit>()
        val stack = IntArray(w * h + 16)
        val tpl = templates()
        for (start in 0 until w * h) {
            if (!m.a[start] || seen[start]) continue
            var sp0 = 0; stack[sp0++] = start; seen[start] = true
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var n = 0; var big = false
            val members = ArrayList<Int>()
            while (sp0 > 0) {
                val p = stack[--sp0]; val x = p % w; val y = p / w; n++
                if (!big) members.add(p)
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x1 - x0 > 2 * sp || y1 - y0 > 2 * sp) big = true
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val q = yy * w + xx
                    if (m.a[q] && !seen[q]) { seen[q] = true; if (sp0 < stack.size) stack[sp0++] = q }
                }
            }
            if (big) continue
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (bh < 0.6f * sp || bh > 1.35f * sp || bw < 0.15f * sp || bw > 1.0f * sp || bh < bw * 1.05f) continue
            val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
            val inside = lines.any { cy > it[0] - 0.25f * sp && cy < it[4] + 0.25f * sp }
            val near = lines.indices.minByOrNull { k -> min(abs(cy - lines[k][0]), abs(cy - lines[k][4])) } ?: continue
            if (cx < left[near] + 3 * sp) continue                      // measure numbers, brace, clef
            val crop = BooleanArray(bw * bh); for (p in members) crop[(p / w - y0) * bw + (p % w - x0)] = true
            val (ch, s) = classify(crop, bw, bh, tpl)
            if (ch == 'x' || s < (if (inside) 0.8f else 0.66f)) continue
            out.add(Digit(cx, cy, ch - '0', false))
        }
        return out
    }

    private class Tpl(val ch: Char, val v: FloatArray, val sum: Float)
    private var cache: List<Tpl>? = null
    private fun templates(): List<Tpl> = cache ?: FingerTemplates.ALL.map { s ->
        val v = FloatArray(FingerTemplates.W * FingerTemplates.H) { (s[it + 1] - '0') / 9f }; Tpl(s[0], v, v.sum())
    }.also { cache = it }

    /** the piece scaled to 16 cells high (keeping its shape), centred in 12 cells; best match by overlap */
    private fun classify(crop: BooleanArray, bw: Int, bh: Int, tpl: List<Tpl>): Pair<Char, Float> {
        val W = FingerTemplates.W; val H = FingerTemplates.H
        val s = H.toFloat() / bh; val nw = max(1, min(W, (bw * s).roundToInt())); val ox = (W - nw) / 2
        val v = FloatArray(W * H)
        for (j in 0 until H) for (i in 0 until nw) {
            val sx0 = i * bw.toFloat() / nw; val sx1 = (i + 1) * bw.toFloat() / nw
            val sy0 = j * bh.toFloat() / H; val sy1 = (j + 1) * bh.toFloat() / H
            var acc = 0f; var area = 0f
            var yy = sy0.toInt()
            while (yy < sy1 && yy < bh) {
                val fy = min(sy1, yy + 1f) - max(sy0, yy.toFloat())
                var xx = sx0.toInt()
                while (xx < sx1 && xx < bw) {
                    val fx = min(sx1, xx + 1f) - max(sx0, xx.toFloat()); val a = fx * fy
                    if (crop[yy * bw + xx]) acc += a; area += a; xx++
                }
                yy++
            }
            v[j * W + i + ox] = if (area > 0) acc / area else 0f
        }
        val vs = v.sum()
        val best = HashMap<Char, Float>()
        for (t in tpl) {
            var dot = 0f; for (k in v.indices) dot += v[k] * t.v[k]
            val sc = 2 * dot / (vs + t.sum + 1e-6f)
            if (sc > (best[t.ch] ?: 0f)) best[t.ch] = sc
        }
        val e = best.maxByOrNull { it.value } ?: return 'x' to 0f
        return e.key to e.value
    }

    // ---------------------------------------------------------------- measures of the XML ↔ measures of the PDF
    /** monotone matching of two sequences; cost(i, j) for a pair, gap for leaving one out; free ends on b if freeEnds */
    private fun alignSeq(n: Int, m: Int, cost: (Int, Int) -> Double, gap: Double, freeEnds: Boolean): IntArray {
        val D = Array(n + 1) { DoubleArray(m + 1) }; val B = Array(n + 1) { ByteArray(m + 1) }
        for (i in 1..n) { D[i][0] = i * gap; B[i][0] = 1 }
        for (j in 1..m) { D[0][j] = if (freeEnds) 0.0 else j * gap; B[0][j] = 2 }
        for (i in 1..n) for (j in 1..m) {
            val d = D[i - 1][j - 1] + cost(i - 1, j - 1); val u = D[i - 1][j] + gap; val l = D[i][j - 1] + if (freeEnds && i == n) 0.0 else gap
            if (d <= u && d <= l) { D[i][j] = d; B[i][j] = 0 } else if (u <= l) { D[i][j] = u; B[i][j] = 1 } else { D[i][j] = l; B[i][j] = 2 }
        }
        val map = IntArray(n) { -1 }; var i = n; var j = m
        while (i > 0 && j > 0) when (B[i][j].toInt()) { 0 -> { map[i - 1] = j - 1; i--; j-- }; 1 -> i--; else -> j-- }
        return map
    }

    /** XML measure index → index into pms (or -1) */
    fun alignMeasures(sc: XScore, pms: List<PMeasure>): IntArray {
        val byM = sc.notes.groupBy { it.measure }
        val fx = IntArray(sc.measures.size) { i -> (byM[i] ?: emptyList()).map { (it.onset * 96).roundToInt() }.toSet().size }
        val fp = IntArray(pms.size) { pms[it].allCols() }
        return alignSeq(fx.size, fp.size, { i, j -> abs(ln((fx[i] + 1.0) / (fp[j] + 1.0))) }, 1.3, true)
    }

    /** proposals for XML measures from..to (indices) from the PDF measures matched to them */
    fun fromPdf(sc: XScore, pms: List<PMeasure>, map: IntArray, from: Int, to: Int, have: List<XFinger>): List<XFinger> {
        val out = mutableListOf<XFinger>()
        for (mi in from..to) {
            val pj = map.getOrNull(mi) ?: -1; if (pj < 0) continue
            val pm = pms[pj]; val sp = pm.sp
            val mNotes = sc.notes.filter { it.measure == mi }
            for (d0 in pm.digits.groupBy { staffOf(pm, it.y) }) {
                val st = d0.key; val ds = d0.value
                val notes = mNotes.filter { it.hand == st }; if (notes.isEmpty()) continue
                val ts = notes.map { it.onset }.distinctBy { (it * 96).roundToInt() }.sorted()
                val cols = pm.cols[st]
                // notehead columns ↔ onsets (by order and relative position)
                val colT = IntArray(cols.size) { -1 }
                if (cols.isNotEmpty()) {
                    val c0 = cols.first(); val c1 = max(cols.last(), c0 + 1f); val t0 = ts.first(); val t1 = max(ts.last(), t0 + 1e-3)
                    val mp = alignSeq(cols.size, ts.size, { i, j ->
                        val a = if (cols.size == 1) 0.0 else (cols[i] - c0) / (c1 - c0).toDouble()
                        val b = if (ts.size == 1) 0.0 else (ts[j] - t0) / (t1 - t0)
                        abs(a - b) * 3 }, 0.6, false)
                    for (i in mp.indices) colT[i] = mp[i]
                }
                val byT = HashMap<Int, MutableList<Digit>>()
                // a printed 3 between the staves of a measure with triplets is most likely the triplet sign
                val triplets = ts.any { abs(it * 3 - (it * 3).roundToInt()) < 0.02 && abs(it * 2 - (it * 2).roundToInt()) > 0.1 }
                for (d in ds) {
                    if (triplets && d.n == 3 && !d.manual && d.y > pm.staves[0][4] && d.y < pm.staves[1][0]) continue
                    var ti = -1
                    val ci = cols.indices.minByOrNull { abs(cols[it] - d.x) }
                    if (ci != null && abs(cols[ci] - d.x) < 1.5f * sp) ti = colT[ci]
                    if (ti < 0) {   // no notehead found there: place by position within the measure
                        val rel = ((d.x - pm.x0) / (pm.x1 - pm.x0)).toDouble()
                        val len = max(sc.measures[mi].length, 1e-3)
                        ti = ts.indices.minByOrNull { abs(ts[it] / len - rel) } ?: -1
                    }
                    if (ti >= 0) byT.getOrPut(ti) { mutableListOf() }.add(d)
                }
                val mid = pm.staves[st][2]
                for ((ti, dl) in byT) {
                    val at = notes.filter { abs(it.onset - ts[ti]) < 0.01 }
                    val uniq = dl.distinctBy { (it.y / (0.5f * sp)).roundToInt() }
                    val above = uniq.map { it.y }.average() < mid
                    val dsorted = if (above) uniq.sortedBy { it.y } else uniq.sortedByDescending { it.y }
                    val nsorted = if (above) at.sortedByDescending { it.midi } else at.sortedBy { it.midi }
                    for (k in 0 until min(dsorted.size, nsorted.size)) {
                        val nt = nsorted[k]
                        val f = XFinger(mi, nt.onset, nt.midi, dsorted[k].n, true)
                        if (have.none { !it.p && it.same(f) } && out.none { it.same(f) }) out.add(f)
                    }
                }
            }
        }
        return out
    }

    private fun staffOf(pm: PMeasure, y: Float): Int {
        fun dist(s: FloatArray) = if (y < s[0]) s[0] - y else if (y > s[4]) y - s[4] else 0f
        return if (dist(pm.staves[0]) <= dist(pm.staves[1])) 0 else 1
    }

    // ---------------------------------------------------------------- same / similar passages
    /**
     * For each measure (per hand) in from..to with notes still without fingering: the measure of the same hand that
     * matches best (same notes, the same pattern transposed, or mostly the same) and has fingering → copy it.
     */
    fun fromSimilar(sc: XScore, have: List<XFinger>, from: Int, to: Int): List<XFinger> {
        val fixed = have.filter { !it.p }
        val byKey = HashMap<Long, Int>()
        fun key(m: Int, t: Double, k: Int) = (m.toLong() shl 32) or (((t * 96).roundToInt().toLong() and 0xFFFFF) shl 8) or k.toLong()
        for (f in fixed) byKey[key(f.m, f.t, f.k)] = f.n
        val groups = sc.notes.groupBy { it.measure to it.hand }
        val sources = groups.filter { (_, ns) -> ns.any { byKey.containsKey(key(it.measure, it.onset, it.midi)) } }
        val out = mutableListOf<XFinger>()
        for (mi in from..to) for (hand in 0..1) {
            val tgt = (groups[mi to hand] ?: continue).sortedWith(compareBy({ it.onset }, { -it.midi }))
            if (tgt.all { byKey.containsKey(key(mi, it.onset, it.midi)) }) continue
            var best: Pair<List<XNote>, Int>? = null; var bestScore = 0.0; var bestDist = Int.MAX_VALUE
            for ((k, src0) in sources) {
                if (k.second != hand || k.first == mi) continue
                val src = src0.sortedWith(compareBy({ it.onset }, { -it.midi }))
                val (score, shift) = similarity(tgt, src)
                val dist = abs(k.first - mi)
                if (score > bestScore + 1e-6 || (abs(score - bestScore) < 1e-6 && dist < bestDist)) { bestScore = score; best = src to shift; bestDist = dist }
            }
            val (src, shift) = best ?: continue
            if (bestScore < 0.8) continue
            for (n in tgt) {
                if (byKey.containsKey(key(mi, n.onset, n.midi))) continue
                val s = src.firstOrNull { abs(it.onset - n.onset) < 0.01 && it.midi == n.midi - shift } ?: continue
                val fn = byKey[key(s.measure, s.onset, s.midi)] ?: continue
                out.add(XFinger(mi, n.onset, n.midi, fn, true))
            }
        }
        return out
    }

    /** share of notes that line up (same onset, same pitch after one common transposition), and that transposition */
    private fun similarity(a: List<XNote>, b: List<XNote>): Pair<Double, Int> {
        val shifts = HashMap<Int, Int>()
        for (x in a) for (y in b) if (abs(x.onset - y.onset) < 0.01) shifts[x.midi - y.midi] = (shifts[x.midi - y.midi] ?: 0) + 1
        if (shifts.isEmpty()) return 0.0 to 0
        val c = shifts.values.max()
        val use = if ((shifts[0] ?: 0) == c) 0 else shifts.entries.first { it.value == c }.key
        return c.toDouble() / max(a.size, b.size) * (if (use == 0) 1.0 else 0.97) to use
    }
}

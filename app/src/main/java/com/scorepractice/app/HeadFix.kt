package com.scorepractice.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the notehead under a tap and recolours its own ink.
 * - size comes from the page itself (staff spacing), so the patch matches the printed notehead
 * - filled heads: every ink pixel inside the head is recoloured
 * - hollow heads (half / whole notes): only the outline is recoloured, the inside stays as printed
 */
object HeadFix {
    private const val ANG = -0.35  // noteheads lean up to the right (about -20°)
    private val C = cos(ANG); private val S = sin(ANG)

    /** ink = dark, or clearly coloured (already-coloured heads are light pinks/blues/greens) */
    fun ink(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val lum = (r * 3 + g * 6 + b) / 10; val sat = max(r, max(g, b)) - min(r, min(g, b))
        return lum < 170 || (sat > 55 && lum < 245)
    }

    /** staff spacing in pixels from long horizontal lines */
    fun staffSpace(bm: Bitmap): Float {
        val w = bm.width; val h = bm.height; val row = IntArray(w); val cnt = IntArray(h)
        for (y in 0 until h) {
            bm.getPixels(row, 0, w, 0, y, w, 1)
            var n = 0; for (x in w / 10 until w * 9 / 10) if (lumOf(row[x]) < 160) n++
            cnt[y] = n
        }
        val thr = (w * 0.8f * 0.5f).toInt()
        val lines = mutableListOf<Float>(); var y = 0
        while (y < h) {
            if (cnt[y] > thr) { val y0 = y; while (y < h && cnt[y] > thr) y++; lines.add((y0 + y - 1) / 2f) } else y++
        }
        val d = lines.zipWithNext { a, b -> b - a }.filter { it > 4 && it < w * 0.03f }.sorted()
        return if (d.isEmpty()) w * 0.0085f else d[d.size / 2]
    }

    private fun lumOf(c: Int) = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10

    class Head(val x: Float, val y: Float, val a: Float, val b: Float, val hollow: Boolean)

    /** search near (px, py) for the best filled or hollow head; returns pixel geometry */
    fun detect(bm: Bitmap, px: Float, py: Float, sp: Float): Head? {
        val W = bm.width; val H = bm.height
        val R = (sp * 2.2f).toInt()
        val x0 = max(0, (px - R).toInt()); val y0 = max(0, (py - R).toInt())
        val x1 = min(W, (px + R).toInt()); val y1 = min(H, (py + R).toInt())
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        val ww = x1 - x0; val hh = y1 - y0; val pix = IntArray(ww * hh)
        bm.getPixels(pix, 0, ww, x0, y0, ww, hh)
        val m = BooleanArray(pix.size) { ink(pix[it]) }
        var best: Head? = null; var bestScore = 0.0
        val reach = sp * 0.9f
        for (scale in floatArrayOf(0.9f, 1.0f, 1.12f)) {
            val a = 0.66f * sp * scale; val b = 0.47f * sp * scale
            var cy = max(py - reach, y0 + b); while (cy <= min(py + reach, y1 - b)) {
                var cx = max(px - reach, x0 + a); while (cx <= min(px + reach, x1 - a)) {
                    var inF = 0; var nF = 0; var ring = 0; var nR = 0; var core = 0; var nC = 0
                    val ia = a.toInt() + 1
                    for (yy in (cy - ia).toInt()..(cy + ia).toInt()) for (xx in (cx - ia).toInt()..(cx + ia).toInt()) {
                        if (xx < x0 || yy < y0 || xx >= x1 || yy >= y1) continue
                        val dx = xx - cx; val dy = yy - cy
                        val u = dx * C + dy * S; val v = -dx * S + dy * C
                        val q = (u * u) / (a * a) + (v * v) / (b * b)
                        val k = m[(yy - y0) * ww + (xx - x0)]
                        if (q <= 1.0) { nF++; if (k) inF++ }
                        if (q in 0.55..1.25) { nR++; if (k) ring++ }
                        if (q < 0.3) { nC++; if (k) core++ }
                    }
                    if (nF > 0 && nR > 0) {
                        val filled = inF.toDouble() / nF
                        val hollowS = ring.toDouble() / nR - (if (nC > 0) core.toDouble() / nC else 1.0)
                        // closeness to the tap breaks ties
                        val near = 1.0 - sqrt(((cx - px) * (cx - px) + (cy - py) * (cy - py)).toDouble()) / (sp * 4)
                        if (filled > 0.8 && filled * near > bestScore) { bestScore = filled * near; best = Head(cx, cy, a, b, false) }
                        if (hollowS > 0.35 && (hollowS + 0.3) * near > bestScore) { bestScore = (hollowS + 0.3) * near; best = Head(cx, cy, a, b, true) }
                    }
                    cx += 1f
                }
                cy += 1f
            }
        }
        return best
    }

    /** recolour the head's ink in place (bm must be mutable). geometry in pixels. */
    fun apply(bm: Bitmap, cx: Float, cy: Float, a: Float, b: Float, hollow: Boolean, color: Int) {
        val ia = (a * 1.25f).toInt() + 2
        val x0 = max(0, (cx - ia).toInt()); val y0 = max(0, (cy - ia).toInt())
        val x1 = min(bm.width, (cx + ia).toInt() + 1); val y1 = min(bm.height, (cy + ia).toInt() + 1)
        if (x1 <= x0 || y1 <= y0) return
        val ww = x1 - x0; val hh = y1 - y0; val pix = IntArray(ww * hh)
        bm.getPixels(pix, 0, ww, x0, y0, ww, hh)
        val ea = a * 1.12f; val eb = b * 1.15f
        for (yy in y0 until y1) for (xx in x0 until x1) {
            val dx = xx - cx; val dy = yy - cy
            val u = dx * C + dy * S; val v = -dx * S + dy * C
            val q = (u * u) / (ea * ea) + (v * v) / (eb * eb)
            val inside = if (hollow) q <= 1.0 && q >= 0.32 else q <= 1.0
            val i = (yy - y0) * ww + (xx - x0)
            if (inside && ink(pix[i])) pix[i] = color
        }
        bm.setPixels(pix, 0, ww, x0, y0, ww, hh)
    }
}

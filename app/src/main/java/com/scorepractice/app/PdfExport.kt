package com.scorepractice.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import java.io.OutputStream

/** writes the score back to a PDF with the chosen layers burned in */
object PdfExport {
    class Options(var fixes: Boolean = true, var ink: Boolean = true, var fingers: Boolean = true, var ranges: Boolean = false)

    /** scale = pixels per PDF point (2.5 ≈ 180 dpi) */
    fun export(pdf: PdfRenderer, lock: Any, data: ScoreData, opt: Options, out: OutputStream,
               scale: Float = 2.5f, progress: (Int, Int) -> Unit) {
        val doc = PdfDocument()
        try {
            val n = synchronized(lock) { pdf.pageCount }
            for (p in 0 until n) {
                progress(p + 1, n)
                var pw = 0; var ph = 0
                val bm = synchronized(lock) {
                    pdf.openPage(p).use { pg ->
                        pw = pg.width; ph = pg.height
                        val b = Bitmap.createBitmap((pw * scale).toInt(), (ph * scale).toInt(), Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE); pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); b
                    }
                }
                drawLayers(bm, data, p, opt)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, p + 1).create())
                page.canvas.drawBitmap(bm, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page); bm.recycle()
            }
            doc.writeTo(out)
        } finally { doc.close() }
    }

    fun drawLayers(bm: Bitmap, d: ScoreData, page: Int, opt: Options) {
        val W = bm.width.toFloat(); val H = bm.height.toFloat()
        val c = Canvas(bm)
        if (opt.fixes) for (f in d.fixes) if (f.page == page) {
            if (f.manual) HeadFix.drawManual(c, f.x * W, f.y * H, f.r * W, f.rb * W, f.hollow, f.color)
            else HeadFix.apply(bm, f.x * W, f.y * H, f.r * W, f.rb * W, f.hollow, f.color)
        }
        fun sx(x: Float) = x * W
        fun sy(y: Float) = y * H
        if (opt.ranges) {
            val fill = Paint(); val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = W * 0.002f }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = W * 0.016f; isFakeBoldText = true }
            for (r in d.ranges) if (r.page == page && d.layer(r.layer)?.visible == true) {
                val col = d.layer(r.layer)?.color ?: Color.RED
                fill.color = Color.argb(35, Color.red(col), Color.green(col), Color.blue(col)); edge.color = col
                val rr = RectF(sx(r.x0), sy(r.y0), sx(r.x1), sy(r.y1)); c.drawRect(rr, fill); c.drawRect(rr, edge)
                label.color = col
                c.drawText((if (r.name.isNotEmpty()) r.name + "  " else "") + "${r.count}/${r.target}", rr.left + W * 0.004f, rr.top - W * 0.004f, label)
            }
        }
        if (opt.fingers) {
            val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = Color.rgb(20, 30, 120) }
            val halo = Paint(fp).apply { color = Color.WHITE; style = Paint.Style.STROKE }
            for (g in d.fingers) if (g.page == page) {
                val ts = g.s * W * 1.7f * d.fingerScale; fp.textSize = ts; halo.textSize = ts; halo.strokeWidth = ts * 0.18f
                val yb = sy(g.y) + ts * 0.36f
                c.drawText(g.n.toString(), sx(g.x), yb, halo); c.drawText(g.n.toString(), sx(g.x), yb, fp)
            }
        }
        if (opt.ink) {
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            Highlight.bake(bm, d.strokes.filter { it.page == page && Highlight.isHighlighter(it) && d.strokeVisible(it) })
            for (s in d.strokes) if (s.page == page && !Highlight.isHighlighter(s) && d.strokeVisible(s)) {
                ink.color = s.color; ink.strokeWidth = s.width * W
                ink.strokeCap = if (Color.alpha(s.color) < 255) Paint.Cap.BUTT else Paint.Cap.ROUND
                val path = Path(); path.moveTo(sx(s.pts[0]), sy(s.pts[1]))
                if (s.pts.size == 2) path.lineTo(sx(s.pts[0]) + 0.5f, sy(s.pts[1]))
                var i = 2; while (i + 1 < s.pts.size) { path.lineTo(sx(s.pts[i]), sy(s.pts[i + 1])); i += 2 }
                c.drawPath(path, ink)
            }
        }
    }
}

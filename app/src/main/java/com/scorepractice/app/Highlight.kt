package com.scorepractice.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * Highlighter strokes are multiplied into the page itself: white paper takes the colour,
 * black notes and lines stay black (the marker goes "under" the print and the pen).
 */
object Highlight {
    fun isHighlighter(s: Stroke) = Color.alpha(s.color) < 255

    /** the see-through stored colour as the opaque tint it gives on white paper */
    fun tint(c: Int): Int {
        val a = Color.alpha(c) / 255f
        fun ch(v: Int) = (255 - (255 - v) * a).toInt()
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    fun bake(bm: Bitmap, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        val c = Canvas(bm); val W = bm.width.toFloat(); val H = bm.height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        for (s in strokes) {
            p.color = tint(s.color); p.strokeWidth = s.width * W
            p.strokeCap = if (s.pts.size == 4) Paint.Cap.BUTT else Paint.Cap.ROUND   // straight lines: flat ends
            val path = Path(); path.moveTo(s.pts[0] * W, s.pts[1] * H)
            if (s.pts.size == 2) path.lineTo(s.pts[0] * W + 0.5f, s.pts[1] * H)
            var i = 2; while (i + 1 < s.pts.size) { path.lineTo(s.pts[i] * W, s.pts[i + 1] * H); i += 2 }
            c.drawPath(path, p)
        }
    }
}

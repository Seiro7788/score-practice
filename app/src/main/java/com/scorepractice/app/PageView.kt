package com.scorepractice.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * one score page: the PDF bitmap + practice ranges (per layer) + handwriting.
 * modes: VIEW (tap a range = +1, long-press = edit), RANGE (drag a box = new range), PEN (draw).
 */
class PageView(context: Context, private val host: Host) : View(context) {
    interface Host {
        val data: ScoreData
        val mode: Mode
        val penColor: Int
        val penWidth: Float
        val erasing: Boolean
        fun onDoubleTap(page: Int)
        fun onRangeTap(r: Range)
        fun onRangeLongPress(r: Range)
        fun onRangeDrawn(page: Int, x0: Float, y0: Float, x1: Float, y1: Float)
        fun onInkChanged()
    }

    enum class Mode { VIEW, RANGE, PEN }

    var page = -1
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    private val dst = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint()
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; isFakeBoldText = true }
    private val labelBg = Paint().apply { color = Color.argb(210, 255, 255, 255) }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val cur = mutableListOf<Float>()
    private var dragStart: Pair<Float, Float>? = null
    private var dragNow: Pair<Float, Float>? = null

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { host.onDoubleTap(page); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            hit(e)?.let { host.onRangeTap(it) }; return true
        }
        override fun onLongPress(e: MotionEvent) { hit(e)?.let { host.onRangeLongPress(it) } }
    })

    private fun layoutPage() {
        val b = bitmap ?: return
        val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * s; val h = b.height * s
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun nx(x: Float) = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
    private fun ny(y: Float) = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
    private fun sx(x: Float) = dst.left + x * dst.width()
    private fun sy(y: Float) = dst.top + y * dst.height()

    private fun visibleRanges() = host.data.ranges.filter { it.page == page && host.data.layer(it.layer)?.visible == true }

    private fun hit(e: MotionEvent): Range? {
        val x = nx(e.x); val y = ny(e.y)
        return visibleRanges().filter { x in it.x0..it.x1 && y in it.y0..it.y1 }
            .minByOrNull { (it.x1 - it.x0) * (it.y1 - it.y0) }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val b = bitmap
        if (b == null) { c.drawColor(Color.WHITE); return }
        layoutPage()
        c.drawBitmap(b, null, dst, bmpPaint)
        // practice ranges
        for (r in visibleRanges()) {
            val col = host.data.layer(r.layer)?.color ?: Color.RED
            val done = r.count >= r.target && r.target > 0
            fill.color = Color.argb(if (done) 30 else 45, Color.red(col), Color.green(col), Color.blue(col))
            edge.color = col
            val rr = RectF(sx(r.x0), sy(r.y0), sx(r.x1), sy(r.y1))
            c.drawRect(rr, fill); c.drawRect(rr, edge)
            val t = (if (r.name.isNotEmpty()) r.name + "  " else "") + "${r.count}/${r.target}" + if (done) " ✓" else ""
            val tw = label.measureText(t)
            c.drawRect(rr.left, rr.top - 40f, rr.left + tw + 16f, rr.top, labelBg)
            label.color = col; c.drawText(t, rr.left + 8f, rr.top - 9f, label)
        }
        // handwriting
        if (host.data.showInk) {
            for (s in host.data.strokes) if (s.page == page) drawStroke(c, s.pts, s.color, s.width)
        }
        if (cur.size >= 4) drawStroke(c, cur.toFloatArray(), host.penColor, host.penWidth)
        // range being dragged
        val a = dragStart; val z = dragNow
        if (a != null && z != null) {
            edge.color = Color.MAGENTA
            c.drawRect(minOf(a.first, z.first), minOf(a.second, z.second), maxOf(a.first, z.first), maxOf(a.second, z.second), edge)
        }
    }

    private fun drawStroke(c: Canvas, pts: FloatArray, color: Int, w: Float) {
        ink.color = color; ink.strokeWidth = w * dst.width()
        val p = Path(); p.moveTo(sx(pts[0]), sy(pts[1]))
        var i = 2
        while (i + 1 < pts.size) { p.lineTo(sx(pts[i]), sy(pts[i + 1])); i += 2 }
        c.drawPath(p, ink)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (host.mode) {
            Mode.PEN -> {
                if (e.pointerCount > 1) return true
                if (host.erasing) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) erase(nx(e.x), ny(e.y))
                    if (e.actionMasked == MotionEvent.ACTION_UP) host.onInkChanged()
                    return true
                }
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { cur.clear(); cur.add(nx(e.x)); cur.add(ny(e.y)) }
                    MotionEvent.ACTION_MOVE -> {
                        for (h in 0 until e.historySize) { cur.add(nx(e.getHistoricalX(h))); cur.add(ny(e.getHistoricalY(h))) }
                        cur.add(nx(e.x)); cur.add(ny(e.y))
                    }
                    MotionEvent.ACTION_UP -> {
                        if (cur.size >= 4) host.data.strokes.add(Stroke(page, host.penColor, host.penWidth, cur.toFloatArray()))
                        cur.clear(); host.onInkChanged()
                    }
                }
                invalidate(); return true
            }
            Mode.RANGE -> {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dragStart = e.x to e.y; dragNow = e.x to e.y }
                    MotionEvent.ACTION_MOVE -> dragNow = e.x to e.y
                    MotionEvent.ACTION_UP -> {
                        val a = dragStart!!
                        val x0 = nx(minOf(a.first, e.x)); val x1 = nx(maxOf(a.first, e.x))
                        val y0 = ny(minOf(a.second, e.y)); val y1 = ny(maxOf(a.second, e.y))
                        dragStart = null; dragNow = null
                        if (x1 - x0 > 0.03f && y1 - y0 > 0.01f) host.onRangeDrawn(page, x0, y0, x1, y1)
                    }
                    MotionEvent.ACTION_CANCEL -> { dragStart = null; dragNow = null }
                }
                invalidate(); return true
            }
            Mode.VIEW -> return gd.onTouchEvent(e)
        }
        return true
    }

    private fun erase(x: Float, y: Float) {
        val r = 0.02f
        val gone = host.data.strokes.filter { s ->
            s.page == page && (0 until s.pts.size / 2).any { i ->
                val dx = s.pts[2 * i] - x; val dy = s.pts[2 * i + 1] - y; dx * dx + dy * dy < r * r
            }
        }
        if (gone.isNotEmpty()) { host.data.strokes.removeAll(gone); invalidate() }
    }
}

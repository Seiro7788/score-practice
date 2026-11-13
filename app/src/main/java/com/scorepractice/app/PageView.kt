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
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * one score page: the PDF bitmap + practice ranges (per layer) + handwriting.
 * two fingers: pinch to zoom / move (any mode). one finger when zoomed in (VIEW): move the page.
 * VIEW: tap a range = select it (count with the +1 button), long-press = edit, double-tap = start writing.
 * RANGE: drag a box = new range.  PEN: draw; double-tap = finish writing.
 * FIX: tap a notehead = cover it with the chosen colour (tap a patch again = remove it).
 */
class PageView(context: Context, private val host: Host) : View(context) {
    interface Host {
        val data: ScoreData
        val mode: Mode
        val penColor: Int
        val penWidth: Float
        val erasing: Boolean
        val selectedRange: String?
        fun onDoubleTap(page: Int)
        fun onRangeTap(r: Range)
        fun onRangeLongPress(r: Range)
        fun onRangeDrawn(page: Int, x0: Float, y0: Float, x1: Float, y1: Float)
        fun onInkChanged()
        fun onZoomChanged(zoomed: Boolean)
        fun onFixTap(page: Int, x: Float, y: Float, head: HeadFix.Head?, w: Int, h: Int)
    }

    enum class Mode { VIEW, RANGE, PEN, FIX }

    var page = -1
    /** the page as rendered from the PDF; [bitmap] is that page with colour fixes applied */
    var orig: Bitmap? = null
        set(v) { field = v; sp = 0f; refreshFixes() }
    var bitmap: Bitmap? = null
        private set
    private var sp = 0f

    fun refreshFixes() {
        val o = orig
        if (o == null) { bitmap = null; invalidate(); return }
        val mine = if (host.data.showFixes) host.data.fixes.filter { it.page == page } else emptyList()
        bitmap = if (mine.isEmpty()) o else o.copy(Bitmap.Config.ARGB_8888, true).also { bm ->
            for (f in mine) HeadFix.apply(bm, f.x * bm.width, f.y * bm.height, f.r * bm.width, f.rb * bm.width, f.hollow, f.color)
        }
        invalidate()
    }

    // zoom: screen = base * z + (tx, ty)
    private var z = 1f; private var tx = 0f; private var ty = 0f
    val zoomed get() = z > 1.01f
    fun resetZoom() { z = 1f; tx = 0f; ty = 0f; invalidate() }

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
    private var multi = false            // a two-finger gesture is (or was) in progress
    private var lastX = 0f; private var lastY = 0f
    // pen-mode double tap
    private var downT = 0L; private var downX = 0f; private var downY = 0f; private var lastTapT = 0L
    private var pendingDot: Runnable? = null

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nz = (z * d.scaleFactor).coerceIn(1f, 5f); val f = nz / z
            tx = d.focusX - (d.focusX - tx) * f; ty = d.focusY - (d.focusY - ty) * f; z = nz
            clamp(); invalidate(); host.onZoomChanged(zoomed); return true
        }
    })

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { host.onDoubleTap(page); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { hit(e)?.let { host.onRangeTap(it) }; return true }
        override fun onLongPress(e: MotionEvent) { if (!multi) hit(e)?.let { host.onRangeLongPress(it) } }
    })

    private fun clamp() {
        if (z <= 1.01f) { z = 1f; tx = 0f; ty = 0f; return }
        tx = tx.coerceIn(width - width * z, 0f); ty = ty.coerceIn(height - height * z, 0f)
    }

    private fun layoutPage() {
        val b = bitmap ?: return
        val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * s; val h = b.height * s
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    // screen -> base -> normalized page coords
    private fun bx(x: Float) = (x - tx) / z
    private fun by(y: Float) = (y - ty) / z
    private fun nx(x: Float) = ((bx(x) - dst.left) / dst.width()).coerceIn(0f, 1f)
    private fun ny(y: Float) = ((by(y) - dst.top) / dst.height()).coerceIn(0f, 1f)
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
        c.drawColor(Color.WHITE)
        if (b == null) return
        layoutPage()
        c.save(); c.translate(tx, ty); c.scale(z, z)
        c.drawBitmap(b, null, dst, bmpPaint)
        for (r in visibleRanges()) {
            val col = host.data.layer(r.layer)?.color ?: Color.RED
            val done = r.count >= r.target && r.target > 0
            val sel = r.id == host.selectedRange
            fill.color = Color.argb(if (sel) 70 else if (done) 30 else 45, Color.red(col), Color.green(col), Color.blue(col))
            edge.color = col; edge.strokeWidth = (if (sel) 9f else 4f) / z
            val rr = RectF(sx(r.x0), sy(r.y0), sx(r.x1), sy(r.y1))
            c.drawRect(rr, fill); c.drawRect(rr, edge)
            val t = (if (r.name.isNotEmpty()) r.name + "  " else "") + "${r.count}/${r.target}" + if (done) " ✓" else ""
            label.textSize = 34f / z
            val tw = label.measureText(t)
            c.drawRect(rr.left, rr.top - 40f / z, rr.left + tw + 16f / z, rr.top, labelBg)
            label.color = col; c.drawText(t, rr.left + 8f / z, rr.top - 9f / z, label)
        }
        if (host.data.showInk) for (s in host.data.strokes) if (s.page == page) drawStroke(c, s.pts, s.color, s.width)
        if (cur.size >= 2) drawStroke(c, cur.toFloatArray(), host.penColor, host.penWidth)
        val a = dragStart; val e = dragNow
        if (a != null && e != null) {
            edge.color = Color.MAGENTA; edge.strokeWidth = 4f / z
            c.drawRect(sx(minOf(a.first, e.first)), sy(minOf(a.second, e.second)), sx(maxOf(a.first, e.first)), sy(maxOf(a.second, e.second)), edge)
        }
        c.restore()
    }

    private fun drawStroke(c: Canvas, pts: FloatArray, color: Int, w: Float) {
        ink.color = color; ink.strokeWidth = w * dst.width()
        val p = Path(); p.moveTo(sx(pts[0]), sy(pts[1]))
        if (pts.size == 2) p.lineTo(sx(pts[0]) + 0.5f, sy(pts[1]))
        var i = 2
        while (i + 1 < pts.size) { p.lineTo(sx(pts[i]), sy(pts[i + 1])); i += 2 }
        c.drawPath(p, ink)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        val am = e.actionMasked
        if (am == MotionEvent.ACTION_DOWN) { multi = false; lastX = e.x; lastY = e.y }
        if (am == MotionEvent.ACTION_POINTER_DOWN) {
            multi = true; parent?.requestDisallowInterceptTouchEvent(true)
            cur.clear(); dragStart = null; dragNow = null; invalidate()
        }
        if (multi || e.pointerCount > 1) {
            // two-finger move while pinching
            if (am == MotionEvent.ACTION_MOVE && e.pointerCount > 1) {
                val fx = (e.getX(0) + e.getX(1)) / 2; val fy = (e.getY(0) + e.getY(1)) / 2
                if (lastX != 0f) { tx += fx - lastX; ty += fy - lastY; clamp(); invalidate() }
                lastX = fx; lastY = fy
            }
            if (am == MotionEvent.ACTION_POINTER_UP) lastX = 0f
            if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL) multi = false
            return true
        }
        when (host.mode) {
            Mode.PEN -> return penTouch(e)
            Mode.FIX -> {
                if (am == MotionEvent.ACTION_DOWN) { downT = e.eventTime; downX = e.x; downY = e.y }
                if (zoomed && am == MotionEvent.ACTION_MOVE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    tx += e.x - lastX; ty += e.y - lastY; clamp(); invalidate()
                }
                lastX = e.x; lastY = e.y
                if (am == MotionEvent.ACTION_UP && hypot(e.x - downX, e.y - downY) < 20f) {
                    val o = orig
                    if (o != null) {
                        if (sp == 0f) sp = HeadFix.staffSpace(o)
                        val head = HeadFix.detect(o, nx(e.x) * o.width, ny(e.y) * o.height, sp)
                        host.onFixTap(page, nx(e.x), ny(e.y), head, o.width, o.height)
                    }
                }
                return true
            }
            Mode.RANGE -> {
                when (am) {
                    MotionEvent.ACTION_DOWN -> { dragStart = nx(e.x) to ny(e.y); dragNow = dragStart }
                    MotionEvent.ACTION_MOVE -> dragNow = nx(e.x) to ny(e.y)
                    MotionEvent.ACTION_UP -> {
                        val a = dragStart
                        if (a != null) {
                            val bx2 = nx(e.x); val by2 = ny(e.y)
                            val x0 = minOf(a.first, bx2); val x1 = maxOf(a.first, bx2)
                            val y0 = minOf(a.second, by2); val y1 = maxOf(a.second, by2)
                            if (x1 - x0 > 0.03f && y1 - y0 > 0.01f) host.onRangeDrawn(page, x0, y0, x1, y1)
                        }
                        dragStart = null; dragNow = null
                    }
                    MotionEvent.ACTION_CANCEL -> { dragStart = null; dragNow = null }
                }
                invalidate(); return true
            }
            Mode.VIEW -> {
                if (zoomed) {  // one finger moves the zoomed page
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (am == MotionEvent.ACTION_MOVE) { tx += e.x - lastX; ty += e.y - lastY; clamp(); invalidate() }
                    lastX = e.x; lastY = e.y
                }
                return gd.onTouchEvent(e)
            }
        }
    }

    private fun penTouch(e: MotionEvent): Boolean {
        if (host.erasing) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) erase(nx(e.x), ny(e.y))
            if (e.actionMasked == MotionEvent.ACTION_UP) host.onInkChanged()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downT = e.eventTime; downX = e.x; downY = e.y
                cur.clear(); cur.add(nx(e.x)); cur.add(ny(e.y))
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) { cur.add(nx(e.getHistoricalX(h))); cur.add(ny(e.getHistoricalY(h))) }
                cur.add(nx(e.x)); cur.add(ny(e.y))
            }
            MotionEvent.ACTION_UP -> {
                val tap = e.eventTime - downT < 250 && hypot(e.x - downX, e.y - downY) < 20f
                if (tap) {
                    pendingDot?.let { removeCallbacks(it) }; pendingDot = null
                    if (e.eventTime - lastTapT < 350) {  // double tap: finish writing
                        lastTapT = 0L; cur.clear(); invalidate(); host.onDoubleTap(page); return true
                    }
                    lastTapT = e.eventTime
                    val dot = floatArrayOf(cur[0], cur[1])
                    val r = Runnable { host.data.strokes.add(Stroke(page, host.penColor, host.penWidth, dot)); host.onInkChanged() }
                    pendingDot = r; postDelayed(r, 350)
                } else if (cur.size >= 4) {
                    host.data.strokes.add(Stroke(page, host.penColor, host.penWidth, cur.toFloatArray())); host.onInkChanged()
                }
                cur.clear()
            }
        }
        invalidate(); return true
    }

    private fun erase(x: Float, y: Float) {
        val r = 0.02f / z
        val gone = host.data.strokes.filter { s ->
            s.page == page && (0 until s.pts.size / 2).any { i ->
                val dx = s.pts[2 * i] - x; val dy = s.pts[2 * i + 1] - y; abs(dx) < r && abs(dy) < r
            }
        }
        if (gone.isNotEmpty()) { host.data.strokes.removeAll(gone); invalidate() }
    }
}

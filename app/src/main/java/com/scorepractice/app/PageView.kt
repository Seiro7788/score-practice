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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot

/**
 * one score page: the PDF bitmap + practice ranges (per layer) + handwriting.
 * two fingers: pinch to zoom / move (any mode). one finger swipe always turns the page.
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
        fun onFingerAdd(f: Finger)
        fun onFingerRemove(f: Finger)
        fun onFingerMoved(f: Finger)
        fun onFingerExit()
        fun onUndoGesture()
    }

    enum class Mode { VIEW, RANGE, PEN, FIX, FINGER }

    /** a horizontal slice [y0, y1] (0..1) of a page; a screen shows one or more slices stacked */
    class Seg(val page: Int, val y0: Float, val y1: Float)

    var pos = -1                     // adapter position
    var segs: List<Seg> = emptyList()
        set(v) { field = v; refreshFixes() }
    val pagesShown get() = segs.map { it.page }.distinct()
    /** page currently being drawn / touched, and its full-page rect in base coords */
    var page = -1
        private set
    private val origs = mutableMapOf<Int, Bitmap>()     // as rendered from the PDF
    private val shown = mutableMapOf<Int, Bitmap>()     // with colour fixes applied
    private val sps = mutableMapOf<Int, Float>()

    fun setOrig(p: Int, b: Bitmap?) {
        if (b == null) origs.remove(p) else origs[p] = b
        sps.remove(p); refreshFixes()
    }

    fun clearBitmaps() { origs.clear(); shown.clear(); sps.clear() }

    fun refreshFixes() {
        shown.clear()
        for ((p, o) in origs) {
            val mine = if (host.data.showFixes) host.data.fixes.filter { it.page == p } else emptyList()
            shown[p] = if (mine.isEmpty()) o else o.copy(Bitmap.Config.ARGB_8888, true).also { bm ->
                for (f in mine) HeadFix.apply(bm, f.x * bm.width, f.y * bm.height, f.r * bm.width, f.rb * bm.width, f.hollow, f.color)
            }
        }
        invalidate()
    }

    private val segRects = mutableListOf<RectF>()   // visible part of each slice
    private val pageRects = mutableListOf<RectF>()  // whole page of each slice (larger, clipped)
    private var segY0 = 0f; private var segY1 = 1f
    private var touchSeg = 0

    private fun select(i: Int) {
        if (i !in segs.indices || i !in pageRects.indices) return
        page = segs[i].page; dst.set(pageRects[i]); segY0 = segs[i].y0; segY1 = segs[i].y1
    }

    private fun selectAt(screenY: Float) {
        layoutPage(); val y = by(screenY)
        var i = segRects.indexOfFirst { y >= it.top && y <= it.bottom }
        if (i < 0) i = if (segRects.isNotEmpty() && y > segRects.last().bottom) segRects.size - 1 else 0
        touchSeg = i; select(i)
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
    private var multiT = 0L; private var multiZ = 1f; private var multiMoved = 0f
    private var lastX = 0f; private var lastY = 0f
    // pen-mode double tap
    private var downT = 0L; private var downX = 0f; private var downY = 0f; private var lastTapT = 0L
    private var pendingDot: Runnable? = null
    // fingering: press where the number goes, then swipe. angle (counter-clockwise from right):
    // 1 = 0°, 2 ≈ 34°, 3 ≈ 68°, 4 ≈ 101°, 5 = 135°  (±15°);  left (180° ±15°) = leave fingering mode
    private var fStart: Pair<Float, Float>? = null
    private var fNow: Pair<Float, Float>? = null
    private var fHit: Finger? = null
    private var fDragging = false
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = Color.rgb(20, 30, 120) }
    private val fingerHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = Color.WHITE; style = Paint.Style.STROKE
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val guideBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(230, 0, 90); strokeWidth = 3f }

    companion object {
        val FINGER_ANGLES = floatArrayOf(0f, 33.75f, 67.5f, 101.25f, 135f)
        const val TOL = 15f
        /** 1..5, 0 = leave mode, -2 = cancel (225°), -1 = nothing */
        fun choice(dx: Float, dy: Float): Int {
            var a = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
            if (a < -90f) a += 360f
            if (abs(a - 180f) <= TOL) return 0
            if (abs(a - 225f) <= TOL) return -2
            FINGER_ANGLES.forEachIndexed { i, t -> if (abs(a - t) <= TOL) return i + 1 }
            return -1
        }
    }

    private fun staffSpaceNorm(): Float {
        val o = origs[page] ?: return 0.0085f
        val sp = sps.getOrPut(page) { HeadFix.staffSpace(o) }
        return sp / o.width
    }

    private fun fingerAt(x: Float, y: Float): Finger? {
        val r = 1.3f * staffSpaceNorm()
        return host.data.fingers.filter { it.page == page && abs(it.x - x) < r && abs((it.y - y) * dst.height() / dst.width()) < r * 1.2f }
            .minByOrNull { abs(it.x - x) + abs(it.y - y) }
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nz = (z * d.scaleFactor).coerceIn(1f, 5f); val f = nz / z
            tx = d.focusX - (d.focusX - tx) * f; ty = d.focusY - (d.focusY - ty) * f; z = nz
            clamp(); invalidate(); host.onZoomChanged(zoomed); return true
        }
    })

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { selectAt(e.y); host.onDoubleTap(page); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { selectAt(e.y); hit(e)?.let { host.onRangeTap(it) }; return true }
        override fun onLongPress(e: MotionEvent) { selectAt(e.y); if (!multi) hit(e)?.let { host.onRangeLongPress(it) } }
    })

    private fun clamp() {
        if (z <= 1.01f) { z = 1f; tx = 0f; ty = 0f; return }
        tx = tx.coerceIn(width - width * z, 0f); ty = ty.coerceIn(height - height * z, 0f)
    }

    /** stack the slices at one common width, fitted into the view */
    private fun layoutPage() {
        segRects.clear(); pageRects.clear()
        if (segs.isEmpty()) return
        val ref = origs.values.firstOrNull()
        val aspect = segs.map { (origs[it.page] ?: ref)?.let { b -> b.height.toFloat() / b.width } ?: 1.414f }
        val total = segs.indices.sumOf { ((segs[it].y1 - segs[it].y0) * aspect[it]).toDouble() }.toFloat()
        val w = minOf(width.toFloat(), height / total); var top = (height - total * w) / 2; val left = (width - w) / 2
        for (i in segs.indices) {
            val full = aspect[i] * w; val h = (segs[i].y1 - segs[i].y0) * full
            segRects.add(RectF(left, top, left + w, top + h))
            pageRects.add(RectF(left, top - segs[i].y0 * full, left + w, top - segs[i].y0 * full + full))
            top += h
        }
    }

    // screen -> base -> normalized page coords
    private fun bx(x: Float) = (x - tx) / z
    private fun by(y: Float) = (y - ty) / z
    private fun nx(x: Float) = ((bx(x) - dst.left) / dst.width()).coerceIn(0f, 1f)
    private fun ny(y: Float) = ((by(y) - dst.top) / dst.height()).coerceIn(segY0, segY1)
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
        c.drawColor(Color.WHITE)
        layoutPage()
        c.save(); c.translate(tx, ty); c.scale(z, z)
        for (i in segs.indices) {
        val b = shown[segs[i].page] ?: continue
        select(i)
        c.save(); c.clipRect(segRects[i])
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
        if (host.data.showFingers || host.mode == Mode.FINGER) for (g in host.data.fingers) if (g.page == page) {
            val ts = g.s * dst.width() * 1.7f * host.data.fingerScale
            fingerPaint.textSize = ts; fingerHalo.textSize = ts; fingerHalo.strokeWidth = ts * 0.18f
            val yb = sy(g.y) + ts * 0.36f
            c.drawText(g.n.toString(), sx(g.x), yb, fingerHalo); c.drawText(g.n.toString(), sx(g.x), yb, fingerPaint)
        }
        if (host.data.showInk) for (s in host.data.strokes) if (s.page == page) drawStroke(c, s.pts, s.color, s.width)
        if (i == touchSeg && cur.size >= 2) drawStroke(c, cur.toFloatArray(), host.penColor, host.penWidth)
        val a = dragStart; val e = dragNow
        if (i == touchSeg && a != null && e != null) {
            edge.color = Color.MAGENTA; edge.strokeWidth = 4f / z
            c.drawRect(sx(minOf(a.first, e.first)), sy(minOf(a.second, e.second)), sx(maxOf(a.first, e.first)), sy(maxOf(a.second, e.second)), edge)
        }
        c.restore()
        if (i > 0) { edge.color = Color.rgb(150, 150, 150); edge.strokeWidth = 3f / z
            c.drawLine(segRects[i].left, segRects[i].top, segRects[i].right, segRects[i].top, edge) }
        }
        c.restore()
        select(touchSeg)
        drawFingerGuide(c)
    }

    /** fan of the five numbers around the pressed point while swiping */
    private fun drawFingerGuide(c: Canvas) {
        val s0 = fStart ?: return; val n = fNow ?: return
        if (fDragging || host.mode != Mode.FINGER) return
        val moved = hypot(n.first - s0.first, n.second - s0.second) > 30f
        val sel = if (moved) choice(n.first - s0.first, n.second - s0.second) else -1
        val R = 120f
        c.drawCircle(s0.first, s0.second, R + 45f, guideBg)
        for (i in 0..4) {
            val a = Math.toRadians(FINGER_ANGLES[i].toDouble())
            val gx = s0.first + (R * cos(a)).toFloat(); val gy = s0.second - (R * sin(a)).toFloat()
            guide.textSize = if (sel == i + 1) 64f else 40f
            guide.color = if (sel == i + 1) Color.rgb(200, 30, 30) else Color.rgb(60, 60, 60)
            c.drawText((i + 1).toString(), gx, gy + guide.textSize * 0.35f, guide)
        }
        guide.textSize = if (sel == 0) 40f else 30f; guide.color = if (sel == 0) Color.rgb(200, 30, 30) else Color.GRAY
        c.drawText("완료", s0.first - R, s0.second + 12f, guide)
        val ca = Math.toRadians(225.0)
        guide.textSize = if (sel == -2) 40f else 30f; guide.color = if (sel == -2) Color.rgb(200, 30, 30) else Color.GRAY
        c.drawText("취소", s0.first + (R * cos(ca)).toFloat(), s0.second - (R * sin(ca)).toFloat() + 12f, guide)
        // the pressed spot: crosshair + preview of the number that will be placed there
        val ts = staffSpaceNorm() * dst.width() * z * 1.7f * host.data.fingerScale
        c.drawCircle(s0.first, s0.second, ts * 0.7f, mark)
        c.drawLine(s0.first - ts, s0.second, s0.first - ts * 0.75f, s0.second, mark)
        c.drawLine(s0.first + ts * 0.75f, s0.second, s0.first + ts, s0.second, mark)
        c.drawLine(s0.first, s0.second - ts, s0.first, s0.second - ts * 0.75f, mark)
        c.drawLine(s0.first, s0.second + ts * 0.75f, s0.first, s0.second + ts, mark)
        if (sel in 1..5) {
            fingerPaint.textSize = ts; fingerHalo.textSize = ts; fingerHalo.strokeWidth = ts * 0.18f
            c.drawText(sel.toString(), s0.first, s0.second + ts * 0.36f, fingerHalo)
            c.drawText(sel.toString(), s0.first, s0.second + ts * 0.36f, fingerPaint)
        }
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
        if (am == MotionEvent.ACTION_DOWN) { multi = false; lastX = e.x; lastY = e.y; selectAt(e.y) } else select(touchSeg)
        if (am == MotionEvent.ACTION_POINTER_DOWN) {
            if (!multi) { multiT = e.eventTime; multiZ = z; multiMoved = 0f }
            multi = true; parent?.requestDisallowInterceptTouchEvent(true)
            cur.clear(); dragStart = null; dragNow = null; fStart = null; fNow = null; fDragging = false; invalidate()
        }
        if (multi || e.pointerCount > 1) {
            // two-finger move while pinching
            if (am == MotionEvent.ACTION_MOVE && e.pointerCount > 1) {
                val fx = (e.getX(0) + e.getX(1)) / 2; val fy = (e.getY(0) + e.getY(1)) / 2
                if (lastX != 0f) { multiMoved += abs(fx - lastX) + abs(fy - lastY); tx += fx - lastX; ty += fy - lastY; clamp(); invalidate() }
                lastX = fx; lastY = fy
            }
            if (am == MotionEvent.ACTION_POINTER_UP) lastX = 0f
            if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL) {
                // quick two-finger tap without zooming or moving = undo (like Ctrl+Z)
                if (am == MotionEvent.ACTION_UP && e.eventTime - multiT < 300 && abs(z - multiZ) < 0.03f && multiMoved < 40f) host.onUndoGesture()
                multi = false
            }
            return true
        }
        when (host.mode) {
            Mode.PEN -> return penTouch(e)
            Mode.FINGER -> {
                when (am) {
                    MotionEvent.ACTION_DOWN -> {
                        downT = e.eventTime; fStart = e.x to e.y; fNow = fStart; fDragging = false
                        fHit = fingerAt(nx(e.x), ny(e.y))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        fNow = e.x to e.y
                        val h = fHit
                        if (h != null && !fDragging && e.eventTime - downT > 350) fDragging = true
                        if (fDragging && h != null) { h.x = nx(e.x); h.y = ny(e.y) }
                    }
                    MotionEvent.ACTION_UP -> {
                        val s = fStart; val h = fHit
                        if (fDragging && h != null) host.onFingerMoved(h)
                        else if (s != null) {
                            val dx = e.x - s.first; val dy = e.y - s.second
                            if (hypot(dx, dy) < 30f) { if (h != null) host.onFingerRemove(h) }
                            else when (val k = choice(dx, dy)) {
                                0 -> host.onFingerExit()
                                in 1..5 -> host.onFingerAdd(Finger(page, nx(s.first), ny(s.second), k, staffSpaceNorm()))
                                else -> {}
                            }
                        }
                        fStart = null; fNow = null; fHit = null; fDragging = false
                    }
                    MotionEvent.ACTION_CANCEL -> { fStart = null; fNow = null; fHit = null; fDragging = false }
                }
                invalidate(); return true
            }
            Mode.FIX -> {
                if (am == MotionEvent.ACTION_DOWN) { downT = e.eventTime; downX = e.x; downY = e.y }
                if (am == MotionEvent.ACTION_UP && hypot(e.x - downX, e.y - downY) < 20f) {
                    val o = origs[page]
                    if (o != null) {
                        val sp = sps.getOrPut(page) { HeadFix.staffSpace(o) }
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
                // one finger always turns pages (even zoomed in); moving a zoomed page = two fingers only
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
                    val pg = page
                    val r = Runnable { host.data.strokes.add(Stroke(pg, host.penColor, host.penWidth, dot)); host.onInkChanged() }
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

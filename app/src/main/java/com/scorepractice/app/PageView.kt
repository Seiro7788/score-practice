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
        val penStraight: Boolean   // highlighter: keep only a straight line from start to end
        val erasing: Boolean
        val selectedRange: String?
        fun onDoubleTap(page: Int)
        fun onTripleTap(r: Range?)   // three quick taps: +1 practice (the range tapped, else the one selected)
        fun onRangeTap(r: Range)
        fun onRangeLongPress(r: Range)
        fun onRangeDrawn(page: Int, x0: Float, y0: Float, x1: Float, y1: Float)
        fun onInkChanged()
        fun onZoomChanged(zoomed: Boolean)
        fun onFixTap(page: Int, x: Float, y: Float, head: HeadFix.Head?, w: Int, h: Int, color: Int? = null)
        val fixPlace: Int              // long press on an empty spot places: 0 = a hollow head (half note), 1 = a filled head (quarter)
        fun onFixPlace(page: Int, x: Float, y: Float, r: Float, rb: Float, hollow: Boolean, color: Int?)
        fun onFixMoved(old: Fix, x: Float, y: Float)
        val fixPalette: List<Int>      // black, ♯ black key, ♯ white key, ♭ black key, ♭ white key, × black key, × white key
        fun onFingerAdd(f: Finger)
        fun onFingerRemove(f: Finger)
        fun onFingerMoved(f: Finger)
        fun onFingerExit()
        val fingerTool: Int                  // 0 = put numbers in, 1 = copy (draw a box), 2 = paste
        val fingerClip: List<Finger>         // copied numbers: x, y relative to the copy's first number
        fun onFingerCopy(page: Int, x0: Float, y0: Float, x1: Float, y1: Float)
        fun onFingerPaste(page: Int, x: Float, y: Float)
        fun onUndoGesture()
        fun onFlick(dir: Int)   // +1 next page, -1 previous
        val fixAreaErase: Boolean   // colour-fix mode: drag a box to remove the colours inside it
        fun onFixArea(page: Int, x0: Float, y0: Float, x1: Float, y1: Float)
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

    fun clearBitmaps() { origs.clear(); shown.clear(); sps.clear(); sprites.clear() }

    private val bakedKey = mutableMapOf<Int, Int>()

    // Note colours are a separate layer: each coloured note is a small picture (its own ink pixels in the colour)
    // drawn over the page. Adding or removing one never touches the page bitmap, so a tap costs the same anywhere.
    // The page bitmap only bakes in the highlighter (multiply), and is repainted in place when that changes.
    private val sprites = HashMap<Fix, Pair<Bitmap, android.graphics.Rect>?>()
    @Volatile private var finding = false
    private var findingT = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val finder = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun refreshFixes() { invalidate() }
    fun refreshFixes(p: Int) { invalidate() }
    fun fixAdded(f: Fix) { invalidate() }

    private fun spriteOf(f: Fix, o: Bitmap): Pair<Bitmap, android.graphics.Rect>? {
        if (sprites.size > 4000) sprites.clear()
        return sprites.getOrPut(f) {
            val cx = f.x * o.width; val cy = f.y * o.height; val a = f.r * o.width; val b = f.rb * o.width
            val ia = (a * 1.25f).toInt() + 2
            val x0 = kotlin.math.max(0, (cx - ia).toInt()); val y0 = kotlin.math.max(0, (cy - ia).toInt())
            val x1 = kotlin.math.min(o.width, (cx + ia).toInt() + 1); val y1 = kotlin.math.min(o.height, (cy + ia).toInt() + 1)
            if (x1 <= x0 || y1 <= y0) return@getOrPut null
            val bm = Bitmap.createBitmap(o, x0, y0, x1 - x0, y1 - y0).copy(Bitmap.Config.ARGB_8888, true)
            bm.eraseColor(Color.TRANSPARENT)
            val src = Bitmap.createBitmap(o, x0, y0, x1 - x0, y1 - y0)
            val tmp = src.copy(Bitmap.Config.ARGB_8888, true)
            HeadFix.apply(tmp, cx - x0, cy - y0, a, b, f.hollow, f.color)
            // keep only the pixels the colouring changed
            val w = x1 - x0; val h = y1 - y0; val p0 = IntArray(w * h); val p1 = IntArray(w * h)
            src.getPixels(p0, 0, w, 0, 0, w, h); tmp.getPixels(p1, 0, w, 0, 0, w, h)
            for (i in p1.indices) if (p1[i] == p0[i]) p1[i] = Color.TRANSPARENT
            bm.setPixels(p1, 0, w, 0, 0, w, h)
            tmp.recycle()
            bm to android.graphics.Rect(x0, y0, x1, y1)
        }
    }

    private fun drawFixes(c: Canvas, p: Int) {
        if (!host.data.showFixes) return
        val o = origs[p] ?: return
        val k = dst.width() / o.width
        val r = RectF()
        for (f in host.data.fixes) if (f.page == p) {
            if (f === moving) continue
            if (f.manual) { HeadFix.drawManual(c, sx(f.x), sy(f.y), f.r * dst.width(), f.rb * dst.width(), f.hollow, f.color); continue }
            val (bm, box) = spriteOf(f, o) ?: continue
            r.set(dst.left + box.left * k, dst.top + box.top * k, dst.left + box.right * k, dst.top + box.bottom * k)
            c.drawBitmap(bm, null, r, bmpPaint)
        }
    }

    private fun keyOf(p: Int): Int {
        var key = 17
        for (s in host.data.strokes) if (s.page == p && Highlight.isHighlighter(s) && host.data.strokeVisible(s)) key = key * 31 + System.identityHashCode(s)
        return key
    }

    /** page bitmap with the highlighter baked in; rebuilt (in the same bitmap) only when the highlighter changes */
    private fun baked(p: Int): Bitmap? {
        val o = origs[p] ?: return null
        val hi = host.data.strokes.filter { it.page == p && Highlight.isHighlighter(it) && host.data.strokeVisible(it) }
        if (hi.isEmpty()) { shown.remove(p); bakedKey.remove(p); return o }
        val key = keyOf(p)
        val have = shown[p]
        if (have != null && bakedKey[p] == key) return have
        val reuse = have?.takeIf { it !== o && it.isMutable && !it.isRecycled && it.width == o.width && it.height == o.height }
        val bm = (reuse?.also { b ->
            Canvas(b).drawBitmap(o, 0f, 0f, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) })
        } ?: o.copy(Bitmap.Config.ARGB_8888, true))
        Highlight.bake(bm, hi)
        shown[p] = bm; bakedKey[p] = key; return bm
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
        return host.data.fingers.filter { it.page == page && (host.data.fingerVisible(it) || it.layer == host.data.activeLayer) && abs(it.x - x) < r && abs((it.y - y) * dst.height() / dst.width()) < r * 1.2f }
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
        // taps are counted here: 1 = select a range, 2 = double tap, 3 = +1 practice
        override fun onSingleTapUp(e: MotionEvent): Boolean { countTap(e); return true }
        override fun onLongPress(e: MotionEvent) { selectAt(e.y); if (!multi) hit(e)?.let { host.onRangeLongPress(it) } }
    }).apply { setOnDoubleTapListener(null) }

    private var taps = 0; private var tapT = 0L; private var tapX = 0f; private var tapY = 0f
    private var tapRange: Range? = null
    private val tapDone = Runnable {
        val n = taps; taps = 0
        when {
            n >= 3 -> host.onTripleTap(tapRange)
            n == 2 -> host.onDoubleTap(page)
            n == 1 -> tapRange?.let { host.onRangeTap(it) }
        }
    }
    private fun countTap(e: MotionEvent) {
        val now = e.eventTime
        if (taps > 0 && now - tapT < 380 && hypot(e.x - tapX, e.y - tapY) < 90f) taps++
        else { taps = 1; tapX = e.x; tapY = e.y; selectAt(e.y); tapRange = hit(e) }
        tapT = now
        removeCallbacks(tapDone)
        if (taps >= 3) post(tapDone) else postDelayed(tapDone, 330)
    }

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

    private fun visibleRanges() = host.data.ranges.filter { it.page == page && (host.data.showRanges || host.mode == Mode.RANGE) && host.data.layer(it.layer)?.visible == true }

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
        val b = baked(segs[i].page) ?: continue
        select(i)
        c.save(); c.clipRect(segRects[i])
        c.drawBitmap(b, null, dst, bmpPaint)
        drawFixes(c, segs[i].page)
        if (segs[i].page == page) drawPasteGhost(c)
        placing?.let { pl -> if (page == segs[i].page) {
            val (r, rb) = typicalHead(page); val n = cNow; val s0 = cStart
            val k = if (n != null && s0 != null && hypot(n.first - s0.first, n.second - s0.second) >= 30f) colourChoice(n.first - s0.first, n.second - s0.second) else -2
            val col = if (k >= 0) host.fixPalette[k] else Color.argb(160, 120, 120, 120)
            HeadFix.drawManual(c, sx(pl.first), sy(pl.second), r * dst.width(), rb * dst.width(), host.fixPlace != 1, col) } }
        moving?.let { mv -> val p0 = movePos; if (mv.page == segs[i].page && p0 != null) {
            HeadFix.drawManual(c, sx(p0.first), sy(p0.second), mv.r * dst.width(), mv.rb * dst.width(), mv.hollow, mv.color)
            mark.strokeWidth = 3f / z; c.drawCircle(sx(p0.first), sy(p0.second), mv.r * dst.width() * 1.6f, mark); mark.strokeWidth = 3f } }
        for (r in visibleRanges()) {
            // practice range: no fill — only a ┌ at the start and a ┘ at the end, see-through
            val col = host.data.layer(r.layer)?.color ?: Color.RED
            val done = r.count >= r.target && r.target > 0
            val sel = r.id == host.selectedRange
            val alpha = if (sel) 200 else 120
            edge.color = Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col)); edge.strokeWidth = (if (sel) 9f else 6f) / z
            edge.strokeCap = Paint.Cap.ROUND
            val rr = RectF(sx(r.x0), sy(r.y0), sx(r.x1), sy(r.y1))
            val arm = minOf(rr.width(), rr.height()) * 0.35f
            c.drawLine(rr.left, rr.top, rr.left + arm, rr.top, edge); c.drawLine(rr.left, rr.top, rr.left, rr.top + arm, edge)
            c.drawLine(rr.right, rr.bottom, rr.right - arm, rr.bottom, edge); c.drawLine(rr.right, rr.bottom, rr.right, rr.bottom - arm, edge)
            edge.strokeCap = Paint.Cap.BUTT
            val t = (if (r.name.isNotEmpty()) r.name + "  " else "") + "${r.count}/${r.target}" + if (done) " ✓" else ""
            label.textSize = 30f / z
            label.color = Color.argb(if (sel) 230 else 150, Color.red(col), Color.green(col), Color.blue(col))
            c.drawText(t, rr.left + 4f / z, rr.top - 8f / z, label)
        }
        for (g in host.data.fingers) if (g.page == page && (host.data.fingerVisible(g) || (host.mode == Mode.FINGER && g.layer == host.data.activeLayer))) {
            val ts = g.s * dst.width() * 1.7f * host.data.fingerScale
            fingerPaint.textSize = ts; fingerHalo.textSize = ts; fingerHalo.strokeWidth = ts * 0.18f
            val yb = sy(g.y) + ts * 0.36f
            c.drawText(g.n.toString(), sx(g.x), yb, fingerHalo); c.drawText(g.n.toString(), sx(g.x), yb, fingerPaint)
        }
        if (host.data.showInk) {
            for (s in host.data.strokes) if (s.page == page && Color.alpha(s.color) == 255 && host.data.strokeVisible(s)) drawStroke(c, s.pts, s.color, s.width)
        }
        if (i == touchSeg && cur.size >= 2) drawStroke(c,
            if (host.penStraight && cur.size >= 4) floatArrayOf(cur[0], cur[1], cur[cur.size - 2], cur[cur.size - 1]) else cur.toFloatArray(),
            host.penColor, host.penWidth)
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
        drawColourGuide(c)
    }

    /** fan of the five numbers around the pressed point while swiping */
    private var pastePos: Pair<Float, Float>? = null

    private fun drawPasteGhost(c: Canvas) {
        val p0 = pastePos ?: return
        if (host.mode != Mode.FINGER || host.fingerTool != 2) return
        val ax = nx(p0.first); val ay = ny(p0.second)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; color = Color.argb(170, 220, 0, 90) }
        for (g in host.fingerClip) {
            val ts = g.s * dst.width() * 1.7f * host.data.fingerScale; tp.textSize = ts
            c.drawText(g.n.toString(), sx(ax + g.x), sy(ay + g.y) + ts * 0.36f, tp)
        }
        mark.strokeWidth = 3f / z; c.drawCircle(sx(ax), sy(ay), 10f / z, mark); mark.strokeWidth = 3f
    }

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
        ink.strokeCap = if (Color.alpha(color) < 255) Paint.Cap.BUTT else Paint.Cap.ROUND
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
                if (host.fingerTool == 1) {          // copy: box around the numbers to copy
                    when (am) {
                        MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragStart = nx(e.x) to ny(e.y); dragNow = dragStart; invalidate() }
                        MotionEvent.ACTION_MOVE -> { dragNow = nx(e.x) to ny(e.y); invalidate() }
                        MotionEvent.ACTION_UP -> {
                            val a = dragStart; val b = nx(e.x) to ny(e.y); dragStart = null; dragNow = null; invalidate()
                            if (a != null) host.onFingerCopy(page, minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
                        }
                        MotionEvent.ACTION_CANCEL -> { dragStart = null; dragNow = null; invalidate() }
                    }
                    return true
                }
                if (host.fingerTool == 2) {          // paste: the copied numbers follow above the finger, let go to put them there
                    val lift = 110f
                    when (am) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { pastePos = e.x to (e.y - lift); invalidate() }
                        MotionEvent.ACTION_UP -> { val p0 = e.x to (e.y - lift); pastePos = null; invalidate(); host.onFingerPaste(page, nx(p0.first), ny(p0.second)) }
                        MotionEvent.ACTION_CANCEL -> { pastePos = null; invalidate() }
                    }
                    return true
                }
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
                                in 1..5 -> host.onFingerAdd(Finger(page, nx(s.first), ny(s.second), k, host.data.fingerSize() ?: staffSpaceNorm(), host.data.activeLayer))
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
                if (host.fixAreaErase && am != MotionEvent.ACTION_DOWN) {
                    // box: from where the finger went down to where it is now
                    if (hypot(e.x - downX, e.y - downY) >= 20f || dragStart != null) {
                        when (am) {
                            MotionEvent.ACTION_MOVE -> { dragStart = nx(downX) to ny(downY); dragNow = nx(e.x) to ny(e.y); invalidate(); return true }
                            MotionEvent.ACTION_UP -> {
                                val a = dragStart ?: (nx(downX) to ny(downY))
                                val bx = nx(e.x); val by = ny(e.y)
                                dragStart = null; dragNow = null; invalidate()
                                host.onFixArea(page, minOf(a.first, bx), minOf(a.second, by), maxOf(a.first, bx), maxOf(a.second, by))
                                return true
                            }
                            MotionEvent.ACTION_CANCEL -> { dragStart = null; dragNow = null; invalidate(); return true }
                        }
                    }
                }
                // hold a coloured note still for a moment = pick it up, then drag it where it belongs
                if (am == MotionEvent.ACTION_DOWN && !host.fixAreaErase) {
                    moving = null; movePos = null
                    val x = nx(e.x); val y = ny(e.y)
                    val near = host.data.fixes.filter { it.page == page && abs(it.x - x) < it.r * 1.4f && abs((it.y - y) * dst.height() / dst.width()) < it.rb * 1.8f }
                        .minByOrNull { abs(it.x - x) + abs(it.y - y) }
                    lastMoveX = e.x; lastMoveY = e.y
                    val r = if (near != null) Runnable {
                        if (hypot(lastMoveX - downX, lastMoveY - downY) < 15f) { moving = near; movePos = near.x to near.y; cStart = null; cNow = null
                            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); invalidate() } }
                    // hold an empty spot (or a note that isn't found): a head appears there; swipe to pick its colour
                    else Runnable {
                        if (hypot(lastMoveX - downX, lastMoveY - downY) < 15f) { placing = x to y; cStart = downX to downY; cNow = downX to downY
                            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); invalidate() } }
                    pickUp = r; postDelayed(r, 420)
                }
                if (am == MotionEvent.ACTION_MOVE) { lastMoveX = e.x; lastMoveY = e.y }
                if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL) { pickUp?.let { removeCallbacks(it) }; pickUp = null }
                placing?.let { pl ->
                    when (am) {
                        MotionEvent.ACTION_MOVE -> { cNow = e.x to e.y; invalidate() }
                        MotionEvent.ACTION_UP -> {
                            val moved = hypot(e.x - downX, e.y - downY) >= 30f
                            val k = if (moved) colourChoice(e.x - downX, e.y - downY) else -2
                            placing = null; cStart = null; cNow = null; invalidate()
                            if (k != -1) { val (r, rb) = typicalHead(page)
                                host.onFixPlace(page, pl.first, pl.second, r, rb, host.fixPlace != 1, if (k >= 0) host.fixPalette[k] else null) }
                        }
                        MotionEvent.ACTION_CANCEL -> { placing = null; cStart = null; cNow = null; invalidate() }
                    }
                    return true
                }
                moving?.let { mv ->
                    val start = mv.x to mv.y
                    when (am) {
                        MotionEvent.ACTION_MOVE -> { movePos = (start.first + (e.x - downX) / dst.width()) to (start.second + (e.y - downY) / dst.height()); invalidate() }
                        MotionEvent.ACTION_UP -> { val p0 = movePos; moving = null; movePos = null; invalidate(); if (p0 != null) host.onFixMoved(mv, p0.first, p0.second) }
                        MotionEvent.ACTION_CANCEL -> { moving = null; movePos = null; invalidate() }
                    }
                    return true
                }
                if (am == MotionEvent.ACTION_DOWN) { cStart = e.x to e.y; cNow = null; invalidate() }
                if (am == MotionEvent.ACTION_MOVE && !host.fixAreaErase && cStart != null) { cNow = e.x to e.y; invalidate() }
                if (am == MotionEvent.ACTION_CANCEL) { cStart = null; cNow = null; invalidate() }
                if (am == MotionEvent.ACTION_UP && !host.fixAreaErase && isFlick(e, 600L)) { cStart = null; cNow = null; invalidate(); host.onFlick(if (e.x < downX) 1 else -1); return true }
                // press a note and swipe: the direction picks the colour (like fingering)
                if (am == MotionEvent.ACTION_UP && !host.fixAreaErase && cStart != null && hypot(e.x - downX, e.y - downY) >= 30f) {
                    val k = colourChoice(e.x - downX, e.y - downY)
                    cStart = null; cNow = null; invalidate()
                    if (k >= 0) findHead(downX, downY) { pg, x, y, head, w, h -> host.onFixTap(pg, x, y, head, w, h, host.fixPalette[k]) }
                    return true
                }
                if (am == MotionEvent.ACTION_UP) { cStart = null; cNow = null; invalidate() }
                if (am == MotionEvent.ACTION_UP && hypot(e.x - downX, e.y - downY) < 20f)
                    findHead(e.x, e.y) { pg, x, y, head, w, h -> host.onFixTap(pg, x, y, head, w, h) }
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

    /** a quick, long, mostly sideways swipe = turn the page (short strokes and slow lines stay ink) */
    private fun isFlick(e: MotionEvent, maxMs: Long): Boolean {
        val dx = e.x - downX; val dy = e.y - downY
        return e.eventTime - downT <= maxMs && abs(dx) > width * 0.28f && abs(dy) < abs(dx) * 0.45f
    }

    private var moving: Fix? = null
    private var placing: Pair<Float, Float>? = null
    private var movePos: Pair<Float, Float>? = null
    private var pickUp: Runnable? = null
    private var lastMoveX = 0f; private var lastMoveY = 0f

    /** head size (normalized to page width) of the notes already coloured on this page, else from the staff spacing */
    private fun typicalHead(p: Int): Pair<Float, Float> {
        val fx = host.data.fixes.filter { it.page == p && !it.manual }
        if (fx.size >= 3) { val rs = fx.map { it.r }.sorted(); val rbs = fx.map { it.rb }.sorted(); return rs[rs.size / 2] to rbs[rbs.size / 2] }
        val o = origs[p] ?: return 0.0085f * 0.66f to 0.0085f * 0.47f
        val sp = synchronized(sps) { sps.getOrPut(p) { HeadFix.staffSpace(o) } }
        return 0.66f * sp / o.width to 0.47f * sp / o.width
    }

    /** find the note head at a screen point off the screen thread, then report it on the screen thread */
    private fun findHead(sx0: Float, sy0: Float, done: (Int, Float, Float, HeadFix.Head?, Int, Int) -> Unit) {
        val o = origs[page] ?: return
        if (finding && android.os.SystemClock.uptimeMillis() - findingT < 3000) return
        finding = true; findingT = android.os.SystemClock.uptimeMillis()
        val x = nx(sx0); val y = ny(sy0); val pg = page
        finder.execute {
            val head = try {
                val sp = synchronized(sps) { sps.getOrPut(pg) { HeadFix.staffSpace(o) } }
                HeadFix.find(o, x * o.width, y * o.height, sp)
            } catch (t: Throwable) { null }
            mainHandler.post { finding = false; done(pg, x, y, head, o.width, o.height) }
        }
    }

    // colour by swipe: right side = black key, left = white key; up = ♯, down = ♭; sideways = ×;
    // straight up = black (take the colour off), straight down = cancel
    private var cStart: Pair<Float, Float>? = null
    private var cNow: Pair<Float, Float>? = null
    private val C_ANG = intArrayOf(0, 45, 90, 135, 180, 225, 270, 315)
    private val C_IDX = intArrayOf(5, 1, 0, 2, 6, 4, -1, 3)   // index into host.fixPalette, -1 = cancel
    private val C_NAME = arrayOf("×검", "♯검", "검정", "♯흰", "×흰", "♭흰", "취소", "♭검")
    private fun colourChoice(dx: Float, dy: Float): Int {
        var a = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat(); if (a < 0) a += 360f
        val sector = ((a + 22.5f) / 45f).toInt() % 8
        return C_IDX[sector]
    }

    private fun drawColourGuide(c: Canvas) {
        val s0 = cStart ?: return; val n = cNow ?: return
        if (host.mode != Mode.FIX || host.fixAreaErase) return
        val dx = n.first - s0.first; val dy = n.second - s0.second
        if (hypot(dx, dy) < 30f && placing == null) return
        var a = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat(); if (a < 0) a += 360f
        val sel = if (hypot(dx, dy) < 30f) -1 else ((a + 22.5f) / 45f).toInt() % 8
        val R = 125f
        c.drawCircle(s0.first, s0.second, R + 55f, guideBg)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 8) {
            val rad = Math.toRadians(C_ANG[i].toDouble())
            val gx = s0.first + (R * cos(rad)).toFloat(); val gy = s0.second - (R * sin(rad)).toFloat()
            val big = i == sel
            if (C_IDX[i] >= 0) {
                dot.style = Paint.Style.FILL; dot.color = host.fixPalette[C_IDX[i]]
                c.drawCircle(gx, gy, if (big) 34f else 22f, dot)
                if (big) { dot.style = Paint.Style.STROKE; dot.strokeWidth = 5f; dot.color = Color.rgb(230, 0, 90); c.drawCircle(gx, gy, 38f, dot) }
            }
            guide.textSize = if (big) 30f else 22f; guide.color = if (big) Color.rgb(200, 30, 30) else Color.rgb(60, 60, 60)
            c.drawText(C_NAME[i], gx, gy + (if (C_IDX[i] >= 0) 60f else 8f), guide)
        }
        c.drawCircle(s0.first, s0.second, 18f, mark)
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
                    val r = Runnable { host.data.strokes.add(Stroke(pg, host.penColor, host.penWidth, dot, host.data.activeLayer)); host.onInkChanged() }
                    pendingDot = r; postDelayed(r, 350)
                } else if (cur.size >= 4) {
                    val pts = if (host.penStraight) floatArrayOf(cur[0], cur[1], cur[cur.size - 2], cur[cur.size - 1]) else cur.toFloatArray()
                    host.data.strokes.add(Stroke(page, host.penColor, host.penWidth, pts, host.data.activeLayer)); host.onInkChanged()
                }
                cur.clear()
            }
        }
        invalidate(); return true
    }

    private fun erase(x: Float, y: Float) {
        val r = 0.02f / z
        val gone = host.data.strokes.filter { s ->
            s.page == page && host.data.strokeVisible(s) && (0 until s.pts.size / 2).any { i ->
                val dx = s.pts[2 * i] - x; val dy = s.pts[2 * i + 1] - y; abs(dx) < r && abs(dy) < r
            }
        }
        if (gone.isNotEmpty()) { host.data.strokes.removeAll(gone); invalidate() }
    }
}

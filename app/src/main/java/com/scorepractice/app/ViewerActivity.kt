package com.scorepractice.app

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ViewerActivity : AppCompatActivity(), PageView.Host {
    private lateinit var score: Score
    override lateinit var data: ScoreData
    override var mode = PageView.Mode.VIEW
    override var penColor = Color.BLACK
    override var penWidth = 0.003f
    override val penStraight: Boolean
        get() = straight && Color.alpha(penColor) < 255
    private var straight = true
    private var penSize = 1; private var hiSize = 1
    private val PEN_WIDTHS = floatArrayOf(0.0018f, 0.003f, 0.005f)
    private val HI_WIDTHS = floatArrayOf(0.009f, 0.014f, 0.022f)
    private val HI_YELLOW = Color.argb(95, 255, 225, 0); private val HI_GREEN = Color.argb(85, 60, 220, 60)
    private val HI_PINK = Color.argb(80, 255, 60, 160); private val HI_BLUE = Color.argb(80, 40, 170, 255)
    override var erasing = false
    override var selectedRange: String? = null
    private lateinit var practiceBar: View
    private lateinit var rName: TextView
    private lateinit var metroBar: View
    private lateinit var mBpm: EditText
    private lateinit var mPlay: Button
    private lateinit var mBeats: Button
    private val taps = mutableListOf<Long>()
    private lateinit var fixBar: View
    private lateinit var fingerBar: View
    private var fixColor = Color.BLACK
    private val metro = Metronome({ b -> onBeat(b) }, { msg -> toast("메트로놈 소리를 낼 수 없습니다: $msg"); mPlay.text = "▶ 시작" })

    private lateinit var pdf: PdfRenderer
    private lateinit var fd: ParcelFileDescriptor
    private lateinit var pager: ViewPager2
    private lateinit var pageNo: TextView
    private lateinit var btnRange: Button
    private lateinit var penBar: View
    private val cache = LruCache<Int, Bitmap>(6)
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pages = mutableMapOf<Int, PageView>()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val exportOpt = PdfExport.Options()
    // save dialog of the system: phone storage or Google Drive
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) doExport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        val id = intent.getStringExtra("id")!!
        score = Store.loadLibrary(this).first { it.id == id }
        data = Store.loadData(this, id)
        fd = ParcelFileDescriptor.open(File(Store.scoreDir(this), score.file), ParcelFileDescriptor.MODE_READ_ONLY)
        pdf = PdfRenderer(fd)

        val titleView = findViewById<TextView>(R.id.title)
        val showTitle = { titleView.text = if (score.composer.isNotEmpty()) "${score.composer} · ${score.shownTitle}" else score.shownTitle }
        showTitle()
        titleView.setOnClickListener {   // edit title / composer
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
            val comp = EditText(this).apply { hint = "작곡가"; setText(score.composer) }
            val tit = EditText(this).apply { hint = "제목 (비우면 파일 이름)"; setText(score.title) }
            box.addView(comp); box.addView(tit)
            AlertDialog.Builder(this).setTitle("제목·작곡가 수정").setView(box).setPositiveButton("저장") { _, _ ->
                val lib = Store.loadLibrary(this)
                lib.firstOrNull { it.id == score.id }?.let { it.composer = comp.text.toString().trim(); it.title = tit.text.toString().trim(); score = it }
                Store.saveLibrary(this, lib); showTitle()
            }.setNegativeButton("취소", null).show()
        }
        pageNo = findViewById(R.id.pageNo); btnRange = findViewById(R.id.btnRange); penBar = findViewById(R.id.penBar)
        pager = findViewById(R.id.pager)
        applyDirection()
        half = prefs.getBoolean("halfPage", false)
        straight = prefs.getBoolean("hiStraight", true)
        pager.adapter = PageAdapter()
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(posOf(data.lastPage), false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageNo.text = pageLabel(position); data.lastPage = primaryPage(position); save()
                pages.values.forEach { if (it.pos != position) it.resetZoom() }
                pager.isUserInputEnabled = mode == PageView.Mode.VIEW
                selectedRange = null; updatePracticeBar()
            }
        })
        pageNo.text = pageLabel(pager.currentItem)

        btnRange.setOnClickListener { switchMode(if (mode == PageView.Mode.RANGE) PageView.Mode.VIEW else PageView.Mode.RANGE) }
        findViewById<Button>(R.id.btnLayer).setOnClickListener { layerDialog() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { menuDialog() }
        findViewById<Button>(R.id.btnFull).setOnClickListener { if (mode != PageView.Mode.VIEW) switchMode(PageView.Mode.VIEW); hideMenus(true) }
        // pens (opaque) and highlighters (see-through, drawn under the notes' darkness)
        val penSw = findViewById<LinearLayout>(R.id.penSwatches)
        val pens = listOf(Color.BLACK, Color.rgb(220, 30, 30), Color.rgb(30, 80, 220),
            HI_YELLOW, HI_GREEN, HI_PINK, HI_BLUE)
        val penViews = mutableListOf<View>()
        pens.forEachIndexed { i, c ->
            val v = swatch(c, i == 0, big = Color.alpha(c) < 255)
            if (i == 3) penSw.addView(TextView(this).apply { text = " 형광 "; textSize = 12f })
            v.setOnClickListener {
                val hi = Color.alpha(c) < 255
                pen(c, if (hi) HI_WIDTHS[hiSize] else PEN_WIDTHS[penSize])
                penViews.forEachIndexed { j, o -> mark(o, pens[j], j == i) }
                toast(if (hi) "형광펜" + (if (straight) " (곧게)" else "") else "펜")
            }
            penViews.add(v); penSw.addView(v)
        }
        penSw.addView(Button(this).apply {
            text = "굵기"; isAllCaps = false; minWidth = 0; minimumWidth = 0
            setOnClickListener {
                if (Color.alpha(penColor) < 255) { hiSize = (hiSize + 1) % 3; penWidth = HI_WIDTHS[hiSize] }
                else { penSize = (penSize + 1) % 3; penWidth = PEN_WIDTHS[penSize] }
                toast(arrayOf("가늘게", "보통", "굵게")[if (Color.alpha(penColor) < 255) hiSize else penSize])
            }
        })
        penSw.addView(Button(this).apply {
            text = if (straight) "곧게 ✓" else "곧게"; isAllCaps = false; minWidth = 0; minimumWidth = 0
            setOnClickListener { straight = !straight; prefs.edit().putBoolean("hiStraight", straight).apply(); text = if (straight) "곧게 ✓" else "곧게" }
        })
        findViewById<Button>(R.id.penErase).setOnClickListener { erasing = true; toast("지우개: 지울 선을 문지르세요") }
        findViewById<Button>(R.id.undo).setOnClickListener { undo() }
        findViewById<Button>(R.id.redo).setOnClickListener { redo() }
        lastJson = snapshot()
        findViewById<Button>(R.id.penDone).setOnClickListener { switchMode(PageView.Mode.VIEW) }
        setupPractice(); setupMetronome(id); setupFix(id); setupFinger()
    }

    /** "오른쪽으로 밀면 다음 쪽" = right-to-left page order */
    private fun applyDirection() {
        pager.layoutDirection = if (prefs.getBoolean("swipeRightNext", false)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun pen(c: Int, w: Float) { penColor = c; penWidth = w; erasing = false }

    private fun switchMode(m: PageView.Mode) {
        mode = m
        pager.isUserInputEnabled = m == PageView.Mode.VIEW
        penBar.visibility = if (m == PageView.Mode.PEN) View.VISIBLE else View.GONE
        fixBar.visibility = if (m == PageView.Mode.FIX) View.VISIBLE else View.GONE
        fingerBar.visibility = if (m == PageView.Mode.FINGER) View.VISIBLE else View.GONE
        if (m == PageView.Mode.FIX && !data.showFixes) { data.showFixes = true; redraw() }
        btnRange.text = if (m == PageView.Mode.RANGE) "범위 끝" else "범위"
        if (m == PageView.Mode.RANGE) toast("연습할 부분을 손가락으로 끌어 네모를 그리세요")
        if (m == PageView.Mode.PEN) { erasing = false; if (!data.showInk) { data.showInk = true; redraw() } }
        if (m == PageView.Mode.VIEW) save()
        updatePracticeBar()
    }

    // ---- practice count buttons
    private fun pageRanges() = data.ranges.filter { r ->
        data.layer(r.layer)?.visible == true && segsFor(pager.currentItem).any { it.page == r.page && r.y1 > it.y0 && r.y0 < it.y1 }
    }
        .sortedWith(compareBy({ it.y0 }, { it.x0 }))

    private fun currentRange(): Range? {
        val rs = pageRanges()
        return rs.firstOrNull { it.id == selectedRange } ?: rs.firstOrNull()?.also { selectedRange = it.id }
    }

    private fun updatePracticeBar() {
        val r = if (mode == PageView.Mode.PEN || mode == PageView.Mode.FIX || mode == PageView.Mode.FINGER) null else currentRange()
        practiceBar.visibility = if (r == null || immersive) View.GONE else View.VISIBLE
        if (r != null) {
            val n = pageRanges().size
            rName.text = (r.name.ifEmpty { "연습 범위" }) + "\n" + "${r.count} / ${r.target}회" +
                (if (r.count >= r.target) "  ✓" else "") + if (n > 1) "   (${pageRanges().indexOf(r) + 1}/$n)" else ""
        }
        redraw()
    }

    private fun setupPractice() {
        practiceBar = findViewById(R.id.practiceBar); rName = findViewById(R.id.rName)
        findViewById<Button>(R.id.rPlus).setOnClickListener {
            val r = currentRange() ?: return@setOnClickListener
            r.count++; r.log.add(System.currentTimeMillis()); save(); updatePracticeBar()
            if (r.count == r.target) toast("목표 ${r.target}회 달성!")
        }
        findViewById<Button>(R.id.rMinus).setOnClickListener {
            val r = currentRange() ?: return@setOnClickListener
            if (r.count > 0) { r.count--; if (r.log.isNotEmpty()) r.log.removeAt(r.log.size - 1); save(); updatePracticeBar() }
        }
        val step = { d: Int ->
            val rs = pageRanges()
            if (rs.isNotEmpty()) {
                val i = rs.indexOfFirst { it.id == selectedRange }.coerceAtLeast(0)
                selectedRange = rs[(i + d + rs.size) % rs.size].id; updatePracticeBar()
            }
        }
        findViewById<Button>(R.id.rPrev).setOnClickListener { step(-1) }
        findViewById<Button>(R.id.rNext).setOnClickListener { step(1) }
        rName.setOnClickListener { openRecord() }
        findViewById<Button>(R.id.rLog).setOnClickListener { openRecord() }
    }

    // ---- metronome
    private fun setupMetronome(id: String) {
        metroBar = findViewById(R.id.metroBar); mBpm = findViewById(R.id.mBpm)
        mPlay = findViewById(R.id.mPlay); mBeats = findViewById(R.id.mBeats)
        // this score's own tempo; a score opened for the first time starts from the tempo used most recently
        metro.bpm = if (data.bpm > 0) data.bpm else prefs.getInt("tempo_$id", prefs.getInt("tempo_last", 100))
        metro.beats = if (data.beats > 0) data.beats else prefs.getInt("meter_$id", prefs.getInt("meter_last", 1))
        val show = {
            if (mBpm.text.toString() != metro.bpm.toString()) mBpm.setText(metro.bpm.toString())
            mBeats.text = if (metro.beats == 1) "박없음" else "${metro.beats}박"
            prefs.edit().putInt("tempo_last", metro.bpm).putInt("meter_last", metro.beats).apply()
            if (data.bpm != metro.bpm || data.beats != metro.beats) {
                data.bpm = metro.bpm; data.beats = metro.beats
                Store.saveData(this, score.id, data)   // kept with the score (and in backups); not an undo step
            }
        }
        val add = { d: Int -> metro.bpm = (metro.bpm + d).coerceIn(20, 300); show() }
        findViewById<Button>(R.id.mM5).setOnClickListener { add(-5) }
        findViewById<Button>(R.id.mM1).setOnClickListener { add(-1) }
        findViewById<Button>(R.id.mP1).setOnClickListener { add(1) }
        findViewById<Button>(R.id.mP5).setOnClickListener { add(5) }
        mBeats.setOnClickListener {
            val opts = intArrayOf(1, 2, 3, 4, 6, 8)
            AlertDialog.Builder(this).setTitle("한 마디 박 수 (첫 박 강하게)")
                .setItems(opts.map { if (it == 1) "박없음 (모두 같은 소리)" else "${it}박" }.toTypedArray()) { _, i -> metro.beats = opts[i]; show() }.show()
        }
        findViewById<Button>(R.id.mTap).setOnClickListener {
            val now = System.currentTimeMillis()
            if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
            taps.add(now); if (taps.size > 6) taps.removeAt(0)
            if (taps.size >= 2) { metro.bpm = (60000.0 / ((taps.last() - taps.first()) / (taps.size - 1).toDouble())).toInt().coerceIn(20, 300); show() }
        }
        // type the tempo directly; applied on Done or when the field loses focus
        val applyTyped = {
            mBpm.text.toString().toIntOrNull()?.let { metro.bpm = it.coerceIn(20, 300) }
            show()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(mBpm.windowToken, 0)
            mBpm.clearFocus()
        }
        mBpm.setOnEditorActionListener { _, _, _ -> applyTyped(); true }
        mBpm.setOnFocusChangeListener { _, has -> if (!has) { mBpm.text.toString().toIntOrNull()?.let { metro.bpm = it.coerceIn(20, 300) }; show() } }
        mPlay.setOnClickListener {
            if (metro.isRunning) { metro.stop(); mPlay.text = "▶ 시작"; mBpm.setBackgroundColor(Color.TRANSPARENT) }
            else { mBpm.text.toString().toIntOrNull()?.let { metro.bpm = it.coerceIn(20, 300) }; show(); metro.start(); mPlay.text = "■ 멈춤" }
        }
        findViewById<Button>(R.id.btnMetro).setOnClickListener {
            metroBar.visibility = if (metroBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        show()
    }

    // ---- colour corrections (for scores that are already coloured)
    private val FIX_COLORS = listOf(
        Triple("검정(색 빼기)", Color.BLACK, false),
        Triple("♯ 검은건반", Color.rgb(255, 125, 125), false),
        Triple("♯ 흰건반", Color.rgb(110, 35, 160), false),
        Triple("♭ 검은건반", Color.rgb(80, 135, 255), false),
        Triple("♭ 흰건반", Color.rgb(70, 190, 100), false),
        Triple("× 검은건반", Color.rgb(255, 185, 185), false),
        Triple("× 흰건반", Color.rgb(115, 105, 115), false)
    )

    private fun setupFix(id: String) {
        fixBar = findViewById(R.id.fixBar)
        val row = findViewById<LinearLayout>(R.id.fixRow)
        val btns = mutableListOf<View>()
        FIX_COLORS.forEachIndexed { i, (_, col, _) ->
            val b = swatch(col, i == 0)
            b.setOnClickListener {
                fixColor = col
                btns.forEachIndexed { j, o -> mark(o, FIX_COLORS[j].second, j == i) }
            }
            btns.add(b); row.addView(b, 1 + i)
        }
        findViewById<Button>(R.id.btnFix).setOnClickListener {
            switchMode(if (mode == PageView.Mode.FIX) PageView.Mode.VIEW else PageView.Mode.FIX)
            if (mode == PageView.Mode.FIX) toast("색을 고르고 음표를 누르세요 (크기·빈 음표는 자동). 다시 누르면 취소")
        }
        findViewById<Button>(R.id.fixDone).setOnClickListener { switchMode(PageView.Mode.VIEW) }
    }

    override fun onFixTap(page: Int, x: Float, y: Float, head: HeadFix.Head?, w: Int, h: Int) {
        // tapping a note that was already fixed removes that fix
        val hit = data.fixes.filter { it.page == page && kotlin.math.abs(it.x - x) < it.r * 1.1f && kotlin.math.abs((it.y - y) * h / w) < it.rb * 1.4f }
            .minByOrNull { kotlin.math.abs(it.x - x) + kotlin.math.abs(it.y - y) }
        if (hit != null) { data.fixes.remove(hit); save(); refreshFixes(); return }
        if (head == null) { toast("음표를 찾지 못했습니다. 음표 한가운데를 눌러 주세요"); return }
        val f = Fix(page, head.x / w, head.y / h, head.a / w, fixColor, head.hollow, head.b / w)
        data.fixes.add(f); save(); refreshFixes()
    }

    // ---- fingering numbers
    private fun setupFinger() {
        fingerBar = findViewById(R.id.fingerBar)
        findViewById<Button>(R.id.fingerSmaller).setOnClickListener { fingerSize(0.88f) }
        findViewById<Button>(R.id.fingerBigger).setOnClickListener { fingerSize(1.14f) }
        findViewById<Button>(R.id.btnFinger).setOnClickListener {
            switchMode(if (mode == PageView.Mode.FINGER) PageView.Mode.VIEW else PageView.Mode.FINGER)
            if (mode == PageView.Mode.FINGER && !data.showFingers) { data.showFingers = true; save() }
        }
    }

    private fun fingerSize(f: Float) {
        data.fingerScale = (data.fingerScale * f).coerceIn(0.5f, 2.5f); save(); redraw()
        toast("손번호 크기 ${(data.fingerScale * 100).toInt()}%")
    }

    override fun onFingerAdd(f: Finger) { data.fingers.add(f); save(); redraw() }
    override fun onFingerRemove(f: Finger) { data.fingers.remove(f); save(); redraw() }
    override fun onFingerMoved(f: Finger) { save(); redraw() }
    override fun onFingerExit() { switchMode(PageView.Mode.VIEW); toast("손번호 입력 끝") }

    private fun refreshFixes() { pages.values.forEach { it.refreshFixes() } }

    private fun onBeat(b: Int) {
        if (!metro.isRunning) return
        mBpm.setBackgroundColor(if (b == 0) Color.rgb(255, 170, 170) else Color.rgb(200, 215, 255))
        mBpm.postDelayed({ mBpm.setBackgroundColor(Color.TRANSPARENT) }, 90)
    }

    // ---- PageView.Host
    // ---- hide all menus (full screen); double-tap brings them back
    private var immersive = false
    private var metroWasOpen = false

    private fun hideMenus(on: Boolean) {
        immersive = on
        val vis = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bar).visibility = vis
        findViewById<View>(R.id.undoBox).visibility = vis
        if (on) { metroWasOpen = metroBar.visibility == View.VISIBLE; metroBar.visibility = View.GONE; practiceBar.visibility = View.GONE }
        else { if (metroWasOpen) metroBar.visibility = View.VISIBLE; updatePracticeBar() }
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        if (on) toast("메뉴 숨김 — 화면을 두 번 두드리면 다시 나옵니다")
    }

    override fun onDoubleTap(page: Int) {
        if (immersive && mode == PageView.Mode.VIEW) { hideMenus(false); return }
        when (mode) {
            PageView.Mode.VIEW -> { switchMode(PageView.Mode.PEN); toast("필기 모드 — 끝내려면 다시 두 번 두드리기") }
            PageView.Mode.PEN -> switchMode(PageView.Mode.VIEW)
            else -> {}
        }
    }

    override fun onRangeTap(r: Range) { selectedRange = r.id; updatePracticeBar() }

    override fun onZoomChanged(zoomed: Boolean) {
        pager.isUserInputEnabled = mode == PageView.Mode.VIEW   // zoomed in: still swipe to turn
    }

    override fun onRangeLongPress(r: Range) {
        AlertDialog.Builder(this).setTitle(r.name.ifEmpty { "연습 범위" } + "  (${r.count}/${r.target})")
            .setItems(arrayOf("횟수 -1", "이름·목표 횟수 수정", "횟수 0으로", "연습 기록 보기", "다른 레이어로 옮기기", "삭제")) { _, w ->
                when (w) {
                    0 -> { if (r.count > 0) { r.count--; if (r.log.isNotEmpty()) r.log.removeAt(r.log.size - 1) } }
                    1 -> rangeDialog(r.name, r.target) { n, t -> r.name = n; r.target = t; save(); redraw() }
                    2 -> { r.count = 0; r.log.clear() }
                    3 -> showLog(r)
                    4 -> {
                        val names = data.layers.map { it.name }.toTypedArray()
                        AlertDialog.Builder(this).setItems(names) { _, i -> r.layer = data.layers[i].id; save(); redraw() }.show()
                    }
                    5 -> data.ranges.remove(r)
                }
                save(); updatePracticeBar()
            }.show()
    }

    override fun onRangeDrawn(page: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val n = data.ranges.count { it.page == page } + 1
        rangeDialog("${page + 1}쪽-$n", prefs.getInt("defaultTarget", 10)) { name, t ->
            data.ranges.add(Range(Store.newId(), data.activeLayer, page, x0, y0, x1, y1, name, t))
            prefs.edit().putInt("defaultTarget", t).apply()
            selectedRange = data.ranges.last().id
            save(); updatePracticeBar()
        }
    }

    override fun onInkChanged() { save(); redraw() }

    // ---- dialogs
    private fun rangeDialog(name: String, target: Int, ok: (String, Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val n = EditText(this).apply { hint = "이름 (예: 1~4마디 왼손)"; setText(name) }
        val t = EditText(this).apply { hint = "목표 횟수"; inputType = InputType.TYPE_CLASS_NUMBER; setText(target.toString()) }
        box.addView(TextView(this).apply { text = "레이어: " + (data.layer(data.activeLayer)?.name ?: "") })
        box.addView(n); box.addView(t)
        AlertDialog.Builder(this).setTitle("연습 범위").setView(box)
            .setPositiveButton("저장") { _, _ -> ok(n.text.toString().trim(), t.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10) }
            .setNegativeButton("취소", null).show()
    }

    private fun showLog(r: Range) {
        val f = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        val byDay = r.log.groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(it)) }
        val text = if (r.log.isEmpty()) "아직 기록이 없습니다." else
            byDay.entries.sortedByDescending { it.key }.joinToString("\n") { (d, l) -> "$d : ${l.size}회 (마지막 ${f.format(Date(l.last()))})" }
        AlertDialog.Builder(this).setTitle("연습 기록 — ${r.name}").setMessage(text).setPositiveButton("닫기", null).show()
    }

    private fun layerDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 0) }
        box.addView(TextView(this).apply { text = "체크 = 보이기,  ● = 새 범위를 넣을 레이어" })
        for (l in data.layers) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val cb = CheckBox(this).apply { isChecked = l.visible; setOnCheckedChangeListener { _, v -> l.visible = v; save(); redraw() } }
            val rb = RadioButton(this).apply {
                text = "${l.name}  (${data.ranges.count { it.layer == l.id }}개)"; setTextColor(l.color); isChecked = l.id == data.activeLayer
                setOnClickListener { data.activeLayer = l.id; save() }
            }
            row.addView(cb); row.addView(rb)
            rb.setOnLongClickListener { layerEdit(l); true }
            box.addView(row)
        }
        val ink = CheckBox(this).apply { text = "필기 보이기"; isChecked = data.showInk; setOnCheckedChangeListener { _, v -> data.showInk = v; save(); redraw() } }
        box.addView(ink)
        box.addView(CheckBox(this).apply { text = "색 고친 것 보이기"; isChecked = data.showFixes
            setOnCheckedChangeListener { _, v -> data.showFixes = v; save(); refreshFixes() } })
        box.addView(CheckBox(this).apply { text = "손번호 보이기"; isChecked = data.showFingers
            setOnCheckedChangeListener { _, v -> data.showFingers = v; save(); redraw() } })
        box.addView(TextView(this).apply { text = "레이어 이름을 길게 누르면 이름 바꾸기·삭제"; alpha = 0.6f })
        val dlg = AlertDialog.Builder(this).setTitle("레이어").setView(box)
            .setPositiveButton("닫기", null)
            .setNeutralButton("새 레이어") { _, _ ->
                val e = EditText(this).apply { hint = "예: 2주차, 왼손, 어려운 곳" }
                AlertDialog.Builder(this).setTitle("새 레이어").setView(e).setPositiveButton("만들기") { _, _ ->
                    val l = Layer(Store.newId(), e.text.toString().ifEmpty { "연습 ${data.layers.size + 1}" },
                        Store.LAYER_COLORS[data.layers.size % Store.LAYER_COLORS.size])
                    data.layers.add(l); data.activeLayer = l.id; save(); layerDialog()
                }.setNegativeButton("취소", null).show()
            }.create()
        dlg.show()
        // radio buttons are in separate rows: keep only one checked
        box.post {
            for (i in 0 until box.childCount) {
                val row = box.getChildAt(i) as? LinearLayout ?: continue
                val rb = row.getChildAt(1) as RadioButton
                rb.setOnClickListener { _ ->
                    val idx = i - 1; data.activeLayer = data.layers[idx].id; save()
                    for (j in 0 until box.childCount) ((box.getChildAt(j) as? LinearLayout)?.getChildAt(1) as? RadioButton)?.isChecked = j == i
                }
            }
        }
    }

    private fun layerEdit(l: Layer) {
        AlertDialog.Builder(this).setTitle(l.name).setItems(arrayOf("이름 바꾸기", "횟수 모두 0으로", "레이어 삭제(범위 포함)")) { _, w ->
            when (w) {
                0 -> { val e = EditText(this).apply { setText(l.name) }
                    AlertDialog.Builder(this).setView(e).setPositiveButton("저장") { _, _ -> l.name = e.text.toString(); save() }.show() }
                1 -> { data.ranges.filter { it.layer == l.id }.forEach { it.count = 0; it.log.clear() }; save(); redraw() }
                2 -> if (data.layers.size > 1) {
                    data.ranges.removeAll { it.layer == l.id }; data.layers.remove(l)
                    if (data.activeLayer == l.id) data.activeLayer = data.layers[0].id
                    save(); updatePracticeBar()
                } else toast("레이어가 하나뿐이라 지울 수 없습니다")
            }
        }.show()
    }

    private fun menuDialog() {
        val dir = if (prefs.getBoolean("swipeRightNext", false)) "지금: 오른쪽으로 밀면 다음 쪽" else "지금: 왼쪽으로 밀면 다음 쪽"
        AlertDialog.Builder(this).setItems(arrayOf("연습 기록", "PDF로 저장", "쪽 이동", "넘김 방향 바꾸기 ($dir)", "이 쪽 필기 모두 지우기",
            if (half) "반 페이지 넘김 끄기" else "반 페이지 넘김 켜기")) { _, w ->
            when (w) {
                0 -> openRecord()
                1 -> exportDialog()
                2 -> { val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "1 ~ ${pdf.pageCount}" }
                    AlertDialog.Builder(this).setTitle("쪽 이동").setView(e).setPositiveButton("이동") { _, _ ->
                        e.text.toString().toIntOrNull()?.let { pager.setCurrentItem(posOf(it - 1), false) }
                    }.show() }
                5 -> setHalf(!half)
                3 -> { prefs.edit().putBoolean("swipeRightNext", !prefs.getBoolean("swipeRightNext", false)).apply(); applyDirection() }
                4 -> { val p = primaryPage(pager.currentItem)
                    AlertDialog.Builder(this).setMessage("${p + 1}쪽의 필기를 모두 지울까요?").setPositiveButton("지우기") { _, _ ->
                        data.strokes.removeAll { it.page == p }; onInkChanged() }.setNegativeButton("취소", null).show() }
            }
        }.show()
    }

    private fun exportDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val items = listOf(
            Triple("색 고친 것", exportOpt.fixes) { v: Boolean -> exportOpt.fixes = v },
            Triple("필기", exportOpt.ink) { v: Boolean -> exportOpt.ink = v },
            Triple("손번호", exportOpt.fingers) { v: Boolean -> exportOpt.fingers = v },
            Triple("연습 범위(네모·횟수)", exportOpt.ranges) { v: Boolean -> exportOpt.ranges = v })
        for ((name, on, set) in items) box.addView(CheckBox(this).apply { text = name; isChecked = on; setOnCheckedChangeListener { _, v -> set(v) } })
        AlertDialog.Builder(this).setTitle("PDF로 저장 — 넣을 것").setView(box)
            .setPositiveButton("저장 위치 고르기") { _, _ ->
                val base = (if (score.composer.isNotEmpty()) score.composer + "_" else "") + score.shownTitle
                createPdf.launch(base.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "_연습본.pdf")
            }.setNegativeButton("취소", null).show()
    }

    private fun doExport(uri: android.net.Uri) {
        val msg = TextView(this).apply { setPadding(60, 40, 60, 40); text = "PDF 만드는 중…" }
        val dlg = AlertDialog.Builder(this).setView(msg).setCancelable(false).show()
        val snap = Store.parse(Store.toJson(data).toString())
        Thread {
            val err = try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    PdfExport.export(pdf, pdf, snap, exportOpt, out) { i, n -> main.post { msg.text = "PDF 만드는 중… $i / $n 쪽" } }
                }; null
            } catch (e: Exception) { e.message ?: e.toString() }
            main.post { dlg.dismiss(); toast(if (err == null) "PDF로 저장했습니다" else "저장 실패: $err") }
        }.start()
    }

    private fun openRecord() {
        PracticeLog.show(this, score.shownTitle, data,
            onJump = { r -> pager.setCurrentItem(posOf(r.page), false); selectedRange = r.id; updatePracticeBar() },
            onEdit = { r -> pager.setCurrentItem(posOf(r.page), false); selectedRange = r.id; updatePracticeBar(); onRangeLongPress(r) })
    }

    private fun rangeList() {
        val rs = data.ranges.sortedWith(compareBy({ it.page }, { it.y0 }, { it.x0 }))
        if (rs.isEmpty()) { toast("연습 범위가 없습니다. '범위'를 눌러 만드세요"); return }
        val items = rs.map { r ->
            val l = data.layer(r.layer)?.name ?: ""
            "${r.page + 1}쪽  ${r.name}  [${l}]  ${r.count}/${r.target}" + if (r.count >= r.target) " ✓" else ""
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("연습 범위 (누르면 그 쪽으로)").setItems(items) { _, i ->
            pager.setCurrentItem(posOf(rs[i].page), false)
        }.setPositiveButton("닫기", null).show()
    }

    // ---- pages
    private fun redraw() { pages.values.forEach { it.invalidate() } }
    // ---- undo / redo for every change (like Ctrl+Z): whole-score snapshots
    private var lastJson = ""
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    /** snapshot without view-only state (page, visibility toggles don't count as edits) */
    private fun snapshot(): String = Store.toJson(data).apply { remove("lastPage"); remove("bpm"); remove("beats") }.toString()

    private fun save() {
        val now = snapshot()
        if (lastJson.isNotEmpty() && now != lastJson) {
            undoStack.addLast(lastJson); if (undoStack.size > 100) undoStack.removeFirst(); redoStack.clear()
        }
        lastJson = now
        Store.saveData(this, score.id, data)
    }

    private fun restore(json: String) {
        val page = data.lastPage; val bpm = data.bpm; val beats = data.beats
        data = Store.parse(json); data.lastPage = page; data.bpm = bpm; data.beats = beats; lastJson = json
        Store.saveData(this, score.id, data)
        refreshFixes(); updatePracticeBar(); redraw()
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: run { toast("되돌릴 것이 없습니다"); return }
        redoStack.addLast(lastJson); restore(prev)
    }

    private fun redo() {
        val next = redoStack.removeLastOrNull() ?: run { toast("다시 할 것이 없습니다"); return }
        undoStack.addLast(lastJson); restore(next)
    }

    override fun onUndoGesture() = undo()

    // ---- colour swatches (show the colour itself, not its name)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun swatch(color: Int, selected: Boolean, big: Boolean = false): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(dp(if (big) 44 else 38), dp(38)).apply { setMargins(dp(5), dp(2), dp(5), dp(2)) }
        mark(v, color, selected); return v
    }

    private fun mark(v: View, color: Int, selected: Boolean) {
        v.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(if (selected) 4 else 1), if (selected) Color.rgb(40, 40, 40) else Color.rgb(170, 170, 170))
        }
        v.scaleX = if (selected) 1.15f else 1f; v.scaleY = v.scaleX
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- half-page turning: screen 0 = page 1; screen i = bottom of page i + top of page i+1; last = last page
    private var half = false
    private val splits = mutableMapOf<Int, Float>()

    private fun n() = pdf.pageCount
    private fun screens() = if (half && n() > 1) n() + 1 else n()
    private fun posOf(page: Int) = page.coerceIn(0, n() - 1)   // the screen where the page (or its top half) appears
    private fun primaryPage(pos: Int) = if (half && n() > 1) (if (pos >= n()) n() - 1 else pos) else pos
    private fun split(p: Int) = splits[p] ?: 0.5f

    private fun segsFor(pos: Int): List<PageView.Seg> =
        if (!half || n() < 2 || pos == 0) listOf(PageView.Seg(if (half) 0 else pos, 0f, 1f))
        else if (pos >= n()) listOf(PageView.Seg(n() - 1, 0f, 1f))
        else listOf(PageView.Seg(pos - 1, split(pos - 1), 1f), PageView.Seg(pos, 0f, split(pos)))

    private fun pageLabel(pos: Int): String =
        if (!half || n() < 2) "${pos + 1}/${n()}"
        else if (pos == 0) "1/${n()}" else if (pos >= n()) "${n()}/${n()}" else "${pos}½–${pos + 1}/${n()}"

    private fun setHalf(on: Boolean) {
        val page = primaryPage(pager.currentItem)
        half = on; prefs.edit().putBoolean("halfPage", on).apply()
        pager.adapter?.notifyDataSetChanged(); pager.setCurrentItem(posOf(page), false)
        pageNo.text = pageLabel(pager.currentItem)
        toast(if (on) "반 페이지 넘김: 아래 절반과 다음 쪽 위 절반을 함께 보여 줍니다" else "한 쪽씩 넘김")
    }

    /** split between two systems near the middle: the blank band closest to half height */
    private fun findSplit(b: Bitmap): Float {
        val w = b.width; val h = b.height; val row = IntArray(w)
        val ink = FloatArray(h)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var k = 0; var x = w / 20
            while (x < w - w / 20) { val c = row[x]; if ((Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10 < 170) k++; x += 2 }
            ink[y] = k * 2f / w
        }
        var best = 0.5f; var bestScore = -1e9f; var y = (h * 0.3f).toInt()
        while (y < h * 0.72f) {
            if (ink[y] < 0.004f) {
                val y0 = y; while (y < h && ink[y] < 0.004f) y++
                val len = y - y0; val mid = (y0 + y) / 2f
                val score = len - kotlin.math.abs(mid - h / 2f) * 0.25f
                if (len > h * 0.006f && score > bestScore) { bestScore = score; best = mid / h }
            } else y++
        }
        return best
    }

    private fun load(pv: PageView, page: Int) {
        cache.get(page)?.let { pv.setOrig(page, it); return }
        val w = resources.displayMetrics.widthPixels.coerceAtLeast(1080) * 3 / 2
        exec.execute {
            val bmp = synchronized(pdf) {
                pdf.openPage(page).use { p ->
                    val h = (w.toLong() * p.height / p.width).toInt()
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); b
                }
            }
            val sp = if (splits.containsKey(page)) null else findSplit(bmp)
            main.post {
                cache.put(page, bmp)
                if (sp != null) {
                    splits[page] = sp
                    // slices that used the default middle must be re-cut
                    if (half) pages.values.forEach { v -> if (v.segs.any { it.page == page }) v.segs = segsFor(v.pos) }
                }
                if (pv.pagesShown.contains(page)) pv.setOrig(page, bmp)
            }
        }
    }

    inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = PageView(parent.context, this@ViewerActivity)
            v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            v.layoutDirection = View.LAYOUT_DIRECTION_LTR
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val v = h.itemView as PageView
            pages.entries.removeAll { it.value === v }
            v.pos = pos; pages[pos] = v; v.resetZoom(); v.clearBitmaps()
            v.segs = segsFor(pos)
            for (p in v.pagesShown) load(v, p)
            if (pos == pager.currentItem) updatePracticeBar()
        }

        override fun getItemCount() = screens()
    }

    override fun onPause() { super.onPause(); save(); Thread { Backup.sync(applicationContext) }.start(); if (metro.isRunning) { metro.stop(); mPlay.text = "▶ 시작" } }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        try { synchronized(pdf) { pdf.close() }; fd.close() } catch (_: Exception) {}
    }
}

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
    override var erasing = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        val id = intent.getStringExtra("id")!!
        score = Store.loadLibrary(this).first { it.id == id }
        data = Store.loadData(this, id)
        fd = ParcelFileDescriptor.open(File(Store.scoreDir(this), score.file), ParcelFileDescriptor.MODE_READ_ONLY)
        pdf = PdfRenderer(fd)

        findViewById<TextView>(R.id.title).text =
            if (score.composer.isNotEmpty()) "${score.composer} · ${score.title}" else score.title
        pageNo = findViewById(R.id.pageNo); btnRange = findViewById(R.id.btnRange); penBar = findViewById(R.id.penBar)
        pager = findViewById(R.id.pager)
        applyDirection()
        pager.adapter = PageAdapter()
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(data.lastPage.coerceIn(0, pdf.pageCount - 1), false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageNo.text = "${position + 1}/${pdf.pageCount}"; data.lastPage = position; save()
            }
        })
        pageNo.text = "${pager.currentItem + 1}/${pdf.pageCount}"

        btnRange.setOnClickListener { switchMode(if (mode == PageView.Mode.RANGE) PageView.Mode.VIEW else PageView.Mode.RANGE) }
        findViewById<Button>(R.id.btnLayer).setOnClickListener { layerDialog() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { menuDialog() }
        findViewById<Button>(R.id.penBlack).setOnClickListener { pen(Color.BLACK, 0.003f) }
        findViewById<Button>(R.id.penRed).setOnClickListener { pen(Color.rgb(220, 30, 30), 0.003f) }
        findViewById<Button>(R.id.penBlue).setOnClickListener { pen(Color.rgb(30, 80, 220), 0.003f) }
        findViewById<Button>(R.id.penHi).setOnClickListener { pen(Color.argb(90, 255, 220, 0), 0.014f) }
        findViewById<Button>(R.id.penErase).setOnClickListener { erasing = true; toast("지우개: 지울 선을 문지르세요") }
        findViewById<Button>(R.id.penUndo).setOnClickListener {
            val p = pager.currentItem
            val last = data.strokes.lastOrNull { it.page == p }
            if (last != null) { data.strokes.remove(last); onInkChanged() }
        }
        findViewById<Button>(R.id.penDone).setOnClickListener { switchMode(PageView.Mode.VIEW) }
    }

    /** "오른쪽으로 밀면 다음 쪽" = right-to-left page order */
    private fun applyDirection() {
        pager.layoutDirection = if (prefs.getBoolean("swipeRightNext", true)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun pen(c: Int, w: Float) { penColor = c; penWidth = w; erasing = false }

    private fun switchMode(m: PageView.Mode) {
        mode = m
        pager.isUserInputEnabled = m == PageView.Mode.VIEW
        penBar.visibility = if (m == PageView.Mode.PEN) View.VISIBLE else View.GONE
        btnRange.text = if (m == PageView.Mode.RANGE) "범위 끝" else "범위"
        if (m == PageView.Mode.RANGE) toast("연습할 부분을 손가락으로 끌어 네모를 그리세요")
        if (m == PageView.Mode.PEN) { erasing = false; if (!data.showInk) { data.showInk = true; redraw() } }
        if (m == PageView.Mode.VIEW) save()
    }

    // ---- PageView.Host
    override fun onDoubleTap(page: Int) { if (mode == PageView.Mode.VIEW) { switchMode(PageView.Mode.PEN); toast("필기 모드 — 다 쓰면 '완료'") } }

    override fun onRangeTap(r: Range) {
        r.count++; r.log.add(System.currentTimeMillis()); save(); redraw()
        toast(if (r.count == r.target) "목표 ${r.target}회 달성!" else "${r.name.ifEmpty { "범위" }} ${r.count}/${r.target}")
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
                save(); redraw()
            }.show()
    }

    override fun onRangeDrawn(page: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val n = data.ranges.count { it.page == page } + 1
        rangeDialog("${page + 1}쪽-$n", prefs.getInt("defaultTarget", 10)) { name, t ->
            data.ranges.add(Range(Store.newId(), data.activeLayer, page, x0, y0, x1, y1, name, t))
            prefs.edit().putInt("defaultTarget", t).apply()
            save(); redraw()
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
                    save(); redraw()
                } else toast("레이어가 하나뿐이라 지울 수 없습니다")
            }
        }.show()
    }

    private fun menuDialog() {
        val dir = if (prefs.getBoolean("swipeRightNext", true)) "지금: 오른쪽으로 밀면 다음 쪽" else "지금: 왼쪽으로 밀면 다음 쪽"
        AlertDialog.Builder(this).setItems(arrayOf("연습 범위 목록", "쪽 이동", "넘김 방향 바꾸기 ($dir)", "이 쪽 필기 모두 지우기")) { _, w ->
            when (w) {
                0 -> rangeList()
                1 -> { val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "1 ~ ${pdf.pageCount}" }
                    AlertDialog.Builder(this).setTitle("쪽 이동").setView(e).setPositiveButton("이동") { _, _ ->
                        e.text.toString().toIntOrNull()?.let { pager.setCurrentItem((it - 1).coerceIn(0, pdf.pageCount - 1), false) }
                    }.show() }
                2 -> { prefs.edit().putBoolean("swipeRightNext", !prefs.getBoolean("swipeRightNext", true)).apply(); applyDirection() }
                3 -> { val p = pager.currentItem
                    AlertDialog.Builder(this).setMessage("${p + 1}쪽의 필기를 모두 지울까요?").setPositiveButton("지우기") { _, _ ->
                        data.strokes.removeAll { it.page == p }; onInkChanged() }.setNegativeButton("취소", null).show() }
            }
        }.show()
    }

    private fun rangeList() {
        val rs = data.ranges.sortedWith(compareBy({ it.page }, { it.y0 }, { it.x0 }))
        if (rs.isEmpty()) { toast("연습 범위가 없습니다. '범위'를 눌러 만드세요"); return }
        val items = rs.map { r ->
            val l = data.layer(r.layer)?.name ?: ""
            "${r.page + 1}쪽  ${r.name}  [${l}]  ${r.count}/${r.target}" + if (r.count >= r.target) " ✓" else ""
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("연습 범위 (누르면 그 쪽으로)").setItems(items) { _, i ->
            pager.setCurrentItem(rs[i].page, false)
        }.setPositiveButton("닫기", null).show()
    }

    // ---- pages
    private fun redraw() { pages.values.forEach { it.invalidate() } }
    private fun save() = Store.saveData(this, score.id, data)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun load(pv: PageView, page: Int) {
        cache.get(page)?.let { pv.bitmap = it; return }
        pv.bitmap = null
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
            main.post { cache.put(page, bmp); if (pv.page == page) pv.bitmap = bmp }
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
            v.page = pos; pages[pos] = v; load(v, pos)
        }

        override fun getItemCount() = pdf.pageCount
    }

    override fun onPause() { super.onPause(); save() }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        try { synchronized(pdf) { pdf.close() }; fd.close() } catch (_: Exception) {}
    }
}

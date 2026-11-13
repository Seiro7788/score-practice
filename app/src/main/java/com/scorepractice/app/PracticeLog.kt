package com.scorepractice.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/** practice statistics from the +1 log of every range */
object PracticeStats {
    fun dayKey(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    fun today() = dayKey(System.currentTimeMillis())

    /** day keys for the last n days, oldest first */
    fun lastDays(n: Int): List<Pair<Int, Calendar>> = (n - 1 downTo 0).map { back ->
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }
        dayKey(c.timeInMillis) to c
    }

    fun perDay(ranges: List<Range>): Map<Int, Int> =
        ranges.flatMap { it.log }.groupingBy { dayKey(it) }.eachCount()

    /** consecutive days with practice, counting back from today (or yesterday if nothing yet today) */
    fun streak(per: Map<Int, Int>): Int {
        var n = 0; val c = Calendar.getInstance()
        if ((per[dayKey(c.timeInMillis)] ?: 0) == 0) c.add(Calendar.DAY_OF_YEAR, -1)
        while ((per[dayKey(c.timeInMillis)] ?: 0) > 0) { n++; c.add(Calendar.DAY_OF_YEAR, -1) }
        return n
    }

    fun ago(t: Long): String {
        val d = (today() - dayKey(t)).let { if (it < 0) 365 + it else it }
        return when (d) { 0 -> "오늘"; 1 -> "어제"; else -> "${d}일 전" }
    }

    /** one line for the library list */
    fun summary(d: ScoreData): String {
        val per = perDay(d.ranges); val total = d.ranges.sumOf { it.log.size }
        if (total == 0) return ""
        val last = d.ranges.flatMap { it.log }.maxOrNull()
        return "오늘 ${per[today()] ?: 0}회 · 총 ${total}회" + (if (last != null) " · 마지막 ${ago(last)}" else "")
    }
}

/** bar chart of the last 14 days */
class DayChart(context: Context, private val counts: IntArray, private val labels: List<String>, private val todayIdx: Int) : View(context) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.DKGRAY }

    override fun onDraw(c: Canvas) {
        val n = counts.size; if (n == 0) return
        val mx = maxOf(1, counts.maxOrNull() ?: 1)
        val w = width.toFloat() / n; val top = 34f; val bottom = height - 36f
        txt.textSize = minOf(26f, w * 0.55f)
        for (i in 0 until n) {
            val h = (bottom - top) * counts[i] / mx
            bar.color = if (i == todayIdx) Color.rgb(255, 125, 125) else Color.rgb(80, 135, 255)
            c.drawRoundRect(RectF(i * w + w * 0.18f, bottom - h, (i + 1) * w - w * 0.18f, bottom), 6f, 6f, bar)
            if (counts[i] > 0) c.drawText(counts[i].toString(), i * w + w / 2, bottom - h - 6f, txt)
            c.drawText(labels[i], i * w + w / 2, height - 8f, txt)
        }
    }
}

/** full-screen practice record for one score */
object PracticeLog {
    fun show(a: Activity, title: String, data: ScoreData, onJump: (Range) -> Unit, onEdit: (Range) -> Unit) {
        val dlg = Dialog(a, android.R.style.Theme_Material_Light_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dp = { v: Int -> (v * a.resources.displayMetrics.density).toInt() }
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // header
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(4), dp(8)); setBackgroundColor(Color.rgb(238, 238, 245)) }
        head.addView(TextView(a).apply { text = "연습 기록 · $title"; textSize = 17f; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Button(a).apply { text = "닫기"; setOnClickListener { dlg.dismiss() } })
        root.addView(head)

        val scroll = ScrollView(a); val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        var layerFilter: String? = null
        fun render() {
            body.removeAllViews()
            val rs = data.ranges.filter { layerFilter == null || it.layer == layerFilter }
            val per = PracticeStats.perDay(rs); val today = PracticeStats.today()
            val days = PracticeStats.lastDays(14)
            val week = days.takeLast(7).sumOf { per[it.first] ?: 0 }

            // summary tiles
            val tiles = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, value) in listOf("오늘" to "${per[today] ?: 0}회", "최근 7일" to "${week}회",
                    "연속" to "${PracticeStats.streak(per)}일", "목표 달성" to "${rs.count { it.count >= it.target }}/${rs.size}")) {
                tiles.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6))
                    addView(TextView(a).apply { text = value; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER })
                    addView(TextView(a).apply { text = label; textSize = 12f; alpha = 0.7f; gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(tiles)

            // last 14 days chart
            val labels = days.map { (_, c) -> "${c.get(Calendar.DAY_OF_MONTH)}" }
            body.addView(DayChart(a, IntArray(14) { per[days[it].first] ?: 0 }, labels, 13), LinearLayout.LayoutParams(-1, dp(150)))
            body.addView(TextView(a).apply { text = "최근 14일 연습 횟수 (분홍 = 오늘)"; textSize = 12f; alpha = 0.6f; gravity = Gravity.CENTER })

            // layer filter chips
            val chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4)) }
            val opts = listOf<Pair<String?, String>>(null to "전체") + data.layers.map { it.id to it.name }
            for ((id, name) in opts) chips.addView(Button(a).apply {
                text = name; isAllCaps = false; alpha = if (id == layerFilter) 1f else 0.5f
                setOnClickListener { layerFilter = id; render() }
            })
            body.addView(HorizontalScrollView(a).apply { addView(chips) })

            // ranges: page order, progress bars; tap = go there, long-press = edit
            if (rs.isEmpty()) body.addView(TextView(a).apply { text = "\n연습 범위가 없습니다. 악보에서 [범위]를 눌러 만드세요."; textSize = 15f })
            for (r in rs.sortedWith(compareBy({ it.page }, { it.y0 }, { it.x0 }))) {
                val col = data.layer(r.layer)?.color ?: Color.GRAY
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)); gravity = Gravity.CENTER_VERTICAL
                    isClickable = true; setBackgroundResource(android.R.drawable.list_selector_background)
                }
                row.addView(View(a).apply { setBackgroundColor(col) }, LinearLayout.LayoutParams(dp(6), dp(48)).apply { marginEnd = dp(10) })
                val mid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                val done = r.count >= r.target
                mid.addView(TextView(a).apply { text = "${r.page + 1}쪽 · ${r.name.ifEmpty { "연습 범위" }}" + if (done) "  ✓" else ""; textSize = 16f })
                mid.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = maxOf(1, r.target); progress = minOf(r.count, r.target)
                }, LinearLayout.LayoutParams(-1, dp(14)))
                val todayN = r.log.count { PracticeStats.dayKey(it) == today }
                val last = r.log.maxOrNull()
                mid.addView(TextView(a).apply {
                    text = "${r.count} / ${r.target}회 · 오늘 ${todayN}회" + (if (last != null) " · 마지막 ${PracticeStats.ago(last)}" else " · 아직 안 함")
                    textSize = 13f; alpha = 0.75f
                })
                row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener { dlg.dismiss(); onJump(r) }
                row.setOnLongClickListener { dlg.dismiss(); onEdit(r); true }
                body.addView(row)
            }
            body.addView(TextView(a).apply { text = "\n누르면 그 쪽으로 이동 · 길게 누르면 수정"; textSize = 12f; alpha = 0.6f; gravity = Gravity.CENTER })
        }
        render()
        dlg.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        dlg.show()
    }
}

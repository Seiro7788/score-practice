package com.scorepractice.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * The MusicXML drawn as a score. Tap a measure = start there, tap another = end there and play;
 * tap the same measure twice = play from there to the end; tap while playing = stop.
 * The measure being played is highlighted and followed.
 */
class XmlViewActivity : AppCompatActivity() {
    private lateinit var score: Score
    private lateinit var data: ScoreData
    private lateinit var sc: XScore
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var tempo: android.widget.EditText
    private var playA = -1; private var playB = -1
    private val main = Handler(Looper.getMainLooper())
    private val players = mutableListOf<MediaPlayer>()
    private var from = -1
    private var times: List<Pair<Int, Double>> = emptyList()
    private var loop = false
    private var bpm = 100
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var scoreZoom = 0.55

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")!!
        score = Store.loadLibrary(this).first { it.id == id }
        data = Store.loadData(this, id)
        val f = File(Store.scoreDir(this), "$id.musicxml")
        sc = try { MusicXml.load(f) } catch (e: Exception) { Toast.makeText(this, "XML을 읽지 못했습니다", Toast.LENGTH_LONG).show(); finish(); return }
        scoreZoom = prefs.getFloat("xmlZoom", if (resources.displayMetrics.widthPixels > 1400) 0.8f else 0.55f).toDouble()
        bpm = xmlTempo(this, data, sc)
        title = "악보(XML) · " + score.shownTitle

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        // top row: status + score size (redraw bigger / smaller; two fingers also zoom the page)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(238, 246, 238)) }
        status = TextView(this).apply { setPadding(dp(12), dp(6), dp(6), dp(6)); textSize = 14f; setTextColor(Color.rgb(30, 30, 30)) }
        top.addView(Button(this).apply { text = "◀ 뒤로"; isAllCaps = false; minWidth = 0; minimumWidth = 0; setOnClickListener { finish() } })
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        stopBtn = Button(this).apply { text = "■ 정지"; isAllCaps = false; minWidth = 0; minimumWidth = 0; isEnabled = false; setOnClickListener { stop() } }
        top.addView(stopBtn)
        for ((t, f) in listOf("가−" to 0.85, "가+" to 1.18)) top.addView(Button(this).apply {
            text = t; isAllCaps = false; minWidth = 0; minimumWidth = 0; setOnClickListener { zoomBy(f) } })
        root.addView(top)
        // long scores are drawn one part at a time: a movement (or a chosen range of measures)
        setupSections(id)
        if (secs.isNotEmpty()) {
            val srow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(238, 246, 238)) }
            fun b(t: String, on: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; minWidth = 0; minimumWidth = 0; setOnClickListener { on() } }
            srow.addView(b("◀") { stepSection(-1) })
            secBtn = b("") { chooseSection() }
            srow.addView(secBtn, LinearLayout.LayoutParams(0, -2, 1f))
            srow.addView(b("▶") { stepSection(1) })
            root.addView(srow); updateSecLabel()
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.setSupportZoom(true); web.settings.builtInZoomControls = true; web.settings.displayZoomControls = false   // pinch
        web.addJavascriptInterface(Bridge(xmlText = MusicXml.text(f)), "Android")
        web.loadUrl("file:///android_asset/osmd/view.html")
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        // controls
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)); setBackgroundColor(Color.rgb(243, 238, 248)) }
        val hands = arrayOf("오른손", "왼손", "양손"); val sounds = arrayOf("피아노", "계이름", "계이름+피아노")
        fun small(t: String, on: (Button) -> Unit) = Button(this).apply { text = t; isAllCaps = false; minWidth = 0; minimumWidth = 0; setOnClickListener { on(this) } }
        bar.addView(small(hands[data.playHands]) { b -> data.playHands = (data.playHands + 1) % 3; b.text = hands[data.playHands]; saveData(); stop() })
        bar.addView(small(sounds[data.playSound]) { b -> data.playSound = (data.playSound + 1) % 3; b.text = sounds[data.playSound]; saveData(); stop() })
        bar.addView(CheckBox(this).apply {
            text = "선율"; isChecked = prefs.getBoolean("xmlMelody", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("xmlMelody", v).apply(); js("melodyOn=$v; drawMelody($v)") }
        })
        bar.addView(CheckBox(this).apply {
            text = "셈여림"; isChecked = prefs.getBoolean("xmlDyn", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("xmlDyn", v).apply(); js("toggleDyn($v)") }
        })
        bar.addView(CheckBox(this).apply {
            text = "반음색"; isChecked = prefs.getBoolean("xmlColors", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("xmlColors", v).apply(); js("setColors($v)") }
        })
        bar.addView(CheckBox(this).apply {
            text = "코드"; isChecked = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("xmlChords", true)
            setOnCheckedChangeListener { _, v -> getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("xmlChords", v).apply(); js("toggleChords($v)") }
        })
        fingerBtn = small("손번호") { setFingerMode(!fingerMode) }
        bar.addView(fingerBtn)
        bar.addView(CheckBox(this).apply { text = "반복"; setOnCheckedChangeListener { _, v -> loop = v; players.forEach { it.isLooping = v } } })
        root.addView(bar)
        // tempo row: −5 −1 [♩= typed number] +1 +5 [악보 빠르기]
        val tbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(2)); setBackgroundColor(Color.rgb(243, 238, 248)) }
        tbar.addView(small("−5") { setBpm(bpm - 5) }); tbar.addView(small("−1") { setBpm(bpm - 1) })
        tbar.addView(TextView(this).apply { text = " ♩="; textSize = 16f })
        tempo = android.widget.EditText(this).apply {
            textSize = 18f; gravity = Gravity.CENTER; inputType = android.text.InputType.TYPE_CLASS_NUMBER; minEms = 2
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE; setSelectAllOnFocus(true)
            setOnEditorActionListener { v, _, _ ->
                text.toString().toIntOrNull()?.let { setBpm(it) }
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                clearFocus(); true
            }
        }
        tbar.addView(tempo, LinearLayout.LayoutParams(0, -2, 1f))
        tbar.addView(small("확인") {
            tempo.text.toString().toIntOrNull()?.let { setBpm(it) } ?: tempo.setText(bpm.toString())
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(tempo.windowToken, 0)
            tempo.clearFocus()
        })
        tbar.addView(small("+1") { setBpm(bpm + 1) }); tbar.addView(small("+5") { setBpm(bpm + 5) })
        if (sc.tempo > 0) tbar.addView(small("악보 ♩=${sc.tempo}") { setBpm(sc.tempo) })
        root.addView(tbar)
        // fingering tools (shown in fingering mode)
        fbarWrap = android.widget.HorizontalScrollView(this).apply { setBackgroundColor(Color.rgb(255, 243, 224)); visibility = android.view.View.GONE }
        val fbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        fbar.addView(small("PDF에서 읽기") { askRange("PDF에서 손번호 읽어오기") { a, b -> readPdf(a, b) } })
        fbar.addView(small("비슷한 곳에서") { askRange("같거나 비슷한 곳의 손번호 불러오기") { a, b -> fromSimilar(a, b) } })
        applyBtn = small("제안 적용") { applyProposals(true) }; fbar.addView(applyBtn)
        dropBtn = small("제안 버리기") { applyProposals(false) }; fbar.addView(dropBtn)
        fbar.addView(small("↶") { undoF() }); fbar.addView(small("↷") { redoF() })
        fbar.addView(small("숫자−") { scaleF(0.85f) }); fbar.addView(small("숫자+") { scaleF(1.18f) })
        fbar.addView(small("완료") { setFingerMode(false) })
        fbarWrap.addView(fbar); root.addView(fbarWrap)
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        setBpm(bpm); idleText()
    }

    private fun zoomBy(f: Double) {
        scoreZoom = (scoreZoom * f).coerceIn(0.3, 2.0)
        prefs.edit().putFloat("xmlZoom", scoreZoom.toFloat()).apply()
        js("setZoom($scoreZoom)")
    }

    private fun saveData() = Store.saveData(this, score.id, data)

    private fun setBpm(v: Int) {
        bpm = v.coerceIn(20, 300)
        if (tempo.text.toString() != bpm.toString()) tempo.setText(bpm.toString())
        if (data.xmlBpm != bpm) { data.xmlBpm = bpm; saveData() }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("xml_tempo_last", bpm).apply()
        // playing: carry on at the new tempo from the measure being played
        if (players.isNotEmpty()) { val at = if (lastMark >= 0) lastMark else playA; stopPlayers(); play(at, playB) }
    }

    private fun idleText() { status.text = "시작할 마디를 누르세요" }

    private fun js(s: String) = web.evaluateJavascript(s, null)

    inner class Bridge(private val xmlText: String) {
        @JavascriptInterface fun xml(): String = xmlText
        @JavascriptInterface fun zoom(): Double = scoreZoom
        @JavascriptInterface fun onReady(n: Int) { main.post { status.text = "${n}마디 · 시작할 마디를 누르세요" } }
        @JavascriptInterface fun onError(msg: String) { main.post { status.text = "악보를 그리지 못했습니다: $msg" } }
        @JavascriptInterface fun onMeasure(i: Int) { main.post { tapped(i) } }
        @JavascriptInterface fun showMelody(): Boolean = prefs.getBoolean("xmlMelody", true)
        @JavascriptInterface fun showDyn(): Boolean = prefs.getBoolean("xmlDyn", true)
        @JavascriptInterface fun dynamics(): String {
            val a = org.json.JSONArray()
            try {
                for (p in Dynamics.curve(sc)) a.put(org.json.JSONObject().put("m", p.m).put("beat", p.beat).put("v", p.v)
                    .put("L", sc.measures[p.m].length))
                for ((m, b) in Dynamics.accents(sc)) a.put(org.json.JSONObject().put("m", m).put("beat", b).put("acc", 1).put("L", sc.measures[m].length))
                for ((s0, e0, cresc) in Dynamics.hairpins(sc)) a.put(org.json.JSONObject().put("m", s0.first).put("beat", s0.second)
                    .put("m2", e0.first).put("beat2", e0.second).put("pin", if (cresc) 1 else -1)
                    .put("L", sc.measures[s0.first].length).put("L2", sc.measures[e0.first].length))
            } catch (e: Exception) {}
            return a.toString()
        }
        @JavascriptInterface fun range(): String = "[$curA,$curB]"
        @JavascriptInterface fun onSection(a: Int, b: Int) { main.post { switching = false; updateSecLabel(); if (players.isEmpty() && !fingerMode) idleText() } }
        @JavascriptInterface fun xfingers(): String = fingersJson()
        @JavascriptInterface fun fingerScale(): Double = data.fingerScale.toDouble()
        @JavascriptInterface fun fingerTap(m: Int, t: Double, k: Int) { main.post { tapFinger(m, t, k) } }
        @JavascriptInterface fun fingerSet(m: Int, t: Double, k: Int, n: Int) { main.post { setFinger(m, t, k, n) } }
        @JavascriptInterface fun fingerExit() { main.post { setFingerMode(false) } }
        @JavascriptInterface fun showColors(): Boolean = prefs.getBoolean("xmlColors", true)
        @JavascriptInterface fun showChords(): Boolean = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("xmlChords", true)
        @JavascriptInterface fun chords(): String {
            val a = org.json.JSONArray()
            try {
                for (l in Harmony.analyse(sc)) a.put(org.json.JSONObject().put("m", l.measure).put("beat", l.beat).put("name", l.name).put("roman", l.roman))
            } catch (e: Exception) {}
            return a.toString()
        }
    }

    private fun label(i: Int) = sc.measures.getOrNull(i)?.number ?: "${i + 1}"

    private fun tapped(i: Int) {
        if (players.isNotEmpty()) { stop(); return }
        if (from < 0) {
            from = i; js("mark('from',$i)")
            status.text = "${label(i)}마디부터 · 끝 마디를 누르세요 (같은 마디를 한 번 더 = 끝까지)"
            return
        }
        val a = minOf(from, i); val b = if (i == from) -1 else maxOf(from, i)
        from = -1
        js("mark('from',$a)"); js("mark('to',${if (b < 0) -1 else b})")
        play(a, b)
    }

    private fun play(a: Int, b: Int) {
        playA = a; playB = b
        val ch = PlayChoice(data.playHands, loop = loop, fromIdx = a, toIdx = b)
        val sound = data.playSound
        status.text = "준비 중… (■ 정지로 취소)"
        val token = ++playToken; stopBtn.isEnabled = true
        Thread {
            val err = try {
                val tl = Timeline.build(sc, ch, bpm)
                if (tl.isEmpty()) "이 범위에 재생할 음표가 없습니다" else {
                    val files = mutableListOf<File>()
                    if (sound != 1) { val f = File(cacheDir, "xplay.mid"); MidiPlayer.write(tl, f); files.add(f) }
                    if (sound != 0) { val f = File(cacheDir, "xsing.wav"); if (Singer.render(this, Timeline.melody(tl), f, score.composer)) files.add(f) }
                    times = Timeline.measureTimes(sc, ch, bpm)
                    main.post {
                        if (token != playToken) return@post      // stopped while getting ready
                        stopPlayers()
                        for (f in files) players.add(MidiPlayer.player(this, f, loop).apply { setOnCompletionListener { if (!loop) stop() } })
                        players.reversed().forEach { it.start() }   // voice first, piano right after
                        status.text = "${label(a)}~${if (b < 0) "끝" else label(b)}마디 재생 중 · 아무 곳이나 누르면 멈춤"
                        follow()
                    }
                    null
                }
            } catch (e: Throwable) { if (e is OutOfMemoryError) "곡이 너무 길어 메모리가 모자랍니다. 마디 범위를 줄여 주세요" else (e.message ?: e.toString()) }
            if (err != null) main.post { status.text = err }
        }.start()
    }

    private var lastMark = -1
    private val follower = object : Runnable {
        override fun run() {
            val p = players.firstOrNull() ?: return
            val t = try { p.currentPosition / 1000.0 } catch (e: Exception) { return }
            val m = times.lastOrNull { it.second <= t + 0.02 }?.first ?: -1
            if (m != lastMark) {
                lastMark = m
                // the measure being played is in another part of a long score: show that part
                if (m >= 0 && curA >= 0 && (m < curA || m > curB) && !switching) {
                    val sct = secs.firstOrNull { m in it.first..it.second }
                    switching = true
                    if (sct != null) showSection(sct.first, sct.second) else showSection(m, minOf(sc.measures.size - 1, m + 59))
                }
                js("mark('play',$m,true)")
            }
            main.postDelayed(this, 80)
        }
    }

    private fun follow() { lastMark = -1; main.removeCallbacks(follower); main.post(follower) }

    private fun stopPlayers() {
        main.removeCallbacks(follower)
        for (p in players) try { p.stop(); p.release() } catch (_: Exception) {}
        players.clear()
    }

    private fun stop() {
        playToken++; if (::stopBtn.isInitialized) stopBtn.isEnabled = false
        stopPlayers(); from = -1
        js("mark('play',-1)"); js("mark('from',-1)"); js("mark('to',-1)")
        idleText()
    }

    override fun onPause() { super.onPause(); stop() }

    // ---------------------------------------------------------------- playing / parts of long scores
    private var playToken = 0
    private lateinit var stopBtn: Button
    private var secs: List<Triple<Int, Int, String>> = emptyList()
    private var curA = -1; private var curB = -1
    private var switching = false
    private var secBtn: Button? = null
    private var scoreId = ""

    private fun setupSections(id: String) {
        scoreId = id
        val n = sc.measures.size
        val starts = sc.movementStarts()
        if (n <= 100 && starts.size <= 1) return
        secs = if (starts.size > 1) starts.mapIndexed { k, a ->
            val b = (starts.getOrNull(k + 1) ?: n) - 1
            val w = sc.measures[a].words?.takeIf { it.contains("악장") || it.contains("번") || it.contains("No", true) }
            Triple(a, b, w?.take(22) ?: "${k + 1}부분")
        } else (0 until n step 60).map { a -> Triple(a, minOf(n - 1, a + 59), "${label(a)}–${label(minOf(n - 1, a + 59))}마디") }
        val saved = prefs.getString("xmlSec_$id", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (saved != null && saved.size == 2 && saved[0] in -1 until n) { curA = saved[0]; curB = saved[1].coerceIn(saved[0], n - 1) }
        else { curA = secs[0].first; curB = secs[0].second }
        if (curA < 0) curB = -1
    }

    private fun updateSecLabel() {
        val b = secBtn ?: return
        b.text = if (curA < 0) "전체 ${sc.measures.size}마디 ▾" else {
            val nm = secs.firstOrNull { it.first == curA && it.second == curB }?.third
            (if (nm != null && !nm.endsWith("마디")) "$nm · " else "") + "${label(curA)}–${label(curB)}마디 ▾"
        }
    }

    private fun showSection(a: Int, b: Int) {
        curA = a; curB = if (a < 0) -1 else b
        prefs.edit().putString("xmlSec_$scoreId", "$curA,$curB").apply()
        status.text = "악보 그리는 중…"; updateSecLabel()
        js("showRange($curA,$curB)")
    }

    private fun stepSection(d: Int) {
        if (curA < 0) { showSection(secs[0].first, secs[0].second); return }
        val i = secs.indexOfFirst { it.first == curA && it.second == curB }
        if (i >= 0) { secs.getOrNull(i + d)?.let { showSection(it.first, it.second) } ?: toast(if (d > 0) "마지막 부분입니다" else "첫 부분입니다"); return }
        val len = curB - curA + 1; val a = (curA + d * len).coerceIn(0, maxOf(0, sc.measures.size - len))
        showSection(a, minOf(sc.measures.size - 1, a + len - 1))
    }

    private fun chooseSection() {
        val items = secs.map { (a, b, nm) -> (if (nm.endsWith("마디")) "" else "$nm  ") + "(${label(a)}–${label(b)}마디)" } +
            listOf("직접 정하기 (시작·끝 마디)", "전체 보기 (곡이 길면 오래 걸립니다)")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("어느 부분을 볼까요?")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i < secs.size -> showSection(secs[i].first, secs[i].second)
                    i == secs.size -> customSection()
                    else -> showSection(-1, -1)
                }
            }.show()
    }

    private fun customSection() {
        val a0 = if (curA >= 0) curA else 0; val b0 = if (curA >= 0) curB else minOf(sc.measures.size - 1, 59)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun num(v: String) = android.widget.EditText(this).apply { setText(v); inputType = android.text.InputType.TYPE_CLASS_TEXT; minEms = 3; gravity = Gravity.CENTER; setSelectAllOnFocus(true) }
        val ea = num(label(a0)); val eb = num(label(b0))
        row.addView(TextView(this).apply { text = "마디 " }); row.addView(ea); row.addView(TextView(this).apply { text = " ~ " }); row.addView(eb)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("볼 마디 범위").setView(row)
            .setNegativeButton("취소", null)
            .setPositiveButton("보기") { _, _ ->
                val a = idxOf(ea.text.toString(), a0); val b = idxOf(eb.text.toString(), b0)
                showSection(minOf(a, b), maxOf(a, b))
            }.show()
    }

    // ---------------------------------------------------------------- fingering
    private var fingerMode = false
    private lateinit var fingerBtn: Button
    private lateinit var fbarWrap: android.widget.HorizontalScrollView
    private lateinit var applyBtn: Button
    private lateinit var dropBtn: Button
    private val fUndo = ArrayDeque<String>(); private val fRedo = ArrayDeque<String>()
    private var pdfScan: List<FingerRead.PMeasure>? = null

    private fun fingersJson(): String = org.json.JSONArray().apply { data.xfingers.forEach { put(it.toJson()) } }.toString()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun setFingerMode(on: Boolean) {
        if (on) stop()
        fingerMode = on
        fbarWrap.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        fingerBtn.setTextColor(if (on) Color.rgb(200, 30, 30) else Color.BLACK)
        js("setFingerMode($on)")
        status.text = if (on) "음표를 누른 채 긁기 (누르면 방향 안내가 나옵니다) · 숫자 있는 음표를 톡 누르면 지우기" else "시작할 마디를 누르세요"
        if (on) toast("손번호: PDF와 같은 방식 — 음표를 누르고 오른쪽(1)부터 왼쪽 위(5) 방향으로 긁기, 왼쪽으로 긁으면 끝")
        updateF()
    }

    /** remember for undo, save, redraw */
    private fun changeF(before: String) {
        fUndo.addLast(before); if (fUndo.size > 100) fUndo.removeFirst(); fRedo.clear()
        Store.saveData(this, score.id, data); updateF()
    }

    private fun updateF() {
        val np = data.xfingers.count { it.p }
        applyBtn.text = if (np > 0) "제안 적용 ($np)" else "제안 적용"
        applyBtn.isEnabled = np > 0; dropBtn.isEnabled = np > 0
        js("setFingers(${fingersJson()}, ${data.fingerScale})")
    }

    private fun restoreF(json: String) {
        data.xfingers.clear()
        val a = org.json.JSONArray(json); for (i in 0 until a.length()) data.xfingers.add(XFinger.of(a.getJSONObject(i)))
        Store.saveData(this, score.id, data); updateF()
    }
    private fun undoF() { val p = fUndo.removeLastOrNull() ?: run { toast("되돌릴 것이 없습니다"); return }; fRedo.addLast(fingersJson()); restoreF(p) }
    private fun redoF() { val p = fRedo.removeLastOrNull() ?: run { toast("다시 할 것이 없습니다"); return }; fUndo.addLast(fingersJson()); restoreF(p) }

    private fun scaleF(f: Float) { data.fingerScale = (data.fingerScale * f).coerceIn(0.15f, 2.5f); Store.saveData(this, score.id, data); updateF() }

    private fun tapFinger(m: Int, t: Double, k: Int) {
        val key = XFinger(m, t, k, 0)
        if (data.xfingers.none { it.same(key) }) { status.text = "숫자를 넣으려면 음표를 누른 채 긁으세요 (방향 안내가 나옵니다)"; return }
        val before = fingersJson(); data.xfingers.removeAll { it.same(key) }; changeF(before)
    }

    private fun setFinger(m: Int, t: Double, k: Int, n: Int) {
        val before = fingersJson(); val f = XFinger(m, t, k, n)
        data.xfingers.removeAll { it.same(f) }; data.xfingers.add(f); changeF(before)
    }

    private fun applyProposals(keep: Boolean) {
        val before = fingersJson()
        if (keep) data.xfingers.forEach { it.p = false } else data.xfingers.removeAll { it.p }
        changeF(before)
        status.text = if (keep) "제안을 적용했습니다 (↶ 로 되돌리기)" else "제안을 버렸습니다"
    }

    /** proposals replace older proposals on the same notes; notes with an accepted number keep it */
    private fun addProposals(list: List<XFinger>, what: String) {
        if (list.isEmpty()) { status.text = "$what: 찾은 손번호가 없습니다"; return }
        val before = fingersJson()
        data.xfingers.removeAll { o -> o.p && list.any { it.same(o) } }
        data.xfingers.addAll(list.filter { f -> data.xfingers.none { !it.p && it.same(f) } })
        changeF(before)
        status.text = "$what: 주황색 제안 ${list.size}개 · 틀린 건 눌러 지우거나 긁어 고친 뒤 '제안 적용'"
    }

    private fun idxOf(txt: String, def: Int): Int {
        val t = txt.trim(); if (t.isEmpty()) return def
        sc.measures.indexOfFirst { it.number == t }.let { if (it >= 0) return it }
        t.toIntOrNull()?.let { n -> sc.indexOf(n).let { if (it >= 0) return it }; return (n - 1).coerceIn(0, sc.measures.size - 1) }
        return def
    }

    /** measure range (printed numbers); starts with the range last chosen for playing, else the whole piece */
    private fun askRange(title: String, go: (Int, Int) -> Unit) {
        val a0 = if (playA >= 0) playA else 0; val b0 = if (playA >= 0 && playB >= 0) playB else sc.measures.size - 1
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun num(v: String) = android.widget.EditText(this).apply { setText(v); inputType = android.text.InputType.TYPE_CLASS_TEXT; minEms = 3; gravity = Gravity.CENTER; setSelectAllOnFocus(true) }
        val ea = num(label(a0)); val eb = num(label(b0))
        row.addView(TextView(this).apply { text = "마디 " }); row.addView(ea); row.addView(TextView(this).apply { text = " ~ " }); row.addView(eb)
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setView(row)
            .setNeutralButton("곡 전체") { _, _ -> go(0, sc.measures.size - 1) }
            .setNegativeButton("취소", null)
            .setPositiveButton("읽기") { _, _ ->
                val a = idxOf(ea.text.toString(), 0); val b = idxOf(eb.text.toString(), sc.measures.size - 1)
                go(minOf(a, b), maxOf(a, b))
            }.show()
    }

    private fun fromSimilar(a: Int, b: Int) {
        if (data.xfingers.none { !it.p }) { status.text = "먼저 손번호를 몇 군데 넣어 주세요 (적용된 손번호를 보고 비슷한 곳을 찾습니다)"; return }
        addProposals(FingerRead.fromSimilar(sc, data.xfingers, a, b), "비슷한 곳에서")
    }

    private fun readPdf(a: Int, b: Int) {
        val pdf = File(Store.scoreDir(this), score.file)
        if (!pdf.exists()) { status.text = "이 곡의 PDF가 없습니다"; return }
        status.text = "PDF를 살펴보는 중…"
        Thread {
            try {
                val pms = pdfScan ?: run {
                    val all = mutableListOf<FingerRead.PMeasure>()
                    android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        android.graphics.pdf.PdfRenderer(fd).use { r ->
                            for (p in 0 until r.pageCount) {
                                main.post { status.text = "PDF를 살펴보는 중… ${p + 1}/${r.pageCount}쪽" }
                                r.openPage(p).use { pg ->
                                    val w = 1800; val h = (w.toLong() * pg.height / pg.width).toInt()
                                    val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                                    bm.eraseColor(Color.WHITE); pg.render(bm, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    all.addAll(FingerRead.scanPage(bm, p, data.fingers)); bm.recycle()
                                }
                            }
                        }
                    }
                    all.also { pdfScan = it }
                }
                val map = FingerRead.alignMeasures(sc, pms)
                val found = FingerRead.fromPdf(sc, pms, map, a, b, data.xfingers)
                val matched = (a..b).count { map.getOrNull(it) ?: -1 >= 0 }
                main.post {
                    addProposals(found, "PDF에서 (PDF 마디 ${pms.size}개 찾음, 맞춘 마디 $matched/${b - a + 1})")
                }
            } catch (e: Throwable) {
                main.post { status.text = "PDF를 읽지 못했습니다: ${e.message ?: e}" }
            }
        }.start()
    }
}

/** XML tempo: this score's last XML tempo → the last XML tempo used anywhere → the score's own marking → 100 */
fun xmlTempo(c: android.content.Context, d: ScoreData, sc: XScore?): Int {
    if (d.xmlBpm > 0) return d.xmlBpm
    val p = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    if (p.contains("xml_tempo_last")) return p.getInt("xml_tempo_last", 100)
    return if (sc != null && sc.tempo > 0) sc.tempo else 100
}

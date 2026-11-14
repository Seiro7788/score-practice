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
        for ((t, f) in listOf("가−" to 0.85, "가+" to 1.18)) top.addView(Button(this).apply {
            text = t; isAllCaps = false; minWidth = 0; minimumWidth = 0; setOnClickListener { zoomBy(f) } })
        root.addView(top)
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
        status.text = "준비 중…"
        Thread {
            val err = try {
                val tl = Timeline.build(sc, ch, bpm)
                if (tl.isEmpty()) "이 범위에 재생할 음표가 없습니다" else {
                    val files = mutableListOf<File>()
                    if (sound != 1) { val f = File(cacheDir, "xplay.mid"); MidiPlayer.write(tl, f); files.add(f) }
                    if (sound != 0) { val f = File(cacheDir, "xsing.wav"); if (Singer.render(this, Timeline.melody(tl), f, score.composer)) files.add(f) }
                    times = Timeline.measureTimes(sc, ch, bpm)
                    main.post {
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
            if (m != lastMark) { lastMark = m; js("mark('play',$m,true)") }
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
        stopPlayers(); from = -1
        js("mark('play',-1)"); js("mark('from',-1)"); js("mark('to',-1)")
        idleText()
    }

    override fun onPause() { super.onPause(); stop() }
}

/** XML tempo: this score's last XML tempo → the last XML tempo used anywhere → the score's own marking → 100 */
fun xmlTempo(c: android.content.Context, d: ScoreData, sc: XScore?): Int {
    if (d.xmlBpm > 0) return d.xmlBpm
    val p = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    if (p.contains("xml_tempo_last")) return p.getInt("xml_tempo_last", 100)
    return if (sc != null && sc.tempo > 0) sc.tempo else 100
}

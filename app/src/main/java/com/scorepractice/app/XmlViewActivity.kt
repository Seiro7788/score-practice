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
    private lateinit var tempo: TextView
    private val main = Handler(Looper.getMainLooper())
    private val players = mutableListOf<MediaPlayer>()
    private var from = -1
    private var times: List<Pair<Int, Double>> = emptyList()
    private var loop = false
    private var bpm = 100

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")!!
        score = Store.loadLibrary(this).first { it.id == id }
        data = Store.loadData(this, id)
        val f = File(Store.scoreDir(this), "$id.musicxml")
        sc = try { MusicXml.load(f) } catch (e: Exception) { Toast.makeText(this, "XML을 읽지 못했습니다", Toast.LENGTH_LONG).show(); finish(); return }
        bpm = if (data.bpm > 0) data.bpm else getSharedPreferences("settings", MODE_PRIVATE).getInt("tempo_last", 100)
        title = "악보(XML) · " + score.shownTitle

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        status = TextView(this).apply { setPadding(dp(12), dp(6), dp(12), dp(6)); textSize = 14f; setBackgroundColor(Color.rgb(238, 246, 238)) }
        root.addView(status)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(xmlText = MusicXml.text(f)), "Android")
        web.loadUrl("file:///android_asset/osmd/view.html")
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        // controls
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)); setBackgroundColor(Color.rgb(243, 238, 248)) }
        val hands = arrayOf("오른손", "왼손", "양손"); val sounds = arrayOf("피아노", "계이름", "계이름+피아노")
        fun small(t: String, on: (Button) -> Unit) = Button(this).apply { text = t; isAllCaps = false; minWidth = 0; minimumWidth = 0; setOnClickListener { on(this) } }
        bar.addView(small(hands[data.playHands]) { b -> data.playHands = (data.playHands + 1) % 3; b.text = hands[data.playHands]; saveData(); stop() })
        bar.addView(small(sounds[data.playSound]) { b -> data.playSound = (data.playSound + 1) % 3; b.text = sounds[data.playSound]; saveData(); stop() })
        bar.addView(CheckBox(this).apply { text = "반복"; setOnCheckedChangeListener { _, v -> loop = v; players.forEach { it.isLooping = v } } })
        bar.addView(small("−") { setBpm(bpm - 5) })
        tempo = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(2), 0, dp(2), 0) }
        bar.addView(tempo)
        bar.addView(small("+") { setBpm(bpm + 5) })
        root.addView(bar)
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        setBpm(bpm); idleText()
    }

    private fun saveData() = Store.saveData(this, score.id, data)

    private fun setBpm(v: Int) {
        bpm = v.coerceIn(20, 300); tempo.text = "♩=$bpm"
        data.bpm = bpm; saveData()
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("tempo_last", bpm).apply()
        if (players.isNotEmpty()) stop()
    }

    private fun idleText() { status.text = "시작할 마디를 누르세요" }

    private fun js(s: String) = web.evaluateJavascript(s, null)

    inner class Bridge(private val xmlText: String) {
        @JavascriptInterface fun xml(): String = xmlText
        @JavascriptInterface fun zoom(): Double = if (resources.displayMetrics.widthPixels > 1400) 0.8 else 0.55
        @JavascriptInterface fun onReady(n: Int) { main.post { status.text = "${n}마디 · 시작할 마디를 누르세요" } }
        @JavascriptInterface fun onError(msg: String) { main.post { status.text = "악보를 그리지 못했습니다: $msg" } }
        @JavascriptInterface fun onMeasure(i: Int) { main.post { tapped(i) } }
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
                        players.forEach { it.start() }
                        status.text = "${label(a)}~${if (b < 0) "끝" else label(b)}마디 재생 중 · 아무 곳이나 누르면 멈춤"
                        follow()
                    }
                    null
                }
            } catch (e: Exception) { e.message ?: e.toString() }
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

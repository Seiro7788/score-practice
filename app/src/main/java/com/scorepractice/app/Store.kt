package com.scorepractice.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** one score in the library */
data class Score(
    val id: String,
    var title: String,
    var composer: String,
    val file: String,
    val added: Long,
    var origName: String = ""     // file name it was imported from (used when no title is given)
) {
    /** title to show: the given title, otherwise the original file name */
    val shownTitle get() = title.ifEmpty { origName.ifEmpty { "제목 없음" } }
}

/** a named practice layer (e.g. "1주차", "왼손") holding practice ranges */
data class Layer(val id: String, var name: String, var color: Int, var visible: Boolean = true)

/** practice range: rectangle on a page, normalized 0..1 coordinates */
data class Range(
    val id: String,
    var layer: String,
    val page: Int,
    var x0: Float, var y0: Float, var x1: Float, var y1: Float,
    var name: String,
    var target: Int,
    var count: Int = 0,
    val log: MutableList<Long> = mutableListOf()
)

/** colour correction on an already-coloured score: a notehead-shaped patch (normalized coords, r = width/page width) */
data class Fix(val page: Int, val x: Float, val y: Float, val r: Float, val color: Int, val hollow: Boolean, val rb: Float = r * 0.71f,
               val auto: Boolean = false)   // auto = made by automatic colouring

/** fingering number placed by the user: centre (normalized), s = staff spacing / page width at that time */
data class Finger(val page: Int, var x: Float, var y: Float, val n: Int, val s: Float)

/** fingering on a note of the attached MusicXML: measure index (file order), onset in quarters within the measure,
 *  MIDI pitch, finger 1..5. p = only proposed (read from the PDF / copied from a similar passage), not yet accepted */
data class XFinger(val m: Int, val t: Double, val k: Int, var n: Int, var p: Boolean = false) {
    fun same(o: XFinger) = m == o.m && k == o.k && kotlin.math.abs(t - o.t) < 0.01
    fun toJson(): JSONObject = JSONObject().put("m", m).put("t", t).put("k", k).put("n", n).put("p", p)
    companion object { fun of(g: JSONObject) = XFinger(g.getInt("m"), g.getDouble("t"), g.getInt("k"), g.getInt("n"), g.optBoolean("p")) }
}

/** handwriting stroke, normalized coordinates */
data class Stroke(val page: Int, val color: Int, val width: Float, val pts: FloatArray, val layer: String = "")   // layer "" = not in a layer

class ScoreData {
    val layers = mutableListOf<Layer>()
    val ranges = mutableListOf<Range>()
    val strokes = mutableListOf<Stroke>()
    val fixes = mutableListOf<Fix>()
    var showFixes = true
    val fingers = mutableListOf<Finger>()
    var showFingers = true
    val xfingers = mutableListOf<XFinger>()
    var activeLayer: String = ""
    var showInk = true
    var lastPage = 0
    var keySig = 99        // key signature for automatic colouring (+ sharps / - flats, 99 = not chosen)
    var lowerTreble = false
    var playHands = 2; var playSound = 0   // last choices in the play bar
    var fingerScale = 1f  // size of fingering numbers for this score
    var bpm = 0      // metronome tempo used for this score (0 = never set)
    var beats = 0
    var xmlBpm = 0   // tempo used in the XML score view / playback (kept apart from the practice metronome)

    fun layer(id: String) = layers.firstOrNull { it.id == id }

    /** handwriting shown: ink switched on and its layer visible */
    fun strokeVisible(s: Stroke) = showInk && (s.layer.isEmpty() || layer(s.layer)?.visible != false)
}

object Store {
    val LAYER_COLORS = intArrayOf(
        0xFFFF7D7D.toInt(), 0xFF5087FF.toInt(), 0xFF46BE64.toInt(),
        0xFF6E23A0.toInt(), 0xFFFFA000.toInt(), 0xFF00A0A0.toInt()
    )

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun libFile(c: Context) = File(c.filesDir, "library.json")
    fun libraryFile(c: Context) = libFile(c)
    fun scoreDir(c: Context) = File(c.filesDir, "scores").apply { mkdirs() }
    private fun dataFile(c: Context, id: String) = File(scoreDir(c), "$id.json")

    fun loadLibrary(c: Context): MutableList<Score> {
        val f = libFile(c); val out = mutableListOf<Score>()
        if (!f.exists()) return out
        val a = JSONArray(f.readText())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Score(o.getString("id"), o.optString("title"), o.optString("composer"),
                o.getString("file"), o.optLong("added"), o.optString("origName")).also {
                    if (it.title == "제목 없음") it.title = ""   // older entries: show the file name instead
                })
        }
        return out
    }

    fun libraryJson(list: List<Score>): String {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("id", s.id).put("title", s.title)
            .put("composer", s.composer).put("file", s.file).put("added", s.added).put("origName", s.origName))
        return a.toString()
    }

    fun saveLibrary(c: Context, list: List<Score>) = writeSafe(libFile(c), libraryJson(list))

    fun loadData(c: Context, id: String): ScoreData {
        val f = dataFile(c, id)
        return parse(if (f.exists()) f.readText() else null)
    }

    fun parse(text: String?): ScoreData {
        val d = ScoreData()
        if (text != null) {
            val o = JSONObject(text)
            val la = o.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until la.length()) {
                val l = la.getJSONObject(i)
                d.layers.add(Layer(l.getString("id"), l.getString("name"), l.getInt("color"), l.optBoolean("visible", true)))
            }
            val ra = o.optJSONArray("ranges") ?: JSONArray()
            for (i in 0 until ra.length()) {
                val r = ra.getJSONObject(i); val lg = r.optJSONArray("log") ?: JSONArray()
                val log = MutableList(lg.length()) { lg.getLong(it) }
                d.ranges.add(Range(r.getString("id"), r.getString("layer"), r.getInt("page"),
                    r.getDouble("x0").toFloat(), r.getDouble("y0").toFloat(),
                    r.getDouble("x1").toFloat(), r.getDouble("y1").toFloat(),
                    r.optString("name"), r.optInt("target", 10), r.optInt("count", 0), log))
            }
            val sa = o.optJSONArray("strokes") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val s = sa.getJSONObject(i); val p = s.getJSONArray("pts")
                d.strokes.add(Stroke(s.getInt("page"), s.getInt("color"), s.getDouble("width").toFloat(),
                    FloatArray(p.length()) { p.getDouble(it).toFloat() }, s.optString("layer")))
            }
            val fa = o.optJSONArray("fixes") ?: JSONArray()
            for (i in 0 until fa.length()) {
                val f = fa.getJSONObject(i)
                d.fixes.add(Fix(f.getInt("page"), f.getDouble("x").toFloat(), f.getDouble("y").toFloat(),
                    f.getDouble("r").toFloat(), f.getInt("color"), f.optBoolean("hollow"),
                    f.optDouble("rb", f.getDouble("r") * 0.71).toFloat(), f.optBoolean("auto")))
            }
            d.showFixes = o.optBoolean("showFixes", true)
            val ga = o.optJSONArray("fingers") ?: JSONArray()
            for (i in 0 until ga.length()) {
                val g = ga.getJSONObject(i)
                d.fingers.add(Finger(g.getInt("page"), g.getDouble("x").toFloat(), g.getDouble("y").toFloat(),
                    g.getInt("n"), g.getDouble("s").toFloat()))
            }
            d.showFingers = o.optBoolean("showFingers", true)
            o.optJSONArray("xfingers")?.let { xa -> for (i in 0 until xa.length()) d.xfingers.add(XFinger.of(xa.getJSONObject(i))) }
            d.activeLayer = o.optString("activeLayer")
            d.showInk = o.optBoolean("showInk", true)
            d.lastPage = o.optInt("lastPage", 0)
            d.fingerScale = o.optDouble("fingerScale", 1.0).toFloat()
            d.playHands = o.optInt("playHands", 2); d.playSound = o.optInt("playSound", 0)
            d.keySig = o.optInt("keySig", 99); d.lowerTreble = o.optBoolean("lowerTreble")
            d.bpm = o.optInt("bpm", 0); d.xmlBpm = o.optInt("xmlBpm", 0); d.beats = o.optInt("beats", 0)
        }
        if (d.layers.isEmpty()) d.layers.add(Layer(newId(), "연습 1", LAYER_COLORS[0]))
        if (d.layer(d.activeLayer) == null) d.activeLayer = d.layers[0].id
        return d
    }

    fun saveData(c: Context, id: String, d: ScoreData) = writeText(c, id, toJson(d).toString())

    fun writeText(c: Context, id: String, text: String) = writeSafe(dataFile(c, id), text)

    fun toJson(d: ScoreData): JSONObject {
        val o = JSONObject()
        o.put("layers", JSONArray().apply {
            d.layers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("visible", it.visible)) }
        })
        o.put("ranges", JSONArray().apply {
            d.ranges.forEach { r ->
                put(JSONObject().put("id", r.id).put("layer", r.layer).put("page", r.page)
                    .put("x0", r.x0.toDouble()).put("y0", r.y0.toDouble()).put("x1", r.x1.toDouble()).put("y1", r.y1.toDouble())
                    .put("name", r.name).put("target", r.target).put("count", r.count)
                    .put("log", JSONArray().apply { r.log.forEach { put(it) } }))
            }
        })
        o.put("strokes", JSONArray().apply {
            d.strokes.forEach { s ->
                put(JSONObject().put("page", s.page).put("color", s.color).put("width", s.width.toDouble())
                    .put("pts", JSONArray().apply { s.pts.forEach { put(it.toDouble()) } }).put("layer", s.layer))
            }
        })
        o.put("fixes", JSONArray().apply {
            d.fixes.forEach { f ->
                put(JSONObject().put("page", f.page).put("x", f.x.toDouble()).put("y", f.y.toDouble())
                    .put("r", f.r.toDouble()).put("rb", f.rb.toDouble()).put("color", f.color).put("hollow", f.hollow).put("auto", f.auto))
            }
        })
        o.put("showFixes", d.showFixes)
        o.put("fingers", JSONArray().apply {
            d.fingers.forEach { g ->
                put(JSONObject().put("page", g.page).put("x", g.x.toDouble()).put("y", g.y.toDouble())
                    .put("n", g.n).put("s", g.s.toDouble()))
            }
        })
        o.put("showFingers", d.showFingers)
        o.put("xfingers", JSONArray().apply { d.xfingers.forEach { put(it.toJson()) } })
        o.put("activeLayer", d.activeLayer).put("showInk", d.showInk).put("lastPage", d.lastPage)
        o.put("bpm", d.bpm).put("xmlBpm", d.xmlBpm).put("beats", d.beats).put("fingerScale", d.fingerScale.toDouble()).put("playHands", d.playHands).put("playSound", d.playSound).put("keySig", d.keySig).put("lowerTreble", d.lowerTreble)
        return o
    }

    fun deleteScore(c: Context, s: Score) {
        File(scoreDir(c), s.file).delete(); dataFile(c, s.id).delete(); File(scoreDir(c), "${s.id}.musicxml").delete()
    }

    private fun writeSafe(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text); if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }
}

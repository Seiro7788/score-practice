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
    val added: Long
)

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
data class Fix(val page: Int, val x: Float, val y: Float, val r: Float, val color: Int, val hollow: Boolean, val rb: Float = r * 0.71f)

/** handwriting stroke, normalized coordinates */
data class Stroke(val page: Int, val color: Int, val width: Float, val pts: FloatArray)

class ScoreData {
    val layers = mutableListOf<Layer>()
    val ranges = mutableListOf<Range>()
    val strokes = mutableListOf<Stroke>()
    val fixes = mutableListOf<Fix>()
    var showFixes = true
    var activeLayer: String = ""
    var showInk = true
    var lastPage = 0

    fun layer(id: String) = layers.firstOrNull { it.id == id }
}

object Store {
    val LAYER_COLORS = intArrayOf(
        0xFFFF7D7D.toInt(), 0xFF5087FF.toInt(), 0xFF46BE64.toInt(),
        0xFF6E23A0.toInt(), 0xFFFFA000.toInt(), 0xFF00A0A0.toInt()
    )

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun libFile(c: Context) = File(c.filesDir, "library.json")
    fun scoreDir(c: Context) = File(c.filesDir, "scores").apply { mkdirs() }
    private fun dataFile(c: Context, id: String) = File(scoreDir(c), "$id.json")

    fun loadLibrary(c: Context): MutableList<Score> {
        val f = libFile(c); val out = mutableListOf<Score>()
        if (!f.exists()) return out
        val a = JSONArray(f.readText())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Score(o.getString("id"), o.optString("title"), o.optString("composer"),
                o.getString("file"), o.optLong("added")))
        }
        return out
    }

    fun saveLibrary(c: Context, list: List<Score>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("id", s.id).put("title", s.title)
            .put("composer", s.composer).put("file", s.file).put("added", s.added))
        writeSafe(libFile(c), a.toString())
    }

    fun loadData(c: Context, id: String): ScoreData {
        val d = ScoreData(); val f = dataFile(c, id)
        if (f.exists()) {
            val o = JSONObject(f.readText())
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
                    FloatArray(p.length()) { p.getDouble(it).toFloat() }))
            }
            val fa = o.optJSONArray("fixes") ?: JSONArray()
            for (i in 0 until fa.length()) {
                val f = fa.getJSONObject(i)
                d.fixes.add(Fix(f.getInt("page"), f.getDouble("x").toFloat(), f.getDouble("y").toFloat(),
                    f.getDouble("r").toFloat(), f.getInt("color"), f.optBoolean("hollow"),
                    f.optDouble("rb", f.getDouble("r") * 0.71).toFloat()))
            }
            d.showFixes = o.optBoolean("showFixes", true)
            d.activeLayer = o.optString("activeLayer")
            d.showInk = o.optBoolean("showInk", true)
            d.lastPage = o.optInt("lastPage", 0)
        }
        if (d.layers.isEmpty()) d.layers.add(Layer(newId(), "연습 1", LAYER_COLORS[0]))
        if (d.layer(d.activeLayer) == null) d.activeLayer = d.layers[0].id
        return d
    }

    fun saveData(c: Context, id: String, d: ScoreData) {
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
                    .put("pts", JSONArray().apply { s.pts.forEach { put(it.toDouble()) } }))
            }
        })
        o.put("fixes", JSONArray().apply {
            d.fixes.forEach { f ->
                put(JSONObject().put("page", f.page).put("x", f.x.toDouble()).put("y", f.y.toDouble())
                    .put("r", f.r.toDouble()).put("rb", f.rb.toDouble()).put("color", f.color).put("hollow", f.hollow))
            }
        })
        o.put("showFixes", d.showFixes)
        o.put("activeLayer", d.activeLayer).put("showInk", d.showInk).put("lastPage", d.lastPage)
        writeSafe(dataFile(c, id), o.toString())
    }

    fun deleteScore(c: Context, s: Score) {
        File(scoreDir(c), s.file).delete(); dataFile(c, s.id).delete()
    }

    private fun writeSafe(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text); if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }
}

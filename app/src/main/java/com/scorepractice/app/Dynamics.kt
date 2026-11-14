package com.scorepractice.app

/**
 * Loudness through the piece from the dynamic marks and hairpins:
 * a point list (measure, beat, velocity) in score order, used both for the coloured band
 * under each system and for the playing volume. Hairpins change the level gradually, also across barlines.
 */
object Dynamics {
    class Pt(val m: Int, val beat: Double, val v: Int)

    fun curve(sc: XScore): List<Pt> {
        // score time (quarters from the start, file order) of every measure
        val start = DoubleArray(sc.measures.size + 1)
        for (i in sc.measures.indices) start[i + 1] = start[i] + maxOf(sc.measures[i].length, 0.25)
        fun T(m: Int, b: Double) = start[m] + b

        val ev = sc.dyn
        var level = ev.firstOrNull { it.level != null }?.level ?: 76
        val keys = mutableListOf<Triple<Double, Int, Pair<Int, Double>>>()   // (time, velocity, (measure, beat))
        val ramps = mutableListOf<Pair<Double, Double>>()                     // hairpin time spans
        var wedgeAt: XDyn? = null; var wedgeType = ""
        for ((i, d) in ev.withIndex()) {
            if (d.level != null && d.wedge == null) { level = d.level; keys.add(Triple(T(d.measure, d.pos), level, d.measure to d.pos)) }
            when (d.wedge) {
                "crescendo", "diminuendo" -> { wedgeAt = d; wedgeType = d.wedge; keys.add(Triple(T(d.measure, d.pos), level, d.measure to d.pos)) }
                "stop" -> wedgeAt?.let { w ->
                    val next = ev.drop(i + 1).firstOrNull { it.level != null && it.measure - d.measure <= 1 }
                    level = next?.level ?: (level + if (wedgeType == "crescendo") 30 else -30).coerceIn(25, 120)
                    keys.add(Triple(T(d.measure, d.pos), level, d.measure to d.pos))
                    ramps.add(T(w.measure, w.pos) to T(d.measure, d.pos)); wedgeAt = null
                }
            }
        }
        if (keys.isEmpty()) keys.add(Triple(0.0, level, 0 to 0.0))
        keys.sortBy { it.first }
        // value at time t: inside a hairpin linear, otherwise the last level (steps)
        fun valueAt(t: Double): Int {
            val i = keys.indexOfLast { it.first <= t }
            if (i < 0) return keys[0].second
            if (i + 1 < keys.size && ramps.any { r -> keys[i].first >= r.first - 1e-6 && keys[i + 1].first <= r.second + 1e-6 && t < keys[i + 1].first }) {
                val a = keys[i]; val b = keys[i + 1]
                return (a.second + (b.second - a.second) * (t - a.first) / maxOf(1e-6, b.first - a.first)).toInt()
            }
            return keys[i].second
        }
        // points: every measure start, every key, and the end of each measure (so steps stay steps)
        val pts = mutableListOf<Pt>()
        for (mi in sc.measures.indices) {
            pts.add(Pt(mi, 0.0, valueAt(start[mi])))
            for (k in keys) if (k.third.first == mi && k.third.second > 0) {
                pts.add(Pt(mi, k.third.second - 1e-3, valueAt(k.first - 1e-3)))
                pts.add(Pt(mi, k.third.second, valueAt(k.first)))
            }
            pts.add(Pt(mi, start[mi + 1] - start[mi] - 1e-3, valueAt(start[mi + 1] - 1e-3)))
        }
        return pts
    }

    /** sf / sfz / fz marks: (measure, beat) */
    fun accents(sc: XScore): List<Pair<Int, Double>> = sc.dyn.filter { it.accent }.map { it.measure to it.pos }

    /** hairpins as (measure, beat) start / stop pairs with their kind, for drawing "<" ">" */
    fun hairpins(sc: XScore): List<Triple<Pair<Int, Double>, Pair<Int, Double>, Boolean>> {
        val out = mutableListOf<Triple<Pair<Int, Double>, Pair<Int, Double>, Boolean>>(); var open: XDyn? = null
        for (d in sc.dyn) when (d.wedge) {
            "crescendo", "diminuendo" -> open = d
            "stop" -> open?.let { o -> out.add(Triple(o.measure to o.pos, d.measure to d.pos, o.wedge == "crescendo")); open = null }
        }
        return out
    }

    /** velocity at a place in the score (linear between points of the same measure) */
    fun at(pts: List<Pt>, m: Int, beat: Double): Int {
        val inM = pts.filter { it.m == m }
        if (inM.isEmpty()) return 80
        val a = inM.lastOrNull { it.beat <= beat } ?: return inM.first().v
        val b = inM.firstOrNull { it.beat > beat } ?: return a.v
        return (a.v + (b.v - a.v) * ((beat - a.beat) / (b.beat - a.beat))).toInt()
    }
}

package com.scorepractice.app

/**
 * Chord names and Roman numerals from the MusicXML notes (e.g. C♯m / i, F♯m / iv6, G♯7 / V7).
 * Each beat is matched against chord shapes; equal neighbours are merged.
 */
object Harmony {
    class Label(val measure: Int, val beat: Double, val name: String, var roman: String) {
        var rootPc = 0; var rootStep = 'C'; var rootAlter = 0
        var iv: IntArray = intArrayOf(0, 4, 7); var seventh = false; var quality = ""   // "", m, dim, aug, 7, M7, m7, m7♭5, dim7
        var inv = 0; var tonic = 0; var minor = false; var keyName = ""
        var fn = "X"            // T (으뜸), S (버금딸림), D (딸림), X (그 밖: 부속화음 등)
        var title = ""          // e.g. "딸림7화음"
        var expl = ""           // what the chord does, in one or two sentences
        var cadence = ""        // 정격종지 / 반종지 / 거짓종지 / 변격종지
        var start = 0.0; var end = 0.0   // absolute time in quarters (file order)
        /** chord tones spelled as written: root letter + 3rd / 5th / 7th */
        fun tones(): List<Pair<Char, Int>> {
            val ri = "CDEFGAB".indexOf(rootStep); val nat = intArrayOf(0, 2, 4, 5, 7, 9, 11)
            return iv.mapIndexed { k, d ->
                val li = (ri + 2 * k) % 7
                var a = ((rootPc + d) - nat[li]) % 12; if (a > 6) a -= 12; if (a < -6) a += 12
                "CDEFGAB"[li] to a
            }
        }
    }

    private val SOLFA = mapOf('C' to "도", 'D' to "레", 'E' to "미", 'F' to "파", 'G' to "솔", 'A' to "라", 'B' to "시")
    fun solfa(p: Pair<Char, Int>) = SOLFA[p.first] + acc(p.second)

    /** 도 빨강 … 시 보라 (빨주노초파남보), 반음은 그 사이 색 — same as the rings on the score */
    val RAINBOW = intArrayOf(0xFFE53935.toInt(), 0xFFF06A2A.toInt(), 0xFFFB8C00.toInt(), 0xFFF9B81A.toInt(), 0xFFF2C200.toInt(), 0xFF43A047.toInt(),
        0xFF1AA39A.toInt(), 0xFF1E88E5.toInt(), 0xFF3557C4.toInt(), 0xFF283593.toInt(), 0xFF5E35B1.toInt(), 0xFF8E24AA.toInt())
    private val NATPC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
    fun pcOf(p: Pair<Char, Int>) = ((NATPC[p.first] ?: 0) + p.second + 12) % 12

    /** interval between two spelled notes going up: 장3도, 단3도, 완전5도 … */
    fun interval(a: Pair<Char, Int>, b: Pair<Char, Int>): String {
        val L = "CDEFGAB"
        val steps = (L.indexOf(b.first) - L.indexOf(a.first) + 7) % 7
        val semis = (pcOf(b) - pcOf(a) + 12) % 12
        val num = steps + 1
        val perfect = num == 1 || num == 4 || num == 5 || num == 8
        val base = intArrayOf(0, 2, 4, 5, 7, 9, 11)[steps]     // major / perfect size
        val diff = semis - base
        val q = if (perfect) when (diff) { 0 -> "완전"; 1 -> "증"; -1 -> "감"; else -> "?" }
                else when (diff) { 0 -> "장"; -1 -> "단"; 1 -> "증"; -2 -> "감"; else -> "?" }
        return "$q${num}도"
    }

    private class Shape(val iv: IntArray, val suffix: String, val upper: Boolean, val romanSuffix: String, val seventh: Boolean)
    private val SHAPES = listOf(
        Shape(intArrayOf(0, 4, 7), "", true, "", false),
        Shape(intArrayOf(0, 3, 7), "m", false, "", false),
        Shape(intArrayOf(0, 3, 6), "dim", false, "°", false),
        Shape(intArrayOf(0, 4, 8), "aug", true, "+", false),
        Shape(intArrayOf(0, 4, 7, 10), "7", true, "", true),
        Shape(intArrayOf(0, 4, 7, 11), "M7", true, "M", true),
        Shape(intArrayOf(0, 3, 7, 10), "m7", false, "", true),
        Shape(intArrayOf(0, 3, 6, 10), "m7♭5", false, "ø", true),
        Shape(intArrayOf(0, 3, 6, 9), "dim7", false, "°", true),
    )
    private val LETTERS = "CDEFGAB"
    private val NAT = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MAJ_DEG = mapOf(0 to "I", 2 to "II", 4 to "III", 5 to "IV", 7 to "V", 9 to "VI", 11 to "VII")
    private val MIN_DEG = mapOf(0 to "I", 2 to "II", 3 to "III", 5 to "IV", 7 to "V", 8 to "VI", 10 to "VII", 11 to "VII", 9 to "VI")

    private fun acc(a: Int) = when (a) { 1 -> "♯"; 2 -> "𝄪"; -1 -> "♭"; -2 -> "𝄫"; else -> "" }

    /** tonic pitch class and spelled name for a key signature */
    fun keyName(fifths: Int, minor: Boolean): Pair<Int, String> {
        val majors = listOf("C♭", "G♭", "D♭", "A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯")
        val minors = listOf("a♭", "e♭", "b♭", "f", "c", "g", "d", "a", "e", "b", "f♯", "c♯", "g♯", "d♯", "a♯")
        val i = (fifths + 7).coerceIn(0, 14)
        val name = if (minor) minors[i] else majors[i]
        val pc = ((fifths * 7) % 12 + 12) % 12
        return (if (minor) (pc + 9) % 12 else pc) to name
    }

    private fun roman(rootPc: Int, sh: Shape, tonic: Int, minor: Boolean, inv: Int): String {
        val d = (rootPc - tonic + 12) % 12
        val table = if (minor) MIN_DEG else MAJ_DEG
        val base = table[d] ?: (table[(d + 1) % 12]?.let { "♭$it" } ?: table[(d + 11) % 12]?.let { "♯$it" } ?: "?")
        val r = if (sh.upper) base else base.lowercase()
        // figures for inversions: triad 6 / 64, seventh 7 / 65 / 43 / 42
        val fig = if (!sh.seventh) listOf("", "6", "64")[inv.coerceIn(0, 2)] else listOf("7", "65", "43", "42")[inv.coerceIn(0, 3)]
        return r + sh.romanSuffix + fig
    }

    fun analyse(sc: XScore): List<Label> {
        val out = mutableListOf<Label>()
        val byM = sc.notes.groupBy { it.measure }
        var prevKey = ""
        for ((mi, m) in sc.measures.withIndex()) {
            val notes = byM[mi] ?: continue
            val beatLen = if (m.beatType == 8 && m.beats % 3 == 0 && m.beats > 3) 1.5 else 4.0 / m.beatType
            val len = if (m.length > 0) m.length else m.beats * 4.0 / m.beatType
            val (tonic, kname) = keyName(m.fifths, m.minor)
            var b = 0.0
            while (b < len - 1e-6) {
                val e = b + beatLen
                val w = DoubleArray(12); var bass: XNote? = null; var total = 0.0
                for (n in notes) {
                    val s = maxOf(n.onset, b); val t = minOf(n.onset + n.dur, e)
                    if (t <= s) continue
                    val pc = ((n.midi % 12) + 12) % 12
                    w[pc] += t - s; total += t - s
                    if (bass == null || n.midi < bass.midi) bass = n
                }
                if (total > 0 && w.count { it > 0 } >= 2) {
                    var best = -1e9; var br = 0; var bs = SHAPES[0]
                    for (r in 0 until 12) for (sh in SHAPES) {
                        val inS = BooleanArray(12); for (iv in sh.iv) inS[(r + iv) % 12] = true
                        var sc0 = 0.0
                        for (pc in 0 until 12) sc0 += if (inS[pc]) w[pc] else -0.7 * w[pc]
                        if (w[(r + sh.iv[1]) % 12] == 0.0) continue                     // no third: not this chord (octaves, open fifths)
                        if (w[r] == 0.0) sc0 -= 0.25 * total                              // no root
                        if (bass != null && (bass.midi % 12 + 12) % 12 == r) sc0 += 0.2 * total
                        if (sh.seventh) sc0 -= 0.12 * total                               // prefer the plain triad
                        if (sc0 > best) { best = sc0; br = r; bs = sh }
                    }
                    if (best > 0.25 * total) {
                        // spell the root as written in the score when possible
                        val sp = notes.filter { ((it.midi % 12) + 12) % 12 == br }.groupingBy { it.step to it.alter }.eachCount().maxByOrNull { it.value }?.key
                        val rootName = if (sp != null) "${sp.first}${acc(sp.second)}" else {
                            val flats = m.fifths < 0
                            val nat = NAT.indexOf(br)
                            if (nat >= 0) "${LETTERS[nat]}" else if (flats) "${LETTERS[NAT.indexOf(br + 1)]}♭" else "${LETTERS[NAT.indexOf(br - 1)]}♯"
                        }
                        val bpc = bass?.let { ((it.midi % 12) + 12) % 12 } ?: br
                        val inv = bs.iv.indexOfFirst { (br + it) % 12 == bpc }.coerceAtLeast(0)
                        val bassName = if (inv > 0 && bass != null) "/${bass.step}${acc(bass.alter)}" else ""
                        val name = rootName + bs.suffix + bassName
                        val rn = roman(br, bs, tonic, m.minor, inv)
                        val last = out.lastOrNull()
                        if (last == null || last.name != name) out.add(Label(mi, b, name, (if (kname != prevKey) "$kname: " else "") + rn).also { l ->
                            l.rootPc = br; l.rootStep = sp?.first ?: rootName[0]; l.rootAlter = sp?.second ?: when { rootName.contains("♯") -> 1; rootName.contains("♭") -> -1; else -> 0 }
                            l.iv = bs.iv; l.seventh = bs.seventh; l.quality = bs.suffix; l.inv = inv; l.tonic = tonic; l.minor = m.minor; l.keyName = kname
                        })
                        prevKey = kname
                    }
                }
                b = e
            }
        }
        explain(sc, out)
        return out
    }

    // ---------------------------------------------------------------- what each chord does
    private val MAJ_Q = mapOf(0 to "", 2 to "m", 4 to "m", 5 to "", 7 to "", 9 to "m", 11 to "dim")      // diatonic triads, major key
    private val MIN_Q = mapOf(0 to "m", 2 to "dim", 3 to "", 5 to "m", 7 to "", 8 to "", 11 to "dim", 10 to "")

    private fun triadQ(q: String) = when (q) { "7", "M7" -> ""; "m7" -> "m"; "m7♭5", "dim7" -> "dim"; else -> q }

    private fun explain(sc: XScore, out: List<Label>) {
        // absolute times
        val mStart = DoubleArray(sc.measures.size + 1)
        for (i in sc.measures.indices) mStart[i + 1] = mStart[i] + (if (sc.measures[i].length > 0) sc.measures[i].length else sc.measures[i].beats * 4.0 / sc.measures[i].beatType)
        for ((i, l) in out.withIndex()) { l.start = mStart[l.measure] + l.beat; l.end = if (i + 1 < out.size) mStart[out[i + 1].measure] + out[i + 1].beat else mStart[sc.measures.size] }
        val invName = { l: Label -> if (l.seventh) listOf("기본형", "첫째 자리바꿈(65)", "둘째 자리바꿈(43)", "셋째 자리바꿈(42)")[l.inv.coerceIn(0, 3)]
            else listOf("기본형", "첫째 자리바꿈(6)", "둘째 자리바꿈(64)")[l.inv.coerceIn(0, 2)] }
        for (l in out) {
            val d = (l.rootPc - l.tonic + 12) % 12
            val q = triadQ(l.quality)
            val diat = (if (l.minor) MIN_Q else MAJ_Q)[d]
            val seventh = if (l.quality == "7") "7" else ""
            val inv = invName(l)
            when {
                d == 1 && q == "" -> { l.fn = "S"; l.title = "나폴리화음 (♭II)"; l.expl = "II를 반음 낮춘 장3화음으로, 주로 첫째 자리바꿈(N6)으로 딸림화음 앞에 와서 극적인 색을 더합니다." }
                // a major / dominant-7th chord where the key has no such chord: the dominant of the chord a fifth below
                q == "" && (l.quality == "" || l.quality == "7") && (diat != "" || (d == 0 && l.quality == "7")) -> {
                    val target = (d + 5) % 12
                    val tName = (if (l.minor) mapOf(0 to "i", 2 to "ii°", 3 to "III", 5 to "iv", 7 to "V", 8 to "VI", 10 to "VII") else mapOf(0 to "I", 2 to "ii", 4 to "iii", 5 to "IV", 7 to "V", 9 to "vi", 11 to "vii°"))[target]
                    l.fn = "X"
                    if (tName != null && target != 0) {
                        val keyPart = if (l.roman.contains(": ")) l.roman.substringBefore(": ") + ": " else ""
                        l.roman = keyPart + "V$seventh/$tName"
                        l.title = "부속딸림화음 (V$seventh/$tName)"
                        l.expl = "$tName 화음을 잠깐 으뜸화음처럼 들리게 하는 딸림화음입니다. 임시표가 붙은 이끔음이 반음 올라가 $tName 화음의 근음으로 풀립니다."
                    } else { l.title = "조 밖의 화음"; l.expl = "이 조의 음계 밖 음이 들어간 화음입니다. 앞뒤 화음과 이어지는 반음 진행에 주목해 보세요." }
                }
                d == 0 -> { l.fn = "T"; l.title = "으뜸화음 (I)"; l.expl = "조의 중심이 되는 화음입니다. 가장 안정되어 '쉬는' 느낌을 주고, 곡과 프레이즈가 보통 여기서 끝납니다." +
                    if (l.inv == 2) " 둘째 자리바꿈(64)은 V 바로 앞에 오면 V를 꾸미는 '종지 6-4'로 들립니다." else "" }
                d == 9 && !l.minor || d == 8 && l.minor -> { l.fn = "T"; l.title = "버금가온화음 (vi / VI)"; l.expl = "으뜸화음과 두 음을 같이 가진 대리 화음입니다. 으뜸보다 부드럽고(장조에선 어둡게) 안정적이며, V 뒤에 오면 '거짓종지'가 됩니다." }
                d == 4 && !l.minor || d == 3 && l.minor -> { l.fn = "T"; l.title = "가온화음 (iii / III)"; l.expl = "으뜸화음의 대리로 쓰이는 화음입니다. 단조의 III는 나란한조(장조)의 으뜸화음처럼 밝게 들립니다." }
                d == 5 -> { l.fn = "S"; l.title = "버금딸림화음 (IV / iv)"; l.expl = "으뜸에서 한 걸음 벗어나 딸림화음으로 이어 주는 준비 화음입니다. IV→I로 끝나면 '변격종지(아멘 종지)'입니다." }
                d == 2 && q != "" -> { l.fn = "S"; l.title = "윗으뜸화음 (ii / ii°)"; l.expl = "버금딸림 기능의 화음입니다. ii–V–I 진행의 출발점으로, 베이스가 4도 위(5도 아래)로 움직이며 딸림화음으로 갑니다." }
                d == 7 -> { l.fn = "D"; l.title = if (l.quality == "7") "딸림7화음 (V7)" else "딸림화음 (V)"; l.expl = "으뜸화음으로 돌아가려는 긴장을 만드는 화음입니다. 3음인 이끔음이 반음 올라가 으뜸음으로 풀립니다." +
                    if (l.quality == "7") " 7음은 한 음 내려가 으뜸화음의 3음으로 풀립니다." else "" }
                d == 11 -> { l.fn = "D"; l.title = if (l.quality == "dim7") "감7화음 (vii°7)" else "이끔화음 (vii°)"; l.expl = "딸림화음의 윗부분과 같은 화음으로, 딸림 기능을 합니다. 이끔음이 으뜸음으로 올라가며 풀립니다." +
                    if (l.quality == "dim7") " 감7화음은 모든 음이 단3도 간격이라 긴장이 매우 강합니다." else "" }
                else -> { l.fn = "X"; l.title = "그 밖의 화음"; l.expl = "이 조에서 흔하지 않은 화음입니다. 전조나 반음계적 진행일 수 있으니 앞뒤 화음과 함께 들어 보세요." }
            }
            l.title += " · $inv"
        }
        // cadences: a resolution that lasts (a beat and a half or more) on a strong beat
        for (i in 1 until out.size) {
            val p = out[i - 1]; val c = out[i]
            val dp = (p.rootPc - p.tonic + 12) % 12; val dc = (c.rootPc - c.tonic + 12) % 12
            val long = c.end - c.start >= 1.5 && (c.beat < 0.01 || kotlin.math.abs(c.beat - (sc.measures[c.measure].length / 2)) < 0.01)
            if (!long || p.tonic != c.tonic) continue
            c.cadence = when {
                dp == 7 && p.fn == "D" && dc == 0 && c.inv == 0 && p.inv == 0 -> "정격종지 (V→I)"
                (dp == 7 || dp == 11) && p.fn == "D" && dc == 0 -> "불완전정격종지"
                dp == 7 && p.fn == "D" && ((!c.minor && dc == 9) || (c.minor && dc == 8)) -> "거짓종지 (V→vi)"
                dp == 5 && dc == 0 && c.inv == 0 -> "변격종지 (IV→I)"
                dc == 7 && c.fn == "D" && c.inv == 0 && c.end - c.start >= 2.0 && (i + 1 >= out.size || (out[i + 1].rootPc - out[i + 1].tonic + 12) % 12 != 0) -> "반종지 (→V)"
                else -> ""
            }
        }
    }
}

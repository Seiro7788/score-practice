package com.scorepractice.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import java.io.File
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Keeping the library outside the app:
 *  - a phone folder chosen by the user is kept as a mirror (survives uninstall / reinstall)
 *  - a single backup .zip can be saved anywhere (Google Drive too) and loaded on another phone
 */
object Backup {
    private const val PREF = "settings"; private const val KEY = "folderUri"

    fun folder(c: Context): Uri? = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)?.let { Uri.parse(it) }

    fun setFolder(c: Context, uri: Uri) {
        c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, uri.toString()).apply()
    }

    private fun localFiles(c: Context): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val lib = Store.libraryFile(c); if (lib.exists()) out.add("library.json" to lib)
        Store.scoreDir(c).listFiles()?.filter { it.name.endsWith(".pdf") || it.name.endsWith(".json") || it.name.endsWith(".musicxml") }
            ?.forEach { out.add("scores/${it.name}" to it) }
        return out
    }

    // ---------- zip backup
    /** ids = the scores to put in the backup (null = all) */
    fun writeZip(c: Context, out: OutputStream, ids: Set<String>? = null) {
        val lib = Store.loadLibrary(c).filter { ids == null || it.id in ids }
        val keep = lib.flatMap { listOf(it.file, "${it.id}.json", "${it.id}.musicxml") }.toSet()
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry("library.json"))
            z.write(Store.libraryJson(lib).toByteArray(Charsets.UTF_8)); z.closeEntry()
            for ((name, f) in localFiles(c)) {
                if (name == "library.json" || name.removePrefix("scores/") !in keep) continue
                z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    /** unpack a backup to a temporary folder and list its scores (for choosing what to load) */
    fun openZip(c: Context, inp: InputStream): List<Score> {
        val tmp = File(c.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(inp.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                if (e.isDirectory || name.contains("..") || !(name == "library.json" || name.startsWith("scores/"))) continue
                val f = File(tmp, name); f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }
            }
        }
        val lib = File(tmp, "library.json").takeIf { it.exists() }?.readText() ?: return emptyList()
        return parseLib(lib)
    }

    /** load the chosen scores from the unpacked backup (scores already on the phone stay; the same score is replaced) */
    fun loadOpened(c: Context, ids: Set<String>): Int {
        val tmp = File(c.cacheDir, "restore")
        val lib = File(tmp, "library.json").takeIf { it.exists() }?.readText()
        return mergeFrom(c, lib, ids) { name -> File(tmp, "scores/$name").takeIf { it.exists() }?.inputStream() }.also { tmp.deleteRecursively() }
    }

    /** the same piece already on the phone: same id, or same composer and title */
    fun sameOnPhone(mine: List<Score>, s: Score): Score? =
        mine.firstOrNull { it.id == s.id } ?: mine.firstOrNull { norm(it.composer) == norm(s.composer) && norm(it.shownTitle) == norm(s.shownTitle) }
    private fun norm(t: String) = t.lowercase().replace(Regex("[\\s_\\-·.,()]"), "")

    private fun parseLib(text: String): List<Score> {
        val a = JSONArray(text)
        return (0 until a.length()).map { i -> val o = a.getJSONObject(i)
            Score(o.getString("id"), o.optString("title"), o.optString("composer"), o.getString("file"), o.optLong("added"), o.optString("origName")) }
    }

    /** merge a backup into this phone: scores from the backup are added, same scores are replaced by the backup copy */
    fun readZip(c: Context, inp: InputStream): Int {
        val tmp = File(c.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(inp.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                if (e.isDirectory || name.contains("..") || !(name == "library.json" || name.startsWith("scores/"))) continue
                val f = File(tmp, name); f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }
            }
        }
        return mergeFrom(c, File(tmp, "library.json").takeIf { it.exists() }?.readText(), null) { name -> File(tmp, "scores/$name").takeIf { it.exists() }?.inputStream() }
            .also { tmp.deleteRecursively() }
    }

    private fun mergeFrom(c: Context, libText: String?, ids: Set<String>?, open: (String) -> InputStream?): Int {
        if (libText == null) return 0
        val mine = Store.loadLibrary(c); var n = 0
        for (s in parseLib(libText)) {
            if (ids != null && s.id !in ids) continue
            val pdf = open(s.file) ?: continue
            // the same piece under another id (imported again on this phone): the backup copy takes its place
            sameOnPhone(mine, s)?.let { old -> if (old.id != s.id) { Store.deleteScore(c, old); mine.remove(old) } }
            pdf.use { inp -> File(Store.scoreDir(c), s.file).outputStream().use { inp.copyTo(it) } }
            open("${s.id}.json")?.use { inp -> File(Store.scoreDir(c), "${s.id}.json").outputStream().use { inp.copyTo(it) } }
            open("${s.id}.musicxml")?.use { inp -> File(Store.scoreDir(c), "${s.id}.musicxml").outputStream().use { inp.copyTo(it) } }
            mine.removeAll { it.id == s.id }; mine.add(s); n++
        }
        Store.saveLibrary(c, mine)
        return n
    }

    // ---------- phone folder mirror
    // Works through DocumentsContract with the folder ids remembered: cloud folders (Google Drive …) list a new
    // folder only after a delay, so looking it up by name again would create "scores" over and over.
    private class Child(val id: String, val name: String, val size: Long, val dir: Boolean)

    private fun children(c: Context, tree: Uri, parentId: String): List<Child> {
        val out = mutableListOf<Child>()
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        c.contentResolver.query(q, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cu ->
            while (cu.moveToNext()) out.add(Child(cu.getString(0), cu.getString(1) ?: "", if (cu.isNull(2)) -1 else cu.getLong(2),
                cu.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR))
        }
        return out
    }

    private fun exists(c: Context, tree: Uri, id: String): Boolean = try {
        c.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) { false }

    private val SCORES = Regex("^scores( \\(\\d+\\))?$")

    /** the one "scores" folder: remembered id → existing folder (the fullest one; empty duplicates are removed) → new */
    private fun scoresDir(c: Context, tree: Uri, rootId: String, kids: List<Child>): String? {
        val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE); val key = "scoresDir_$tree"
        p.getString(key, null)?.let { if (exists(c, tree, it)) { cleanDuplicates(c, tree, kids, it); return it } }
        val dirs = kids.filter { it.dir && SCORES.matches(it.name) }
        val pick = dirs.maxByOrNull { children(c, tree, it.id).size }?.id
            ?: DocumentsContract.createDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.Document.MIME_TYPE_DIR, "scores")
                ?.let { DocumentsContract.getDocumentId(it) } ?: return null
        p.edit().putString(key, pick).apply()
        cleanDuplicates(c, tree, kids, pick)
        return pick
    }

    private fun cleanDuplicates(c: Context, tree: Uri, kids: List<Child>, keep: String) {
        for (d in kids) if (d.dir && d.id != keep && SCORES.matches(d.name) && children(c, tree, d.id).isEmpty())
            try { DocumentsContract.deleteDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, d.id)) } catch (_: Exception) {}
    }

    private fun write(c: Context, tree: Uri, parentId: String, have: Map<String, Child>, name: String, mime: String, f: File) {
        val uri = have[name]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
            ?: DocumentsContract.createDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mime, name) ?: return
        c.contentResolver.openOutputStream(uri, "wt")?.use { o -> f.inputStream().use { it.copyTo(o) } }
    }

    private val syncLock = Any()

    // ================= sync between devices through one shared folder (e.g. a Google Drive folder) =================
    // Folder: library.json = {"scores":[{id,title,composer,file,added,origName,savedAt}], "deleted":{id:time}}
    //         scores/<id>.json, <pdf>, <id>.musicxml
    // Receiving happens on its own when the app opens; sending only when the user presses 올리기.

    private class Remote(val scores: LinkedHashMap<String, JSONObject>, val deleted: JSONObject)

    private fun readRemote(c: Context, tree: Uri, kids: List<Child>): Remote {
        val lib = kids.firstOrNull { !it.dir && it.name == "library.json" }
        val text = lib?.let { c.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, it.id))?.use { s -> s.readBytes().toString(Charsets.UTF_8) } }
        val map = LinkedHashMap<String, JSONObject>(); var del = JSONObject()
        if (text != null) try {
            val t = text.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { o -> del = o.optJSONObject("deleted") ?: JSONObject(); o.optJSONArray("scores") ?: JSONArray() }
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { map[it.getString("id")] = it }
        } catch (_: Exception) {}
        return Remote(map, del)
    }

    private fun scoreOf(o: JSONObject) = Score(o.getString("id"), o.optString("title"), o.optString("composer"), o.getString("file"), o.optLong("added"), o.optString("origName"))
    private fun entryOf(s: Score, savedAt: Long) = JSONObject().put("id", s.id).put("title", s.title).put("composer", s.composer)
        .put("file", s.file).put("added", s.added).put("origName", s.origName).put("savedAt", savedAt)

    private fun synced(c: Context): JSONObject = try { JSONObject(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("synced", "{}")!!) } catch (e: Exception) { JSONObject() }
    private fun saveSynced(c: Context, o: JSONObject) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("synced", o.toString()).apply()

    /** files in every "scores" folder of the shared folder: name -> child */
    private fun remoteFiles(c: Context, tree: Uri, kids: List<Child>): HashMap<String, Child> {
        val files = HashMap<String, Child>()
        for (d in kids.filter { it.dir && SCORES.matches(it.name) }) for (ch in children(c, tree, d.id)) if (!ch.dir && ch.size != 0L) files.putIfAbsent(ch.name, ch)
        return files
    }

    private fun download(c: Context, tree: Uri, ch: Child, to: File) {
        val tmp = File(to.parentFile, to.name + ".part")
        c.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, ch.id))?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        if (!tmp.renameTo(to)) { to.delete(); tmp.renameTo(to) }
    }

    /** scores changed on this phone and not sent yet (and deletions not sent yet) */
    fun pending(c: Context): Int {
        val sy = synced(c)
        val n = Store.loadLibrary(c).count { s -> val t = Store.savedAt(c, s.id); t == 0L || t > sy.optLong(s.id, -1) }
        val tomb = Store.tombstones(c); var d = 0
        for (k in tomb.keys()) if (!sy.optBoolean("del_$k")) d++
        return n + d
    }

    /** take scores that are newer in the shared folder; returns how many came in (or -1 = no folder / not readable) */
    fun receive(c: Context): Int = synchronized(syncLock) {
        val tree = folder(c) ?: return -1
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val kids = children(c, tree, rootId)
            val remote = readRemote(c, tree, kids)
            if (remote.scores.isEmpty() && remote.deleted.length() == 0) return 0
            val files = remoteFiles(c, tree, kids)
            val local = Store.loadLibrary(c); val tomb = Store.tombstones(c); val sy = synced(c)
            var got = 0
            // deleted on another device after this phone last changed them
            for (id in remote.deleted.keys()) {
                val l = local.firstOrNull { it.id == id } ?: continue
                if (Store.savedAt(c, id) <= remote.deleted.optLong(id)) { Store.deleteScore(c, l, remember = false); local.remove(l); got++ }
            }
            for ((id, e) in remote.scores) {
                val rs = e.optLong("savedAt", 0)
                if (tomb.has(id) && tomb.optLong(id) >= rs) continue                // deleted here later
                val l = local.firstOrNull { it.id == id } ?: sameOnPhone(local, scoreOf(e))
                if (l != null && rs <= Store.savedAt(c, l.id)) continue             // this phone has the same or newer
                val js = files["$id.json"] ?: continue
                val sc = scoreOf(e)
                val pdfLocal = File(Store.scoreDir(c), sc.file)
                val pdfRemote = files[sc.file]
                if (!(pdfLocal.exists() && (pdfRemote == null || pdfLocal.length() == pdfRemote.size))) {
                    if (pdfRemote == null) continue                                  // PDF not uploaded (yet)
                    download(c, tree, pdfRemote, pdfLocal)
                }
                download(c, tree, js, File(Store.scoreDir(c), "$id.json"))
                files["$id.musicxml"]?.let { download(c, tree, it, File(Store.scoreDir(c), "$id.musicxml")) }
                if (l != null) {
                    if (l.id != id) { Store.deleteScore(c, l, remember = true); local.remove(l) }
                    else if (l.file != sc.file) File(Store.scoreDir(c), l.file).delete()
                }
                local.removeAll { it.id == id }; local.add(sc)
                sy.put(id, Store.savedAt(c, id)); got++
            }
            Store.saveLibrary(c, local); saveSynced(c, sy)
            got
        } catch (e: Exception) { -1 }
    }

    /** send this phone's changed scores (and deletions) to the shared folder. Returns a message for the user. */
    fun send(c: Context): String = synchronized(syncLock) {
        val tree = folder(c) ?: return "동기화 폴더를 먼저 정하세요 (⋮ → 동기화 폴더 지정)"
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val kids = children(c, tree, rootId)
            val remote = readRemote(c, tree, kids)
            val sdId = scoresDir(c, tree, rootId, kids) ?: return "폴더에 쓸 수 없습니다"
            val have = children(c, tree, sdId).associateBy { it.name }.toMutableMap()
            val sy = synced(c); val local = Store.loadLibrary(c); val tomb = Store.tombstones(c)
            var sent = 0; var newer = 0
            fun up(name: String, f: File, mime: String) { write(c, tree, sdId, have, name, mime, f) }
            fun removeRemote(id: String) {
                val e = remote.scores.remove(id) ?: return
                for (n in listOf("$id.json", "$id.musicxml", e.optString("file"))) have[n]?.let {
                    try { DocumentsContract.deleteDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.id)) } catch (_: Exception) {}
                    have.remove(n) }
            }
            for (s in local) {
                var ls = Store.savedAt(c, s.id); if (ls == 0L) { Store.touch(c, s.id); ls = Store.savedAt(c, s.id) }
                val re = remote.scores[s.id]; val rs = re?.optLong("savedAt", 0) ?: -1
                if (re != null && rs > ls) { newer++; continue }                      // the folder has a newer copy: receive first
                if (re != null && rs == ls && have.containsKey(s.file)) { sy.put(s.id, ls); continue }
                // the same piece under another id in the folder (imported separately on two devices)
                remote.scores.values.firstOrNull { it.optString("id") != s.id && sameOnPhone(listOf(s), scoreOf(it)) != null }?.let { o ->
                    if (o.optLong("savedAt", 0) <= ls) { val oid = o.getString("id"); removeRemote(oid); remote.deleted.put(oid, System.currentTimeMillis()) }
                }
                val pdf = File(Store.scoreDir(c), s.file)
                if (pdf.exists() && have[s.file]?.size != pdf.length()) up(s.file, pdf, "application/pdf")
                File(Store.scoreDir(c), "${s.id}.json").takeIf { it.exists() }?.let { up("${s.id}.json", it, "application/json") }
                File(Store.scoreDir(c), "${s.id}.musicxml").takeIf { it.exists() }?.let { up("${s.id}.musicxml", it, "application/octet-stream") }
                remote.scores[s.id] = entryOf(s, ls); sy.put(s.id, ls); sent++
            }
            // deletions made on this phone
            for (id in tomb.keys().asSequence().toList()) {
                val t = tomb.optLong(id)
                val re = remote.scores[id]
                if (re == null || re.optLong("savedAt", 0) <= t) { removeRemote(id); remote.deleted.put(id, t) }
                sy.put("del_$id", true)
            }
            val out = JSONObject().put("scores", JSONArray().apply { remote.scores.values.forEach { put(it) } }).put("deleted", remote.deleted)
            val tmp = File(c.cacheDir, "library_sync.json").apply { writeText(out.toString()) }
            write(c, tree, rootId, kids.filter { !it.dir }.associateBy { it.name }, "library.json", "application/json", tmp)
            saveSynced(c, sy)
            "올렸습니다: ${sent}곡" + (if (newer > 0) " · 다른 기기에서 더 최근에 고친 ${newer}곡은 그대로 두었습니다(받기로 가져오세요)" else "") +
                if (isCloud(tree)) "\n구글드라이브 앱이 인터넷에 연결되면 올라갑니다" else ""
        } catch (e: Exception) { "올리지 못했습니다: ${e.message}" }
    }

    /** cloud folders (Google Drive …) are slow and unreliable as a live mirror */
    fun isCloud(uri: Uri): Boolean = uri.authority?.let { it.contains("google.android.apps.docs") || it.contains("onedrive") || it.contains("dropbox") } ?: false

    /** true when the chosen folder already holds a library (e.g. after reinstalling) */
    fun folderHasLibrary(c: Context, uri: Uri): Boolean = try {
        children(c, uri, DocumentsContract.getTreeDocumentId(uri)).any { !it.dir && it.name == "library.json" }
    } catch (e: Exception) { false }

    fun importFromFolder(c: Context, uri: Uri): Int {
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val kids = children(c, uri, rootId)
        val lib = kids.firstOrNull { !it.dir && it.name == "library.json" } ?: return 0
        val text = c.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(uri, lib.id))?.use { it.readBytes().toString(Charsets.UTF_8) }
        // every "scores" folder (a cloud folder may hold duplicates): a file is taken from whichever has it
        val files = HashMap<String, String>()
        for (d in kids.filter { it.dir && SCORES.matches(it.name) }) for (ch in children(c, uri, d.id)) if (!ch.dir && ch.size != 0L) files.putIfAbsent(ch.name, ch.id)
        return mergeFrom(c, text, null) { name -> files[name]?.let { c.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(uri, it)) } }
    }
}

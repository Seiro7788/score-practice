package com.scorepractice.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import java.io.File
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

    /** copy the library to the chosen folder (PDFs only when missing, data every time) */
    fun sync(c: Context): Boolean = synchronized(syncLock) {
        val tree = folder(c) ?: return false
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val kids = children(c, tree, rootId)
            val sdId = scoresDir(c, tree, rootId, kids) ?: return false
            val have = children(c, tree, sdId).associateBy { it.name }
            val keep = mutableSetOf<String>()
            for ((name, f) in localFiles(c)) {
                if (name == "library.json") { write(c, tree, rootId, kids.filter { !it.dir }.associateBy { it.name }, "library.json", "application/json", f); continue }
                val short = name.removePrefix("scores/"); keep.add(short)
                val isPdf = short.endsWith(".pdf")
                if (isPdf && have[short]?.size == f.length()) continue
                write(c, tree, sdId, have, short, if (isPdf) "application/pdf" else if (short.endsWith(".json")) "application/json" else "application/octet-stream", f)
            }
            for ((n, ch) in have) if (n !in keep && !ch.dir)   // scores deleted on the phone
                try { DocumentsContract.deleteDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, ch.id)) } catch (_: Exception) {}
            true
        } catch (e: Exception) { false }
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

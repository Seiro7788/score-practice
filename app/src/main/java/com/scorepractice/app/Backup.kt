package com.scorepractice.app

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        Store.scoreDir(c).listFiles()?.filter { it.name.endsWith(".pdf") || it.name.endsWith(".json") }
            ?.forEach { out.add("scores/${it.name}" to it) }
        return out
    }

    // ---------- zip backup
    fun writeZip(c: Context, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { z ->
            for ((name, f) in localFiles(c)) {
                z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
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
        return mergeFrom(c, File(tmp, "library.json").takeIf { it.exists() }?.readText()) { name -> File(tmp, "scores/$name").takeIf { it.exists() }?.inputStream() }
            .also { tmp.deleteRecursively() }
    }

    private fun mergeFrom(c: Context, libText: String?, open: (String) -> InputStream?): Int {
        if (libText == null) return 0
        val mine = Store.loadLibrary(c); val a = JSONArray(libText); var n = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val s = Score(o.getString("id"), o.optString("title"), o.optString("composer"), o.getString("file"), o.optLong("added"))
            val pdf = open(s.file) ?: continue
            pdf.use { inp -> File(Store.scoreDir(c), s.file).outputStream().use { inp.copyTo(it) } }
            open("${s.id}.json")?.use { inp -> File(Store.scoreDir(c), "${s.id}.json").outputStream().use { inp.copyTo(it) } }
            mine.removeAll { it.id == s.id }; mine.add(s); n++
        }
        Store.saveLibrary(c, mine)
        return n
    }

    // ---------- phone folder mirror
    private fun dir(c: Context): DocumentFile? {
        val u = folder(c) ?: return null
        val root = DocumentFile.fromTreeUri(c, u) ?: return null
        return if (root.canWrite()) root else null
    }

    private fun child(parent: DocumentFile, name: String, mime: String): DocumentFile? =
        parent.findFile(name) ?: parent.createFile(mime, name)

    /** copy the library to the chosen folder (PDFs only when missing, data every time) */
    fun sync(c: Context): Boolean {
        val root = dir(c) ?: return false
        return try {
            val sd = root.findFile("scores") ?: root.createDirectory("scores") ?: return false
            val keep = mutableSetOf<String>()
            for ((name, f) in localFiles(c)) {
                if (name == "library.json") {
                    child(root, "library.json", "application/json")?.let { d -> c.contentResolver.openOutputStream(d.uri, "wt")?.use { o -> f.inputStream().use { it.copyTo(o) } } }
                    continue
                }
                val short = name.removePrefix("scores/"); keep.add(short)
                val isPdf = short.endsWith(".pdf")
                val existing = sd.findFile(short)
                if (isPdf && existing != null && existing.length() == f.length()) continue
                val d = existing ?: sd.createFile(if (isPdf) "application/pdf" else "application/json", short) ?: continue
                c.contentResolver.openOutputStream(d.uri, "wt")?.use { o -> f.inputStream().use { it.copyTo(o) } }
            }
            sd.listFiles().forEach { if (it.name != null && it.name !in keep) it.delete() }  // scores deleted on the phone
            true
        } catch (e: Exception) { false }
    }

    /** true when the chosen folder already holds a library (e.g. after reinstalling) */
    fun folderHasLibrary(c: Context, uri: Uri): Boolean =
        DocumentFile.fromTreeUri(c, uri)?.findFile("library.json") != null

    fun importFromFolder(c: Context, uri: Uri): Int {
        val root = DocumentFile.fromTreeUri(c, uri) ?: return 0
        val lib = root.findFile("library.json") ?: return 0
        val text = c.contentResolver.openInputStream(lib.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        val sd = root.findFile("scores")
        return mergeFrom(c, text) { name -> sd?.findFile(name)?.let { c.contentResolver.openInputStream(it.uri) } }
    }
}

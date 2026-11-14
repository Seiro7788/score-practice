package com.scorepractice.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

class MainActivity : AppCompatActivity() {
    private var all = mutableListOf<Score>()
    private var shown = listOf<Score>()
    /** rows of the list: a composer header (String) or a score */
    private var rows = listOf<Any>()
    private val collapsed = mutableSetOf<String>()
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var search: EditText

    // Storage Access Framework picker: shows phone storage AND Google Drive (if the Drive app is installed)
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }

    // ---- keeping the library outside the app
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) chooseFolder(uri)
    }
    private val makeBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) background("백업 만드는 중…") {
            val ids = backupIds
            contentResolver.openOutputStream(uri, "wt")!!.use { Backup.writeZip(this, it, ids); it.flush() }
            val size = try { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L } catch (e: Exception) { -1L }
            val n = ids?.size ?: Store.loadLibrary(this).size
            when {
                size == 0L -> "백업 파일이 비어 있습니다. 다시 저장해 주세요"
                Backup.isCloud(uri) -> "백업 파일을 저장했습니다 (악보 ${n}개${if (size > 0) ", " + size / 1024 / 1024 + "MB" else ""}).\n구글드라이브 앱이 인터넷에 연결되면 올립니다 — 드라이브 앱에서 '업로드 중'이 끝났는지 확인하세요"
                else -> "백업 파일을 저장했습니다 (악보 ${n}개${if (size > 0) ", " + size / 1024 / 1024 + "MB" else ""})"
            }
        }
    }
    private val loadBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val dlg = AlertDialog.Builder(this).setMessage("백업 파일을 여는 중…").setCancelable(false).show()
            Thread {
                val list = try { contentResolver.openInputStream(uri)!!.use { Backup.openZip(this, it) } } catch (e: Exception) { null }
                runOnUiThread { dlg.dismiss(); if (list.isNullOrEmpty()) Toast.makeText(this, "백업 파일에 악보가 없습니다", Toast.LENGTH_LONG).show() else chooseRestore(list) }
            }.start()
        }
    }
    private var backupIds: Set<String>? = null

    private fun scoreLine(s: Score) = (if (s.composer.isNotBlank()) s.composer + " - " else "") + s.shownTitle

    /** which scores go into the backup file */
    private fun chooseBackup() {
        val lib = Store.loadLibrary(this).sortedWith(compareBy({ it.composer }, { it.shownTitle }))
        if (lib.isEmpty()) { Toast.makeText(this, "저장할 악보가 없습니다", Toast.LENGTH_LONG).show(); return }
        val checked = BooleanArray(lib.size) { true }
        val name = "오선노트_백업_" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(java.util.Date())
        AlertDialog.Builder(this).setTitle("백업할 악보 고르기")
            .setMultiChoiceItems(lib.map { scoreLine(it) }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setNegativeButton("취소", null)
            .setNeutralButton("전체 저장") { _, _ -> backupIds = null; makeBackup.launch("$name.zip") }
            .setPositiveButton("고른 것 저장") { _, _ ->
                val ids = lib.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                if (ids.isEmpty()) { Toast.makeText(this, "고른 악보가 없습니다", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                backupIds = ids; makeBackup.launch(if (ids.size == lib.size) "$name.zip" else "${name}_${ids.size}곡.zip")
            }.show()
    }

    /** which scores to load from a backup; scores on the phone stay, the same piece is replaced by the backup copy */
    private fun chooseRestore(list: List<Score>) {
        val mine = Store.loadLibrary(this)
        val items = list.map { s -> scoreLine(s) + if (Backup.sameOnPhone(mine, s) != null) "  (있음 → 덮어씀)" else "  (새 악보)" }
        val checked = BooleanArray(list.size) { true }
        AlertDialog.Builder(this).setTitle("불러올 악보 고르기")
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setNegativeButton("취소", null)
            .setPositiveButton("불러오기") { _, _ ->
                val ids = list.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                if (ids.isEmpty()) return@setPositiveButton
                background("백업 불러오는 중…") {
                    val n = Backup.loadOpened(this, ids)
                    "악보 ${n}개를 불러왔습니다 (휴대폰에 있던 다른 악보는 그대로입니다)"
                }
            }.show()
    }

    private fun background(msg: String, work: () -> String) {
        val dlg = AlertDialog.Builder(this).setMessage(msg).setCancelable(false).show()
        Thread {
            val r = try { work() } catch (e: Exception) { "실패: ${e.message}" }
            runOnUiThread { dlg.dismiss(); Toast.makeText(this, r, Toast.LENGTH_LONG).show(); all = Store.loadLibrary(this); refresh() }
        }.start()
    }

    private fun chooseFolder(uri: Uri) = useFolder(uri)

    private fun useFolder(uri: Uri) {
        try { Backup.setFolder(this, uri) } catch (e: Exception) { Toast.makeText(this, "이 폴더는 쓸 수 없습니다", Toast.LENGTH_LONG).show(); return }
        // take what is already there, then offer to send this phone's scores
        background("동기화 폴더를 읽는 중…") {
            val n = Backup.receive(this)
            runOnUiThread {
                updateSyncBar()
                AlertDialog.Builder(this).setTitle("동기화 폴더")
                    .setMessage((if (n > 0) "폴더에서 ${n}곡을 받았습니다.\n\n" else "") +
                        "다른 기기에서도 같은 폴더를 동기화 폴더로 정하세요.\n\n· 받기: 앱을 열 때 자동으로 (목록 위 [받기]로도)\n· 올리기: 목록 위 [올리기]를 누를 때만\n\n지금 이 폰의 악보를 올릴까요?")
                    .setPositiveButton("올리기") { _, _ -> background("올리는 중…") { Backup.send(this).also { runOnUiThread { updateSyncBar() } } } }
                    .setNegativeButton("나중에", null).show()
            }
            "동기화 폴더를 정했습니다"
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "동기화 폴더 지정 (기기끼리 맞추기)")
        menu.add(0, 2, 1, "백업 파일 만들기 (구글드라이브 등)")
        menu.add(0, 3, 2, "백업 불러오기")
        menu.add(0, 4, 3, "앱 정보")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> pickFolder.launch(null)
            2 -> chooseBackup()
            4 -> {
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
                AlertDialog.Builder(this).setTitle(getString(R.string.app_name))
                    .setMessage("피아노 악보 보기 · 연습 기록 · 반음 색칠 · 연주와 계이름 노래\n\n버전 $ver\n\n제작자: 임종철\nsmartrain7788@gmail.com")
                    .setPositiveButton("확인", null).show()
            }
            3 -> loadBackup.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = getString(R.string.app_name)
        list = findViewById(R.id.list); empty = findViewById(R.id.empty); search = findViewById(R.id.search)
        findViewById<Button>(R.id.syncReceive).setOnClickListener {
            background("받는 중…") { val n = Backup.receive(this); runOnUiThread { updateSyncBar() }
                if (n < 0) "동기화 폴더를 읽지 못했습니다" else if (n == 0) "새로 받을 것이 없습니다" else "${n}곡을 받았습니다" } }
        findViewById<Button>(R.id.syncSend).setOnClickListener {
            background("올리는 중…") { Backup.send(this).also { runOnUiThread { updateSyncBar() } } } }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
        findViewById<FloatingActionButton>(R.id.add).setOnClickListener { pick.launch(arrayOf("application/pdf")) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // fresh install with nothing yet: offer to load from a folder or a backup
        if (Store.loadLibrary(this).isEmpty() && Backup.folder(this) == null)
            AlertDialog.Builder(this).setTitle("처음 시작")
                .setMessage("전에 쓰던 악보가 있으면 불러올 수 있습니다.\n\n· 동기화 폴더: 다른 기기나 전에 쓰던 앱과 맞춰 둔 폴더(구글드라이브 등)\n· 백업 파일: 구글드라이브 등에 올려 둔 백업\n\n나중에 오른쪽 위 메뉴(⋮)에서도 할 수 있습니다.")
                .setPositiveButton("동기화 폴더 고르기") { _, _ -> pickFolder.launch(null) }
                .setNeutralButton("백업 파일") { _, _ -> loadBackup.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
                .setNegativeButton("새로 시작", null).show()
    }

    override fun onResume() {
        super.onResume(); all = Store.loadLibrary(this); refresh(); updateSyncBar()
        autoReceive()
    }

    // ---------------- sync between devices: receiving is automatic, sending (올리기) is by hand
    private var lastReceive = 0L
    private fun autoReceive() {
        if (Backup.folder(this) == null || System.currentTimeMillis() - lastReceive < 60_000) return
        lastReceive = System.currentTimeMillis()
        Thread {
            val n = Backup.receive(this)
            runOnUiThread {
                if (n > 0) { all = Store.loadLibrary(this); refresh(); Toast.makeText(this, "다른 기기에서 고친 악보 ${n}곡을 받았습니다", Toast.LENGTH_LONG).show() }
                updateSyncBar()
            }
        }.start()
    }

    private fun updateSyncBar() {
        val bar = findViewById<View>(R.id.syncBar) ?: return
        if (Backup.folder(this) == null) { bar.visibility = View.GONE; return }
        bar.visibility = View.VISIBLE
        Thread {
            val n = Backup.pending(this)
            runOnUiThread { findViewById<TextView>(R.id.syncInfo).text = if (n > 0) "동기화: 아직 안 올린 변경 ${n}곡" else "동기화: 모두 올렸습니다" }
        }.start()
    }

    private fun refresh() {
        val q = search.text.toString().trim().lowercase()
        shown = all.filter { q.isEmpty() || it.shownTitle.lowercase().contains(q) || it.composer.lowercase().contains(q) }
            .sortedWith(compareBy({ it.composer.lowercase() }, { it.shownTitle.lowercase() }))
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        // grouped by composer; tap a composer to fold / unfold (searching shows everything)
        val r = mutableListOf<Any>()
        for ((comp, items) in shown.groupBy { it.composer.ifEmpty { "작곡가 미지정" } }) {
            r.add(comp)
            if (q.isNotEmpty() || comp !in collapsed) r.addAll(items)
        }
        rows = r
        list.adapter?.notifyDataSetChanged()
    }

    private fun countOf(comp: String) = shown.count { it.composer.ifEmpty { "작곡가 미지정" } == comp }

    private fun displayName(uri: Uri): String {
        var name = "악보"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        return name.removeSuffix(".pdf").removeSuffix(".PDF")
    }

    private fun importPdf(uri: Uri) {
        val id = Store.newId(); val fname = "$id.pdf"
        try {
            contentResolver.openInputStream(uri)!!.use { inp ->
                File(Store.scoreDir(this), fname).outputStream().use { inp.copyTo(it) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "파일을 가져오지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show(); return
        }
        val name = displayName(uri)
        val s = Score(id, name, "", fname, System.currentTimeMillis(), origName = name)
        editInfo(s, isNew = true)
    }

    private fun editInfo(s: Score, isNew: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val comp = EditText(this).apply { hint = "작곡가"; setText(s.composer) }
        val tit = EditText(this).apply { hint = "제목 (비우면 파일 이름)"; setText(s.title) }
        box.addView(comp); box.addView(tit)
        AlertDialog.Builder(this).setTitle(if (isNew) "악보 정보" else "정보 수정").setView(box)
            .setPositiveButton("저장") { _, _ ->
                s.composer = comp.text.toString().trim(); s.title = tit.text.toString().trim()   // empty = show the file name
                if (isNew) all.add(s)
                Store.saveLibrary(this, all); if (!isNew) Store.touch(this, s.id); refresh(); updateSyncBar()
            }
            .setNegativeButton("취소") { _, _ -> if (isNew) Store.deleteScore(this, s, remember = false) }
            .setCancelable(false).show()
    }

    private fun itemMenu(s: Score) {
        AlertDialog.Builder(this).setTitle(s.shownTitle)
            .setItems(arrayOf("제목·작곡가 수정", "삭제")) { _, w ->
                if (w == 0) editInfo(s, false)
                else AlertDialog.Builder(this).setMessage("'${s.shownTitle}'을(를) 삭제할까요? 연습 기록과 필기도 지워집니다.")
                    .setPositiveButton("삭제") { _, _ -> all.remove(s); Store.deleteScore(this, s); Store.saveLibrary(this, all); refresh(); updateSyncBar() }
                    .setNegativeButton("취소", null).show()
            }.show()
    }

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(pos: Int) = if (rows[pos] is String) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (viewType == 0) {
                val t = TextView(parent.context).apply {
                    textSize = 15f; setPadding(32, 30, 32, 14); layoutParams = lp
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(android.graphics.Color.rgb(63, 81, 181))
                    setBackgroundColor(android.graphics.Color.rgb(240, 241, 248))
                }
                return object : RecyclerView.ViewHolder(t) {}
            }
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = lp
            }
            val box = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 24, 16, 24) }
            box.addView(TextView(parent.context).apply { textSize = 18f })
            box.addView(TextView(parent.context).apply { textSize = 13f; alpha = 0.7f })
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(parent.context).apply { text = "✎"; textSize = 22f; setPadding(36, 24, 40, 24); contentDescription = "제목·작곡가 수정" })
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val item = rows[pos]
            if (item is String) {
                val t = h.itemView as TextView
                t.text = (if (item in collapsed && search.text.isEmpty()) "▸ " else "▾ ") + item + "  (" + countOf(item) + ")"
                t.setOnClickListener { if (!collapsed.remove(item)) collapsed.add(item); refresh() }
                return
            }
            val s = item as Score
            val row = h.itemView as LinearLayout; val box = row.getChildAt(0) as LinearLayout
            (box.getChildAt(0) as TextView).text = s.shownTitle
            val sum = try { PracticeStats.summary(Store.loadData(this@MainActivity, s.id)) } catch (e: Exception) { "" }
            (box.getChildAt(1) as TextView).text = sum.ifEmpty { "아직 연습 기록 없음" }
            box.setOnClickListener { startActivity(Intent(this@MainActivity, ViewerActivity::class.java).putExtra("id", s.id)) }
            box.setOnLongClickListener { itemMenu(s); true }
            row.getChildAt(1).setOnClickListener { editInfo(s, isNew = false) }
        }

        override fun getItemCount() = rows.size
    }
}

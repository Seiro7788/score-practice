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
            contentResolver.openOutputStream(uri)!!.use { Backup.writeZip(this, it) }; "백업 파일을 저장했습니다"
        }
    }
    private val loadBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background("백업 불러오는 중…") {
            val n = contentResolver.openInputStream(uri)!!.use { Backup.readZip(this, it) }
            Backup.sync(this); "악보 ${n}개를 불러왔습니다"
        }
    }

    private fun background(msg: String, work: () -> String) {
        val dlg = AlertDialog.Builder(this).setMessage(msg).setCancelable(false).show()
        Thread {
            val r = try { work() } catch (e: Exception) { "실패: ${e.message}" }
            runOnUiThread { dlg.dismiss(); Toast.makeText(this, r, Toast.LENGTH_LONG).show(); all = Store.loadLibrary(this); refresh() }
        }.start()
    }

    private fun chooseFolder(uri: Uri) {
        try { Backup.setFolder(this, uri) } catch (e: Exception) { Toast.makeText(this, "이 폴더는 쓸 수 없습니다", Toast.LENGTH_LONG).show(); return }
        if (Backup.folderHasLibrary(this, uri))
            AlertDialog.Builder(this).setTitle("저장 폴더").setMessage("이 폴더에 전에 저장한 악보가 있습니다. 불러올까요?")
                .setPositiveButton("불러오기") { _, _ -> background("불러오는 중…") { val n = Backup.importFromFolder(this, uri); Backup.sync(this); "악보 ${n}개를 불러왔습니다" } }
                .setNegativeButton("아니요") { _, _ -> background("저장하는 중…") { if (Backup.sync(this)) "이 폴더에 저장했습니다" else "저장 실패" } }
                .show()
        else background("저장하는 중…") { if (Backup.sync(this)) "이제 이 폴더에 자동으로 저장됩니다" else "저장 실패" }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "저장 폴더 지정 (앱을 지워도 남음)")
        menu.add(0, 2, 1, "백업 파일 만들기 (구글드라이브 등)")
        menu.add(0, 3, 2, "백업 불러오기")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> pickFolder.launch(null)
            2 -> makeBackup.launch("악보연습_백업_" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(java.util.Date()) + ".zip")
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
                .setMessage("전에 쓰던 악보가 있으면 불러올 수 있습니다.\n\n· 저장 폴더: 앱을 지우기 전에 지정해 둔 휴대폰 폴더\n· 백업 파일: 구글드라이브 등에 올려 둔 백업\n\n나중에 오른쪽 위 메뉴(⋮)에서도 할 수 있습니다.")
                .setPositiveButton("저장 폴더 고르기") { _, _ -> pickFolder.launch(null) }
                .setNeutralButton("백업 파일") { _, _ -> loadBackup.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
                .setNegativeButton("새로 시작", null).show()
    }

    override fun onResume() {
        super.onResume(); all = Store.loadLibrary(this); refresh()
        Thread { Backup.sync(this) }.start()   // pick up changes made in the score screen
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
                Store.saveLibrary(this, all); refresh(); Thread { Backup.sync(this) }.start()
            }
            .setNegativeButton("취소") { _, _ -> if (isNew) Store.deleteScore(this, s) }
            .setCancelable(false).show()
    }

    private fun itemMenu(s: Score) {
        AlertDialog.Builder(this).setTitle(s.shownTitle)
            .setItems(arrayOf("제목·작곡가 수정", "삭제")) { _, w ->
                if (w == 0) editInfo(s, false)
                else AlertDialog.Builder(this).setMessage("'${s.shownTitle}'을(를) 삭제할까요? 연습 기록과 필기도 지워집니다.")
                    .setPositiveButton("삭제") { _, _ -> all.remove(s); Store.deleteScore(this, s); Store.saveLibrary(this, all); refresh(); Thread { Backup.sync(this) }.start() }
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

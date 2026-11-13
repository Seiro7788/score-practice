package com.scorepractice.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
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
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var search: EditText

    // Storage Access Framework picker: shows phone storage AND Google Drive (if the Drive app is installed)
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
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
    }

    override fun onResume() {
        super.onResume(); all = Store.loadLibrary(this); refresh()
    }

    private fun refresh() {
        val q = search.text.toString().trim().lowercase()
        shown = all.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.composer.lowercase().contains(q) }
            .sortedWith(compareBy({ it.composer.lowercase() }, { it.title.lowercase() }))
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        list.adapter?.notifyDataSetChanged()
    }

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
        val s = Score(id, displayName(uri), "", fname, System.currentTimeMillis())
        editInfo(s, isNew = true)
    }

    private fun editInfo(s: Score, isNew: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val comp = EditText(this).apply { hint = "작곡가"; setText(s.composer) }
        val tit = EditText(this).apply { hint = "제목"; setText(s.title) }
        box.addView(comp); box.addView(tit)
        AlertDialog.Builder(this).setTitle(if (isNew) "악보 정보" else "정보 수정").setView(box)
            .setPositiveButton("저장") { _, _ ->
                s.composer = comp.text.toString().trim(); s.title = tit.text.toString().trim().ifEmpty { "제목 없음" }
                if (isNew) all.add(s)
                Store.saveLibrary(this, all); refresh()
            }
            .setNegativeButton("취소") { _, _ -> if (isNew) Store.deleteScore(this, s) }
            .setCancelable(false).show()
    }

    private fun itemMenu(s: Score) {
        AlertDialog.Builder(this).setTitle(s.title)
            .setItems(arrayOf("제목·작곡가 수정", "삭제")) { _, w ->
                if (w == 0) editInfo(s, false)
                else AlertDialog.Builder(this).setMessage("'${s.title}'을(를) 삭제할까요? 연습 기록과 필기도 지워집니다.")
                    .setPositiveButton("삭제") { _, _ -> all.remove(s); Store.deleteScore(this, s); Store.saveLibrary(this, all); refresh() }
                    .setNegativeButton("취소", null).show()
            }.show()
    }

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(40, 28, 40, 28)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_VERTICAL
            }
            val t = TextView(parent.context).apply { textSize = 18f }
            val c = TextView(parent.context).apply { textSize = 14f; alpha = 0.7f }
            box.addView(t); box.addView(c)
            return object : RecyclerView.ViewHolder(box) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val s = shown[pos]; val box = h.itemView as LinearLayout
            (box.getChildAt(0) as TextView).text = s.title
            val sum = try { PracticeStats.summary(Store.loadData(this@MainActivity, s.id)) } catch (e: Exception) { "" }
            (box.getChildAt(1) as TextView).text = s.composer.ifEmpty { "작곡가 미지정" } + if (sum.isNotEmpty()) "   ·   $sum" else ""
            box.setOnClickListener {
                startActivity(Intent(this@MainActivity, ViewerActivity::class.java).putExtra("id", s.id))
            }
            box.setOnLongClickListener { itemMenu(s); true }
        }

        override fun getItemCount() = shown.size
    }
}

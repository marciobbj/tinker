package com.pdfreader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.pdfreader.R
import com.pdfreader.data.BookmarkStore
import com.pdfreader.data.SettingsStore

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabOpen: FloatingActionButton
    private lateinit var emptyView: TextView
    private val bookmarkStore by lazy { BookmarkStore(this) }
    private var recentList = mutableListOf<BookmarkStore.Bookmark>()

    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistent permission so we can reopen later from the list
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            openReader(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        fabOpen = findViewById(R.id.fabOpen)
        emptyView = findViewById(R.id.emptyView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = RecentAdapter()
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        fabOpen.setOnClickListener {
            openPdfLauncher.launch(arrayOf("application/pdf"))
        }

        // Handle incoming PDF share / open
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            openReader(intent.data!!)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun applyTheme() {
        val dark = SettingsStore(this).darkMode
        setTheme(if (dark) R.style.Theme_PdfReader_Dark else R.style.Theme_PdfReader)
    }

    private fun refreshList() {
        recentList.clear()
        recentList.addAll(bookmarkStore.getRecent())
        (recyclerView.adapter as? RecentAdapter)?.notifyDataSetChanged()
        emptyView.visibility = if (recentList.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (recentList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openReader(uri: Uri) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    inner class RecentAdapter : RecyclerView.Adapter<RecentAdapter.VH>() {
        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.itemTitle)
            val subtitle: TextView = itemView.findViewById(R.id.itemSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = recentList.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val bm = recentList[position]
            val displayTitle = if (bm.title.isNotBlank()) {
                bm.title.removeSuffix(".pdf")
            } else {
                try {
                    val decoded = java.net.URLDecoder.decode(bm.uri, "UTF-8")
                    decoded.substringAfterLast("/").removeSuffix(".pdf").ifBlank { "PDF" }
                } catch (_: Exception) { "PDF" }
            }
            holder.title.text = displayTitle
            val modeStr = if (bm.displayMode == SettingsStore.DISPLAY_MODE_BOOK) {
                getString(R.string.mode_book)
            } else {
                getString(R.string.mode_vertical)
            }
            holder.subtitle.text = getString(R.string.page_mode_format, bm.pageNumber + 1, modeStr)
            holder.itemView.setOnClickListener {
                openReader(Uri.parse(bm.uri))
            }

            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.remove_recent_title)
                    .setMessage(getString(R.string.remove_recent_message, displayTitle))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.remove_recent_confirm) { _, _ ->
                        // Best-effort: drop persisted permission so the import can be fully discarded.
                        try {
                            contentResolver.releasePersistableUriPermission(
                                Uri.parse(bm.uri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                        }

                        bookmarkStore.remove(bm.uri)
                        refreshList()
                    }
                    .show()
                true
            }
        }
    }
}

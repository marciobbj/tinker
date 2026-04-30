package com.pdfreader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        val adapter = RecentAdapter()
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        ItemTouchHelper(RecentSwipeCallback(adapter)).attachToRecyclerView(recyclerView)

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

    private fun confirmRemoveRecent(onConfirm: () -> Unit, onCancel: () -> Unit = {}) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_recent_title)
            .setMessage(R.string.remove_recent_message)
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                onCancel()
            }
            .setPositiveButton(R.string.remove_recent_confirm) { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .show()
    }

    private fun removeRecentAt(position: Int) {
        val item = recentList.getOrNull(position) ?: return
        bookmarkStore.remove(item.uri)
        recentList.removeAt(position)
        recyclerView.adapter?.notifyItemRemoved(position)
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
            val deleteBtn: ImageView = itemView.findViewById(R.id.itemDelete)
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
            holder.deleteBtn.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    confirmRemoveRecent(onConfirm = { removeRecentAt(pos) })
                }
            }
        }

        fun removeAt(position: Int): BookmarkStore.Bookmark? {
            if (position !in recentList.indices) return null
            val removed = recentList.removeAt(position)
            notifyItemRemoved(position)
            return removed
        }

        fun restoreAt(position: Int, item: BookmarkStore.Bookmark) {
            val insertAt = position.coerceIn(0, recentList.size)
            recentList.add(insertAt, item)
            notifyItemInserted(insertAt)
        }
    }

    private inner class RecentSwipeCallback(
        private val adapter: RecentAdapter
    ) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val item = recentList.getOrNull(position) ?: return
            adapter.removeAt(position)
            confirmRemoveRecent(
                onConfirm = { bookmarkStore.remove(item.uri) },
                onCancel = { adapter.restoreAt(position, item) }
            )
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            val itemView = viewHolder.itemView
            if (dX < 0) {
                val background = ColorDrawable(ContextCompat.getColor(this@MainActivity, R.color.swipe_delete_red))
                background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                background.draw(c)

                val icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_delete)
                if (icon != null) {
                    val margin = (itemView.height - icon.intrinsicHeight) / 2
                    val top = itemView.top + margin
                    val bottom = top + icon.intrinsicHeight
                    val left = itemView.right - margin - icon.intrinsicWidth
                    val right = itemView.right - margin
                    icon.setBounds(left, top, right, bottom)
                    icon.draw(c)
                }
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }
}

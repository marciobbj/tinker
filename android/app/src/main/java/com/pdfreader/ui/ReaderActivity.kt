package com.pdfreader.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.core.content.ContextCompat
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pdfreader.R
import com.pdfreader.core.PdfDocument
import com.pdfreader.data.AnnotationStore
import com.pdfreader.data.BookmarkStore
import com.pdfreader.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ReaderActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var pageIndicator: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnGoTo: ImageButton
    private lateinit var btnMode: ImageButton
    private lateinit var btnDark: ImageButton
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnAnnotations: ImageButton
    private lateinit var btnPen: ImageButton

    private var isDrawingMode = false
    private var inkDrawingView: InkDrawingView? = null


    private var pdfDocument: PdfDocument? = null
    private var tempFile: File? = null
    private val bookmarkStore by lazy { BookmarkStore(this) }
    private val settingsStore by lazy { SettingsStore(this) }
    private val annotationStore by lazy { AnnotationStore(this) }

    private var verticalView: PdfVerticalView? = null
    private var bookView: PdfBookView? = null
    private var currentMode = SettingsStore.DISPLAY_MODE_VERTICAL
    private var uiVisible = true
    private var documentTitle: String = ""
    private var documentReady = false
    private var selectionPopup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        container = findViewById(R.id.container)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        pageIndicator = findViewById(R.id.pageIndicator)
        btnBack = findViewById(R.id.btnBack)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnGoTo = findViewById(R.id.btnGoTo)
        btnMode = findViewById(R.id.btnMode)
        btnDark = findViewById(R.id.btnDark)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnAnnotations = findViewById(R.id.btnAnnotations)
        btnPen = findViewById(R.id.btnPen)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDrawingMode) {
                    exitDrawingMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        val uri = intent.data ?: run { finish(); return }

        // Get display name from ContentResolver for the title
        documentTitle = getFileName(uri) ?: "PDF"

        lifecycleScope.launch {
            copyUriToFile(uri)?.let { file ->
                tempFile = file
                openDocument(file.absolutePath)
                loadBookmark(uri)
                documentReady = true
            } ?: run {
                finish()
            }
        }

        setupControls()
    }

    private fun applyTheme() {
        val dark = SettingsStore(this).darkMode
        setTheme(if (dark) R.style.Theme_PdfReader_Dark_NoActionBar else R.style.Theme_PdfReader_NoActionBar)
    }

    private suspend fun copyUriToFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri) ?: "document.pdf"
            val file = File(cacheDir, fileName)
            // If cached file exists, reuse it to preserve saved annotations
            if (file.exists() && file.length() > 0) return@withContext file
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private suspend fun openDocument(path: String) {
        val saved = bookmarkStore.load(intent.data.toString())
        currentMode = saved?.displayMode ?: settingsStore.defaultDisplayMode

        val doc = PdfDocument(path)
        val ok = doc.open(precachePageSizes = currentMode == SettingsStore.DISPLAY_MODE_VERTICAL)
        if (!ok) return
        pdfDocument = doc
        doc.setDarkMode(settingsStore.darkMode)

        // Try to get the PDF metadata title; fallback to filename
        val metaTitle = doc.getTitle()
        if (metaTitle.isNotBlank()) {
            documentTitle = metaTitle
        }

        setupReader(currentMode)
        updatePageIndicator()
    }

    private fun loadBookmark(uri: Uri) {
        val saved = bookmarkStore.load(uri.toString())
        saved?.let { bm ->
            applyPageToCurrentView(bm.pageNumber)
        }
    }

    private fun setupReader(mode: Int, initialPage: Int = 0) {
        verticalView?.cleanup()
        bookView?.cleanup()
        container.removeAllViews()
        verticalView = null
        bookView = null

        val doc = pdfDocument ?: return
        val pageCount = doc.pageCount
        val targetPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

        val onTap = {
            toggleUi()
        }

        if (mode == SettingsStore.DISPLAY_MODE_BOOK) {
            currentMode = SettingsStore.DISPLAY_MODE_BOOK
            val view = PdfBookView(this)
            view.pageCount = pageCount
            view.getPageSize = { page -> doc.getPageSize(page) }
            view.getPageSizeAsync = { page -> doc.getPageSizeAsync(page) }
            view.renderPage = { page, w, h, zoom, reused -> doc.renderPage(page, w, h, zoom, reused) }
            view.getSelectionQuads = { page, ax, ay, bx, by, mode -> doc.getSelectionQuads(page, ax, ay, bx, by, mode) }
            view.copySelectionText = { page, ax, ay, bx, by, mode -> doc.copySelectionText(page, ax, ay, bx, by, mode) }
            view.listener = object : PdfBookView.Listener {
                override fun onPageChanged(page: Int) = updatePageIndicator()
                override fun onPageTap() = onTap()
                override fun onSelectionFinished(page: Int, quads: FloatArray, text: String) {
                    showSelectionActions(page, quads, text)
                }
            }
            container.addView(view)
            bookView = view
            view.setPage(targetPage)
        } else {
            currentMode = SettingsStore.DISPLAY_MODE_VERTICAL
            val view = PdfVerticalView(this)
            view.pageCount = pageCount
            view.maxPageWidth = doc.maxWidth
            view.getPageSize = { page -> doc.getPageSize(page) }
            view.renderPage = { page, w, h, zoom, reused -> doc.renderPage(page, w, h, zoom, reused) }
            view.getSelectionQuads = { page, ax, ay, bx, by, mode -> doc.getSelectionQuads(page, ax, ay, bx, by, mode) }
            view.copySelectionText = { page, ax, ay, bx, by, mode -> doc.copySelectionText(page, ax, ay, bx, by, mode) }
            view.listener = object : PdfVerticalView.Listener {
                override fun onPageVisible(page: Int) = updatePageIndicator(page)
                override fun onPageTap() = onTap()
                override fun onSelectionFinished(page: Int, quads: FloatArray, text: String) {
                    showSelectionActions(page, quads, text)
                }
            }
            container.addView(view)
            verticalView = view
            view.post { view.scrollToPage(targetPage) }
        }

        updatePageIndicator(targetPage)
    }

    private fun applyPageToCurrentView(page: Int) {
        val pageCount = pdfDocument?.pageCount ?: return
        val targetPage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
            bookView?.setPage(targetPage)
        } else {
            verticalView?.post { verticalView?.scrollToPage(targetPage) }
        }
        updatePageIndicator(targetPage)
    }

    private fun getCurrentReaderPage(): Int {
        val pageCount = (pdfDocument?.pageCount ?: 1).coerceAtLeast(1)
        val rawPage = if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
            bookView?.currentPage ?: 0
        } else {
            verticalView?.getCurrentPage() ?: 0
        }
        return rawPage.coerceIn(0, pageCount - 1)
    }

    private fun setupControls() {
        btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        btnPrev.setOnClickListener {
            if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                bookView?.goToPage((bookView?.currentPage ?: 0) - 1)
            } else {
                val vp = verticalView?.getCurrentPage() ?: 0
                verticalView?.scrollToPage((vp - 1).coerceAtLeast(0))
            }
        }

        btnNext.setOnClickListener {
            if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                bookView?.goToPage((bookView?.currentPage ?: 0) + 1)
            } else {
                val vp = verticalView?.getCurrentPage() ?: 0
                val pc = pdfDocument?.pageCount ?: 1
                verticalView?.scrollToPage((vp + 1).coerceAtMost(pc - 1))
            }
        }

        btnGoTo.setOnClickListener {
            if (!documentReady) return@setOnClickListener
            showGoToPageDialog()
        }

        btnMode.setOnClickListener {
            val currentPage = getCurrentReaderPage()
            val newMode = if (currentMode == SettingsStore.DISPLAY_MODE_VERTICAL)
                SettingsStore.DISPLAY_MODE_BOOK else SettingsStore.DISPLAY_MODE_VERTICAL
            lifecycleScope.launch {
                if (newMode == SettingsStore.DISPLAY_MODE_VERTICAL) {
                    pdfDocument?.ensurePageSizesCached()
                }
                currentMode = newMode
                setupReader(newMode, currentPage)
                saveBookmark(currentPage)
            }
        }

        btnDark.setOnClickListener {
            val newDark = !settingsStore.darkMode
            settingsStore.darkMode = newDark
            lifecycleScope.launch {
                pdfDocument?.setDarkMode(newDark)
                // Force refresh of all pages to ensure annotations are properly rendered
                if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                    bookView?.refreshAllPages()
                } else {
                    verticalView?.refreshAllPages()
                }
            }
        }

        btnBookmark.setOnClickListener {
            if (!documentReady) return@setOnClickListener
            saveBookmark()
            AlertDialog.Builder(this)
                .setMessage(R.string.bookmark_saved)
                .setPositiveButton("OK", null)
                .show()
        }

        btnAnnotations.setOnClickListener {
            if (!documentReady) return@setOnClickListener
            showAnnotationsPanel()
        }

        btnPen.setOnClickListener {
            if (!documentReady) return@setOnClickListener
            enterDrawingMode()
        }
    }

    private fun showGoToPageDialog() {
        val pageCount = pdfDocument?.pageCount ?: return
        if (pageCount <= 0) return

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            val current = (getCurrentReaderPage() + 1).toString()
            setText(current)
            setSelection(current.length)
            hint = getString(R.string.go_to_page_hint, pageCount)
        }

        val horizontal = (resources.displayMetrics.density * 24).toInt()
        val vertical = (resources.displayMetrics.density * 12).toInt()
        input.setPadding(horizontal, vertical, horizontal, vertical)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.go_to_page_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.go_to_page_confirm, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val targetOneBased = input.text?.toString()?.trim()?.toIntOrNull()
                if (targetOneBased == null || targetOneBased < 1 || targetOneBased > pageCount) {
                    input.error = getString(R.string.go_to_page_invalid)
                    return@setOnClickListener
                }
                val targetPage = targetOneBased - 1
                applyPageToCurrentView(targetPage)
                saveBookmark(targetPage)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun toggleUi() {
        uiVisible = !uiVisible
        val vis = if (uiVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
    }

    private fun showSelectionActions(page: Int, quads: FloatArray, text: String) {
        if (quads.isEmpty()) {
            clearSelectionOverlays()
            return
        }
        dismissSelectionPopup()

        val toolbarView = LayoutInflater.from(this).inflate(R.layout.selection_toolbar, null)
        val popup = PopupWindow(
            toolbarView,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            false // not focusable — lets touches pass through to the view for dismissal
        )
        popup.isOutsideTouchable = true
        popup.elevation = 12f * resources.displayMetrics.density
        popup.setOnDismissListener { clearSelectionOverlays() }

        toolbarView.findViewById<View>(R.id.btnCopy).setOnClickListener {
            copyToClipboard(text)
            dismissSelectionPopup()
            clearSelectionOverlays()
        }
        toolbarView.findViewById<View>(R.id.btnHighlight).setOnClickListener {
            applyHighlight(page, quads, text)
            dismissSelectionPopup()
            clearSelectionOverlays()
        }

        selectionPopup = popup

        // Position the popup above the selection bounds
        val bounds = if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
            bookView?.getSelectionScreenBounds()
        } else {
            verticalView?.getSelectionScreenBounds()
        }

        // Measure to know the popup dimensions
        toolbarView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = toolbarView.measuredWidth
        val popupH = toolbarView.measuredHeight
        val margin = (8 * resources.displayMetrics.density).toInt()

        if (bounds != null) {
            val anchorView = (bookView ?: verticalView) as View
            val loc = IntArray(2)
            anchorView.getLocationOnScreen(loc)

            val centerX = loc[0] + ((bounds.left + bounds.right) / 2f).toInt()
            val topY = loc[1] + bounds.top.toInt()

            var x = centerX - popupW / 2
            var y = topY - popupH - margin

            // Clamp to screen
            val screenW = resources.displayMetrics.widthPixels
            x = x.coerceIn(margin, (screenW - popupW - margin).coerceAtLeast(margin))
            if (y < margin) {
                // Show below selection if not enough room above
                y = loc[1] + bounds.bottom.toInt() + margin
            }

            popup.showAtLocation(anchorView, Gravity.NO_GRAVITY, x, y)
        } else {
            // Fallback: center at top of container
            popup.showAtLocation(container, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, margin + (56 * resources.displayMetrics.density).toInt())
        }
    }

    private fun dismissSelectionPopup() {
        try { selectionPopup?.dismiss() } catch (_: Exception) {}
        selectionPopup = null
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard != null) {
            val clip = ClipData.newPlainText("pdf-selection", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.selection_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyHighlight(page: Int, quads: FloatArray, text: String = "") {
        val doc = pdfDocument ?: return
        val uri = intent.data?.toString() ?: return
        val highlightColor = ContextCompat.getColor(this, R.color.highlight_color)
        val opacity = 0.35f
        lifecycleScope.launch {
            val ok = doc.addMarkupAnnotation(page, PdfDocument.ANNOT_HIGHLIGHT, quads, highlightColor, opacity)
            if (ok) {
                // Save the PDF to persist the annotation
                doc.saveDocument()
                // Store metadata for the annotations panel
                val snippet = text.take(150).ifBlank { "Highlight" }
                annotationStore.addAnnotation(uri, AnnotationStore.AnnotationEntry(
                    page = page,
                    type = PdfDocument.ANNOT_HIGHLIGHT,
                    textSnippet = snippet,
                    quads = quads
                ))
                if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                    bookView?.invalidatePage(page)
                } else {
                    verticalView?.invalidatePage(page)
                }
            }
        }
    }

    private fun showAnnotationsPanel() {
        val uri = intent.data?.toString() ?: return
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_annotations, null)
        dialog.setContentView(view)

        val bookmarkSection = view.findViewById<View>(R.id.bookmarkSection)
        val bookmarkItem = view.findViewById<View>(R.id.bookmarkItem)
        val bookmarkText = view.findViewById<TextView>(R.id.bookmarkText)
        val highlightsHeader = view.findViewById<View>(R.id.highlightsHeader)
        val highlightsList = view.findViewById<RecyclerView>(R.id.highlightsList)
        val emptyState = view.findViewById<View>(R.id.emptyState)

        // Bookmark section
        val bookmark = bookmarkStore.load(uri)
        if (bookmark != null) {
            bookmarkSection.visibility = View.VISIBLE
            bookmarkText.text = getString(R.string.annotations_page_format, bookmark.pageNumber + 1)
            bookmarkItem.setOnClickListener {
                applyPageToCurrentView(bookmark.pageNumber)
                dialog.dismiss()
            }
        }

        // Highlights section
        val annotations = annotationStore.loadAnnotations(uri)
        if (annotations.isNotEmpty()) {
            highlightsHeader.visibility = View.VISIBLE
            val adapter = AnnotationAdapter(
                annotations.toMutableList(),
                { entry ->
                    applyPageToCurrentView(entry.page)
                    dialog.dismiss()
                }
            )
            highlightsList.layoutManager = LinearLayoutManager(this)
            highlightsList.adapter = adapter
            ItemTouchHelper(SwipeToDeleteCallback(adapter, dialog, uri)).attachToRecyclerView(highlightsList)
        } else if (bookmark == null) {
            emptyState.visibility = View.VISIBLE
        }

        dialog.show()
    }

    private fun confirmDeleteHighlight(onConfirm: () -> Unit, onCancel: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_highlight_title)
            .setMessage(R.string.delete_highlight_message)
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                onCancel()
            }
            .setOnCancelListener {
                onCancel()
            }
            .setPositiveButton(R.string.delete_highlight_confirm) { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .show()
    }

    private fun deleteHighlight(
        uri: String,
        index: Int,
        entry: AnnotationStore.AnnotationEntry,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val doc = pdfDocument ?: return
        lifecycleScope.launch {
            val deleted = if (entry.type == PdfDocument.ANNOT_INK) {
                doc.deleteInkAnnotation(entry.page, entry.inkPoints)
            } else {
                doc.deleteMarkupAnnotation(entry.page, entry.type, entry.quads)
            }
            val saved = deleted && doc.saveDocument()
            if (deleted && saved) {
                annotationStore.removeAnnotation(uri, index)
                if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                    bookView?.invalidatePage(entry.page)
                } else {
                    verticalView?.invalidatePage(entry.page)
                }
                onSuccess()
            } else {
                Toast.makeText(
                    this@ReaderActivity,
                    R.string.delete_highlight_failed,
                    Toast.LENGTH_SHORT
                ).show()
                onFailure()
            }
        }
    }

    private inner class AnnotationAdapter(
        private val items: MutableList<AnnotationStore.AnnotationEntry>,
        private val onClick: (AnnotationStore.AnnotationEntry) -> Unit
    ) : RecyclerView.Adapter<AnnotationAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val page: TextView = view.findViewById(R.id.annotPage)
            val text: TextView = view.findViewById(R.id.annotText)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_annotation, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.page.text = getString(R.string.annotations_page_format, entry.page + 1)
            holder.text.text = if (entry.type == PdfDocument.ANNOT_INK) {
                getString(R.string.ink_annotation_label)
            } else {
                entry.textSnippet
            }
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount() = items.size

        fun removeAt(position: Int): AnnotationStore.AnnotationEntry? {
            if (position !in items.indices) return null
            val removed = items.removeAt(position)
            notifyItemRemoved(position)
            return removed
        }

        fun restoreItem(position: Int, entry: AnnotationStore.AnnotationEntry) {
            val insertAt = position.coerceIn(0, items.size)
            items.add(insertAt, entry)
            notifyItemInserted(insertAt)
        }

        fun getItem(position: Int): AnnotationStore.AnnotationEntry? = items.getOrNull(position)
    }

    private inner class SwipeToDeleteCallback(
        private val adapter: AnnotationAdapter,
        private val dialog: BottomSheetDialog,
        private val uri: String
    ) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val entry = adapter.getItem(position) ?: return
            val removed = adapter.removeAt(position) ?: return
            confirmDeleteHighlight(
                onConfirm = {
                    deleteHighlight(
                        uri = uri,
                        index = position,
                        entry = entry,
                        onSuccess = {
                            dialog.dismiss()
                            showAnnotationsPanel()
                        },
                        onFailure = {
                            adapter.restoreItem(position, removed)
                        }
                    )
                },
                onCancel = {
                    adapter.restoreItem(position, removed)
                }
            )
        }

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            val itemView = viewHolder.itemView
            val background = ColorDrawable(ContextCompat.getColor(this@ReaderActivity, R.color.swipe_delete_red))
            val icon = ContextCompat.getDrawable(this@ReaderActivity, android.R.drawable.ic_menu_delete) ?: return
            val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
            val iconTop = itemView.top + iconMargin
            val iconBottom = iconTop + icon.intrinsicHeight

            if (dX < 0) {
                background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                background.draw(c)
                val iconLeft = itemView.right - iconMargin - icon.intrinsicWidth
                val iconRight = itemView.right - iconMargin
                icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                icon.draw(c)
            }

            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private fun clearSelectionOverlays() {
        dismissSelectionPopup()
        bookView?.clearSelection()
        verticalView?.clearSelection()
    }

    private fun updatePageIndicator(page: Int = -1) {
        val pc = pdfDocument?.pageCount ?: 0
        val cp = if (page >= 0) page else (bookView?.currentPage ?: 0)
        pageIndicator.text = getString(R.string.page_indicator_format, cp + 1, pc)
    }

    private fun saveBookmark(pageOverride: Int? = null) {
        if (!documentReady) return
        val uri = intent.data?.toString() ?: return
        try {
            val currentPage = (pageOverride ?: when (currentMode) {
                SettingsStore.DISPLAY_MODE_BOOK -> bookView?.currentPage ?: 0
                else -> verticalView?.getCurrentPage() ?: 0
            }).coerceAtLeast(0)
            val bm = BookmarkStore.Bookmark(
                uri = uri,
                title = documentTitle,
                pageNumber = currentPage,
                scrollY = 0f,
                displayMode = currentMode,
                totalPages = pdfDocument?.pageCount ?: 1
            )
            bookmarkStore.save(bm)
        } catch (_: Exception) {
            // Prevent crash in edge cases
        }
    }

    override fun onPause() {
        super.onPause()
        saveBookmark()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissSelectionPopup()
        // Cancel all render coroutines first
        try { verticalView?.cleanup() } catch (_: Exception) {}
        try { bookView?.cleanup() } catch (_: Exception) {}
        // Close document on background thread to avoid blocking Main
        val doc = pdfDocument
        val file = tempFile
        pdfDocument = null
        documentReady = false
        if (doc != null || file != null) {
            Thread {
                try { doc?.close() } catch (_: Exception) {}
                // Don't delete temp file — it contains saved annotations
            }.start()
        }
    }

    private fun enterDrawingMode() {
        if (isDrawingMode) return
        isDrawingMode = true

        // Hide UI bars
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        uiVisible = false

        // Disable touch (scrolling/zooming) on reader views
        verticalView?.touchEnabled = false
        bookView?.touchEnabled = false

        // Create and add the InkDrawingView overlay
        val drawView = InkDrawingView(this).apply {
            penColor = ContextCompat.getColor(this@ReaderActivity, R.color.ink_black)
            penSize = 3.5f // Medium size
            isEraserMode = false
            pageNumber = getCurrentReaderPage()
            pageTransformProvider = {
                if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                    bookView?.getPageTransform(pageNumber)?.let { t ->
                        InkDrawingView.PageTransform(t.offsetX, t.offsetY, t.scale)
                      }
                } else {
                    verticalView?.getPageTransform(pageNumber)?.let { t ->
                        InkDrawingView.PageTransform(t.offsetX, t.offsetY, t.scale)
                    }
                }
            }
        }
        
        container.addView(drawView)
        inkDrawingView = drawView

        // Load existing ink annotations from PDF in background
        lifecycleScope.launch {
            val doc = pdfDocument ?: return@launch
            val page = drawView.pageNumber
            val flatData = doc.getInkAnnotations(page)
            if (flatData != null && flatData.isNotEmpty()) {
                val parsed = parseStrokes(flatData)
                if (isDrawingMode && inkDrawingView == drawView) {
                    drawView.setInitialStrokes(parsed)
                    // Clear ink annotations from PDF memory so they are not rendered under our overlay
                    doc.clearInkAnnotations(page)
                    if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                        bookView?.refreshAllPages()
                    } else {
                        verticalView?.refreshAllPages()
                    }
                }
            }
        }

        // Configure toolbar
        setupPenToolbar()
    }

    private fun exitDrawingMode() {
        if (!isDrawingMode) return
        isDrawingMode = false

        // Remove InkDrawingView overlay
        inkDrawingView?.let {
            container.removeView(it)
        }
        inkDrawingView = null

        // Re-enable touch on reader views
        verticalView?.touchEnabled = true
        bookView?.touchEnabled = true

        // Hide pen toolbar
        findViewById<View>(R.id.penToolbar).visibility = View.GONE

        // Restore UI bars
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        uiVisible = true
    }

    private fun setupPenToolbar() {
        val toolbar = findViewById<View>(R.id.penToolbar)
        toolbar.visibility = View.VISIBLE

        val colorBlack = toolbar.findViewById<FrameLayout>(R.id.colorBlack)
        val colorRed = toolbar.findViewById<FrameLayout>(R.id.colorRed)
        val colorBlue = toolbar.findViewById<FrameLayout>(R.id.colorBlue)
        val colorGreen = toolbar.findViewById<FrameLayout>(R.id.colorGreen)
        val colorOrange = toolbar.findViewById<FrameLayout>(R.id.colorOrange)
        val colorPurple = toolbar.findViewById<FrameLayout>(R.id.colorPurple)

        val sizeThin = toolbar.findViewById<ImageButton>(R.id.sizeThin)
        val sizeMedium = toolbar.findViewById<ImageButton>(R.id.sizeMedium)
        val sizeThick = toolbar.findViewById<ImageButton>(R.id.sizeThick)

        val btnUndo = toolbar.findViewById<ImageButton>(R.id.btnUndo)
        val btnEraser = toolbar.findViewById<ImageButton>(R.id.btnEraser)
        val btnDone = toolbar.findViewById<ImageButton>(R.id.btnDone)

        val drawDot = { sizeDp: Int ->
            val density = resources.displayMetrics.density
            val sizePx = (36 * density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(this@ReaderActivity, R.color.ink_black)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, (sizeDp / 2f) * density, p)
            android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }

        sizeThin.setImageDrawable(drawDot(4))
        sizeMedium.setImageDrawable(drawDot(8))
        sizeThick.setImageDrawable(drawDot(12))

        val colors = listOf(colorBlack, colorRed, colorBlue, colorGreen, colorOrange, colorPurple)
        val colorValues = listOf(
            ContextCompat.getColor(this, R.color.ink_black),
            ContextCompat.getColor(this, R.color.ink_red),
            ContextCompat.getColor(this, R.color.ink_blue),
            ContextCompat.getColor(this, R.color.ink_green),
            ContextCompat.getColor(this, R.color.ink_orange),
            ContextCompat.getColor(this, R.color.ink_purple)
        )

        fun updateSelectedColor(selectedLayout: FrameLayout) {
            for (c in colors) {
                if (c == selectedLayout) {
                    c.setBackgroundResource(R.drawable.bg_color_dot_selected)
                } else {
                    c.background = null
                }
            }
        }

        for (i in colors.indices) {
            val layout = colors[i]
            val value = colorValues[i]
            layout.setOnClickListener {
                inkDrawingView?.penColor = value
                inkDrawingView?.isEraserMode = false
                btnEraser.isSelected = false
                btnEraser.setBackgroundResource(0)
                updateSelectedColor(layout)
            }
        }
        
        updateSelectedColor(colorBlack)

        val sizes = listOf(sizeThin, sizeMedium, sizeThick)
        val sizeValues = listOf(1.5f, 3.5f, 7.0f) // in PDF points

        fun updateSelectedSize(selectedBtn: ImageButton) {
            for (s in sizes) {
                s.setBackgroundResource(if (s == selectedBtn) R.drawable.bg_color_dot_selected else 0)
            }
        }

        for (i in sizes.indices) {
            val btn = sizes[i]
            val value = sizeValues[i]
            btn.setOnClickListener {
                inkDrawingView?.penSize = value
                inkDrawingView?.isEraserMode = false
                btnEraser.isSelected = false
                btnEraser.setBackgroundResource(0)
                updateSelectedSize(btn)
            }
        }
        updateSelectedSize(sizeMedium)

        btnUndo.setOnClickListener {
            inkDrawingView?.undo()
        }

        btnEraser.setOnClickListener {
            val eraserOn = !(inkDrawingView?.isEraserMode ?: false)
            inkDrawingView?.isEraserMode = eraserOn
            btnEraser.isSelected = eraserOn
            btnEraser.setBackgroundResource(if (eraserOn) R.drawable.bg_color_dot_selected else 0)
            
            if (eraserOn) {
                for (c in colors) c.background = null
                for (s in sizes) s.background = null
            } else {
                updateSelectedColor(colorBlack)
                updateSelectedSize(sizeMedium)
                inkDrawingView?.penColor = colorValues[0]
                inkDrawingView?.penSize = sizeValues[1]
            }
        }

        btnDone.setOnClickListener {
            saveDrawingAndExit()
        }
    }

    private fun saveDrawingAndExit() {
        val strokes = inkDrawingView?.getStrokes() ?: run {
            exitDrawingMode()
            return
        }
        
        val page = getCurrentReaderPage()

        lifecycleScope.launch {
            val doc = pdfDocument ?: return@launch
            
            // Clear existing ink annotations on this page first
            doc.clearInkAnnotations(page)

            if (strokes.isEmpty()) {
                // If user erased everything, just save and exit
                doc.saveDocument()
                if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                    bookView?.refreshAllPages()
                } else {
                    verticalView?.refreshAllPages()
                }
                exitDrawingMode()
                return@launch
            }

            // Group strokes by color and width to write them
            val groups = strokes.groupBy { Pair(it.color, it.width) }
            var allOk = true
            for ((key, strokeGroup) in groups) {
                val color = key.first
                val width = key.second
                val totalPointsCount = strokeGroup.sumOf { it.points.size }
                if (totalPointsCount == 0) continue

                val pointsArray = FloatArray(totalPointsCount * 2)
                val strokeLengthsArray = IntArray(strokeGroup.size)

                var pIdx = 0
                for (sIdx in strokeGroup.indices) {
                    val stroke = strokeGroup[sIdx]
                    strokeLengthsArray[sIdx] = stroke.points.size
                    for (pt in stroke.points) {
                        pointsArray[pIdx * 2] = pt.x
                        pointsArray[pIdx * 2 + 1] = pt.y
                        pIdx++
                    }
                }

                val ok = doc.addInkAnnotation(
                    pageNumber = page,
                    points = pointsArray,
                    strokeLengths = strokeLengthsArray,
                    color = color,
                    opacity = 1.0f,
                    strokeWidth = width
                )
                if (!ok) {
                    allOk = false
                }
            }

            if (allOk) {
                doc.saveDocument()
                if (currentMode == SettingsStore.DISPLAY_MODE_BOOK) {
                    bookView?.refreshAllPages()
                } else {
                    verticalView?.refreshAllPages()
                }
            }
            exitDrawingMode()
        }
    }

    private fun parseStrokes(flatData: FloatArray): List<InkDrawingView.Stroke> {
        val list = mutableListOf<InkDrawingView.Stroke>()
        var idx = 0
        while (idx < flatData.size) {
            val count = flatData[idx].toInt()
            val r = flatData[idx + 1]
            val g = flatData[idx + 2]
            val b = flatData[idx + 3]
            val width = flatData[idx + 4]
            idx += 5

            val color = Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
            val stroke = InkDrawingView.Stroke(color = color, width = width)
            for (p in 0 until count) {
                if (idx + 1 < flatData.size) {
                    val x = flatData[idx]
                    val y = flatData[idx + 1]
                    stroke.points.add(android.graphics.PointF(x, y))
                    idx += 2
                }
            }
            list.add(stroke)
        }
        return list
    }
}


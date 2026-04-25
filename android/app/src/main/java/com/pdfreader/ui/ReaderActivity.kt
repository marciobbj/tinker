package com.pdfreader.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pdfreader.R
import com.pdfreader.core.PdfDocument
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
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnMode: ImageButton
    private lateinit var btnDark: ImageButton
    private lateinit var btnBookmark: ImageButton

    private var pdfDocument: PdfDocument? = null
    private var tempFile: File? = null
    private val bookmarkStore by lazy { BookmarkStore(this) }
    private val settingsStore by lazy { SettingsStore(this) }

    private var verticalView: PdfVerticalView? = null
    private var bookView: PdfBookView? = null
    private var currentMode = SettingsStore.DISPLAY_MODE_VERTICAL
    private var uiVisible = true
    private var documentTitle: String = ""
    private var documentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        container = findViewById(R.id.container)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        pageIndicator = findViewById(R.id.pageIndicator)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnMode = findViewById(R.id.btnMode)
        btnDark = findViewById(R.id.btnDark)
        btnBookmark = findViewById(R.id.btnBookmark)

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
            view.renderPage = { page, w, h, zoom, reused -> doc.renderPage(page, w, h, zoom, reused) }
            view.listener = object : PdfBookView.Listener {
                override fun onPageChanged(page: Int) = updatePageIndicator()
                override fun onPageTap() = onTap()
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
            view.listener = object : PdfVerticalView.Listener {
                override fun onPageVisible(page: Int) = updatePageIndicator(page)
                override fun onPageTap() = onTap()
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
                recreate()
            }
        }

        btnBookmark.setOnClickListener {
            if (!documentReady) return@setOnClickListener
            saveBookmark()
            AlertDialog.Builder(this)
                .setMessage("Posição salva com sucesso!")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toggleUi() {
        uiVisible = !uiVisible
        val vis = if (uiVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
    }

    private fun updatePageIndicator(page: Int = -1) {
        val pc = pdfDocument?.pageCount ?: 0
        val cp = if (page >= 0) page else (bookView?.currentPage ?: 0)
        pageIndicator.text = "Página ${cp + 1} / $pc"
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
                displayMode = currentMode
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
                try { file?.delete() } catch (_: Exception) {}
            }.start()
        }
    }
}

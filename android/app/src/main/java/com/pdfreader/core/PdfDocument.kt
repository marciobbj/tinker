package com.pdfreader.core

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfDocument(private val filePath: String) {
    private var handle: Long = 0L
    private var _pageCount: Int = 0
    private var _maxWidth: Float = 0f
    private var pageSizesCached: Boolean = false
    val pageCount: Int get() = _pageCount
    val maxWidth: Float get() = _maxWidth

    var isOpen: Boolean = false
        private set

    // Cached page sizes — avoids JNI calls during scroll/layout
    private val pageSizeCache = mutableMapOf<Int, Pair<Float, Float>>()

    companion object {
        // Single-threaded executor for ALL instances — MuPDF context without locks is NOT thread-safe.
        // We serialize all native calls through this single dispatcher.
        private val renderExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pdf-render").apply { isDaemon = true }
        }
        private val renderDispatcher = renderExecutor.asCoroutineDispatcher()
    }

    suspend fun open(precachePageSizes: Boolean = true): Boolean = withContext(renderDispatcher) {
        handle = PdfNative.nativeCreateEngine()
        if (handle == 0L) return@withContext false
        
        val ok = PdfNative.nativeOpenDocument(handle, filePath)
        if (ok) {
            _pageCount = PdfNative.nativeGetPageCount(handle)
            isOpen = true
            if (precachePageSizes) {
                cacheAllPageSizes()
                pageSizesCached = true
            } else {
                pageSizeCache.clear()
                _maxWidth = 0f
                pageSizesCached = false
            }
        }
        ok
    }

    private fun cacheAllPageSizes() {
        var maxW = 0f
        for (i in 0 until _pageCount) {
            val arr = PdfNative.nativeGetPageSize(handle, i)
            if (arr != null && arr.size >= 2) {
                pageSizeCache[i] = arr[0] to arr[1]
                if (arr[0] > maxW) maxW = arr[0]
            }
        }
        _maxWidth = maxW
    }

    fun close() {
        isOpen = false
        pageSizesCached = false
        // Close sequentially on the render thread to ensure no race conditions
        // with any currently executing renders for this or other documents.
        runBlocking {
            withContext(renderDispatcher) {
                if (handle != 0L) {
                    PdfNative.nativeCloseDocument(handle)
                    PdfNative.nativeDestroyEngine(handle)
                    handle = 0L
                }
            }
        }
        pageSizeCache.clear()
    }

    suspend fun setDarkMode(enabled: Boolean) = withContext(renderDispatcher) {
        if (handle != 0L) {
            PdfNative.nativeSetDarkMode(handle, enabled)
        }
    }

    /**
     * Returns cached page size — no JNI call, safe to call from UI thread.
     */
    fun getPageSize(pageNumber: Int): Pair<Float, Float>? {
        return pageSizeCache[pageNumber]
    }

    /**
     * Ensures all page sizes and max width are available for layout-dependent modes.
     */
    suspend fun ensurePageSizesCached() = withContext(renderDispatcher) {
        if (!isOpen || handle == 0L || pageSizesCached) return@withContext
        cacheAllPageSizes()
        pageSizesCached = true
    }

    /**
     * Render a page to a Bitmap. Runs on the single render thread.
     * Returns null if the document is closed or the page is invalid.
     */
    suspend fun renderPage(pageNumber: Int, width: Int, height: Int, zoom: Float = 1.0f, reusedBitmap: Bitmap? = null): Bitmap? {
        if (!isOpen || pageNumber < 0 || pageNumber >= _pageCount) return null
        return withContext(renderDispatcher) {
            if (!isOpen || handle == 0L) return@withContext null
            val bitmap = reusedBitmap?.takeIf { it.width == width && it.height == height && !it.isRecycled && it.isMutable }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val ok = PdfNative.nativeRenderPage(handle, pageNumber, bitmap, width, height, zoom)
            if (ok) {
                if (!isActive) {
                    // Coroutine was cancelled while JNI was processing. We must recycle to prevent OOM.
                    bitmap.recycle()
                    null
                } else {
                    bitmap
                }
            } else {
                bitmap.recycle()
                null
            }
        }
    }

    suspend fun getTitle(): String = withContext(renderDispatcher) {
        if (handle != 0L) PdfNative.nativeGetTitle(handle) else ""
    }
}

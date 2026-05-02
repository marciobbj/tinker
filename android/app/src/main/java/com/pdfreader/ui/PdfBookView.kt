package com.pdfreader.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.graphics.withSave
import com.pdfreader.core.PdfDocument
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class PdfBookView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(page: Int)
        fun onPageTap()
        fun onSelectionFinished(page: Int, quads: FloatArray, text: String)
    }

    var listener: Listener? = null
    var pageCount: Int = 0
        set(value) { field = value; invalidate() }
    var currentPage: Int = 0; private set

    var renderPage: (suspend (Int, Int, Int, Float, Bitmap?) -> Bitmap?)? = null
    var getPageSize: ((Int) -> Pair<Float, Float>?)? = null
    var getPageSizeAsync: (suspend (Int) -> Pair<Float, Float>?)? = null
    var getSelectionQuads: (suspend (Int, Float, Float, Float, Float, Int) -> FloatArray?)? = null
    var copySelectionText: (suspend (Int, Float, Float, Float, Float, Int) -> String)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPath = Path()
    private val holders = mutableMapOf<Int, PageHolder>()
    private val pageSizeOverrides = mutableMapOf<Int, Pair<Float, Float>>()
    private val renderJobs = mutableMapOf<Int, Job>()
    private val renderJobTargets = mutableMapOf<Int, Pair<Int, Int>>()
    private val renderJobTokens = mutableMapOf<Int, Int>()
    private val bitmapPool = mutableListOf<Bitmap>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var nextRenderToken = 1
    private var scroller = OverScroller(context)
    private var offsetX = 0f
    private var vw = 0; private var vh = 0
    private var animating = false
    private val forwardCacheRadius = 2
    private val backwardCacheRadius = 1
    private val minZoom = 1.0f
    private val maxZoom = 2.0f
    private var zoomFactor = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var pinchInProgress = false

    private var selectionActive = false
    private var selectionPage = -1
    private val selectionStart = PointF()
    private val selectionEnd = PointF()
    private var selectionQuads: FloatArray? = null
    private var selectionJob: Job? = null
    private val selectionMode = PdfDocument.SELECT_WORDS

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 247, 122, 12)
        style = Paint.Style.FILL
    }
    private val handleRadius = 10f
    private var handleRadiusPx = 0f

    // ── Crossfade state ──
    // When transitioning pages, we fade out the old page and fade in the new one.
    // This masks any remaining render latency so the user never sees a gray placeholder.
    private var crossfadeAlpha = 255          // 0..255 — alpha of the current (new) page
    private var crossfadeOldBitmap: Bitmap? = null
    private var crossfadeInProgress = false
    private var crossfadeStartTime = 0L
    private val crossfadeDurationMs = 150L    // keep short — just enough to mask render

    private val isDarkTheme: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val pageBackgroundColor: Int
        get() = if (isDarkTheme) Color.rgb(20, 20, 20) else Color.WHITE

    private val loadingPlaceholderColor: Int
        get() = if (isDarkTheme) Color.rgb(34, 34, 34) else Color.LTGRAY

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchInProgress = true
            scroller.forceFinished(true)
            animating = false
            if (selectionActive || selectionQuads != null) {
                clearSelection()
            }
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (vw <= 0 || vh <= 0) return false
            val oldZoom = zoomFactor
            val newZoom = (oldZoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
            if (kotlin.math.abs(newZoom - oldZoom) < 0.01f) return false

            val fx = detector.focusX - vw / 2f
            val fy = detector.focusY - vh / 2f
            val ratio = newZoom / oldZoom

            zoomFactor = newZoom
            panX = (panX + fx) * ratio - fx
            panY = (panY + fy) * ratio - fy

            if (!isZoomed()) {
                zoomFactor = 1.0f
                panX = 0f
                panY = 0f
            }

            clampPan()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinchInProgress = false
            prefetch(currentPage)
            invalidate()
        }
    })

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); animating = false; return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            if (selectionActive) {
                updateSelection(e2.x, e2.y)
                return true
            }
            if (isZoomed()) {
                panX -= dx
                panY -= dy
                clampPan()
            } else {
                offsetX -= dx
            }
            invalidate()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (isZoomed()) return true
            val t = if (vx > 300) currentPage - 1 else if (vx < -300) currentPage + 1 else currentPage
            animateTo(t.coerceIn(0, pageCount - 1)); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (selectionQuads != null) {
                clearSelection()
                return true
            }
            if (isZoomed()) {
                listener?.onPageTap()
                return true
            }
            val x = e.x
            when { x < width * 0.25f -> goToPage(currentPage - 1); x > width * 0.75f -> goToPage(currentPage + 1); else -> listener?.onPageTap() }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            startSelection(e.x, e.y)
        }
    })

    init {
        setBackgroundColor(pageBackgroundColor)
        selectionPaint.style = Paint.Style.FILL
        handleRadiusPx = handleRadius * resources.displayMetrics.density
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        vw = w
        vh = h
        offsetX = 0f
        panX = 0f
        panY = 0f
        recycleAll()
        if (w > 0 && h > 0) {
            prefetch(currentPage)
            requestPageSize(currentPage)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (vw <= 0 || vh <= 0 || pageCount <= 0) return

        // ── Update scroller FIRST so pages are drawn at the correct position ──
        // Previously the offset was updated AFTER drawing, causing a 1-frame desync
        // that flashed the background on the trailing edge.
        if (scroller.computeScrollOffset()) {
            offsetX = scroller.currX.toFloat()
            // Clamp to prevent overshoot beyond page boundaries.
            // OverScroller can slightly exceed the target, creating an uncovered
            // strip (the black gap visible on the right edge).
            offsetX = offsetX.coerceIn(-vw.toFloat(), vw.toFloat())
            if (scroller.isFinished) {
                finishTransition()
            } else {
                postInvalidateOnAnimation()
            }
        }

        val renderW = (vw * zoomFactor).roundToInt().coerceAtLeast(1)
        val renderH = (vh * zoomFactor).roundToInt().coerceAtLeast(1)
        updateSelectionPaint()

        // ── Crossfade: draw old page underneath if active ──
        if (crossfadeInProgress && crossfadeOldBitmap != null && !crossfadeOldBitmap!!.isRecycled) {
            val elapsed = System.currentTimeMillis() - crossfadeStartTime
            val progress = (elapsed.toFloat() / crossfadeDurationMs).coerceIn(0f, 1f)
            crossfadeAlpha = (progress * 255).roundToInt()

            // Draw old page fading out
            paint.alpha = 255 - crossfadeAlpha
            val oldBmp = crossfadeOldBitmap!!
            canvas.withSave {
                drawBitmap(oldBmp, Rect(0, 0, oldBmp.width, oldBmp.height), Rect(0, 0, vw, vh), paint)
            }
            paint.alpha = 255

            if (progress >= 1f) {
                // Crossfade complete — recycle old bitmap
                crossfadeInProgress = false
                recycleBitmap(crossfadeOldBitmap)
                crossfadeOldBitmap = null
                crossfadeAlpha = 255
            } else {
                // Keep animating
                postInvalidateOnAnimation()
            }
        }

        // ── Draw current page(s) with current alpha ──
        val drawAlpha = if (crossfadeInProgress) crossfadeAlpha else 255

        if (isZoomed()) {
            val x = ((vw - renderW) / 2f + panX).roundToInt()
            val y = ((vh - renderH) / 2f + panY).roundToInt()
            drawPage(canvas, currentPage, x, y, renderW, renderH, drawAlpha)
            drawSelectionOverlay(canvas, currentPage, x, y, renderW, renderH)
        } else {
            val ox = offsetX.toInt()
            // Always draw all adjacent pages during scroll to guarantee full
            // pixel coverage — prevents any background from bleeding through.
            if (currentPage > 0) drawPage(canvas, currentPage - 1, -vw + ox, 0, vw, vh, drawAlpha)
            drawPage(canvas, currentPage, ox, 0, vw, vh, drawAlpha)
            drawSelectionOverlay(canvas, currentPage, ox, 0, renderW, renderH)
            if (currentPage < pageCount - 1) drawPage(canvas, currentPage + 1, vw + ox, 0, vw, vh, drawAlpha)
        }
    }

    private fun isZoomed(): Boolean = zoomFactor > 1.01f

    private fun clampPan() {
        if (vw <= 0 || vh <= 0) {
            panX = 0f
            panY = 0f
            return
        }
        val renderW = vw * zoomFactor
        val renderH = vh * zoomFactor
        val maxPanX = ((renderW - vw) / 2f).coerceAtLeast(0f)
        val maxPanY = ((renderH - vh) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun cancelRenderJobs() {
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        renderJobTargets.clear()
        renderJobTokens.clear()
    }

    private fun getReusableBitmap(w: Int, h: Int): Bitmap? {
        val i = bitmapPool.indexOfFirst { it.width == w && it.height == h && !it.isRecycled }
        return if (i >= 0) bitmapPool.removeAt(i) else null
    }

    private fun recycleBitmap(bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        if (bitmapPool.size < 5) {
            bitmapPool.add(bmp)
        } else {
            bmp.recycle()
        }
    }

    private fun drawPage(canvas: Canvas, page: Int, x: Int, y: Int, w: Int, h: Int, alpha: Int = 255) {
        val holder = holders.getOrPut(page) { PageHolder(w, h) }
        if (holder.w != w || holder.h != h) {
            if (holder.bitmap != null || !pinchInProgress) {
                holder.needsRerender = true
            }
            holder.w = w; holder.h = h
        }

        // Always draw a solid page-colored rect first. This is critical to prevent
        // any gap from showing the parent's black background, regardless of
        // whether a bitmap is ready or crossfade is active.
        paint.color = pageBackgroundColor
        paint.alpha = alpha
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)

        val bmp = holder.bitmap?.takeUnless { it.isRecycled }
        if (bmp != null) {
            paint.alpha = alpha
            canvas.withSave { clipRect(x, y, x + w, y + h); drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), Rect(x, y, x + w, y + h), paint) }
            paint.alpha = 255
        } else if (!crossfadeInProgress) {
            // No bitmap and no crossfade — tint with placeholder color on top of page bg
            paint.color = loadingPlaceholderColor
            paint.alpha = alpha
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
            paint.alpha = 255
        }
        // When crossfade IS active and bitmap not ready, the page background rect
        // above still covers this region (same color as view bg = invisible),
        // and the crossfade old-bitmap on top provides visual continuity.

        if (!pinchInProgress && (holder.needsRerender || bmp == null)) {
            requestRender(page, w, h)
        }
    }

    /**
     * Forces all pages to be rerendered during the next draw cycle.
     * Useful when global display settings change (e.g. theme switch).
     */
    fun refreshAllPages() {
        holders.forEach { (page, _) ->
            holders[page]?.needsRerender = true
        }
        invalidate()
    }

    private fun updateSelectionPaint() {
        selectionPaint.color = if (isDarkTheme) Color.argb(120, 255, 214, 102) else Color.argb(90, 255, 214, 102)
    }

    private fun drawSelectionOverlay(canvas: Canvas, page: Int, pageX: Int, pageY: Int, renderW: Int, renderH: Int) {
        val quads = selectionQuads ?: return
        if (selectionPage != page) return
        val size = getPageSize?.invoke(page) ?: pageSizeOverrides[page] ?: return

        val scale = minOf(renderW / size.first, renderH / size.second)
        if (scale <= 0f) return
        val drawnW = size.first * scale
        val drawnH = size.second * scale
        val padX = (renderW - drawnW) / 2f
        val padY = (renderH - drawnH) / 2f

        val baseX = pageX.toFloat() + padX
        val baseY = pageY.toFloat() + padY
        val count = quads.size / 8
        var firstLeft = Float.MAX_VALUE
        var firstTop = Float.MAX_VALUE
        var lastRight = Float.MIN_VALUE
        var lastBottom = Float.MIN_VALUE
        for (i in 0 until count) {
            val o = i * 8
            val ulx = baseX + quads[o + 0] * scale
            val uly = baseY + quads[o + 1] * scale
            val urx = baseX + quads[o + 2] * scale
            val ury = baseY + quads[o + 3] * scale
            val llx = baseX + quads[o + 4] * scale
            val lly = baseY + quads[o + 5] * scale
            val lrx = baseX + quads[o + 6] * scale
            val lry = baseY + quads[o + 7] * scale

            selectionPath.reset()
            selectionPath.moveTo(ulx, uly)
            selectionPath.lineTo(urx, ury)
            selectionPath.lineTo(lrx, lry)
            selectionPath.lineTo(llx, lly)
            selectionPath.close()
            canvas.drawPath(selectionPath, selectionPaint)

            if (i == 0) { firstLeft = ulx; firstTop = uly }
            if (i == count - 1) { lastRight = lrx; lastBottom = lry }
        }
        if (count > 0) {
            canvas.drawCircle(firstLeft, firstTop - handleRadiusPx, handleRadiusPx, handlePaint)
            canvas.drawCircle(lastRight, lastBottom + handleRadiusPx, handleRadiusPx, handlePaint)
        }
    }

    private data class PagePoint(val page: Int, val x: Float, val y: Float)

    private fun startSelection(x: Float, y: Float) {
        if (pinchInProgress || scaleDetector.isInProgress) return
        if (getSelectionQuads == null) return
        if (selectionActive || selectionQuads != null) clearSelection()
        val point = mapViewPointToPage(x, y) ?: return
        selectionActive = true
        selectionPage = point.page
        selectionStart.set(point.x, point.y)
        selectionEnd.set(point.x, point.y)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updateSelectionQuads()
        invalidate()
    }

    private fun updateSelection(x: Float, y: Float) {
        if (!selectionActive) return
        val point = mapViewPointToPage(x, y) ?: return
        if (point.page != selectionPage) return
        selectionEnd.set(point.x, point.y)
        updateSelectionQuads()
    }

    private fun updateSelectionQuads() {
        val page = selectionPage
        if (page < 0) return
        val fetch = getSelectionQuads ?: return
        val start = PointF(selectionStart.x, selectionStart.y)
        val end = PointF(selectionEnd.x, selectionEnd.y)
        selectionJob?.cancel()
        selectionJob = scope.launch {
            val quads = fetch(page, start.x, start.y, end.x, end.y, selectionMode)
            selectionQuads = quads?.takeIf { it.isNotEmpty() }
            invalidate()
        }
    }

    private fun finishSelection() {
        val page = selectionPage
        if (page < 0) {
            clearSelection()
            return
        }
        val fetch = getSelectionQuads
        if (fetch == null) {
            clearSelection()
            return
        }
        val start = PointF(selectionStart.x, selectionStart.y)
        val end = PointF(selectionEnd.x, selectionEnd.y)
        selectionActive = false
        selectionJob?.cancel()
        selectionJob = scope.launch {
            val quads = fetch(page, start.x, start.y, end.x, end.y, selectionMode)
            if (quads == null || quads.isEmpty()) {
                clearSelection()
                return@launch
            }
            selectionQuads = quads
            val text = copySelectionText?.invoke(page, start.x, start.y, end.x, end.y, selectionMode) ?: ""
            listener?.onSelectionFinished(page, quads, text)
            invalidate()
        }
    }

    fun clearSelection() {
        selectionActive = false
        selectionPage = -1
        selectionQuads = null
        selectionJob?.cancel()
        invalidate()
    }

    fun getSelectionScreenBounds(): android.graphics.RectF? {
        val quads = selectionQuads ?: return null
        val page = selectionPage
        if (page < 0 || quads.isEmpty() || vw <= 0 || vh <= 0) return null
        val size = getPageSize?.invoke(page) ?: pageSizeOverrides[page] ?: return null
        val renderW = (vw * zoomFactor).roundToInt().coerceAtLeast(1)
        val renderH = (vh * zoomFactor).roundToInt().coerceAtLeast(1)
        val scale = minOf(renderW / size.first, renderH / size.second)
        if (scale <= 0f) return null
        val drawnW = size.first * scale
        val drawnH = size.second * scale
        val padX = (renderW - drawnW) / 2f
        val padY = (renderH - drawnH) / 2f
        val pageX = if (isZoomed()) ((vw - renderW) / 2f + panX) else offsetX
        val pageY = if (isZoomed()) ((vh - renderH) / 2f + panY) else 0f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        val count = quads.size / 8
        for (i in 0 until count) {
            val o = i * 8
            for (j in 0 until 4) {
                val sx = pageX + padX + quads[o + j * 2] * scale
                val sy = pageY + padY + quads[o + j * 2 + 1] * scale
                if (sx < minX) minX = sx; if (sx > maxX) maxX = sx
                if (sy < minY) minY = sy; if (sy > maxY) maxY = sy
            }
        }
        return android.graphics.RectF(minX, minY, maxX, maxY)
    }

    fun invalidatePage(page: Int) {
        holders[page]?.needsRerender = true
        invalidate()
    }

    private fun requestPageSize(page: Int) {
        if (pageSizeOverrides.containsKey(page)) return
        val fetch = getPageSizeAsync ?: return
        scope.launch {
            val size = fetch(page)
            if (size != null) {
                pageSizeOverrides[page] = size
                invalidate()
            }
        }
    }

    private fun mapViewPointToPage(x: Float, y: Float): PagePoint? {
        if (vw <= 0 || vh <= 0 || pageCount <= 0) return null
        val page = currentPage
        val size = getPageSize?.invoke(page) ?: pageSizeOverrides[page] ?: run {
            requestPageSize(page)
            return null
        }

        val renderW = (vw * zoomFactor).roundToInt().coerceAtLeast(1)
        val renderH = (vh * zoomFactor).roundToInt().coerceAtLeast(1)
        val scale = minOf(renderW / size.first, renderH / size.second)
        if (scale <= 0f) return null
        val drawnW = size.first * scale
        val drawnH = size.second * scale
        val padX = (renderW - drawnW) / 2f
        val padY = (renderH - drawnH) / 2f

        val pageX = if (isZoomed()) ((vw - renderW) / 2f + panX) else offsetX
        val pageY = if (isZoomed()) ((vh - renderH) / 2f + panY) else 0f

        if (!isZoomed() && kotlin.math.abs(offsetX) > 1f) return null

        val localX = x - pageX
        val localY = y - pageY
        if (localX < padX || localX > padX + drawnW || localY < padY || localY > padY + drawnH) return null

        val pageXPt = (localX - padX) / scale
        val pageYPt = (localY - padY) / scale
        return PagePoint(page, pageXPt, pageYPt)
    }

    /**
     * Request a render for a page. If urgent=true, cancel all other pending
     * render jobs first so this page gets the render thread immediately.
     */
    private fun requestRender(page: Int, w: Int, h: Int, urgent: Boolean = false) {
        if (page < 0 || page >= pageCount) return

        if (urgent) {
            // Cancel all non-matching render jobs so the urgent page gets priority
            val toCancel = renderJobs.keys.filter { it != page }
            toCancel.forEach { key ->
                renderJobs.remove(key)?.cancel()
                renderJobTargets.remove(key)
                renderJobTokens.remove(key)
            }
        }

        val runningJob = renderJobs[page]
        val runningTarget = renderJobTargets[page]
        if (runningJob != null) {
            if (runningTarget != null && runningTarget.first == w && runningTarget.second == h) return
            runningJob.cancel()
            renderJobs.remove(page)
            renderJobTargets.remove(page)
            renderJobTokens.remove(page)
        }

        val token = nextRenderToken++
        renderJobTargets[page] = w to h
        renderJobTokens[page] = token
        val reused = getReusableBitmap(w, h)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val bmp = renderPage?.invoke(page, w, h, 0f, reused)
                if (bmp != null && !bmp.isRecycled) {
                    val hld = holders[page]
                    if (hld != null && hld.w == w && hld.h == h && renderJobTokens[page] == token) {
                        val oldBitmap = hld.bitmap
                        hld.bitmap = bmp
                        hld.needsRerender = false
                        recycleBitmap(oldBitmap)
                    }
                    else recycleBitmap(bmp)
                }
            } finally { 
                if (renderJobTokens[page] == token) {
                    renderJobs.remove(page)
                    renderJobTargets.remove(page)
                    renderJobTokens.remove(page)
                }
                invalidate() 
            }
        }
        renderJobs[page] = job
        job.start()
    }

    private fun prefetch(center: Int) {
        if (vw <= 0 || vh <= 0) return
        val renderW = (vw * zoomFactor).roundToInt().coerceAtLeast(1)
        val renderH = (vh * zoomFactor).roundToInt().coerceAtLeast(1)

        // Render the current page with highest priority
        requestRender(center, renderW, renderH, urgent = true)

        if (!isZoomed()) {
            // Prioritize forward reading so the next page is usually ready before swipe ends.
            for (d in 1..forwardCacheRadius) {
                val next = center + d
                if (next < pageCount) requestRender(next, renderW, renderH)
            }

            // Keep one page behind for quick back navigation.
            for (d in 1..backwardCacheRadius) {
                val prev = center - d
                if (prev >= 0) requestRender(prev, renderW, renderH)
            }
        }
        evict()
    }

    private fun evict() {
        val lo: Int
        val hi: Int
        if (isZoomed()) {
            lo = currentPage
            hi = currentPage
        } else {
            lo = (currentPage - backwardCacheRadius).coerceAtLeast(0)
            hi = (currentPage + forwardCacheRadius).coerceAtMost(pageCount - 1)
        }
        holders.keys.filter { it < lo || it > hi }.forEach { key ->
            recycleBitmap(holders.remove(key)?.bitmap)
        }
        renderJobs.keys.filter { it < lo || it > hi }.forEach { key ->
            renderJobs.remove(key)?.cancel()
            renderJobTargets.remove(key)
            renderJobTokens.remove(key)
        }
    }

    private fun recycleAll() {
        holders.values.forEach { it.bitmap?.recycle() }
        holders.clear()
        bitmapPool.forEach { it.recycle() }
        bitmapPool.clear()
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        renderJobTargets.clear()
        renderJobTokens.clear()
    }

    private fun animateTo(page: Int) {
        animating = true
        val target = when { page < currentPage -> vw.toFloat(); page > currentPage -> -vw.toFloat(); else -> 0f }

        // Pre-request the target page urgently so it starts rendering NOW,
        // while the scroll animation is still playing (250ms of free time).
        if (page != currentPage && page in 0 until pageCount) {
            requestRender(page, vw, vh, urgent = true)
        }

        scroller.startScroll(offsetX.toInt(), 0, (target - offsetX).toInt(), 0, 250); invalidate()
    }

    private fun finishTransition() {
        animating = false
        val oldPage = currentPage
        if (offsetX > vw / 2 && currentPage > 0) currentPage--
        else if (offsetX < -vw / 2 && currentPage < pageCount - 1) currentPage++

        // Start crossfade BEFORE prefetch/evict — we need the old holder's bitmap
        // to still be alive when startCrossfade() copies it.
        if (oldPage != currentPage) {
            startCrossfade(oldPage)
        }

        offsetX = 0f; listener?.onPageChanged(currentPage)
        // prefetch calls evict() which may recycle the old page holder,
        // but that's fine — crossfade already copied the bitmap above.
        prefetch(currentPage)
        requestPageSize(currentPage)
        invalidate()
    }

    /**
     * Start a crossfade transition from the old page to the new one.
     * Captures the old page's bitmap (or creates a snapshot) so we can
     * blend it out while the new page fades in.
     */
    private fun startCrossfade(oldPage: Int) {
        // Recycle any previous crossfade bitmap
        recycleBitmap(crossfadeOldBitmap)
        crossfadeOldBitmap = null

        val oldHolder = holders[oldPage]
        val oldBmp = oldHolder?.bitmap?.takeUnless { it.isRecycled }
        if (oldBmp != null) {
            // If the new page already has a bitmap ready, skip crossfade entirely
            val newHolder = holders[currentPage]
            val newBmp = newHolder?.bitmap?.takeUnless { it.isRecycled }
            if (newBmp != null) {
                // New page is already rendered — no need to crossfade
                return
            }

            // Copy the old bitmap for crossfade (can't hold reference since holder may recycle it)
            crossfadeOldBitmap = oldBmp.copy(Bitmap.Config.ARGB_8888, false)
            crossfadeInProgress = true
            crossfadeStartTime = System.currentTimeMillis()
            crossfadeAlpha = 0
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // When selection is active, handle MOVE directly — GestureDetector
        // does NOT forward onScroll events after onLongPress.
        if (selectionActive) {
            when (e.action) {
                MotionEvent.ACTION_MOVE -> {
                    updateSelection(e.x, e.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    finishSelection()
                    return true
                }
            }
        }

        val scaleHandled = scaleDetector.onTouchEvent(e)
        val handled = if (!scaleDetector.isInProgress) detector.onTouchEvent(e) else true
        if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
            if (!animating && !pinchInProgress && !isZoomed()) {
                val t = vw * 0.2f
                when {
                    offsetX > t -> animateTo(currentPage - 1)
                    offsetX < -t -> animateTo(currentPage + 1)
                    else -> animateTo(currentPage)
                }
            }
        }
        return scaleHandled || handled || super.onTouchEvent(e)
    }

    fun goToPage(p: Int) {
        val t = p.coerceIn(0, pageCount - 1)
        if (t == currentPage) return
        if (isZoomed()) setPage(t) else animateTo(t)
    }

    fun setPage(p: Int) {
        val oldPage = currentPage
        currentPage = p.coerceIn(0, pageCount - 1)
        offsetX = 0f
        panX = 0f
        panY = 0f

        // Start crossfade if there was a page change and we have old content
        if (oldPage != currentPage) {
            startCrossfade(oldPage)
        }

        // Recycle all holders except the crossfade bitmap (already copied)
        recycleAll()
        listener?.onPageChanged(currentPage)
        prefetch(currentPage)
        requestPageSize(currentPage)
        invalidate()
    }

    fun cleanup() {
        clearSelection()
        scope.cancel()
        recycleAll()
        recycleBitmap(crossfadeOldBitmap)
        crossfadeOldBitmap = null
        pageSizeOverrides.clear()
    }

    private class PageHolder(var w: Int, var h: Int) {
        var bitmap: Bitmap? = null
        var needsRerender: Boolean = false
    }
}

package com.pdfreader.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.graphics.withSave
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class PdfBookView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener { fun onPageChanged(page: Int); fun onPageTap() }

    var listener: Listener? = null
    var pageCount: Int = 0
        set(value) { field = value; invalidate() }
    var currentPage: Int = 0; private set

    var renderPage: (suspend (Int, Int, Int, Float, Bitmap?) -> Bitmap?)? = null
    var getPageSize: ((Int) -> Pair<Float, Float>?)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holders = mutableMapOf<Int, PageHolder>()
    private val renderJobs = mutableMapOf<Int, Job>()
    private val bitmapPool = mutableListOf<Bitmap>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
            if (isZoomed()) {
                listener?.onPageTap()
                return true
            }
            val x = e.x
            when { x < width * 0.25f -> goToPage(currentPage - 1); x > width * 0.75f -> goToPage(currentPage + 1); else -> listener?.onPageTap() }
            return true
        }
    })

    init { setBackgroundColor(pageBackgroundColor) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        vw = w
        vh = h
        offsetX = 0f
        panX = 0f
        panY = 0f
        recycleAll()
        if (w > 0 && h > 0) prefetch(currentPage)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (vw <= 0 || vh <= 0 || pageCount <= 0) return
        val renderW = (vw * zoomFactor).roundToInt().coerceAtLeast(1)
        val renderH = (vh * zoomFactor).roundToInt().coerceAtLeast(1)

        if (isZoomed()) {
            val x = ((vw - renderW) / 2f + panX).roundToInt()
            val y = ((vh - renderH) / 2f + panY).roundToInt()
            drawPage(canvas, currentPage, x, y, renderW, renderH)
        } else {
            if (offsetX > 0 && currentPage > 0) drawPage(canvas, currentPage - 1, (-vw + offsetX).toInt(), 0, vw, vh)
            drawPage(canvas, currentPage, offsetX.toInt(), 0, vw, vh)
            if (offsetX < 0 && currentPage < pageCount - 1) drawPage(canvas, currentPage + 1, (vw + offsetX).toInt(), 0, vw, vh)
        }

        if (scroller.computeScrollOffset()) {
            offsetX = scroller.currX.toFloat(); postInvalidateOnAnimation()
            if (scroller.isFinished) finishTransition()
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

    private fun drawPage(canvas: Canvas, page: Int, x: Int, y: Int, w: Int, h: Int) {
        val holder = holders.getOrPut(page) { PageHolder(w, h) }
        if (holder.w != w || holder.h != h) {
            if (!pinchInProgress) {
                recycleBitmap(holder.bitmap)
                holder.bitmap = null
                holder.needsRerender = false
            } else if (holder.bitmap != null) {
                // Keep old bitmap as a temporary preview during pinch,
                // then force a proper rerender when pinch ends.
                holder.needsRerender = true
            }
            holder.w = w; holder.h = h
        }

        if (!pinchInProgress && holder.needsRerender) {
            recycleBitmap(holder.bitmap)
            holder.bitmap = null
            holder.needsRerender = false
        }

        paint.color = pageBackgroundColor
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
        val bmp = holder.bitmap?.takeUnless { it.isRecycled }
        if (bmp != null) {
            canvas.withSave { clipRect(x, y, x + w, y + h); drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), Rect(x, y, x + w, y + h), paint) }
        } else {
            paint.color = loadingPlaceholderColor; canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
            if (!pinchInProgress) {
                requestRender(page, w, h)
            }
        }
    }

    private fun requestRender(page: Int, w: Int, h: Int) {
        if (page < 0 || page >= pageCount || renderJobs.containsKey(page)) return
        val reused = getReusableBitmap(w, h)
        renderJobs[page] = scope.launch {
            try {
                val bmp = renderPage?.invoke(page, w, h, 0f, reused)
                if (bmp != null && !bmp.isRecycled) {
                    val hld = holders[page]
                    if (hld != null && hld.w == w && hld.h == h) {
                        recycleBitmap(hld.bitmap)
                        hld.bitmap = bmp
                        hld.needsRerender = false
                    }
                    else recycleBitmap(bmp)
                }
            } finally { 
                renderJobs.remove(page)
                invalidate() 
            }
        }
    }

    private fun prefetch(center: Int) {
        if (vw <= 0 || vh <= 0) return
        val renderW = (vw * zoomFactor).roundToInt().coerceAtLeast(1)
        val renderH = (vh * zoomFactor).roundToInt().coerceAtLeast(1)
        requestRender(center, renderW, renderH)

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
        }
    }

    private fun recycleAll() {
        holders.values.forEach { it.bitmap?.recycle() }
        holders.clear()
        bitmapPool.forEach { it.recycle() }
        bitmapPool.clear()
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
    }

    private fun animateTo(page: Int) {
        animating = true
        val target = when { page < currentPage -> vw.toFloat(); page > currentPage -> -vw.toFloat(); else -> 0f }
        scroller.startScroll(offsetX.toInt(), 0, (target - offsetX).toInt(), 0, 250); invalidate()
    }

    private fun finishTransition() {
        animating = false
        if (offsetX > vw / 2 && currentPage > 0) currentPage--
        else if (offsetX < -vw / 2 && currentPage < pageCount - 1) currentPage++
        offsetX = 0f; listener?.onPageChanged(currentPage); prefetch(currentPage); invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
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
        currentPage = p.coerceIn(0, pageCount - 1)
        offsetX = 0f
        panX = 0f
        panY = 0f
        recycleAll()
        listener?.onPageChanged(currentPage)
        prefetch(currentPage)
        invalidate()
    }
    fun cleanup() { scope.cancel(); recycleAll() }

    private class PageHolder(var w: Int, var h: Int) {
        var bitmap: Bitmap? = null
        var needsRerender: Boolean = false
    }
}

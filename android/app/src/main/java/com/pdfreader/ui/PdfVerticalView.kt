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
import android.view.ViewGroup
import android.widget.OverScroller
import androidx.core.graphics.withSave
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class PdfVerticalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    interface Listener { fun onPageVisible(page: Int); fun onPageTap() }

    var listener: Listener? = null
    var pageCount: Int = 0
        set(value) { field = value; if (width > 0) rebuildLayout(width); requestLayout() }
    var maxPageWidth: Float = 0f
        set(value) { field = value; if (width > 0) rebuildLayout(width); requestLayout() }

    var renderPage: (suspend (Int, Int, Int, Float, Bitmap?) -> Bitmap?)? = null
    var getPageSize: ((Int) -> Pair<Float, Float>?)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holders = mutableMapOf<Int, PageHolder>()
    private val renderJobs = mutableMapOf<Int, Job>()
    private val bitmapPool = mutableListOf<Bitmap>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scroller = OverScroller(context)
    private var maxScrollY = 0
    private val gap = 16
    private val pageBuffer = 1
    private var pageOffsets = IntArray(0)
    private var pageWidths = IntArray(0)
    private var pageHeights = IntArray(0)
    private var contentMaxWidth = 0
    private val minZoom = 1.0f
    private val maxZoom = 2.0f
    private var zoomFactor = 1.0f
    private var panX = 0f
    private var pinchInProgress = false

    // Track last visible range to avoid evicting every frame
    private var lastEvictFirst = -1
    private var lastEvictLast = -1

    private val isDarkTheme: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val pageBackgroundColor: Int
        get() = if (isDarkTheme) Color.rgb(20, 20, 20) else Color.WHITE

    private val loadingPlaceholderColor: Int
        get() = if (isDarkTheme) Color.rgb(34, 34, 34) else Color.LTGRAY

    private fun isZoomed(): Boolean = zoomFactor > 1.01f

    private fun clampPanX() {
        val vw = width
        if (vw <= 0) {
            panX = 0f
            return
        }
        val maxPan = ((contentMaxWidth - vw) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxPan, maxPan)
    }

    private fun cancelRenderJobs() {
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchInProgress = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (width <= 0 || height <= 0 || pageCount <= 0) return false

            val oldZoom = zoomFactor
            val newZoom = (oldZoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
            if (kotlin.math.abs(newZoom - oldZoom) < 0.01f) return false

            val oldOffsets = pageOffsets
            val oldHeights = pageHeights
            val anchor = getCurrentPage().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val oldTop = if (anchor < oldOffsets.size) oldOffsets[anchor] else scrollY
            val oldHeight = if (anchor < oldHeights.size) oldHeights[anchor].coerceAtLeast(1) else 1
            val ratioWithinPage = (scrollY - oldTop).toFloat() / oldHeight.toFloat()

            zoomFactor = newZoom
            if (!isZoomed()) {
                zoomFactor = 1.0f
                panX = 0f
            }

            rebuildLayout(width, height)

            if (pageOffsets.isNotEmpty()) {
                val idx = anchor.coerceIn(0, pageOffsets.size - 1)
                val newTop = pageOffsets[idx]
                val newHeight = pageHeights[idx].coerceAtLeast(1)
                val newY = (newTop + ratioWithinPage * newHeight).roundToInt()
                scrollTo(0, newY)
            }

            clampPanX()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinchInProgress = false
            invalidate()
        }
    })

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            if (isZoomed()) {
                panX -= dx
                clampPanX()
            }
            scrollBy(0, dy.toInt()); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollY, 0, -vy.toInt(), 0, 0, 0, maxScrollY)
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { listener?.onPageTap(); return true }
    })

    init { setWillNotDraw(false); setBackgroundColor(pageBackgroundColor) }

    override fun onMeasure(wSpec: Int, hSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        recycleAll(); rebuildLayout(w, h)
    }

    private fun rebuildLayout(viewW: Int, viewH: Int = height) {
        if (viewW <= 0 || pageCount <= 0) {
            pageOffsets = IntArray(0)
            pageWidths = IntArray(0)
            pageHeights = IntArray(0)
            contentMaxWidth = 0
            maxScrollY = 0
            return
        }
        pageOffsets = IntArray(pageCount)
        pageWidths = IntArray(pageCount)
        pageHeights = IntArray(pageCount)
        var y = 0
        val fitZoom = if (maxPageWidth > 0) viewW.toFloat() / maxPageWidth else 1.0f
        val zoom = fitZoom * zoomFactor
        var widest = 0
        for (i in 0 until pageCount) {
            pageOffsets[i] = y
            val size = getPageSize?.invoke(i)
            val pw = if (size != null) {
                (size.first * zoom).toInt().coerceAtLeast(1)
            } else {
                (viewW * zoomFactor).roundToInt().coerceAtLeast(1)
            }
            val ph = if (size != null) (size.second * zoom).toInt().coerceAtLeast(1) else viewH.coerceAtLeast(1)
            pageWidths[i] = pw
            pageHeights[i] = ph
            widest = maxOf(widest, pw)
            y += ph + gap
        }
        contentMaxWidth = widest
        clampPanX()
        maxScrollY = (y - viewH).coerceAtLeast(0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || pageOffsets.isEmpty()) return
        val vw = width; val vh = height; val sy = scrollY
        val zoom = (if (maxPageWidth > 0) vw.toFloat() / maxPageWidth else 1.0f) * zoomFactor
        var firstVis = -1; var lastVis = -1
        val start = findFirst(sy)
        for (i in start until pageCount) {
            val pageTop = pageOffsets[i]
            val ph = pageHeights[i]
            if (pageTop > sy + vh) break
            if (pageTop + ph >= sy) {
                if (firstVis < 0) firstVis = i; lastVis = i
                val size = getPageSize?.invoke(i)
                val pw = if (size != null) (size.first * zoom).toInt().coerceAtLeast(1) else pageWidths.getOrNull(i)?.coerceAtLeast(1) ?: vw
                val px = ((vw - pw) / 2f + panX).roundToInt()
                drawPage(canvas, i, px, pageTop, pw, ph)
            }
        }
        if (firstVis >= 0) {
            listener?.onPageVisible(firstVis)
            // Only evict and prefetch when visible range actually changes
            if (firstVis != lastEvictFirst || lastVis != lastEvictLast) {
                lastEvictFirst = firstVis
                lastEvictLast = lastVis
                evict(firstVis, lastVis)
                if (!isZoomed()) {
                    prefetch(firstVis, lastVis, vw, zoom)
                }
            }
        }
        if (scroller.computeScrollOffset()) { scrollTo(0, scroller.currY); postInvalidateOnAnimation() }
    }

    private fun findFirst(sy: Int): Int {
        var lo = 0; var hi = pageCount - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (pageOffsets[m] + pageHeights[m] < sy) lo = m + 1 else hi = m }; return lo
    }

    private fun getReusableBitmap(w: Int, h: Int): Bitmap? {
        val i = bitmapPool.indexOfFirst { it.width == w && it.height == h && !it.isRecycled }
        return if (i >= 0) bitmapPool.removeAt(i) else null
    }

    private fun recycleBitmap(bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        if (bitmapPool.size < 6) {
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
                // Keep temporary preview during pinch and rerender after gesture ends.
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
            canvas.withSave {
                clipRect(x, y, x + w, y + h)
                drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), Rect(x, y, x + w, y + h), paint)
            }
        } else {
            paint.color = loadingPlaceholderColor
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
            if (!pinchInProgress) {
                requestRender(page, w, h)
            }
        }
    }

    private fun requestRender(page: Int, w: Int, h: Int) {
        if (renderJobs.containsKey(page)) return
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
                    } else {
                        recycleBitmap(bmp)
                    }
                }
            } finally { 
                renderJobs.remove(page)
                invalidate() 
            }
        }
    }

    private fun prefetch(firstVis: Int, lastVis: Int, vw: Int, zoom: Float) {
        val lo = (firstVis - 1).coerceAtLeast(0)
        val hi = (lastVis + 1).coerceAtMost(pageCount - 1)
        for (i in lo..hi) {
            if (i < firstVis || i > lastVis) {
                val ph = pageHeights[i]
                val size = getPageSize?.invoke(i)
                val pw = if (size != null) (size.first * zoom).toInt().coerceAtLeast(1) else vw
                holders.getOrPut(i) { PageHolder(pw, ph) }
                requestRender(i, pw, ph)
            }
        }
    }

    private fun evict(first: Int, last: Int) {
        val buffer = if (isZoomed()) 0 else pageBuffer
        val lo = (first - buffer).coerceAtLeast(0)
        val hi = (last + buffer).coerceAtMost(pageCount - 1)
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
        lastEvictFirst = -1
        lastEvictLast = -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) scroller.forceFinished(true)
        val scaleHandled = scaleDetector.onTouchEvent(e)
        val gestureHandled = if (!scaleDetector.isInProgress) detector.onTouchEvent(e) else true
        return scaleHandled || gestureHandled || super.onTouchEvent(e)
    }

    override fun scrollTo(x: Int, y: Int) { super.scrollTo(x, y.coerceIn(0, maxScrollY)); invalidate() }

    fun scrollToPage(p: Int) { if (pageOffsets.isNotEmpty()) scrollTo(0, pageOffsets[p.coerceIn(0, pageCount - 1)]) }

    fun getCurrentPage(): Int {
        if (pageOffsets.isEmpty()) return 0; val sy = scrollY
        var lo = 0; var hi = pageCount - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (pageOffsets[m] + pageHeights[m] <= sy) lo = m + 1 else hi = m }; return lo
    }

    fun cleanup() { scope.cancel(); recycleAll() }

    private class PageHolder(var w: Int, var h: Int) {
        var bitmap: Bitmap? = null
        var needsRerender: Boolean = false
    }
}

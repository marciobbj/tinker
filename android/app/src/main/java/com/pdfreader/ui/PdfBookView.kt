package com.pdfreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.graphics.withSave
import kotlinx.coroutines.*

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

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); animating = false; return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            parent.requestDisallowInterceptTouchEvent(true); offsetX -= dx; invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val t = if (vx > 300) currentPage - 1 else if (vx < -300) currentPage + 1 else currentPage
            animateTo(t.coerceIn(0, pageCount - 1)); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val x = e.x
            when { x < width * 0.25f -> goToPage(currentPage - 1); x > width * 0.75f -> goToPage(currentPage + 1); else -> listener?.onPageTap() }
            return true
        }
    })

    init { setBackgroundColor(Color.BLACK) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); vw = w; vh = h; offsetX = 0f; recycleAll()
        if (w > 0 && h > 0) prefetch(currentPage)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (vw <= 0 || vh <= 0 || pageCount <= 0) return
        if (offsetX > 0 && currentPage > 0) drawPage(canvas, currentPage - 1, (-vw + offsetX).toInt(), 0, vw, vh)
        drawPage(canvas, currentPage, offsetX.toInt(), 0, vw, vh)
        if (offsetX < 0 && currentPage < pageCount - 1) drawPage(canvas, currentPage + 1, (vw + offsetX).toInt(), 0, vw, vh)
        if (scroller.computeScrollOffset()) {
            offsetX = scroller.currX.toFloat(); postInvalidateOnAnimation()
            if (scroller.isFinished) finishTransition()
        }
    }

    private fun getReusableBitmap(w: Int, h: Int): Bitmap? {
        val i = bitmapPool.indexOfFirst { it.width == w && it.height == h && !it.isRecycled }
        return if (i >= 0) bitmapPool.removeAt(i) else null
    }

    private fun recycleBitmap(bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        if (bitmapPool.size < 3) {
            bitmapPool.add(bmp)
        } else {
            bmp.recycle()
        }
    }

    private fun drawPage(canvas: Canvas, page: Int, x: Int, y: Int, w: Int, h: Int) {
        val holder = holders.getOrPut(page) { PageHolder(w, h) }
        if (holder.w != w || holder.h != h) {
            recycleBitmap(holder.bitmap); holder.bitmap = null
            holder.w = w; holder.h = h
        }
        paint.color = Color.WHITE
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
        val bmp = holder.bitmap?.takeUnless { it.isRecycled }
        if (bmp != null) {
            canvas.withSave { clipRect(x, y, x + w, y + h); drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), Rect(x, y, x + w, y + h), paint) }
        } else {
            paint.color = Color.LTGRAY; canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
            requestRender(page, w, h)
        }
    }

    private fun requestRender(page: Int, w: Int, h: Int) {
        if (page < 0 || page >= pageCount || renderJobs.containsKey(page)) return
        val reused = getReusableBitmap(w, h)
        renderJobs[page] = scope.launch {
            try {
                val size = getPageSize?.invoke(page)
                val zoom = if (size != null && size.first > 0 && size.second > 0) {
                    minOf(w.toFloat() / size.first, h.toFloat() / size.second)
                } else 1.0f
                val bmp = renderPage?.invoke(page, w, h, zoom, reused)
                if (bmp != null && !bmp.isRecycled) {
                    val hld = holders[page]
                    if (hld != null && hld.w == w && hld.h == h) {
                        recycleBitmap(hld.bitmap); hld.bitmap = bmp
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
        requestRender(center, vw, vh)
        if (center > 0) requestRender(center - 1, vw, vh)
        if (center < pageCount - 1) requestRender(center + 1, vw, vh)
        evict()
    }

    private fun evict() {
        val lo = (currentPage - 1).coerceAtLeast(0); val hi = (currentPage + 1).coerceAtMost(pageCount - 1)
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
        val handled = detector.onTouchEvent(e)
        if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
            if (!animating) { val t = vw * 0.2f; when { offsetX > t -> animateTo(currentPage - 1); offsetX < -t -> animateTo(currentPage + 1); else -> animateTo(currentPage) } }
        }
        return handled || super.onTouchEvent(e)
    }

    fun goToPage(p: Int) { val t = p.coerceIn(0, pageCount - 1); if (t != currentPage) animateTo(t) }
    fun setPage(p: Int) { currentPage = p.coerceIn(0, pageCount - 1); offsetX = 0f; recycleAll(); listener?.onPageChanged(currentPage); prefetch(currentPage); invalidate() }
    fun cleanup() { scope.cancel(); recycleAll() }

    private class PageHolder(var w: Int, var h: Int) { var bitmap: Bitmap? = null }
}

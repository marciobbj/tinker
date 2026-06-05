package com.pdfreader.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A transparent overlay view that captures freehand touch drawings
 * and represents them in resolution-independent PDF page coordinates.
 */
class InkDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class PageTransform(
        val offsetX: Float,
        val offsetY: Float,
        val scale: Float
    )

    class Stroke(
        val points: MutableList<PointF> = mutableListOf(),
        val color: Int,
        val width: Float // stroke width in PDF page points
    )

    // Drawing configuration
    var penColor: Int = Color.BLACK
    var penSize: Float = 3.0f // in PDF points
    var isEraserMode: Boolean = false

    // State
    var pageNumber: Int = 0
    var pageTransformProvider: (() -> PageTransform?)? = null

    private val strokes = mutableListOf<Stroke>()
    private val undoStack = mutableListOf<List<Stroke>>() // Stores copies of the stroke list for undo

    private var currentStroke: Stroke? = null
    private val screenPath = Path() // For low-latency rendering of current active path
    private var lastTouchPoint: PointF? = null

    // Paint objects
    private val paint = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val eraserRadiusPx = 40f // Eraser circle radius on screen

    init {
        // Ensure hardware acceleration is enabled
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun getStrokes(): List<Stroke> = strokes

    fun setInitialStrokes(initialStrokes: List<Stroke>) {
        strokes.clear()
        strokes.addAll(initialStrokes)
        undoStack.clear() // Clear undo stack when loading fresh initial strokes
        invalidate()
    }

    fun clear() {
        saveToUndoStack()
        strokes.clear()
        currentStroke = null
        screenPath.reset()
        invalidate()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            strokes.clear()
            strokes.addAll(undoStack.removeAt(undoStack.size - 1))
            invalidate()
        }
    }

    private fun saveToUndoStack() {
        // Deep copy strokes for undo stack
        val copy = strokes.map { stroke ->
            Stroke(
                points = stroke.points.map { PointF(it.x, it.y) }.toMutableList(),
                color = stroke.color,
                width = stroke.width
            )
        }
        undoStack.add(copy)
        // Limit stack size to 20 to prevent excessive memory usage
        if (undoStack.size > 20) {
            undoStack.removeAt(0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val transform = pageTransformProvider?.invoke() ?: return

        // 1. Draw completed strokes
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            paint.color = stroke.color
            paint.strokeWidth = stroke.width * transform.scale
            
            drawStroke(canvas, stroke.points, transform)
        }

        // 2. Draw current active stroke
        val activeStroke = currentStroke
        if (activeStroke != null && activeStroke.points.isNotEmpty()) {
            paint.color = activeStroke.color
            paint.strokeWidth = activeStroke.width * transform.scale
            
            drawStroke(canvas, activeStroke.points, transform)
        }

        // 3. Draw eraser visual feedback
        if (isEraserMode && lastTouchPoint != null) {
            eraserPaint.color = Color.GRAY
            eraserPaint.strokeWidth = 2f
            canvas.drawCircle(lastTouchPoint!!.x, lastTouchPoint!!.y, eraserRadiusPx, eraserPaint)
        }
    }

    private fun drawStroke(canvas: Canvas, points: List<PointF>, transform: PageTransform) {
        if (points.size == 1) {
            val pt = points[0]
            val x = transform.offsetX + pt.x * transform.scale
            val y = transform.offsetY + pt.y * transform.scale
            canvas.drawPoint(x, y, paint)
        } else {
            screenPath.reset()
            val first = points[0]
            screenPath.moveTo(
                transform.offsetX + first.x * transform.scale,
                transform.offsetY + first.y * transform.scale
            )
            for (i in 1 until points.size) {
                val pt = points[i]
                screenPath.lineTo(
                    transform.offsetX + pt.x * transform.scale,
                    transform.offsetY + pt.y * transform.scale
                )
            }
            canvas.drawPath(screenPath, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val transform = pageTransformProvider?.invoke() ?: return false

        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                saveToUndoStack()
                lastTouchPoint = PointF(e.x, e.y)
                
                val pdfX = (e.x - transform.offsetX) / transform.scale
                val pdfY = (e.y - transform.offsetY) / transform.scale

                if (isEraserMode) {
                    eraseAt(pdfX, pdfY, transform.scale)
                } else {
                    currentStroke = Stroke(color = penColor, width = penSize).apply {
                        points.add(PointF(pdfX, pdfY))
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                lastTouchPoint = PointF(e.x, e.y)
                val pdfX = (e.x - transform.offsetX) / transform.scale
                val pdfY = (e.y - transform.offsetY) / transform.scale

                if (isEraserMode) {
                    eraseAt(pdfX, pdfY, transform.scale)
                } else {
                    currentStroke?.points?.add(PointF(pdfX, pdfY))
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastTouchPoint = null
                currentStroke?.let {
                    if (it.points.isNotEmpty()) {
                        strokes.add(it)
                    }
                }
                currentStroke = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun eraseAt(pdfX: Float, pdfY: Float, scale: Float) {
        val p = PointF(pdfX, pdfY)
        val eraserRadiusPdf = eraserRadiusPx / scale
        var anyErased = false

        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val stroke = iterator.next()
            if (stroke.points.isEmpty()) continue

            // For single-point strokes
            if (stroke.points.size == 1) {
                if (distance(p, stroke.points[0]) < (eraserRadiusPdf + stroke.width / 2f)) {
                    iterator.remove()
                    anyErased = true
                }
                continue
            }

            // For segment-based strokes
            for (i in 0 until stroke.points.size - 1) {
                val a = stroke.points[i]
                val b = stroke.points[i + 1]
                val dist = distanceToSegment(p, a, b)
                if (dist < (eraserRadiusPdf + stroke.width / 2f)) {
                    iterator.remove()
                    anyErased = true
                    break
                }
            }
        }

        if (anyErased) {
            invalidate()
        }
    }

    // Geometry Helpers
    private fun distanceToSegment(p: PointF, a: PointF, b: PointF): Float {
        val l2 = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)
        if (l2 == 0f) return distance(p, a)
        
        var t = ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / l2
        t = t.coerceIn(0f, 1f)
        
        val projection = PointF(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
        return distance(p, projection)
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        return kotlin.math.hypot(p1.x - p2.x, p1.y - p2.y)
    }
}

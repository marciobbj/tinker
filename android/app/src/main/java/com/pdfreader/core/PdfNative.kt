package com.pdfreader.core

import android.graphics.Bitmap

object PdfNative {
    init {
        System.loadLibrary("mupdf_java")
        System.loadLibrary("pdfcore")
    }

    external fun nativeCreateEngine(): Long
    external fun nativeDestroyEngine(handle: Long)
    external fun nativeOpenDocument(handle: Long, path: String): Boolean
    external fun nativeCloseDocument(handle: Long)
    external fun nativeGetPageCount(handle: Long): Int
    external fun nativeGetTitle(handle: Long): String
    external fun nativeRenderPage(handle: Long, pageNumber: Int, bitmap: Bitmap, width: Int, height: Int, zoom: Float): Boolean
    external fun nativeSetDarkMode(handle: Long, enabled: Boolean)
    external fun nativeGetPageSize(handle: Long, pageNumber: Int): FloatArray?
    external fun nativeGetSelectionQuads(handle: Long, pageNumber: Int, ax: Float, ay: Float, bx: Float, by: Float, mode: Int): FloatArray?
    external fun nativeCopySelection(handle: Long, pageNumber: Int, ax: Float, ay: Float, bx: Float, by: Float, mode: Int): String
    external fun nativeAddMarkupAnnotation(handle: Long, pageNumber: Int, type: Int, quads: FloatArray,
                                 r: Float, g: Float, b: Float, opacity: Float): Boolean
    external fun nativeDeleteMarkupAnnotation(handle: Long, pageNumber: Int, type: Int, quads: FloatArray): Boolean
    external fun nativeAddInkAnnotation(
        handle: Long, pageNumber: Int,
        points: FloatArray, strokeLengths: IntArray,
        strokeCount: Int,
        r: Float, g: Float, b: Float, opacity: Float, lineWidth: Float
    ): Boolean
    external fun nativeDeleteInkAnnotation(handle: Long, pageNumber: Int, matchPoints: FloatArray): Boolean
    external fun nativeGetInkAnnotations(handle: Long, pageNumber: Int): FloatArray?
    external fun nativeClearInkAnnotations(handle: Long, pageNumber: Int): Boolean
    external fun nativeSaveDocument(handle: Long): Boolean
}

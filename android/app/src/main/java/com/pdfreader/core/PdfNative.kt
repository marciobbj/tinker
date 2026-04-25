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
}

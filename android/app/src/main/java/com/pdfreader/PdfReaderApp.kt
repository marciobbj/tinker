package com.pdfreader

import android.app.Application
import android.content.Context

class PdfReaderApp : Application() {
    companion object {
        lateinit var instance: PdfReaderApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}

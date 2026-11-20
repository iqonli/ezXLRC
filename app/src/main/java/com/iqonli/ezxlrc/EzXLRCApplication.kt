package com.iqonli.ezxlrc

import android.app.Application

class EzXLRCApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }
}

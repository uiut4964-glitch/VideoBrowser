package com.example.videobrowser

import android.app.Application
import android.util.Log
import java.io.File
import java.util.Date

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(Date().toString() + "\n" + Log.getStackTraceString(e))
            } catch (x: Exception) {
            }
            old?.uncaughtException(t, e)
        }
    }
}

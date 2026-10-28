package com.smartwater.monitor

import android.app.Application
import com.smartwater.monitor.engine.MonitorEngine
import org.opencv.android.OpenCVLoader

class App : Application() {
    lateinit var engine: MonitorEngine
        private set
    var openCvReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        openCvReady = OpenCVLoader.initLocal()
        engine = MonitorEngine(this)
    }
}

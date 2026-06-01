package com.ntzb.myradio

import android.app.Application
import com.ntzb.myradio.work.RefreshWorker

class RadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedule(this)
    }
}

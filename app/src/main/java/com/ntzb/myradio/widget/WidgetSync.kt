package com.ntzb.myradio.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single entry point for refreshing the widget. provideGlance reads the app's DataStore
 * (liked set + now-playing) directly, so we just need to trigger a recomposition.
 *  - MUST run off the main thread (Glance update does disk I/O; on Main it fails silently).
 *  - Serialized with a Mutex so rapid triggers (e.g. tapping hearts quickly) don't fire
 *    concurrent updateAll() calls that race and drop each other.
 */
object WidgetSync {
    private val mutex = Mutex()

    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { RadioWidget().updateAll(context.applicationContext) }
        }
        Unit
    }
}

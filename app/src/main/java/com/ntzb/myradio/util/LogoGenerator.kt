package com.ntzb.myradio.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates a fallback station logo when none is available: a colored square (hue derived from
 * the name, so it's stable per station) with the station's initial centered in white.
 */
object LogoGenerator {

    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun generate(name: String, sizePx: Int = 256): Bitmap {
        cache["$name@$sizePx"]?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hue = ((name.hashCode() % 360) + 360) % 360
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.5f, 0.6f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val initial = initialOf(name)
        val y = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, sizePx / 2f, y, paint)
        cache["$name@$sizePx"] = bmp
        return bmp
    }

    /** First meaningful character of the name (skips a leading "(" etc.), uppercased. */
    private fun initialOf(name: String): String {
        val ch = name.trim().firstOrNull { it.isLetterOrDigit() }
        return ch?.toString()?.uppercase() ?: "?"
    }
}

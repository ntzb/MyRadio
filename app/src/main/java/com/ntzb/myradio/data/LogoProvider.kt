package com.ntzb.myradio.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Serves station logos as `content://` URIs. Android Auto runs in a different process and cannot
 * read our bundled `file:///android_asset/...` logos, so we expose each logo through this read-only
 * provider (caching bundled-asset or remote logos to a file on first request). Used as the artwork
 * URI for browse items and the playing item so logos show in Auto's lists and now-playing.
 */
class LogoProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val id = uri.lastPathSegment ?: return null
        val file = runCatching { cachedLogo(ctx, id) }.getOrNull() ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.ntzb.myradio.logos"

        fun uriFor(stationId: String): Uri =
            Uri.parse("content://$AUTHORITY").buildUpon().appendPath(stationId).build()

        // Bounded so a slow/unreachable remote logo can't hang the binder thread serving openFile.
        private val http by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }
        // id -> logoUri, loaded once (logos rarely change; refreshed on process restart).
        @Volatile private var logoUris: Map<String, String>? = null

        /** Cached logo file for the station, fetched (asset/http) on first request. Null if none. */
        private fun cachedLogo(context: Context, stationId: String): File? {
            val dir = File(context.cacheDir, "logos").apply { mkdirs() }
            val out = File(dir, stationId)
            if (out.exists() && out.length() > 0) return out   // fast path: already cached

            // Don't cache an empty catalog (a transient load failure) — let a later request retry.
            val uris = logoUris ?: loadLogoUris(context).also { if (it.isNotEmpty()) logoUris = it }
            val uri = uris[stationId] ?: return null
            val bytes = when {
                uri.startsWith("file:///android_asset/") ->
                    context.assets.open(uri.removePrefix("file:///android_asset/")).use { it.readBytes() }
                uri.startsWith("http") ->
                    http.newCall(Request.Builder().url(uri).build()).execute().use { it.body?.bytes() }
                else -> null
            }?.takeIf { it.isNotEmpty() } ?: return null
            // Write to a temp file then rename, so a kill mid-write can't leave a truncated cache file.
            val tmp = File.createTempFile("logo", null, dir)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(out)) tmp.delete()   // lost a concurrent race / rename unsupported
            return out.takeIf { it.exists() && it.length() > 0 }
        }

        private fun loadLogoUris(context: Context): Map<String, String> =
            runCatching {
                runBlocking { StationRepository.loadStations(context) }
                    .associate { it.id to it.logoUri }
            }.getOrDefault(emptyMap())
    }
}

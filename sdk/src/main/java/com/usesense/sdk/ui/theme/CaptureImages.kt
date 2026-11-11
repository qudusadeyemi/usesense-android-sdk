package com.usesense.sdk.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brand images (logo, page background, result illustrations, loader image).
 * https and data: URLs only (BRAND-v1 section 7); anything else is ignored.
 */
internal object CaptureImages {
    private val cache = LruCache<String, Bitmap>(8)

    /** Only these schemes load. */
    fun isAllowed(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || u.startsWith("data:image/")
    }

    suspend fun load(url: String?): Bitmap? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() && isAllowed(it) } ?: return null
        cache.get(u)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                if (u.startsWith("data:", ignoreCase = true)) {
                    val comma = u.indexOf(',')
                    if (comma < 0 || !u.substring(0, comma).contains(";base64", ignoreCase = true)) return@runCatching null
                    val bytes = Base64.decode(u.substring(comma + 1), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    val conn = java.net.URL(u).openConnection().apply {
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                    }
                    conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()?.also { cache.put(u, it) }
        }
    }

    private const val TIMEOUT_MS = 10_000
}

/** `background.imageUrl` drawn "cover" (centre-cropped) over the page colour. */
internal class CoverDrawable(private val bitmap: Bitmap, private val fill: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(fill)
        if (b.width() <= 0 || b.height() <= 0) return
        val scale = maxOf(b.width() / bitmap.width.toFloat(), b.height() / bitmap.height.toFloat())
        val w = (b.width() / scale).toInt()
        val h = (b.height() / scale).toInt()
        val x = (bitmap.width - w) / 2
        val y = (bitmap.height - h) / 2
        src.set(x, y, x + w, y + h)
        canvas.drawBitmap(bitmap, src, b, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

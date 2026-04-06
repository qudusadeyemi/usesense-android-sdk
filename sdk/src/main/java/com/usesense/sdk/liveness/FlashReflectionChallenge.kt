package com.usesense.sdk.liveness

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Choreographer
import android.view.View
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Flash Reflection step-up challenge per spec Chapter 7.4.
 *
 * Displays 3 random colored overlays. Real faces reflect the colors; screens don't.
 *
 * Protocol per color:
 * 1. Clear overlay, wait 400ms, sample face-region RGB (baseline)
 * 2. Display color overlay (60% opacity), wait 600ms, sample face-region RGB (flash)
 * 3. Compute colorDelta = sqrt(dR² + dG² + dB²)
 * 4. Check: delta >= 8 AND dominant channel shift matches flash color
 *
 * Pass if >= 2 of 3 flashes show valid reflection.
 */
internal class FlashReflectionChallenge {

    data class FlashResult(
        val color: String,
        val durationMs: Long,
        val baselineRgb: IntArray,
        val flashRgb: IntArray,
        val colorDelta: Double,
        val reflectionDetected: Boolean,
    )

    private val flashResults = mutableListOf<FlashResult>()
    var passed = false
        private set
    var hardReject = false
        private set
    var confidence = 0
        private set
    var overallColorDelta = 0.0
        private set

    /**
     * Run the flash reflection challenge.
     *
     * @param overlayView View to apply color overlay to
     * @param sampleFrame Function that captures current camera frame as Bitmap
     * @return true if challenge passed
     */
    suspend fun execute(
        overlayView: View,
        sampleFrame: suspend () -> Bitmap?,
    ): Boolean = withContext(Dispatchers.Main) {
        flashResults.clear()

        // Pick 3 random colors
        val colors = FLASH_COLORS.shuffled().take(3)

        for (colorHex in colors) {
            val color = Color.parseColor(colorHex)

            // Phase 1: Baseline - clear overlay, wait, sample
            overlayView.setBackgroundColor(Color.TRANSPARENT)
            waitForRender(overlayView)
            delay(400)
            val baselineBitmap = sampleFrame()
            val baselineRgb = sampleCenterRegion(baselineBitmap)
            baselineBitmap?.recycle()

            // Phase 2: Flash - display color overlay (60% opacity), wait, sample
            val flashColor = Color.argb(153, Color.red(color), Color.green(color), Color.blue(color))
            overlayView.setBackgroundColor(flashColor)
            waitForRender(overlayView)
            delay(600)
            val flashBitmap = sampleFrame()
            val flashRgb = sampleCenterRegion(flashBitmap)
            flashBitmap?.recycle()

            // Compute color delta
            val dR = (flashRgb[0] - baselineRgb[0]).toDouble()
            val dG = (flashRgb[1] - baselineRgb[1]).toDouble()
            val dB = (flashRgb[2] - baselineRgb[2]).toDouble()
            val delta = sqrt(dR * dR + dG * dG + dB * dB)

            // Check dominant channel matches flash direction
            val dominantMatch = checkDominantChannel(colorHex, dR.toInt(), dG.toInt(), dB.toInt())
            val reflected = delta >= DELTA_THRESHOLD && dominantMatch

            flashResults.add(FlashResult(
                color = colorHex,
                durationMs = 600,
                baselineRgb = baselineRgb,
                flashRgb = flashRgb,
                colorDelta = delta,
                reflectionDetected = reflected,
            ))
        }

        // Clear overlay
        overlayView.setBackgroundColor(Color.TRANSPARENT)

        // Evaluate results
        val passCount = flashResults.count { it.reflectionDetected }
        passed = passCount >= 2
        overallColorDelta = flashResults.map { it.colorDelta }.average()

        // Hard reject: average delta < 3 across ALL flashes
        hardReject = overallColorDelta < HARD_REJECT_DELTA

        confidence = when (passCount) {
            3 -> 95
            2 -> 75
            1 -> 40
            else -> 10
        }

        passed
    }

    /**
     * Check if the dominant color delta channel matches the expected flash color direction.
     */
    private fun checkDominantChannel(colorHex: String, dR: Int, dG: Int, dB: Int): Boolean {
        val maxDelta = maxOf(kotlin.math.abs(dR), kotlin.math.abs(dG), kotlin.math.abs(dB))
        if (maxDelta < 3) return false

        return when (colorHex) {
            "#FF0000" -> kotlin.math.abs(dR) == maxDelta && dR > 0
            "#00FF00" -> kotlin.math.abs(dG) == maxDelta && dG > 0
            "#0000FF" -> kotlin.math.abs(dB) == maxDelta && dB > 0
            "#FFFF00" -> dR > 0 && dG > 0
            "#FF00FF" -> dR > 0 && dB > 0
            "#00FFFF" -> dG > 0 && dB > 0
            else -> true
        }
    }

    /**
     * Sample the center 40% of the frame at reduced resolution (120x90).
     * Returns [R, G, B] average values.
     */
    private fun sampleCenterRegion(bitmap: Bitmap?): IntArray {
        if (bitmap == null) return intArrayOf(0, 0, 0)

        val w = bitmap.width
        val h = bitmap.height
        val x0 = (w * 0.3).toInt()
        val y0 = (h * 0.2).toInt()
        val regionW = (w * 0.4).toInt()
        val regionH = (h * 0.5).toInt()

        // Sample at reduced resolution
        val sampleW = minOf(120, regionW)
        val sampleH = minOf(90, regionH)
        val stepX = regionW / sampleW
        val stepY = regionH / sampleH

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0

        for (sy in 0 until sampleH) {
            for (sx in 0 until sampleW) {
                val px = x0 + sx * stepX
                val py = y0 + sy * stepY
                if (px < w && py < h) {
                    val pixel = bitmap.getPixel(px, py)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    count++
                }
            }
        }

        return if (count > 0) {
            intArrayOf(
                (totalR / count).toInt(),
                (totalG / count).toInt(),
                (totalB / count).toInt(),
            )
        } else {
            intArrayOf(0, 0, 0)
        }
    }

    /**
     * Wait for the view to actually render the color change.
     * Uses Choreographer to wait for the next frame callback.
     */
    private suspend fun waitForRender(view: View) {
        suspendCancellableCoroutine { cont ->
            view.post {
                Choreographer.getInstance().postFrameCallback {
                    if (cont.isActive) cont.resume(Unit) {}
                }
            }
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("type", "flash_reflection")

        val flashArray = JSONArray()
        for (result in flashResults) {
            flashArray.put(JSONObject().apply {
                put("color", result.color)
                put("durationMs", result.durationMs)
                put("baselineRgb", JSONArray(result.baselineRgb.toList()))
                put("flashRgb", JSONArray(result.flashRgb.toList()))
                put("colorDelta", result.colorDelta)
                put("reflectionDetected", result.reflectionDetected)
            })
        }
        json.put("flashes", flashArray)
        json.put("passed", passed)
        json.put("confidence", confidence)
        json.put("overallColorDelta", overallColorDelta)

        return json
    }

    companion object {
        val FLASH_COLORS = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF", "#00FFFF")
        const val DELTA_THRESHOLD = 8.0
        const val HARD_REJECT_DELTA = 3.0
    }
}

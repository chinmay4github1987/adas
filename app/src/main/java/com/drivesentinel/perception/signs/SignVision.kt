package com.drivesentinel.perception.signs

import com.drivesentinel.core.BBox
import com.drivesentinel.perception.lane.ArgbFrame
import kotlin.math.max
import kotlin.math.min

enum class LightColor { RED, AMBER, GREEN, UNKNOWN }

/** ARGB → hue (0..360), saturation and value (0..1). Pure Kotlin so it runs in unit tests. */
internal fun hsv(argb: Int, out: FloatArray) {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    out[0] = if (h < 0f) h + 360f else h
    out[1] = if (mx == 0f) 0f else d / mx
    out[2] = mx
}

private fun isSignRed(h: Float, s: Float, v: Float) = (h < 12f || h > 340f) && s > 0.45f && v > 0.25f

/**
 * Reads a traffic-light crop by counting bright, saturated lamp pixels per hue band.
 * Unlit lamps are dark, so only the lit one passes the brightness gate.
 */
object TrafficLightClassifier {
    fun classify(pixels: IntArray, width: Int, height: Int): LightColor {
        val px = FloatArray(3)
        var red = 0
        var amber = 0
        var green = 0
        val n = width * height
        for (i in 0 until n) {
            hsv(pixels[i], px)
            if (px[1] < 0.4f || px[2] < 0.55f) continue
            val h = px[0]
            when {
                h < 15f || h >= 335f -> red++
                h < 55f -> amber++
                h in 110f..200f -> green++ // signal greens are bluish
            }
        }
        val lit = red + amber + green
        if (lit < max(3, n / 50)) return LightColor.UNKNOWN
        val best = maxOf(red, amber, green)
        if (best < lit * 0.6f) return LightColor.UNKNOWN
        return when (best) {
            red -> LightColor.RED
            amber -> LightColor.AMBER
            else -> LightColor.GREEN
        }
    }
}

/**
 * Finds regulatory-sign candidates — a red ring around a light centre (speed limits) — in a
 * small frame. Filled red shapes (STOP, no-entry) fail the fill-ratio test and are ignored.
 */
object RedRingFinder {
    private const val MIN_SIDE_PX = 8

    fun find(frame: ArgbFrame, maxResults: Int = 1): List<BBox> {
        val w = frame.width
        val h = frame.height
        val searchH = (h * 0.8f).toInt() // signs sit above the bonnet line
        val px = FloatArray(3)
        val red = BooleanArray(w * searchH)
        for (y in 0 until searchH) for (x in 0 until w) {
            hsv(frame.pixels[y * w + x], px)
            red[y * w + x] = isSignRed(px[0], px[1], px[2])
        }

        val seen = BooleanArray(red.size)
        val stack = IntArray(red.size)
        val found = mutableListOf<Pair<BBox, Int>>()
        for (start in red.indices) {
            if (!red[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var count = 0
            var minX = w; var maxX = 0; var minY = searchH; var maxY = 0
            while (sp > 0) {
                val i = stack[--sp]
                count++
                val x = i % w
                val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && red[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && red[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && red[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < searchH - 1 && red[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < MIN_SIDE_PX || bh < MIN_SIDE_PX || bh > h * 0.45f) continue
            val aspect = bw.toFloat() / bh
            if (aspect !in 0.7f..1.4f) continue
            val fill = count.toFloat() / (bw * bh)
            if (fill !in 0.12f..0.55f) continue
            if (!lightCentre(frame, minX, minY, bw, bh, px)) continue
            found += BBox(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1).toFloat() / w, (maxY + 1).toFloat() / h) to bw * bh
        }
        return found.sortedByDescending { it.second }.take(maxResults).map { it.first }
    }

    /** The inner half of a speed-limit sign is a white/light background with dark digits. */
    private fun lightCentre(frame: ArgbFrame, x0: Int, y0: Int, bw: Int, bh: Int, px: FloatArray): Boolean {
        val ix0 = x0 + bw / 4
        val iy0 = y0 + bh / 4
        var light = 0
        var total = 0
        for (y in iy0 until iy0 + max(1, bh / 2)) for (x in ix0 until ix0 + max(1, bw / 2)) {
            hsv(frame.pixels[y * frame.width + x], px)
            total++
            if (px[1] < 0.3f && px[2] > 0.5f) light++
        }
        return total > 0 && light >= total * 0.35f
    }
}

/** Turns OCR text from a red-ring crop into a plausible posted limit, or null. */
object SpeedLimitParser {
    private val VALID = setOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 80, 90, 100, 110, 120)
    private val UNITS = Regex("KM/?H|KMPH|KPH")
    private val SPACES = Regex("[\\s.]")
    private val DIGITS = Regex("\\d{2,3}")

    fun parse(text: String): Int? {
        val cleaned = text.uppercase().replace(UNITS, "").replace(SPACES, "")
        // Common OCR letter↔digit confusions on sign fonts.
        val fixed = cleaned.map {
            when (it) {
                'O', 'D', 'Q' -> '0'
                'I', 'L', '|' -> '1'
                'S' -> '5'
                'B' -> '8'
                else -> it
            }
        }.joinToString("")
        if (!fixed.matches(DIGITS)) return null // rejects "10T" weight limits, "STOP", etc.
        return fixed.toInt().takeIf { it in VALID }
    }
}

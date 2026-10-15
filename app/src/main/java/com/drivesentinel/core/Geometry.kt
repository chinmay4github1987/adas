package com.drivesentinel.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Axis-aligned box in **normalized** frame coordinates (0..1, origin top-left) unless a
 * call site says otherwise. Pure Kotlin so detector post-processing is unit-testable.
 */
data class BBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun iou(other: BBox): Float {
        val ix = max(0f, min(right, other.right) - max(left, other.left))
        val iy = max(0f, min(bottom, other.bottom) - max(top, other.top))
        val inter = ix * iy
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun clamped(): BBox = BBox(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    companion object {
        fun fromCenter(cx: Float, cy: Float, w: Float, h: Float) =
            BBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}

/** Minimal 3-vector for sensor math (device coordinates, m/s²). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    val norm: Float get() = sqrt(this dot this)
    fun normalized(): Vec3 = norm.let { n -> if (n < 1e-6f) ZERO else Vec3(x / n, y / n, z / n) }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

/** Exponential moving average helper; [alpha] = weight of the newest sample. */
class Ema(private val alpha: Float) {
    var value: Float = Float.NaN
        private set

    val hasValue: Boolean get() = !value.isNaN()

    fun update(sample: Float, alphaOverride: Float = alpha): Float {
        value = if (value.isNaN()) sample else value + alphaOverride * (sample - value)
        return value
    }

    fun reset() {
        value = Float.NaN
    }
}

const val G = 9.80665f

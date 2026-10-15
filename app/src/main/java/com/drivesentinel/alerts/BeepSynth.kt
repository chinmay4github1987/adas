package com.drivesentinel.alerts

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthesises short "robotic" alert tones as 16-bit mono PCM: a square-ish two-tone chirp,
 * bit-crushed and ring-modulated for a synthetic timbre, with short fades to avoid clicks.
 */
object BeepSynth {
    const val SAMPLE_RATE = 44_100

    data class Tone(val frequencyHz: Float, val durationMs: Int)

    /** "Bee-boop" used before voice prompts. */
    val ROBOT_PROMPT = listOf(Tone(1_320f, 90), Tone(0f, 30), Tone(880f, 120))

    /** Triple urgent pulse for FCW critical. */
    val URGENT = listOf(Tone(1_760f, 70), Tone(0f, 40), Tone(1_760f, 70), Tone(0f, 40), Tone(1_760f, 70))

    fun render(tones: List<Tone>, amplitude: Float = 0.6f, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val total = tones.sumOf { it.durationMs * sampleRate / 1000 }
        val out = ShortArray(total)
        var offset = 0
        for (tone in tones) {
            val n = tone.durationMs * sampleRate / 1000
            if (tone.frequencyHz > 0f) {
                val fade = (sampleRate * 0.004).toInt().coerceAtMost(n / 2) // 4 ms
                for (i in 0 until n) {
                    val t = i.toDouble() / sampleRate
                    val carrier = sin(2 * PI * tone.frequencyHz * t)
                    // Soft square: add odd harmonics, then ring-modulate at 70 Hz for "robot" buzz.
                    var v = carrier + sin(2 * PI * 3 * tone.frequencyHz * t) / 3 + sin(2 * PI * 5 * tone.frequencyHz * t) / 5
                    v *= 0.75 + 0.25 * sin(2 * PI * 70 * t)
                    // 5-bit crush.
                    v = (v * 16).roundToInt() / 16.0
                    val env = when {
                        i < fade -> i.toDouble() / fade
                        i > n - fade -> (n - i).toDouble() / fade
                        else -> 1.0
                    }
                    val s = (v * env * amplitude * 0.8 * Short.MAX_VALUE).coerceIn(-32767.0, 32767.0)
                    out[offset + i] = s.toInt().toShort()
                }
            }
            offset += n
        }
        return out
    }
}

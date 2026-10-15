package com.drivesentinel.perception.detector

import com.drivesentinel.core.BBox

/** A single detection in normalized coordinates of the (rotated, upright) analysis frame. */
data class Detection(
    val box: BBox,
    val classId: Int,
    val label: String,
    val score: Float,
)

/**
 * Road-relevant COCO classes and the assumed real-world heights used for monocular distance.
 * Heights are rough priors (car ≈ 1.5 m, truck/bus ≈ 3 m, motorcycle+rider ≈ 1.4 m, …) — see
 * research notes §3; they are NOT calibrated for Indian vehicle mixes (no autorickshaw in COCO).
 */
object RoadClasses {
    const val PERSON = 0
    const val BICYCLE = 1
    const val CAR = 2
    const val MOTORCYCLE = 3
    const val BUS = 5
    const val TRUCK = 7
    const val DOG = 16
    const val COW = 19

    val ALLOWED: Set<Int> = setOf(PERSON, BICYCLE, CAR, MOTORCYCLE, BUS, TRUCK, DOG, COW)

    /** Classes that can be the FCW "lead" target (anything in the ego path you could hit). */
    val LEAD_CANDIDATES: Set<Int> = setOf(CAR, MOTORCYCLE, BUS, TRUCK, BICYCLE, COW, PERSON)

    fun realHeightMeters(classId: Int): Float = when (classId) {
        CAR -> 1.5f
        TRUCK -> 3.2f
        BUS -> 3.1f
        MOTORCYCLE -> 1.4f
        BICYCLE -> 1.6f
        PERSON -> 1.7f
        COW -> 1.4f
        DOG -> 0.6f
        else -> 1.5f
    }
}

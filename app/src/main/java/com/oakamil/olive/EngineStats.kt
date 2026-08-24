package com.oakamil.olive

data class EngineStats(
    val status: String = "Idle",
    val ra: Double? = null,
    val dec: Double? = null,
    val roll: Double? = null,
    val fov: Double? = null,
    val centroidCount: Int = 0,
    val exposureMs: Double = 0.0,
    val postProcessMs: Double = 0.0,
    val extractMs: Double = 0.0,
    val solveMs: Double = 0.0,
    val solveRateHz: Double = 0.0,
    val offsetRa: Double? = null,
    val offsetDec: Double? = null,
    val offsetAz: Double? = null,
    val offsetAlt: Double? = null,
    val offsetAngle: Double? = null,
    val hfr: Double = 0.0,
    val targetRa: Double? = null,
    val targetDec: Double? = null,
    val isImuEstimate: Boolean = false,
    val solvedCrop: String? = null
)

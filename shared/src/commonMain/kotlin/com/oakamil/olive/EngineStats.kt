package com.oakamil.olive

enum class EngineStatus(val rawValue: String) {
    IDLE("Idle"),
    SOLVING("Solving"),
    SLEWING("Slewing"),
    CAMERA_MODE("Camera Mode"),
    CALIBRATING("Calibrating"),
    CALIBRATING_FOCUS("Calibrating Focus");

    companion object {
        fun fromRaw(status: String): EngineStatus? = entries.find { it.rawValue == status }
    }
}

data class EngineStats(
    val status: String = EngineStatus.IDLE.rawValue,
    val ra: Double? = null,
    val dec: Double? = null,
    val centerRa: Double? = null,
    val centerDec: Double? = null,
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
    val isMirrored: Boolean? = null,
    val solvedCrop: String? = null,
    val targetPixelX: Double? = null,
    val targetPixelY: Double? = null,
    val distortion: Double? = null,
    val focus: Float? = null
) {
    val parsedStatus: EngineStatus?
        get() = EngineStatus.fromRaw(status)

    val isCalibrating: Boolean
        get() = parsedStatus == EngineStatus.CALIBRATING || parsedStatus == EngineStatus.CALIBRATING_FOCUS

    val isCalibratingFocus: Boolean
        get() = parsedStatus == EngineStatus.CALIBRATING_FOCUS

    val isActivelySolvingOrSlewing: Boolean
        get() = parsedStatus == EngineStatus.SOLVING || parsedStatus == EngineStatus.SLEWING

    val isEstimateOrFailedSolve: Boolean
        get() = isImuEstimate || solvedCrop == CROP_FAILED

    val hasValidSolve: Boolean
        get() = solvedCrop != null && solvedCrop != CROP_FAILED && !isImuEstimate

    fun isCoordInCameraView(objRa: Double, objDec: Double): Boolean {
        val isStale = solvedCrop == CROP_FAILED && !isImuEstimate
        if (fov == null || isStale) return false
        val projRa = if (isImuEstimate || centerRa == null) ra else centerRa
        val projDec = if (isImuEstimate || centerDec == null) dec else centerDec
        if (projRa == null || projDec == null) return false
        return AstrometryUtils.angularDistance(projRa, projDec, objRa, objDec) < fov * 0.75
    }

    fun isTargetMatch(objRa: Double, objDec: Double): Boolean =
        targetRa != null && targetDec != null &&
                AstrometryUtils.angularDistance(objRa, objDec, targetRa, targetDec) < 0.05

    val isTargetVisible: Boolean
        get() = targetRa != null && targetDec != null && isCoordInCameraView(targetRa, targetDec)

    companion object {
        const val CROP_FAILED = "Failed"
    }
}

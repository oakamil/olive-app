// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlin.math.PI

internal object Math {
    const val PI: Double = kotlin.math.PI
    fun toRadians(angdeg: Double): Double = angdeg * (PI / 180.0)
    fun toDegrees(angrad: Double): Double = angrad * (180.0 / PI)
}


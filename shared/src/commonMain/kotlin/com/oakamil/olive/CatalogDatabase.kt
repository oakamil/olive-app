package com.oakamil.olive

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

data class CatalogObject(
    val id: Int,
    val catalogLabel: String,
    val catalogEntry: String,
    val ra: Double,
    val dec: Double,
    val constellation: String?,
    val objectType: String,
    val broadCategory: String,
    val magnitude: Double?,
    val surfaceBrightness: Double?,
    val angularSize: String?,
    val commonName: String?,
    val description: String?,
    val copyright: String?
) {
    val isFakeCommonName: Boolean
        get() = commonName != null && commonName.matches(Regex("^[a-zA-Z0-9]+\\s+([IVX]+\\s+)?\\d+[a-zA-Z]?$"))

    val displayName: String
        get() = if (commonName.isNullOrBlank() || isFakeCommonName) designation else commonName

    val designation: String
        get() = when (catalogLabel) {
            "Str", "Planet", "Solar System" -> {
                catalogEntry
            }

            in listOf("M", "NGC", "IC", "C", "Col") -> {
                catalogLabel + catalogEntry
            }

            else -> {
                "$catalogLabel $catalogEntry"
            }
        }
}

interface CatalogDatabase {
    companion object {
        const val CONTENT_VERSION = 1

        fun buildPriorityCaseSql(brightStarMagLimit: Double = BRIGHT_STAR_PRIORITY_MAG_LIMIT): String =
            "CASE " +
                    "WHEN catalog_label = 'Str' AND magnitude IS NOT NULL AND magnitude <= $brightStarMagLimit THEN 3 " +
                    "WHEN catalog_label = 'M' THEN 4 " +
                    "WHEN catalog_label = 'C' THEN 5 " +
                    "WHEN catalog_label = 'Col' THEN 6 " +
                    "WHEN catalog_label = 'NGC' THEN 7 " +
                    "WHEN catalog_label = 'IC' THEN 8 " +
                    "WHEN catalog_label = 'H' THEN 11 " +
                    "WHEN catalog_label = 'B' THEN 12 " +
                    "WHEN catalog_label = 'Sh2' THEN 13 " +
                    "WHEN catalog_label = 'Abl' THEN 14 " +
                    "WHEN catalog_label = 'Arp' THEN 15 " +
                    "WHEN catalog_label = 'SaA' THEN 16 " +
                    "WHEN catalog_label = 'SaM' THEN 17 " +
                    "WHEN catalog_label = 'SaR' THEN 18 " +
                    "WHEN catalog_label = 'Ta2' THEN 19 " +
                    "WHEN catalog_label = 'Har' THEN 20 " +
                    "WHEN catalog_label = 'RDS' THEN 21 " +
                    "WHEN catalog_label = 'EGC' THEN 22 " +
                    "WHEN catalog_label = 'Str' THEN 100 " +
                    "ELSE 999 END"

        private const val DEG_TO_RAD = PI / 180.0

        private fun sphericalCosDistanceSql(targetRa: Double, targetDec: Double): String {
            val targetRaRad = targetRa * DEG_TO_RAD
            val targetDecRad = targetDec * DEG_TO_RAD
            val sinTargetDec = sin(targetDecRad)
            val cosTargetDec = cos(targetDecRad)
            return "(SIN(dec * $DEG_TO_RAD) * $sinTargetDec + COS(dec * $DEG_TO_RAD) * $cosTargetDec * COS(ra * $DEG_TO_RAD - $targetRaRad))"
        }

        fun buildSphericalProximityClauseSql(
            targetRa: Double,
            targetDec: Double,
            radiusDegrees: Double
        ): String {
            val radius = radiusDegrees.coerceIn(0.0, 180.0)
            if (radius >= 180.0) return ""

            val decMin = (targetDec - radius).coerceAtLeast(-90.0)
            val decMax = (targetDec + radius).coerceAtMost(90.0)
            val clauses = mutableListOf("(dec BETWEEN $decMin AND $decMax)")

            // Only prune by RA when the search cap does not enclose or touch a celestial pole.
            if (abs(targetDec) + radius < 90.0) {
                val cosDec = cos(targetDec * DEG_TO_RAD)
                if (cosDec > 1e-9) {
                    val sinHalfAngle = (sin(radius * DEG_TO_RAD) / cosDec).coerceIn(-1.0, 1.0)
                    val deltaRa = asin(sinHalfAngle) / DEG_TO_RAD
                    if (deltaRa < 180.0) {
                        val normRa = ((targetRa % 360.0) + 360.0) % 360.0
                        val rawMin = normRa - deltaRa
                        val rawMax = normRa + deltaRa
                        if (rawMin >= 0.0 && rawMax <= 360.0) {
                            clauses += "(ra BETWEEN $rawMin AND $rawMax)"
                        } else {
                            val raMin = ((rawMin % 360.0) + 360.0) % 360.0
                            val raMax = rawMax % 360.0
                            clauses += "(ra >= $raMin OR ra <= $raMax)"
                        }
                    }
                }
            }

            val minCos = cos(radius * DEG_TO_RAD) - 1e-12
            clauses += "(${sphericalCosDistanceSql(targetRa, targetDec)} >= $minCos)"
            return clauses.joinToString(" AND ")
        }

        fun buildSphericalDistanceOrderSql(targetRa: Double, targetDec: Double): String =
            "(1.0 - ${sphericalCosDistanceSql(targetRa, targetDec)}) ASC"
    }

    fun getCatalogDescriptions(): Map<String, String>

    fun searchObjects(
        searchQuery: String = "",
        catalogFilter: String? = null,
        sortBy: String = "catalog ID",
        magLimit: Double? = null,
        includeNullMagnitude: Boolean = false,
        typeFilters: Set<String>? = null,
        starFilter: Boolean? = null,
        visibilityFilter: String = "Above Horizon",
        lat: Double? = null,
        lon: Double? = null,
        targetRa: Double? = null,
        targetDec: Double? = null,
        maxDistanceDegrees: Double? = null,
        limit: Int = 300,
        offset: Int = 0
    ): List<CatalogObject>

    fun getAsteroidsJson(): String = "[]"

    fun getCometsJson(): String = "[]"

    fun refreshComets(): Boolean = false

    fun getBspPath(): String = ""

    fun closeDatabase() {}
}

val DEFAULT_CATALOG_TYPE_FILTERS = setOf(
    "Asterism", "Asteroid", "Cluster + Nebula", "Comet", "Dark Nebula",
    "Galaxy", "Globular Cluster", "Multiple Star", "Nebula", "Open Cluster",
    "Planet", "Planetary Nebula", "Star"
)


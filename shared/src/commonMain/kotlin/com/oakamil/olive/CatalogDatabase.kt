package com.oakamil.olive

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


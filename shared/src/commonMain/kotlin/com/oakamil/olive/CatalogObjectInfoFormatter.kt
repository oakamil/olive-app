// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

/**
 * Display-ready metadata resolved from a primary [CatalogObject] and its co-located aliases.
 */
data class CatalogObjectMetadata(
    val designation: String?,
    val alsoKnownAs: String?,
    val surfaceBrightness: Double?,
    val angularSizeTokens: List<String>?,
    val distance: String?,
    val notes: String?,
)

/**
 * Resolves cross-catalog aliases, merged pre-expanded notes, and display units for [CatalogObject] popups.
 */
object CatalogObjectInfoFormatter {
    private val STAR_TYPES_WITHOUT_ANGULAR_SIZE = setOf("Star", "Multiple Star", "Double Star")
    private val DREYER_FALLBACK_LABELS = setOf("NGC", "IC")
    private val RICH_NARRATIVE_LABELS = setOf("Sh2", "Ta2", "H", "M", "SaA", "B", "EGC", "C", "Abl")
    private val PROSE_NARRATIVE_LABELS = setOf("Sh2", "Ta2", "H")
    private val SOURCE_RANK = mapOf(
        "Sh2" to 0,
        "Ta2" to 1,
        "H" to 2,
        "M" to 3,
        "SaA" to 4,
        "B" to 5,
        "EGC" to 6,
        "C" to 7,
        "Abl" to 8,
        "SaR" to 9,
        "RDS" to 10,
        "SaM" to 11,
        "Arp" to 12,
    )

    private data class RankedNote(val rank: Int, val index: Int, val note: String)

    private val SIZE_SPLIT_REGEX = Regex("\\s*[x×]\\s*", RegexOption.IGNORE_CASE)
    private val BARE_NUMBER_REGEX = Regex("^\\d+(?:\\.\\d+)?$")
    private val DYNAMIC_BOILERPLATE_REGEX =
        Regex("^The\\s+(?:Sun|planet\\s+.+|asteroid\\s+.+|comet\\s+.+)$", RegexOption.IGNORE_CASE)

    fun isBareNumber(token: String): Boolean = BARE_NUMBER_REGEX.matches(token)

    /**
     * Resolves merged metadata and deduplicated notes across [primary] and [coLocated].
     */
    fun extractMetadata(
        primary: CatalogObject,
        coLocated: List<CatalogObject> = emptyList(),
    ): CatalogObjectMetadata {
        val allRows = listOf(primary) + coLocated
        val showDesignation = primary.designation.isNotBlank() &&
                !primary.displayName.trim().equals(primary.designation.trim(), ignoreCase = true)
        val alsoKnownAs = coLocated
            .map { it.designation }
            .filter { it.isNotBlank() && it != primary.designation }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

        val surfaceBrightness = allRows.firstNotNullOfOrNull { row ->
            row.surfaceBrightness?.takeIf { it > 0.0 }
        }
        val angularSizeTokens = allRows.firstNotNullOfOrNull { row ->
            parseAngularSizeTokens(row.angularSize, row.objectType)
        }
        val distance = allRows.firstNotNullOfOrNull { row ->
            row.distance?.trim()?.takeIf { it.isNotEmpty() }
        }
        val notes = extractMergedNotes(allRows)

        return CatalogObjectMetadata(
            designation = if (showDesignation) primary.designation else null,
            alsoKnownAs = alsoKnownAs,
            surfaceBrightness = surfaceBrightness,
            angularSizeTokens = angularSizeTokens,
            distance = distance,
            notes = notes,
        )
    }

    /**
     * Splits an angular extent into normalized tokens, omitting point-source star/double separations.
     */
    fun parseAngularSizeTokens(raw: String?, objectType: String): List<String>? {
        if (objectType in STAR_TYPES_WITHOUT_ANGULAR_SIZE) return null
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.split(SIZE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    private fun extractMergedNotes(rows: List<CatalogObject>): String? {
        val primaryNotes = mutableListOf<RankedNote>()
        val fallbackDreyerNotes = mutableListOf<String>()
        val colNotes = mutableListOf<String>()
        var hasRichNarrative = false
        var hasSh2OrFullMessierProse = false

        for ((index, row) in rows.withIndex()) {
            val cleaned = cleanRuntimeDescription(row) ?: continue
            when (row.catalogLabel) {
                in DREYER_FALLBACK_LABELS -> fallbackDreyerNotes.add(cleaned)
                "Col" -> colNotes.add(cleaned)
                else -> {
                    if (row.catalogLabel in RICH_NARRATIVE_LABELS) {
                        hasRichNarrative = true
                    }
                    if (row.catalogLabel in PROSE_NARRATIVE_LABELS ||
                        (row.catalogLabel == "M" && "\n\nDreyer notes:" in cleaned)
                    ) {
                        hasSh2OrFullMessierProse = true
                    }
                    primaryNotes.add(
                        RankedNote(
                            SOURCE_RANK[row.catalogLabel] ?: SOURCE_RANK.size,
                            index,
                            cleaned
                        )
                    )
                }
            }
        }

        val ordered = mutableListOf<String>()
        primaryNotes.sortWith(compareBy({ it.rank }, { it.index }))
        primaryNotes.mapTo(ordered) { it.note }
        if (!hasRichNarrative) {
            ordered.addAll(fallbackDreyerNotes)
        }
        // Append Collinder star counts only when fuller Sharpless/Herschel/TAAS narratives aren't already present.
        if (!hasSh2OrFullMessierProse) {
            ordered.addAll(colNotes)
        }

        return ordered
            .distinctBy { it.lowercase() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
    }

    private fun cleanRuntimeDescription(obj: CatalogObject): String? {
        val raw = obj.description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (CatalogDatabase.isDynamicObject(obj) && DYNAMIC_BOILERPLATE_REGEX.matches(raw)) {
            return null
        }
        return raw
    }
}

// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlinx.datetime.Clock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Astronomical object from the bundled static SQLite catalog or runtime solar-system ephemerides.
 * [ra] and [dec] are J2000/ICRS degrees; dynamic objects (Sun, planets, asteroids, comets) use
 * non-positive [id] values.
 */
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
    val copyright: String?,
    val distance: String? = null
) {
    val designation: String = formatDesignation(catalogLabel, catalogEntry)

    val isFakeCommonName: Boolean = commonName != null && (
            commonName.equals("none", ignoreCase = true) ||
                    commonName.equals("nl", ignoreCase = true) ||
                    FAKE_COMMON_NAME_REGEX.matches(commonName)
            )

    val displayName: String =
        if (commonName.isNullOrBlank() || isFakeCommonName) designation else commonName

    val skyMapLabel: String =
        if (commonName.isNullOrBlank() || isFakeCommonName) designation else extractPrimaryName(
            commonName
        )

    companion object {
        private val COMPACT_CATALOG_LABELS = setOf("M", "NGC", "IC", "C", "Col")

        fun formatDesignation(catalogLabel: String, catalogEntry: String): String =
            when (catalogLabel) {
                "Str", "Planet", "Solar System" -> catalogEntry
                in COMPACT_CATALOG_LABELS -> catalogLabel + catalogEntry
                else -> "$catalogLabel $catalogEntry"
            }

        private val FAKE_COMMON_NAME_REGEX = Regex("^[a-zA-Z0-9]+\\s+([IVX]+\\s+)?\\d+[a-zA-Z]?$")
        private val SLASH_OR_EQUALS_REGEX = Regex("\\s+[/=]\\s+")
        private val BAYER_DESIGNATION_REGEX = Regex(
            "^(?:Alpha|Beta|Gamma|Delta|Epsilon|Zeta|Eta|Theta|Iota|Kappa|Lambda|Mu|Nu|Xi|Omicron|Pi|Rho|Sigma|Tau|Upsilon|Phi|Chi|Psi|Omega|[A-Za-z]{1,2})\\s+[A-Z][a-zA-Z]{2}\\.?$",
            RegexOption.IGNORE_CASE
        )

        private fun isTechnicalDesignation(s: String): Boolean =
            s.any { it.isDigit() } || s.endsWith(" pm") || BAYER_DESIGNATION_REGEX.matches(s)

        private fun extractPrimaryName(raw: String): String {
            if ('/' !in raw && ',' !in raw && '=' !in raw) return raw.trim()
            val slashParts =
                raw.split(SLASH_OR_EQUALS_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
            if (slashParts.size <= 1 && ',' !in raw) return raw.trim()
            slashParts.firstOrNull {
                ',' !in it && !it.contains(" or ", ignoreCase = true) && !isTechnicalDesignation(it)
            }?.let { return it }
            val allParts =
                slashParts.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            return allParts.firstOrNull { !isTechnicalDesignation(it) } ?: allParts.first()
        }
    }
}

data class ParsedCometElements(
    val catalogId: String,
    val commonName: String,
    val h: Double,
    val k: Double,
    val tPeri: Double,
    val q: Double,
    val e: Double,
    val w: Double,
    val node: Double,
    val incl: Double,
)

/**
 * Read-only astronomical catalog contract backed by the bundled SQLite database (`catalog.db`),
 * DE421 SPK ephemeris (`de421.bsp`), and active-comet MPC elements feed.
 * Shared SQL query builders and comet-feed parsers live on [Companion].
 */
interface CatalogDatabase {
    companion object {
        // 1. Constants & Shared Catalog Metadata
        const val CATALOG_DB_NAME = "catalog.db"
        const val BSP_ASSET_NAME = "de421.bsp"
        const val BRIGHT_STAR_PRIORITY_MAG_LIMIT = 4.0
        const val COMETS_JSON_STORAGE_KEY = "user_comets_json"
        const val COMETS_FEED_URL =
            "https://cobs.si/api/elements.api?format=mpc&is-active=true&cur-mag=20"

        const val CATALOG_OBJECT_COLUMNS_SQL =
            "id, catalog_label, catalog_entry, ra, dec, constellation, object_type, " +
                    "broad_category, magnitude, surface_brightness, angular_size, common_name, " +
                    "description, copyright, distance"

        private const val DEG_TO_RAD = PI / 180.0
        internal val DYNAMIC_CATALOG_LABELS = setOf("Solar System", "Planet", "Asteroid", "Comet")
        private val SEARCH_QUERY_PREFIX_REGEX = Regex("^(sh2|ta2|[a-z]+)\\s*(\\d+)?$")
        private val COMPACT_DESIGNATION_REGEX =
            Regex("^(sh2|ta2|[a-z]+)-?(\\d[a-z0-9-]*)$", RegexOption.IGNORE_CASE)
        private val COMET_LINE_WHITESPACE_REGEX = Regex("\\s+")
        private val SEARCH_CATALOG_ALIASES = mapOf(
            "messier" to "M", "m" to "M",
            "caldwell" to "C", "c" to "C",
            "collinder" to "Col", "col" to "Col", "cr" to "Col",
            "ngc" to "NGC",
            "ic" to "IC",
            "herschel" to "H", "h" to "H",
            "barnard" to "B", "b" to "B",
            "sharpless" to "Sh2", "sh2" to "Sh2", "sh" to "Sh2",
            "ta2" to "Ta2", "taas" to "Ta2",
            "abell" to "Abl", "abl" to "Abl",
            "arp" to "Arp",
            "saa" to "SaA",
            "sam" to "SaM",
            "sar" to "SaR",
            "har" to "Har",
            "rds" to "RDS",
            "egc" to "EGC",
        )
        private val EXTRA_DESIGNATION_LABELS = mapOf(
            "str" to "Str",
            "planet" to "Planet",
            "asteroid" to "Asteroid",
            "comet" to "Comet",
        )

        private fun normalizeCatalogEntry(rawEntry: String): String =
            if (rawEntry.all { it.isDigit() }) rawEntry.trimStart('0').ifEmpty { "0" } else rawEntry

        /**
         * Splits a catalog designation (compact like `M31`/`NGC224`/`Col399`/`Sh2-155` or spaced
         * like `Sh2 155`/`B 33`/`RDS STF 738`/`Comet 1P/Halley`) into `(catalogLabel, catalogEntry)`.
         * Returns `null` for unprefixed names (e.g. `Jupiter`, `Sirius`) or blank input.
         */
        fun parseCatalogDesignation(raw: String): Pair<String, String>? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            if (trimmed.startsWith("Solar System ", ignoreCase = true)) {
                val entry = trimmed.substring("Solar System ".length).trim()
                return if (entry.isNotEmpty()) "Solar System" to entry else null
            }

            val spaceIdx = trimmed.indexOf(' ')
            if (spaceIdx > 0) {
                val prefix = trimmed.take(spaceIdx).lowercase()
                val rawEntry = trimmed.substring(spaceIdx + 1).trim()
                val label = SEARCH_CATALOG_ALIASES[prefix] ?: EXTRA_DESIGNATION_LABELS[prefix]
                if (label != null && rawEntry.isNotEmpty()) {
                    return label to normalizeCatalogEntry(rawEntry)
                }
            }

            val compact = COMPACT_DESIGNATION_REGEX.matchEntire(trimmed)
            if (compact != null) {
                val label = SEARCH_CATALOG_ALIASES[compact.groupValues[1].lowercase()]
                val rawEntry = compact.groupValues[2]
                if (label != null && rawEntry.isNotEmpty()) {
                    return label to normalizeCatalogEntry(rawEntry)
                }
            }

            return null
        }

        fun isDynamicObject(obj: CatalogObject): Boolean =
            obj.id <= 0 || obj.catalogLabel in DYNAMIC_CATALOG_LABELS

        // 2. Search & Proximity SQL Builders
        fun buildCoLocatedQuerySql(): String =
            "SELECT $CATALOG_OBJECT_COLUMNS_SQL FROM catalog " +
                    "WHERE ra = ? AND dec = ? AND object_type = ? AND id != ? " +
                    "ORDER BY ${buildPriorityCaseSql()} ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC;"

        fun buildDesignationsQuerySql(pairCount: Int): String {
            require(pairCount > 0) { "pairCount must be positive" }
            val clauses = List(pairCount) { "(catalog_label = ? AND catalog_entry = ?)" }
                .joinToString(" OR ")
            return "SELECT $CATALOG_OBJECT_COLUMNS_SQL FROM catalog WHERE $clauses;"
        }

        /** Builds the SQLite WHERE clause and bound parameters for [searchObjects]. */
        fun buildSearchSelectionSql(
            searchQuery: String,
            catalogFilter: String?,
            magLimit: Double?,
            includeNullMagnitude: Boolean,
            typeFilters: Set<String>?,
            starFilter: Boolean?,
            visibilityFilter: String?,
            lat: Double?,
            lon: Double?,
            targetRa: Double?,
            targetDec: Double?,
            maxDistanceDegrees: Double?,
            currentTimeMs: Long = Clock.System.now().toEpochMilliseconds(),
        ): Pair<String, List<String>> {
            val args = mutableListOf<String>()
            val clauses = mutableListOf("1=1")

            buildSearchClauseSql(searchQuery, args)?.let { clauses.add(it) }

            val radius =
                maxDistanceDegrees ?: if (catalogFilter == "Nearest Objects") 10.0 else null
            if (radius != null && targetRa != null && targetDec != null) {
                val proximityClause = buildSphericalProximityClauseSql(
                    targetRa = targetRa,
                    targetDec = targetDec,
                    radiusDegrees = radius,
                )
                if (proximityClause.isNotEmpty()) {
                    clauses.add(proximityClause)
                }
            }

            if (catalogFilter != null &&
                catalogFilter != "All Objects" &&
                catalogFilter != "Solar System" &&
                catalogFilter != "Nearest Objects"
            ) {
                clauses.add("catalog_label = ?")
                args.add(catalogFilter)
            }

            if (magLimit != null && magLimit.isFinite()) {
                clauses.add(
                    if (includeNullMagnitude) {
                        "(magnitude IS NULL OR magnitude <= $magLimit)"
                    } else {
                        "(magnitude IS NOT NULL AND magnitude <= $magLimit)"
                    }
                )
            }

            if (typeFilters != null) {
                if (typeFilters.isEmpty()) {
                    clauses.add("0 = 1")
                } else {
                    val placeholders = typeFilters.joinToString(",") { "?" }
                    clauses.add("object_type IN ($placeholders)")
                    args.addAll(typeFilters)
                }
            }

            if (starFilter != null) {
                clauses.add(
                    if (starFilter) {
                        "object_type LIKE '%Star%'"
                    } else {
                        "object_type NOT LIKE '%Star%' AND object_type != 'Unknown'"
                    }
                )
            }

            if (visibilityFilter == "Above Horizon" &&
                lat != null && lon != null &&
                lat in -90.0..90.0 && lon in -180.0..180.0
            ) {
                val (zRaDeg, zDecDeg) = AstrometryUtils.horizontalFrame(lat, lon, currentTimeMs)
                    .zenithRaDecJ2000()
                val zRaRad = zRaDeg * DEG_TO_RAD
                val zDecRad = zDecDeg * DEG_TO_RAD
                val sinZDec = sin(zDecRad)
                val cosZDec = cos(zDecRad)
                // sin(alt) = dot(object, zenith) > 0 in J2000 coordinates.
                clauses.add("(SIN(dec * $DEG_TO_RAD) * $sinZDec + COS(dec * $DEG_TO_RAD) * $cosZDec * COS($zRaRad - ra * $DEG_TO_RAD)) > 0")
            }

            return clauses.joinToString(" AND ") to args
        }

        /** Builds the complete parameterized SELECT query and bound parameters for [searchObjects]. */
        fun buildSearchQuerySql(
            searchQuery: String,
            catalogFilter: String?,
            sortBy: String,
            magLimit: Double?,
            includeNullMagnitude: Boolean,
            typeFilters: Set<String>?,
            starFilter: Boolean?,
            visibilityFilter: String?,
            lat: Double?,
            lon: Double?,
            targetRa: Double?,
            targetDec: Double?,
            maxDistanceDegrees: Double?,
            limit: Int,
            offset: Int,
            currentTimeMs: Long = Clock.System.now().toEpochMilliseconds(),
        ): Pair<String, List<String>> {
            val (selection, args) = buildSearchSelectionSql(
                searchQuery = searchQuery,
                catalogFilter = catalogFilter,
                magLimit = magLimit,
                includeNullMagnitude = includeNullMagnitude,
                typeFilters = typeFilters,
                starFilter = starFilter,
                visibilityFilter = visibilityFilter,
                lat = lat,
                lon = lon,
                targetRa = targetRa,
                targetDec = targetDec,
                maxDistanceDegrees = maxDistanceDegrees,
                currentTimeMs = currentTimeMs,
            )
            val priorityCase = buildPriorityCaseSql()
            val orderBy = buildOrderBySql(sortBy, targetRa, targetDec)
            // SQLite bare-column selection returns the row achieving MIN(priority) per co-located group.
            val sql =
                "SELECT $CATALOG_OBJECT_COLUMNS_SQL, MIN($priorityCase) as priority " +
                        "FROM catalog WHERE $selection " +
                        "GROUP BY ra, dec, object_type " +
                        "ORDER BY $orderBy " +
                        "LIMIT $limit OFFSET $offset;"
            return sql to args
        }

        private fun buildSearchClauseSql(searchQuery: String, args: MutableList<String>): String? {
            if (searchQuery.isBlank()) return null
            val q = searchQuery.trim().lowercase()
            val match = SEARCH_QUERY_PREFIX_REGEX.matchEntire(q)
            val noSpaceQuery = searchQuery.replace(" ", "")

            var clause =
                "(catalog_label || ' ' || catalog_entry LIKE ? OR REPLACE(catalog_label || catalog_entry, ' ', '') LIKE ? OR common_name LIKE ? OR catalog_entry = ?)"
            args.add("%$searchQuery%")
            args.add("%$noSpaceQuery%")
            args.add("%$searchQuery%")
            args.add(searchQuery)

            if (match != null) {
                val label = SEARCH_CATALOG_ALIASES[match.groupValues[1]]
                val rawNum = match.groupValues[2]
                if (label != null) {
                    if (rawNum.isNotEmpty()) {
                        clause = "($clause OR (catalog_label = ? AND catalog_entry = ?))"
                        args.add(label)
                        args.add(normalizeCatalogEntry(rawNum))
                    } else {
                        clause = "($clause OR catalog_label = ?)"
                        args.add(label)
                    }
                }
            }
            return clause
        }

        private fun buildOrderBySql(sortBy: String, targetRa: Double?, targetDec: Double?): String =
            when (sortBy) {
                "distance" if targetRa != null && targetDec != null ->
                    buildSphericalDistanceOrderSql(targetRa, targetDec)

                "name" ->
                    "(common_name IS NULL OR common_name = '') ASC, common_name ASC, priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"

                "brightness" ->
                    "magnitude IS NULL ASC, magnitude ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"

                "priority" ->
                    "priority ASC, magnitude IS NULL ASC, magnitude ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"

                else ->
                    "priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            }

        private fun buildPriorityCaseSql(brightStarMagLimit: Double = BRIGHT_STAR_PRIORITY_MAG_LIMIT): String =
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

        private fun sphericalCosDistanceSql(targetRa: Double, targetDec: Double): String {
            val targetRaRad = targetRa * DEG_TO_RAD
            val targetDecRad = targetDec * DEG_TO_RAD
            val sinTargetDec = sin(targetDecRad)
            val cosTargetDec = cos(targetDecRad)
            return "(SIN(dec * $DEG_TO_RAD) * $sinTargetDec + COS(dec * $DEG_TO_RAD) * $cosTargetDec * COS(ra * $DEG_TO_RAD - $targetRaRad))"
        }

        private fun buildSphericalProximityClauseSql(
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

            // Exact spherical cap filter (with tiny epsilon for trig rounding) after the RA/Dec box pre-filter.
            val minCos = cos(radius * DEG_TO_RAD) - 1e-12
            clauses += "(${sphericalCosDistanceSql(targetRa, targetDec)} >= $minCos)"
            return clauses.joinToString(" AND ")
        }

        private fun buildSphericalDistanceOrderSql(targetRa: Double, targetDec: Double): String =
            "(1.0 - ${sphericalCosDistanceSql(targetRa, targetDec)}) ASC"

        // 3. Comet Parsing & JSON Serialization
        // Gregorian calendar date (with fractional UTC day) to Julian Date (Meeus Ch. 7).
        private fun dateToJd(year: Int, month: Int, dayFrac: Double): Double {
            var y = year
            var m = month
            if (m <= 2) {
                y -= 1
                m += 12
            }
            val a = y / 100
            val b = 2 - a + (a / 4)
            return (365.25 * (y + 4716)).toInt() + (30.6001 * (m + 1)).toInt() + dayFrac + b - 1524.5
        }

        /** Parses one MPC comet orbital-elements line, or returns null for blank/incomplete records. */
        fun parseCometLine(line: String): ParsedCometElements? {
            if (line.trim().isEmpty() || line.length < 101) return null
            val hStr = line.substring(91, 96).trim()
            val kStr = line.substring(96, 101).trim()
            if (hStr.isEmpty() || kStr.isEmpty()) return null

            val catalogId = line.take(12).trim().replace(COMET_LINE_WHITESPACE_REGEX, " ")
            val year = line.substring(14, 18).trim().toInt()
            val month = line.substring(19, 21).trim().toInt()
            val dayFrac = line.substring(22, 29).trim().toDouble()
            val tPeri = dateToJd(year, month, dayFrac)

            val q = line.substring(30, 39).trim().toDouble()
            val e = line.substring(40, 49).trim().toDouble()
            val w = line.substring(50, 59).trim().toDouble()
            val node = line.substring(60, 69).trim().toDouble()
            val incl = line.substring(70, 79).trim().toDouble()

            val h = hStr.toDouble()
            val k = kStr.toDouble()
            if (!h.isFinite() || !k.isFinite() || !tPeri.isFinite() ||
                !q.isFinite() || !e.isFinite() || !w.isFinite() ||
                !node.isFinite() || !incl.isFinite()
            ) {
                throw IllegalArgumentException("Non-finite comet orbital element")
            }

            var namePart = if (line.length > 102) line.substring(102) else ""
            val doubleSpaceIdx = namePart.indexOf("  ")
            if (doubleSpaceIdx != -1) {
                namePart = namePart.take(doubleSpaceIdx)
            }

            return ParsedCometElements(
                catalogId = catalogId,
                commonName = namePart.trim(),
                h = h,
                k = k,
                tPeri = tPeri,
                q = q,
                e = e,
                w = w,
                node = node,
                incl = incl,
            )
        }

        fun escapeJson(str: String): String = buildString(str.length) {
            for (ch in str) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) {
                        append("\\u00")
                        append(ch.code.toString(16).padStart(2, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }

        /** Parses MPC active-comet lines into a JSON array matching Rust `CometRecord`. Returns null if payload looks invalid. */
        fun parseCometLinesToJson(
            lines: List<String>,
            onParseError: ((String, Exception) -> Unit)? = null,
        ): String? {
            if (lines.isEmpty()) return null
            // Reject HTML/XML error pages or captive-portal responses.
            if (lines.any { it.trimStart().startsWith("<") }) return null
            val sb = StringBuilder("[")
            var nextId = 1
            for (line in lines) {
                val comet = try {
                    parseCometLine(line)
                } catch (ex: Exception) {
                    onParseError?.invoke(line, ex)
                    null
                } ?: continue
                if (nextId > 1) sb.append(',')
                sb.append("{\"id\":").append(nextId++)
                sb.append(",\"catalog_id\":\"").append(escapeJson(comet.catalogId)).append('"')
                sb.append(",\"common_name\":\"").append(escapeJson(comet.commonName)).append('"')
                sb.append(",\"h\":").append(comet.h)
                sb.append(",\"k\":").append(comet.k)
                sb.append(",\"t_peri\":").append(comet.tPeri)
                sb.append(",\"q\":").append(comet.q)
                sb.append(",\"e\":").append(comet.e)
                sb.append(",\"w\":").append(comet.w)
                sb.append(",\"node\":").append(comet.node)
                sb.append(",\"incl\":").append(comet.incl).append('}')
            }
            if (nextId == 1) return null
            sb.append(']')
            return sb.toString()
        }
    }

    fun getCatalogDescriptions(): Map<String, String>

    fun getCoLocatedObjects(obj: CatalogObject): List<CatalogObject> = emptyList()

    fun getObjectsByDesignations(keys: Collection<Pair<String, String>>): List<CatalogObject> =
        emptyList()

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
        offset: Int = 0,
        currentTimeMs: Long = Clock.System.now().toEpochMilliseconds()
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


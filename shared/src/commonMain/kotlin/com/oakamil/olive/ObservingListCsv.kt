// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

enum class CsvImportFormat {
    SINGLE_LIST,
    FULL_LISTS,
    OBSERVATIONS
}

data class ParsedCsvRow(
    val listName: String? = null,
    val name: String,
    val designation: String,
    val objectType: String,
    val raDeg: Double?,
    val decDeg: Double?,
    val observedAtMs: Long? = null,
    val observingNight: String? = null,
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
    val altDeg: Double? = null,
    val azDeg: Double? = null,
    val notes: String = ""
)

data class ParsedCsvImport(
    val format: CsvImportFormat,
    val rows: List<ParsedCsvRow>
)

/**
 * RFC 4180 CSV parser and format auto-detector for importing single observing lists,
 * multi-list backups, and observation logs exported by [UserDatabase].
 */
object ObservingListCsv {

    /**
     * Parses RFC 4180 CSV content into logical records, supporting UTF-8 BOM,
     * CRLF/LF/CR line endings, quoted fields with commas, doubled quotes, and
     * embedded newlines inside quoted fields.
     */
    fun parseCsvRecords(content: String): List<List<String>> {
        val text = content.removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()

        val records = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = text.length

        while (i < len) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> {
                        if (field.isEmpty()) {
                            inQuotes = true
                        } else {
                            field.append(ch)
                        }
                    }

                    ',' -> {
                        currentRow.add(field.toString())
                        field.clear()
                    }

                    '\r', '\n' -> {
                        currentRow.add(field.toString())
                        field.clear()
                        if (currentRow.any { it.isNotBlank() }) {
                            records.add(currentRow)
                        }
                        currentRow = mutableListOf()
                        if (ch == '\r' && i + 1 < len && text[i + 1] == '\n') {
                            i++
                        }
                    }

                    else -> field.append(ch)
                }
            }
            i++
        }

        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            if (currentRow.any { it.isNotBlank() }) {
                records.add(currentRow)
            }
        }

        return records
    }

    /** Reverses the leading-apostrophe formula neutralization applied by [UserDatabase.escapeCsvField]. */
    internal fun unescapeFormulaField(value: String): String {
        if (value.length < 2 || value[0] != '\'') return value
        val rest = value.substring(1)
        return when (rest[0]) {
            '=', '+', '@', '\t', '\r' -> rest
            // Negative numeric values are not apostrophe-prefixed on export, so preserve literal "'-12.3".
            '-' -> if (rest.toDoubleOrNull()?.isFinite() == true) value else rest
            else -> value
        }
    }

    /**
     * Auto-detects the CSV format (single observing list, full multi-list export, or
     * observation log) from headers and parses all valid data rows. Returns `null` if
     * the input is empty or its headers do not match any supported format.
     */
    fun parse(content: String): ParsedCsvImport? {
        val records = parseCsvRecords(content)
        if (records.isEmpty()) return null

        val headerMap = HashMap<String, Int>(records[0].size)
        records[0].forEachIndexed { idx, rawHeader ->
            val key = unescapeFormulaField(rawHeader).trim().lowercase()
            if (key.isNotEmpty() && key !in headerMap) {
                headerMap[key] = idx
            }
        }

        val timestampCol = headerMap["timestamp (utc)"] ?: headerMap["timestamp"]
        val observingNightCol = headerMap["observing night"]
        val listNameCol = headerMap["list name"]
        val nameCol = headerMap["name"] ?: headerMap["object"]
        val designationCol = headerMap["designation"]
        val typeCol = headerMap["type"]
        val raCol = headerMap["ra (j2000 deg)"] ?: headerMap["ra (deg)"] ?: headerMap["ra"]
        val decCol = headerMap["dec (j2000 deg)"] ?: headerMap["dec (deg)"] ?: headerMap["dec"]
        val latCol = headerMap["latitude"] ?: headerMap["lat"]
        val lonCol = headerMap["longitude"] ?: headerMap["lon"]
        val altCol = headerMap["alt (deg)"] ?: headerMap["alt"]
        val azCol = headerMap["az (deg)"] ?: headerMap["az"]
        val notesCol = headerMap["notes"]

        val hasObjectIdentityCols =
            nameCol != null || designationCol != null || (raCol != null && decCol != null)

        val format = when {
            timestampCol != null && hasObjectIdentityCols -> CsvImportFormat.OBSERVATIONS
            listNameCol != null && hasObjectIdentityCols -> CsvImportFormat.FULL_LISTS
            hasObjectIdentityCols -> CsvImportFormat.SINGLE_LIST
            else -> return null
        }

        fun List<String>.fieldAt(col: Int?): String {
            if (col == null || col !in indices) return ""
            return unescapeFormulaField(this[col])
        }

        fun List<String>.doubleAt(col: Int?, range: ClosedFloatingPointRange<Double>): Double? {
            val raw = fieldAt(col).trim()
            if (raw.isEmpty()) return null
            val v = raw.toDoubleOrNull() ?: return null
            return v.takeIf { it.isFinite() && it in range }
        }

        val parsedRows = mutableListOf<ParsedCsvRow>()

        for (rowIdx in 1 until records.size) {
            val row = records[rowIdx]
            val listName = if (format == CsvImportFormat.FULL_LISTS) {
                row.fieldAt(listNameCol).trim()
            } else {
                null
            }
            if (format == CsvImportFormat.FULL_LISTS && listName.isNullOrEmpty()) {
                continue
            }

            val name = row.fieldAt(nameCol).trim()
            val designation = row.fieldAt(designationCol).trim()
            val objectType = row.fieldAt(typeCol).trim()
            val raDeg = row.doubleAt(raCol, 0.0..360.0)
            val decDeg = row.doubleAt(decCol, -90.0..90.0)
            val hasValidCoords = raDeg != null && decDeg != null

            if (name.isEmpty() && designation.isEmpty() && !hasValidCoords) {
                continue
            }

            if (format == CsvImportFormat.OBSERVATIONS) {
                val rawTimestamp = row.fieldAt(timestampCol).trim()
                val observedAtMs = runCatching {
                    Instant.parse(rawTimestamp).toEpochMilliseconds()
                }.getOrNull() ?: continue
                val rawNight = row.fieldAt(observingNightCol).trim()
                val observingNight =
                    if (rawNight.length == 10 && runCatching { LocalDate.parse(rawNight) }.isSuccess) {
                        rawNight
                    } else {
                        UserDatabase.computeObservingNightKey(observedAtMs)
                    }
                val latDeg = row.doubleAt(latCol, -90.0..90.0)
                val lonDeg = row.doubleAt(lonCol, -180.0..180.0)
                val validLocation = latDeg != null && lonDeg != null
                val altDeg = row.doubleAt(altCol, -90.0..90.0)
                val azDeg = row.doubleAt(azCol, 0.0..360.0)
                val notes = row.fieldAt(notesCol)

                parsedRows.add(
                    ParsedCsvRow(
                        name = name,
                        designation = designation,
                        objectType = objectType,
                        raDeg = raDeg,
                        decDeg = decDeg,
                        observedAtMs = observedAtMs,
                        observingNight = observingNight,
                        latDeg = if (validLocation) latDeg else null,
                        lonDeg = if (validLocation) lonDeg else null,
                        altDeg = altDeg,
                        azDeg = azDeg,
                        notes = notes
                    )
                )
            } else {
                parsedRows.add(
                    ParsedCsvRow(
                        listName = listName,
                        name = name,
                        designation = designation,
                        objectType = objectType,
                        raDeg = raDeg,
                        decDeg = decDeg
                    )
                )
            }
        }

        return ParsedCsvImport(
            format = format,
            rows = parsedRows
        )
    }
}

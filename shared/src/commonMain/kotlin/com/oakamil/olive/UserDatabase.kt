// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

data class ObservingListSummary(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val sortOrder: Int
)

data class NightSessionSummary(
    val observingNight: String,
    val objectCount: Int,
    val observationCount: Int,
    val latestObservedAtMs: Long
)

data class ObservingListItemRecord(
    val id: Long,
    val listId: Long,
    val catalogLabel: String,
    val catalogEntry: String,
    val fallbackName: String,
    val objectType: String,
    val raDeg: Double,
    val decDeg: Double,
    val sortOrder: Int,
    val addedAtMs: Long
) {
    val objectKey: String get() = UserDatabase.objectKey(catalogLabel, catalogEntry)
}

data class ObservationRecord(
    val id: Long,
    val catalogLabel: String,
    val catalogEntry: String,
    val targetName: String,
    val objectType: String,
    val raDeg: Double,
    val decDeg: Double,
    val observedAtMs: Long,
    val observingNight: String,
    val latDeg: Double?,
    val lonDeg: Double?,
    val altDeg: Double?,
    val azDeg: Double?,
    val notes: String
) {
    val objectKey: String get() = UserDatabase.objectKey(catalogLabel, catalogEntry)
}

data class TargetRef(
    val catalogLabel: String,
    val catalogEntry: String,
    val name: String,
    val objectType: String,
    val raDeg: Double,
    val decDeg: Double
) {
    val objectKey: String get() = UserDatabase.objectKey(catalogLabel, catalogEntry)
}

data class StaticCoLocatedKey(
    val raDeg: Double,
    val decDeg: Double,
    val objectType: String
)

interface UserDatabase {
    companion object {
        const val USER_DB_NAME = "user_data.db"
        const val SCHEMA_VERSION = 1
        const val CUSTOM_CATALOG_LABEL = "Custom"

        val SCHEMA_STATEMENTS = listOf(
            """
            CREATE TABLE IF NOT EXISTS observing_lists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL
            );
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS observing_list_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                list_id INTEGER NOT NULL REFERENCES observing_lists(id) ON DELETE CASCADE,
                catalog_label TEXT NOT NULL,
                catalog_entry TEXT NOT NULL,
                fallback_name TEXT NOT NULL,
                object_type TEXT NOT NULL,
                ra_deg REAL NOT NULL,
                dec_deg REAL NOT NULL,
                sort_order INTEGER NOT NULL,
                added_at_ms INTEGER NOT NULL,
                UNIQUE(list_id, catalog_label, catalog_entry)
            );
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_list_items_list_order ON observing_list_items(list_id, sort_order);",
            """
            CREATE TABLE IF NOT EXISTS observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                catalog_label TEXT NOT NULL,
                catalog_entry TEXT NOT NULL,
                target_name TEXT NOT NULL,
                object_type TEXT NOT NULL,
                ra_deg REAL NOT NULL,
                dec_deg REAL NOT NULL,
                observed_at_ms INTEGER NOT NULL,
                observing_night TEXT NOT NULL,
                lat_deg REAL,
                lon_deg REAL,
                alt_deg REAL,
                az_deg REAL,
                notes TEXT NOT NULL DEFAULT ''
            );
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_obs_night_time ON observations(observing_night DESC, observed_at_ms DESC);",
            "CREATE INDEX IF NOT EXISTS idx_obs_key ON observations(catalog_label, catalog_entry);"
        )

        const val SQL_GET_MANUAL_LISTS = """
            SELECT l.id, l.name, COUNT(i.id) AS item_count, l.sort_order
            FROM observing_lists l
            LEFT JOIN observing_list_items i ON i.list_id = l.id
            GROUP BY l.id
            ORDER BY l.sort_order ASC, l.id ASC;
        """

        const val SQL_GET_NEXT_LIST_SORT_ORDER =
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM observing_lists;"

        const val SQL_INSERT_LIST =
            "INSERT INTO observing_lists (name, sort_order, created_at_ms) VALUES (?, ?, ?);"

        const val SQL_RENAME_LIST =
            "UPDATE observing_lists SET name = ? WHERE id = ?;"

        const val SQL_DELETE_LIST =
            "DELETE FROM observing_lists WHERE id = ?;"

        const val SQL_UPDATE_LIST_ORDER =
            "UPDATE observing_lists SET sort_order = ? WHERE id = ?;"

        const val SQL_GET_LIST_ITEMS = """
            SELECT id, list_id, catalog_label, catalog_entry, fallback_name, object_type,
                   ra_deg, dec_deg, sort_order, added_at_ms
            FROM observing_list_items
            WHERE list_id = ?
            ORDER BY sort_order ASC, id ASC;
        """

        const val SQL_GET_LIST_IDS_FOR_OBJECT =
            "SELECT list_id FROM observing_list_items WHERE catalog_label = ? AND catalog_entry = ?;"

        const val SQL_GET_NEXT_ITEM_SORT_ORDER =
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM observing_list_items WHERE list_id = ?;"

        const val SQL_INSERT_LIST_ITEM = """
            INSERT OR IGNORE INTO observing_list_items (
                list_id, catalog_label, catalog_entry, fallback_name, object_type,
                ra_deg, dec_deg, sort_order, added_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
        """

        const val SQL_DELETE_LIST_ITEM =
            "DELETE FROM observing_list_items WHERE list_id = ? AND catalog_label = ? AND catalog_entry = ?;"

        const val SQL_UPDATE_ITEM_ORDER =
            "UPDATE observing_list_items SET sort_order = ? WHERE list_id = ? AND id = ?;"

        const val SQL_GET_NIGHT_SESSIONS = """
            SELECT observing_night,
                   COUNT(DISTINCT catalog_label || '|' || catalog_entry) AS object_count,
                   COUNT(*) AS observation_count,
                   MAX(observed_at_ms) AS latest_ms
            FROM observations
            GROUP BY observing_night
            ORDER BY observing_night DESC;
        """

        const val SQL_GET_OBSERVATIONS_FOR_NIGHT = """
            SELECT id, catalog_label, catalog_entry, target_name, object_type,
                   ra_deg, dec_deg, observed_at_ms, observing_night,
                   lat_deg, lon_deg, alt_deg, az_deg, notes
            FROM observations
            WHERE observing_night = ?
            ORDER BY observed_at_ms ASC, id ASC;
        """

        const val SQL_GET_ALL_OBSERVATIONS = """
            SELECT id, catalog_label, catalog_entry, target_name, object_type,
                   ra_deg, dec_deg, observed_at_ms, observing_night,
                   lat_deg, lon_deg, alt_deg, az_deg, notes
            FROM observations
            ORDER BY observed_at_ms DESC, id DESC;
        """

        const val SQL_INSERT_OBSERVATION = """
            INSERT INTO observations (
                catalog_label, catalog_entry, target_name, object_type,
                ra_deg, dec_deg, observed_at_ms, observing_night,
                lat_deg, lon_deg, alt_deg, az_deg, notes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """

        const val SQL_UPDATE_OBSERVATION_NOTES =
            "UPDATE observations SET notes = ? WHERE id = ?;"

        const val SQL_DELETE_OBSERVATION =
            "DELETE FROM observations WHERE id = ?;"

        fun objectKey(catalogLabel: String, catalogEntry: String): String =
            "$catalogLabel|$catalogEntry"

        fun targetRefFrom(
            catalogObject: CatalogObject?,
            coords: Pair<Double, Double>,
            fallbackTitle: String
        ): TargetRef {
            if (catalogObject != null) {
                return TargetRef(
                    catalogLabel = catalogObject.catalogLabel,
                    catalogEntry = catalogObject.catalogEntry,
                    name = catalogObject.displayName,
                    objectType = catalogObject.objectType,
                    raDeg = catalogObject.ra,
                    decDeg = catalogObject.dec
                )
            }
            val entry = "${pureFormat("%.4f", coords.first)},${pureFormat("%.4f", coords.second)}"
            return TargetRef(
                catalogLabel = CUSTOM_CATALOG_LABEL,
                catalogEntry = entry,
                name = fallbackTitle,
                objectType = CUSTOM_CATALOG_LABEL,
                raDeg = coords.first,
                decDeg = coords.second
            )
        }

        /**
         * Returns the universal `YYYY-MM-DD` key for the beginning date of the local
         * astronomical observing night (noon-to-noon local time, DST-safe).
         */
        fun computeObservingNightKey(
            timestampMs: Long = Clock.System.now().toEpochMilliseconds(),
            timeZone: TimeZone = TimeZone.currentSystemDefault()
        ): String {
            val local = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(timeZone)
            val nightDate = if (local.hour < 12) {
                local.date.minus(1, DateTimeUnit.DAY)
            } else {
                local.date
            }
            val year = nightDate.year.toString().padStart(4, '0')
            val month = nightDate.monthNumber.toString().padStart(2, '0')
            val day = nightDate.dayOfMonth.toString().padStart(2, '0')
            return "$year-$month-$day"
        }

        /** Formats local date and time as `YYYY-MM-DD HH:MM` (24h) or `YYYY-MM-DD h:MM AM/PM` (12h). */
        fun formatObservationTimestamp(
            timestampMs: Long,
            is24Hour: Boolean = true,
            timeZone: TimeZone = TimeZone.currentSystemDefault(),
            amLabel: String = "AM",
            pmLabel: String = "PM"
        ): String {
            val dt = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(timeZone)
            val year = dt.year.toString().padStart(4, '0')
            val month = dt.monthNumber.toString().padStart(2, '0')
            val day = dt.dayOfMonth.toString().padStart(2, '0')
            return "$year-$month-$day ${
                formatObservationTimeOnly(
                    timestampMs = timestampMs,
                    is24Hour = is24Hour,
                    timeZone = timeZone,
                    amLabel = amLabel,
                    pmLabel = pmLabel
                )
            }"
        }

        /** Formats local clock time as `HH:MM` (24h) or `h:MM AM/PM` (12h). */
        fun formatObservationTimeOnly(
            timestampMs: Long,
            is24Hour: Boolean = true,
            timeZone: TimeZone = TimeZone.currentSystemDefault(),
            amLabel: String = "AM",
            pmLabel: String = "PM"
        ): String {
            val dt = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(timeZone)
            val minute = dt.minute.toString().padStart(2, '0')
            return if (is24Hour) {
                val hour = dt.hour.toString().padStart(2, '0')
                "$hour:$minute"
            } else {
                val amPm = if (dt.hour < 12) amLabel else pmLabel
                val hour12 = when (val h = dt.hour % 12) {
                    0 -> 12
                    else -> h
                }
                "$hour12:$minute $amPm"
            }
        }

        /** Formats exact UTC timestamp in ISO-8601 (`YYYY-MM-DDTHH:MM:SSZ`) for CSV export. */
        fun formatObservationUtcIso(timestampMs: Long): String {
            val dt = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(TimeZone.UTC)
            val year = dt.year.toString().padStart(4, '0')
            val month = dt.monthNumber.toString().padStart(2, '0')
            val day = dt.dayOfMonth.toString().padStart(2, '0')
            val hour = dt.hour.toString().padStart(2, '0')
            val minute = dt.minute.toString().padStart(2, '0')
            val second = dt.second.toString().padStart(2, '0')
            return "${year}-${month}-${day}T${hour}:${minute}:${second}Z"
        }

        /**
         * Matches observations belonging to [target] directly or via strictly co-located static
         * catalog aliases (exact RA, Dec, and object type, e.g. M31 and NGC 224).
         */
        fun filterObservationsForTarget(
            allObservations: List<ObservationRecord>,
            target: TargetRef,
            coLocated: List<CatalogObject>
        ): List<ObservationRecord> {
            val directKeys = buildSet {
                add(target.objectKey)
                for (sibling in coLocated) {
                    add(objectKey(sibling.catalogLabel, sibling.catalogEntry))
                }
            }
            return allObservations.filter { obs -> obs.objectKey in directKeys }
        }

        fun matchesObservedSet(
            obj: CatalogObject,
            observedKeys: Set<String>,
            observedStaticCoLocatedKeys: Set<StaticCoLocatedKey>
        ): Boolean {
            if (objectKey(obj.catalogLabel, obj.catalogEntry) in observedKeys) return true
            if (CatalogDatabase.isDynamicObject(obj)) return false
            return StaticCoLocatedKey(
                obj.ra,
                obj.dec,
                obj.objectType
            ) in observedStaticCoLocatedKeys
        }

        fun escapeCsvField(value: String): String {
            val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
            if (!needsQuotes) return value
            return "\"${value.replace("\"", "\"\"")}\""
        }

        fun sanitizeExportFileName(rawName: String): String {
            val cleaned = rawName.trim()
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_', '.')
            return cleaned.ifEmpty { "observing_list" }
        }

        fun buildManualListCsv(entries: List<ResolvedListEntry>): String {
            val sb = StringBuilder()
            sb.appendLine("Name,Designation,Type,RA (J2000 deg),Dec (J2000 deg),Magnitude,Angular Size,Constellation")
            for (entry in entries) {
                val obj = entry.catalogObject
                val fields = listOf(
                    obj.displayName,
                    if (obj.catalogLabel == CUSTOM_CATALOG_LABEL) "" else obj.designation,
                    obj.objectType,
                    pureFormat("%.5f", obj.ra),
                    pureFormat("%.5f", obj.dec),
                    obj.magnitude?.let { pureFormat("%.2f", it) }.orEmpty(),
                    obj.angularSize.orEmpty(),
                    obj.constellation.orEmpty()
                )
                sb.appendLine(fields.joinToString(",") { escapeCsvField(it) })
            }
            return sb.toString()
        }

        fun buildNightObservationsCsv(
            observations: List<ObservationRecord>
        ): String {
            val sb = StringBuilder()
            sb.appendLine("Timestamp (UTC),Observing Night,Object,Designation,Type,RA (J2000 deg),Dec (J2000 deg),Latitude,Longitude,Alt (deg),Az (deg),Notes")
            for (obs in observations) {
                val designation = if (obs.catalogLabel == CUSTOM_CATALOG_LABEL) {
                    ""
                } else {
                    CatalogObject.formatDesignation(obs.catalogLabel, obs.catalogEntry)
                }
                val fields = listOf(
                    formatObservationUtcIso(obs.observedAtMs),
                    obs.observingNight,
                    obs.targetName,
                    designation,
                    obs.objectType,
                    pureFormat("%.5f", obs.raDeg),
                    pureFormat("%.5f", obs.decDeg),
                    obs.latDeg?.let { pureFormat("%.4f", it) }.orEmpty(),
                    obs.lonDeg?.let { pureFormat("%.4f", it) }.orEmpty(),
                    obs.altDeg?.let { pureFormat("%.1f", it) }.orEmpty(),
                    obs.azDeg?.let { pureFormat("%.1f", it) }.orEmpty(),
                    obs.notes
                )
                sb.appendLine(fields.joinToString(",") { escapeCsvField(it) })
            }
            return sb.toString()
        }
    }

    fun getManualLists(): List<ObservingListSummary>
    fun createManualList(
        name: String,
        createdAtMs: Long = Clock.System.now().toEpochMilliseconds()
    ): Long

    fun renameManualList(listId: Long, newName: String)
    fun deleteManualList(listId: Long)
    fun reorderManualLists(orderedListIds: List<Long>)

    fun getListItems(listId: Long): List<ObservingListItemRecord>
    fun getListIdsContainingObject(catalogLabel: String, catalogEntry: String): Set<Long>
    fun addObjectToList(
        listId: Long,
        target: TargetRef,
        addedAtMs: Long = Clock.System.now().toEpochMilliseconds()
    ): Boolean

    fun removeObjectFromList(listId: Long, catalogLabel: String, catalogEntry: String)
    fun reorderListItems(listId: Long, orderedItemIds: List<Long>)

    fun getNightSessions(): List<NightSessionSummary>
    fun getObservationsForNight(observingNight: String): List<ObservationRecord>
    fun getAllObservations(): List<ObservationRecord>
    fun insertObservation(
        target: TargetRef,
        observedAtMs: Long,
        observingNight: String,
        latDeg: Double?,
        lonDeg: Double?,
        altDeg: Double?,
        azDeg: Double?,
        notes: String
    ): Long

    fun updateObservationNotes(observationId: Long, notes: String)
    fun deleteObservation(observationId: Long)

    fun close() {}
}

object NoOpUserDatabase : UserDatabase {
    override fun getManualLists(): List<ObservingListSummary> = emptyList()
    override fun createManualList(name: String, createdAtMs: Long): Long = -1L
    override fun renameManualList(listId: Long, newName: String) {}
    override fun deleteManualList(listId: Long) {}
    override fun reorderManualLists(orderedListIds: List<Long>) {}
    override fun getListItems(listId: Long): List<ObservingListItemRecord> = emptyList()
    override fun getListIdsContainingObject(catalogLabel: String, catalogEntry: String): Set<Long> =
        emptySet()

    override fun addObjectToList(listId: Long, target: TargetRef, addedAtMs: Long): Boolean = false
    override fun removeObjectFromList(listId: Long, catalogLabel: String, catalogEntry: String) {}
    override fun reorderListItems(listId: Long, orderedItemIds: List<Long>) {}
    override fun getNightSessions(): List<NightSessionSummary> = emptyList()
    override fun getObservationsForNight(observingNight: String): List<ObservationRecord> =
        emptyList()

    override fun getAllObservations(): List<ObservationRecord> = emptyList()
    override fun insertObservation(
        target: TargetRef,
        observedAtMs: Long,
        observingNight: String,
        latDeg: Double?,
        lonDeg: Double?,
        altDeg: Double?,
        azDeg: Double?,
        notes: String
    ): Long = -1L

    override fun updateObservationNotes(observationId: Long, notes: String) {}
    override fun deleteObservation(observationId: Long) {}
}

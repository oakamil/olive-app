// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import com.oakamil.olive.cinterop.SQLITE_DONE
import com.oakamil.olive.cinterop.SQLITE_NULL
import com.oakamil.olive.cinterop.SQLITE_OK
import com.oakamil.olive.cinterop.SQLITE_OPEN_CREATE
import com.oakamil.olive.cinterop.SQLITE_OPEN_FULLMUTEX
import com.oakamil.olive.cinterop.SQLITE_OPEN_READWRITE
import com.oakamil.olive.cinterop.SQLITE_ROW
import com.oakamil.olive.cinterop.olive_sqlite3_bind_text
import com.oakamil.olive.cinterop.sqlite3_bind_double
import com.oakamil.olive.cinterop.sqlite3_bind_int
import com.oakamil.olive.cinterop.sqlite3_bind_int64
import com.oakamil.olive.cinterop.sqlite3_bind_null
import com.oakamil.olive.cinterop.sqlite3_changes
import com.oakamil.olive.cinterop.sqlite3_close_v2
import com.oakamil.olive.cinterop.sqlite3_column_double
import com.oakamil.olive.cinterop.sqlite3_column_int
import com.oakamil.olive.cinterop.sqlite3_column_int64
import com.oakamil.olive.cinterop.sqlite3_column_text
import com.oakamil.olive.cinterop.sqlite3_column_type
import com.oakamil.olive.cinterop.sqlite3_errmsg
import com.oakamil.olive.cinterop.sqlite3_exec
import com.oakamil.olive.cinterop.sqlite3_finalize
import com.oakamil.olive.cinterop.sqlite3_last_insert_rowid
import com.oakamil.olive.cinterop.sqlite3_open_v2
import com.oakamil.olive.cinterop.sqlite3_prepare_v2
import com.oakamil.olive.cinterop.sqlite3_step
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * Read-write SQLite [UserDatabase] stored under `Application Support/<USER_DB_NAME>`.
 *
 * Serializes all queries and transactions with a recursive [NativeMutex], enforces
 * `PRAGMA foreign_keys = ON`, and applies [UserDatabase.SCHEMA_STATEMENTS] idempotently on open.
 */
@OptIn(ExperimentalForeignApi::class)
class IosUserDatabase : UserDatabase {
    companion object {
        private const val TAG = "IosUserDatabase"
    }

    private val mutex = NativeMutex()
    private var db: CPointer<sqlite3>? = null

    // Guarded by mutex; prevents nested BEGIN IMMEDIATE when helper transactions run inside runInTransaction.
    private var inTransaction = false

    private fun resolveDbPath(): String? {
        val fm = NSFileManager.defaultManager
        val appSupportUrl = fm.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null
        ) ?: return null
        val dirPath = appSupportUrl.path ?: return null
        return "$dirPath/${UserDatabase.USER_DB_NAME}"
    }

    private fun openDatabase(): CPointer<sqlite3>? {
        db?.let { return it }
        val path = resolveDbPath() ?: return null
        memScoped {
            val ppDb = alloc<CPointerVar<sqlite3>>()
            val flags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX
            val rc = sqlite3_open_v2(path, ppDb.ptr, flags, null)
            if (rc != SQLITE_OK) {
                Log.e(TAG, "Failed to open user database at $path, rc=$rc")
                ppDb.value?.let { sqlite3_close_v2(it) }
                return null
            }
            val database = ppDb.value ?: return null
            execSimple(database, "PRAGMA foreign_keys = ON;")
            execSimple(database, "BEGIN IMMEDIATE;")
            var ok = true
            for (stmt in UserDatabase.SCHEMA_STATEMENTS) {
                if (!execSimple(database, stmt)) {
                    ok = false
                    break
                }
            }
            if (ok) {
                execSimple(database, "PRAGMA user_version = ${UserDatabase.SCHEMA_VERSION};")
                execSimple(database, "COMMIT;")
                db = database
            } else {
                execSimple(database, "ROLLBACK;")
                sqlite3_close_v2(database)
                return null
            }
        }
        return db
    }

    override fun close() {
        mutex.withLock {
            db?.let {
                sqlite3_close_v2(it)
                db = null
            }
        }
    }

    private fun execSimple(database: CPointer<sqlite3>, sql: String): Boolean {
        val rc = sqlite3_exec(database, sql, null, null, null)
        if (rc != SQLITE_OK) {
            Log.e(
                TAG,
                "sqlite3_exec failed (rc=$rc): ${sqlite3_errmsg(database)?.toKString()} [$sql]"
            )
            return false
        }
        return true
    }

    private fun columnString(stmt: CPointer<sqlite3_stmt>?, col: Int): String? {
        val ptr = sqlite3_column_text(stmt, col) ?: return null
        return ptr.reinterpret<ByteVar>().toKString()
    }

    private fun nullableDouble(stmt: CPointer<sqlite3_stmt>?, col: Int): Double? =
        if (sqlite3_column_type(stmt, col) == SQLITE_NULL) null else sqlite3_column_double(
            stmt,
            col
        )

    private fun <T> queryRows(
        database: CPointer<sqlite3>,
        sql: String,
        bind: (CPointer<sqlite3_stmt>?) -> Unit = {},
        readRow: (CPointer<sqlite3_stmt>?) -> T
    ): List<T> {
        val results = mutableListOf<T>()
        memScoped {
            val ppStmt = alloc<CPointerVar<sqlite3_stmt>>()
            if (sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null) == SQLITE_OK) {
                val stmt = ppStmt.value
                try {
                    bind(stmt)
                    var stepRc = sqlite3_step(stmt)
                    while (stepRc == SQLITE_ROW) {
                        results.add(readRow(stmt))
                        stepRc = sqlite3_step(stmt)
                    }
                    if (stepRc != SQLITE_DONE && stepRc != SQLITE_ROW) {
                        Log.e(
                            TAG,
                            "sqlite3_step failed (rc=$stepRc): ${sqlite3_errmsg(database)?.toKString()}"
                        )
                    }
                } finally {
                    sqlite3_finalize(stmt)
                }
            } else {
                Log.e(TAG, "Failed to prepare query: ${sqlite3_errmsg(database)?.toKString()}")
            }
        }
        return results
    }

    private fun executeStatement(
        database: CPointer<sqlite3>,
        sql: String,
        bind: (CPointer<sqlite3_stmt>?) -> Unit
    ): Boolean {
        memScoped {
            val ppStmt = alloc<CPointerVar<sqlite3_stmt>>()
            if (sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null) != SQLITE_OK) {
                Log.e(TAG, "Failed to prepare statement: ${sqlite3_errmsg(database)?.toKString()}")
                return false
            }
            val stmt = ppStmt.value
            return try {
                bind(stmt)
                val stepRc = sqlite3_step(stmt)
                if (stepRc != SQLITE_DONE) {
                    Log.e(
                        TAG,
                        "Statement failed (rc=$stepRc): ${sqlite3_errmsg(database)?.toKString()}"
                    )
                    false
                } else {
                    true
                }
            } finally {
                sqlite3_finalize(stmt)
            }
        }
    }

    private fun querySingleInt(
        database: CPointer<sqlite3>,
        sql: String,
        bind: (CPointer<sqlite3_stmt>?) -> Unit = {}
    ): Int = queryRows(database, sql, bind) { sqlite3_column_int(it, 0) }.firstOrNull() ?: 0

    override fun getManualLists(): List<ObservingListSummary> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptyList()
        queryRows(database, UserDatabase.SQL_GET_MANUAL_LISTS) { stmt ->
            ObservingListSummary(
                id = sqlite3_column_int64(stmt, 0),
                name = columnString(stmt, 1) ?: "",
                itemCount = sqlite3_column_int(stmt, 2),
                sortOrder = sqlite3_column_int(stmt, 3)
            )
        }
    }

    override fun createManualList(name: String, createdAtMs: Long): Long = mutex.withLock {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withLock -1L
        val database = openDatabase() ?: return@withLock -1L
        val nextOrder = querySingleInt(database, UserDatabase.SQL_GET_NEXT_LIST_SORT_ORDER)
        val ok = executeStatement(database, UserDatabase.SQL_INSERT_LIST) { stmt ->
            olive_sqlite3_bind_text(stmt, 1, trimmed)
            sqlite3_bind_int(stmt, 2, nextOrder)
            sqlite3_bind_int64(stmt, 3, createdAtMs)
        }
        if (ok) sqlite3_last_insert_rowid(database) else -1L
    }

    override fun renameManualList(listId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        mutex.withLock {
            val database = openDatabase() ?: return@withLock
            executeStatement(database, UserDatabase.SQL_RENAME_LIST) { stmt ->
                olive_sqlite3_bind_text(stmt, 1, trimmed)
                sqlite3_bind_int64(stmt, 2, listId)
            }
        }
    }

    override fun deleteManualList(listId: Long) {
        mutex.withLock {
            val database = openDatabase() ?: return@withLock
            executeStatement(database, UserDatabase.SQL_DELETE_LIST) { stmt ->
                sqlite3_bind_int64(stmt, 1, listId)
            }
        }
    }

    private fun inImmediateTransaction(
        database: CPointer<sqlite3>,
        block: () -> Boolean
    ) {
        if (inTransaction) {
            block()
            return
        }
        if (!execSimple(database, "BEGIN IMMEDIATE;")) return
        inTransaction = true
        val ok = try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Transaction failed: ${e.message}")
            false
        } finally {
            inTransaction = false
        }
        execSimple(database, if (ok) "COMMIT;" else "ROLLBACK;")
    }

    override fun <T> runInTransaction(block: () -> T): T = mutex.withLock {
        val database = openDatabase() ?: return@withLock block()
        if (inTransaction) return@withLock block()
        if (!execSimple(database, "BEGIN IMMEDIATE;")) return@withLock block()
        inTransaction = true
        try {
            val result = block()
            execSimple(database, "COMMIT;")
            result
        } catch (t: Throwable) {
            execSimple(database, "ROLLBACK;")
            throw t
        } finally {
            inTransaction = false
        }
    }

    override fun reorderManualLists(orderedListIds: List<Long>) {
        if (orderedListIds.isEmpty()) return
        mutex.withLock {
            val database = openDatabase() ?: return@withLock
            inImmediateTransaction(database) {
                orderedListIds.indices.all { index ->
                    executeStatement(database, UserDatabase.SQL_UPDATE_LIST_ORDER) { stmt ->
                        sqlite3_bind_int(stmt, 1, index)
                        sqlite3_bind_int64(stmt, 2, orderedListIds[index])
                    }
                }
            }
        }
    }

    override fun getListItems(listId: Long): List<ObservingListItemRecord> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptyList()
        queryRows(
            database,
            UserDatabase.SQL_GET_LIST_ITEMS,
            bind = { stmt -> sqlite3_bind_int64(stmt, 1, listId) }
        ) { stmt ->
            ObservingListItemRecord(
                id = sqlite3_column_int64(stmt, 0),
                listId = sqlite3_column_int64(stmt, 1),
                catalogLabel = columnString(stmt, 2) ?: "",
                catalogEntry = columnString(stmt, 3) ?: "",
                fallbackName = columnString(stmt, 4) ?: "",
                objectType = columnString(stmt, 5) ?: "",
                raDeg = sqlite3_column_double(stmt, 6),
                decDeg = sqlite3_column_double(stmt, 7),
                sortOrder = sqlite3_column_int(stmt, 8),
                addedAtMs = sqlite3_column_int64(stmt, 9)
            )
        }
    }

    override fun getListIdsContainingObject(
        catalogLabel: String,
        catalogEntry: String
    ): Set<Long> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptySet()
        queryRows(
            database,
            UserDatabase.SQL_GET_LIST_IDS_FOR_OBJECT,
            bind = { stmt ->
                olive_sqlite3_bind_text(stmt, 1, catalogLabel)
                olive_sqlite3_bind_text(stmt, 2, catalogEntry)
            }
        ) { stmt -> sqlite3_column_int64(stmt, 0) }.toSet()
    }

    override fun addObjectToList(
        listId: Long,
        target: TargetRef,
        addedAtMs: Long
    ): Boolean = mutex.withLock {
        val database = openDatabase() ?: return@withLock false
        val nextOrder =
            querySingleInt(database, UserDatabase.SQL_GET_NEXT_ITEM_SORT_ORDER) { stmt ->
                sqlite3_bind_int64(stmt, 1, listId)
            }
        val ok = executeStatement(database, UserDatabase.SQL_INSERT_LIST_ITEM) { stmt ->
            sqlite3_bind_int64(stmt, 1, listId)
            olive_sqlite3_bind_text(stmt, 2, target.catalogLabel)
            olive_sqlite3_bind_text(stmt, 3, target.catalogEntry)
            olive_sqlite3_bind_text(stmt, 4, target.name)
            olive_sqlite3_bind_text(stmt, 5, target.objectType)
            sqlite3_bind_double(stmt, 6, target.raDeg)
            sqlite3_bind_double(stmt, 7, target.decDeg)
            sqlite3_bind_int(stmt, 8, nextOrder)
            sqlite3_bind_int64(stmt, 9, addedAtMs)
        }
        ok && sqlite3_changes(database) > 0
    }

    override fun removeObjectFromList(listId: Long, catalogLabel: String, catalogEntry: String) {
        mutex.withLock {
            val database = openDatabase() ?: return@withLock
            executeStatement(database, UserDatabase.SQL_DELETE_LIST_ITEM) { stmt ->
                sqlite3_bind_int64(stmt, 1, listId)
                olive_sqlite3_bind_text(stmt, 2, catalogLabel)
                olive_sqlite3_bind_text(stmt, 3, catalogEntry)
            }
        }
    }

    override fun reorderListItems(listId: Long, orderedItemIds: List<Long>) {
        if (orderedItemIds.isEmpty()) return
        mutex.withLock {
            val database = openDatabase() ?: return@withLock
            inImmediateTransaction(database) {
                orderedItemIds.indices.all { index ->
                    executeStatement(database, UserDatabase.SQL_UPDATE_ITEM_ORDER) { stmt ->
                        sqlite3_bind_int(stmt, 1, index)
                        sqlite3_bind_int64(stmt, 2, listId)
                        sqlite3_bind_int64(stmt, 3, orderedItemIds[index])
                    }
                }
            }
        }
    }

    override fun getNightSessions(): List<NightSessionSummary> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptyList()
        queryRows(database, UserDatabase.SQL_GET_NIGHT_SESSIONS) { stmt ->
            NightSessionSummary(
                observingNight = columnString(stmt, 0) ?: "",
                objectCount = sqlite3_column_int(stmt, 1),
                observationCount = sqlite3_column_int(stmt, 2),
                latestObservedAtMs = sqlite3_column_int64(stmt, 3)
            )
        }
    }

    override fun getObservationsForNight(observingNight: String): List<ObservationRecord> =
        mutex.withLock {
            val database = openDatabase() ?: return@withLock emptyList()
            queryRows(
                database,
                UserDatabase.SQL_GET_OBSERVATIONS_FOR_NIGHT,
                bind = { stmt -> olive_sqlite3_bind_text(stmt, 1, observingNight) },
                readRow = ::readObservationRow
            )
        }

    override fun getAllObservations(): List<ObservationRecord> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptyList()
        queryRows(
            database,
            UserDatabase.SQL_GET_ALL_OBSERVATIONS,
            readRow = ::readObservationRow
        )
    }

    override fun insertObservation(
        target: TargetRef,
        observedAtMs: Long,
        observingNight: String,
        latDeg: Double?,
        lonDeg: Double?,
        altDeg: Double?,
        azDeg: Double?,
        notes: String
    ): Long = mutex.withLock {
        val database = openDatabase() ?: return@withLock -1L
        val ok = executeStatement(database, UserDatabase.SQL_INSERT_OBSERVATION) { stmt ->
            olive_sqlite3_bind_text(stmt, 1, target.catalogLabel)
            olive_sqlite3_bind_text(stmt, 2, target.catalogEntry)
            olive_sqlite3_bind_text(stmt, 3, target.name)
            olive_sqlite3_bind_text(stmt, 4, target.objectType)
            sqlite3_bind_double(stmt, 5, target.raDeg)
            sqlite3_bind_double(stmt, 6, target.decDeg)
            sqlite3_bind_int64(stmt, 7, observedAtMs)
            olive_sqlite3_bind_text(stmt, 8, observingNight)
            if (latDeg != null) sqlite3_bind_double(stmt, 9, latDeg) else sqlite3_bind_null(stmt, 9)
            if (lonDeg != null) sqlite3_bind_double(stmt, 10, lonDeg) else sqlite3_bind_null(
                stmt,
                10
            )
            if (altDeg != null) sqlite3_bind_double(stmt, 11, altDeg) else sqlite3_bind_null(
                stmt,
                11
            )
            if (azDeg != null) sqlite3_bind_double(stmt, 12, azDeg) else sqlite3_bind_null(stmt, 12)
            olive_sqlite3_bind_text(stmt, 13, notes)
        }
        if (ok) sqlite3_last_insert_rowid(database) else -1L
    }

    override fun updateObservationNotes(observationId: Long, notes: String) {
        mutex.withLock {
            val database = openDatabase() ?: return@withLock
            executeStatement(database, UserDatabase.SQL_UPDATE_OBSERVATION_NOTES) { stmt ->
                olive_sqlite3_bind_text(stmt, 1, notes)
                sqlite3_bind_int64(stmt, 2, observationId)
            }
        }
    }

    override fun deleteObservation(observationId: Long) {
        mutex.withLock {
            val database = openDatabase() ?: return@withLock
            executeStatement(database, UserDatabase.SQL_DELETE_OBSERVATION) { stmt ->
                sqlite3_bind_int64(stmt, 1, observationId)
            }
        }
    }

    private fun readObservationRow(stmt: CPointer<sqlite3_stmt>?): ObservationRecord =
        ObservationRecord(
            id = sqlite3_column_int64(stmt, 0),
            catalogLabel = columnString(stmt, 1) ?: "",
            catalogEntry = columnString(stmt, 2) ?: "",
            targetName = columnString(stmt, 3) ?: "",
            objectType = columnString(stmt, 4) ?: "",
            raDeg = sqlite3_column_double(stmt, 5),
            decDeg = sqlite3_column_double(stmt, 6),
            observedAtMs = sqlite3_column_int64(stmt, 7),
            observingNight = columnString(stmt, 8) ?: "",
            latDeg = nullableDouble(stmt, 9),
            lonDeg = nullableDouble(stmt, 10),
            altDeg = nullableDouble(stmt, 11),
            azDeg = nullableDouble(stmt, 12),
            notes = columnString(stmt, 13) ?: ""
        )
}

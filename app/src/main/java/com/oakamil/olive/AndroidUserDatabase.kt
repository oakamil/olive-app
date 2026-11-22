// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import android.content.Context
import android.database.Cursor
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase

class AndroidUserDatabase(private val context: Context) : UserDatabase {
    companion object {
        private const val TAG = "AndroidUserDatabase"
    }

    private val dbLock = Any()
    private var db: SQLiteDatabase? = null

    private fun openDatabaseLocked(): SQLiteDatabase? {
        db?.takeIf { it.isOpen }?.let { return it }
        var opened: SQLiteDatabase? = null
        return try {
            val dbFile = context.getDatabasePath(UserDatabase.USER_DB_NAME)
            dbFile.parentFile?.mkdirs()
            val database = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.OPEN_CREATE
            )
            opened = database
            database.execSQL("PRAGMA foreign_keys = ON;")
            database.beginTransaction()
            try {
                for (stmt in UserDatabase.SCHEMA_STATEMENTS) {
                    database.execSQL(stmt)
                }
                if (database.version < UserDatabase.SCHEMA_VERSION) {
                    database.version = UserDatabase.SCHEMA_VERSION
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            db = database
            database
        } catch (e: Exception) {
            runCatching { opened?.close() }
            Log.e(TAG, "Failed to open user database", e)
            null
        }
    }

    override fun close() {
        synchronized(dbLock) {
            db?.close()
            db = null
        }
    }

    override fun getManualLists(): List<ObservingListSummary> = synchronized(dbLock) {
        val database = openDatabaseLocked() ?: return emptyList()
        val results = mutableListOf<ObservingListSummary>()
        try {
            database.rawQuery(UserDatabase.SQL_GET_MANUAL_LISTS, null).use { c ->
                while (c.moveToNext()) {
                    results.add(
                        ObservingListSummary(
                            id = c.getLong(0),
                            name = c.getString(1).orEmpty(),
                            itemCount = c.getInt(2),
                            sortOrder = c.getInt(3)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load manual lists", e)
        }
        results
    }

    override fun createManualList(name: String, createdAtMs: Long): Long = synchronized(dbLock) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L
        val database = openDatabaseLocked() ?: return -1L
        try {
            val nextOrder =
                querySingleInt(database, UserDatabase.SQL_GET_NEXT_LIST_SORT_ORDER, null)
            database.compileStatement(UserDatabase.SQL_INSERT_LIST).use { stmt ->
                stmt.bindString(1, trimmed)
                stmt.bindLong(2, nextOrder.toLong())
                stmt.bindLong(3, createdAtMs)
                stmt.executeInsert()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create observing list", e)
            -1L
        }
    }

    override fun renameManualList(listId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        synchronized(dbLock) {
            val database = openDatabaseLocked() ?: return
            try {
                database.execSQL(UserDatabase.SQL_RENAME_LIST, arrayOf<Any>(trimmed, listId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename observing list $listId", e)
            }
        }
    }

    override fun deleteManualList(listId: Long) {
        synchronized(dbLock) {
            val database = openDatabaseLocked() ?: return
            try {
                database.execSQL(UserDatabase.SQL_DELETE_LIST, arrayOf<Any>(listId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete observing list $listId", e)
            }
        }
    }

    override fun reorderManualLists(orderedListIds: List<Long>) {
        if (orderedListIds.isEmpty()) return
        synchronized(dbLock) {
            val database = openDatabaseLocked() ?: return
            database.beginTransaction()
            try {
                database.compileStatement(UserDatabase.SQL_UPDATE_LIST_ORDER).use { stmt ->
                    orderedListIds.forEachIndexed { index, listId ->
                        stmt.clearBindings()
                        stmt.bindLong(1, index.toLong())
                        stmt.bindLong(2, listId)
                        stmt.executeUpdateDelete()
                    }
                }
                database.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reorder observing lists", e)
            } finally {
                database.endTransaction()
            }
        }
    }

    override fun getListItems(listId: Long): List<ObservingListItemRecord> = synchronized(dbLock) {
        val database = openDatabaseLocked() ?: return emptyList()
        val results = mutableListOf<ObservingListItemRecord>()
        try {
            database.rawQuery(UserDatabase.SQL_GET_LIST_ITEMS, arrayOf<Any>(listId)).use { c ->
                while (c.moveToNext()) {
                    results.add(
                        ObservingListItemRecord(
                            id = c.getLong(0),
                            listId = c.getLong(1),
                            catalogLabel = c.getString(2).orEmpty(),
                            catalogEntry = c.getString(3).orEmpty(),
                            fallbackName = c.getString(4).orEmpty(),
                            objectType = c.getString(5).orEmpty(),
                            raDeg = c.getDouble(6),
                            decDeg = c.getDouble(7),
                            sortOrder = c.getInt(8),
                            addedAtMs = c.getLong(9)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load items for list $listId", e)
        }
        results
    }

    override fun getListIdsContainingObject(
        catalogLabel: String,
        catalogEntry: String
    ): Set<Long> = synchronized(dbLock) {
        val database = openDatabaseLocked() ?: return emptySet()
        val ids = mutableSetOf<Long>()
        try {
            database.rawQuery(
                UserDatabase.SQL_GET_LIST_IDS_FOR_OBJECT,
                arrayOf<Any>(catalogLabel, catalogEntry)
            ).use { c ->
                while (c.moveToNext()) {
                    ids.add(c.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query lists containing $catalogLabel $catalogEntry", e)
        }
        ids
    }

    override fun addObjectToList(
        listId: Long,
        target: TargetRef,
        addedAtMs: Long
    ): Boolean = synchronized(dbLock) {
        val database = openDatabaseLocked() ?: return false
        try {
            val nextOrder = querySingleInt(
                database,
                UserDatabase.SQL_GET_NEXT_ITEM_SORT_ORDER,
                arrayOf(listId)
            )
            database.compileStatement(UserDatabase.SQL_INSERT_LIST_ITEM).use { stmt ->
                stmt.bindLong(1, listId)
                stmt.bindString(2, target.catalogLabel)
                stmt.bindString(3, target.catalogEntry)
                stmt.bindString(4, target.name)
                stmt.bindString(5, target.objectType)
                stmt.bindDouble(6, target.raDeg)
                stmt.bindDouble(7, target.decDeg)
                stmt.bindLong(8, nextOrder.toLong())
                stmt.bindLong(9, addedAtMs)
                stmt.executeInsert() != -1L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add object to list $listId", e)
            false
        }
    }

    override fun removeObjectFromList(listId: Long, catalogLabel: String, catalogEntry: String) {
        synchronized(dbLock) {
            val database = openDatabaseLocked() ?: return
            try {
                database.execSQL(
                    UserDatabase.SQL_DELETE_LIST_ITEM,
                    arrayOf<Any>(listId, catalogLabel, catalogEntry)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove object from list $listId", e)
            }
        }
    }

    override fun reorderListItems(listId: Long, orderedItemIds: List<Long>) {
        if (orderedItemIds.isEmpty()) return
        synchronized(dbLock) {
            val database = openDatabaseLocked() ?: return
            database.beginTransaction()
            try {
                database.compileStatement(UserDatabase.SQL_UPDATE_ITEM_ORDER).use { stmt ->
                    orderedItemIds.forEachIndexed { index, itemId ->
                        stmt.clearBindings()
                        stmt.bindLong(1, index.toLong())
                        stmt.bindLong(2, listId)
                        stmt.bindLong(3, itemId)
                        stmt.executeUpdateDelete()
                    }
                }
                database.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reorder items in list $listId", e)
            } finally {
                database.endTransaction()
            }
        }
    }

    override fun getNightSessions(): List<NightSessionSummary> = synchronized(dbLock) {
        val database = openDatabaseLocked() ?: return emptyList()
        val results = mutableListOf<NightSessionSummary>()
        try {
            database.rawQuery(UserDatabase.SQL_GET_NIGHT_SESSIONS, null).use { c ->
                while (c.moveToNext()) {
                    results.add(
                        NightSessionSummary(
                            observingNight = c.getString(0).orEmpty(),
                            objectCount = c.getInt(1),
                            observationCount = c.getInt(2),
                            latestObservedAtMs = c.getLong(3)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load night sessions", e)
        }
        results
    }

    override fun getObservationsForNight(observingNight: String): List<ObservationRecord> =
        synchronized(dbLock) {
            val database = openDatabaseLocked() ?: return emptyList()
            try {
                database.rawQuery(
                    UserDatabase.SQL_GET_OBSERVATIONS_FOR_NIGHT,
                    arrayOf<Any>(observingNight)
                ).use { c ->
                    readObservations(c)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load observations for $observingNight", e)
                emptyList()
            }
        }

    override fun getAllObservations(): List<ObservationRecord> = synchronized(dbLock) {
        val database = openDatabaseLocked() ?: return emptyList()
        try {
            database.rawQuery(UserDatabase.SQL_GET_ALL_OBSERVATIONS, null).use { c ->
                readObservations(c)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load all observations", e)
            emptyList()
        }
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
    ): Long = synchronized(dbLock) {
        val database = openDatabaseLocked() ?: return -1L
        try {
            database.compileStatement(UserDatabase.SQL_INSERT_OBSERVATION).use { stmt ->
                stmt.bindString(1, target.catalogLabel)
                stmt.bindString(2, target.catalogEntry)
                stmt.bindString(3, target.name)
                stmt.bindString(4, target.objectType)
                stmt.bindDouble(5, target.raDeg)
                stmt.bindDouble(6, target.decDeg)
                stmt.bindLong(7, observedAtMs)
                stmt.bindString(8, observingNight)
                if (latDeg != null) stmt.bindDouble(9, latDeg) else stmt.bindNull(9)
                if (lonDeg != null) stmt.bindDouble(10, lonDeg) else stmt.bindNull(10)
                if (altDeg != null) stmt.bindDouble(11, altDeg) else stmt.bindNull(11)
                if (azDeg != null) stmt.bindDouble(12, azDeg) else stmt.bindNull(12)
                stmt.bindString(13, notes)
                stmt.executeInsert()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert observation", e)
            -1L
        }
    }

    override fun updateObservationNotes(observationId: Long, notes: String) {
        synchronized(dbLock) {
            val database = openDatabaseLocked() ?: return
            try {
                database.execSQL(
                    UserDatabase.SQL_UPDATE_OBSERVATION_NOTES,
                    arrayOf<Any>(notes, observationId)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update observation $observationId", e)
            }
        }
    }

    override fun deleteObservation(observationId: Long) {
        synchronized(dbLock) {
            val database = openDatabaseLocked() ?: return
            try {
                database.execSQL(UserDatabase.SQL_DELETE_OBSERVATION, arrayOf<Any>(observationId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete observation $observationId", e)
            }
        }
    }

    override fun <T> runInTransaction(block: () -> T): T = synchronized(dbLock) {
        val database = openDatabaseLocked() ?: return@synchronized block()
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    private fun querySingleInt(database: SQLiteDatabase, sql: String, args: Array<Any>?): Int =
        database.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun readObservations(c: Cursor): List<ObservationRecord> {
        val results = mutableListOf<ObservationRecord>()
        while (c.moveToNext()) {
            results.add(
                ObservationRecord(
                    id = c.getLong(0),
                    catalogLabel = c.getString(1).orEmpty(),
                    catalogEntry = c.getString(2).orEmpty(),
                    targetName = c.getString(3).orEmpty(),
                    objectType = c.getString(4).orEmpty(),
                    raDeg = c.getDouble(5),
                    decDeg = c.getDouble(6),
                    observedAtMs = c.getLong(7),
                    observingNight = c.getString(8).orEmpty(),
                    latDeg = if (c.isNull(9)) null else c.getDouble(9),
                    lonDeg = if (c.isNull(10)) null else c.getDouble(10),
                    altDeg = if (c.isNull(11)) null else c.getDouble(11),
                    azDeg = if (c.isNull(12)) null else c.getDouble(12),
                    notes = c.getString(13).orEmpty()
                )
            )
        }
        return results
    }
}

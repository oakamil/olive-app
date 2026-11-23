// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import com.oakamil.olive.cinterop.SQLITE_DONE
import com.oakamil.olive.cinterop.SQLITE_FLOAT
import com.oakamil.olive.cinterop.SQLITE_INTEGER
import com.oakamil.olive.cinterop.SQLITE_NULL
import com.oakamil.olive.cinterop.SQLITE_OK
import com.oakamil.olive.cinterop.SQLITE_OPEN_FULLMUTEX
import com.oakamil.olive.cinterop.SQLITE_OPEN_READONLY
import com.oakamil.olive.cinterop.SQLITE_OPEN_URI
import com.oakamil.olive.cinterop.SQLITE_ROW
import com.oakamil.olive.cinterop.olive_register_sqlite_math_functions
import com.oakamil.olive.cinterop.olive_sqlite3_bind_text
import com.oakamil.olive.cinterop.sqlite3_bind_double
import com.oakamil.olive.cinterop.sqlite3_bind_int
import com.oakamil.olive.cinterop.sqlite3_close_v2
import com.oakamil.olive.cinterop.sqlite3_column_count
import com.oakamil.olive.cinterop.sqlite3_column_double
import com.oakamil.olive.cinterop.sqlite3_column_int
import com.oakamil.olive.cinterop.sqlite3_column_int64
import com.oakamil.olive.cinterop.sqlite3_column_name
import com.oakamil.olive.cinterop.sqlite3_column_text
import com.oakamil.olive.cinterop.sqlite3_column_type
import com.oakamil.olive.cinterop.sqlite3_errmsg
import com.oakamil.olive.cinterop.sqlite3_finalize
import com.oakamil.olive.cinterop.sqlite3_open_v2
import com.oakamil.olive.cinterop.sqlite3_prepare_v2
import com.oakamil.olive.cinterop.sqlite3_step
import kotlinx.cinterop.Arena
import kotlinx.cinterop.BetaInteropApi
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
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.PTHREAD_MUTEX_RECURSIVE
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.pthread_mutexattr_t

/**
 * Recursive POSIX mutex serializing SQLite access across Kotlin/Native threads, including nested
 * transaction helpers. Intended for app-lifetime database instances.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NativeMutex {
    private val arena = Arena()
    private val mutexPtr = arena.alloc<pthread_mutex_t>()

    init {
        memScoped {
            val attr = alloc<pthread_mutexattr_t>()
            pthread_mutexattr_init(attr.ptr)
            pthread_mutexattr_settype(attr.ptr, PTHREAD_MUTEX_RECURSIVE)
            pthread_mutex_init(mutexPtr.ptr, attr.ptr)
            pthread_mutexattr_destroy(attr.ptr)
        }
    }

    inline fun <T> withLock(block: () -> T): T {
        pthread_mutex_lock(mutexPtr.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(mutexPtr.ptr)
        }
    }
}

/**
 * Read-only SQLite [CatalogDatabase] backed by the bundled `catalog.db` (`immutable=1` URI mode).
 *
 * Registers `SIN`/`COS` SQLite math functions via [olive_register_sqlite_math_functions] for
 * visibility queries and caches downloaded comet orbital elements in [KeyValueStorage].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosCatalogDatabase(
    private val storage: KeyValueStorage,
) : CatalogDatabase {

    companion object {
        private const val TAG = "IosCatalogDatabase"
    }

    private val mutex = NativeMutex()
    private var db: CPointer<sqlite3>? = null

    private fun resolveBundleResourcePath(name: String, extension: String): String? {
        val bundle = NSBundle.mainBundle
        bundle.pathForResource(name, extension)?.let { return it }
        val fileName = "$name.$extension"
        val fileManager = NSFileManager.defaultManager
        return listOfNotNull(
            "${bundle.bundlePath}/$fileName",
            bundle.resourcePath?.let { "$it/$fileName" })
            .firstOrNull { fileManager.fileExistsAtPath(it) }
    }

    private fun openDatabase(): CPointer<sqlite3>? {
        if (db != null) return db

        val path = resolveBundleResourcePath("catalog", "db") ?: return null
        val uri = NSURL.fileURLWithPath(path).absoluteString?.let { "$it?immutable=1" }
        memScoped {
            val ppDb = alloc<CPointerVar<sqlite3>>()
            val (openPath, flags) = if (uri != null) {
                uri to (SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_URI)
            } else {
                path to (SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX)
            }
            val rc = sqlite3_open_v2(openPath, ppDb.ptr, flags, null)
            if (rc == SQLITE_OK) {
                db = ppDb.value
                Log.i(TAG, "Opened read-only bundled catalog database at $path")
            } else {
                Log.e(TAG, "Failed to open catalog database at $path, rc=$rc")
            }
        }
        val openedDb = db
        if (openedDb != null) {
            val mathRc = olive_register_sqlite_math_functions(openedDb)
            if (mathRc != SQLITE_OK) {
                Log.w(TAG, "Failed to register SQLite math functions (rc=$mathRc)")
            }
        }
        return db
    }

    override fun closeDatabase() {
        mutex.withLock {
            db?.let {
                sqlite3_close_v2(it)
                db = null
            }
        }
    }

    override fun getBspPath(): String =
        resolveBundleResourcePath("de421", "bsp") ?: ""

    private fun columnString(stmt: CPointer<sqlite3_stmt>?, col: Int): String? {
        val ptr = sqlite3_column_text(stmt, col) ?: return null
        return ptr.reinterpret<ByteVar>().toKString()
    }

    override fun getCatalogDescriptions(): Map<String, String> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptyMap()
        val result = mutableMapOf<String, String>()

        memScoped {
            val ppStmt = alloc<CPointerVar<sqlite3_stmt>>()
            val sql = "SELECT catalog_label, description FROM catalog_descriptions;"
            if (sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null) == SQLITE_OK) {
                val stmt = ppStmt.value
                try {
                    while (sqlite3_step(stmt) == SQLITE_ROW) {
                        val label = columnString(stmt, 0) ?: ""
                        val desc = columnString(stmt, 1) ?: ""
                        if (label.isNotEmpty()) {
                            result[label] = desc
                        }
                    }
                } finally {
                    sqlite3_finalize(stmt)
                }
            } else {
                Log.e(TAG, "Failed to prepare getCatalogDescriptions statement")
            }
        }
        result
    }

    override fun getAsteroidsJson(): String = mutex.withLock {
        val database = openDatabase() ?: return@withLock "[]"
        val sb = StringBuilder("[")
        var first = true

        memScoped {
            val ppStmt = alloc<CPointerVar<sqlite3_stmt>>()
            val sql = "SELECT * FROM asteroids;"
            if (sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null) == SQLITE_OK) {
                val stmt = ppStmt.value
                try {
                    val colCount = sqlite3_column_count(stmt)
                    val colNames = Array(colCount) { i ->
                        sqlite3_column_name(stmt, i)?.toKString() ?: "col_$i"
                    }
                    while (sqlite3_step(stmt) == SQLITE_ROW) {
                        if (!first) sb.append(',')
                        first = false
                        sb.append('{')
                        for (i in 0 until colCount) {
                            if (i > 0) sb.append(',')
                            sb.append('"').append(colNames[i]).append("\":")
                            when (sqlite3_column_type(stmt, i)) {
                                SQLITE_NULL -> sb.append("null")
                                SQLITE_INTEGER -> sb.append(sqlite3_column_int64(stmt, i))
                                SQLITE_FLOAT -> sb.append(sqlite3_column_double(stmt, i))
                                else -> {
                                    val text = columnString(stmt, i) ?: ""
                                    sb.append('"').append(CatalogDatabase.escapeJson(text))
                                        .append('"')
                                }
                            }
                        }
                        sb.append('}')
                    }
                } finally {
                    sqlite3_finalize(stmt)
                }
            } else {
                Log.e(TAG, "Failed to prepare getAsteroidsJson statement")
            }
        }
        sb.append(']')
        sb.toString()
    }

    override fun getCometsJson(): String =
        storage.getString(CatalogDatabase.COMETS_JSON_STORAGE_KEY) ?: "[]"

    override fun refreshComets(): Boolean {
        try {
            val url = NSURL.URLWithString(CatalogDatabase.COMETS_FEED_URL) ?: return false
            val request = NSMutableURLRequest.requestWithURL(url).apply {
                setHTTPMethod("GET")
                setValue("Mozilla/5.0", forHTTPHeaderField = "User-Agent")
                setTimeoutInterval(15.0)
            }

            val sem = dispatch_semaphore_create(0)
            val resultHolder = arrayOfNulls<NSData>(1)
            var networkSuccess = false

            val session = NSURLSession.sharedSession
            val task = session.dataTaskWithRequest(request) { data, response, error ->
                if (error == null && data != null) {
                    val httpResponse = response as? NSHTTPURLResponse
                    val statusCode = httpResponse?.statusCode?.toInt() ?: 200
                    if (statusCode in 200..299) {
                        resultHolder[0] = data
                        networkSuccess = true
                    }
                }
                dispatch_semaphore_signal(sem)
            }
            task.resume()

            val timeout = dispatch_time(DISPATCH_TIME_NOW, 15_000_000_000L)
            if (dispatch_semaphore_wait(sem, timeout) != 0L) {
                task.cancel()
                Log.e(TAG, "Timed out downloading comets")
                return false
            }

            val data = resultHolder[0]
            if (!networkSuccess || data == null) {
                Log.e(TAG, "Network fetch failed for comets")
                return false
            }

            val text = NSString.create(data, NSUTF8StringEncoding)?.toString()
                ?: return false

            val json = CatalogDatabase.parseCometLinesToJson(text.lines()) { line, ex ->
                Log.e(TAG, "Error parsing comet line: $line: ${ex.message}")
            } ?: run {
                Log.w(TAG, "Invalid or empty comet feed payload")
                return false
            }

            storage.putString(CatalogDatabase.COMETS_JSON_STORAGE_KEY, json)
            Log.i(TAG, "Successfully refreshed comets")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing comets: ${e.message}")
            return false
        }
    }

    override fun searchObjects(
        searchQuery: String,
        catalogFilter: String?,
        sortBy: String,
        magLimit: Double?,
        includeNullMagnitude: Boolean,
        typeFilters: Set<String>?,
        starFilter: Boolean?,
        visibilityFilter: String,
        lat: Double?,
        lon: Double?,
        targetRa: Double?,
        targetDec: Double?,
        maxDistanceDegrees: Double?,
        limit: Int,
        offset: Int,
        currentTimeMs: Long
    ): List<CatalogObject> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptyList()
        val (sql, args) = CatalogDatabase.buildSearchQuerySql(
            searchQuery = searchQuery,
            catalogFilter = catalogFilter,
            sortBy = sortBy,
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
            limit = limit,
            offset = offset,
            currentTimeMs = currentTimeMs,
        )

        executeCatalogQuery(database, sql) { stmt ->
            for ((i, arg) in args.withIndex()) {
                val bindRc = olive_sqlite3_bind_text(stmt, i + 1, arg)
                if (bindRc != SQLITE_OK) {
                    Log.e(TAG, "Failed to bind arg $i ('$arg'), rc=$bindRc")
                }
            }
        }
    }

    override fun getCoLocatedObjects(obj: CatalogObject): List<CatalogObject> = mutex.withLock {
        if (CatalogDatabase.isDynamicObject(obj)) return@withLock emptyList()
        val database = openDatabase() ?: return@withLock emptyList()

        executeCatalogQuery(database, CatalogDatabase.buildCoLocatedQuerySql()) { stmt ->
            sqlite3_bind_double(stmt, 1, obj.ra)
            sqlite3_bind_double(stmt, 2, obj.dec)
            olive_sqlite3_bind_text(stmt, 3, obj.objectType)
            sqlite3_bind_int(stmt, 4, obj.id)
        }
    }

    override fun getObjectsByDesignations(
        keys: Collection<Pair<String, String>>
    ): List<CatalogObject> = mutex.withLock {
        if (keys.isEmpty()) return@withLock emptyList()
        val database = openDatabase() ?: return@withLock emptyList()
        val results = mutableListOf<CatalogObject>()
        // Chunked to 200 pairs (400 bind parameters) to stay below SQLite's parameter limit.
        for (chunk in keys.distinct().chunked(200)) {
            val sql = CatalogDatabase.buildDesignationsQuerySql(chunk.size)
            results.addAll(
                executeCatalogQuery(database, sql) { stmt ->
                    chunk.forEachIndexed { idx, (label, entry) ->
                        olive_sqlite3_bind_text(stmt, idx * 2 + 1, label)
                        olive_sqlite3_bind_text(stmt, idx * 2 + 2, entry)
                    }
                }
            )
        }
        results
    }

    private fun executeCatalogQuery(
        database: CPointer<sqlite3>,
        sql: String,
        bind: (CPointer<sqlite3_stmt>?) -> Unit,
    ): List<CatalogObject> {
        val results = mutableListOf<CatalogObject>()
        memScoped {
            val ppStmt = alloc<CPointerVar<sqlite3_stmt>>()
            val prepRc = sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null)
            if (prepRc == SQLITE_OK) {
                val stmt = ppStmt.value
                try {
                    bind(stmt)
                    var stepRc = sqlite3_step(stmt)
                    while (stepRc == SQLITE_ROW) {
                        results.add(readCatalogObjectRow(stmt))
                        stepRc = sqlite3_step(stmt)
                    }
                    if (stepRc != SQLITE_DONE && stepRc != SQLITE_ROW) {
                        Log.e(
                            TAG,
                            "sqlite3_step ended with rc=$stepRc: ${sqlite3_errmsg(database)?.toKString()}"
                        )
                    }
                } finally {
                    sqlite3_finalize(stmt)
                }
            } else {
                Log.e(
                    TAG,
                    "Failed to prepare catalog query (rc=$prepRc, err=${
                        sqlite3_errmsg(database)?.toKString()
                    }): $sql"
                )
            }
        }
        return results
    }

    private fun nullableColumnString(stmt: CPointer<sqlite3_stmt>?, col: Int): String? =
        if (sqlite3_column_type(stmt, col) == SQLITE_NULL) null else columnString(stmt, col)

    private fun nullableColumnDouble(stmt: CPointer<sqlite3_stmt>?, col: Int): Double? =
        if (sqlite3_column_type(stmt, col) == SQLITE_NULL) null else sqlite3_column_double(
            stmt,
            col
        )

    private fun readCatalogObjectRow(stmt: CPointer<sqlite3_stmt>?): CatalogObject = CatalogObject(
        id = sqlite3_column_int(stmt, 0),
        catalogLabel = columnString(stmt, 1) ?: "",
        catalogEntry = columnString(stmt, 2) ?: "",
        ra = sqlite3_column_double(stmt, 3),
        dec = sqlite3_column_double(stmt, 4),
        constellation = nullableColumnString(stmt, 5),
        objectType = columnString(stmt, 6) ?: "",
        broadCategory = columnString(stmt, 7) ?: "",
        magnitude = nullableColumnDouble(stmt, 8),
        surfaceBrightness = nullableColumnDouble(stmt, 9),
        angularSize = nullableColumnString(stmt, 10),
        commonName = nullableColumnString(stmt, 11),
        description = nullableColumnString(stmt, 12),
        copyright = nullableColumnString(stmt, 13),
        distance = nullableColumnString(stmt, 14),
    )
}

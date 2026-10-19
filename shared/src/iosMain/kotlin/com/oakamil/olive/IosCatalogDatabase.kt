package com.oakamil.olive

import com.oakamil.olive.cinterop.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.*
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalForeignApi::class)
private class NativeMutex {
    private val arena = Arena()
    private val mutexPtr = arena.alloc<pthread_mutex_t>()

    init {
        pthread_mutex_init(mutexPtr.ptr, null)
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

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosCatalogDatabase : CatalogDatabase {

    companion object {
        private const val TAG = "IosCatalogDatabase"
    }

    private val mutex = NativeMutex()
    private var db: CPointer<cnames.structs.sqlite3>? = null

    private fun resolveDbPath(): String? {
        val fileManager = NSFileManager.defaultManager
        val bundle = NSBundle.mainBundle

        // 1. Check for writable copy in Documents directory
        val docUrls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val docDir = (docUrls.firstOrNull() as? NSURL)?.path
        if (docDir != null) {
            val writableDbPath = "$docDir/catalog.db"
            val bundleSource = listOfNotNull(
                bundle.pathForResource("catalog", "db"),
                "${bundle.bundlePath}/catalog.db",
                bundle.resourcePath?.let { "$it/catalog.db" }
            ).firstOrNull { fileManager.fileExistsAtPath(it) }

            val userDefaults = NSUserDefaults.standardUserDefaults
            val currentVersion =
                (bundle.infoDictionary?.get("CFBundleShortVersionString") as? String)
                    ?: (bundle.infoDictionary?.get("CFBundleVersion") as? String) ?: "1.0"
            val storedVersion = userDefaults.stringForKey("olive_catalog_bundle_version")

            if (bundleSource != null && (storedVersion != currentVersion || !fileManager.fileExistsAtPath(
                    writableDbPath
                ))
            ) {
                try {
                    if (fileManager.fileExistsAtPath(writableDbPath)) {
                        fileManager.removeItemAtPath(writableDbPath, null)
                    }
                    fileManager.copyItemAtPath(bundleSource, writableDbPath, null)
                    userDefaults.setObject(currentVersion, forKey = "olive_catalog_bundle_version")
                    Log.i(
                        TAG,
                        "Copied fresh bundled database from $bundleSource to $writableDbPath (version $currentVersion)"
                    )
                    return writableDbPath
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not copy database to Documents: ${e.message}")
                }
            } else if (fileManager.fileExistsAtPath(writableDbPath)) {
                return writableDbPath
            }
        }

        val candidates = listOfNotNull(
            bundle.pathForResource("catalog", "db"),
            "${bundle.bundlePath}/catalog.db",
            bundle.resourcePath?.let { "$it/catalog.db" },
            "/tmp/catalog.db"
        )

        for (candidate in candidates) {
            if (fileManager.fileExistsAtPath(candidate)) {
                return candidate
            }
        }
        return candidates.firstOrNull()
    }

    private fun openDatabase(): CPointer<cnames.structs.sqlite3>? {
        if (db != null) return db

        val path = resolveDbPath() ?: return null
        memScoped {
            val ppDb = alloc<CPointerVar<cnames.structs.sqlite3>>()
            // Try read-write first so that refreshComets() and updates can write
            val rwFlags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX
            var rc = sqlite3_open_v2(path, ppDb.ptr, rwFlags, null)
            if (rc == SQLITE_OK) {
                db = ppDb.value
                Log.i(TAG, "Opened catalog database at $path (read-write)")
                val indexes = listOf(
                    "CREATE INDEX IF NOT EXISTS idx_mag ON catalog(magnitude);",
                    "CREATE INDEX IF NOT EXISTS idx_label ON catalog(catalog_label);",
                    "CREATE INDEX IF NOT EXISTS idx_broad_category ON catalog(broad_category);",
                    "CREATE INDEX IF NOT EXISTS idx_type ON catalog(object_type);",
                    "CREATE INDEX IF NOT EXISTS idx_constellation ON catalog(constellation);"
                )
                for (sql in indexes) {
                    sqlite3_exec(db, sql, null, null, null)
                }
            } else {
                val roFlags = SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX
                rc = sqlite3_open_v2(path, ppDb.ptr, roFlags, null)
                if (rc == SQLITE_OK) {
                    db = ppDb.value
                    Log.i(TAG, "Opened catalog database at $path (read-only fallback)")
                } else {
                    Log.e(TAG, "Failed to open catalog database at $path, rc=$rc")
                }
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

    override fun getBspPath(): String {
        val fileManager = NSFileManager.defaultManager
        val bundle = NSBundle.mainBundle
        val candidates = listOfNotNull(
            bundle.pathForResource("de421", "bsp"),
            "${bundle.bundlePath}/de421.bsp",
            bundle.resourcePath?.let { "$it/de421.bsp" }
        )
        for (candidate in candidates) {
            if (fileManager.fileExistsAtPath(candidate)) {
                return candidate
            }
        }
        return candidates.firstOrNull() ?: ""
    }

    private fun columnString(stmt: CPointer<cnames.structs.sqlite3_stmt>?, col: Int): String? {
        val ptr = sqlite3_column_text(stmt, col) ?: return null
        return ptr.reinterpret<kotlinx.cinterop.ByteVar>().toKString()
    }

    override fun getCatalogDescriptions(): Map<String, String> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptyMap()
        val result = mutableMapOf<String, String>()

        memScoped {
            val ppStmt = alloc<CPointerVar<cnames.structs.sqlite3_stmt>>()
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
            val ppStmt = alloc<CPointerVar<cnames.structs.sqlite3_stmt>>()
            val sql = "SELECT * FROM asteroids;"
            if (sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null) == SQLITE_OK) {
                val stmt = ppStmt.value
                try {
                    val colCount = sqlite3_column_count(stmt)
                    while (sqlite3_step(stmt) == SQLITE_ROW) {
                        if (!first) sb.append(",")
                        first = false
                        sb.append("{")
                        for (i in 0 until colCount) {
                            if (i > 0) sb.append(",")
                            val name = sqlite3_column_name(stmt, i)?.toKString() ?: "col_$i"
                            sb.append("\"").append(name).append("\":")
                            when (sqlite3_column_type(stmt, i)) {
                                SQLITE_NULL -> sb.append("null")
                                SQLITE_INTEGER -> sb.append(sqlite3_column_int(stmt, i))
                                SQLITE_FLOAT -> sb.append(sqlite3_column_double(stmt, i))
                                else -> {
                                    val text = columnString(stmt, i) ?: ""
                                    sb.append("\"").append(escapeJson(text)).append("\"")
                                }
                            }
                        }
                        sb.append("}")
                    }
                } finally {
                    sqlite3_finalize(stmt)
                }
            }
        }
        sb.append("]")
        sb.toString()
    }

    override fun getCometsJson(): String = mutex.withLock {
        val database = openDatabase() ?: return@withLock "[]"
        val sb = StringBuilder("[")
        var first = true

        memScoped {
            val ppStmt = alloc<CPointerVar<cnames.structs.sqlite3_stmt>>()
            val sql = "SELECT * FROM comets;"
            if (sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null) == SQLITE_OK) {
                val stmt = ppStmt.value
                try {
                    val colCount = sqlite3_column_count(stmt)
                    while (sqlite3_step(stmt) == SQLITE_ROW) {
                        if (!first) sb.append(",")
                        first = false
                        sb.append("{")
                        for (i in 0 until colCount) {
                            if (i > 0) sb.append(",")
                            val name = sqlite3_column_name(stmt, i)?.toKString() ?: "col_$i"
                            sb.append("\"").append(name).append("\":")
                            when (sqlite3_column_type(stmt, i)) {
                                SQLITE_NULL -> sb.append("null")
                                SQLITE_INTEGER -> sb.append(sqlite3_column_int(stmt, i))
                                SQLITE_FLOAT -> sb.append(sqlite3_column_double(stmt, i))
                                else -> {
                                    val text = columnString(stmt, i) ?: ""
                                    sb.append("\"").append(escapeJson(text)).append("\"")
                                }
                            }
                        }
                        sb.append("}")
                    }
                } finally {
                    sqlite3_finalize(stmt)
                }
            }
        }
        sb.append("]")
        sb.toString()
    }

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

    override fun refreshComets(): Boolean = mutex.withLock {
        try {
            val urlString = "https://cobs.si/api/elements.api?format=mpc&is-active=true&cur-mag=20"
            val url = NSURL.URLWithString(urlString) ?: return@withLock false
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
            dispatch_semaphore_wait(sem, timeout)

            val data = resultHolder[0]
            if (!networkSuccess || data == null) {
                Log.e(TAG, "Network fetch failed for comets")
                return@withLock false
            }

            val text = NSString.create(data, NSUTF8StringEncoding) as? String
                ?: return@withLock false

            val lines = text.lines()
            if (lines.isEmpty()) {
                Log.w(TAG, "Empty comet lines received")
                return@withLock false
            }

            // If database is currently open in read-only mode, close it so it can reopen read-write
            if (db != null && sqlite3_db_readonly(db, "main") == 1) {
                sqlite3_close_v2(db)
                db = null
            }

            val database = openDatabase() ?: return@withLock false
            if (sqlite3_db_readonly(database, "main") == 1) {
                Log.e(TAG, "Cannot refresh comets: database is read-only")
                return@withLock false
            }

            memScoped {
                sqlite3_exec(database, "BEGIN TRANSACTION;", null, null, null)
                sqlite3_exec(database, "DELETE FROM comets;", null, null, null)

                val ppStmt = alloc<CPointerVar<cnames.structs.sqlite3_stmt>>()
                val sql =
                    "INSERT INTO comets (catalog_id, common_name, h, k, t_peri, q, e, w, node, incl) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);"
                if (sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null) != SQLITE_OK) {
                    sqlite3_exec(database, "ROLLBACK;", null, null, null)
                    Log.e(TAG, "Failed to prepare comets insert statement")
                    return@withLock false
                }
                val stmt = ppStmt.value

                try {
                    for (line in lines) {
                        if (line.trim().isEmpty() || line.length < 100) continue
                        try {
                            val hStr = line.substring(91, 96).trim()
                            val kStr = line.substring(96, 101).trim()
                            if (hStr.isEmpty() || kStr.isEmpty()) continue

                            val catalogId = line.take(12).trim().replace(Regex("\\s+"), " ")
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

                            var namePart = line.substring(102)
                            val doubleSpaceIdx = namePart.indexOf("  ")
                            if (doubleSpaceIdx != -1) {
                                namePart = namePart.take(doubleSpaceIdx)
                            }
                            val commonName = namePart.trim()

                            sqlite3_reset(stmt)
                            olive_sqlite3_bind_text(stmt, 1, catalogId)
                            olive_sqlite3_bind_text(stmt, 2, commonName)
                            sqlite3_bind_double(stmt, 3, h)
                            sqlite3_bind_double(stmt, 4, k)
                            sqlite3_bind_double(stmt, 5, tPeri)
                            sqlite3_bind_double(stmt, 6, q)
                            sqlite3_bind_double(stmt, 7, e)
                            sqlite3_bind_double(stmt, 8, w)
                            sqlite3_bind_double(stmt, 9, node)
                            sqlite3_bind_double(stmt, 10, incl)

                            sqlite3_step(stmt)
                        } catch (ex: Exception) {
                            Log.e(TAG, "Error parsing comet line: $line: ${ex.message}")
                        }
                    }
                    sqlite3_exec(database, "COMMIT;", null, null, null)
                    Log.i(TAG, "Successfully refreshed comets")
                    return@withLock true
                } catch (e: Exception) {
                    sqlite3_exec(database, "ROLLBACK;", null, null, null)
                    Log.e(TAG, "Error in comets refresh transaction: ${e.message}")
                    return@withLock false
                } finally {
                    sqlite3_finalize(stmt)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing comets: ${e.message}")
            return@withLock false
        }
    }

    override fun searchObjects(
        searchQuery: String,
        catalogFilter: String?,
        sortBy: String,
        magLimit: Double?,
        typeFilters: Set<String>?,
        starFilter: Boolean?,
        visibilityFilter: String,
        lat: Double?,
        lon: Double?,
        targetRa: Double?,
        targetDec: Double?,
        maxDistanceDegrees: Double?,
        limit: Int,
        offset: Int
    ): List<CatalogObject> = mutex.withLock {
        val database = openDatabase() ?: return@withLock emptyList()
        val results = mutableListOf<CatalogObject>()
        val args = mutableListOf<String>()
        var selection = "1=1"

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            val regex = Regex("^([a-z]+)\\s*(\\d+)?$")
            val match = regex.matchEntire(q)

            val catalogAliases = mapOf(
                "messier" to "M", "m" to "M",
                "caldwell" to "C", "c" to "C",
                "collinder" to "Col", "col" to "Col", "cr" to "Col",
                "ngc" to "NGC",
                "ic" to "IC",
                "herschel" to "H", "h" to "H",
                "barnard" to "B", "b" to "B",
                "sharpless" to "Sh2", "sh2" to "Sh2", "sh" to "Sh2",
                "abell" to "Abl", "abl" to "Abl",
                "arp" to "Arp"
            )

            val noSpaceQuery = searchQuery.replace(" ", "")

            var clause =
                "(catalog_label || ' ' || catalog_entry LIKE ? OR REPLACE(catalog_label || catalog_entry, ' ', '') LIKE ? OR common_name LIKE ? OR catalog_entry = ?)"
            args.add("%$searchQuery%")
            args.add("%$noSpaceQuery%")
            args.add("%$searchQuery%")
            args.add(searchQuery)

            if (match != null) {
                val prefix = match.groupValues[1]
                val num = match.groupValues[2]
                val label = catalogAliases[prefix]
                if (label != null) {
                    if (num.isNotEmpty()) {
                        clause = "($clause OR (catalog_label = ? AND catalog_entry = ?))"
                        args.add(label)
                        args.add(num)
                    } else {
                        clause = "($clause OR catalog_label = ?)"
                        args.add(label)
                    }
                }
            }
            selection += " AND $clause"
        }

        val radius = maxDistanceDegrees ?: if (catalogFilter == "Nearest Objects") 10.0 else null
        if (radius != null && targetRa != null && targetDec != null) {
            val c = cos(targetDec * PI / 180.0).coerceAtLeast(0.01)
            val maxDistSq = radius * radius
            val deltaRa = radius / c

            selection += " AND (dec BETWEEN ${targetDec - radius} AND ${targetDec + radius})"
            if (targetRa - deltaRa >= 0.0 && targetRa + deltaRa <= 360.0) {
                selection += " AND (ra BETWEEN ${targetRa - deltaRa} AND ${targetRa + deltaRa})"
            }
            selection += " AND ((((ra - $targetRa) * $c) * ((ra - $targetRa) * $c) + (dec - $targetDec) * (dec - $targetDec)) <= $maxDistSq)"
        }

        if (catalogFilter != null && catalogFilter != "All Objects" && catalogFilter != "Solar System" && catalogFilter != "Nearest Objects") {
            selection += " AND catalog_label = ?"
            args.add(catalogFilter)
        }

        if (magLimit != null) {
            selection += " AND (magnitude IS NOT NULL AND magnitude <= $magLimit)"
        }

        if (typeFilters != null) {
            if (typeFilters.isEmpty()) {
                selection += " AND 0 = 1"
            } else {
                val placeholders = typeFilters.joinToString(",") { "?" }
                selection += " AND object_type IN ($placeholders)"
                args.addAll(typeFilters)
            }
        }

        if (starFilter != null) {
            selection += if (starFilter) {
                " AND object_type LIKE '%Star%'"
            } else {
                " AND object_type NOT LIKE '%Star%'"
            }
        }

        if (visibilityFilter == "Above Horizon" && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
            val timeMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            val lst = AstrometryUtils.calculateLST(lon, timeMs)
            val lstRad = lst * PI / 180.0

            val latRad = lat * PI / 180.0
            val sinLat = sin(latRad)
            val cosLat = cos(latRad)

            val altCondition =
                "(SIN(dec * ${PI / 180.0}) * $sinLat + COS(dec * ${PI / 180.0}) * $cosLat * COS($lstRad - ra * ${PI / 180.0})) > 0"
            selection += " AND $altCondition"
        }

        val priorityCase = "CASE " +
                "WHEN catalog_label = 'Str' AND magnitude IS NOT NULL AND magnitude <= $BRIGHT_STAR_PRIORITY_MAG_LIMIT THEN 3 " +
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

        val orderBy = when (sortBy) {
            "distance" -> {
                if (targetRa != null && targetDec != null) {
                    val c = cos(targetDec * PI / 180.0)
                    val c2 = c * c
                    "((ra - $targetRa) * (ra - $targetRa) * $c2) + ((dec - $targetDec) * (dec - $targetDec)) ASC"
                } else {
                    "priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
                }
            }

            "name" -> "(common_name IS NULL OR common_name = '') ASC, common_name ASC, priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            "brightness" -> "magnitude IS NULL ASC, magnitude ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            "catalog ID" -> "priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            else -> "priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
        }

        val sql = "SELECT id, catalog_label, catalog_entry, ra, dec, constellation, object_type, " +
                "broad_category, magnitude, surface_brightness, angular_size, common_name, " +
                "description, copyright, MIN($priorityCase) as priority " +
                "FROM catalog WHERE $selection " +
                "GROUP BY ra, dec, object_type " +
                "ORDER BY $orderBy " +
                "LIMIT $limit OFFSET $offset;"

        memScoped {
            val ppStmt = alloc<CPointerVar<cnames.structs.sqlite3_stmt>>()
            val prepRc = sqlite3_prepare_v2(database, sql, -1, ppStmt.ptr, null)
            if (prepRc == SQLITE_OK) {
                val stmt = ppStmt.value
                try {
                    // Bind parameters with SQLITE_TRANSIENT so SQLite makes its own copy
                    for ((i, arg) in args.withIndex()) {
                        val bindRc = olive_sqlite3_bind_text(stmt, i + 1, arg)
                        if (bindRc != SQLITE_OK) {
                            Log.e(TAG, "Failed to bind arg $i ('$arg'), rc=$bindRc")
                        }
                    }

                    var stepRc = sqlite3_step(stmt)
                    while (stepRc == SQLITE_ROW) {
                        val id = sqlite3_column_int(stmt, 0)
                        val label = columnString(stmt, 1) ?: ""
                        val entry = columnString(stmt, 2) ?: ""
                        val ra = sqlite3_column_double(stmt, 3)
                        val dec = sqlite3_column_double(stmt, 4)
                        val constel =
                            if (sqlite3_column_type(stmt, 5) == SQLITE_NULL) null else columnString(
                                stmt,
                                5
                            )
                        val type = columnString(stmt, 6) ?: ""
                        val cat = columnString(stmt, 7) ?: ""
                        val mag = if (sqlite3_column_type(
                                stmt,
                                8
                            ) == SQLITE_NULL
                        ) null else sqlite3_column_double(stmt, 8)
                        val sb = if (sqlite3_column_type(
                                stmt,
                                9
                            ) == SQLITE_NULL
                        ) null else sqlite3_column_double(stmt, 9)
                        val size = if (sqlite3_column_type(
                                stmt,
                                10
                            ) == SQLITE_NULL
                        ) null else columnString(stmt, 10)
                        val commonName = if (sqlite3_column_type(
                                stmt,
                                11
                            ) == SQLITE_NULL
                        ) null else columnString(stmt, 11)
                        val desc = if (sqlite3_column_type(
                                stmt,
                                12
                            ) == SQLITE_NULL
                        ) null else columnString(stmt, 12)
                        val copyright = if (sqlite3_column_type(
                                stmt,
                                13
                            ) == SQLITE_NULL
                        ) null else columnString(stmt, 13)

                        results.add(
                            CatalogObject(
                                id = id,
                                catalogLabel = label,
                                catalogEntry = entry,
                                ra = ra,
                                dec = dec,
                                constellation = constel,
                                objectType = type,
                                broadCategory = cat,
                                magnitude = mag,
                                surfaceBrightness = sb,
                                angularSize = size,
                                commonName = commonName,
                                description = desc,
                                copyright = copyright
                            )
                        )
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
                    "Failed to prepare searchObjects query (rc=$prepRc, err=${
                        sqlite3_errmsg(database)?.toKString()
                    }): $sql"
                )
            }
        }

        results
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

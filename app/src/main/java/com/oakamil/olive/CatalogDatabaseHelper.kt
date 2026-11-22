package com.oakamil.olive

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.cos
import kotlin.math.sin

class CatalogDatabaseHelper(
    private val context: Context,
    private val storage: KeyValueStorage,
) : CatalogDatabase {

    companion object {
        private const val TAG = "CatalogDatabaseHelper"
        private const val DB_MARKER_KEY = "asset_marker_catalog_db"
        private const val BSP_MARKER_KEY = "asset_marker_de421_bsp"
        private val STALE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal", ".bak")
    }

    private val dbLock = Any()
    private var db: SQLiteDatabase? = null
    private val verifiedAssetsThisSession = mutableSetOf<String>()

    private val appUpdateMarker: String by lazy {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            "${PackageInfoCompat.getLongVersionCode(info)}:${info.lastUpdateTime}"
        } catch (_: Exception) {
            ""
        }
    }

    override fun getBspPath(): String = synchronized(dbLock) {
        val bspFile = ensureAssetExtractedLocked(
            assetName = CatalogDatabase.BSP_ASSET_NAME,
            markerKey = BSP_MARKER_KEY,
            isCatalogDb = false,
        )
        (bspFile ?: context.getDatabasePath(CatalogDatabase.BSP_ASSET_NAME)).absolutePath
    }

    private fun ensureAssetExtractedLocked(
        assetName: String,
        markerKey: String,
        isCatalogDb: Boolean,
    ): File? {
        val destFile = context.getDatabasePath(assetName)
        val hasValidDest = destFile.exists() && destFile.length() > 0L
        if (assetName in verifiedAssetsThisSession && hasValidDest) {
            return destFile
        }
        val marker = appUpdateMarker
        if (marker.isNotEmpty() && storage.getString(markerKey) == marker && hasValidDest) {
            verifiedAssetsThisSession.add(assetName)
            return destFile
        }
        if (isCatalogDb) {
            db?.close()
            db = null
            deleteSidecars(destFile)
        }
        if (!copyAssetAtomically(assetName, destFile)) {
            return if (hasValidDest) destFile else null
        }
        if (marker.isNotEmpty()) {
            storage.putString(markerKey, marker)
        }
        verifiedAssetsThisSession.add(assetName)
        return destFile
    }

    private fun deleteSidecars(dbFile: File) {
        val parent = dbFile.parentFile ?: return
        for (suffix in STALE_SIDECAR_SUFFIXES) {
            File(parent, "${dbFile.name}$suffix").takeIf { it.exists() }?.delete()
        }
    }

    private fun copyAssetAtomically(assetName: String, destFile: File): Boolean {
        destFile.parentFile?.mkdirs()
        val tempFile = File(destFile.parentFile, "$assetName.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.length() <= 0L) {
                tempFile.delete()
                return false
            }
            Files.move(
                tempFile.toPath(),
                destFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            Log.i(TAG, "Staged read-only asset $assetName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetName", e)
            tempFile.delete()
            false
        }
    }

    private fun openDatabaseLocked() {
        if (db?.isOpen == true) return
        val dbFile = ensureAssetExtractedLocked(
            assetName = CatalogDatabase.CATALOG_DB_NAME,
            markerKey = DB_MARKER_KEY,
            isCatalogDb = true,
        ) ?: return
        val database = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        registerMathFunctions(database)
        db = database
    }

    private fun registerMathFunctions(database: SQLiteDatabase) {
        val sinFunc = SQLiteDatabase.Function { args, result ->
            try {
                result.set(sin(args.getDouble(0)))
            } catch (_: Exception) {
                result.set(0.0)
            }
        }
        val cosFunc = SQLiteDatabase.Function { args, result ->
            try {
                result.set(cos(args.getDouble(0)))
            } catch (_: Exception) {
                result.set(0.0)
            }
        }

        database.addFunction("SIN", 1, sinFunc, SQLiteDatabase.Function.FLAG_DETERMINISTIC)
        database.addFunction("COS", 1, cosFunc, SQLiteDatabase.Function.FLAG_DETERMINISTIC)
        database.addFunction("sin", 1, sinFunc, SQLiteDatabase.Function.FLAG_DETERMINISTIC)
        database.addFunction("cos", 1, cosFunc, SQLiteDatabase.Function.FLAG_DETERMINISTIC)
    }

    override fun closeDatabase() {
        synchronized(dbLock) {
            db?.close()
            db = null
        }
    }

    override fun getCatalogDescriptions(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        synchronized(dbLock) {
            openDatabaseLocked()
            val database = db ?: return emptyMap()
            try {
                database.query(
                    "catalog_descriptions",
                    arrayOf("catalog_label", "description"),
                    null,
                    null,
                    null,
                    null,
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        map[c.getString(c.getColumnIndexOrThrow("catalog_label"))] =
                            c.getString(c.getColumnIndexOrThrow("description"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching catalog descriptions", e)
            }
        }
        return map
    }

    override fun getAsteroidsJson(): String {
        synchronized(dbLock) {
            openDatabaseLocked()
            val database = db ?: return "[]"
            val sb = StringBuilder("[")
            var first = true
            try {
                database.rawQuery("SELECT * FROM asteroids", null).use { c ->
                    val colCount = c.columnCount
                    val colNames = Array(colCount) { i -> c.getColumnName(i) }
                    while (c.moveToNext()) {
                        if (!first) sb.append(',')
                        first = false
                        sb.append('{')
                        for (i in 0 until colCount) {
                            if (i > 0) sb.append(',')
                            sb.append('"').append(colNames[i]).append("\":")
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> sb.append(c.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> sb.append(c.getDouble(i))
                                Cursor.FIELD_TYPE_NULL -> sb.append("null")
                                else -> sb.append('"')
                                    .append(CatalogDatabase.escapeJson(c.getString(i).orEmpty()))
                                    .append('"')
                            }
                        }
                        sb.append('}')
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching asteroids", e)
                return "[]"
            }
            sb.append(']')
            return sb.toString()
        }
    }

    override fun getCometsJson(): String =
        storage.getString(CatalogDatabase.COMETS_JSON_STORAGE_KEY) ?: "[]"

    override fun refreshComets(): Boolean {
        val lines = try {
            val url = URL(CatalogDatabase.COMETS_FEED_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 10000
                readTimeout = 10000
            }
            try {
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "Unexpected HTTP ${connection.responseCode} downloading comets")
                    return false
                }
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readLines()
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading comets", e)
            return false
        }

        val json = CatalogDatabase.parseCometLinesToJson(lines) { line, ex ->
            Log.e(TAG, "Error parsing comet line: $line", ex)
        } ?: return false
        storage.putString(CatalogDatabase.COMETS_JSON_STORAGE_KEY, json)
        return true
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
        offset: Int
    ): List<CatalogObject> {
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
        )

        return try {
            synchronized(dbLock) {
                openDatabaseLocked()
                val database = db ?: return emptyList()
                database.rawQuery(sql, args.toTypedArray()).use { c ->
                    readCatalogObjects(c)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying catalog", e)
            emptyList()
        }
    }

    override fun getCoLocatedObjects(obj: CatalogObject): List<CatalogObject> {
        if (CatalogDatabase.isDynamicObject(obj)) return emptyList()

        return try {
            synchronized(dbLock) {
                openDatabaseLocked()
                val database = db ?: return emptyList()
                database.rawQuery(
                    CatalogDatabase.buildCoLocatedQuerySql(),
                    arrayOf<Any>(obj.ra, obj.dec, obj.objectType, obj.id)
                ).use { cursor ->
                    readCatalogObjects(cursor)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying co-located objects", e)
            emptyList()
        }
    }

    override fun getObjectsByDesignations(
        keys: Collection<Pair<String, String>>
    ): List<CatalogObject> {
        if (keys.isEmpty()) return emptyList()
        val uniqueKeys = keys.distinct()
        val results = mutableListOf<CatalogObject>()
        return try {
            synchronized(dbLock) {
                openDatabaseLocked()
                val database = db ?: return emptyList()
                for (chunk in uniqueKeys.chunked(200)) {
                    val sql = CatalogDatabase.buildDesignationsQuerySql(chunk.size)
                    val args = Array<Any>(chunk.size * 2) { idx ->
                        val pair = chunk[idx / 2]
                        if (idx % 2 == 0) pair.first else pair.second
                    }
                    database.rawQuery(sql, args).use { cursor ->
                        results.addAll(readCatalogObjects(cursor))
                    }
                }
                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying objects by designation", e)
            results
        }
    }

    private fun readCatalogObjects(c: Cursor): List<CatalogObject> {
        val results = mutableListOf<CatalogObject>()
        while (c.moveToNext()) {
            results.add(
                CatalogObject(
                    id = c.getInt(0),
                    catalogLabel = c.getString(1).orEmpty(),
                    catalogEntry = c.getString(2).orEmpty(),
                    ra = c.getDouble(3),
                    dec = c.getDouble(4),
                    constellation = c.getString(5),
                    objectType = c.getString(6).orEmpty(),
                    broadCategory = c.getString(7).orEmpty(),
                    magnitude = if (c.isNull(8)) null else c.getDouble(8),
                    surfaceBrightness = if (c.isNull(9)) null else c.getDouble(9),
                    angularSize = c.getString(10),
                    commonName = c.getString(11),
                    description = c.getString(12),
                    copyright = c.getString(13),
                    distance = c.getString(14)
                )
            )
        }
        return results
    }
}

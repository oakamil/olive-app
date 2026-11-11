package com.oakamil.olive

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.core.content.edit
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin

class CatalogDatabaseHelper(private val context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), CatalogDatabase {

    override fun getBspPath(): String = context.getDatabasePath("de421.bsp").absolutePath


    companion object {
        private const val DB_NAME = "catalog.db"
        private const val DB_VERSION = 1
        private const val TAG = "CatalogDatabaseHelper"
    }

    private var db: SQLiteDatabase? = null

    private var isInitialized = false

    @Synchronized
    private fun initializeIfNeeded() {
        if (isInitialized) return
        val prefs = context.getSharedPreferences("olive_prefs", Context.MODE_PRIVATE)
        val lastContentVersion = prefs.getInt("catalog_content_version", 0)

        val forceUpdate = CatalogDatabase.CONTENT_VERSION > lastContentVersion

        val dbSuccess = copyAssetSafely(DB_NAME, forceUpdate)
        val bspSuccess = copyAssetSafely("de421.bsp", forceUpdate)

        if (forceUpdate && dbSuccess && bspSuccess) {
            prefs.edit { putInt("catalog_content_version", CatalogDatabase.CONTENT_VERSION) }
        }
        if (dbSuccess) {
            isInitialized = true
        }
    }

    private fun copyAssetSafely(filename: String, forceUpdate: Boolean): Boolean {
        val file = context.getDatabasePath(filename)
        if (file.exists() && !forceUpdate) {
            return true
        }

        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "$filename.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        return try {
            context.assets.open(filename).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (file.exists()) {
                file.delete()
            }
            val renamed = tempFile.renameTo(file)
            if (renamed) {
                Log.i(TAG, "Successfully staged and moved $filename into place.")
                true
            } else {
                Log.e(TAG, "Failed to rename $tempFile to $file")
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $filename", e)
            tempFile.delete()
            false
        }
    }

    override fun onCreate(db: SQLiteDatabase?) {
        // Database is pre-populated, nothing to create
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Handle upgrades if necessary
    }

    override fun onOpen(db: SQLiteDatabase?) {
        super.onOpen(db)
        if (db != null) {
            registerMathFunctions(db)
        }
    }

    fun openDatabase() {
        initializeIfNeeded()
        if (db == null || !db!!.isOpen) {
            val dbPath = context.getDatabasePath(DB_NAME).absolutePath
            val database = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
            registerMathFunctions(database)
            db = database
        }
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
        db?.close()
        db = null
    }

    override fun getCatalogDescriptions(): Map<String, String> {
        openDatabase()
        val database = db ?: return emptyMap()
        val map = mutableMapOf<String, String>()
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
        return map
    }

    override fun getAsteroidsJson(): String {
        openDatabase()
        val database = db ?: return "[]"
        val array = JSONArray()
        try {
            database.query(
                "asteroids",
                null,
                null,
                null,
                null,
                null,
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    for (i in 0 until c.columnCount) {
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_INTEGER -> obj.put(c.getColumnName(i), c.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> obj.put(c.getColumnName(i), c.getDouble(i))
                            Cursor.FIELD_TYPE_STRING -> obj.put(c.getColumnName(i), c.getString(i))
                            Cursor.FIELD_TYPE_NULL -> obj.put(c.getColumnName(i), JSONObject.NULL)
                            Cursor.FIELD_TYPE_BLOB -> Log.w(TAG, "Unexpected blob field")
                        }
                    }
                    array.put(obj)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching asteroids", e)
        }
        return array.toString()
    }

    override fun getCometsJson(): String {
        openDatabase()
        val database = db ?: return "[]"
        val array = JSONArray()
        try {
            database.query(
                "comets",
                null,
                null,
                null,
                null,
                null,
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    for (i in 0 until c.columnCount) {
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_INTEGER -> obj.put(c.getColumnName(i), c.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> obj.put(c.getColumnName(i), c.getDouble(i))
                            Cursor.FIELD_TYPE_STRING -> obj.put(c.getColumnName(i), c.getString(i))
                            Cursor.FIELD_TYPE_NULL -> obj.put(c.getColumnName(i), JSONObject.NULL)
                            Cursor.FIELD_TYPE_BLOB -> Log.w(TAG, "Unexpected blob field")
                        }
                    }
                    array.put(obj)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching comets", e)
        }
        return array.toString()
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

    override fun refreshComets(): Boolean {
        try {
            val url =
                URL("https://cobs.si/api/elements.api?format=mpc&is-active=true&cur-mag=20")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val lines = reader.readLines()
            reader.close()

            openDatabase()
            val database = db ?: return false
            database.beginTransaction()
            try {
                database.execSQL("DELETE FROM comets")
                val statement = database.compileStatement(
                    "INSERT INTO comets (catalog_id, common_name, h, k, t_peri, q, e, w, node, incl) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )

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

                        statement.bindString(1, catalogId)
                        statement.bindString(2, commonName)
                        statement.bindDouble(3, h)
                        statement.bindDouble(4, k)
                        statement.bindDouble(5, tPeri)
                        statement.bindDouble(6, q)
                        statement.bindDouble(7, e)
                        statement.bindDouble(8, w)
                        statement.bindDouble(9, node)
                        statement.bindDouble(10, incl)
                        statement.executeInsert()
                    } catch (ex: Exception) {
                        Log.e(TAG, $$"Error parsing comet line: $line", ex)
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing comets", e)
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
        offset: Int
    ): List<CatalogObject> {
        openDatabase()
        val database = db ?: return emptyList()

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
            val c = cos(Math.toRadians(targetDec)).coerceAtLeast(0.01)
            val maxDistSq = radius * radius
            val deltaRa = radius / c

            selection += " AND (dec BETWEEN ${targetDec - radius} AND ${targetDec + radius})"
            if (deltaRa < 180.0) {
                if (targetRa - deltaRa >= 0.0 && targetRa + deltaRa <= 360.0) {
                    selection += " AND (ra BETWEEN ${targetRa - deltaRa} AND ${targetRa + deltaRa})"
                } else {
                    val raMin = (targetRa - deltaRa + 360.0) % 360.0
                    val raMax = (targetRa + deltaRa) % 360.0
                    selection += " AND (ra >= $raMin OR ra <= $raMax)"
                }
            }
            val raDiff = "min(abs(ra - $targetRa), 360.0 - abs(ra - $targetRa))"
            selection += " AND (((($raDiff) * $c) * (($raDiff) * $c) + (dec - $targetDec) * (dec - $targetDec)) <= $maxDistSq)"
        }

        if (catalogFilter != null && catalogFilter != "All Objects" && catalogFilter != "Solar System" && catalogFilter != "Nearest Objects") {
            selection += " AND catalog_label = ?"
            args.add(catalogFilter)
        }

        if (magLimit != null) {
            selection += if (includeNullMagnitude) {
                " AND (magnitude IS NULL OR magnitude <= ?)"
            } else {
                " AND (magnitude IS NOT NULL AND magnitude <= ?)"
            }
            args.add(magLimit.toString())
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
            val timeMs = System.currentTimeMillis()
            val lst = AstrometryUtils.calculateLST(lon, timeMs)
            val lstRad = Math.toRadians(lst)

            val latRad = Math.toRadians(lat)
            val sinLat = sin(latRad)
            val cosLat = cos(latRad)

            // RA in db is in degrees
            // Dec in db is in degrees
            val altCondition =
                "(SIN(dec * ${Math.PI / 180.0}) * $sinLat + COS(dec * ${Math.PI / 180.0}) * $cosLat * COS($lstRad - ra * ${Math.PI / 180.0})) > 0"
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
            "distance" if targetRa != null && targetDec != null -> {
                val c = cos(Math.toRadians(targetDec))
                val c2 = c * c
                val raDiff = "min(abs(ra - $targetRa), 360.0 - abs(ra - $targetRa))"
                "(($raDiff) * ($raDiff) * $c2) + ((dec - $targetDec) * (dec - $targetDec)) ASC"
            }

            "name" -> "(common_name IS NULL OR common_name = '') ASC, common_name ASC, priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            "brightness" -> "magnitude IS NULL ASC, magnitude ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            "priority" -> "priority ASC, magnitude IS NULL ASC, magnitude ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            "catalog ID" -> "priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            else -> "priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
        }

        try {
            val cursor = database.query(
                "catalog",
                arrayOf("*", "MIN($priorityCase) as priority"),
                selection,
                args.toTypedArray(),
                "ra, dec, object_type", // groupBy for deduplication!
                null,
                orderBy,
                "$offset, $limit"
            )

            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getInt(c.getColumnIndexOrThrow("id"))
                    val label = c.getString(c.getColumnIndexOrThrow("catalog_label"))
                    val entry = c.getString(c.getColumnIndexOrThrow("catalog_entry"))
                    val ra = c.getDouble(c.getColumnIndexOrThrow("ra"))
                    val dec = c.getDouble(c.getColumnIndexOrThrow("dec"))
                    val constel = c.getString(c.getColumnIndexOrThrow("constellation"))
                    val type = c.getString(c.getColumnIndexOrThrow("object_type"))
                    val cat = c.getString(c.getColumnIndexOrThrow("broad_category"))
                    val magIdx = c.getColumnIndexOrThrow("magnitude")
                    val mag = if (c.isNull(magIdx)) null else c.getDouble(magIdx)

                    val sbIdx = c.getColumnIndexOrThrow("surface_brightness")
                    val sb = if (c.isNull(sbIdx)) null else c.getDouble(sbIdx)

                    val size = c.getString(c.getColumnIndexOrThrow("angular_size"))

                    val commonName = c.getString(c.getColumnIndexOrThrow("common_name"))

                    val desc = c.getString(c.getColumnIndexOrThrow("description"))
                    val copyright = c.getString(c.getColumnIndexOrThrow("copyright"))

                    results.add(
                        CatalogObject(
                            id,
                            label,
                            entry,
                            ra,
                            dec,
                            constel,
                            type,
                            cat,
                            mag,
                            sb,
                            size,
                            commonName,
                            desc,
                            copyright
                        )
                    )
                }
            }

            // Sorting by distance is now completely handled by SQLite!

        } catch (e: Exception) {
            Log.e(TAG, "Error querying catalog", e)
            return emptyList()
        }

        return results
    }
}

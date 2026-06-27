package com.oakamil.olive

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.sin

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
    val copyright: String?
) {
    val isFakeCommonName: Boolean
        get() = commonName != null && commonName.matches(Regex("^[a-zA-Z0-9]+\\s+([IVX]+\\s+)?\\d+[a-zA-Z]?$"))

    val displayName: String
        get() = if (commonName.isNullOrBlank() || isFakeCommonName) designation else commonName

    val designation: String
        get() = if (catalogLabel == "Str" || catalogLabel == "Planet" || catalogLabel == "Solar System") catalogEntry else "$catalogLabel $catalogEntry"
}

class CatalogDatabaseHelper(private val context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

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
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        @Suppress("DEPRECATION")
        val currentAppVersion = packageInfo.versionCode
        val lastAppVersion = prefs.getInt("last_app_version", 0)

        val forceUpdate = currentAppVersion > lastAppVersion

        val dbCopied = copyAssetIfNeeded(DB_NAME, forceUpdate)
        copyAssetIfNeeded("de421.bsp", forceUpdate)

        if (dbCopied) {
            buildIndexes(context.getDatabasePath(DB_NAME))
        }

        if (forceUpdate) {
            prefs.edit { putInt("last_app_version", currentAppVersion) }
        }
        isInitialized = true
    }

    private fun buildIndexes(dbFile: File) {
        try {
            val database = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            Log.i(TAG, "Building indexes for $DB_NAME...")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_mag ON catalog(magnitude)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_label ON catalog(catalog_label)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_broad_category ON catalog(broad_category)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_type ON catalog(object_type)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_constellation ON catalog(constellation)")
            database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS catalog_spatial USING rtree(id, minX, maxX, minY, maxY, minZ, maxZ)")
            database.close()
            Log.i(TAG, "Indexes built successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build indexes", e)
        }
    }

    private fun copyAssetIfNeeded(filename: String, forceUpdate: Boolean): Boolean {
        val file = context.getDatabasePath(filename)
        if (!file.exists() || forceUpdate) {
            file.parentFile?.mkdirs()
            if (file.exists()) {
                file.delete()
            }
            try {
                // Try .gz first
                val inputStream: InputStream = try {
                    GZIPInputStream(context.assets.open("$filename.gz"))
                } catch (_: Exception) {
                    context.assets.open(filename)
                }

                inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Successfully copied $filename from assets.")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $filename", e)
            }
        }
        return false
    }

    override fun onCreate(db: SQLiteDatabase?) {
        // Database is pre-populated, nothing to create
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Handle upgrades if necessary
    }

    fun openDatabase() {
        initializeIfNeeded()
        if (db == null || !db!!.isOpen) {
            val dbPath = context.getDatabasePath(DB_NAME).absolutePath
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)

            db?.addCustomFunction("SIN", 1) { args ->
                try {
                    sin(args[0].toDouble()).toString()
                } catch (_: Exception) {
                    "0.0"
                }
            }
            db?.addCustomFunction("COS", 1) { args ->
                try {
                    cos(args[0].toDouble()).toString()
                } catch (_: Exception) {
                    "0.0"
                }
            }
        }
    }

    fun closeDatabase() {
        db?.close()
        db = null
    }

    fun getCatalogDescriptions(): Map<String, String> {
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

    fun searchObjects(
        searchQuery: String = "",
        catalogFilter: String? = null,
        sortBy: String = "catalog ID",
        magLimit: Double? = null,
        typeFilter: String? = null,
        visibilityFilter: String = "Above Horizon",
        lat: Double? = null,
        lon: Double? = null,
        targetRa: Double? = null,
        targetDec: Double? = null,
        limit: Int = 300,
        offset: Int = 0
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

        if (catalogFilter != null && catalogFilter != "All Objects" && catalogFilter != "Solar System") {
            if (catalogFilter == "Nearest Objects") {
                if (targetRa != null && targetDec != null) {
                    val c = cos(Math.toRadians(targetDec))
                    val maxDistSq = 10.0 * 10.0
                    selection += " AND ((((ra - $targetRa) * $c) * ((ra - $targetRa) * $c) + (dec - $targetDec) * (dec - $targetDec)) <= $maxDistSq)"
                }
            } else {
                selection += " AND catalog_label = ?"
                args.add(catalogFilter)
            }
        }

        if (magLimit != null) {
            selection += " AND (magnitude IS NULL OR magnitude <= ?)"
            args.add(magLimit.toString())
        }

        if (typeFilter != null && typeFilter != "All Types") {
            selection += " AND object_type = ?"
            args.add(typeFilter)
        }

        if (visibilityFilter == "Above Horizon" && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
            val timeMs = System.currentTimeMillis()
            val daysSinceJ2000 = (timeMs - 946728000000.0) / 86400000.0
            var gmst = (280.46061837 + 360.98564736629 * daysSinceJ2000) % 360.0
            if (gmst < 0) gmst += 360.0
            var lst = (gmst + lon) % 360.0
            if (lst < 0) lst += 360.0
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

        val priorityCase = "CASE catalog_label " +
                "WHEN 'M' THEN 1 " +
                "WHEN 'C' THEN 2 " +
                "WHEN 'Col' THEN 3 " +
                "WHEN 'NGC' THEN 4 " +
                "WHEN 'IC' THEN 5 " +
                "WHEN 'H' THEN 6 " +
                "WHEN 'B' THEN 7 " +
                "WHEN 'Sh2' THEN 8 " +
                "WHEN 'Abl' THEN 9 " +
                "WHEN 'Arp' THEN 10 " +
                "ELSE 11 END"

        val orderBy = when {
            sortBy == "distance" && targetRa != null && targetDec != null -> {
                val c = cos(Math.toRadians(targetDec))
                val c2 = c * c
                "((ra - $targetRa) * (ra - $targetRa) * $c2) + ((dec - $targetDec) * (dec - $targetDec)) ASC"
            }

            sortBy == "name" -> "(common_name IS NULL OR common_name = '') ASC, common_name ASC, priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            sortBy == "brightness" -> "magnitude IS NULL ASC, magnitude ASC, priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
            sortBy == "catalog ID" -> "priority ASC, CAST(catalog_entry AS INTEGER) ASC, catalog_entry ASC"
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

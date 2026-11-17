package com.oakamil.olive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CatalogObjectInfoFormatterTest {

    private fun loadStringsFromXml(): Map<String, String> {
        val xmlFile = listOf(
            File("../shared/src/commonMain/composeResources/values/strings.xml"),
            File("shared/src/commonMain/composeResources/values/strings.xml"),
        ).first { it.isFile }

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        val nodes = doc.getElementsByTagName("string")
        return buildMap {
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                val key = element.getAttribute("name")
                if (key.isNotEmpty()) {
                    put(key, element.textContent.replace("\\'", "'").replace("\\\"", "\""))
                }
            }
        }
    }

    private fun obj(
        id: Int = 1,
        catalogLabel: String = "NGC",
        catalogEntry: String = "1",
        objectType: String = "Galaxy",
        broadCategory: String = "galaxy",
        magnitude: Double? = 13.0,
        surfaceBrightness: Double? = null,
        angularSize: String? = null,
        commonName: String? = null,
        description: String? = null,
        distance: String? = null,
    ) = CatalogObject(
        id = id,
        catalogLabel = catalogLabel,
        catalogEntry = catalogEntry,
        ra = 10.0,
        dec = 20.0,
        constellation = "And",
        objectType = objectType,
        broadCategory = broadCategory,
        magnitude = magnitude,
        surfaceBrightness = surfaceBrightness,
        angularSize = angularSize,
        commonName = commonName,
        description = description,
        copyright = null,
        distance = distance,
    )

    @Test
    fun angularSizeTokensPreserveUnitsAndOmitPointSources() {
        assertEquals(listOf("32'"), CatalogObjectInfoFormatter.parseAngularSizeTokens("32'", "Sun"))
        assertEquals(
            listOf("13°", "5°"),
            CatalogObjectInfoFormatter.parseAngularSizeTokens("13°x5°", "Asterism")
        )
        assertEquals(
            listOf("36\""),
            CatalogObjectInfoFormatter.parseAngularSizeTokens("36\"", "Globular Cluster")
        )
        assertEquals(
            listOf("6", "4"),
            CatalogObjectInfoFormatter.parseAngularSizeTokens("6x4", "Galaxy")
        )
        assertEquals(
            listOf("189.1", "61.7"),
            CatalogObjectInfoFormatter.parseAngularSizeTokens("189.1x61.7", "Galaxy")
        )
        assertEquals(
            listOf("5"),
            CatalogObjectInfoFormatter.parseAngularSizeTokens("5", "Asterism")
        )
        assertTrue(CatalogObjectInfoFormatter.isBareNumber("6"))
        assertTrue(CatalogObjectInfoFormatter.isBareNumber("189.1"))
        assertFalse(CatalogObjectInfoFormatter.isBareNumber("32'"))
        assertFalse(CatalogObjectInfoFormatter.isBareNumber("36\""))
        assertFalse(CatalogObjectInfoFormatter.isBareNumber("13°"))
        assertFalse(CatalogObjectInfoFormatter.isBareNumber(""))
        assertNull(CatalogObjectInfoFormatter.parseAngularSizeTokens("44.1\"", "Multiple Star"))
        assertNull(CatalogObjectInfoFormatter.parseAngularSizeTokens("5.3", "Double Star"))
        assertNull(CatalogObjectInfoFormatter.parseAngularSizeTokens("1.0", "Star"))
    }

    @Test
    fun resolvesStructuredFieldsAndPrefersProseOverDreyer() {
        val ngc281 = obj(
            id = 281,
            catalogLabel = "NGC",
            catalogEntry = "281",
            objectType = "Nebula",
            broadCategory = "nebula",
            surfaceBrightness = 13.5,
            angularSize = "35x30",
            description = "Faint, very large, diffuse, small triple star on northwest edge",
            distance = "2.9 Mpc",
        )
        val ic11 = obj(
            id = 10011,
            catalogLabel = "IC",
            catalogEntry = "11",
            objectType = "Nebula",
            broadCategory = "nebula",
            description = "Very faint, large, triple star on northwest corner",
        )
        val ta2 = obj(
            id = 20011,
            catalogLabel = "Ta2",
            catalogEntry = "11",
            objectType = "Nebula",
            broadCategory = "nebula",
            description = "Large, best in O III filter.\nMinimum aperture: 2.4 in, 10X",
        )
        val sh2 = obj(
            id = 30184,
            catalogLabel = "Sh2",
            catalogEntry = "184",
            objectType = "Nebula",
            broadCategory = "nebula",
            description = "Often called NGC 281.",
        )

        val meta = CatalogObjectInfoFormatter.extractMetadata(ngc281, listOf(ic11, ta2, sh2))

        assertEquals("IC11, Ta2 11, Sh2 184", meta.alsoKnownAs)
        assertEquals(13.5, meta.surfaceBrightness)
        assertEquals(listOf("35", "30"), meta.angularSizeTokens)
        assertEquals("2.9 Mpc", meta.distance)
        assertEquals(
            "Often called NGC 281.\n\nLarge, best in O III filter.\nMinimum aperture: 2.4 in, 10X",
            meta.notes,
        )
    }

    @Test
    fun deduplicatesCoLocatedNotesAndKeepsCollinderFromOverridingMessier() {
        val m4 = obj(
            id = 4,
            catalogLabel = "M",
            catalogEntry = "4",
            objectType = "Globular Cluster",
            broadCategory = "cluster",
            description = "Cluster, 8 or 10 bright stars in a line, with 5 stars, well resolved",
        )
        val ngc6121 = obj(
            id = 6121,
            catalogLabel = "NGC",
            catalogEntry = "6121",
            objectType = "Globular Cluster",
            broadCategory = "cluster",
            description = "Cluster, 8 or 10 bright stars in a line, with 5 stars, well resolved",
        )
        val har45 = obj(
            id = 14045,
            catalogLabel = "Har",
            catalogEntry = "45",
            objectType = "Globular Cluster",
            broadCategory = "cluster",
            distance = "2.2 kpc",
            description = null,
        )
        val m4Meta = CatalogObjectInfoFormatter.extractMetadata(m4, listOf(ngc6121, har45))
        assertEquals("NGC6121, Har 45", m4Meta.alsoKnownAs)
        assertEquals("2.2 kpc", m4Meta.distance)
        assertEquals(
            "Cluster, 8 or 10 bright stars in a line, with 5 stars, well resolved",
            m4Meta.notes
        )

        val m46 = obj(
            id = 46,
            catalogLabel = "M",
            catalogEntry = "46",
            objectType = "Open Cluster",
            broadCategory = "cluster",
            description = "Remarkable object: cluster, very bright, very rich, very large, involved planetary nebula",
        )
        val col159 = obj(
            id = 2159,
            catalogLabel = "Col",
            catalogEntry = "159",
            objectType = "Open Cluster",
            broadCategory = "cluster",
            description = "125 stars, like Praesepe",
        )
        val m46Meta = CatalogObjectInfoFormatter.extractMetadata(m46, listOf(col159))
        assertEquals(
            "Remarkable object: cluster, very bright, very rich, very large, involved planetary nebula\n\n125 stars, like Praesepe",
            m46Meta.notes,
        )

        val m17 = obj(
            id = 17,
            catalogLabel = "M",
            catalogEntry = "17",
            objectType = "Cluster + Nebula",
            broadCategory = "cluster",
            description = "This is M 17, the Omega nebula.\n\nDreyer notes: Magnificent object: bright",
        )
        val col377 = obj(
            id = 2377,
            catalogLabel = "Col",
            catalogEntry = "377",
            objectType = "Cluster + Nebula",
            broadCategory = "cluster",
            description = "27 stars, like nebulosity",
        )
        assertEquals(
            m17.description,
            CatalogObjectInfoFormatter.extractMetadata(m17, listOf(col377)).notes
        )
    }

    @Test
    fun suppressesDynamicSolarSystemBoilerplateAndRemovesObsoleteVocabStrings() {
        val sun = obj(
            id = -10,
            catalogLabel = "Solar System",
            catalogEntry = "Sun",
            objectType = "Sun",
            broadCategory = "Sun",
            angularSize = "32'",
            commonName = "Sun",
            description = "The Sun",
        )
        val moon = obj(
            id = 3,
            catalogLabel = "Planet",
            catalogEntry = "Moon",
            objectType = "Planet",
            broadCategory = "planet",
            commonName = "Moon",
            description = "The planet Moon",
        )

        val sunMeta = CatalogObjectInfoFormatter.extractMetadata(sun)
        assertNull(sunMeta.designation)
        assertEquals(listOf("32'"), sunMeta.angularSizeTokens)
        assertNull(sunMeta.notes)
        val moonMeta = CatalogObjectInfoFormatter.extractMetadata(moon)
        assertNull(moonMeta.designation)
        assertNull(moonMeta.notes)

        assertTrue(CatalogDatabase.isDynamicObject(sun))
        assertTrue(CatalogDatabase.isDynamicObject(moon))
        assertFalse(
            CatalogDatabase.isDynamicObject(
                obj(
                    id = 42,
                    catalogLabel = "M",
                    catalogEntry = "42"
                )
            )
        )
        assertTrue(CatalogDatabase.buildCoLocatedQuerySql().contains("distance"))

        val strings = loadStringsFromXml()
        assertFalse(strings.keys.any { it.startsWith("sh_") })
        assertFalse(strings.containsKey("format_distance_mpc"))
        assertFalse(strings.containsKey("format_distance_kpc"))
    }

    @Test
    fun azimuthCompassSectorsMapAll16DirectionsFromStringsXml() {
        val strings = loadStringsFromXml()
        val expectedKeys = listOf(
            "dir_north", "dir_north_northeast", "dir_northeast", "dir_east_northeast",
            "dir_east", "dir_east_southeast", "dir_southeast", "dir_south_southeast",
            "dir_south", "dir_south_southwest", "dir_southwest", "dir_west_southwest",
            "dir_west", "dir_west_northwest", "dir_northwest", "dir_north_northwest",
        )
        val directions = expectedKeys.map { strings.getValue(it) }
        assertEquals(
            listOf(
                "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
            ),
            directions,
        )

        for (sector in 0 until 16) {
            assertEquals(sector, AstrometryUtils.azimuthSectorIndex16(sector * 22.5))
        }
        assertEquals(0, AstrometryUtils.azimuthSectorIndex16(11.24))
        assertEquals(1, AstrometryUtils.azimuthSectorIndex16(11.25))
        assertEquals(15, AstrometryUtils.azimuthSectorIndex16(348.74))
        assertEquals(0, AstrometryUtils.azimuthSectorIndex16(348.75))
        assertEquals(12, AstrometryUtils.azimuthSectorIndex16(-90.0))
        assertEquals(0, AstrometryUtils.azimuthSectorIndex16(360.0))

        assertEquals(
            "42.1° / 135.4° (SE)",
            String.format(
                strings.getValue("format_alt_az"),
                "42.1",
                "135.4",
                directions[AstrometryUtils.azimuthSectorIndex16(135.4)]
            ),
        )
    }

    @Test
    fun formatRaAndDecWrapAndHandleBoundariesCleanly() {
        assertEquals("23h 56m 00s", AstrometryUtils.formatRa(-1.0))
        assertEquals("00h 00m 00s", AstrometryUtils.formatRa(360.0))
        assertEquals("00h 00m 00s", AstrometryUtils.formatRa(359.9999))
        assertEquals("00h 00m 00s", AstrometryUtils.formatRa(-360.0))
        assertEquals("", AstrometryUtils.formatRa(Double.NaN))

        assertEquals("+00° 00' 00\"", AstrometryUtils.formatDec(-0.0001))
        assertEquals("-45° 30' 00\"", AstrometryUtils.formatDec(-45.5))
        assertEquals("", AstrometryUtils.formatDec(Double.POSITIVE_INFINITY))
    }

    @Test
    fun parseCometLineAndBuildSearchSelectionSqlShareCommonLogic() {
        // Columns (0-based): [0..12) id, [14..18) yyyy, [19..21) mm, [22..29) dd.dddd,
        // [30..39) q, [40..49) e, [50..59) w, [60..69) node, [70..79) incl, [91..96) H, [96..101) K, [102..) name
        val sampleLine =
            "CK23A030      2024 09 27.7376  0.391408  1.000105  308.4864   21.5594  139.1116  20240928   4.5  4.0  C/2023 A3 (Tsuchinshan-ATLAS)   MPC"
        val parsed = CatalogDatabase.parseCometLine(sampleLine)
        assertTrue(parsed != null)
        assertEquals("CK23A030", parsed?.catalogId)
        assertEquals("C/2023 A3 (Tsuchinshan-ATLAS)", parsed?.commonName)
        assertEquals(4.5, parsed?.h)
        assertEquals(4.0, parsed?.k)
        assertEquals(0.391408, parsed?.q)
        assertEquals(1.000105, parsed?.e)
        assertEquals(308.4864, parsed?.w)
        assertEquals(21.5594, parsed?.node)
        assertEquals(139.1116, parsed?.incl)

        assertNull(CatalogDatabase.parseCometLine("   "))
        assertNull(CatalogDatabase.parseCometLine("too short"))

        val (selection, args) = CatalogDatabase.buildSearchSelectionSql(
            searchQuery = "M42",
            catalogFilter = "M",
            magLimit = 10.0,
            includeNullMagnitude = false,
            typeFilters = setOf("Nebula", "Galaxy"),
            starFilter = false,
            visibilityFilter = "Above Horizon",
            lat = 37.0,
            lon = -122.0,
            targetRa = 83.8,
            targetDec = -5.4,
            maxDistanceDegrees = 5.0,
            currentTimeMs = 1_700_000_000_000L,
        )
        assertTrue(selection.contains("catalog_label = ?"))
        assertTrue(selection.contains("object_type IN (?,?)"))
        assertTrue(selection.contains("object_type NOT LIKE '%Star%'"))
        assertTrue(selection.contains("magnitude IS NOT NULL AND magnitude <= 10.0"))
        assertTrue(selection.contains("SIN(dec *"))
        assertTrue(args.contains("M"))
        assertTrue(args.contains("Nebula"))
        assertTrue(args.contains("Galaxy"))

        val (emptyTypesSql, _) = CatalogDatabase.buildSearchSelectionSql(
            searchQuery = "",
            catalogFilter = "All Objects",
            magLimit = null,
            includeNullMagnitude = true,
            typeFilters = emptySet(),
            starFilter = null,
            visibilityFilter = "All Objects",
            lat = null,
            lon = null,
            targetRa = null,
            targetDec = null,
            maxDistanceDegrees = null,
            currentTimeMs = 0L,
        )
        assertEquals("1=1 AND 0 = 1", emptyTypesSql)

        fun prefixArgs(query: String) = CatalogDatabase.buildSearchSelectionSql(
            searchQuery = query,
            catalogFilter = null,
            magLimit = null,
            includeNullMagnitude = true,
            typeFilters = null,
            starFilter = null,
            visibilityFilter = "Any",
            lat = null,
            lon = null,
            targetRa = null,
            targetDec = null,
            maxDistanceDegrees = null,
            currentTimeMs = 0L,
        )

        val (sh2Sql, sh2Args) = prefixArgs("sh2")
        assertFalse(sh2Sql.contains("catalog_label = ? AND catalog_entry = ?"))
        assertTrue(sh2Sql.contains("OR catalog_label = ?"))
        assertEquals("Sh2", sh2Args.last())

        val (sh2EntrySql, sh2EntryArgs) = prefixArgs("sh2 184")
        assertTrue(sh2EntrySql.contains("catalog_label = ? AND catalog_entry = ?"))
        assertEquals(listOf("Sh2", "184"), sh2EntryArgs.takeLast(2))

        val (ta2EntrySql, ta2EntryArgs) = prefixArgs("ta2 11")
        assertTrue(ta2EntrySql.contains("catalog_label = ? AND catalog_entry = ?"))
        assertEquals(listOf("Ta2", "11"), ta2EntryArgs.takeLast(2))

        val (_, m01Args) = prefixArgs("m01")
        assertEquals(listOf("M", "1"), m01Args.takeLast(2))

        val (_, m042Args) = prefixArgs("m 042")
        assertEquals(listOf("M", "42"), m042Args.takeLast(2))

        val (_, saaArgs) = prefixArgs("saa 01")
        assertEquals(listOf("SaA", "1"), saaArgs.takeLast(2))

        val errors = mutableListOf<String>()
        val cometsJson = CatalogDatabase.parseCometLinesToJson(
            listOf(
                sampleLine,
                "CK24B010      2025 01 15.5000  badfloat  1.000105  308.4864   21.5594  139.1116  20240928   4.5  4.0  Broken\\Comet \"Test\"",
                "   ",
            ),
            onParseError = { line, _ -> errors.add(line) },
        )
        assertTrue(cometsJson != null && cometsJson.startsWith("[{\"id\":1,"))
        assertTrue(cometsJson!!.contains("\"catalog_id\":\"CK23A030\""))
        assertTrue(cometsJson.contains("\"common_name\":\"C/2023 A3 (Tsuchinshan-ATLAS)\""))
        assertEquals(1, errors.size)

        assertNull(CatalogDatabase.parseCometLinesToJson(emptyList()))
        assertNull(CatalogDatabase.parseCometLinesToJson(listOf("<html><body>502</body></html>")))
        assertNull(CatalogDatabase.parseCometLinesToJson(listOf("{\"error\":\"rate limited\"}")))
        assertEquals("a\\\\b\\\"c\\nd", CatalogDatabase.escapeJson("a\\b\"c\nd"))

        val (fullSql, fullArgs) = CatalogDatabase.buildSearchQuerySql(
            searchQuery = "sh2 184",
            catalogFilter = "All Objects",
            sortBy = "priority",
            magLimit = null,
            includeNullMagnitude = true,
            typeFilters = null,
            starFilter = null,
            visibilityFilter = "Any",
            lat = null,
            lon = null,
            targetRa = null,
            targetDec = null,
            maxDistanceDegrees = null,
            limit = 100,
            offset = 25,
        )
        assertTrue(fullSql.contains("GROUP BY ra, dec, object_type"))
        assertTrue(fullSql.contains("LIMIT 100 OFFSET 25;"))
        assertEquals(listOf("Sh2", "184"), fullArgs.takeLast(2))
    }
}

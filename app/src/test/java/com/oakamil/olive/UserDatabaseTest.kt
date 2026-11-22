// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UserDatabaseTest {

    @Test
    fun observingNightGroupsEveningAndEarlyMorningTogether() {
        val tz = TimeZone.UTC
        val eveningMs = LocalDateTime(2026, 11, 20, 22, 30).toInstant(tz).toEpochMilliseconds()
        val afterMidnightMs = LocalDateTime(2026, 11, 21, 2, 15).toInstant(tz).toEpochMilliseconds()
        val nextAfternoonMs = LocalDateTime(2026, 11, 21, 13, 0).toInstant(tz).toEpochMilliseconds()

        assertEquals("2026-11-20", UserDatabase.computeObservingNightKey(eveningMs, tz))
        assertEquals("2026-11-20", UserDatabase.computeObservingNightKey(afterMidnightMs, tz))
        assertEquals("2026-11-21", UserDatabase.computeObservingNightKey(nextAfternoonMs, tz))
    }

    @Test
    fun observingNightHandlesDstNoonTransitionsAccurately() {
        val laTz = TimeZone.of("America/Los_Angeles")
        // Spring forward day (2026-03-08): 11:30 AM -> previous night, 12:30 PM -> current night
        val springBeforeNoon =
            LocalDateTime(2026, 3, 8, 11, 30).toInstant(laTz).toEpochMilliseconds()
        val springAfterNoon =
            LocalDateTime(2026, 3, 8, 12, 30).toInstant(laTz).toEpochMilliseconds()
        assertEquals("2026-03-07", UserDatabase.computeObservingNightKey(springBeforeNoon, laTz))
        assertEquals("2026-03-08", UserDatabase.computeObservingNightKey(springAfterNoon, laTz))

        // Fall back day (2026-11-01): 11:30 AM -> previous night, 12:30 PM -> current night
        val fallBeforeNoon =
            LocalDateTime(2026, 11, 1, 11, 30).toInstant(laTz).toEpochMilliseconds()
        val fallAfterNoon = LocalDateTime(2026, 11, 1, 12, 30).toInstant(laTz).toEpochMilliseconds()
        assertEquals("2026-10-31", UserDatabase.computeObservingNightKey(fallBeforeNoon, laTz))
        assertEquals("2026-11-01", UserDatabase.computeObservingNightKey(fallAfterNoon, laTz))
    }

    @Test
    fun filterObservationsMatchesStrictlyCoLocatedAliasesOnly() {
        val m31Ref = TargetRef("M", "31", "Andromeda Galaxy", "Galaxy", 10.6847, 41.2687)
        val ngc224 = CatalogObject(
            id = 224,
            catalogLabel = "NGC",
            catalogEntry = "224",
            ra = 10.6847,
            dec = 41.2687,
            constellation = "And",
            objectType = "Galaxy",
            broadCategory = "Galaxy",
            magnitude = 3.4,
            surfaceBrightness = null,
            angularSize = null,
            commonName = "Andromeda Galaxy",
            description = null,
            copyright = null
        )
        val obsNgc224 = ObservationRecord(
            id = 1L,
            catalogLabel = "NGC",
            catalogEntry = "224",
            targetName = "NGC224",
            objectType = "Galaxy",
            raDeg = 10.6847,
            decDeg = 41.2687,
            observedAtMs = 1000L,
            observingNight = "2026-11-20",
            latDeg = 37.0,
            lonDeg = -122.0,
            altDeg = 45.0,
            azDeg = 120.0,
            notes = "Dust lanes visible"
        )
        val obsJupiterAtSameCoord = ObservationRecord(
            id = 2L,
            catalogLabel = "Planet",
            catalogEntry = "Jupiter",
            targetName = "Jupiter",
            objectType = "Planet",
            raDeg = 10.6847,
            decDeg = 41.2687,
            observedAtMs = 2000L,
            observingNight = "2026-11-20",
            latDeg = 37.0,
            lonDeg = -122.0,
            altDeg = 45.0,
            azDeg = 120.0,
            notes = "Cloud belts"
        )
        val obsNearbyDifferentStar = ObservationRecord(
            id = 3L,
            catalogLabel = "Str",
            catalogEntry = "NearbyStar",
            targetName = "NearbyStar",
            objectType = "Star",
            raDeg = 10.6850,
            decDeg = 41.2690,
            observedAtMs = 3000L,
            observingNight = "2026-11-20",
            latDeg = 37.0,
            lonDeg = -122.0,
            altDeg = 45.0,
            azDeg = 120.0,
            notes = "Foreground star"
        )

        val matched = UserDatabase.filterObservationsForTarget(
            allObservations = listOf(obsNgc224, obsJupiterAtSameCoord, obsNearbyDifferentStar),
            target = m31Ref,
            coLocated = listOf(ngc224)
        )
        assertEquals(1, matched.size)
        assertEquals(1L, matched.first().id)

        val observedKeys = setOf(obsNgc224.objectKey)
        val staticCoLocatedKeys = setOf(
            StaticCoLocatedKey(obsNgc224.raDeg, obsNgc224.decDeg, obsNgc224.objectType)
        )
        val m31CatalogObj = ngc224.copy(id = 31, catalogLabel = "M", catalogEntry = "31")
        val differentTypeSameCoord =
            ngc224.copy(id = 999, catalogLabel = "Str", catalogEntry = "X", objectType = "Star")
        val nearbyDifferentGalaxy =
            ngc224.copy(id = 998, catalogLabel = "NGC", catalogEntry = "225", ra = 10.6850)
        assertTrue(
            UserDatabase.matchesObservedSet(
                m31CatalogObj,
                observedKeys,
                staticCoLocatedKeys
            )
        )
        assertFalse(
            UserDatabase.matchesObservedSet(
                differentTypeSameCoord,
                observedKeys,
                staticCoLocatedKeys
            )
        )
        assertFalse(
            UserDatabase.matchesObservedSet(
                nearbyDifferentGalaxy,
                observedKeys,
                staticCoLocatedKeys
            )
        )
    }

    @Test
    fun textFieldCursorInsertAndBackspaceEditAtSelection() {
        val initial = TextFieldValue("Faint 13mm Nagler", TextRange(6, 8))
        val replaced = initial.insertTextAtCursor("9")
        assertEquals("Faint 9mm Nagler", replaced.text)
        assertEquals(TextRange(7), replaced.selection)

        val deleted = replaced.backspaceAtCursor()
        assertEquals("Faint mm Nagler", deleted.text)
        assertEquals(TextRange(6), deleted.selection)
        assertTrue(TextFieldValue("", TextRange(0)).backspaceAtCursor().text.isEmpty())
    }

    @Test
    fun csvExportUnifiesDesignationIncludesJ2000AndEscapesFields() {
        val obs = ObservationRecord(
            id = 1L,
            catalogLabel = "M",
            catalogEntry = "42",
            targetName = "Orion Nebula",
            objectType = "Nebula",
            raDeg = 83.8221,
            decDeg = -5.3911,
            observedAtMs = LocalDateTime(2026, 11, 20, 22, 30).toInstant(TimeZone.UTC)
                .toEpochMilliseconds(),
            observingNight = "2026-11-20",
            latDeg = 37.24,
            lonDeg = -121.88,
            altDeg = 45.2,
            azDeg = 140.5,
            notes = "Trapezium \"crisp\", bright wings\nO-III helped"
        )
        val obsJupiter = ObservationRecord(
            id = 2L,
            catalogLabel = "Planet",
            catalogEntry = "Jupiter",
            targetName = "Jupiter",
            objectType = "Planet",
            raDeg = 100.0,
            decDeg = 20.0,
            observedAtMs = LocalDateTime(2026, 11, 20, 23, 0).toInstant(TimeZone.UTC)
                .toEpochMilliseconds(),
            observingNight = "2026-11-20",
            latDeg = 37.24,
            lonDeg = -121.88,
            altDeg = 55.0,
            azDeg = 160.0,
            notes = "GRS visible"
        )
        val csv = UserDatabase.buildNightObservationsCsv(listOf(obs, obsJupiter))
        assertTrue(csv.contains("Timestamp (UTC),Observing Night,Object,Designation,Type,RA (J2000 deg),Dec (J2000 deg)"))
        assertTrue(csv.contains("2026-11-20T22:30:00Z,2026-11-20,Orion Nebula,M42,Nebula"))
        assertTrue(csv.contains("2026-11-20T23:00:00Z,2026-11-20,Jupiter,Jupiter,Planet"))
        assertTrue(csv.contains("\"Trapezium \"\"crisp\"\", bright wings\nO-III helped\""))
        assertEquals(
            "Spring_Galaxies_2026",
            UserDatabase.sanitizeExportFileName("Spring Galaxies / 2026!")
        )

        val m42Obj = CatalogObject(
            id = 42,
            catalogLabel = "M",
            catalogEntry = "42",
            ra = 83.8221,
            dec = -5.3911,
            constellation = "Orion",
            objectType = "Nebula",
            broadCategory = "Nebula",
            magnitude = 4.0,
            surfaceBrightness = null,
            angularSize = "85' × 60'",
            commonName = "Orion Nebula",
            description = null,
            copyright = null
        )
        val listCsv = UserDatabase.buildManualListCsv(listOf(ResolvedListEntry(m42Obj)))
        assertTrue(listCsv.startsWith("Name,Designation,Type,RA (J2000 deg),Dec (J2000 deg),Magnitude,Angular Size,Constellation"))
        assertTrue(listCsv.contains("Orion Nebula,M42,Nebula,83.82210,-5.39110,4.00"))
        assertEquals("observing_list", UserDatabase.sanitizeExportFileName("."))
        assertEquals("observing_list", UserDatabase.sanitizeExportFileName(".."))
    }

    @Test
    fun csvAndCustomTargetKeysAreLocaleInvariant() {
        val prevLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val customRef = UserDatabase.targetRefFrom(null, 10.6847 to 41.2687, "Custom Spot")
            assertEquals("10.6847,41.2687", customRef.catalogEntry)

            val m31 = CatalogObject(
                id = 31,
                catalogLabel = "M",
                catalogEntry = "31",
                ra = 10.68471,
                dec = 41.26875,
                constellation = "Andromeda",
                objectType = "Galaxy",
                broadCategory = "Galaxy",
                magnitude = 3.4,
                surfaceBrightness = null,
                angularSize = null,
                commonName = "Andromeda",
                description = null,
                copyright = null
            )
            val listCsv = UserDatabase.buildManualListCsv(listOf(ResolvedListEntry(m31)))
            assertTrue(listCsv.contains("10.68471,41.26875,3.40"))
        } finally {
            Locale.setDefault(prevLocale)
        }
    }

    @Test
    fun formatsObservationTimestampIn12hAnd24h() {
        val tz = TimeZone.UTC
        val pmMs = LocalDateTime(2026, 11, 20, 22, 30).toInstant(tz).toEpochMilliseconds()
        val midnightMs = LocalDateTime(2026, 11, 21, 0, 5).toInstant(tz).toEpochMilliseconds()
        assertEquals(
            "2026-11-20 22:30",
            UserDatabase.formatObservationTimestamp(pmMs, is24Hour = true, timeZone = tz)
        )
        assertEquals(
            "2026-11-20 10:30 PM",
            UserDatabase.formatObservationTimestamp(pmMs, is24Hour = false, timeZone = tz)
        )
        assertEquals(
            "2026-11-21 12:05 AM",
            UserDatabase.formatObservationTimestamp(midnightMs, is24Hour = false, timeZone = tz)
        )
    }
}

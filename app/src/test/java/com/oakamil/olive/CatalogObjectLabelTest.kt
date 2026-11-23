// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogObjectLabelTest {

    private fun catalogObj(
        catalogLabel: String = "Col",
        catalogEntry: String = "399",
        objectType: String = "Asterism",
        commonName: String? = null
    ) = CatalogObject(
        id = 1,
        catalogLabel = catalogLabel,
        catalogEntry = catalogEntry,
        ra = 0.0,
        dec = 0.0,
        constellation = "Vul",
        objectType = objectType,
        broadCategory = "DSO",
        magnitude = 3.6,
        surfaceBrightness = null,
        angularSize = null,
        commonName = commonName,
        description = null,
        copyright = null
    )

    @Test
    fun multiNameDsoSelectsCleanPrimaryNameForSkyMapAndPreservesDisplayName() {
        val coathanger = catalogObj(
            catalogLabel = "Col",
            catalogEntry = "399",
            commonName = "4-5 Vul. / Al Sufi's Cluster, Brocchi's Cluster, Coathanger / The Coathanger"
        )
        assertEquals("The Coathanger", coathanger.skyMapLabel)
        assertEquals(
            "4-5 Vul. / Al Sufi's Cluster, Brocchi's Cluster, Coathanger / The Coathanger",
            coathanger.displayName
        )

        val beehive = catalogObj(
            catalogLabel = "Col",
            catalogEntry = "189",
            objectType = "Open Cluster",
            commonName = "2632 (M44) / Beehive Cluster, Praesepe"
        )
        assertEquals("Beehive Cluster", beehive.skyMapLabel)

        val owlCluster = catalogObj(
            catalogLabel = "NGC",
            catalogEntry = "457",
            objectType = "Open Cluster",
            commonName = "Owl or E.T. Cluster / The Owl Cluster"
        )
        assertEquals("The Owl Cluster", owlCluster.skyMapLabel)

        val eskimo = catalogObj(
            catalogLabel = "NGC",
            catalogEntry = "2392",
            objectType = "Planetary Nebula",
            commonName = "Eskimo nebula / Clown Nebula or Eskimo"
        )
        assertEquals("Eskimo nebula", eskimo.skyMapLabel)

        val southernPleiades = catalogObj(
            catalogLabel = "IC",
            catalogEntry = "2602",
            objectType = "Open Cluster",
            commonName = "Southern Pleiades / Theta Carinae Cluster, Southern Pleiades"
        )
        assertEquals("Southern Pleiades", southernPleiades.skyMapLabel)

        val jewelBox = catalogObj(
            catalogLabel = "NGC",
            catalogEntry = "4755",
            objectType = "Open Cluster",
            commonName = "Jewel Box / kappa Cru cluster"
        )
        assertEquals("Jewel Box", jewelBox.skyMapLabel)

        val omegaCentauri = catalogObj(
            catalogLabel = "NGC",
            catalogEntry = "5139",
            objectType = "Globular Cluster",
            commonName = "omega Cen / Omega Centauri"
        )
        assertEquals("Omega Centauri", omegaCentauri.skyMapLabel)
    }

    @Test
    fun multiNameStarsSelectProperNameOrFirstDesignation() {
        val v460Cyg = catalogObj(
            catalogLabel = "SaR",
            catalogEntry = "131",
            objectType = "Multiple Star",
            commonName = "V 460 Cyg / DS Peg = V460 Cyg"
        )
        assertEquals("V 460 Cyg", v460Cyg.skyMapLabel)
        assertEquals("V 460 Cyg / DS Peg = V460 Cyg", v460Cyg.displayName)

        val sirius = catalogObj(
            catalogLabel = "SaM",
            catalogEntry = "495",
            objectType = "Multiple Star",
            commonName = "Alpha CMa / Sirius"
        )
        assertEquals("Sirius", sirius.skyMapLabel)

        val achird = catalogObj(
            catalogLabel = "RDS",
            catalogEntry = "94",
            objectType = "Multiple Star",
            commonName = "Achird / Eta Cas / 24 Cas"
        )
        assertEquals("Achird", achird.skyMapLabel)

        val alkalbain = catalogObj(
            catalogLabel = "RDS",
            catalogEntry = "2",
            objectType = "Multiple Star",
            commonName = "Phi Tau / 52 Tau / Alkalbain I"
        )
        assertEquals("Alkalbain I", alkalbain.skyMapLabel)

        val burnhamPm = catalogObj(
            catalogLabel = "SaM",
            catalogEntry = "457",
            objectType = "Multiple Star",
            commonName = "38 Cet / Burnham pm"
        )
        assertEquals("38 Cet", burnhamPm.skyMapLabel)
    }

    @Test
    fun unspacedSlashesAndPlaceholderNamesAreHandledCleanly() {
        val doubleStarPair = catalogObj(
            catalogLabel = "SaM",
            catalogEntry = "676",
            objectType = "Multiple Star",
            commonName = "15/17 CVn"
        )
        assertEquals("15/17 CVn", doubleStarPair.skyMapLabel)

        val mizarAlcor = catalogObj(
            catalogLabel = "SaM",
            catalogEntry = "2054",
            objectType = "Multiple Star",
            commonName = "Zeta UMa / Mizar/Alcor"
        )
        assertEquals("Mizar/Alcor", mizarAlcor.skyMapLabel)

        val nonePlaceholder = catalogObj(
            catalogLabel = "C",
            catalogEntry = "41",
            objectType = "Open Cluster",
            commonName = "none"
        )
        assertTrue(nonePlaceholder.isFakeCommonName)
        assertEquals("C41", nonePlaceholder.displayName)
        assertEquals("C41", nonePlaceholder.skyMapLabel)

        val nlPlaceholder = catalogObj(
            catalogLabel = "Col",
            catalogEntry = "62",
            objectType = "Open Cluster",
            commonName = "nl"
        )
        assertTrue(nlPlaceholder.isFakeCommonName)
        assertEquals("Col62", nlPlaceholder.displayName)
        assertEquals("Col62", nlPlaceholder.skyMapLabel)
    }
}

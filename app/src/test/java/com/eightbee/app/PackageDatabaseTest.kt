package com.eightbee.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDatabaseTest {

    @Test
    fun isValidPackageName_acceptsStandardAndSystemPackageNames() {
        assertTrue(PackageDatabase.isValidPackageName("com.google.android.youtube"))
        assertTrue(PackageDatabase.isValidPackageName("sec.penup"))
        assertTrue(PackageDatabase.isValidPackageName("com.samsung.android.app_1"))
        assertTrue(PackageDatabase.isValidPackageName("android"))
    }

    @Test
    fun isValidPackageName_rejectsShellInjectionAndMalformedNames() {
        assertFalse(PackageDatabase.isValidPackageName(""))
        assertFalse(PackageDatabase.isValidPackageName("com.foo; rm -rf /"))
        assertFalse(PackageDatabase.isValidPackageName("com.foo && id"))
        assertFalse(PackageDatabase.isValidPackageName("com.foo | cat"))
        assertFalse(PackageDatabase.isValidPackageName("com.foo\nid"))
        assertFalse(PackageDatabase.isValidPackageName("$(whoami)"))
        assertFalse(PackageDatabase.isValidPackageName("-user"))
        assertFalse(PackageDatabase.isValidPackageName("com..foo"))
        assertFalse(PackageDatabase.isValidPackageName("com.foo."))
        assertFalse(PackageDatabase.isValidPackageName(".com.foo"))
        assertFalse(PackageDatabase.isValidPackageName("com.foo bar"))
        assertFalse(PackageDatabase.isValidPackageName("null".repeat(70)))
    }

    @Test
    fun allowedSafetyLevels_mapsUiFiltersToDatabaseSafetyTiers() {
        assertEquals(setOf("recommended"), PackageDatabase.allowedSafetyLevels("recommended"))
        assertEquals(
            setOf("recommended", "replaceable", "advanced"),
            PackageDatabase.allowedSafetyLevels("advanced")
        )
        assertEquals(
            setOf("recommended", "replaceable", "advanced", "expert", "danger"),
            PackageDatabase.allowedSafetyLevels("unsafe")
        )
    }

    @Test
    fun matchesOem_handlesUniversalTagsBrandAndLgAlias() {
        assertTrue(PackageDatabase.matchesOem(emptyList(), "Google"))
        assertTrue(PackageDatabase.matchesOem(listOf("aosp"), "Samsung"))
        assertTrue(PackageDatabase.matchesOem(listOf("qualcomm"), "Google"))
        assertTrue(PackageDatabase.matchesOem(listOf("mediatek"), "Xiaomi"))
        assertTrue(PackageDatabase.matchesOem(listOf("meta"), "OnePlus"))
        assertTrue(PackageDatabase.matchesOem(listOf("microsoft"), "Samsung"))
        assertTrue(PackageDatabase.matchesOem(listOf("lg"), "LGE"))
        assertTrue(PackageDatabase.matchesOem(listOf("google"), "Google", "Pixel"))
        assertFalse(PackageDatabase.matchesOem(listOf("samsung"), "Google", "Pixel"))
    }
}

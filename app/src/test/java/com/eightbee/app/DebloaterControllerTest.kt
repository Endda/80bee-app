package com.eightbee.app

import com.eightbee.app.features.DebloaterController
import com.eightbee.app.features.SnapshotFeedback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebloaterControllerTest {

    @Test
    fun debloaterController_scansTrackDisabledPackagesAcrossTabs() = runBlocking {
        val fakeConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when (cmd) {
                    "getprop ro.product.manufacturer" -> "Google"
                    "getprop ro.product.brand" -> "google"
                    "pm list packages" -> "package:com.example.bloat\npackage:com.example.gapp\npackage:com.samsung.app"
                    "pm list packages -d" -> "package:com.example.gapp"
                    else -> ""
                }
            }
        )
        val controller = DebloaterController(
            loadUltimateDb = {
                mapOf(
                    "com.example.bloat" to BloatInfo(
                        packageName = "com.example.bloat",
                        label = "Example Bloat",
                        description = "Test",
                        safety = "recommended",
                        oems = listOf("aosp")
                    )
                )
            },
            loadDegoogleDb = {
                listOf(PresetPkg("com.example.gapp", "GApp", 1, "Desc", ""))
            },
            loadSamsungDb = {
                listOf(PresetPkg("com.samsung.app", "SApp", 1, "Desc", ""))
            },
            openOutputStream = { null },
            readUriText = { "" },
            runner = eagerRunner(this, fakeConn)
        )

        controller.scanBloatware("recommended")
        assertEquals(listOf("com.example.bloat"), controller.debloatList.value.map { it.packageName })
        assertEquals(setOf("com.example.gapp"), controller.disabledPackages.value)

        controller.scanDegoogle(1)
        assertEquals(listOf("com.example.gapp"), controller.degoogleList.value.map { it.id })
        assertEquals(setOf("com.example.gapp"), controller.disabledPackages.value)

        controller.scanDesamsung(1)
        assertEquals(listOf("com.samsung.app"), controller.desamsungList.value.map { it.id })
        assertEquals(3, fakeConn.executedShellCommands.count { it == "pm list packages -d" })
    }

    @Test
    fun debloaterController_syncsDisabledAndRestoreStateAfterDisableAndRestore() = runBlocking {
        val fakeConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when {
                    cmd == "pm list packages" -> "package:com.example.a\npackage:com.example.b"
                    cmd == "pm list packages -d" -> "package:com.example.a"
                    cmd.startsWith("pm disable-user --user 0 ") -> "Package com.example.b new state: disabled-user"
                    cmd.startsWith("pm uninstall -k --user 0 ") -> "Success"
                    cmd.startsWith("pm enable ") -> "Package com.example.a new state: enabled"
                    else -> ""
                }
            }
        )
        val controller = DebloaterController(
            loadUltimateDb = { emptyMap() },
            loadDegoogleDb = {
                listOf(
                    PresetPkg("com.example.a", "App A", 1, "Desc", ""),
                    PresetPkg("com.example.b", "App B", 1, "Desc", "")
                )
            },
            loadSamsungDb = { emptyList() },
            openOutputStream = { null },
            readUriText = { "" },
            runner = eagerRunner(this, fakeConn)
        )

        controller.scanDegoogle(1)
        assertEquals(setOf("com.example.a"), controller.disabledPackages.value)

        // Disabling updates disabledPackages immediately without rescanning
        controller.disablePackages(listOf("com.example.b"), isUninstall = false)
        assertEquals(setOf("com.example.a", "com.example.b"), controller.disabledPackages.value)

        // Restoring removes restored package from disabledPackages and restoreList
        controller.scanDisabledPackages()
        assertEquals(listOf("com.example.a"), controller.restoreList.value.map { it.id })
        controller.restorePackages(listOf("com.example.a"))
        assertFalse("com.example.a" in controller.disabledPackages.value)
        assertTrue(controller.restoreList.value.isEmpty())

        // Uninstalling removes uninstalled package from scan lists and disabledPackages
        controller.disablePackages(listOf("com.example.b"), isUninstall = true)
        assertEquals(listOf("com.example.a"), controller.degoogleList.value.map { it.id })
        assertFalse("com.example.b" in controller.disabledPackages.value)
    }

    @Test
    fun debloaterController_snapshotExportAndImportReportFeedbackAndClearOnDisabledScan() = runBlocking {
        val exported = java.io.ByteArrayOutputStream()
        var snapshotInput = ""
        val fakeConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when (cmd) {
                    "pm list packages" -> "package:com.example.installed"
                    "pm list packages -d" -> "package:com.example.disabled"
                    else -> ""
                }
            }
        )
        var shouldFailExport = false
        val controller = DebloaterController(
            loadUltimateDb = { emptyMap() },
            loadDegoogleDb = { emptyList() },
            loadSamsungDb = { emptyList() },
            openOutputStream = { null },
            readUriText = { "" },
            runner = eagerRunner(this, fakeConn),
            encodeSnapshot = { packages -> packages.joinToString(",") },
            parseSnapshot = { raw ->
                when (raw) {
                    "clean" -> SnapshotParseResult(
                        validPackages = linkedSetOf("com.example.installed", "com.example.missing"),
                        rejectedCount = 0
                    )
                    "with-invalid" -> SnapshotParseResult(
                        validPackages = linkedSetOf("com.example.missing"),
                        rejectedCount = 1
                    )
                    else -> throw IllegalArgumentException("Malformed snapshot")
                }
            }
        )

        // Export success
        controller.exportSnapshot { if (shouldFailExport) null else exported }
        assertEquals(
            SnapshotFeedback("Exported snapshot with 1 package(s).", isError = false),
            controller.snapshotFeedback.value
        )
        assertEquals("com.example.installed", exported.toString(Charsets.UTF_8.name()))

        // Export failure
        shouldFailExport = true
        controller.exportSnapshot { null }
        assertEquals(
            SnapshotFeedback("Failed to export snapshot: Unable to open destination file.", isError = true),
            controller.snapshotFeedback.value
        )

        // Import success without rejected entries
        snapshotInput = "clean"
        controller.importSnapshot { snapshotInput }
        assertEquals(listOf("com.example.missing"), controller.restoreList.value.map { it.id })
        assertEquals(
            SnapshotFeedback("Loaded 1 missing package(s) from snapshot.", isError = false),
            controller.snapshotFeedback.value
        )

        // Import with rejected entries triggers error/warning flag
        snapshotInput = "with-invalid"
        controller.importSnapshot { snapshotInput }
        assertEquals(
            SnapshotFeedback(
                "Loaded 1 missing package(s) from snapshot. Ignored 1 invalid package entry/entries.",
                isError = true
            ),
            controller.snapshotFeedback.value
        )

        // Import failure clears restoreList and reports error
        snapshotInput = "bad"
        controller.importSnapshot { snapshotInput }
        assertTrue(controller.restoreList.value.isEmpty())
        assertEquals(
            SnapshotFeedback("Failed to load snapshot: Malformed snapshot", isError = true),
            controller.snapshotFeedback.value
        )

        // Disabled scan clears snapshotFeedback
        controller.scanDisabledPackages()
        assertEquals(null, controller.snapshotFeedback.value)
        assertEquals(listOf("com.example.disabled"), controller.restoreList.value.map { it.id })
    }
}

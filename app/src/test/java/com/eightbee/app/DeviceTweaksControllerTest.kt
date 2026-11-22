package com.eightbee.app

import android.app.Application
import com.eightbee.app.features.BluetoothOem
import com.eightbee.app.features.CommandRunner
import com.eightbee.app.features.DeviceTweaksController
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTweaksControllerTest {

    @Test
    fun isValidPrivateDnsHost_acceptsHostnamesAndRejectsShellInjection() {
        assertTrue(DeviceTweaksController.isValidPrivateDnsHost("dns.adguard.com"))
        assertTrue(DeviceTweaksController.isValidPrivateDnsHost(" 1dot1dot1dot1.cloudflare-dns.com "))
        assertFalse(DeviceTweaksController.isValidPrivateDnsHost(""))
        assertFalse(DeviceTweaksController.isValidPrivateDnsHost("localhost"))
        assertFalse(DeviceTweaksController.isValidPrivateDnsHost("dns.quad9.net; id"))
        assertFalse(DeviceTweaksController.isValidPrivateDnsHost("dns.quad9.net' && whoami"))
    }

    /** What a [DeviceTweaksController] job did: commands interleaved with "> console" lines, final output, virtual ms. */
    private data class TweakRun(val transcript: List<String>, val output: String, val elapsedMs: Long)

    private fun TestScope.runTweak(
        sdk: Int = 33,
        airplaneMode: String = "0",
        installed: Set<String> = emptySet(),
        action: DeviceTweaksController.() -> Unit
    ): TweakRun {
        val transcript = mutableListOf<String>()
        val conn = FakeConnectionManager(shellResponder = { cmd ->
            transcript += cmd
            when {
                cmd == "getprop ro.build.version.sdk" -> "$sdk\n"
                cmd == "settings get global airplane_mode_on" -> "$airplaneMode\n"
                cmd == "dumpsys bluetooth_manager" -> "BT_DUMP"
                cmd.startsWith("pm path ") ->
                    if (cmd.removePrefix("pm path ") in installed) "package:/data/app/base.apk" else ""
                else -> ""
            }
        })
        val runner = CommandRunner(this, connectionProvider = { conn }, dispatcher = StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.output.drop(1).collect { transcript += "> $it" }
        }
        val controller = DeviceTweaksController(
            application = Application(),
            scope = this,
            connectionProvider = { conn },
            runner = runner
        )
        val start = testScheduler.currentTime
        controller.action()
        advanceUntilIdle()
        return TweakRun(transcript, runner.output.value, testScheduler.currentTime - start)
    }

    @Test
    fun deviceTweaks_standardBluetoothRepairRestartsBaseAndOemPackages() = runTest {
        val base = listOf("com.android.bluetooth", "com.android.bluetoothmidiservice", "com.google.android.bluetooth")
        val oemExtras = mapOf(
            BluetoothOem.AOSP to emptyList(),
            BluetoothOem.SAMSUNG to listOf("com.samsung.android.bluetooth"),
            BluetoothOem.XIAOMI to listOf("com.miui.bluetooth", "com.xiaomi.bluetooth", "com.xiaomi.bluetooth.overlay"),
            BluetoothOem.ONEPLUS to listOf("com.oplus.bluetooth", "com.coloros.bluetooth", "com.nearme.bluetooth"),
            BluetoothOem.MOTOROLA to listOf("com.motorola.bluetooth", "com.motorola.bt")
        )
        for ((oem, extras) in oemExtras) {
            val run = runTweak { executeBluetoothRepair(oem, isAdvanced = false) }
            val restarts = (base + extras).flatMap { listOf("> Restarting $it...", "am force-stop $it") }
            assertEquals(
                oem.name,
                listOf(
                    "> Starting Bluetooth Repair...",
                    "> Disabling Bluetooth...", "cmd bluetooth_manager disable || svc bluetooth disable"
                ) +
                    restarts +
                    listOf(
                        "> Enabling Bluetooth...", "cmd bluetooth_manager enable || svc bluetooth enable",
                        "> Checking Bluetooth state...", "dumpsys bluetooth_manager",
                        "> Repair complete!\n\nBT_DUMP"
                    ),
                run.transcript
            )
            assertEquals("Repair complete!\n\nBT_DUMP", run.output)
            assertEquals(5000L, run.elapsedMs)
        }
    }

    @Test
    fun deviceTweaks_advancedBluetoothRepairClearsDataAndCyclesAirplaneModeOnAndroid11Plus() = runTest {
        val clears = listOf(
            "com.android.bluetooth", "com.android.bluetoothmidiservice", "com.google.android.bluetooth",
            "com.android.networkstack", "com.samsung.android.bluetooth"
        ).flatMap { listOf("> Clearing data for $it...", "pm clear $it") }
        val prefix = listOf(
            "> Starting Bluetooth Repair...",
            "> Disabling Bluetooth...", "cmd bluetooth_manager disable || svc bluetooth disable"
        ) +
            clears + "getprop ro.build.version.sdk"
        val suffix = listOf(
            "> Enabling Bluetooth...", "cmd bluetooth_manager enable || svc bluetooth enable",
            "> Checking Bluetooth state...", "dumpsys bluetooth_manager",
            "> Repair complete!\n\nBT_DUMP"
        )

        val airplaneOff = runTweak(sdk = 30, airplaneMode = "0") { executeBluetoothRepair(BluetoothOem.SAMSUNG, isAdvanced = true) }
        assertEquals(
            prefix + listOf(
                "> Cycling radios via Airplane Mode...", "settings get global airplane_mode_on",
                "cmd connectivity airplane-mode enable", "cmd connectivity airplane-mode disable"
            ) + suffix,
            airplaneOff.transcript
        )
        assertEquals(13000L, airplaneOff.elapsedMs)

        val airplaneOn = runTweak(sdk = 34, airplaneMode = "1") { executeBluetoothRepair(BluetoothOem.SAMSUNG, isAdvanced = true) }
        assertEquals(
            prefix + listOf(
                "> Cycling radios via Airplane Mode...", "settings get global airplane_mode_on",
                "cmd connectivity airplane-mode disable", "cmd connectivity airplane-mode enable"
            ) + suffix,
            airplaneOn.transcript
        )

        val oldSdk = runTweak(sdk = 29) { executeBluetoothRepair(BluetoothOem.SAMSUNG, isAdvanced = true) }
        assertEquals(
            prefix + listOf(
                "> Skipping Airplane Mode radio cycle (cmd connectivity requires Android 11+; target SDK is 29)."
            ) + suffix,
            oldSdk.transcript
        )
        assertEquals(5000L, oldSdk.elapsedMs)
        assertEquals("Repair complete!\n\nBT_DUMP", oldSdk.output)
    }

    @Test
    fun deviceTweaks_bluetoothQuirkExemptsInstalledPackagesOnlyForXiaomiAndOnePlus() = runTest {
        val xiaomi = runTweak(installed = setOf("com.android.bluetooth", "com.miui.bluetooth")) {
            applyBluetoothQuirk(BluetoothOem.XIAOMI)
        }
        assertEquals(
            listOf(
                "> Applying XIAOMI Bluetooth Doze & background exemptions...",
                "pm path com.android.bluetooth",
                "dumpsys deviceidle whitelist +com.android.bluetooth",
                "cmd appops set com.android.bluetooth RUN_ANY_IN_BACKGROUND allow",
                "pm path com.xiaomi.bluetooth",
                "pm path com.miui.bluetooth",
                "dumpsys deviceidle whitelist +com.miui.bluetooth",
                "cmd appops set com.miui.bluetooth RUN_ANY_IN_BACKGROUND allow",
                "> Exempted 2 Bluetooth package(s):\ncom.android.bluetooth\ncom.miui.bluetooth"
            ),
            xiaomi.transcript
        )
        assertEquals("Exempted 2 Bluetooth package(s):\ncom.android.bluetooth\ncom.miui.bluetooth", xiaomi.output)

        val oneplus = runTweak { applyBluetoothQuirk(BluetoothOem.ONEPLUS) }
        assertEquals(
            listOf(
                "> Applying ONEPLUS Bluetooth Doze & background exemptions...",
                "pm path com.android.bluetooth",
                "pm path com.oplus.bluetooth",
                "pm path com.coloros.bluetooth",
                "pm path com.nearme.bluetooth",
                "> No matching Bluetooth packages found for ONEPLUS."
            ),
            oneplus.transcript
        )
        assertEquals("No matching Bluetooth packages found for ONEPLUS.", oneplus.output)

        for (oem in listOf(BluetoothOem.AOSP, BluetoothOem.SAMSUNG, BluetoothOem.MOTOROLA)) {
            val none = runTweak { applyBluetoothQuirk(oem) }
            assertEquals(oem.name, emptyList<String>(), none.transcript)
            assertEquals(oem.name, "Ready.", none.output)
        }
    }

    @Test
    fun deviceTweaks_audioBypassSetsCsdWarningOnlyOnAndroid14Plus() = runTest {
        val common = listOf(
            "settings put global safe_media_volume_enabled 0",
            "settings put global audio_safe_volume_state 3"
        )
        val sdk34 = runTweak(sdk = 34) { executeAudioBypass() }
        assertEquals(
            listOf(
                "> Applying Audio Safety Bypass...",
                "getprop ro.build.version.sdk",
                "settings put global audio_safe_csd_next_warning 999999.0"
            ) + common + "> Bypass applied successfully! Rebooting the device is highly recommended.",
            sdk34.transcript
        )
        assertEquals("Bypass applied successfully! Rebooting the device is highly recommended.", sdk34.output)

        val sdk33 = runTweak(sdk = 33) { executeAudioBypass() }
        assertEquals(
            listOf("> Applying Audio Safety Bypass...", "getprop ro.build.version.sdk") + common +
                "> Bypass applied successfully! Rebooting the device is highly recommended.",
            sdk33.transcript
        )
    }
}

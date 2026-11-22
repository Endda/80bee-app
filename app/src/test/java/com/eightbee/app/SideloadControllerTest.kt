package com.eightbee.app

import com.eightbee.app.features.SideloadController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SideloadControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newSideloadConfig(vararg contents: Pair<String, ByteArray>): SideloadState.ConfigReady {
        val dir = tempFolder.newFolder()
        val files = contents.map { (name, bytes) ->
            File(dir, name).apply { writeBytes(bytes) }
        }
        return SideloadState.ConfigReady(
            appLabel = "Test App",
            packageName = "com.example.test",
            versionName = "1.0",
            appIcon = null,
            totalSize = files.sumOf { it.length() },
            apkFiles = files,
            isArchive = files.size > 1,
            archiveName = "test.apks",
            targetDeviceSdk = 34
        )
    }

    @Test
    fun sideloadController_executeInstallAppliesAllFlagsStreamsSplitsAndCleansUpOnSuccess() = runBlocking {
        val config = newSideloadConfig(
            "base.apk" to byteArrayOf(1, 2, 3),
            "split_en.apk" to byteArrayOf(4, 5)
        )
        val fakeConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when {
                    cmd.startsWith("cmd package install-create") -> "Success: created install session [42]"
                    cmd == "cmd package install-commit 42" -> "Success"
                    else -> ""
                }
            },
            shellWithInputResponder = { _, _ -> "Success: streamed" }
        )
        val controller = SideloadController(
            scope = this,
            connectionProvider = { fakeConn },
            ioDispatcher = Dispatchers.Unconfined
        )

        controller.executeInstall(
            config = config,
            options = SideloadInstallOptions(
                bypassLowTargetSdk = true,
                grantAllPermissions = true,
                allowDowngrade = true,
                testOnly = true,
                installToSdCard = true
            )
        )

        assertEquals(SideloadState.Success("Test App"), controller.state.value)
        assertEquals(
            listOf(
                "cmd package install-create -r --bypass-low-target-sdk-block -g -d -t -s",
                "cmd package install-commit 42"
            ),
            fakeConn.executedShellCommands
        )
        assertEquals(2, fakeConn.executedShellInputCommands.size)
        assertEquals("cmd package install-write -S 3 42 split_0.apk -", fakeConn.executedShellInputCommands[0].first)
        assertTrue(fakeConn.executedShellInputCommands[0].second.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("cmd package install-write -S 2 42 split_1.apk -", fakeConn.executedShellInputCommands[1].first)
        assertTrue(fakeConn.executedShellInputCommands[1].second.contentEquals(byteArrayOf(4, 5)))
        assertTrue(config.apkFiles.none { it.exists() })
    }

    @Test
    fun sideloadController_executeInstallCleansUpOnUnavailableConnectionAndSessionCreateErrors() = runBlocking {
        val unavailableConfig = newSideloadConfig("base.apk" to byteArrayOf(1))
        val unavailableConn = FakeConnectionManager(available = false)
        val unavailableController = SideloadController(
            scope = this,
            connectionProvider = { unavailableConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        unavailableController.executeInstall(unavailableConfig)
        assertEquals(
            SideloadState.Error("Device connection not active or unauthorized.", InstallStep.CHECK_CONNECTION),
            unavailableController.state.value
        )
        assertTrue(unavailableConfig.apkFiles.none { it.exists() })

        val badSessionConfig = newSideloadConfig("base.apk" to byteArrayOf(1))
        val badSessionConn = FakeConnectionManager(shellResponder = { "Success: no id here" })
        val badSessionController = SideloadController(
            scope = this,
            connectionProvider = { badSessionConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        badSessionController.executeInstall(badSessionConfig)
        assertEquals(
            SideloadState.Error("Could not parse session ID from: Success: no id here", InstallStep.CREATE_SESSION),
            badSessionController.state.value
        )
        assertTrue(badSessionConfig.apkFiles.none { it.exists() })

        val throwingCreateConfig = newSideloadConfig("base.apk" to byteArrayOf(1))
        val throwingCreateConn = FakeConnectionManager(shellResponder = { throw IOException("link down") })
        val throwingCreateController = SideloadController(
            scope = this,
            connectionProvider = { throwingCreateConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        throwingCreateController.executeInstall(throwingCreateConfig)
        assertEquals(
            SideloadState.Error("Failed to create install session: link down", InstallStep.CREATE_SESSION),
            throwingCreateController.state.value
        )
        assertTrue(throwingCreateConfig.apkFiles.none { it.exists() })
    }

    @Test
    fun sideloadController_executeInstallAbandonsSessionAndCleansUpOnStreamAndCommitFailures() = runBlocking {
        val streamFailConfig = newSideloadConfig("base.apk" to byteArrayOf(9, 9))
        val streamFailConn = FakeConnectionManager(
            shellResponder = { cmd ->
                if (cmd.startsWith("cmd package install-create")) "Success: [77]" else ""
            },
            shellWithInputResponder = { _, _ -> "Failure [INSTALL_FAILED_INVALID_APK]" }
        )
        val streamFailController = SideloadController(
            scope = this,
            connectionProvider = { streamFailConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        streamFailController.executeInstall(streamFailConfig)
        assertEquals(
            SideloadState.Error(
                "Failed to write base.apk: Failure [INSTALL_FAILED_INVALID_APK]",
                InstallStep.STREAM_APKS
            ),
            streamFailController.state.value
        )
        assertTrue(streamFailConn.executedShellCommands.contains("cmd package install-abandon 77"))
        assertTrue(streamFailConfig.apkFiles.none { it.exists() })

        val commitBlankConfig = newSideloadConfig("base.apk" to byteArrayOf(7))
        val commitBlankConn = FakeConnectionManager(
            shellResponder = { cmd ->
                when {
                    cmd.startsWith("cmd package install-create") -> "Success: [88]"
                    cmd == "cmd package install-commit 88" -> "   "
                    else -> ""
                }
            },
            shellWithInputResponder = { _, _ -> "Success" }
        )
        val commitBlankController = SideloadController(
            scope = this,
            connectionProvider = { commitBlankConn },
            ioDispatcher = Dispatchers.Unconfined
        )
        commitBlankController.executeInstall(commitBlankConfig)
        assertEquals(
            SideloadState.Error(
                "Installation failed: No response received from package manager.",
                InstallStep.COMMIT_INSTALL
            ),
            commitBlankController.state.value
        )
        assertTrue(commitBlankConfig.apkFiles.none { it.exists() })
    }
}

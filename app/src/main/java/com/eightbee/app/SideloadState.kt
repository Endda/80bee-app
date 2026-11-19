package com.eightbee.app

import android.graphics.Bitmap
import androidx.annotation.StringRes
import java.io.File

sealed class SideloadState {
    object Idle : SideloadState()
    object CopyingAndParsing : SideloadState()

    data class ConfigReady(
        val appLabel: String,
        val packageName: String,
        val versionName: String,
        val appIcon: Bitmap?,
        val totalSize: Long,
        val apkFiles: List<File>,
        val isArchive: Boolean,
        val archiveName: String
    ) : SideloadState()

    data class Installing(
        val currentStep: InstallStep,
        val completedSteps: Set<InstallStep>,
        val currentFileProgress: String = ""
    ) : SideloadState()

    data class Success(val appLabel: String) : SideloadState()

    data class Error(val message: String, val step: InstallStep) : SideloadState()
}

enum class InstallStep {
    CHECK_CONNECTION,
    CREATE_SESSION,
    STREAM_APKS,
    COMMIT_INSTALL;

    @StringRes
    fun labelResId(): Int {
        return when (this) {
            CHECK_CONNECTION -> R.string.sideload_step_check_connection
            CREATE_SESSION -> R.string.sideload_step_create_session
            STREAM_APKS -> R.string.sideload_step_stream_apks
            COMMIT_INSTALL -> R.string.sideload_step_commit_install
        }
    }

    fun getDisplayName(): String {
        return when (this) {
            CHECK_CONNECTION -> "Verify Connection"
            CREATE_SESSION -> "Create Installation Session"
            STREAM_APKS -> "Transfer Installation Files"
            COMMIT_INSTALL -> "Finalize Installation"
        }
    }
}

package com.eightbee.app

import java.io.File

data class MagiskDeviceData(
    val codename: String,
    val buildId: String,
    val apiLevel: Int,
    val previewSdk: Int,
    val isBeta: Boolean,
    val abi: String,
    val oemAllowed: Boolean,
    val currentSlot: String,
    val isUnlocked: Boolean,
    val targetPartition: String
)

data class LogEntry(
    val type: String, // "INFO", "SUCCESS", "WAIT", "ERROR", "PROMPT", "WARNING"
    val message: String
)

sealed interface ReconPhase {
    object Idle : ReconPhase
    object InProgress : ReconPhase
    object Succeeded : ReconPhase
    data class Failed(val message: String) : ReconPhase
}

sealed interface FirmwarePhase {
    object Idle : FirmwarePhase
    data class InProgress(val label: String, val progress: Float) : FirmwarePhase
    object Loaded : FirmwarePhase
    data class Failed(val message: String) : FirmwarePhase
}

sealed interface MagiskExtractPhase {
    object Idle : MagiskExtractPhase
    data class InProgress(val progress: Float) : MagiskExtractPhase
    object Ready : MagiskExtractPhase
    data class Failed(val message: String) : MagiskExtractPhase
}

sealed interface PatchPhase {
    object Idle : PatchPhase
    data class InProgress(val progress: Float, val label: String) : PatchPhase
    object Succeeded : PatchPhase
    data class Failed(val message: String) : PatchPhase
}

sealed interface FlashPhase {
    object AwaitingHandshake : FlashPhase
    object RebootSent : FlashPhase
    object FastbootReady : FlashPhase
    object Flashing : FlashPhase
    object Succeeded : FlashPhase
    data class Failed(val message: String) : FlashPhase
}

data class PixelMagiskPatcherState(
    val isActive: Boolean = false,
    val currentStep: Int = 1,
    val reconPhase: ReconPhase = ReconPhase.Idle,
    val deviceData: MagiskDeviceData? = null,

    // Phase 2
    val bootFile: File? = null,
    val bootFileName: String = "",
    val bootFileSize: String = "",
    val firmwarePhase: FirmwarePhase = FirmwarePhase.Idle,
    val imageWarning: String? = null,

    // Phase 3
    val magiskApkFile: File? = null,
    val magiskExtractPhase: MagiskExtractPhase = MagiskExtractPhase.Idle,
    val extractLogs: List<LogEntry> = emptyList(),

    // Phase 4
    val patchPhase: PatchPhase = PatchPhase.Idle,
    val patchLogs: List<LogEntry> = emptyList(),
    val patchedFile: File? = null,

    // Phase 5
    val flashPhase: FlashPhase = FlashPhase.AwaitingHandshake,
    val isFastbootConnected: Boolean = false,
    val showUnlockWarning: Boolean = false,

    // Phase 6
    val finalApkInstalled: Boolean = false
)

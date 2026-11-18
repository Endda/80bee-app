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

data class PixelMagiskPatcherState(
    val isActive: Boolean = false,
    val currentStep: Int = 1,
    val isReconning: Boolean = false,
    val deviceData: MagiskDeviceData? = null,
    
    // Phase 2
    val bootFile: File? = null,
    val bootFileName: String = "",
    val bootFileSize: String = "",
    val integrityStatus: String = "READY_FOR_IMAGE",
    val integrityProgress: Float = 0f,
    val imageWarning: String? = null,
    val imageError: String? = null,
    
    // Phase 3
    val magiskApkFile: File? = null,
    val appStatus: String = "STATUS: IDLE",
    val extractProgress: Float = 0f,
    val extractLogs: List<LogEntry> = emptyList(),
    val magiskComponentsReady: Boolean = false,
    
    // Phase 4
    val patchStatus: String = "READY_FOR_PATCH",
    val patchProgress: Float = 0f,
    val patchProgressLabel: String = "",
    val patchLogs: List<LogEntry> = emptyList(),
    val patchedFile: File? = null,
    
    // Phase 5
    val fastbootStatus: String = "Awaiting Fastboot Handshake",
    val flashStep: Int = 1, // 1: Reboot ADB, 2: Connect Fastboot, 3: Flash
    val isFastbootConnected: Boolean = false,
    val flashComplete: Boolean = false,
    val showUnlockWarning: Boolean = false,
    
    // Phase 6
    val finalApkInstalled: Boolean = false
)

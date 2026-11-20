package com.eightbee.app.features

import androidx.annotation.StringRes
import com.eightbee.app.R

enum class FastbootToolCategory {
    DIAGNOSTIC,
    REBOOT,
    SLOT_AND_OEM
}

data class CuratedFastbootAction(
    val command: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val category: FastbootToolCategory,
    val requiresConfirmation: Boolean = false
)

sealed interface FastbootValidationResult {
    data class Allowed(val normalizedCommand: String) : FastbootValidationResult
    data class Blocked(val reason: String) : FastbootValidationResult
}

/** Allowlist policy and curated action catalog for Fastboot execution. */
object FastbootCommandPolicy {

    val CURATED_UTILITY_ACTIONS: List<CuratedFastbootAction> = listOf(
        CuratedFastbootAction(
            command = "getvar all",
            titleRes = R.string.bootloader_tool_getvar_all_title,
            descriptionRes = R.string.bootloader_tool_getvar_all_desc,
            category = FastbootToolCategory.DIAGNOSTIC
        ),
        CuratedFastbootAction(
            command = "getvar current-slot",
            titleRes = R.string.bootloader_tool_getvar_slot_title,
            descriptionRes = R.string.bootloader_tool_getvar_slot_desc,
            category = FastbootToolCategory.DIAGNOSTIC
        ),
        CuratedFastbootAction(
            command = "getvar unlocked",
            titleRes = R.string.bootloader_tool_getvar_unlocked_title,
            descriptionRes = R.string.bootloader_tool_getvar_unlocked_desc,
            category = FastbootToolCategory.DIAGNOSTIC
        ),
        CuratedFastbootAction(
            command = "getvar is-userspace",
            titleRes = R.string.bootloader_tool_getvar_userspace_title,
            descriptionRes = R.string.bootloader_tool_getvar_userspace_desc,
            category = FastbootToolCategory.DIAGNOSTIC
        ),
        CuratedFastbootAction(
            command = "reboot-bootloader",
            titleRes = R.string.bootloader_tool_reboot_bootloader_title,
            descriptionRes = R.string.bootloader_tool_reboot_bootloader_desc,
            category = FastbootToolCategory.REBOOT
        ),
        CuratedFastbootAction(
            command = "reboot-fastboot",
            titleRes = R.string.bootloader_tool_reboot_fastbootd_title,
            descriptionRes = R.string.bootloader_tool_reboot_fastbootd_desc,
            category = FastbootToolCategory.REBOOT
        ),
        CuratedFastbootAction(
            command = "reboot-recovery",
            titleRes = R.string.bootloader_tool_reboot_recovery_title,
            descriptionRes = R.string.bootloader_tool_reboot_recovery_desc,
            category = FastbootToolCategory.REBOOT
        ),
        CuratedFastbootAction(
            command = "reboot",
            titleRes = R.string.bootloader_tool_reboot_system_title,
            descriptionRes = R.string.bootloader_tool_reboot_system_desc,
            category = FastbootToolCategory.REBOOT
        ),
        CuratedFastbootAction(
            command = "set_active a",
            titleRes = R.string.bootloader_tool_slot_a_title,
            descriptionRes = R.string.bootloader_tool_slot_a_desc,
            category = FastbootToolCategory.SLOT_AND_OEM,
            requiresConfirmation = true
        ),
        CuratedFastbootAction(
            command = "set_active b",
            titleRes = R.string.bootloader_tool_slot_b_title,
            descriptionRes = R.string.bootloader_tool_slot_b_desc,
            category = FastbootToolCategory.SLOT_AND_OEM,
            requiresConfirmation = true
        ),
        CuratedFastbootAction(
            command = "oem device-info",
            titleRes = R.string.bootloader_tool_oem_info_title,
            descriptionRes = R.string.bootloader_tool_oem_info_desc,
            category = FastbootToolCategory.SLOT_AND_OEM,
            requiresConfirmation = true
        )
    )

    private val ALLOWED_UTILITY_COMMANDS = CURATED_UTILITY_ACTIONS.map { it.command }.toSet()

    private val DESTRUCTIVE_VERBS = setOf(
        "erase",
        "format",
        "flash",
        "flashall",
        "update",
        "boot",
        "continue",
        "stage",
        "download",
        "set_active"
    )

    private val WIZARD_COMMAND_REGEX = Regex(
        "^(flashing (unlock|lock|unlock_critical|lock_critical)|oem (unlock|lock|relock)( [A-Za-z0-9_=-]{1,128})?)$"
    )

    private fun normalize(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    fun isAllowedUtilityCommand(raw: String): Boolean =
        normalize(raw) in ALLOWED_UTILITY_COMMANDS

    fun validateWizardCustomCommand(raw: String): FastbootValidationResult {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) {
            return FastbootValidationResult.Blocked("Enter a bootloader lock or unlock command.")
        }
        if (normalized.any { it.isISOControl() } ||
            normalized.contains(';') ||
            normalized.contains('&') ||
            normalized.contains('|') ||
            normalized.contains('$') ||
            normalized.contains('`')
        ) {
            return FastbootValidationResult.Blocked("Command contains disallowed control or shell characters.")
        }
        val firstToken = normalized.substringBefore(' ').lowercase()
        val secondToken = normalized.substringAfter(' ', "").substringBefore(':').lowercase()
        if (firstToken in DESTRUCTIVE_VERBS || secondToken in DESTRUCTIVE_VERBS) {
            return FastbootValidationResult.Blocked(
                "Destructive or flashing command '$firstToken' is blocked in this workflow."
            )
        }
        if (!WIZARD_COMMAND_REGEX.matches(normalized)) {
            return FastbootValidationResult.Blocked(
                "Only vetted unlock/lock commands (e.g. 'flashing unlock', 'oem unlock [token]') are allowed."
            )
        }
        return FastbootValidationResult.Allowed(normalized)
    }
}

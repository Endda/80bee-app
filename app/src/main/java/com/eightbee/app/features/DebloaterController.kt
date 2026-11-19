package com.eightbee.app.features

import android.app.Application
import android.net.Uri
import com.eightbee.app.BloatInfo
import com.eightbee.app.PackageDatabase
import com.eightbee.app.PresetPkg
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Handles bloatware scanning, package disable/uninstall/restore, and snapshot export/import. */
class DebloaterController(
    private val application: Application,
    private val runExclusive: (initialStatus: String, notifySnackbar: Boolean, block: suspend (ConnectionManager) -> String) -> Unit
) {
    private val _debloatList = MutableStateFlow<List<BloatInfo>>(emptyList())
    val debloatList: StateFlow<List<BloatInfo>> = _debloatList.asStateFlow()

    private val _degoogleList = MutableStateFlow<List<PresetPkg>>(emptyList())
    val degoogleList: StateFlow<List<PresetPkg>> = _degoogleList.asStateFlow()

    private val _desamsungList = MutableStateFlow<List<PresetPkg>>(emptyList())
    val desamsungList: StateFlow<List<PresetPkg>> = _desamsungList.asStateFlow()

    private val _restoreList = MutableStateFlow<List<PresetPkg>>(emptyList())
    val restoreList: StateFlow<List<PresetPkg>> = _restoreList.asStateFlow()

    private val _snapshotFeedback = MutableStateFlow<String?>(null)
    val snapshotFeedback: StateFlow<String?> = _snapshotFeedback.asStateFlow()

    private suspend fun runPmListPackages(conn: ConnectionManager, command: String): List<String> {
        val raw = conn.runShellCommand(command).trim()
        if (raw.startsWith("Error", ignoreCase = true)) {
            throw IOException(raw)
        }
        return parsePmListPackages(raw)
    }

    fun scanBloatware(safetyLevel: String) {
        runExclusive("Scanning for bloatware...", true) { conn ->
            val manufacturer = conn.runShellCommand("getprop ro.product.manufacturer").trim()
            val brand = conn.runShellCommand("getprop ro.product.brand").trim()
            val installed = runPmListPackages(conn, "pm list packages").toSet()

            val db = PackageDatabase.loadUltimateDb(application)
            val allowedLevels = PackageDatabase.allowedSafetyLevels(safetyLevel)

            val filtered = db.values.filter { bloat ->
                installed.contains(bloat.packageName) &&
                    allowedLevels.contains(bloat.safety.lowercase()) &&
                    PackageDatabase.matchesOem(bloat.oems, manufacturer, brand)
            }.sortedBy { it.label }

            _debloatList.value = filtered
            "Scan complete. Found ${filtered.size} bloatware packages."
        }
    }

    fun scanDegoogle(maxTier: Int) {
        runExclusive("Scanning for Google packages...", true) { conn ->
            val installed = runPmListPackages(conn, "pm list packages").toSet()
            val filtered = PackageDatabase.loadDegoogleDb(application).filter { preset ->
                installed.contains(preset.id) && preset.tier <= maxTier
            }
            _degoogleList.value = filtered
            "Scan complete. Found ${filtered.size} Google packages."
        }
    }

    fun scanDesamsung(maxTier: Int) {
        runExclusive("Scanning for Samsung packages...", true) { conn ->
            val installed = runPmListPackages(conn, "pm list packages").toSet()
            val filtered = PackageDatabase.loadSamsungDb(application).filter { preset ->
                installed.contains(preset.id) && preset.tier <= maxTier
            }
            _desamsungList.value = filtered
            "Scan complete. Found ${filtered.size} Samsung packages."
        }
    }

    fun disablePackages(packages: List<String>, isUninstall: Boolean) {
        runExclusive("Processing package removal...", true) { conn ->
            var success = 0
            var fail = 0
            var lastFailure: String? = null
            for (pkg in packages) {
                if (!PackageDatabase.isValidPackageName(pkg)) {
                    fail++
                    lastFailure = "Skipped invalid package name: $pkg"
                    continue
                }
                val cmd = if (isUninstall) {
                    "pm uninstall -k --user 0 $pkg"
                } else {
                    "pm disable-user --user 0 $pkg"
                }
                val out = conn.runShellCommand(cmd)
                if (out.lowercase().contains("success") || out.lowercase().contains("new state: disabled-user")) {
                    success++
                } else {
                    fail++
                    lastFailure = "Failed on $pkg: $out"
                }
            }
            if (lastFailure != null && success == 0) {
                "Removal complete. Success: $success, Failed: $fail ($lastFailure)"
            } else {
                "Removal complete. Success: $success, Failed: $fail"
            }
        }
    }

    fun restorePackages(packages: List<String>) {
        runExclusive("Restoring packages...", true) { conn ->
            var success = 0
            var fail = 0
            for (pkg in packages) {
                if (!PackageDatabase.isValidPackageName(pkg)) {
                    fail++
                    continue
                }
                var out = conn.runShellCommand("pm enable $pkg")
                if (out.lowercase().contains("new state: enabled") || out.lowercase().contains("success")) {
                    success++
                } else {
                    out = conn.runShellCommand("cmd package install-existing --user 0 $pkg")
                    if (out.lowercase().contains("installed") || out.lowercase().contains("success")) {
                        success++
                    } else {
                        fail++
                    }
                }
            }
            "Restore complete. Success: $success, Failed: $fail"
        }
    }

    fun scanDisabledPackages() {
        runExclusive("Scanning disabled packages...", true) { conn ->
            val disabled = runPmListPackages(conn, "pm list packages -d")
            _restoreList.value = disabled.map { PresetPkg(it, it, 1, "Currently disabled package.", "") }
            _snapshotFeedback.value = null
            "Found ${disabled.size} disabled package(s)."
        }
    }

    fun exportSnapshotToUri(uri: Uri) {
        runExclusive("Exporting package snapshot...", true) { conn ->
            try {
                val packages = runPmListPackages(conn, "pm list packages")
                val obj = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("packages", JSONArray().apply { packages.forEach { put(it) } })
                }
                val outputStream = application.contentResolver.openOutputStream(uri)
                    ?: throw IOException("Unable to open destination file.")
                outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(obj.toString(2))
                }
                val msg = "Exported snapshot with ${packages.size} package(s)."
                _snapshotFeedback.value = msg
                msg
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "Failed to export snapshot: ${e.message ?: "Write error"}"
                _snapshotFeedback.value = msg
                msg
            }
        }
    }

    fun importSnapshotFromUri(uri: Uri) {
        runExclusive("Comparing snapshot against device...", true) { conn ->
            try {
                val content = readUriTextBounded(application, uri)
                val parsed = PackageDatabase.parseSnapshotPackageNames(content)
                val installed = runPmListPackages(conn, "pm list packages").toSet()

                val missing = parsed.validPackages.filter { !installed.contains(it) }
                _restoreList.value = missing.map {
                    PresetPkg(it, it, 1, "Backup snapshot package (uninstalled/disabled).", "")
                }

                val statusMsg = buildString {
                    append("Loaded ${missing.size} missing package(s) from snapshot.")
                    if (parsed.rejectedCount > 0) {
                        append(" Ignored ${parsed.rejectedCount} invalid package entry/entries.")
                    }
                }
                _snapshotFeedback.value = statusMsg
                statusMsg
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _restoreList.value = emptyList()
                val msg = "Failed to load snapshot: ${e.message ?: "Invalid format"}"
                _snapshotFeedback.value = msg
                msg
            }
        }
    }
}

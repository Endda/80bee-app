package com.eightbee.app.features

import android.app.Application
import android.net.Uri
import com.eightbee.app.BloatInfo
import com.eightbee.app.PackageDatabase
import com.eightbee.app.PresetPkg
import com.eightbee.app.SnapshotParseResult
import com.eightbee.app.connection.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream

/** User-visible snapshot export/import outcome with explicit error/warning flag. */
data class SnapshotFeedback(
    val message: String,
    val isError: Boolean = false
)

/** Handles bloatware scanning, package disable/uninstall/restore, and snapshot export/import. */
class DebloaterController internal constructor(
    private val loadUltimateDb: () -> Map<String, BloatInfo>,
    private val loadDegoogleDb: () -> List<PresetPkg>,
    private val loadSamsungDb: () -> List<PresetPkg>,
    private val openOutputStream: (Uri) -> OutputStream?,
    private val readUriText: (Uri) -> String,
    private val runExclusive: (initialStatus: String, notifySnackbar: Boolean, block: suspend (ConnectionManager) -> String) -> Unit,
    private val encodeSnapshot: (List<String>) -> String = { packages ->
        JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("packages", JSONArray().apply { packages.forEach { put(it) } })
        }.toString(2)
    },
    private val parseSnapshot: (String) -> SnapshotParseResult = PackageDatabase::parseSnapshotPackageNames
) {
    constructor(
        application: Application,
        runExclusive: (initialStatus: String, notifySnackbar: Boolean, block: suspend (ConnectionManager) -> String) -> Unit
    ) : this(
        loadUltimateDb = { PackageDatabase.loadUltimateDb(application) },
        loadDegoogleDb = { PackageDatabase.loadDegoogleDb(application) },
        loadSamsungDb = { PackageDatabase.loadSamsungDb(application) },
        openOutputStream = { uri -> application.contentResolver.openOutputStream(uri) },
        readUriText = { uri -> readUriTextBounded(application, uri) },
        runExclusive = runExclusive
    )

    private val _debloatList = MutableStateFlow<List<BloatInfo>>(emptyList())
    val debloatList: StateFlow<List<BloatInfo>> = _debloatList.asStateFlow()

    private val _degoogleList = MutableStateFlow<List<PresetPkg>>(emptyList())
    val degoogleList: StateFlow<List<PresetPkg>> = _degoogleList.asStateFlow()

    private val _desamsungList = MutableStateFlow<List<PresetPkg>>(emptyList())
    val desamsungList: StateFlow<List<PresetPkg>> = _desamsungList.asStateFlow()

    private val _restoreList = MutableStateFlow<List<PresetPkg>>(emptyList())
    val restoreList: StateFlow<List<PresetPkg>> = _restoreList.asStateFlow()

    private val _disabledPackages = MutableStateFlow<Set<String>>(emptySet())
    val disabledPackages: StateFlow<Set<String>> = _disabledPackages.asStateFlow()

    private val _snapshotFeedback = MutableStateFlow<SnapshotFeedback?>(null)
    val snapshotFeedback: StateFlow<SnapshotFeedback?> = _snapshotFeedback.asStateFlow()

    private suspend fun runPmListPackages(conn: ConnectionManager, command: String): List<String> {
        val raw = conn.runShellCommand(command).trim()
        if (raw.startsWith("Error", ignoreCase = true)) {
            throw IOException(raw)
        }
        return parsePmListPackages(raw)
    }

    private suspend fun queryInstalledAndDisabled(conn: ConnectionManager): Set<String> {
        val installed = runPmListPackages(conn, "pm list packages").toSet()
        val disabled = runPmListPackages(conn, "pm list packages -d").toSet()
        _disabledPackages.value = disabled
        return installed
    }

    fun scanBloatware(safetyLevel: String) {
        runExclusive("Scanning for bloatware...", true) { conn ->
            val manufacturer = conn.runShellCommand("getprop ro.product.manufacturer").trim()
            val brand = conn.runShellCommand("getprop ro.product.brand").trim()
            val installed = queryInstalledAndDisabled(conn)

            val db = loadUltimateDb()
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

    fun scanDegoogle(maxTier: Int) = scanPresetCatalog(
        initialStatus = "Scanning for Google packages...",
        packageCategory = "Google",
        maxTier = maxTier,
        loadCatalog = loadDegoogleDb,
        targetFlow = _degoogleList
    )

    fun scanDesamsung(maxTier: Int) = scanPresetCatalog(
        initialStatus = "Scanning for Samsung packages...",
        packageCategory = "Samsung",
        maxTier = maxTier,
        loadCatalog = loadSamsungDb,
        targetFlow = _desamsungList
    )

    private fun scanPresetCatalog(
        initialStatus: String,
        packageCategory: String,
        maxTier: Int,
        loadCatalog: () -> List<PresetPkg>,
        targetFlow: MutableStateFlow<List<PresetPkg>>
    ) {
        runExclusive(initialStatus, true) { conn ->
            val installed = queryInstalledAndDisabled(conn)
            val filtered = loadCatalog().filter { preset ->
                installed.contains(preset.id) && preset.tier <= maxTier
            }
            targetFlow.value = filtered
            "Scan complete. Found ${filtered.size} $packageCategory packages."
        }
    }

    fun disablePackages(packages: List<String>, isUninstall: Boolean) {
        runExclusive("Processing package removal...", true) { conn ->
            var success = 0
            var fail = 0
            var lastFailure: String? = null
            val succeededPackages = mutableSetOf<String>()
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
                    succeededPackages.add(pkg)
                } else {
                    fail++
                    lastFailure = "Failed on $pkg: $out"
                }
            }
            if (succeededPackages.isNotEmpty()) {
                if (isUninstall) {
                    _disabledPackages.update { it - succeededPackages }
                    _debloatList.update { list -> list.filterNot { it.packageName in succeededPackages } }
                    _degoogleList.update { list -> list.filterNot { it.id in succeededPackages } }
                    _desamsungList.update { list -> list.filterNot { it.id in succeededPackages } }
                    _restoreList.update { list -> list.filterNot { it.id in succeededPackages } }
                } else {
                    _disabledPackages.update { it + succeededPackages }
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
            val restoredPackages = mutableSetOf<String>()
            for (pkg in packages) {
                if (!PackageDatabase.isValidPackageName(pkg)) {
                    fail++
                    continue
                }
                var out = conn.runShellCommand("pm enable $pkg")
                if (out.lowercase().contains("new state: enabled") || out.lowercase().contains("success")) {
                    success++
                    restoredPackages.add(pkg)
                } else {
                    out = conn.runShellCommand("cmd package install-existing --user 0 $pkg")
                    if (out.lowercase().contains("installed") || out.lowercase().contains("success")) {
                        success++
                        restoredPackages.add(pkg)
                    } else {
                        fail++
                    }
                }
            }
            if (restoredPackages.isNotEmpty()) {
                _disabledPackages.update { it - restoredPackages }
                _restoreList.update { list -> list.filterNot { it.id in restoredPackages } }
            }
            "Restore complete. Success: $success, Failed: $fail"
        }
    }

    fun scanDisabledPackages() {
        runExclusive("Scanning disabled packages...", true) { conn ->
            val disabled = runPmListPackages(conn, "pm list packages -d")
            _disabledPackages.value = disabled.toSet()
            _restoreList.value = disabled.map { PresetPkg(it, it, 1, "Currently disabled package.", "") }
            _snapshotFeedback.value = null
            "Found ${disabled.size} disabled package(s)."
        }
    }

    fun exportSnapshotToUri(uri: Uri) = exportSnapshot { openOutputStream(uri) }

    internal fun exportSnapshot(openStream: () -> OutputStream?) {
        runExclusive("Exporting package snapshot...", true) { conn ->
            try {
                val packages = runPmListPackages(conn, "pm list packages")
                val payload = encodeSnapshot(packages)
                val outputStream = openStream()
                    ?: throw IOException("Unable to open destination file.")
                outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }
                val msg = "Exported snapshot with ${packages.size} package(s)."
                _snapshotFeedback.value = SnapshotFeedback(msg, isError = false)
                msg
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "Failed to export snapshot: ${e.message ?: "Write error"}"
                _snapshotFeedback.value = SnapshotFeedback(msg, isError = true)
                msg
            }
        }
    }

    fun importSnapshotFromUri(uri: Uri) = importSnapshot { readUriText(uri) }

    internal fun importSnapshot(readText: () -> String) {
        runExclusive("Comparing snapshot against device...", true) { conn ->
            try {
                val content = readText()
                val parsed = parseSnapshot(content)
                val installed = queryInstalledAndDisabled(conn)

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
                _snapshotFeedback.value = SnapshotFeedback(statusMsg, isError = parsed.rejectedCount > 0)
                statusMsg
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _restoreList.value = emptyList()
                val msg = "Failed to load snapshot: ${e.message ?: "Invalid format"}"
                _snapshotFeedback.value = SnapshotFeedback(msg, isError = true)
                msg
            }
        }
    }
}

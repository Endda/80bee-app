package com.eightbee.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class BloatInfo(
    val packageName: String,
    val label: String,
    val description: String,
    val safety: String,
    val oems: List<String>
)

data class PresetPkg(
    val id: String,
    val name: String,
    val tier: Int,
    val description: String,
    val riskFactor: String
)

data class SnapshotParseResult(
    val validPackages: Set<String>,
    val rejectedCount: Int
)

object PackageDatabase {
    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")
    private val UNIVERSAL_OEM_TAGS = setOf("aosp", "qualcomm", "mediatek", "meta", "microsoft")
    private val OEM_ALIASES = mapOf(
        "lge" to "lg"
    )

    fun allowedSafetyLevels(safetyFilter: String): Set<String> {
        return when (safetyFilter.trim().lowercase()) {
            "recommended" -> setOf("recommended")
            "advanced" -> setOf("recommended", "replaceable", "advanced")
            "unsafe" -> setOf("recommended", "replaceable", "advanced", "expert", "danger")
            else -> setOf("recommended")
        }
    }

    fun matchesOem(oems: List<String>, manufacturer: String, brand: String = ""): Boolean {
        if (oems.isEmpty()) return true
        val normalizedManufacturer = normalizeOemTag(manufacturer)
        val normalizedBrand = normalizeOemTag(brand)
        return oems.any { rawTag ->
            val tag = normalizeOemTag(rawTag)
            tag in UNIVERSAL_OEM_TAGS ||
                (normalizedManufacturer.isNotEmpty() && tag == normalizedManufacturer) ||
                (normalizedBrand.isNotEmpty() && tag == normalizedBrand)
        }
    }

    private fun normalizeOemTag(raw: String): String {
        val lower = raw.trim().lowercase()
        return OEM_ALIASES[lower] ?: lower
    }

    fun isValidPackageName(packageName: String): Boolean {
        return packageName.isNotEmpty() && packageName.length <= 255 && PACKAGE_NAME_REGEX.matches(packageName)
    }

    fun parseSnapshotPackageNames(jsonContent: String): SnapshotParseResult {
        val obj = JSONObject(jsonContent)
        val arr = obj.getJSONArray("packages")
        val valid = linkedSetOf<String>()
        var rejected = 0
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            if (item is String && isValidPackageName(item)) {
                valid.add(item)
            } else {
                rejected++
            }
        }
        return SnapshotParseResult(valid, rejected)
    }

    private val dbLock = Any()

    @Volatile
    private var ultimateDb: Map<String, BloatInfo>? = null
    @Volatile
    private var degoogleDb: List<PresetPkg>? = null
    @Volatile
    private var samsungDb: List<PresetPkg>? = null

    fun loadUltimateDb(context: Context): Map<String, BloatInfo> {
        ultimateDb?.let { return it }
        synchronized(dbLock) {
            ultimateDb?.let { return it }
            val map = mutableMapOf<String, BloatInfo>()
            try {
                val jsonString = readAssetString(context, "80bee_ultimate_db.json")
                val jsonObject = JSONObject(jsonString)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val pkgName = keys.next()
                    val obj = jsonObject.getJSONObject(pkgName)
                    val label = obj.optString("label", pkgName)
                    val description = obj.optString("description", "")
                    val safety = obj.optString("safety", "Unknown")

                    val oemsArray = obj.optJSONArray("oems")
                    val oems = mutableListOf<String>()
                    if (oemsArray != null) {
                        for (i in 0 until oemsArray.length()) {
                            oems.add(oemsArray.getString(i))
                        }
                    }
                    map[pkgName] = BloatInfo(pkgName, label, description, safety, oems)
                }
                ultimateDb = map
            } catch (e: Exception) {
                Log.e("PackageDatabase", "Failed to load ultimate DB", e)
            }
            return map
        }
    }

    fun loadDegoogleDb(context: Context): List<PresetPkg> {
        degoogleDb?.let { return it }
        synchronized(dbLock) {
            degoogleDb?.let { return it }
            val list = mutableListOf<PresetPkg>()
            try {
                val jsonString = readAssetString(context, "degoogle_db.json")
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        PresetPkg(
                            id = obj.getString("id"),
                            name = obj.optString("name", ""),
                            tier = obj.optInt("tier", 1),
                            description = obj.optString("description", ""),
                            riskFactor = obj.optString("risk_factor", "")
                        )
                    )
                }
                degoogleDb = list
            } catch (e: Exception) {
                Log.e("PackageDatabase", "Failed to load degoogle DB", e)
            }
            return list
        }
    }

    fun loadSamsungDb(context: Context): List<PresetPkg> {
        samsungDb?.let { return it }
        synchronized(dbLock) {
            samsungDb?.let { return it }
            val list = mutableListOf<PresetPkg>()
            try {
                val jsonString = readAssetString(context, "samsung_db.json")
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        PresetPkg(
                            id = obj.getString("id"),
                            name = obj.optString("name", ""),
                            tier = obj.optInt("tier", 1),
                            description = obj.optString("description", ""),
                            riskFactor = obj.optString("risk_factor", "")
                        )
                    )
                }
                samsungDb = list
            } catch (e: Exception) {
                Log.e("PackageDatabase", "Failed to load samsung DB", e)
            }
            return list
        }
    }

    private fun readAssetString(context: Context, fileName: String): String {
        return context.assets.open(fileName).bufferedReader().use { it.readText() }
    }
}

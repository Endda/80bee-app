package com.eightbee.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

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

object PackageDatabase {
    private var ultimateDb: Map<String, BloatInfo>? = null
    private var degoogleDb: List<PresetPkg>? = null
    private var samsungDb: List<PresetPkg>? = null

    fun loadUltimateDb(context: Context): Map<String, BloatInfo> {
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
            e.printStackTrace()
        }
        return map
    }

    fun loadDegoogleDb(context: Context): List<PresetPkg> {
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
            e.printStackTrace()
        }
        return list
    }

    fun loadSamsungDb(context: Context): List<PresetPkg> {
        samsungDb?.let { return it }
        val list = mutableListOf<PresetPkg>()
        try {
            val jsonString = readAssetString(context, "samsung_db.json")
            // Make sure to handle array or object wrapper if any
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
            e.printStackTrace()
        }
        return list
    }

    private fun readAssetString(context: Context, fileName: String): String {
        val inputStream = context.assets.open(fileName)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val sb = java.lang.StringBuilder()
        var line: String? = reader.readLine()
        while (line != null) {
            sb.append(line)
            line = reader.readLine()
        }
        reader.close()
        inputStream.close()
        return sb.toString()
    }
}

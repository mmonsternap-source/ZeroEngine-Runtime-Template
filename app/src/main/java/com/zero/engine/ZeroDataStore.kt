package com.zero.engine

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ZeroDataStore(
    private val projectDir: File,
    private val onConsoleLog: (String) -> Unit
) {
    val worldFlags = ConcurrentHashMap<String, Any>()
    var currentLang = "zh"
        private set
    private val i18nDicts = mutableMapOf<String, MutableMap<String, String>>()

    fun clearRuntimeCaches() {
        // 模块与数据表缓存已迁移至 QuickJS 原生堆，释放更快
    }

    fun readScriptModuleText(rawPath: String): Pair<String, String>? {
        val cleaned = rawPath.trim().replace("\\", "/").removePrefix("./")
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val normPath = if (cleaned.endsWith(".js")) cleaned else "$cleaned.js"
        val file = File(File(projectDir, "scripts"), normPath)
        if (!file.exists()) {
            onConsoleLog("[MODULE 错误] 找不到脚本模块: scripts/$normPath")
            return null
        }
        return try {
            normPath to file.readText()
        } catch (e: Exception) {
            onConsoleLog("[MODULE 错误 @ $normPath] ${e.message}")
            null
        }
    }

    fun resolveNodeScriptText(rawScript: String, scriptFile: String): String {
        val targetFile = when {
            scriptFile.isNotBlank() -> scriptFile.trim()
            rawScript.startsWith("@file:") -> rawScript.removePrefix("@file:").trim()
            else -> ""
        }
        if (targetFile.isEmpty()) return rawScript
        val cleaned = targetFile.replace("\\", "/").removePrefix("./")
        if (cleaned.contains("..")) return rawScript
        val norm = if (cleaned.endsWith(".js")) cleaned else "$cleaned.js"
        val file = File(File(projectDir, "scripts"), norm)
        return if (file.exists()) file.readText() else rawScript
    }

    fun saveWorldFlagsToDisk(slotName: String): Boolean = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_").ifEmpty { "world_flags" }
        val savesDir = File(projectDir, "saves").apply { mkdirs() }
        val jsonObj = JSONObject()
        for ((k, v) in worldFlags) jsonObj.put(k, v)
        File(savesDir, "flags_$safeName.json").writeText(jsonObj.toString())
        true
    } catch (e: Exception) {
        onConsoleLog("[FLAGS 存盘失败] ${e.message}")
        false
    }

    fun loadWorldFlagsFromDisk(slotName: String): Boolean = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_").ifEmpty { "world_flags" }
        val file = File(File(projectDir, "saves"), "flags_$safeName.json")
        if (!file.exists()) false else {
            val root = JSONObject(file.readText())
            worldFlags.clear()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = root.opt(k)
                if (v != null && v !== JSONObject.NULL) {
                    worldFlags[k] = if (v is Number) v.toDouble() else v
                }
            }
            true
        }
    } catch (e: Exception) {
        onConsoleLog("[FLAGS 读盘失败] ${e.message}")
        false
    }

    fun readDataTableJson(tableName: String): Pair<String, String>? {
        val cleaned = tableName.trim().replace("\\", "/").removePrefix("./")
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val norm = if (cleaned.endsWith(".json", ignoreCase = true)) cleaned else "$cleaned.json"
        val dataDir = File(projectDir, "data").apply { mkdirs() }
        val file = File(dataDir, norm)
        if (!file.exists()) {
            onConsoleLog("[DATA 警告] 找不到数据表文件: data/$norm")
            return null
        }
        return try {
            norm to file.readText()
        } catch (e: Exception) {
            onConsoleLog("[DATA 读取错误 @ $norm] ${e.message}")
            null
        }
    }

    fun setLanguage(lang: String): String {
        val clean = lang.trim().lowercase()
        if (clean.isNotEmpty()) {
            currentLang = clean
            loadI18nFromDiskIfNeeded(clean)
        }
        return currentLang
    }

    fun setI18nDictJson(lang: String, jsonStr: String) {
        val clean = lang.trim().lowercase()
        val dict = i18nDicts.getOrPut(clean) { mutableMapOf() }
        try {
            val obj = JSONObject(jsonStr)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = obj.opt(k)
                if (v != null && v !== JSONObject.NULL) dict[k] = v.toString()
            }
        } catch (_: Exception) {}
    }

    fun loadI18nFromDiskIfNeeded(lang: String) {
        val dataDir = File(projectDir, "data")
        if (!dataDir.exists()) return
        val dict = i18nDicts.getOrPut(lang) { mutableMapOf() }
        val perLangFile = File(dataDir, "i18n_$lang.json")
        if (perLangFile.exists()) {
            try {
                val obj = JSONObject(perLangFile.readText())
                val it = obj.keys()
                while (it.hasNext()) { val k = it.next(); dict[k] = obj.optString(k, "") }
            } catch (_: Exception) {}
            return
        }
        val combinedFile = File(dataDir, "i18n.json")
        if (combinedFile.exists()) {
            try {
                val sub = JSONObject(combinedFile.readText()).optJSONObject(lang) ?: return
                val it = sub.keys()
                while (it.hasNext()) { val k = it.next(); dict[k] = sub.optString(k, "") }
            } catch (_: Exception) {}
        }
    }

    fun translateKey(key: String, fallback: String): String {
        val dict = i18nDicts[currentLang]
        if (dict != null && dict.containsKey(key)) return dict[key] ?: fallback
        return if (fallback.isNotEmpty()) fallback else key
    }

    fun saveDiskSlotJson(slotName: String, jsonStr: String): Boolean = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val savesDir = File(projectDir, "saves").apply { mkdirs() }
        File(savesDir, "$safeName.json").writeText(jsonStr.ifBlank { "{}" })
        true
    } catch (_: Exception) { false }

    fun loadDiskSlotJson(slotName: String): String? = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val file = File(File(projectDir, "saves"), "$safeName.json")
        if (file.exists()) file.readText() else null
    } catch (_: Exception) { null }

    fun deleteDiskSlot(slotName: String): Boolean = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val file = File(File(projectDir, "saves"), "$safeName.json")
        file.exists() && file.delete()
    } catch (_: Exception) { false }
}
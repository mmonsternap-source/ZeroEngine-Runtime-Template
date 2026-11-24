package com.zero.engine

import org.json.JSONObject
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ZeroDataStore(
    private val projectDir: File,
    private val onConsoleLog: (String) -> Unit
) {
    val moduleCache = mutableMapOf<String, Any?>()
    val worldFlags = ConcurrentHashMap<String, Any>()
    val dataTableCache = mutableMapOf<String, Any?>()
    var currentLang = "zh"
        private set
    private val i18nDicts = mutableMapOf<String, MutableMap<String, String>>()

    fun clearRuntimeCaches() {
        moduleCache.clear()
        dataTableCache.clear()
    }

    fun requireScriptModule(rhino: RhinoContext, globalScope: ScriptableObject, rawPath: String): Any? {
        val cleaned = rawPath.trim().replace("\\", "/").removePrefix("./")
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val normPath = if (cleaned.endsWith(".js")) cleaned else "$cleaned.js"
        if (moduleCache.containsKey(normPath)) return moduleCache[normPath]

        val file = File(File(projectDir, "scripts"), normPath)
        if (!file.exists()) {
            onConsoleLog("[MODULE 错误] 找不到脚本模块: scripts/$normPath")
            return null
        }
        return try {
            val code = file.readText()
            val modScope = rhino.newObject(globalScope)
            modScope.prototype = globalScope
            val exportsObj = rhino.newObject(modScope)
            val moduleObj = rhino.newObject(modScope)
            ScriptableObject.putProperty(moduleObj, "exports", exportsObj)
            ScriptableObject.putProperty(modScope, "module", moduleObj)
            ScriptableObject.putProperty(modScope, "exports", exportsObj)

            moduleCache[normPath] = exportsObj
            rhino.evaluateString(modScope, code, "scripts/$normPath", 1, null)
            val finalExports = ScriptableObject.getProperty(moduleObj, "exports")
            moduleCache[normPath] = finalExports
            finalExports
        } catch (e: Exception) {
            moduleCache.remove(normPath)
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

    fun parseJsonStringToJs(rhino: RhinoContext, globalScope: ScriptableObject, raw: String): Any? {
        val reviver = object : Callable {
            override fun call(cx: RhinoContext?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>): Any? = args.getOrNull(1)
        }
        return NativeJSON.parse(rhino, globalScope, raw, reviver)
    }

    fun loadDataTable(rhino: RhinoContext, globalScope: ScriptableObject, tableName: String): Any? {
        val cleaned = tableName.trim().replace("\\", "/").removePrefix("./")
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val norm = if (cleaned.endsWith(".json", ignoreCase = true)) cleaned else "$cleaned.json"
        if (dataTableCache.containsKey(norm)) return dataTableCache[norm]

        val dataDir = File(projectDir, "data").apply { mkdirs() }
        val file = File(dataDir, norm)
        if (!file.exists()) {
            onConsoleLog("[DATA 警告] 找不到数据表文件: data/$norm")
            return null
        }
        return try {
            val parsed = parseJsonStringToJs(rhino, globalScope, file.readText())
            dataTableCache[norm] = parsed
            parsed
        } catch (e: Exception) {
            onConsoleLog("[DATA 解析错误 @ $norm] ${e.message}")
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

    fun setI18nDict(lang: String, obj: Scriptable) {
        val clean = lang.trim().lowercase()
        val dict = i18nDicts.getOrPut(clean) { mutableMapOf() }
        for (id in obj.ids) {
            val k = id.toString()
            val v = ScriptableObject.getProperty(obj, k)
            if (v != null && v !== Scriptable.NOT_FOUND) dict[k] = RhinoContext.toString(v)
        }
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

    fun saveDiskSlot(rhino: RhinoContext, globalScope: ScriptableObject, slotName: String, dataObj: Any?): Boolean = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val savesDir = File(projectDir, "saves").apply { mkdirs() }
        val jsonStr = when (dataObj) {
            is String -> dataObj
            null -> "{}"
            else -> RhinoContext.toString(NativeJSON.stringify(rhino, globalScope, dataObj, null, null))
        }
        File(savesDir, "$safeName.json").writeText(jsonStr)
        true
    } catch (_: Exception) { false }

    fun loadDiskSlot(rhino: RhinoContext, globalScope: ScriptableObject, slotName: String): Any? = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val file = File(File(projectDir, "saves"), "$safeName.json")
        if (file.exists()) parseJsonStringToJs(rhino, globalScope, file.readText()) else null
    } catch (_: Exception) { null }

    fun deleteDiskSlot(slotName: String): Boolean = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val file = File(File(projectDir, "saves"), "$safeName.json")
        file.exists() && file.delete()
    } catch (_: Exception) { false }
}

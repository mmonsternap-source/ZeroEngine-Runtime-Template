package com.zero.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.io.File
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    companion object {
        const val EDITOR_MODE = true
        private const val PREFS_NAME = "ZeroEnginePrefs"
        private const val KEY_SAVE_TREE_URI = "save_tree_uri"
    }

    private lateinit var container: FrameLayout
    private var editorWebView: WebView? = null
    private var engineView: ZeroEngineView? = null
    private var isPlaying = false
    private val PICK_IMAGE_CODE = 1001
    private val PICK_AUDIO_CODE = 1002
    private val EXPORT_ZIP_CODE = 1003
    private val PICK_SAVE_DIR_CODE = 1004
    private val uiHandler = Handler(Looper.getMainLooper())
    private val consoleLogs = Collections.synchronizedList(mutableListOf<String>())
    private var liveConsoleView: TextView? = null

    private var currentProject: String = "DefaultProject"

    private fun getSavedTreeUri(): Uri? {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SAVE_TREE_URI, null)
        return if (!raw.isNullOrBlank()) Uri.parse(raw) else null
    }

    private fun getDefaultProjectsRoot(): File {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        return File(baseDir, "ZeroEngine/projects").apply { mkdirs() }
    }

    private fun getProjectDir(projName: String = currentProject): File {
        return File(getDefaultProjectsRoot(), projName).apply {
            File(this, "scenes").mkdirs()
            File(this, "textures").mkdirs()
            File(this, "audio").mkdirs()
        }
    }

    private fun syncFileToSaf(projName: String, subDir: String, fileName: String, bytes: ByteArray, mimeType: String) {
        val treeUri = getSavedTreeUri() ?: return
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val projectsUri = findOrCreateSafDir(treeUri, rootDocUri, "ZeroEngine_Projects") ?: return
            val projUri = findOrCreateSafDir(treeUri, projectsUri, projName) ?: return
            val subUri = findOrCreateSafDir(treeUri, projUri, subDir) ?: return

            val existingFileUri = findSafChild(treeUri, subUri, fileName)
            val targetUri = existingFileUri ?: DocumentsContract.createDocument(contentResolver, subUri, mimeType, fileName)
            if (targetUri != null) {
                contentResolver.openOutputStream(targetUri, "wt")?.use { it.write(bytes) }
            }
        } catch (e: Exception) {
            appendConsoleLog("[SYNC] 外部目录同步提示: ${e.message}")
        }
    }

    private fun findSafChild(treeUri: Uri, parentDocUri: Uri, displayName: String): Uri? {
        val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                if (name == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        }
        return null
    }

    private fun findOrCreateSafDir(treeUri: Uri, parentDocUri: Uri, dirName: String): Uri? {
        findSafChild(treeUri, parentDocUri, dirName)?.let { return it }
        return DocumentsContract.createDocument(
            contentResolver,
            parentDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        )
    }

    private fun syncWholeProjectToSaf(projName: String) {
        val projDir = getProjectDir(projName)
        listOf("scenes" to "application/json", "textures" to "image/png", "audio" to "audio/mpeg").forEach { (sub, mime) ->
            File(projDir, sub).listFiles()?.filter { it.isFile }?.forEach { f ->
                syncFileToSaf(projName, sub, f.name, f.readBytes(), mime)
            }
        }
    }

    private fun formatReadableSaveLocation(): String {
        val treeUri = getSavedTreeUri()
        if (treeUri != null) {
            val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { treeUri.toString() }
            return "已选自定义目录 ($docId/ZeroEngine_Projects/$currentProject)"
        }
        return getProjectDir(currentProject).absolutePath
    }

    private fun appendConsoleLog(msg: String) {
        synchronized(consoleLogs) {
            consoleLogs.add(msg)
            if (consoleLogs.size > 200) {
                consoleLogs.removeAt(0)
            }
        }
        uiHandler.post {
            liveConsoleView?.text = getRecentLogs(3)
        }
    }

    private fun getRecentLogs(limit: Int): String {
        synchronized(consoleLogs) {
            return consoleLogs.takeLast(limit).joinToString("\n")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getProjectDir()

        container = FrameLayout(this)
        setContentView(container)

        if (EDITOR_MODE) {
            editorWebView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webChromeClient = WebChromeClient()
                addJavascriptInterface(EngineBridge(), "ZeroBridge")
                loadUrl("file:///android_asset/editor.html")
            }
            container.addView(
                editorWebView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            val defaultJson = assets.open("main.scene.json").bufferedReader().use { it.readText() }
            startPlayMode(defaultJson)
        }
    }

    inner class EngineBridge {

        @JavascriptInterface
        fun hasCustomSaveLocation(): Boolean = getSavedTreeUri() != null

        @JavascriptInterface
        fun getSaveLocation(): String = formatReadableSaveLocation()

        @JavascriptInterface
        fun pickSaveLocation() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
                startActivityForResult(intent, PICK_SAVE_DIR_CODE)
            }
        }

        @JavascriptInterface
        fun resetDefaultSaveLocation() {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_SAVE_TREE_URI).apply()
            appendConsoleLog("[STORAGE] 已恢复默认存储目录: ${getProjectDir().absolutePath}")
        }

        @JavascriptInterface
        fun createNewProject(projName: String, withTemplate: Boolean): Boolean {
            return try {
                val safe = projName.replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9_-]"), "")
                if (safe.isEmpty()) return false
                currentProject = safe
                getProjectDir(safe)
                appendConsoleLog("[PROJECT] 新建工程成功: $safe (模板=$withTemplate)")
                true
            } catch (e: Exception) {
                appendConsoleLog("[PROJECT 错误] ${e.message}")
                false
            }
        }

        @JavascriptInterface
        fun listProjects(): String {
            val root = getDefaultProjectsRoot()
            val names = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: listOf("DefaultProject")
            return JSONArray(names.ifEmpty { listOf("DefaultProject") }).toString()
        }

        @JavascriptInterface
        fun setProject(projName: String) {
            val safe = projName.replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9_-]"), "").ifEmpty { "DefaultProject" }
            currentProject = safe
            getProjectDir(safe)
        }

        @JavascriptInterface
        fun getCurrentProject(): String = currentProject

        @JavascriptInterface
        fun listScenes(): String {
            val dir = File(getProjectDir(), "scenes")
            val list = dir.listFiles()?.filter { it.name.endsWith(".scene") }?.map { it.name }?.sorted() ?: emptyList()
            return JSONArray(if (list.isEmpty()) listOf("main.scene") else list).toString()
        }

        @JavascriptInterface
        fun listAudioFiles(): String {
            val dir = File(getProjectDir(), "audio")
            val list = dir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
            return JSONArray(list).toString()
        }

        @JavascriptInterface
        fun saveSceneFile(sceneFileName: String, sceneJson: String) {
            val safeName = if (sceneFileName.endsWith(".scene")) sceneFileName else "$sceneFileName.scene"
            File(getProjectDir(), "scenes/$safeName").writeText(sceneJson)
            syncFileToSaf(currentProject, "scenes", safeName, sceneJson.toByteArray(Charsets.UTF_8), "application/json")
        }

        @JavascriptInterface
        fun loadSceneFile(sceneFileName: String): String {
            val safeName = if (sceneFileName.endsWith(".scene")) sceneFileName else "$sceneFileName.scene"
            val f = File(getProjectDir(), "scenes/$safeName")
            return if (f.exists()) f.readText() else ""
        }

        @JavascriptInterface
        fun runGame(sceneJson: String) {
            runOnUiThread { startPlayMode(sceneJson) }
        }

        @JavascriptInterface
        fun getConsoleLogs(): String {
            synchronized(consoleLogs) {
                return consoleLogs.joinToString("\n")
            }
        }

        @JavascriptInterface
        fun clearConsoleLogs() {
            consoleLogs.clear()
        }

        @JavascriptInterface
        fun pickImage() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(intent, PICK_IMAGE_CODE)
            }
        }

        @JavascriptInterface
        fun pickAudio() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }
                startActivityForResult(intent, PICK_AUDIO_CODE)
            }
        }

        @JavascriptInterface
        fun exportProjectBundle() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, "${currentProject}_Release.zip")
                }
                startActivityForResult(intent, EXPORT_ZIP_CODE)
            }
        }

        @JavascriptInterface
        fun getTextureBase64(fileName: String): String {
            val file = File(getProjectDir(), "textures/$fileName")
            if (!file.exists()) return ""
            val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            return "data:image/png;base64,$b64"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return null
    }

    private fun writeProjectToZip(uri: Uri) {
        val projDir = getProjectDir()
        contentResolver.openOutputStream(uri)?.use { rawOut ->
            ZipOutputStream(rawOut).use { zos ->
                val mainSceneFile = File(projDir, "scenes/main.scene")
                if (mainSceneFile.exists()) {
                    zos.putNextEntry(ZipEntry("main.scene.json"))
                    zos.write(mainSceneFile.readBytes())
                    zos.closeEntry()
                }
                projDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relPath = file.relativeTo(projDir).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(relPath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return

        if (requestCode == PICK_SAVE_DIR_CODE) {
            try {
                val takeFlags = (data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                contentResolver.takePersistableUriPermission(
                    uri,
                    if (takeFlags != 0) takeFlags else (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                )
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SAVE_TREE_URI, uri.toString())
                    .apply()
                syncWholeProjectToSaf(currentProject)
                val loc = formatReadableSaveLocation()
                appendConsoleLog("[STORAGE] 已切换存档目录: $loc")
                editorWebView?.evaluateJavascript("if(window.onSaveLocationPicked)onSaveLocationPicked()", null)
            } catch (e: Exception) {
                appendConsoleLog("[STORAGE 错误] ${e.message}")
            }
        } else if (requestCode == PICK_IMAGE_CODE) {
            val texDir = File(getProjectDir(), "textures").apply { mkdirs() }
            val fileName = "tex_${System.currentTimeMillis() % 100000}.png"
            val destFile = File(texDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            val bytes = destFile.readBytes()
            syncFileToSaf(currentProject, "textures", fileName, bytes, "image/png")

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destFile.absolutePath, opts)
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:image/png;base64,$b64"

            editorWebView?.evaluateJavascript(
                "onNativeImagePicked('$fileName', '$dataUrl', ${opts.outWidth}, ${opts.outHeight})",
                null
            )
        } else if (requestCode == PICK_AUDIO_CODE) {
            val audioDir = File(getProjectDir(), "audio").apply { mkdirs() }
            val rawName = queryDisplayName(uri) ?: "snd_${System.currentTimeMillis() % 100000}.mp3"
            val safeName = rawName.replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9._-]"), "_")
            val destFile = File(audioDir, safeName)

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            syncFileToSaf(currentProject, "audio", safeName, destFile.readBytes(), "audio/mpeg")
            appendConsoleLog("[AUDIO] 已导入音频资源: $safeName")
            editorWebView?.evaluateJavascript("onNativeAudioPicked('$safeName')", null)
        } else if (requestCode == EXPORT_ZIP_CODE) {
            try {
                writeProjectToZip(uri)
                appendConsoleLog("[EXPORT] 工程发布包导出成功: ${currentProject}_Release.zip")
                editorWebView?.evaluateJavascript("onExportFinished(true)", null)
            } catch (e: Exception) {
                appendConsoleLog("[EXPORT 错误] ${e.message}")
                editorWebView?.evaluateJavascript("onExportFinished(false)", null)
            }
        }
    }

    private fun startPlayMode(sceneJson: String) {
        if (isPlaying) return
        isPlaying = true
        consoleLogs.clear()
        appendConsoleLog("[SYSTEM] Project [$currentProject] v0.5-Release Runtime started.")

        val projDir = getProjectDir()
        val surface = ZeroEngineView(
            context = this,
            projectDir = projDir,
            sceneJson = sceneJson,
            onConsoleLog = { msg -> appendConsoleLog(msg) }
        )
        engineView = surface

        container.removeAllViews()
        container.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        if (EDITOR_MODE) {
            val hudOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 48, 24, 12)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val statusText = TextView(this).apply {
                text = "ZeroEngine v0.5-Release | TileMap + Particles + Shadows"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#34D399"))
                setBackgroundColor(Color.parseColor("#AA0F172A"))
                setPadding(16, 10, 16, 10)
            }

            val stopBtn = Button(this).apply {
                text = "■ 停止"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#CCEF4444"))
                setTextColor(Color.WHITE)
                setOnClickListener { stopPlayMode() }
            }

            topRow.addView(
                statusText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 16 }
            )
            topRow.addView(
                stopBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            val liveConsole = TextView(this).apply {
                text = getRecentLogs(3)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#F8FAFC"))
                setBackgroundColor(Color.parseColor("#88020617"))
                setPadding(16, 8, 16, 8)
                maxLines = 4
            }
            liveConsoleView = liveConsole

            hudOverlay.addView(topRow)
            hudOverlay.addView(
                liveConsole,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 8
                }
            )

            container.addView(
                hudOverlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
        }
    }

    private fun stopPlayMode() {
        if (!isPlaying) return
        isPlaying = false
        liveConsoleView = null
        engineView?.releaseResources()
        engineView?.onPause()
        engineView = null
        container.removeAllViews()
        editorWebView?.let {
            container.addView(it)
            it.evaluateJavascript("refreshConsoleFromNative()", null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (EDITOR_MODE && isPlaying) stopPlayMode() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        engineView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        engineView?.onPause()
    }

    override fun onDestroy() {
        engineView?.releaseResources()
        super.onDestroy()
    }
}
package com.zero.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val EDITOR_MODE = true
    }

    private lateinit var container: FrameLayout
    private var editorWebView: WebView? = null
    private var glView: GLSurfaceView? = null
    private var scriptRuntime: ScriptRuntime? = null
    private var isPlaying = false
    private val PICK_IMAGE_CODE = 1001
    private val uiHandler = Handler(Looper.getMainLooper())
    private var profilerTask: Runnable? = null

    private var currentProject: String = "DefaultProject"

    private fun getProjectDir(projName: String = currentProject): File {
        return File(filesDir, "projects/$projName").apply {
            File(this, "scenes").mkdirs()
            File(this, "textures").mkdirs()
            File(this, "audio").mkdirs()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AudioEngine.init()
        getProjectDir()

        container = FrameLayout(this)
        setContentView(container)

        if (EDITOR_MODE) {
            editorWebView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(EngineBridge(), "ZeroBridge")
                loadUrl("file:///android_asset/editor.html")
            }
            container.addView(editorWebView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else {
            val defaultJson = assets.open("main.scene.json").bufferedReader().use { it.readText() }
            startPlayMode(defaultJson)
        }
    }

    inner class EngineBridge {
        @JavascriptInterface
        fun listProjects(): String {
            val root = File(filesDir, "projects").apply { mkdirs() }
            val names = root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: listOf("DefaultProject")
            return JSONArray(names.ifEmpty { listOf("DefaultProject") }).toString()
        }

        @JavascriptInterface
        fun setProject(projName: String) {
            val safe = projName.replace(Regex("[^a-zA-Z0-9_-]"), "").ifEmpty { "DefaultProject" }
            currentProject = safe
            getProjectDir(safe)
        }

        @JavascriptInterface
        fun getCurrentProject(): String = currentProject

        @JavascriptInterface
        fun listScenes(): String {
            val dir = File(getProjectDir(), "scenes")
            val list = dir.listFiles()?.filter { it.name.endsWith(".scene") }?.map { it.name } ?: emptyList()
            return JSONArray(if (list.isEmpty()) listOf("main.scene") else list).toString()
        }

        @JavascriptInterface
        fun saveSceneFile(sceneFileName: String, sceneJson: String) {
            val safeName = if (sceneFileName.endsWith(".scene")) sceneFileName else "$sceneFileName.scene"
            File(getProjectDir(), "scenes/$safeName").writeText(sceneJson)
        }

        @JavascriptInterface
        fun loadSceneFile(sceneFileName: String): String {
            val safeName = if (sceneFileName.endsWith(".scene")) sceneFileName else "$sceneFileName.scene"
            val f = File(getProjectDir(), "scenes/$safeName")
            return if (f.exists()) f.readText() else ""
        }

        @JavascriptInterface
        fun runGame(sceneJson: String) { runOnUiThread { startPlayMode(sceneJson) } }

        @JavascriptInterface
        fun getConsoleLogs(): String = EngineConsole.dump()

        @JavascriptInterface
        fun clearConsoleLogs() = EngineConsole.clear()

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
        fun getTextureBase64(fileName: String): String {
            val file = File(getProjectDir(), "textures/$fileName")
            if (!file.exists()) return ""
            val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            return "data:image/png;base64,$b64"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_CODE && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val texDir = File(getProjectDir(), "textures").apply { mkdirs() }
            val fileName = "tex_${System.currentTimeMillis() % 100000}.png"
            val destFile = File(texDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destFile.absolutePath, opts)
            val b64 = Base64.encodeToString(destFile.readBytes(), Base64.NO_WRAP)
            val dataUrl = "data:image/png;base64,$b64"

            editorWebView?.evaluateJavascript(
                "onNativeImagePicked('$fileName', '$dataUrl', ${opts.outWidth}, ${opts.outHeight})",
                null
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startPlayMode(sceneJson: String) {
        if (isPlaying) return
        isPlaying = true
        EngineConsole.clear()
        EngineConsole.log("Project [$currentProject] Runtime started.")

        val projDir = getProjectDir()
        val rootNode = Node2D("Root")
        val camera = Camera2D("MainCamera").apply { x = 540f; y = 960f }
        rootNode.addChild(camera)

        SceneLoader.loadFromString(sceneJson, rootNode)

        val runtime = ScriptRuntime(projDir, rootNode, camera)
        scriptRuntime = runtime
        val renderer = GLRenderer(this, projDir, rootNode, camera, runtime)

        val surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                val tx = event.x
                val ty = event.y
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        InputState.isTouching = true
                        InputState.justPressed = true
                        InputState.touchX = tx
                        InputState.touchY = ty
                        queueEvent { runtime.dispatchInput("down", tx, ty) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        InputState.touchX = tx
                        InputState.touchY = ty
                        queueEvent { runtime.dispatchInput("move", tx, ty) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        InputState.isTouching = false
                        InputState.justReleased = true
                        InputState.touchX = tx
                        InputState.touchY = ty
                        queueEvent { runtime.dispatchInput("up", tx, ty) }
                    }
                }
                true
            }
        }
        glView = surface

        container.removeAllViews()
        container.addView(surface)

        if (EDITOR_MODE) {
            val hudOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 48, 24, 12)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val profilerText = TextView(this).apply {
                text = "FPS: 60 | Nodes: 0 | DC: 0"
                textSize = 12f
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

            topRow.addView(profilerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 16 })
            topRow.addView(stopBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val liveConsole = TextView(this).apply {
                text = ""
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#F8FAFC"))
                setBackgroundColor(Color.parseColor("#88020617"))
                setPadding(16, 8, 16, 8)
                maxLines = 4
            }

            hudOverlay.addView(topRow)
            hudOverlay.addView(liveConsole, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })

            container.addView(hudOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ))

            profilerTask = object : Runnable {
                override fun run() {
                    if (!isPlaying) return
                    profilerText.text = "FPS: ${renderer.currentFps} | Nodes: ${renderer.nodeCount} | DC: ${renderer.drawCalls}"
                    liveConsole.text = EngineConsole.dump().lines().takeLast(3).joinToString("\n")
                    uiHandler.postDelayed(this, 300)
                }
            }
            uiHandler.post(profilerTask!!)
        }
    }

    private fun stopPlayMode() {
        if (!isPlaying) return
        isPlaying = false
        profilerTask?.let { uiHandler.removeCallbacks(it) }
        glView?.queueEvent { scriptRuntime?.release() }
        glView?.onPause()
        glView = null
        scriptRuntime = null
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

    override fun onResume() { super.onResume(); glView?.onResume() }
    override fun onPause() { super.onPause(); glView?.onPause() }
    override fun onDestroy() { super.onDestroy(); AudioEngine.release() }
}
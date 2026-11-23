package com.zero.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import java.io.File
import kotlin.math.sin

class MainActivity : Activity() {

    companion object {
        // 【开发开关】true = 打开手机可视化编辑器；false = 打包成正式游戏直接运行
        const val EDITOR_MODE = true
    }

    private lateinit var container: FrameLayout
    private var editorWebView: WebView? = null
    private var glView: GLSurfaceView? = null
    private var isPlaying = false

    private val PICK_IMAGE_CODE = 1001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        fun runGame(sceneJson: String) {
            runOnUiThread { startPlayMode(sceneJson) }
        }

        @JavascriptInterface
        fun saveScene(sceneJson: String) {
            File(filesDir, "saved_scene.json").writeText(sceneJson)
        }

        @JavascriptInterface
        fun loadSavedScene(): String {
            val f = File(filesDir, "saved_scene.json")
            return if (f.exists()) f.readText() else ""
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
        fun getTextureBase64(fileName: String): String {
            val file = File(filesDir, "textures/$fileName")
            if (!file.exists()) return ""
            val bytes = file.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return "data:image/png;base64,$b64"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_CODE && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val texDir = File(filesDir, "textures").apply { mkdirs() }
            val fileName = "tex_${System.currentTimeMillis()}.png"
            val destFile = File(texDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destFile.absolutePath, opts)
            val bytes = destFile.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:image/png;base64,$b64"

            editorWebView?.evaluateJavascript(
                "onNativeImagePicked('$fileName', '$dataUrl', ${opts.outWidth}, ${opts.outHeight})",
                null
            )
        }
    }

    // ==================== 病毒感染核心玩法运行时 ====================
    @SuppressLint("ClickableViewAccessibility")
    private fun startPlayMode(sceneJson: String) {
        if (isPlaying) return
        isPlaying = true

        val rootNode = Node2D("Root")
        val camera = Camera2D("MainCamera").apply { x = 540f; y = 960f }
        rootNode.addChild(camera)

        // 1. 加载编辑器里摆放的所有节点作为“细胞”
        SceneLoader.loadFromString(sceneJson, rootNode)
        val cells = rootNode.findNodesByType<Sprite2D>()
        val infectedSet = mutableSetOf<Sprite2D>()
        var spreadTimer = 0f
        var pulseTime = 0f

        // 2. 顶部状态文字（实时显示感染百分比）
        val statusLabel = Label2D("StatusLabel", "点击任意细胞投放病毒！", 48f, Color.parseColor("#34D399")).apply {
            x = 260f
            y = 120f
        }
        rootNode.addChild(statusLabel)

        // 3. 病毒传播与呼吸动画控制器
        val virusManager = object : Node2D("VirusManager") {
            override fun onProcess(delta: Float) {
                pulseTime += delta * 6f
                // 已感染细胞产生红色呼吸脉冲效果
                infectedSet.forEach { cell ->
                    cell.r = 0.95f; cell.g = 0.2f; cell.b = 0.25f
                    val s = 1f + 0.08f * sin(pulseTime)
                    cell.scaleX = s
                    cell.scaleY = s
                }

                // 每隔 0.8 秒，已感染细胞向周围 420 像素内的健康细胞扩散病毒！
                if (infectedSet.isNotEmpty() && infectedSet.size < cells.size) {
                    spreadTimer += delta
                    if (spreadTimer >= 0.8f) {
                        spreadTimer = 0f
                        val newlyInfected = mutableListOf<Sprite2D>()
                        for (src in infectedSet) {
                            for (target in cells) {
                                if (target !in infectedSet && src.distanceTo(target) <= 420f) {
                                    newlyInfected.add(target)
                                }
                            }
                        }
                        if (newlyInfected.isNotEmpty()) {
                            infectedSet.addAll(newlyInfected)
                            camera.shake(14f, 0.15f)
                        }
                    }
                }

                if (cells.isNotEmpty()) {
                    val percent = (infectedSet.size * 100) / cells.size
                    statusLabel.text = if (percent >= 100) {
                        "全图感染完成！100%"
                    } else {
                        "感染进度: $percent% (${infectedSet.size}/${cells.size})"
                    }
                }
            }
        }
        rootNode.addChild(virusManager)

        val surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(GLRenderer(this@MainActivity, rootNode))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // 点击任意未感染细胞，投放初始病毒！
                    val hitCell = cells.firstOrNull { it !in infectedSet && it.containsPoint(event.x, event.y) }
                    if (hitCell != null) {
                        infectedSet.add(hitCell)
                        camera.shake(24f, 0.2f)
                    }
                }
                true
            }
        }
        glView = surface

        container.removeAllViews()
        container.addView(surface)

        if (EDITOR_MODE) {
            val stopBtn = Button(this).apply {
                text = "■ 返回编辑"
                setBackgroundColor(Color.parseColor("#CCEF4444"))
                setTextColor(Color.WHITE)
                setOnClickListener { stopPlayMode() }
            }
            val btnParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 48
                rightMargin = 36
            }
            container.addView(stopBtn, btnParams)
        }
    }

    private fun stopPlayMode() {
        if (!isPlaying) return
        isPlaying = false
        glView?.onPause()
        glView = null
        container.removeAllViews()
        editorWebView?.let { container.addView(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (EDITOR_MODE && isPlaying) stopPlayMode() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioEngine.release()
    }
}

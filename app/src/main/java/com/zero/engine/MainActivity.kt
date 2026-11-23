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

class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private lateinit var editorWebView: WebView
    private var glView: GLSurfaceView? = null
    private var isPlaying = false

    private val PICK_IMAGE_CODE = 1001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        container = FrameLayout(this)
        setContentView(container)

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
    }

    // 编辑器与原生 OpenGL 引擎的双向通信桥梁
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

    // 接收从手机相册选中的 PNG 图片，存入引擎本地目录
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

            editorWebView.evaluateJavascript(
                "onNativeImagePicked('$fileName', '$dataUrl', ${opts.outWidth}, ${opts.outHeight})",
                null
            )
        }
    }

    // 切换到原生 OpenGL ES 运行模式
    @SuppressLint("ClickableViewAccessibility")
    private fun startPlayMode(sceneJson: String) {
        if (isPlaying) return
        isPlaying = true

        val rootNode = Node2D("Root")
        val camera = Camera2D("MainCamera").apply { x = 540f; y = 960f }
        rootNode.addChild(camera)

        // 加载你在手机编辑器里刚摆好的关卡
        SceneLoader.loadFromString(sceneJson, rootNode)

        // 加入可触控测试的绿色方块与碰撞器
        val playerCollider = Collider2D("PlayerCollider", 130f, 130f, "player")
        var wasHitting = false
        val playerNode = object : Sprite2D("Player", 130f, 130f, r = 0.2f, g = 0.85f, b = 0.6f) {
            override fun onProcess(delta: Float) {
                val allColliders = rootNode.findNodesByType<Collider2D>()
                val hit = allColliders.any { it !== playerCollider && playerCollider.intersects(it) }
                if (hit) {
                    r = 0.95f; g = 0.25f; b = 0.25f
                    if (!wasHitting) camera.shake(28f, 0.25f)
                } else {
                    r = 0.2f; g = 0.85f; b = 0.6f
                }
                wasHitting = hit
            }
        }.apply {
            x = 540f; y = 850f
            addChild(playerCollider)
        }
        rootNode.addChild(playerNode)

        val surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(GLRenderer(this@MainActivity, rootNode))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    playerNode.x = event.x
                    playerNode.y = event.y
                }
                true
            }
        }
        glView = surface

        // 右上角浮动“返回编辑”按钮
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

        container.removeAllViews()
        container.addView(surface)
        container.addView(stopBtn, btnParams)
    }

    // 退出试玩，秒回可视化编辑器
    private fun stopPlayMode() {
        if (!isPlaying) return
        isPlaying = false
        glView?.onPause()
        glView = null
        container.removeAllViews()
        container.addView(editorWebView)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isPlaying) stopPlayMode() else super.onBackPressed()
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

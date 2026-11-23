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
import android.widget.LinearLayout
import java.io.File
import kotlin.math.sin

class MainActivity : Activity() {

    companion object {
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
        fun runGame(sceneJson: String) { runOnUiThread { startPlayMode(sceneJson) } }

        @JavascriptInterface
        fun saveScene(sceneJson: String) { File(filesDir, "saved_scene.json").writeText(sceneJson) }

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
            val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
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

        val rootNode = Node2D("Root")
        val camera = Camera2D("MainCamera").apply { x = 540f; y = 960f }
        rootNode.addChild(camera)

        SceneLoader.loadFromString(sceneJson, rootNode)
        val allSprites = rootNode.findNodesByType<Sprite2D>()

        var score = 0
        var moveLeft = false
        var moveRight = false
        var jumpRequested = false
        var shootTimer = 0f
        var virusTimer = 0f
        var pulseTime = 0f
        val bullets = mutableListOf<Sprite2D>()

        val hudLabel = Label2D("HUD", "得分: 0", 48f, Color.parseColor("#34D399")).apply {
            x = 220f
            y = 110f
        }
        rootNode.addChild(hudLabel)

        val gameLoop = object : Node2D("UniversalGameLoop") {
            override fun onProcess(delta: Float) {
                pulseTime += delta * 6f
                val colliders = rootNode.findNodesByType<Collider2D>()
                val virusCells = allSprites.filter { it.behavior == "virus" }

                for (obj in allSprites) {
                    if (!obj.visible) continue
                    when (obj.behavior) {
                        "patrol" -> {
                            obj.x += obj.vx * delta
                            if (obj.x > 960f) { obj.x = 960f; obj.vx = -240f }
                            if (obj.x < 120f) { obj.x = 120f; obj.vx = 240f }
                        }
                        "platformer" -> {
                            obj.vx = when {
                                moveLeft -> -420f
                                moveRight -> 420f
                                else -> 0f
                            }
                            if (jumpRequested && obj.stateFlag) {
                                obj.vy = -1050f
                                obj.stateFlag = false
                                jumpRequested = false
                            }
                            obj.vy += 2200f * delta
                            obj.x = (obj.x + obj.vx * delta).coerceIn(60f, 1020f)
                            obj.y += obj.vy * delta

                            val myCol = obj.findNodesByType<Collider2D>().firstOrNull()
                            if (myCol != null) {
                                for (other in colliders) {
                                    val parentSprite = other.parent as? Sprite2D ?: continue
                                    if (parentSprite === obj || !parentSprite.visible) continue
                                    if (myCol.intersects(other)) {
                                        when (parentSprite.behavior) {
                                            "coin" -> {
                                                parentSprite.visible = false
                                                score += 10
                                                camera.shake(12f, 0.12f)
                                            }
                                            "patrol" -> {
                                                camera.shake(28f, 0.25f)
                                                obj.x = 260f; obj.y = 800f; obj.vy = 0f
                                            }
                                            else -> {
                                                if (obj.vy > 0f) {
                                                    obj.y = parentSprite.y - parentSprite.height / 2f - obj.height / 2f
                                                    obj.vy = 0f
                                                    obj.stateFlag = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (obj.y > 1950f) { obj.x = 260f; obj.y = 800f; obj.vy = 0f }
                        }
                        "shooter" -> {
                            shootTimer += delta
                            if (shootTimer >= 0.45f) {
                                shootTimer = 0f
                                val b = Sprite2D("Bullet", 24f, 48f, r = 0.98f, g = 0.9f, b = 0.2f).apply {
                                    x = obj.x; y = obj.y - obj.height / 2f - 20f
                                    addChild(Collider2D("BCol", 24f, 48f, "bullet"))
                                }
                                bullets.add(b)
                                rootNode.addChild(b)
                            }
                        }
                        "virus" -> {
                            if (obj.stateFlag) {
                                obj.r = 0.95f; obj.g = 0.2f; obj.b = 0.25f
                                val s = 1f + 0.08f * sin(pulseTime)
                                obj.scaleX = s; obj.scaleY = s
                            }
                        }
                    }
                }

                val bulletIter = bullets.iterator()
                while (bulletIter.hasNext()) {
                    val b = bulletIter.next()
                    b.y -= 1100f * delta
                    var hitEnemy = false
                    val bCol = b.findNodesByType<Collider2D>().firstOrNull()
                    if (bCol != null) {
                        for (target in allSprites) {
                            if (target.visible && target.behavior == "patrol") {
                                val tCol = target.findNodesByType<Collider2D>().firstOrNull()
                                if (tCol != null && bCol.intersects(tCol)) {
                                    target.visible = false
                                    hitEnemy = true
                                    score += 50
                                    camera.shake(22f, 0.2f)
                                    break
                                }
                            }
                        }
                    }
                    if (hitEnemy || b.y < -50f) {
                        rootNode.removeChild(b)
                        bulletIter.remove()
                    }
                }

                if (virusCells.isNotEmpty()) {
                    val infected = virusCells.filter { it.stateFlag }
                    if (infected.isNotEmpty() && infected.size < virusCells.size) {
                        virusTimer += delta
                        if (virusTimer >= 0.8f) {
                            virusTimer = 0f
                            val nextWave = virusCells.filter { !it.stateFlag && infected.any { src -> src.distanceTo(it) <= 450f } }
                            if (nextWave.isNotEmpty()) {
                                nextWave.forEach { it.stateFlag = true }
                                camera.shake(14f, 0.15f)
                            }
                        }
                    }
                    val pct = (infected.size * 100) / virusCells.size
                    hudLabel.text = if (infected.isEmpty()) "点击细胞投放病毒！" else "感染进度: $pct%"
                } else {
                    hudLabel.text = "得分: $score"
                }
            }
        }
        rootNode.addChild(gameLoop)

        val surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(GLRenderer(this@MainActivity, rootNode))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    allSprites.firstOrNull { it.behavior == "virus" && !it.stateFlag && it.containsPoint(event.x, event.y) }
                        ?.let { it.stateFlag = true; camera.shake(22f, 0.2f) }
                }
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                    allSprites.filter { it.behavior == "drag" || it.behavior == "shooter" }.forEach {
                        it.x = event.x
                        it.y = event.y
                    }
                }
                true
            }
        }
        glView = surface

        container.removeAllViews()
        container.addView(surface)

        if (allSprites.any { it.behavior == "platformer" }) {
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(40, 20, 40, 60)
            }
            fun makePadBtn(label: String, onState: (Boolean) -> Unit): Button {
                return Button(this).apply {
                    text = label
                    textSize = 20f
                    setBackgroundColor(Color.parseColor("#882B3142"))
                    setTextColor(Color.WHITE)
                    setOnTouchListener { _, ev ->
                        if (ev.action == MotionEvent.ACTION_DOWN) onState(true)
                        if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) onState(false)
                        true
                    }
                }
            }
            controls.addView(makePadBtn("◀ 左") { moveLeft = it }, LinearLayout.LayoutParams(220, 150).apply { rightMargin = 24 })
            controls.addView(makePadBtn("右 ▶") { moveRight = it }, LinearLayout.LayoutParams(220, 150))
            val spacer = android.view.View(this)
            controls.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            controls.addView(makePadBtn("▲ 跳跃") { if (it) jumpRequested = true }, LinearLayout.LayoutParams(240, 150))

            container.addView(controls, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))
        }

        if (EDITOR_MODE) {
            val stopBtn = Button(this).apply {
                text = "■ 返回编辑"
                setBackgroundColor(Color.parseColor("#CCEF4444"))
                setTextColor(Color.WHITE)
                setOnClickListener { stopPlayMode() }
            }
            container.addView(stopBtn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { topMargin = 48; rightMargin = 36 })
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

    override fun onResume() { super.onResume(); glView?.onResume() }
    override fun onPause() { super.onPause(); glView?.onPause() }
    override fun onDestroy() { super.onDestroy(); AudioEngine.release() }
}

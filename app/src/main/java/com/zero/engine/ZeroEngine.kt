package com.zero.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// ==================== 1. 基础节点树 ====================
open class Node2D(val name: String) {
    var x: Float = 0f
    var y: Float = 0f
    var vx: Float = 0f
    var vy: Float = 0f
    var rotation: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var visible: Boolean = true
    var behavior: String = "none"
    var tag: String = "default"
    var stateFlag: Boolean = false // 通用状态标记（如：是否已感染、是否落地）

    val globalX: Float get() = (parent?.globalX ?: 0f) + x
    val globalY: Float get() = (parent?.globalY ?: 0f) + y

    var parent: Node2D? = null
        private set
    val children = mutableListOf<Node2D>()

    fun addChild(child: Node2D) {
        child.parent = this
        children.add(child)
        child.onReady()
    }

    fun removeChild(child: Node2D) {
        child.parent = null
        children.remove(child)
    }

    fun distanceTo(other: Node2D): Float = hypot(globalX - other.globalX, globalY - other.globalY)

    inline fun <reified T : Node2D> findNodesByType(): List<T> {
        val result = mutableListOf<T>()
        collectNodes(T::class.java, result)
        return result
    }

    fun <T : Node2D> collectNodes(clazz: Class<T>, out: MutableList<T>) {
        if (clazz.isInstance(this)) clazz.cast(this)?.let { out.add(it) }
        for (i in children.indices) children[i].collectNodes(clazz, out)
    }

    open fun onReady() {}
    open fun onProcess(delta: Float) {}
    open fun onDraw(renderer: GLRenderer) {}

    internal fun updateTree(delta: Float) {
        onProcess(delta)
        val snapshot = children.toList()
        for (i in snapshot.indices) snapshot[i].updateTree(delta)
    }

    internal fun drawTree(renderer: GLRenderer) {
        if (!visible) return
        onDraw(renderer)
        val snapshot = children.toList()
        for (i in snapshot.indices) snapshot[i].drawTree(renderer)
    }
}

// ==================== 2. 静态精灵图节点 ====================
open class Sprite2D(
    name: String,
    var width: Float = 100f,
    var height: Float = 100f,
    var texturePath: String? = null,
    var r: Float = 1f,
    var g: Float = 1f,
    var b: Float = 1f,
    var a: Float = 1f
) : Node2D(name) {

    fun containsPoint(px: Float, py: Float, padding: Float = 20f): Boolean {
        val halfW = (width * scaleX) / 2f + padding
        val halfH = (height * scaleY) / 2f + padding
        return abs(px - globalX) <= halfW && abs(py - globalY) <= halfH
    }

    override fun onDraw(renderer: GLRenderer) {
        val w = width * scaleX
        val h = height * scaleY
        val path = texturePath
        if (!path.isNullOrEmpty()) {
            renderer.drawTexture(path, globalX, globalY, w, h, rotation, a)
        } else {
            renderer.drawRect(globalX, globalY, w, h, rotation, r, g, b, a)
        }
    }
}

// ==================== 3. 文字显示节点 ====================
class Label2D(
    name: String,
    var text: String = "",
    var fontSize: Float = 52f,
    var textColor: Int = Color.WHITE
) : Node2D(name) {
    override fun onDraw(renderer: GLRenderer) {
        if (text.isNotEmpty()) renderer.drawText(text, globalX, globalY, fontSize, textColor)
    }
}

// ==================== 4. 序列帧动画节点 ====================
class AnimatedSprite2D(
    name: String,
    width: Float = 100f,
    height: Float = 100f,
    texturePath: String,
    var hFrames: Int = 1,
    var vFrames: Int = 1,
    var fps: Float = 10f
) : Sprite2D(name, width, height, texturePath) {

    var frame: Int = 0
    var playing: Boolean = true
    var loop: Boolean = true
    private var timer: Float = 0f

    override fun onProcess(delta: Float) {
        if (!playing || fps <= 0f) return
        timer += delta
        val frameDuration = 1f / fps
        if (timer >= frameDuration) {
            timer -= frameDuration
            val total = (hFrames * vFrames).coerceAtLeast(1)
            frame++
            if (frame >= total) {
                if (loop) frame = 0 else { frame = total - 1; playing = false }
            }
        }
    }

    override fun onDraw(renderer: GLRenderer) {
        val path = texturePath ?: return
        val cols = hFrames.coerceAtLeast(1)
        val rows = vFrames.coerceAtLeast(1)
        val col = frame % cols
        val row = (frame / cols) % rows
        val scaleU = 1f / cols
        val scaleV = 1f / rows
        renderer.drawTexture(path, globalX, globalY, width * scaleX, height * scaleY, rotation, a, col * scaleU, row * scaleV, scaleU, scaleV)
    }
}

// ==================== 5. 2D 相机与屏幕震动 ====================
class Camera2D(name: String = "Camera2D") : Node2D(name) {
    var enabled: Boolean = true
    var followTarget: Node2D? = null
    var followLerp: Float = 8f

    private var shakeIntensity: Float = 0f
    private var shakeTimer: Float = 0f
    internal var shakeOffsetX: Float = 0f
    internal var shakeOffsetY: Float = 0f

    fun shake(intensity: Float = 20f, duration: Float = 0.25f) {
        shakeIntensity = intensity
        shakeTimer = duration
    }

    override fun onProcess(delta: Float) {
        followTarget?.let { target ->
            val t = (followLerp * delta).coerceIn(0f, 1f)
            x += (target.globalX - x) * t
            y += (target.globalY - y) * t
        }
        if (shakeTimer > 0f) {
            shakeTimer -= delta
            shakeOffsetX = (Random.nextFloat() * 2f - 1f) * shakeIntensity
            shakeOffsetY = (Random.nextFloat() * 2f - 1f) * shakeIntensity
        } else {
            shakeOffsetX = 0f
            shakeOffsetY = 0f
        }
    }
}

// ==================== 6. 碰撞体节点 ====================
class Collider2D(
    name: String,
    var width: Float,
    var height: Float,
    var colliderTag: String = "default"
) : Node2D(name) {
    fun intersects(other: Collider2D): Boolean {
        val halfW1 = (width * scaleX) / 2f
        val halfH1 = (height * scaleY) / 2f
        val halfW2 = (other.width * other.scaleX) / 2f
        val halfH2 = (other.height * other.scaleY) / 2f
        return abs(globalX - other.globalX) < (halfW1 + halfW2) &&
               abs(globalY - other.globalY) < (halfH1 + halfH2)
    }
}

// ==================== 7. 双通道音频引擎 ====================
object AudioEngine {
    private var soundPool: SoundPool? = null
    private val sfxMap = mutableMapOf<String, Int>()
    private var bgmPlayer: MediaPlayer? = null

    fun init() {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
    }

    fun playSfx(name: String, volume: Float = 1f, rate: Float = 1f) {
        val id = sfxMap[name] ?: return
        soundPool?.play(id, volume, volume, 1, 0, rate)
    }

    fun release() {
        try { bgmPlayer?.stop(); bgmPlayer?.release() } catch (_: Exception) {}
        bgmPlayer = null
        soundPool?.release()
        soundPool = null
        sfxMap.clear()
    }
}

// ==================== 8. 通用场景加载器 ====================
object SceneLoader {
    fun loadFromAssets(context: Context, fileName: String, targetRoot: Node2D) {
        val jsonText = context.assets.open(fileName).bufferedReader().use { it.readText() }
        loadFromString(jsonText, targetRoot)
    }

    fun loadFromString(jsonText: String, targetRoot: Node2D) {
        val rootObj = JSONObject(jsonText)
        val nodesArray = rootObj.getJSONArray("nodes")

        for (i in 0 until nodesArray.length()) {
            val item = nodesArray.getJSONObject(i)
            val name = item.optString("name", "Node_$i")
            val w = item.optDouble("width", 100.0).toFloat()
            val h = item.optDouble("height", 100.0).toFloat()
            val tex = item.optString("texture", "")
            val hFrames = item.optInt("hFrames", 1)
            val vFrames = item.optInt("vFrames", 1)

            val node: Sprite2D = if (tex.isNotEmpty() && (hFrames > 1 || vFrames > 1)) {
                AnimatedSprite2D(name, w, h, tex, hFrames, vFrames, item.optDouble("fps", 10.0).toFloat())
            } else {
                Sprite2D(
                    name = name,
                    width = w,
                    height = h,
                    texturePath = tex.ifEmpty { null },
                    r = item.optDouble("r", 1.0).toFloat(),
                    g = item.optDouble("g", 1.0).toFloat(),
                    b = item.optDouble("b", 1.0).toFloat(),
                    a = item.optDouble("a", 1.0).toFloat()
                )
            }

            node.x = item.optDouble("x", 0.0).toFloat()
            node.y = item.optDouble("y", 0.0).toFloat()
            node.rotation = item.optDouble("rotation", 0.0).toFloat()
            node.behavior = item.optString("behavior", "none")
            node.tag = item.optString("tag", "wall")

            if (node.behavior == "patrol") node.vx = 240f

            if (item.optBoolean("collider", false)) {
                node.addChild(Collider2D("${name}_Collider", w, h, node.tag))
            }

            targetRoot.addChild(node)
        }
    }
}

// ==================== 9. OpenGL ES 2.0 硬件渲染器 ====================
class GLRenderer(private val context: Context, private val rootNode: Node2D) : GLSurfaceView.Renderer {

    private var colorProgram = 0
    private var texProgram = 0
    private var lastFrameTime = System.nanoTime()
    private var screenW = 1080f
    private var screenH = 1920f

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val textureCache = mutableMapOf<String, Int>()
    private val textTextureCache = mutableMapOf<String, Triple<Int, Float, Float>>()

    private val quadData = floatArrayOf(
        -0.5f, -0.5f, 0f, 0f,
         0.5f, -0.5f, 1f, 0f,
        -0.5f,  0.5f, 0f, 1f,
         0.5f,  0.5f, 1f, 1f
    )
    private lateinit var vertexBuffer: FloatBuffer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.08f, 0.09f, 0.12f, 1.0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        vertexBuffer = ByteBuffer.allocateDirect(quadData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadData)
        vertexBuffer.position(0)

        colorProgram = buildProgram(
            "uniform mat4 uMVPMatrix; attribute vec4 aPosition; void main() { gl_Position = uMVPMatrix * aPosition; }",
            "precision mediump float; uniform vec4 uColor; void main() { gl_FragColor = uColor; }"
        )

        texProgram = buildProgram(
            "uniform mat4 uMVPMatrix; uniform vec2 uUVOffset; uniform vec2 uUVScale; attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main() { gl_Position = uMVPMatrix * aPosition; vTexCoord = uUVOffset + aTexCoord * uUVScale; }",
            "precision mediump float; uniform sampler2D uTexture; uniform float uAlpha; varying vec2 vTexCoord; void main() { vec4 c = texture2D(uTexture, vTexCoord); gl_FragColor = vec4(c.rgb, c.a * uAlpha); }"
        )
        lastFrameTime = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        GLES20.glViewport(0, 0, width, height)
        Matrix.orthoM(projectionMatrix, 0, 0f, screenW, screenH, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val delta = ((now - lastFrameTime) / 1_000_000_000.0).toFloat().coerceAtMost(0.05f)
        lastFrameTime = now

        rootNode.updateTree(delta)

        Matrix.setIdentityM(viewMatrix, 0)
        val activeCam = rootNode.findNodesByType<Camera2D>().firstOrNull { it.enabled }
        if (activeCam != null) {
            val offsetX = -(activeCam.globalX + activeCam.shakeOffsetX) + screenW / 2f
            val offsetY = -(activeCam.globalY + activeCam.shakeOffsetY) + screenH / 2f
            Matrix.translateM(viewMatrix, 0, offsetX, offsetY, 0f)
        }
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        rootNode.drawTree(this)
    }

    fun drawRect(x: Float, y: Float, width: Float, height: Float, rotationDeg: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        GLES20.glUseProgram(colorProgram)
        computeMVP(x, y, width, height, rotationDeg)

        vertexBuffer.position(0)
        val posHandle = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), r, g, b, a)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMVPMatrix"), 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    fun drawText(text: String, x: Float, y: Float, fontSize: Float, color: Int) {
        val key = "$text|$fontSize|$color"
        val (texId, w, h) = textTextureCache.getOrPut(key) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSize
                this.color = color
                isFakeBoldText = true
            }
            val tw = (paint.measureText(text) + 24f).toInt().coerceAtLeast(32)
            val th = (fontSize * 1.5f).toInt().coerceAtLeast(32)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawText(text, 12f, fontSize * 1.1f, paint)

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            Triple(ids[0], tw.toFloat(), th.toFloat())
        }
        drawRawTexture(texId, x, y, w, h, 0f, 1f, 0f, 0f, 1f, 1f)
    }

    fun drawTexture(
        assetPath: String,
        x: Float, y: Float, width: Float, height: Float, rotationDeg: Float,
        alpha: Float = 1f, uvOffsetX: Float = 0f, uvOffsetY: Float = 0f, uvScaleX: Float = 1f, uvScaleY: Float = 1f
    ) {
        val texId = getOrLoadTexture(assetPath)
        if (texId == 0) {
            drawRect(x, y, width, height, rotationDeg, 1f, 0f, 1f, alpha)
            return
        }
        drawRawTexture(texId, x, y, width, height, rotationDeg, alpha, uvOffsetX, uvOffsetY, uvScaleX, uvScaleY)
    }

    private fun drawRawTexture(
        texId: Int, x: Float, y: Float, width: Float, height: Float, rotationDeg: Float,
        alpha: Float, uvOffsetX: Float, uvOffsetY: Float, uvScaleX: Float, uvScaleY: Float
    ) {
        GLES20.glUseProgram(texProgram)
        computeMVP(x, y, width, height, rotationDeg)

        vertexBuffer.position(0)
        val posHandle = GLES20.glGetAttribLocation(texProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        val texHandle = GLES20.glGetAttribLocation(texProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(texProgram, "uTexture"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(texProgram, "uAlpha"), alpha)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(texProgram, "uUVOffset"), uvOffsetX, uvOffsetY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(texProgram, "uUVScale"), uvScaleX, uvScaleY)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(texProgram, "uMVPMatrix"), 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    private fun computeMVP(x: Float, y: Float, w: Float, h: Float, rot: Float) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, 0f)
        if (rot != 0f) Matrix.rotateM(modelMatrix, 0, rot, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, w, h, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
    }

    private fun getOrLoadTexture(path: String): Int {
        textureCache[path]?.let { return it }
        return try {
            val localFile = File(context.filesDir, "textures/$path")
            val bitmap = if (localFile.exists()) {
                BitmapFactory.decodeFile(localFile.absolutePath)
            } else {
                context.assets.open(path).use { BitmapFactory.decodeStream(it) }
            } ?: return 0

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            textureCache[path] = texIds[0]
            texIds[0]
        } catch (e: Exception) {
            0
        }
    }

    private fun buildProgram(vCode: String, fCode: String): Int {
        val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { GLES20.glShaderSource(it, vCode); GLES20.glCompileShader(it) }
        val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { GLES20.glShaderSource(it, fCode); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)
            GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
        }
    }
}

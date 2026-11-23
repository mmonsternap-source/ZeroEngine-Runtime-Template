package com.zero.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.random.Random

// ==================== 1. 基础节点树（对标 Godot Node2D） ====================
open class Node2D(val name: String) {
    var x: Float = 0f
    var y: Float = 0f
    var rotation: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var visible: Boolean = true

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

    inline fun <reified T : Node2D> findNodesByType(): List<T> {
        val result = mutableListOf<T>()
        collectNodes(T::class.java, result)
        return result
    }

    fun <T : Node2D> collectNodes(clazz: Class<T>, out: MutableList<T>) {
        if (clazz.isInstance(this)) {
            clazz.cast(this)?.let { out.add(it) }
        }
        for (i in children.indices) {
            children[i].collectNodes(clazz, out)
        }
    }

    open fun onReady() {}
    open fun onProcess(delta: Float) {}
    open fun onDraw(renderer: GLRenderer) {}

    internal fun updateTree(delta: Float) {
        onProcess(delta)
        for (i in children.indices) children[i].updateTree(delta)
    }

    internal fun drawTree(renderer: GLRenderer) {
        if (!visible) return
        onDraw(renderer)
        for (i in children.indices) children[i].drawTree(renderer)
    }
}

// ==================== 2. 静态精灵图节点（支持 PNG/WebP 透明贴图） ====================
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

// ==================== 3. 序列帧动画节点（对标 Godot AnimatedSprite2D） ====================
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
    private val animations = mutableMapOf<String, List<Int>>()
    private var currentAnimFrames: List<Int>? = null
    private var currentAnimIndex: Int = 0

    fun addAnimation(animName: String, frames: List<Int>) {
        animations[animName] = frames
    }

    fun play(animName: String? = null, reset: Boolean = false) {
        if (animName != null && animations.containsKey(animName)) {
            val target = animations[animName]
            if (currentAnimFrames !== target || reset) {
                currentAnimFrames = target
                currentAnimIndex = 0
                frame = target?.firstOrNull() ?: 0
                timer = 0f
            }
        }
        playing = true
    }

    fun stop() {
        playing = false
    }

    override fun onProcess(delta: Float) {
        if (!playing || fps <= 0f) return
        timer += delta
        val frameDuration = 1f / fps
        if (timer >= frameDuration) {
            timer -= frameDuration
            val custom = currentAnimFrames
            if (custom != null && custom.isNotEmpty()) {
                currentAnimIndex++
                if (currentAnimIndex >= custom.size) {
                    if (loop) currentAnimIndex = 0 else {
                        currentAnimIndex = custom.size - 1
                        playing = false
                    }
                }
                frame = custom[currentAnimIndex]
            } else {
                val total = (hFrames * vFrames).coerceAtLeast(1)
                frame++
                if (frame >= total) {
                    if (loop) frame = 0 else {
                        frame = total - 1
                        playing = false
                    }
                }
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
        renderer.drawTexture(
            assetPath = path,
            x = globalX,
            y = globalY,
            width = width * scaleX,
            height = height * scaleY,
            rotationDeg = rotation,
            alpha = a,
            uvOffsetX = col * scaleU,
            uvOffsetY = row * scaleV,
            uvScaleX = scaleU,
            uvScaleY = scaleV
        )
    }
}

// ==================== 4. 2D 相机与屏幕震动（对标 Godot Camera2D） ====================
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

// ==================== 5. 碰撞体节点（对标 Godot CollisionShape2D） ====================
class Collider2D(
    name: String,
    var width: Float,
    var height: Float,
    var tag: String = "default"
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

// ==================== 6. 双通道音频引擎（低延迟 SFX + 流式 BGM） ====================
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
        soundPool = SoundPool.Builder()
            .setMaxStreams(12)
            .setAudioAttributes(attrs)
            .build()
    }

    fun loadSfx(context: Context, name: String, assetPath: String) {
        init()
        try {
            val afd = context.assets.openFd(assetPath)
            val id = soundPool?.load(afd, 1) ?: 0
            if (id != 0) sfxMap[name] = id
        } catch (_: Exception) {}
    }

    fun playSfx(name: String, volume: Float = 1f, rate: Float = 1f) {
        val id = sfxMap[name] ?: return
        soundPool?.play(id, volume, volume, 1, 0, rate)
    }

    fun playBgm(context: Context, assetPath: String, loop: Boolean = true, volume: Float = 0.7f) {
        stopBgm()
        try {
            val afd = context.assets.openFd(assetPath)
            bgmPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = loop
                setVolume(volume, volume)
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    fun stopBgm() {
        try {
            bgmPlayer?.stop()
            bgmPlayer?.release()
        } catch (_: Exception) {}
        bgmPlayer = null
    }

    fun release() {
        stopBgm()
        soundPool?.release()
        soundPool = null
        sfxMap.clear()
    }
}

// ==================== 7. JSON 数据驱动场景加载器（对标 .tscn） ====================
object SceneLoader {
    fun loadFromAssets(context: Context, fileName: String, targetRoot: Node2D) {
        val jsonText = context.assets.open(fileName).bufferedReader().use { it.readText() }
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
                AnimatedSprite2D(
                    name = name,
                    width = w,
                    height = h,
                    texturePath = tex,
                    hFrames = hFrames,
                    vFrames = vFrames,
                    fps = item.optDouble("fps", 10.0).toFloat()
                )
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

            if (item.optBoolean("collider", false)) {
                val tag = item.optString("tag", "wall")
                node.addChild(Collider2D("${name}_Collider", w, h, tag))
            }

            targetRoot.addChild(node)
        }
    }
}

// ==================== 8. OpenGL ES 2.0 双管线硬件渲染器（支持 UV 切片与相机偏移） ====================
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

        // 计算相机视图矩阵（支持屏幕震动与跟随）
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

    fun drawTexture(
        assetPath: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotationDeg: Float,
        alpha: Float = 1f,
        uvOffsetX: Float = 0f,
        uvOffsetY: Float = 0f,
        uvScaleX: Float = 1f,
        uvScaleY: Float = 1f
    ) {
        val texId = getOrLoadTexture(assetPath)
        if (texId == 0) {
            drawRect(x, y, width, height, rotationDeg, 1f, 0f, 1f, alpha)
            return
        }
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

    private fun getOrLoadTexture(assetPath: String): Int {
        textureCache[assetPath]?.let { return it }
        return try {
            val bitmap = context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) } ?: return 0
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            textureCache[assetPath] = texIds[0]
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

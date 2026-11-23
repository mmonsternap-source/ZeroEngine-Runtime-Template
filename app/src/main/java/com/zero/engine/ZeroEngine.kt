package com.zero.engine

import android.content.Context
import android.graphics.BitmapFactory
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

// ==================== 2. 精灵图与色块节点（支持 PNG/WebP 透明贴图） ====================
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

// ==================== 3. 碰撞体节点（对标 Godot CollisionShape2D） ====================
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

// ==================== 4. JSON 数据驱动场景加载器（对标 .tscn） ====================
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

            val sprite = Sprite2D(
                name = name,
                width = w,
                height = h,
                texturePath = tex.ifEmpty { null },
                r = item.optDouble("r", 1.0).toFloat(),
                g = item.optDouble("g", 1.0).toFloat(),
                b = item.optDouble("b", 1.0).toFloat(),
                a = item.optDouble("a", 1.0).toFloat()
            ).apply {
                x = item.optDouble("x", 0.0).toFloat()
                y = item.optDouble("y", 0.0).toFloat()
                rotation = item.optDouble("rotation", 0.0).toFloat()
            }

            if (item.optBoolean("collider", false)) {
                val tag = item.optString("tag", "wall")
                sprite.addChild(Collider2D("${name}_Collider", w, h, tag))
            }

            targetRoot.addChild(sprite)
        }
    }
}

// ==================== 5. OpenGL ES 2.0 双管线硬件渲染器 ====================
class GLRenderer(private val context: Context, private val rootNode: Node2D) : GLSurfaceView.Renderer {

    private var colorProgram = 0
    private var texProgram = 0
    private var lastFrameTime = System.nanoTime()
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val textureCache = mutableMapOf<String, Int>()

    private val quadData = floatArrayOf(
        // X,    Y,    U,  V
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
            "uniform mat4 uMVPMatrix; attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main() { gl_Position = uMVPMatrix * aPosition; vTexCoord = aTexCoord; }",
            "precision mediump float; uniform sampler2D uTexture; uniform float uAlpha; varying vec2 vTexCoord; void main() { vec4 c = texture2D(uTexture, vTexCoord); gl_FragColor = vec4(c.rgb, c.a * uAlpha); }"
        )
        lastFrameTime = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.orthoM(projectionMatrix, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val delta = ((now - lastFrameTime) / 1_000_000_000.0).toFloat().coerceAtMost(0.05f)
        lastFrameTime = now

        rootNode.updateTree(delta)
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

    fun drawTexture(assetPath: String, x: Float, y: Float, width: Float, height: Float, rotationDeg: Float, alpha: Float = 1f) {
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
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
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

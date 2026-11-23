package com.zero.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Function as JsFunction
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Context as JsContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// ==================== 1. 引擎调试控制台 ====================
object EngineConsole {
    private val logs = mutableListOf<String>()
    private const val MAX_LOGS = 100

    @Synchronized
    fun log(msg: String) {
        logs.add("[INFO] $msg")
        if (logs.size > MAX_LOGS) logs.removeAt(0)
    }

    @Synchronized
    fun error(msg: String) {
        logs.add("[ERROR] $msg")
        if (logs.size > MAX_LOGS) logs.removeAt(0)
    }

    @Synchronized
    fun dump(): String = logs.joinToString("\n")

    @Synchronized
    fun clear() = logs.clear()
}

// ==================== 2. 全局输入状态 ====================
object InputState {
    @JvmField var isTouching: Boolean = false
    @JvmField var touchX: Float = 0f
    @JvmField var touchY: Float = 0f
    @JvmField var justPressed: Boolean = false
    @JvmField var justReleased: Boolean = false

    fun endFrame() {
        justPressed = false
        justReleased = false
    }
}

// ==================== 3. 层级节点基类 ====================
open class Node2D(@JvmField var name: String) {
    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
    @JvmField var vx: Float = 0f
    @JvmField var vy: Float = 0f
    @JvmField var rotation: Float = 0f
    @JvmField var scaleX: Float = 1f
    @JvmField var scaleY: Float = 1f
    @JvmField var zIndex: Int = 0
    @JvmField var visible: Boolean = true
    @JvmField var tag: String = "default"
    var script: String = ""

    internal var jsScope: Scriptable? = null
    internal var fnReady: JsFunction? = null
    internal var fnProcess: JsFunction? = null
    internal var fnInput: JsFunction? = null

    val globalRotation: Float get() = (parent?.globalRotation ?: 0f) + rotation
    val globalScaleX: Float get() = (parent?.globalScaleX ?: 1f) * scaleX
    val globalScaleY: Float get() = (parent?.globalScaleY ?: 1f) * scaleY

    val globalX: Float
        get() {
            val p = parent ?: return x
            val rad = Math.toRadians(p.globalRotation.toDouble())
            val sx = x * p.globalScaleX
            val sy = y * p.globalScaleY
            return p.globalX + (sx * cos(rad) - sy * sin(rad)).toFloat()
        }

    val globalY: Float
        get() {
            val p = parent ?: return y
            val rad = Math.toRadians(p.globalRotation.toDouble())
            val sx = x * p.globalScaleX
            val sy = y * p.globalScaleY
            return p.globalY + (sx * sin(rad) + sy * cos(rad)).toFloat()
        }

    var parent: Node2D? = null
        private set
    val children = mutableListOf<Node2D>()

    fun addChild(child: Node2D) {
        child.parent?.removeChild(child)
        child.parent = this
        children.add(child)
    }

    fun removeChild(child: Node2D) {
        if (child.parent === this) {
            child.parent = null
            children.remove(child)
        }
    }

    fun distanceTo(other: Node2D): Float = hypot(globalX - other.globalX, globalY - other.globalY)

    fun moveAndSlide(delta: Float, colliders: List<Collider2D>): String {
        val myCol = findNodesByType<Collider2D>().firstOrNull()
        var hitTag = ""

        x += vx * delta
        if (myCol != null) {
            for (other in colliders) {
                if (other === myCol || other.isTrigger) continue
                if (myCol.intersects(other)) {
                    val halfW = (myCol.width * globalScaleX + other.width * other.globalScaleX) / 2f
                    x = if (vx > 0f) other.globalX - halfW else other.globalX + halfW
                    vx = 0f
                    hitTag = other.colliderTag
                }
            }
        }

        y += vy * delta
        if (myCol != null) {
            for (other in colliders) {
                if (other === myCol || other.isTrigger) continue
                if (myCol.intersects(other)) {
                    val halfH = (myCol.height * globalScaleY + other.height * other.globalScaleY) / 2f
                    y = if (vy > 0f) other.globalY - halfH else other.globalY + halfH
                    vy = 0f
                    hitTag = other.colliderTag
                }
            }
        }
        return hitTag
    }

    inline fun <reified T : Node2D> findNodesByType(): List<T> {
        val result = mutableListOf<T>()
        collectNodes(T::class.java, result)
        return result
    }

    fun <T : Node2D> collectNodes(clazz: Class<T>, out: MutableList<T>) {
        if (clazz.isInstance(this)) clazz.cast(this)?.let { out.add(it) }
        val snapshot = children.toList()
        for (i in snapshot.indices) snapshot[i].collectNodes(clazz, out)
    }

    open fun updateNode(delta: Float) {}
    open fun onDraw(renderer: GLRenderer) {}
}

// ==================== 4. 精灵节点（支持 SpriteSheet 帧动画 + 自定义 GLSL Shader） ====================
open class Sprite2D(
    name: String,
    @JvmField var width: Float = 100f,
    @JvmField var height: Float = 100f,
    @JvmField var texturePath: String? = null,
    @JvmField var r: Float = 1f,
    @JvmField var g: Float = 1f,
    @JvmField var b: Float = 1f,
    @JvmField var a: Float = 1f
) : Node2D(name) {

    // SpriteSheet 序列帧参数
    @JvmField var hFrames: Int = 1
    @JvmField var vFrames: Int = 1
    @JvmField var currentFrame: Int = 0
    @JvmField var animStart: Int = 0
    @JvmField var animEnd: Int = 0
    @JvmField var animFps: Float = 12f
    @JvmField var animPlaying: Boolean = false
    @JvmField var animLoop: Boolean = true
    private var animTimer: Float = 0f

    // 自定义 GLSL Fragment Shader 代码
    var fragmentShader: String = ""

    fun playAnim(start: Int, end: Int, fps: Float = 12f, loop: Boolean = true) {
        animStart = start
        animEnd = end
        animFps = fps
        animLoop = loop
        if (!animPlaying || currentFrame < start || currentFrame > end) {
            currentFrame = start
            animTimer = 0f
        }
        animPlaying = true
    }

    fun stopAnim() {
        animPlaying = false
    }

    override fun updateNode(delta: Float) {
        if (!animPlaying || animFps <= 0f) return
        animTimer += delta
        val step = 1f / animFps
        if (animTimer >= step) {
            animTimer -= step
            currentFrame++
            if (currentFrame > animEnd) {
                if (animLoop) {
                    currentFrame = animStart
                } else {
                    currentFrame = animEnd
                    animPlaying = false
                }
            }
        }
    }

    override fun onDraw(renderer: GLRenderer) {
        val w = width * globalScaleX
        val h = height * globalScaleY
        val hf = hFrames.coerceAtLeast(1)
        val vf = vFrames.coerceAtLeast(1)
        val total = hf * vf
        val frameIdx = currentFrame.coerceIn(0, total - 1)
        val col = frameIdx % hf
        val row = frameIdx / hf
        val uvScaleX = 1f / hf
        val uvScaleY = 1f / vf
        val uvOffsetX = col * uvScaleX
        val uvOffsetY = row * uvScaleY

        renderer.drawSprite(
            assetPath = texturePath,
            x = globalX,
            y = globalY,
            width = w,
            height = h,
            rotationDeg = globalRotation,
            r = r, g = g, b = b, a = a,
            uvOffsetX = uvOffsetX,
            uvOffsetY = uvOffsetY,
            uvScaleX = uvScaleX,
            uvScaleY = uvScaleY,
            customFragShader = fragmentShader,
            nodeKey = name
        )
    }
}

// ==================== 5. 文本节点 ====================
class Label2D(
    name: String,
    @JvmField var text: String = "",
    @JvmField var fontSize: Float = 44f,
    @JvmField var textColor: Int = Color.WHITE
) : Node2D(name) {
    override fun onDraw(renderer: GLRenderer) {
        if (text.isNotEmpty()) renderer.drawText(text, globalX, globalY, fontSize, textColor)
    }
}

// ==================== 6. 2D 相机 ====================
class Camera2D(name: String = "Camera2D") : Node2D(name) {
    @JvmField var enabled: Boolean = true
    @JvmField var zoom: Float = 1f
    @JvmField var followTarget: Node2D? = null
    @JvmField var followLerp: Float = 8f

    private var shakeIntensity: Float = 0f
    private var shakeTimer: Float = 0f
    internal var shakeOffsetX: Float = 0f
    internal var shakeOffsetY: Float = 0f

    fun shake(intensity: Float = 20f, duration: Float = 0.25f) {
        shakeIntensity = intensity
        shakeTimer = duration
    }

    fun updateCamera(delta: Float) {
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

// ==================== 7. 碰撞体与触发器 ====================
class Collider2D(
    name: String,
    @JvmField var width: Float,
    @JvmField var height: Float,
    @JvmField var colliderTag: String = "default",
    @JvmField var isTrigger: Boolean = false
) : Node2D(name) {
    fun intersects(other: Collider2D): Boolean {
        val halfW1 = (width * globalScaleX) / 2f
        val halfH1 = (height * globalScaleY) / 2f
        val halfW2 = (other.width * other.globalScaleX) / 2f
        val halfH2 = (other.height * other.globalScaleY) / 2f
        return abs(globalX - other.globalX) < (halfW1 + halfW2) &&
               abs(globalY - other.globalY) < (halfH1 + halfH2)
    }
}

// ==================== 8. 音频合成与音效总线 ====================
object AudioEngine {
    private var soundPool: SoundPool? = null
    private val soundCache = mutableMapOf<String, Int>()

    fun init() {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
    }

    fun playSfx(projectDir: File, fileName: String, volume: Float = 1f) {
        val pool = soundPool ?: return
        val file = File(projectDir, "audio/$fileName")
        if (!file.exists()) return
        val id = soundCache.getOrPut(file.absolutePath) { pool.load(file.absolutePath, 1) }
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    // 内置零资源依赖程序化合成音效（方便开发者直接测试打击/跳跃/警报音效）
    fun playTone(freqHz: Float, durationMs: Int) {
        Thread {
            try {
                val sampleRate = 22050
                val numSamples = (durationMs * sampleRate) / 1000
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val env = 1f - (i.toFloat() / numSamples)
                    val angle = 2.0 * Math.PI * i * freqHz / sampleRate
                    buffer[i] = (sin(angle) * 16000.0 * env).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buffer, 0, buffer.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 30L)
                track.release()
            } catch (_: Exception) {}
        }.start()
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        soundCache.clear()
    }
}

// ==================== 9. 原生脚本虚拟机（Rhino Runtime + 场景实例化） ====================
class ScriptRuntime(
    private val projectDir: File,
    private val rootNode: Node2D,
    private val camera: Camera2D
) {
    private var jsContext: JsContext? = null
    private lateinit var baseScope: Scriptable
    private val signalListeners = mutableMapOf<String, MutableList<Pair<Scriptable, JsFunction>>>()

    inner class EngineBridgeAPI {
        fun log(msg: Any?) = EngineConsole.log(msg.toString())
        fun getCamera(): Camera2D = camera

        fun getNode(name: String): Node2D? =
            rootNode.findNodesByType<Node2D>().firstOrNull { it.name == name }

        fun getNodesByTag(tag: String): Array<Node2D> =
            rootNode.findNodesByType<Node2D>().filter { it.tag == tag }.toTypedArray()

        fun shake(intensity: Float, duration: Float) = camera.shake(intensity, duration)
        fun setCameraTarget(node: Node2D?) { camera.followTarget = node }

        fun playSfx(fileName: String, volume: Float) = AudioEngine.playSfx(projectDir, fileName, volume)
        fun playTone(freqHz: Float, durationMs: Int) = AudioEngine.playTone(freqHz, durationMs)

        fun moveAndSlide(node: Node2D, delta: Float): String =
            node.moveAndSlide(delta, rootNode.findNodesByType())

        fun isOverlapping(a: Node2D, b: Node2D): Boolean {
            val c1 = a.findNodesByType<Collider2D>().firstOrNull() ?: return false
            val c2 = b.findNodesByType<Collider2D>().firstOrNull() ?: return false
            return c1.intersects(c2)
        }

        fun getOverlapping(node: Node2D, filterTag: String): Array<Node2D> {
            val myCol = node.findNodesByType<Collider2D>().firstOrNull() ?: return emptyArray()
            val allCols = rootNode.findNodesByType<Collider2D>()
            val hits = mutableListOf<Node2D>()
            for (other in allCols) {
                if (other === myCol) continue
                val p = other.parent ?: continue
                if ((filterTag.isEmpty() || p.tag == filterTag) && myCol.intersects(other)) hits.add(p)
            }
            return hits.toTypedArray()
        }

        fun raycast(x1: Float, y1: Float, x2: Float, y2: Float, ignoreTag: String): Node2D? {
            val allCols = rootNode.findNodesByType<Collider2D>()
            var closestNode: Node2D? = null
            var minT = 1.0001f
            for (col in allCols) {
                val p = col.parent ?: continue
                if (ignoreTag.isNotEmpty() && p.tag == ignoreTag) continue
                val hw = (col.width * col.globalScaleX) / 2f
                val hh = (col.height * col.globalScaleY) / 2f
                val t = segmentAABB(x1, y1, x2, y2, col.globalX - hw, col.globalY - hh, col.globalX + hw, col.globalY + hh)
                if (t != null && t < minT) {
                    minT = t
                    closestNode = p
                }
            }
            return closestNode
        }

        // 从项目工程加载子场景预制体（Prefab）
        fun instantiate(sceneFileName: String, x: Float, y: Float): Node2D? {
            val file = File(projectDir, "scenes/$sceneFileName")
            if (!file.exists()) {
                EngineConsole.error("找不到场景预制体: scenes/$sceneFileName")
                return null
            }
            val container = Node2D("Prefab_${System.nanoTime() % 100000}").apply {
                this.x = x
                this.y = y
            }
            SceneLoader.loadFromString(file.readText(), container)
            rootNode.addChild(container)
            val spawnedNodes = container.findNodesByType<Node2D>()
            for (n in spawnedNodes) compileNodeScript(n)
            for (n in spawnedNodes) callLifecycle(n, n.fnReady, emptyArray())
            return container
        }

        // 代码动态创建节点
        fun spawn(config: NativeObject, parentName: String): Node2D {
            val name = config.get("name", config)?.toString() ?: "Spawned_${System.nanoTime() % 10000}"
            val w = (config.get("width", config) as? Number)?.toFloat() ?: 60f
            val h = (config.get("height", config) as? Number)?.toFloat() ?: 60f
            val tex = (config.get("texture", config) as? String) ?: ""
            val sprite = Sprite2D(
                name = name, width = w, height = h,
                texturePath = tex.ifEmpty { null },
                r = (config.get("r", config) as? Number)?.toFloat() ?: 1f,
                g = (config.get("g", config) as? Number)?.toFloat() ?: 1f,
                b = (config.get("b", config) as? Number)?.toFloat() ?: 1f,
                a = (config.get("a", config) as? Number)?.toFloat() ?: 1f
            )
            sprite.x = (config.get("x", config) as? Number)?.toFloat() ?: 0f
            sprite.y = (config.get("y", config) as? Number)?.toFloat() ?: 0f
            sprite.rotation = (config.get("rotation", config) as? Number)?.toFloat() ?: 0f
            sprite.tag = (config.get("tag", config) as? String) ?: "spawned"
            sprite.script = (config.get("script", config) as? String) ?: ""
            sprite.fragmentShader = (config.get("shader", config) as? String) ?: ""

            if ((config.get("collider", config) as? Boolean) == true) {
                val isTrig = (config.get("isTrigger", config) as? Boolean) ?: false
                sprite.addChild(Collider2D("${name}_Col", w, h, sprite.tag, isTrig))
            }

            val targetParent = if (parentName.isNotEmpty()) getNode(parentName) ?: rootNode else rootNode
            targetParent.addChild(sprite)
            compileNodeScript(sprite)
            callLifecycle(sprite, sprite.fnReady, emptyArray())
            return sprite
        }

        fun destroy(node: Node2D?) {
            node?.parent?.removeChild(node)
        }

        fun on(signal: String, callback: JsFunction) {
            signalListeners.getOrPut(signal) { mutableListOf() }.add(baseScope to callback)
        }

        fun emit(signal: String, payload: Any?) {
            val cx = jsContext ?: return
            val list = signalListeners[signal]?.toList() ?: return
            for ((scope, fn) in list) {
                try {
                    fn.call(cx, scope, scope, arrayOf(payload))
                } catch (e: Throwable) {
                    EngineConsole.error("Signal [$signal]: ${e.message}")
                }
            }
        }
    }

    fun init() {
        val cx = JsContext.enter()
        cx.optimizationLevel = -1
        jsContext = cx
        baseScope = cx.initStandardObjects()

        ScriptableObject.putProperty(baseScope, "Engine", JsContext.javaToJS(EngineBridgeAPI(), baseScope))
        ScriptableObject.putProperty(baseScope, "Input", JsContext.javaToJS(InputState, baseScope))

        val allNodes = rootNode.findNodesByType<Node2D>()
        for (node in allNodes) compileNodeScript(node)
        for (node in allNodes) callLifecycle(node, node.fnReady, emptyArray())
    }

    fun compileNodeScript(node: Node2D) {
        if (node.script.isBlank()) return
        val cx = jsContext ?: return
        try {
            val scope = cx.newObject(baseScope)
            scope.prototype = baseScope
            scope.parentScope = null
            ScriptableObject.putProperty(scope, "self", JsContext.javaToJS(node, scope))
            cx.evaluateString(scope, node.script, node.name, 1, null)

            node.jsScope = scope
            node.fnReady = scope.get("onReady", scope) as? JsFunction
            node.fnProcess = scope.get("onProcess", scope) as? JsFunction
            node.fnInput = scope.get("onInput", scope) as? JsFunction
        } catch (e: Throwable) {
            EngineConsole.error("[${node.name}] 脚本语法错误: ${e.message}")
        }
    }

    fun stepProcess(delta: Float) {
        val snapshot = rootNode.findNodesByType<Node2D>()
        for (node in snapshot) {
            node.updateNode(delta)
            if (node.fnProcess != null) callLifecycle(node, node.fnProcess, arrayOf(delta))
        }
    }

    fun dispatchInput(type: String, x: Float, y: Float) {
        val cx = jsContext ?: return
        val snapshot = rootNode.findNodesByType<Node2D>()
        for (node in snapshot) {
            val fn = node.fnInput ?: continue
            val scope = node.jsScope ?: continue
            try {
                val ev = cx.newObject(scope)
                ScriptableObject.putProperty(ev, "type", type)
                ScriptableObject.putProperty(ev, "x", x)
                ScriptableObject.putProperty(ev, "y", y)
                fn.call(cx, scope, scope, arrayOf(ev))
            } catch (e: Throwable) {
                EngineConsole.error("[${node.name}] onInput: ${e.message}")
            }
        }
    }

    private fun callLifecycle(node: Node2D, fn: JsFunction?, args: Array<Any?>) {
        if (fn == null) return
        val cx = jsContext ?: return
        val scope = node.jsScope ?: return
        try {
            fn.call(cx, scope, scope, args)
        } catch (e: Throwable) {
            EngineConsole.error("[${node.name}] 运行错误: ${e.message}")
        }
    }

    private fun segmentAABB(x1: Float, y1: Float, x2: Float, y2: Float, minX: Float, minY: Float, maxX: Float, maxY: Float): Float? {
        val dx = x2 - x1
        val dy = y2 - y1
        var t0 = 0f
        var t1 = 1f
        val checks = arrayOf(
            floatArrayOf(-dx, x1 - minX),
            floatArrayOf(dx, maxX - x1),
            floatArrayOf(-dy, y1 - minY),
            floatArrayOf(dy, maxY - y1)
        )
        for (c in checks) {
            val p = c[0]; val q = c[1]
            if (abs(p) < 1e-6f) {
                if (q < 0f) return null
            } else {
                val t = q / p
                if (p < 0f) { if (t > t1) return null; if (t > t0) t0 = t }
                else { if (t < t0) return null; if (t < t1) t1 = t }
            }
        }
        return if (t0 <= t1) t0 else null
    }

    fun release() {
        if (jsContext != null) {
            try { JsContext.exit() } catch (_: Exception) {}
            jsContext = null
        }
        signalListeners.clear()
    }
}

// ==================== 10. 场景序列化加载器 ====================
object SceneLoader {
    fun loadFromString(jsonText: String, targetRoot: Node2D) {
        val rootObj = JSONObject(jsonText)
        val nodesArray = rootObj.optJSONArray("nodes") ?: JSONArray()
        val nodeMap = mutableMapOf<String, Node2D>()
        val parentLinks = mutableListOf<Pair<Node2D, String>>()

        for (i in 0 until nodesArray.length()) {
            val item = nodesArray.getJSONObject(i)
            val name = item.optString("name", "Node_$i")
            val type = item.optString("nodeType", "Sprite2D")
            val w = item.optDouble("width", 100.0).toFloat()
            val h = item.optDouble("height", 100.0).toFloat()
            val tex = item.optString("texture", "")

            val node: Node2D = if (type == "Label2D") {
                Label2D(
                    name = name,
                    text = item.optString("text", "Label"),
                    fontSize = item.optDouble("fontSize", 44.0).toFloat(),
                    textColor = Color.WHITE
                )
            } else {
                Sprite2D(
                    name = name, width = w, height = h,
                    texturePath = tex.ifEmpty { null },
                    r = item.optDouble("r", 1.0).toFloat(),
                    g = item.optDouble("g", 1.0).toFloat(),
                    b = item.optDouble("b", 1.0).toFloat(),
                    a = item.optDouble("a", 1.0).toFloat()
                ).apply {
                    hFrames = item.optInt("hFrames", 1).coerceAtLeast(1)
                    vFrames = item.optInt("vFrames", 1).coerceAtLeast(1)
                    currentFrame = item.optInt("currentFrame", 0)
                    animStart = item.optInt("animStart", 0)
                    animEnd = item.optInt("animEnd", 0)
                    animFps = item.optDouble("animFps", 12.0).toFloat()
                    animPlaying = item.optBoolean("animPlaying", false)
                    animLoop = item.optBoolean("animLoop", true)
                    fragmentShader = item.optString("shader", "")
                }
            }

            node.x = item.optDouble("x", 0.0).toFloat()
            node.y = item.optDouble("y", 0.0).toFloat()
            node.rotation = item.optDouble("rotation", 0.0).toFloat()
            node.scaleX = item.optDouble("scaleX", 1.0).toFloat()
            node.scaleY = item.optDouble("scaleY", 1.0).toFloat()
            node.zIndex = item.optInt("zIndex", 0)
            node.tag = item.optString("tag", "default")
            node.script = item.optString("script", "")

            if (item.optBoolean("collider", false)) {
                val isTrigger = item.optBoolean("isTrigger", false)
                node.addChild(Collider2D("${name}_Collider", w, h, node.tag, isTrigger))
            }

            nodeMap[name] = node
            parentLinks.add(node to item.optString("parent", ""))
        }

        for ((node, parentName) in parentLinks) {
            val parentNode = if (parentName.isNotEmpty()) nodeMap[parentName] else null
            if (parentNode != null && parentNode !== node) parentNode.addChild(node)
            else targetRoot.addChild(node)
        }
    }
}

// ==================== 11. OpenGL ES 2.0 硬件渲染管线（支持自定义 GLSL Shader） ====================
class GLRenderer(
    private val context: Context,
    private val projectDir: File,
    private val rootNode: Node2D,
    private val camera: Camera2D,
    private val scriptRuntime: ScriptRuntime
) : GLSurfaceView.Renderer {

    private var defaultProgram = 0
    private var whiteTexId = 0
    private var lastFrameTime = System.nanoTime()
    private var elapsedTime = 0f
    private var screenW = 1080f
    private var screenH = 1920f

    @Volatile var currentFps: Int = 60
    @Volatile var nodeCount: Int = 0
    @Volatile var drawCalls: Int = 0
    private var fpsTimer = 0f
    private var fpsFrames = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val textureCache = mutableMapOf<String, Int>()
    private val textTextureCache = mutableMapOf<String, Triple<Int, Float, Float>>()
    private val customShaderPrograms = mutableMapOf<String, Int>()

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform vec2 uUVOffset;
        uniform vec2 uUVScale;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = uUVOffset + aTexCoord * uUVScale;
        }
    """.trimIndent()

    private val defaultFragmentShaderCode = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec4 uColor;
        uniform float uTime;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord) * uColor;
        }
    """.trimIndent()

    private val quadData = floatArrayOf(
        -0.5f, -0.5f, 0f, 0f,
         0.5f, -0.5f, 1f, 0f,
        -0.5f,  0.5f, 0f, 1f,
         0.5f,  0.5f, 1f, 1f
    )
    private lateinit var vertexBuffer: FloatBuffer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.07f, 0.10f, 1.0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        vertexBuffer = ByteBuffer.allocateDirect(quadData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadData)
        vertexBuffer.position(0)

        // 生成 1x1 纯白默认纹理，使纯色矩形与贴图统一走同一个 Shader 管线
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        whiteTexId = ids[0]
        val whiteBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, whiteBmp, 0)
        whiteBmp.recycle()

        defaultProgram = buildProgram(vertexShaderCode, defaultFragmentShaderCode, "DefaultShader")
        scriptRuntime.init()
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
        val delta = ((now - lastFrameTime) / 1_000_000_000.0).toFloat().coerceIn(0.0001f, 0.05f)
        lastFrameTime = now
        elapsedTime += delta

        fpsTimer += delta
        fpsFrames++
        if (fpsTimer >= 0.5f) {
            currentFps = (fpsFrames / fpsTimer).toInt()
            fpsTimer = 0f
            fpsFrames = 0
        }

        scriptRuntime.stepProcess(delta)
        camera.updateCamera(delta)
        InputState.endFrame()

        Matrix.setIdentityM(viewMatrix, 0)
        if (camera.enabled) {
            val z = camera.zoom.coerceIn(0.1f, 10f)
            Matrix.translateM(viewMatrix, 0, screenW / 2f, screenH / 2f, 0f)
            Matrix.scaleM(viewMatrix, 0, z, z, 1f)
            Matrix.translateM(viewMatrix, 0, -(camera.globalX + camera.shakeOffsetX), -(camera.globalY + camera.shakeOffsetY), 0f)
        }
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCalls = 0
        val allNodes = rootNode.findNodesByType<Node2D>()
        nodeCount = allNodes.size
        val renderList = allNodes.filter { it.visible }.sortedBy { it.zIndex }
        for (i in renderList.indices) renderList[i].onDraw(this)
    }

    fun drawSprite(
        assetPath: String?,
        x: Float, y: Float, width: Float, height: Float, rotationDeg: Float,
        r: Float, g: Float, b: Float, a: Float,
        uvOffsetX: Float, uvOffsetY: Float, uvScaleX: Float, uvScaleY: Float,
        customFragShader: String, nodeKey: String
    ) {
        val texId = if (!assetPath.isNullOrEmpty()) getOrLoadTexture(assetPath) else whiteTexId
        val prog = if (customFragShader.isNotBlank()) {
            customShaderPrograms.getOrPut(customFragShader) {
                val p = buildProgram(vertexShaderCode, customFragShader, nodeKey)
                if (p != 0) p else defaultProgram
            }
        } else {
            defaultProgram
        }
        drawQuad(prog, texId, x, y, width, height, rotationDeg, r, g, b, a, uvOffsetX, uvOffsetY, uvScaleX, uvScaleY)
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
            Canvas(bmp).drawText(text, 12f, fontSize * 1.1f, paint)

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            Triple(ids[0], tw.toFloat(), th.toFloat())
        }
        drawQuad(defaultProgram, texId, x, y, w, h, 0f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 1f)
    }

    private fun drawQuad(
        program: Int, texId: Int, x: Float, y: Float, width: Float, height: Float, rotationDeg: Float,
        r: Float, g: Float, b: Float, a: Float,
        uvOffsetX: Float, uvOffsetY: Float, uvScaleX: Float, uvScaleY: Float
    ) {
        GLES20.glUseProgram(program)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, 0f)
        if (rotationDeg != 0f) Matrix.rotateM(modelMatrix, 0, rotationDeg, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, width, height, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        vertexBuffer.position(0)
        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        if (posHandle >= 0) {
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        }

        vertexBuffer.position(2)
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        if (texHandle >= 0) {
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uColor"), r, g, b, a)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTime"), elapsedTime)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUVOffset"), uvOffsetX, uvOffsetY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uUVScale"), uvScaleX, uvScaleY)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVPMatrix"), 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        drawCalls++

        if (posHandle >= 0) GLES20.glDisableVertexAttribArray(posHandle)
        if (texHandle >= 0) GLES20.glDisableVertexAttribArray(texHandle)
    }

    private fun getOrLoadTexture(path: String): Int {
        textureCache[path]?.let { return it }
        return try {
            val localFile = File(projectDir, "textures/$path")
            val bitmap = if (localFile.exists()) {
                BitmapFactory.decodeFile(localFile.absolutePath)
            } else {
                context.assets.open(path).use { BitmapFactory.decodeStream(it) }
            } ?: return whiteTexId

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            textureCache[path] = texIds[0]
            texIds[0]
        } catch (_: Exception) {
            whiteTexId
        }
    }

    private fun buildProgram(vCode: String, fCode: String, label: String): Int {
        val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vCode)
            GLES20.glCompileShader(it)
        }
        val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fCode)
            GLES20.glCompileShader(it)
        }
        val status = IntArray(1)
        GLES20.glGetShaderiv(f, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val err = GLES20.glGetShaderInfoLog(f)
            EngineConsole.error("[$label] GLSL Shader 编译错误:\n$err")
            return 0
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)
            GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
        }
    }
}
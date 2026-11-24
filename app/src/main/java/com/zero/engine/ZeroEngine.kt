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
import android.media.MediaPlayer
import android.media.SoundPool
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class ZeroEngineView(
    context: Context,
    private val projectDir: File,
    private val sceneJson: String,
    private val onConsoleLog: (String) -> Unit
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    data class AnimClip(
        val name: String,
        val start: Int,
        val end: Int,
        val fps: Float,
        val loop: Boolean,
        val events: Map<Int, String> = emptyMap()
    )

    data class TileCell(
        val gx: Int,
        val gy: Int,
        val frame: Int
    )

    data class Particle(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var size: Float = 12f,
        var life: Float = 0f,
        var maxLife: Float = 1f,
        var alpha: Float = 1f
    )

    data class TweenTask(
        val node: GameNode,
        val prop: String,
        val startVal: Float,
        val targetVal: Float,
        val duration: Float,
        val ease: String,
        var elapsed: Float = 0f
    )

    data class DebugRay(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val hit: Boolean,
        var ttl: Float = 0.16f
    )

    data class ColliderBounds(
        val cx: Float,
        val cy: Float,
        val hw: Float,
        val hh: Float
    )

    data class GameNode(
        var name: String,
        var nodeType: String = "Sprite2D", // Sprite2D | Label2D | PointLight | ParticleEmitter | TileMap
        var layer: String = "World",       // World | UI
        var parent: String = "",
        var tag: String = "default",
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = 0f,
        var width: Float = 120f,
        var height: Float = 120f,
        var rotation: Float = 0f,
        var tiltX: Float = 0f,
        var billboard: Boolean = false,
        var scaleX: Float = 1f,
        var scaleY: Float = 1f,
        var zIndex: Int = 0,
        var visible: Boolean = true,
        // 通用视差滚动系数
        var parallaxX: Float = 1f,
        var parallaxY: Float = 1f,
        var texture: String = "",
        var text: String = "",
        var fontSize: Float = 44f,
        var r: Float = 1f,
        var g: Float = 1f,
        var b: Float = 1f,
        var a: Float = 1f,
        var flashTimer: Float = 0f,
        var lightRadius: Float = 450f,
        var lightIntensity: Float = 1.4f,
        var shadowCaster: Boolean = false,
        // 通用物理、平台、斜坡与 P1 独立碰撞体参数
        var collider: Boolean = false,
        var isTrigger: Boolean = false,
        var oneWay: Boolean = false,
        var slopeType: String = "none",     // none | left_up (/) | right_up (\)
        var colOffsetX: Float = 0f,
        var colOffsetY: Float = 0f,
        var colWidth: Float = 0f,           // <= 0 时自动使用 node.width
        var colHeight: Float = 0f,          // <= 0 时自动使用 node.height
        var colLayer: Int = 1,              // 碰撞所属层位掩码
        var colMask: Int = 0xFFFF,          // 碰撞目标层位掩码
        var bodyMode: String = "kinematic", // kinematic | platformer | rigid
        var gravity: Float = 2200f,
        var fallMultiplier: Float = 1.55f,
        var maxFallSpeed: Float = 1450f,
        var bounciness: Float = 0.5f,
        var drag: Float = 0f,
        var isGrounded: Boolean = false,
        var coyoteTimer: Float = 0f,
        var coyoteTimeMax: Float = 0.12f,
        var jumpCount: Int = 0,
        var maxJumps: Int = 2,
        var dropThroughTimer: Float = 0f,
        // 通用战斗判定属性
        var isHitbox: Boolean = false,
        var isHurtbox: Boolean = false,
        var damage: Float = 10f,
        var invulnTimer: Float = 0f,
        var hitCooldownMap: MutableMap<String, Float> = mutableMapOf(),
        // 序列帧与状态机
        var hFrames: Int = 1,
        var vFrames: Int = 1,
        var currentFrame: Int = 0,
        var animStart: Int = 0,
        var animEnd: Int = 0,
        var animFps: Float = 12f,
        var animPlaying: Boolean = false,
        var animLoop: Boolean = true,
        var animTimer: Float = 0f,
        var currentClipName: String = "",
        val clips: MutableMap<String, AnimClip> = mutableMapOf(),
        // TileMap 稀疏瓦片数据 + P0 VBO 缓存 + P1 O(1) 哈希查表
        var tileSize: Float = 90f,
        val tiles: MutableList<TileCell> = mutableListOf(),
        val tileLookup: HashMap<Long, TileCell> = HashMap(),
        var tileVboId: Int = 0,
        var tileVertexCount: Int = 0,
        var tileDirty: Boolean = true,
        var tileMinX: Float = 0f,
        var tileMaxX: Float = 0f,
        var tileMinY: Float = 0f,
        var tileMaxY: Float = 0f,
        // 原生 GPU 粒子池参数
        var emitRate: Float = 24f,
        var particleLife: Float = 1.1f,
        var particleSpeed: Float = 160f,
        var particleSize: Float = 16f,
        var particleGravity: Float = -60f,
        var emitAccum: Float = 0f,
        val particles: Array<Particle> = Array(240) { Particle() },
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vz: Float = 0f,
        var shader: String = "",
        var script: String = "",
        var jsObj: Scriptable? = null,
        var customProgram: Int = 0
    )

    // ==================== P0 + P1：零拷贝 JS 属性代理 ====================
    private inner class GameNodeJsProxy(val n: GameNode) : ScriptableObject() {
        override fun getClassName() = "GameNode"

        override fun has(name: String?, start: Scriptable?): Boolean {
            if (name != null && name in BRIDGED_PROPS) return true
            return super.has(name, start)
        }

        override fun get(name: String?, start: Scriptable?): Any? {
            return when (name) {
                "name" -> n.name
                "tag" -> n.tag
                "x" -> n.x.toDouble()
                "y" -> n.y.toDouble()
                "z" -> n.z.toDouble()
                "vx" -> n.vx.toDouble()
                "vy" -> n.vy.toDouble()
                "vz" -> n.vz.toDouble()
                "width" -> n.width.toDouble()
                "height" -> n.height.toDouble()
                "rotation" -> n.rotation.toDouble()
                "tiltX" -> n.tiltX.toDouble()
                "scaleX" -> n.scaleX.toDouble()
                "scaleY" -> n.scaleY.toDouble()
                "parallaxX" -> n.parallaxX.toDouble()
                "parallaxY" -> n.parallaxY.toDouble()
                "slopeType" -> n.slopeType
                "colOffsetX" -> n.colOffsetX.toDouble()
                "colOffsetY" -> n.colOffsetY.toDouble()
                "colWidth" -> n.colWidth.toDouble()
                "colHeight" -> n.colHeight.toDouble()
                "colLayer" -> n.colLayer
                "colMask" -> n.colMask
                "bodyMode" -> n.bodyMode
                "gravity" -> n.gravity.toDouble()
                "fallMultiplier" -> n.fallMultiplier.toDouble()
                "maxJumps" -> n.maxJumps
                "isGrounded" -> n.isGrounded
                "isHitbox" -> n.isHitbox
                "isHurtbox" -> n.isHurtbox
                "damage" -> n.damage.toDouble()
                "invulnTimer" -> n.invulnTimer.toDouble()
                "r" -> n.r.toDouble()
                "g" -> n.g.toDouble()
                "b" -> n.b.toDouble()
                "a" -> n.a.toDouble()
                "visible" -> n.visible
                "text" -> n.text
                "currentClip" -> n.currentClipName
                "lightRadius" -> n.lightRadius.toDouble()
                "lightIntensity" -> n.lightIntensity.toDouble()
                else -> super.get(name, start)
            }
        }

        override fun put(name: String?, start: Scriptable?, value: Any?) {
            when (name) {
                "tag" -> n.tag = toJsStr(value, n.tag)
                "x" -> n.x = toJsFloat(value, n.x)
                "y" -> n.y = toJsFloat(value, n.y)
                "z" -> n.z = toJsFloat(value, n.z)
                "vx" -> n.vx = toJsFloat(value, n.vx)
                "vy" -> n.vy = toJsFloat(value, n.vy)
                "vz" -> n.vz = toJsFloat(value, n.vz)
                "width" -> n.width = toJsFloat(value, n.width)
                "height" -> n.height = toJsFloat(value, n.height)
                "rotation" -> n.rotation = toJsFloat(value, n.rotation)
                "tiltX" -> n.tiltX = toJsFloat(value, n.tiltX)
                "scaleX" -> n.scaleX = toJsFloat(value, n.scaleX)
                "scaleY" -> n.scaleY = toJsFloat(value, n.scaleY)
                "parallaxX" -> n.parallaxX = toJsFloat(value, n.parallaxX)
                "parallaxY" -> n.parallaxY = toJsFloat(value, n.parallaxY)
                "slopeType" -> n.slopeType = toJsStr(value, n.slopeType)
                "colOffsetX" -> n.colOffsetX = toJsFloat(value, n.colOffsetX)
                "colOffsetY" -> n.colOffsetY = toJsFloat(value, n.colOffsetY)
                "colWidth" -> n.colWidth = toJsFloat(value, n.colWidth)
                "colHeight" -> n.colHeight = toJsFloat(value, n.colHeight)
                "colLayer" -> n.colLayer = toJsInt(value, n.colLayer)
                "colMask" -> n.colMask = toJsInt(value, n.colMask)
                "bodyMode" -> n.bodyMode = toJsStr(value, n.bodyMode)
                "gravity" -> n.gravity = toJsFloat(value, n.gravity)
                "fallMultiplier" -> n.fallMultiplier = toJsFloat(value, n.fallMultiplier)
                "maxJumps" -> n.maxJumps = toJsInt(value, n.maxJumps)
                "isHitbox" -> n.isHitbox = toJsBool(value, n.isHitbox)
                "isHurtbox" -> n.isHurtbox = toJsBool(value, n.isHurtbox)
                "damage" -> n.damage = toJsFloat(value, n.damage)
                "invulnTimer" -> n.invulnTimer = toJsFloat(value, n.invulnTimer)
                "r" -> n.r = toJsFloat(value, n.r)
                "g" -> n.g = toJsFloat(value, n.g)
                "b" -> n.b = toJsFloat(value, n.b)
                "a" -> n.a = toJsFloat(value, n.a)
                "visible" -> n.visible = toJsBool(value, n.visible)
                "text" -> n.text = toJsStr(value, n.text)
                "lightRadius" -> n.lightRadius = toJsFloat(value, n.lightRadius)
                "lightIntensity" -> n.lightIntensity = toJsFloat(value, n.lightIntensity)
                "name", "isGrounded", "currentClip" -> Unit
                else -> super.put(name, start, value)
            }
        }
    }

    private val nodes = mutableListOf<GameNode>()
    private val pendingAdd = mutableListOf<GameNode>()
    private val pendingRemove = mutableSetOf<GameNode>()
    private val activeTweens = mutableListOf<TweenTask>()
    private val textureCache = ConcurrentHashMap<String, Int>()
    private val eventListeners = mutableMapOf<String, MutableList<Function>>()

    // ==================== P1：空间哈希网格与调试线框状态 ====================
    private val spatialCellSize = 256f
    private val spatialGrid = HashMap<Long, MutableList<GameNode>>(256)
    private val activeTileMaps = mutableListOf<GameNode>()
    private val queryCandidates = LinkedHashSet<GameNode>(32)

    private var debugDrawEnabled = false
    private val debugRays = mutableListOf<DebugRay>()
    private val debugVertBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    // P0：有界 LRU 文字纹理缓存
    private val maxTextTextures = 64
    private val textTextureCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            if (size > maxTextTextures && eldest != null) {
                val texId = eldest.value
                if (texId != 0 && texId != whiteTexId) {
                    GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                }
                return true
            }
            return false
        }
    }

    // 跨关卡内存数据与异步场景切换
    private val globalDataStore = ConcurrentHashMap<String, Any?>()
    @Volatile private var pendingSceneLoad: String? = null

    // 音频管理器 (BGM + SFX)
    private var bgmPlayer: MediaPlayer? = null
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val sfxCache = ConcurrentHashMap<String, Int>()

    // 2D / 2.5D 摄像机状态与视锥边界
    private var camMode = "2.5D"
    private var camX = 540f
    private var camY = 960f
    private var camPitch = 48f
    private var camDistance = 1250f
    private var camFov = 45f
    private var camTarget: GameNode? = null
    private var camSmoothSpeed = 9f
    private var camDeadzoneX = 0f
    private var camDeadzoneY = 0f
    private var camLookAheadX = 0f
    private var camLookAheadY = 0f
    private var camBoundsEnabled = false
    private var camMinX = -99999f
    private var camMinY = -99999f
    private var camMaxX = 99999f
    private var camMaxY = 99999f
    private var shakeIntensity = 0f
    private var shakeTimer = 0f

    private var viewMinX = -99999f
    private var viewMaxX = 99999f
    private var viewMinY = -99999f
    private var viewMaxY = 99999f

    // 实时性能统计
    private var smoothedFps = 60f
    private var frameDrawCalls = 0
    private var lastFrameDrawCalls = 0
    private var frameCulled = 0
    private var lastFrameCulled = 0

    // 全局顿帧与时间缩放
    private var hitstopTimer = 0f
    private var timeScale = 1f

    // 环境光
    private var ambientR = 0.52f
    private var ambientG = 0.56f
    private var ambientB = 0.68f

    // 触控输入状态
    @Volatile private var isTouching = false
    @Volatile private var touchScreenX = 0f
    @Volatile private var touchScreenY = 0f
    @Volatile private var touchWorldX = 0f
    @Volatile private var touchWorldY = 0f
    private val pendingTouchEvents = mutableListOf<Triple<String, Float, Float>>()

    // OpenGL 矩阵与着色器
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val invVpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val uiVpMatrix = FloatArray(16)

    private var defaultLitProgram = 0
    private var whiteTexId = 0
    private var engineTime = 0f
    private var lastFrameNs = 0L
    private var surfaceW = 1080
    private var surfaceH = 1920

    private lateinit var rhino: RhinoContext
    private lateinit var globalScope: ScriptableObject

    // 标准矩形顶点
    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -0.5f, -0.5f, 0f, 0f, 0f,
                 0.5f, -0.5f, 0f, 1f, 0f,
                -0.5f,  0.5f, 0f, 0f, 1f,
                 0.5f,  0.5f, 0f, 1f, 1f
            ))
            position(0)
        }

    // 斜坡三角形顶点: left_up (/) -> 右上、左下、右下
    private val slopeLeftUpBuffer: FloatBuffer = ByteBuffer.allocateDirect(3 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                 0.5f, -0.5f, 0f, 1f, 0f,
                -0.5f,  0.5f, 0f, 0f, 1f,
                 0.5f,  0.5f, 0f, 1f, 1f
            ))
            position(0)
        }

    // 斜坡三角形顶点: right_up (\) -> 左上、左下、右下
    private val slopeRightUpBuffer: FloatBuffer = ByteBuffer.allocateDirect(3 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -0.5f, -0.5f, 0f, 0f, 0f,
                -0.5f,  0.5f, 0f, 0f, 1f,
                 0.5f,  0.5f, 0f, 1f, 1f
            ))
            position(0)
        }

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun packGridKey(gx: Int, gy: Int): Long =
        (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)

    fun releaseResources() {
        queueEvent {
            releaseSceneGpuResources()
            if (textureCache.isNotEmpty()) {
                val ids = textureCache.values.filter { it != 0 && it != whiteTexId }.toIntArray()
                if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
                textureCache.clear()
            }
            if (textTextureCache.isNotEmpty()) {
                val ids = textTextureCache.values.filter { it != 0 && it != whiteTexId }.toIntArray()
                if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
                textTextureCache.clear()
            }
        }
        try {
            bgmPlayer?.stop()
            bgmPlayer?.release()
            bgmPlayer = null
        } catch (_: Exception) {}
        try {
            soundPool.release()
        } catch (_: Exception) {}
    }

    private fun releaseSceneGpuResources() {
        for (n in nodes) {
            if (n.tileVboId != 0) {
                GLES20.glDeleteBuffers(1, intArrayOf(n.tileVboId), 0)
                n.tileVboId = 0
                n.tileVertexCount = 0
            }
            if (n.customProgram != 0 && n.customProgram != defaultLitProgram) {
                GLES20.glDeleteProgram(n.customProgram)
                n.customProgram = 0
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        whiteTexId = createSolidWhiteTexture()
        defaultLitProgram = buildShaderProgram(VERTEX_SHADER, DEFAULT_LIT_FRAGMENT_SHADER)

        initRhinoVM()
        loadSceneContent(sceneJson)
        lastFrameNs = System.nanoTime()
    }

    private fun loadSceneContent(rawJson: String) {
        releaseSceneGpuResources()
        nodes.clear()
        pendingAdd.clear()
        pendingRemove.clear()
        activeTweens.clear()
        debugRays.clear()
        camTarget = null
        camBoundsEnabled = false
        parseSceneJson(rawJson, nodes)
        nodes.forEach { bindNodeScript(it) }
        rebuildSpatialIndex()
        nodes.toList().forEach { callLifecycle(it, "onReady") }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = max(1, width)
        surfaceH = max(1, height)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        Matrix.orthoM(uiVpMatrix, 0, 0f, 1080f, 1920f, 0f, -100f, 100f)
    }

    // ==================== P1：独立碰撞体边界计算与空间哈希索引 ====================
    private fun getColliderBounds(n: GameNode): ColliderBounds {
        val gt = getGlobalTransform(n)
        val rawW = if (n.colWidth > 0f) n.colWidth else n.width
        val rawH = if (n.colHeight > 0f) n.colHeight else n.height
        val hw = rawW * abs(gt.gSx) * 0.5f
        val hh = rawH * abs(gt.gSy) * 0.5f
        // 节点水平翻转（scaleX < 0）时，自动镜像 colOffsetX，方便动作游戏左右出刀
        val cx = gt.gx + n.colOffsetX * gt.gSx
        val cy = gt.gy + n.colOffsetY * gt.gSy
        return ColliderBounds(cx, cy, hw, hh)
    }

    private fun canCollideLayer(mover: GameNode, target: GameNode): Boolean {
        return (mover.colMask and target.colLayer) != 0
    }

    private fun rebuildSpatialIndex() {
        spatialGrid.clear()
        activeTileMaps.clear()
        for (n in nodes) {
            if (!n.visible || pendingRemove.contains(n)) continue
            if (!n.collider && !n.isHitbox && !n.isHurtbox) continue
            if (n.nodeType == "TileMap") {
                if (n.tiles.isNotEmpty()) activeTileMaps.add(n)
                continue
            }
            insertNodeIntoSpatialGrid(n)
        }
    }

    private fun insertNodeIntoSpatialGrid(n: GameNode) {
        val b = getColliderBounds(n)
        val minCx = floor((b.cx - b.hw) / spatialCellSize).toInt()
        val maxCx = floor((b.cx + b.hw) / spatialCellSize).toInt()
        val minCy = floor((b.cy - b.hh) / spatialCellSize).toInt()
        val maxCy = floor((b.cy + b.hh) / spatialCellSize).toInt()
        for (cx in minCx..maxCx) {
            for (cy in minCy..maxCy) {
                val key = packGridKey(cx, cy)
                spatialGrid.getOrPut(key) { mutableListOf() }.add(n)
            }
        }
    }

    private fun querySpatialCandidates(minX: Float, maxX: Float, minY: Float, maxY: Float, out: MutableSet<GameNode>) {
        out.clear()
        val minCx = floor(minX / spatialCellSize).toInt()
        val maxCx = floor(maxX / spatialCellSize).toInt()
        val minCy = floor(minY / spatialCellSize).toInt()
        val maxCy = floor(maxY / spatialCellSize).toInt()
        for (cx in minCx..maxCx) {
            for (cy in minCy..maxCy) {
                val bucket = spatialGrid[packGridKey(cx, cy)] ?: continue
                for (i in bucket.indices) {
                    out.add(bucket[i])
                }
            }
        }
        for (tm in activeTileMaps) {
            out.add(tm)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val rawDt = ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        val instFps = 1f / rawDt
        smoothedFps = smoothedFps * 0.9f + instFps * 0.1f

        pendingSceneLoad?.let { nextSceneFile ->
            pendingSceneLoad = null
            val file = File(File(projectDir, "scenes"), nextSceneFile)
            if (file.exists()) {
                onConsoleLog("[SCENE] 切换关卡场景 -> $nextSceneFile")
                loadSceneContent(file.readText())
            } else {
                onConsoleLog("[SCENE 错误] 找不到场景文件: $nextSceneFile")
            }
        }

        if (hitstopTimer > 0f) {
            hitstopTimer = max(0f, hitstopTimer - rawDt)
            updateCameraMatrices(rawDt)
            renderSceneFrame()
            return
        }

        val dt = rawDt * timeScale
        engineTime += dt

        if (debugRays.isNotEmpty()) {
            val iter = debugRays.iterator()
            while (iter.hasNext()) {
                val r = iter.next()
                r.ttl -= rawDt
                if (r.ttl <= 0f) iter.remove()
            }
        }

        updateCameraMatrices(dt)
        updateWorldTouchFromScreen()
        updateTweens(dt)

        flushTouchEvents()
        if (pendingAdd.isNotEmpty()) {
            val added = pendingAdd.toList()
            pendingAdd.clear()
            nodes.addAll(added)
            added.forEach { bindNodeScript(it); callLifecycle(it, "onReady") }
        }

        // 每帧开始重建空间哈希网格，加速所有物理移动与射线检测
        rebuildSpatialIndex()

        nodes.toList().forEach { n ->
            if (!pendingRemove.contains(n)) {
                if (n.flashTimer > 0f) n.flashTimer = max(0f, n.flashTimer - dt)
                if (n.invulnTimer > 0f) n.invulnTimer = max(0f, n.invulnTimer - dt)
                if (n.dropThroughTimer > 0f) n.dropThroughTimer = max(0f, n.dropThroughTimer - dt)
                if (n.hitCooldownMap.isNotEmpty()) {
                    val it = n.hitCooldownMap.iterator()
                    while (it.hasNext()) {
                        val entry = it.next()
                        val left = entry.value - dt
                        if (left <= 0f) it.remove() else entry.setValue(left)
                    }
                }
                updateSpriteAnimation(n, dt)
                if (n.nodeType == "ParticleEmitter") {
                    updateParticleEmitter(n, dt)
                }
                callLifecycle(n, "onProcess", dt)
            }
        }

        // 脚本位移结束后刷新空间索引，进行高精度战斗判定
        rebuildSpatialIndex()
        processCombatOverlaps()

        if (pendingRemove.isNotEmpty()) {
            for (rem in pendingRemove) {
                if (rem.tileVboId != 0) {
                    GLES20.glDeleteBuffers(1, intArrayOf(rem.tileVboId), 0)
                    rem.tileVboId = 0
                }
                if (rem.customProgram != 0 && rem.customProgram != defaultLitProgram) {
                    GLES20.glDeleteProgram(rem.customProgram)
                    rem.customProgram = 0
                }
            }
            nodes.removeAll(pendingRemove)
            pendingRemove.clear()
        }

        renderSceneFrame()
    }

    private fun isBoxInView(minX: Float, maxX: Float, minY: Float, maxY: Float): Boolean {
        return maxX >= viewMinX && minX <= viewMaxX && maxY >= viewMinY && minY <= viewMaxY
    }

    private fun renderSceneFrame() {
        frameDrawCalls = 0
        frameCulled = 0

        val lights = nodes.filter { it.visible && it.nodeType == "PointLight" }.take(8)
        val lightPosArr = FloatArray(8 * 4)
        val lightColArr = FloatArray(8 * 4)
        lights.forEachIndexed { idx, l ->
            val gt = getGlobalTransform(l)
            lightPosArr[idx * 4] = gt.gx
            lightPosArr[idx * 4 + 1] = gt.gy
            lightPosArr[idx * 4 + 2] = gt.gz
            lightPosArr[idx * 4 + 3] = l.lightRadius
            lightColArr[idx * 4] = l.r * l.lightIntensity
            lightColArr[idx * 4 + 1] = l.g * l.lightIntensity
            lightColArr[idx * 4 + 2] = l.b * l.lightIntensity
            lightColArr[idx * 4 + 3] = 1f
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val casters = nodes.filter { it.visible && it.layer != "UI" && it.shadowCaster && it.nodeType != "PointLight" }
        if (lights.isNotEmpty() && casters.isNotEmpty()) {
            for (caster in casters) {
                val cg = getGlobalTransform(caster)
                val pad = max(caster.width * abs(cg.gSx), caster.height * abs(cg.gSy)) * 1.6f
                if (isBoxInView(cg.gx - pad, cg.gx + pad, cg.gy - pad, cg.gy + pad)) {
                    renderDynamicShadow(caster, lights, vpMatrix)
                }
            }
        }

        val worldNodes = nodes.filter { it.visible && it.layer != "UI" && it.nodeType != "PointLight" }
            .sortedWith(compareBy({ it.zIndex }, { it.nodeType != "TileMap" }, { it.tiltX >= -45f }, { getGlobalTransform(it).gy }))

        for (n in worldNodes) {
            when (n.nodeType) {
                "ParticleEmitter" -> renderParticles(n, vpMatrix, isWorld = true)
                "TileMap" -> renderTileMap(n, vpMatrix, isWorld = true, lights.size, lightPosArr, lightColArr)
                else -> renderNode(n, vpMatrix, isWorld = true, lights.size, lightPosArr, lightColArr)
            }
        }

        // P1：调试线框渲染层（绘制在世界物体之上、UI 之下）
        if (debugDrawEnabled) {
            renderDebugOverlay(vpMatrix)
        }

        val uiNodes = nodes.filter { it.visible && it.layer == "UI" }.sortedBy { it.zIndex }
        for (n in uiNodes) {
            if (n.nodeType == "ParticleEmitter") {
                renderParticles(n, uiVpMatrix, isWorld = false)
            } else {
                renderNode(n, uiVpMatrix, isWorld = false, 0, lightPosArr, lightColArr)
            }
        }

        lastFrameDrawCalls = frameDrawCalls
        lastFrameCulled = frameCulled
    }

    // ==================== P1：一键 Debug 线框绘制 ====================
    private fun drawDebugRect(
        posLoc: Int, uvLoc: Int, colLoc: Int,
        minX: Float, minY: Float, maxX: Float, maxY: Float, z: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        debugVertBuf.clear()
        debugVertBuf.put(minX); debugVertBuf.put(minY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
        debugVertBuf.put(maxX); debugVertBuf.put(minY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
        debugVertBuf.put(maxX); debugVertBuf.put(maxY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
        debugVertBuf.put(minX); debugVertBuf.put(maxY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
        debugVertBuf.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, debugVertBuf)
        debugVertBuf.position(3)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, debugVertBuf)
        GLES20.glUniform4f(colLoc, r, g, b, a)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
        frameDrawCalls++
    }

    private fun drawDebugSlope(
        posLoc: Int, uvLoc: Int, colLoc: Int,
        minX: Float, minY: Float, maxX: Float, maxY: Float, z: Float,
        leftUp: Boolean
    ) {
        debugVertBuf.clear()
        if (leftUp) {
            debugVertBuf.put(maxX); debugVertBuf.put(minY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
            debugVertBuf.put(minX); debugVertBuf.put(maxY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
            debugVertBuf.put(maxX); debugVertBuf.put(maxY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
        } else {
            debugVertBuf.put(minX); debugVertBuf.put(minY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
            debugVertBuf.put(minX); debugVertBuf.put(maxY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
            debugVertBuf.put(maxX); debugVertBuf.put(maxY); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
        }
        debugVertBuf.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, debugVertBuf)
        debugVertBuf.position(3)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, debugVertBuf)
        GLES20.glUniform4f(colLoc, 0.75f, 1.0f, 0.15f, 0.95f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 3)
        frameDrawCalls++
    }

    private fun drawDebugLine(
        posLoc: Int, uvLoc: Int, colLoc: Int,
        x1: Float, y1: Float, x2: Float, y2: Float, z: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        debugVertBuf.clear()
        debugVertBuf.put(x1); debugVertBuf.put(y1); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
        debugVertBuf.put(x2); debugVertBuf.put(y2); debugVertBuf.put(z); debugVertBuf.put(0f); debugVertBuf.put(0f)
        debugVertBuf.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, debugVertBuf)
        debugVertBuf.position(3)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, debugVertBuf)
        GLES20.glUniform4f(colLoc, r, g, b, a)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        frameDrawCalls++
    }

    private fun renderDebugOverlay(activeVp: FloatArray) {
        val prog = defaultLitProgram
        GLES20.glUseProgram(prog)
        GLES20.glLineWidth(3f)

        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        val colLoc = GLES20.glGetUniformLocation(prog, "uColor")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, activeVp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, identityMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), 0f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)

        for (n in nodes) {
            if (!n.visible || n.layer != "World") continue
            if (!n.collider && !n.isHitbox && !n.isHurtbox) continue

            val gt = getGlobalTransform(n)
            if (n.nodeType == "TileMap" && n.collider) {
                val ts = max(8f, n.tileSize)
                for (cell in n.tiles) {
                    val x0 = gt.gx + cell.gx * ts
                    val y0 = gt.gy + cell.gy * ts
                    val x1 = x0 + ts
                    val y1 = y0 + ts
                    if (!isBoxInView(x0, x1, y0, y1)) continue
                    drawDebugRect(posLoc, uvLoc, colLoc, x0, y0, x1, y1, gt.gz + 6f, 0.2f, 0.95f, 0.45f, 0.5f)
                }
                continue
            }

            val cb = getColliderBounds(n)
            val minX = cb.cx - cb.hw
            val maxX = cb.cx + cb.hw
            val minY = cb.cy - cb.hh
            val maxY = cb.cy + cb.hh
            if (!isBoxInView(minX, maxX, minY, maxY)) continue

            if (n.collider) {
                when (n.slopeType) {
                    "left_up" -> drawDebugSlope(posLoc, uvLoc, colLoc, minX, minY, maxX, maxY, gt.gz + 6f, leftUp = true)
                    "right_up" -> drawDebugSlope(posLoc, uvLoc, colLoc, minX, minY, maxX, maxY, gt.gz + 6f, leftUp = false)
                    else -> {
                        if (n.isTrigger) {
                            drawDebugRect(posLoc, uvLoc, colLoc, minX, minY, maxX, maxY, gt.gz + 6f, 0.1f, 0.9f, 1.0f, 0.9f)
                        } else {
                            drawDebugRect(posLoc, uvLoc, colLoc, minX, minY, maxX, maxY, gt.gz + 6f, 0.2f, 1.0f, 0.35f, 0.9f)
                        }
                    }
                }
            }
            if (n.isHurtbox) {
                drawDebugRect(posLoc, uvLoc, colLoc, minX + 3f, minY + 3f, maxX - 3f, maxY - 3f, gt.gz + 7f, 0.3f, 0.55f, 1.0f, 0.95f)
            }
            if (n.isHitbox) {
                drawDebugRect(posLoc, uvLoc, colLoc, minX - 2f, minY - 2f, maxX + 2f, maxY + 2f, gt.gz + 8f, 1.0f, 0.2f, 0.2f, 0.95f)
            }
        }

        for (ray in debugRays) {
            if (ray.hit) {
                drawDebugLine(posLoc, uvLoc, colLoc, ray.x1, ray.y1, ray.x2, ray.y2, 10f, 1.0f, 0.25f, 0.85f, 0.95f)
            } else {
                drawDebugLine(posLoc, uvLoc, colLoc, ray.x1, ray.y1, ray.x2, ray.y2, 10f, 1.0f, 0.9f, 0.2f, 0.85f)
            }
        }

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun processCombatOverlaps() {
        val hitboxes = nodes.filter { it.visible && it.isHitbox && !pendingRemove.contains(it) }
        if (hitboxes.isEmpty()) return

        for (hb in hitboxes) {
            val cb = getColliderBounds(hb)
            querySpatialCandidates(cb.cx - cb.hw, cb.cx + cb.hw, cb.cy - cb.hh, cb.cy + cb.hh, queryCandidates)
            for (hurt in queryCandidates) {
                if (!hurt.visible || !hurt.isHurtbox || pendingRemove.contains(hurt)) continue
                if (hb === hurt || hb.parent == hurt.name || hurt.parent == hb.name) continue
                if (!canCollideLayer(hb, hurt)) continue
                if (hurt.invulnTimer > 0f) continue
                if (hb.hitCooldownMap.containsKey(hurt.name)) continue
                if (checkOverlap(hb, hurt)) {
                    hb.hitCooldownMap[hurt.name] = 0.25f
                    callLifecycle(hb, "onHit", hurt.jsObj)
                    val scope = hurt.jsObj?.prototype
                    val fn = if (scope != null) ScriptableObject.getProperty(scope, "onHurt") as? Function else null
                    if (fn != null && scope != null) {
                        try {
                            fn.call(rhino, scope, hurt.jsObj, arrayOf(hb.jsObj, hb.damage.toDouble()))
                        } catch (e: Exception) {
                            onConsoleLog("[JS 运行错误 @ ${hurt.name}.onHurt] ${e.message}")
                        }
                    }
                }
            }
        }
    }

    // ==================== P0：TileMap 静态 VBO 合批（整张地图仅 1 次 DrawCall） ====================
    private fun buildTileMapVbo(n: GameNode) {
        if (n.tileVboId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(n.tileVboId), 0)
            n.tileVboId = 0
        }
        n.tileDirty = false
        n.tileLookup.clear()
        if (n.tiles.isEmpty()) {
            n.tileVertexCount = 0
            return
        }

        val ts = max(8f, n.tileSize)
        val hf = max(1, n.hFrames)
        val vf = max(1, n.vFrames)
        val uScale = 1f / hf
        val vScale = 1f / vf

        val floatsPerTile = 6 * 5
        val data = FloatArray(n.tiles.size * floatsPerTile)
        var offset = 0
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (cell in n.tiles) {
            n.tileLookup[packGridKey(cell.gx, cell.gy)] = cell

            val x0 = cell.gx * ts
            val x1 = x0 + ts
            val y0 = cell.gy * ts
            val y1 = y0 + ts
            if (x0 < minX) minX = x0
            if (x1 > maxX) maxX = x1
            if (y0 < minY) minY = y0
            if (y1 > maxY) maxY = y1

            val fi = cell.frame.coerceIn(0, hf * vf - 1)
            val u0 = (fi % hf) * uScale
            val v0 = (fi / hf) * vScale
            val u1 = u0 + uScale
            val v1 = v0 + vScale

            // Triangle 1: TL, TR, BL
            data[offset++] = x0; data[offset++] = y0; data[offset++] = 0f; data[offset++] = u0; data[offset++] = v0
            data[offset++] = x1; data[offset++] = y0; data[offset++] = 0f; data[offset++] = u1; data[offset++] = v0
            data[offset++] = x0; data[offset++] = y1; data[offset++] = 0f; data[offset++] = u0; data[offset++] = v1
            // Triangle 2: TR, BR, BL
            data[offset++] = x1; data[offset++] = y0; data[offset++] = 0f; data[offset++] = u1; data[offset++] = v0
            data[offset++] = x1; data[offset++] = y1; data[offset++] = 0f; data[offset++] = u1; data[offset++] = v1
            data[offset++] = x0; data[offset++] = y1; data[offset++] = 0f; data[offset++] = u0; data[offset++] = v1
        }

        n.tileMinX = minX
        n.tileMaxX = maxX
        n.tileMinY = minY
        n.tileMaxY = maxY
        n.tileVertexCount = n.tiles.size * 6

        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data)
        buf.position(0)

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        n.tileVboId = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, n.tileVboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun renderTileMap(
        n: GameNode,
        activeVp: FloatArray,
        isWorld: Boolean,
        lightCount: Int,
        lightPos: FloatArray,
        lightCol: FloatArray
    ) {
        if (n.tiles.isEmpty()) return
        if (n.tileDirty || n.tileVboId == 0) {
            buildTileMapVbo(n)
        }
        if (n.tileVboId == 0 || n.tileVertexCount == 0) return

        val gt = getGlobalTransform(n)
        if (isWorld && !isBoxInView(gt.gx + n.tileMinX, gt.gx + n.tileMaxX, gt.gy + n.tileMinY, gt.gy + n.tileMaxY)) {
            frameCulled++
            return
        }

        val prog = getProgramForNode(n)
        GLES20.glUseProgram(prog)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, n.tileVboId)
        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, 12)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, gt.gx, gt.gy, gt.gz)
        if (gt.gRot != 0f) Matrix.rotateM(modelMatrix, 0, gt.gRot, 0f, 0f, 1f)
        if (gt.gSx != 1f || gt.gSy != 1f) Matrix.scaleM(modelMatrix, 0, gt.gSx, gt.gSy, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, activeVp, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), n.r, n.g, n.b, n.a)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTime"), engineTime)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), 0f)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), if (isWorld) 1 else 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uAmbient"), ambientR, ambientG, ambientB)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLightCount"), lightCount)
        if (lightCount > 0) {
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightPos"), lightCount, lightPos, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightColor"), lightCount, lightCol, 0)
        }

        val texId = if (n.texture.isNotEmpty()) getImageTexture(n.texture) else whiteTexId
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n.tileVertexCount)
        frameDrawCalls++

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun renderDynamicShadow(caster: GameNode, lights: List<GameNode>, activeVp: FloatArray) {
        val cg = getGlobalTransform(caster)
        val cw = caster.width * abs(cg.gSx)
        val ch = caster.height * abs(cg.gSy)
        val prog = defaultLitProgram
        GLES20.glUseProgram(prog)

        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, quadBuffer)
        quadBuffer.position(3)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, quadBuffer)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), 0f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)

        for (light in lights) {
            val lg = getGlobalTransform(light)
            val dx = cg.gx - lg.gx
            val dy = cg.gy - lg.gy
            val dist = hypot(dx, dy)
            if (dist < 1f || dist > light.lightRadius) continue
            val nx = dx / dist
            val ny = dy / dist
            val atten = (1f - dist / light.lightRadius).coerceIn(0f, 1f)
            val sLen = max(cw, ch) * 1.15f * atten
            val sx = cg.gx + nx * (sLen * 0.5f + cw * 0.35f)
            val sy = cg.gy + ny * (sLen * 0.5f + ch * 0.35f)
            val angleDeg = Math.toDegrees(atan2(ny.toDouble(), nx.toDouble())).toFloat() - 90f

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, sx, sy, cg.gz - 0.5f)
            Matrix.rotateM(modelMatrix, 0, angleDeg, 0f, 0f, 1f)
            Matrix.scaleM(modelMatrix, 0, cw * 0.9f, sLen, 1f)
            Matrix.multiplyMM(mvpMatrix, 0, activeVp, 0, modelMatrix, 0)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), 0.02f, 0.03f, 0.06f, 0.52f * atten)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            frameDrawCalls++
        }

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun spawnSingleParticle(n: GameNode, gx: Float, gy: Float) {
        val slot = n.particles.firstOrNull { !it.active } ?: n.particles.first()
        val angle = Math.random() * Math.PI * 2.0
        val spd = n.particleSpeed * (0.35 + Math.random() * 0.85).toFloat()
        slot.active = true
        slot.x = gx + ((Math.random() - 0.5) * n.width * 0.4).toFloat()
        slot.y = gy + ((Math.random() - 0.5) * n.height * 0.4).toFloat()
        slot.vx = (cos(angle) * spd).toFloat()
        slot.vy = (sin(angle) * spd).toFloat()
        slot.size = n.particleSize * (0.6 + Math.random() * 0.8).toFloat()
        slot.maxLife = max(0.15f, n.particleLife * (0.7 + Math.random() * 0.6).toFloat())
        slot.life = slot.maxLife
        slot.alpha = n.a
    }

    private fun updateParticleEmitter(n: GameNode, dt: Float) {
        val gt = getGlobalTransform(n)
        if (n.emitRate > 0f) {
            n.emitAccum += dt * n.emitRate
            while (n.emitAccum >= 1f) {
                n.emitAccum -= 1f
                spawnSingleParticle(n, gt.gx, gt.gy)
            }
        }
        for (p in n.particles) {
            if (!p.active) continue
            p.life -= dt
            if (p.life <= 0f) {
                p.active = false
                continue
            }
            p.vy += n.particleGravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
    }

    private fun renderParticles(n: GameNode, activeVp: FloatArray, isWorld: Boolean) {
        val gt = getGlobalTransform(n)
        val prog = defaultLitProgram
        GLES20.glUseProgram(prog)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, quadBuffer)
        quadBuffer.position(3)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, quadBuffer)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), 0f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f)

        val texId = if (n.texture.isNotEmpty()) getImageTexture(n.texture) else whiteTexId
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)

        for (p in n.particles) {
            if (!p.active) continue
            if (isWorld && !isBoxInView(p.x - p.size, p.x + p.size, p.y - p.size, p.y + p.size)) {
                frameCulled++
                continue
            }
            val ratio = (p.life / p.maxLife).coerceIn(0f, 1f)
            val curSize = p.size * (0.25f + 0.75f * ratio)
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, p.x, p.y, gt.gz + 4f)
            if (isWorld && camMode == "2.5D") {
                Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
            }
            Matrix.scaleM(modelMatrix, 0, curSize, curSize, 1f)
            Matrix.multiplyMM(mvpMatrix, 0, activeVp, 0, modelMatrix, 0)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), n.r, n.g, n.b, p.alpha * ratio)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            frameDrawCalls++
        }

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun updateTweens(dt: Float) {
        if (activeTweens.isEmpty()) return
        val iter = activeTweens.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            t.elapsed += dt
            val rawP = (t.elapsed / max(0.01f, t.duration)).coerceIn(0f, 1f)
            val easedP = when (t.ease) {
                "easeOut" -> 1f - (1f - rawP).pow(2)
                "easeInOut" -> if (rawP < 0.5f) 2f * rawP * rawP else 1f - (-2f * rawP + 2f).pow(2) / 2f
                else -> rawP
            }
            val curVal = t.startVal + (t.targetVal - t.startVal) * easedP
            applyTweenProperty(t.node, t.prop, curVal)
            if (rawP >= 1f) iter.remove()
        }
    }

    private fun applyTweenProperty(n: GameNode, prop: String, v: Float) {
        when (prop) {
            "x" -> n.x = v
            "y" -> n.y = v
            "z" -> n.z = v
            "scaleX" -> n.scaleX = v
            "scaleY" -> n.scaleY = v
            "rotation" -> n.rotation = v
            "a" -> n.a = v.coerceIn(0f, 1f)
            "lightIntensity" -> n.lightIntensity = v
        }
    }

    private fun readTweenProperty(n: GameNode, prop: String): Float {
        return when (prop) {
            "x" -> n.x
            "y" -> n.y
            "z" -> n.z
            "scaleX" -> n.scaleX
            "scaleY" -> n.scaleY
            "rotation" -> n.rotation
            "a" -> n.a
            "lightIntensity" -> n.lightIntensity
            else -> 0f
        }
    }

    private fun unprojectCornerZ0(ndcX: Float, ndcY: Float, outXY: FloatArray): Boolean {
        val nearPt = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val farPt = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val nearW = FloatArray(4)
        val farW = FloatArray(4)
        Matrix.multiplyMV(nearW, 0, invVpMatrix, 0, nearPt, 0)
        Matrix.multiplyMV(farW, 0, invVpMatrix, 0, farPt, 0)
        if (abs(nearW[3]) < 1e-5f || abs(farW[3]) < 1e-5f) return false
        for (i in 0..2) {
            nearW[i] /= nearW[3]
            farW[i] /= farW[3]
        }
        val dz = farW[2] - nearW[2]
        if (abs(dz) < 1e-5f) return false
        val t = -nearW[2] / dz
        outXY[0] = nearW[0] + t * (farW[0] - nearW[0])
        outXY[1] = nearW[1] + t * (farW[1] - nearW[1])
        return true
    }

    private fun updateCameraMatrices(dt: Float) {
        camTarget?.let { target ->
            val gt = getGlobalTransform(target)
            val lookX = if (target.scaleX < 0f) -camLookAheadX else camLookAheadX
            val desiredX = gt.gx + lookX
            val desiredY = gt.gy + camLookAheadY

            val dx = desiredX - camX
            val dy = desiredY - camY
            if (abs(dx) > camDeadzoneX) {
                val moveX = dx - sign(dx) * camDeadzoneX
                camX += moveX * min(1f, camSmoothSpeed * dt)
            }
            if (abs(dy) > camDeadzoneY) {
                val moveY = dy - sign(dy) * camDeadzoneY
                camY += moveY * min(1f, camSmoothSpeed * dt)
            }
        }

        if (camBoundsEnabled) {
            camX = camX.coerceIn(camMinX, max(camMinX, camMaxX))
            camY = camY.coerceIn(camMinY, max(camMinY, camMaxY))
        }

        var sx = 0f
        var sy = 0f
        if (shakeTimer > 0f) {
            shakeTimer -= dt
            sx = ((Math.random() - 0.5) * 2.0 * shakeIntensity).toFloat()
            sy = ((Math.random() - 0.5) * 2.0 * shakeIntensity).toFloat()
        }

        val aspect = surfaceW.toFloat() / surfaceH.toFloat()
        if (camMode == "2.5D") {
            Matrix.perspectiveM(projMatrix, 0, camFov, aspect, 10f, 10000f)
            val rad = Math.toRadians(camPitch.toDouble())
            val eyeY = camY + sin(rad).toFloat() * camDistance + sy
            val eyeZ = cos(rad).toFloat() * camDistance
            Matrix.setLookAtM(
                viewMatrix, 0,
                camX + sx, eyeY, eyeZ,
                camX + sx, camY + sy, 0f,
                0f, -1f, 0f
            )
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)

            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            val pt = FloatArray(2)
            val corners = arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
            var valid = 0
            for ((cx, cy) in corners) {
                if (unprojectCornerZ0(cx, cy, pt)) {
                    if (pt[0] < minX) minX = pt[0]
                    if (pt[0] > maxX) maxX = pt[0]
                    if (pt[1] < minY) minY = pt[1]
                    if (pt[1] > maxY) maxY = pt[1]
                    valid++
                }
            }
            if (valid == 4) {
                viewMinX = minX - 400f
                viewMaxX = maxX + 400f
                viewMinY = minY - 400f
                viewMaxY = maxY + 400f
            } else {
                viewMinX = -999999f; viewMaxX = 999999f; viewMinY = -999999f; viewMaxY = 999999f
            }
        } else {
            val halfH = 960f
            val halfW = halfH * aspect
            Matrix.orthoM(projMatrix, 0, -halfW, halfW, halfH, -halfH, -2000f, 2000f)
            Matrix.setLookAtM(
                viewMatrix, 0,
                camX + sx, camY + sy, 500f,
                camX + sx, camY + sy, 0f,
                0f, 1f, 0f
            )
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)

            viewMinX = camX + sx - halfW - 250f
            viewMaxX = camX + sx + halfW + 250f
            viewMinY = camY + sy - halfH - 250f
            viewMaxY = camY + sy + halfH + 250f
        }
    }

    private fun updateWorldTouchFromScreen() {
        val ndcX = (touchScreenX / 1080f) * 2f - 1f
        val ndcY = 1f - (touchScreenY / 1920f) * 2f
        val pt = FloatArray(2)
        if (unprojectCornerZ0(ndcX, ndcY, pt)) {
            touchWorldX = pt[0]
            touchWorldY = pt[1]
        }
    }

    private fun renderNode(
        n: GameNode,
        activeVp: FloatArray,
        isWorld: Boolean,
        lightCount: Int,
        lightPos: FloatArray,
        lightCol: FloatArray
    ) {
        val gt = getGlobalTransform(n)
        val w = n.width * gt.gSx
        val h = n.height * gt.gSy

        if (isWorld) {
            val boundHalf = max(abs(w), abs(h)) * 0.75f + abs(gt.gz)
            if (!isBoxInView(gt.gx - boundHalf, gt.gx + boundHalf, gt.gy - boundHalf, gt.gy + boundHalf)) {
                frameCulled++
                return
            }
        }

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, gt.gx, gt.gy, gt.gz)

        if (isWorld && camMode == "2.5D" && n.billboard) {
            Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
        } else if (isWorld && n.tiltX != 0f) {
            Matrix.rotateM(modelMatrix, 0, n.tiltX, 1f, 0f, 0f)
        }

        if (gt.gRot != 0f) {
            Matrix.rotateM(modelMatrix, 0, gt.gRot, 0f, 0f, 1f)
        }
        Matrix.scaleM(modelMatrix, 0, w, h, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, activeVp, 0, modelMatrix, 0)

        val prog = getProgramForNode(n)
        GLES20.glUseProgram(prog)

        val activeBuf = when (n.slopeType) {
            "left_up" -> slopeLeftUpBuffer
            "right_up" -> slopeRightUpBuffer
            else -> quadBuffer
        }
        val vertCount = if (n.slopeType == "left_up" || n.slopeType == "right_up") 3 else 4
        val drawMode = if (vertCount == 3) GLES20.GL_TRIANGLES else GLES20.GL_TRIANGLE_STRIP

        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        activeBuf.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, activeBuf)
        activeBuf.position(3)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, activeBuf)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), n.r, n.g, n.b, n.a)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTime"), engineTime)
        val flashVal = if (n.flashTimer > 0f) (n.flashTimer * 8f).coerceIn(0f, 1f) else 0f
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), flashVal)

        val hf = max(1, n.hFrames)
        val vf = max(1, n.vFrames)
        val fi = n.currentFrame.coerceIn(0, hf * vf - 1)
        val uScale = 1f / hf
        val vScale = 1f / vf
        val uOffset = (fi % hf) * uScale
        val vOffset = (fi / hf) * vScale
        if (isWorld && camMode == "2.5D" && n.nodeType == "Label2D") {
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 1f, vOffset, -1f, vScale)
        } else {
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), uOffset, vOffset, uScale, vScale)
        }

        val litFlag = if (isWorld) 1 else 0
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), litFlag)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uAmbient"), ambientR, ambientG, ambientB)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLightCount"), lightCount)
        if (lightCount > 0) {
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightPos"), lightCount, lightPos, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightColor"), lightCount, lightCol, 0)
        }

        val texId = when {
            n.nodeType == "Label2D" -> getTextTexture(n.text, n.fontSize)
            n.texture.isNotEmpty() -> getImageTexture(n.texture)
            else -> whiteTexId
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)

        GLES20.glDrawArrays(drawMode, 0, vertCount)
        frameDrawCalls++

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun getProgramForNode(n: GameNode): Int {
        if (n.shader.isBlank()) return defaultLitProgram
        if (n.customProgram != 0) return n.customProgram
        val prog = buildShaderProgram(VERTEX_SHADER, n.shader)
        n.customProgram = if (prog != 0) prog else defaultLitProgram
        return n.customProgram
    }

    private fun updateSpriteAnimation(n: GameNode, dt: Float) {
        if (!n.animPlaying || n.animFps <= 0f) return
        n.animTimer += dt
        val frameDur = 1f / n.animFps
        while (n.animTimer >= frameDur) {
            n.animTimer -= frameDur
            n.currentFrame++
            val clip = n.clips[n.currentClipName]
            val evName = clip?.events?.get(n.currentFrame)
            if (!evName.isNullOrEmpty()) {
                val scope = n.jsObj?.prototype
                val fn = if (scope != null) ScriptableObject.getProperty(scope, "onFrameEvent") as? Function else null
                if (fn != null && scope != null) {
                    try {
                        fn.call(rhino, scope, n.jsObj, arrayOf(n.currentClipName, evName))
                    } catch (_: Exception) {}
                }
            }
            if (n.currentFrame > n.animEnd) {
                if (n.animLoop) {
                    n.currentFrame = n.animStart
                } else {
                    n.currentFrame = n.animEnd
                    n.animPlaying = false
                    val finishedClip = n.currentClipName
                    n.currentClipName = ""
                    if (finishedClip.isNotEmpty()) {
                        callLifecycle(n, "onAnimFinished", finishedClip)
                    }
                }
            }
        }
    }

    // ==================== 音频播放引擎 ====================
    private fun playBGMFile(fileName: String, loop: Boolean, volume: Float) {
        try {
            bgmPlayer?.stop()
            bgmPlayer?.release()
            bgmPlayer = null
            val file = File(File(projectDir, "audio"), fileName)
            if (!file.exists()) {
                onConsoleLog("[AUDIO 警告] 找不到 BGM 文件: $fileName")
                return
            }
            bgmPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = loop
                setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
                prepare()
                start()
            }
        } catch (e: Exception) {
            onConsoleLog("[AUDIO BGM 错误] ${e.message}")
        }
    }

    private fun stopBGMFile() {
        try {
            bgmPlayer?.stop()
            bgmPlayer?.release()
            bgmPlayer = null
        } catch (_: Exception) {}
    }

    private fun playSFXFile(fileName: String, volume: Float) {
        try {
            val vol = volume.coerceIn(0f, 1f)
            val cachedId = sfxCache[fileName]
            if (cachedId != null) {
                soundPool.play(cachedId, vol, vol, 1, 0, 1.0f)
                return
            }
            val file = File(File(projectDir, "audio"), fileName)
            if (!file.exists()) {
                onConsoleLog("[AUDIO 警告] 找不到音效文件: $fileName")
                return
            }
            val sid = soundPool.load(file.absolutePath, 1)
            sfxCache[fileName] = sid
            soundPool.setOnLoadCompleteListener { sp, sampleId, status ->
                if (status == 0 && sampleId == sid) {
                    sp.play(sampleId, vol, vol, 1, 0, 1.0f)
                }
            }
        } catch (e: Exception) {
            onConsoleLog("[AUDIO SFX 错误] ${e.message}")
        }
    }

    // ==================== P1：空间加速射线检测 (支持实体包围盒与 TileMap O(1) 范围查询) ====================
    private fun performRaycast(x1: Float, y1: Float, x2: Float, y2: Float, ignoreTag: String, mask: Int): Scriptable {
        var closestT = 1.0f
        var hitNode: GameNode? = null

        val dx = x2 - x1
        val dy = y2 - y1
        val rMinX = min(x1, x2)
        val rMaxX = max(x1, x2)
        val rMinY = min(y1, y2)
        val rMaxY = max(y1, y2)

        querySpatialCandidates(rMinX, rMaxX, rMinY, rMaxY, queryCandidates)

        for (other in queryCandidates) {
            if (!other.visible || !other.collider || other.isTrigger || other.layer != "World") continue
            if (ignoreTag.isNotEmpty() && other.tag == ignoreTag) continue
            if ((mask and other.colLayer) == 0) continue

            if (other.nodeType == "TileMap") {
                val gb = getGlobalTransform(other)
                val ts = max(8f, other.tileSize)
                val gx0 = floor((rMinX - gb.gx) / ts).toInt()
                val gx1 = floor((rMaxX - gb.gx) / ts).toInt()
                val gy0 = floor((rMinY - gb.gy) / ts).toInt()
                val gy1 = floor((rMaxY - gb.gy) / ts).toInt()
                for (gx in gx0..gx1) {
                    for (gy in gy0..gy1) {
                        if (!other.tileLookup.containsKey(packGridKey(gx, gy))) continue
                        val minX = gb.gx + gx * ts
                        val minY = gb.gy + gy * ts
                        val t = intersectSegmentAABB(x1, y1, dx, dy, minX, minY, minX + ts, minY + ts)
                        if (t != null && t < closestT) {
                            closestT = t
                            hitNode = other
                        }
                    }
                }
            } else {
                val cb = getColliderBounds(other)
                val t = intersectSegmentAABB(x1, y1, dx, dy, cb.cx - cb.hw, cb.cy - cb.hh, cb.cx + cb.hw, cb.cy + cb.hh)
                if (t != null && t < closestT) {
                    closestT = t
                    hitNode = other
                }
            }
        }

        val res = rhino.newObject(globalScope)
        if (hitNode != null) {
            val hx = x1 + dx * closestT
            val hy = y1 + dy * closestT
            val dist = hypot(hx - x1, hy - y1)
            if (debugDrawEnabled) debugRays.add(DebugRay(x1, y1, hx, hy, hit = true))
            ScriptableObject.putProperty(res, "hit", true)
            ScriptableObject.putProperty(res, "x", hx.toDouble())
            ScriptableObject.putProperty(res, "y", hy.toDouble())
            ScriptableObject.putProperty(res, "distance", dist.toDouble())
            ScriptableObject.putProperty(res, "node", hitNode.jsObj)
        } else {
            if (debugDrawEnabled) debugRays.add(DebugRay(x1, y1, x2, y2, hit = false))
            ScriptableObject.putProperty(res, "hit", false)
            ScriptableObject.putProperty(res, "x", x2.toDouble())
            ScriptableObject.putProperty(res, "y", y2.toDouble())
            ScriptableObject.putProperty(res, "distance", hypot(dx, dy).toDouble())
            ScriptableObject.putProperty(res, "node", null)
        }
        return res
    }

    private fun intersectSegmentAABB(
        x1: Float, y1: Float, dx: Float, dy: Float,
        minX: Float, minY: Float, maxX: Float, maxY: Float
    ): Float? {
        var t0 = 0f
        var t1 = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x1 - minX, maxX - x1, y1 - minY, maxY - y1)
        for (i in 0..3) {
            if (abs(p[i]) < 1e-6f) {
                if (q[i] < 0f) return null
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0f) {
                    if (r > t1) return null
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return null
                    if (r < t1) t1 = r
                }
            }
        }
        return if (t0 <= t1) t0 else null
    }

    // ==================== 本地磁盘永久存档 ====================
    private fun saveDiskSlot(slotName: String, dataObj: Any?): Boolean {
        return try {
            val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
            val savesDir = File(projectDir, "saves").apply { mkdirs() }
            val jsonStr = when (dataObj) {
                is String -> dataObj
                null -> "{}"
                else -> RhinoContext.toString(NativeJSON.stringify(rhino, globalScope, dataObj, null, null))
            }
            File(savesDir, "$safeName.json").writeText(jsonStr)
            true
        } catch (e: Exception) {
            onConsoleLog("[SAVE 磁盘存档失败] ${e.message}")
            false
        }
    }

    private fun loadDiskSlot(slotName: String): Any? {
        return try {
            val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
            val file = File(File(projectDir, "saves"), "$safeName.json")
            if (!file.exists()) return null
            val raw = file.readText()
            val reviver = object : Callable {
                override fun call(
                    cx: RhinoContext?,
                    scope: Scriptable?,
                    thisObj: Scriptable?,
                    args: Array<out Any?>
                ): Any? = args.getOrNull(1)
            }
            NativeJSON.parse(rhino, globalScope, raw, reviver)
        } catch (e: Exception) {
            onConsoleLog("[LOAD 磁盘读档失败] ${e.message}")
            null
        }
    }

    private fun deleteDiskSlot(slotName: String): Boolean {
        return try {
            val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
            val file = File(File(projectDir, "saves"), "$safeName.json")
            if (file.exists()) file.delete() else false
        } catch (_: Exception) {
            false
        }
    }

    // ==================== Rhino JS SDK 绑定 ====================
    private fun initRhinoVM() {
        rhino = RhinoContext.enter()
        rhino.optimizationLevel = -1
        globalScope = rhino.initStandardObjects()

        val engineApi = rhino.newObject(globalScope)
        ScriptableObject.putProperty(engineApi, "log", jsFunc { args ->
            val msg = args.joinToString(" ") { RhinoContext.toString(it) }
            onConsoleLog("[LOG] $msg")
            null
        })
        ScriptableObject.putProperty(engineApi, "setDebugDraw", jsFunc { args ->
            debugDrawEnabled = (args.getOrNull(0) as? Boolean) ?: false
            null
        })
        ScriptableObject.putProperty(engineApi, "isDebugDraw", jsFunc { debugDrawEnabled })
        ScriptableObject.putProperty(engineApi, "getStats", jsFunc {
            val obj = rhino.newObject(globalScope)
            ScriptableObject.putProperty(obj, "fps", smoothedFps.toDouble())
            ScriptableObject.putProperty(obj, "drawCalls", lastFrameDrawCalls)
            ScriptableObject.putProperty(obj, "culled", lastFrameCulled)
            ScriptableObject.putProperty(obj, "nodes", nodes.size)
            ScriptableObject.putProperty(obj, "spatialCells", spatialGrid.size)
            ScriptableObject.putProperty(obj, "textures", textureCache.size)
            ScriptableObject.putProperty(obj, "textTextures", textTextureCache.size)
            obj
        })
        ScriptableObject.putProperty(engineApi, "loadScene", jsFunc { args ->
            val sName = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (sName.isNotEmpty()) {
                pendingSceneLoad = if (sName.endsWith(".scene")) sName else "$sName.scene"
            }
            null
        })
        ScriptableObject.putProperty(engineApi, "setGlobal", jsFunc { args ->
            val k = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (k.isNotEmpty()) globalDataStore[k] = args.getOrNull(1)
            null
        })
        ScriptableObject.putProperty(engineApi, "getGlobal", jsFunc { args ->
            val k = RhinoContext.toString(args.getOrNull(0) ?: "")
            globalDataStore[k] ?: args.getOrNull(1)
        })
        ScriptableObject.putProperty(engineApi, "saveDisk", jsFunc { args ->
            val slot = RhinoContext.toString(args.getOrNull(0) ?: "slot_1")
            saveDiskSlot(slot, args.getOrNull(1))
        })
        ScriptableObject.putProperty(engineApi, "loadDisk", jsFunc { args ->
            val slot = RhinoContext.toString(args.getOrNull(0) ?: "slot_1")
            loadDiskSlot(slot)
        })
        ScriptableObject.putProperty(engineApi, "deleteDisk", jsFunc { args ->
            val slot = RhinoContext.toString(args.getOrNull(0) ?: "slot_1")
            deleteDiskSlot(slot)
        })
        ScriptableObject.putProperty(engineApi, "raycast", jsFunc { args ->
            val x1 = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
            val y1 = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val x2 = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
            val y2 = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f
            val ignoreTag = args.getOrNull(4)?.takeIf { it !== Scriptable.NOT_FOUND }?.toString() ?: ""
            val mask = (args.getOrNull(5) as? Number)?.toInt() ?: 0xFFFF
            performRaycast(x1, y1, x2, y2, ignoreTag, mask)
        })
        ScriptableObject.putProperty(engineApi, "hitstop", jsFunc { args ->
            val sec = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.05f
            hitstopTimer = max(hitstopTimer, sec.coerceIn(0f, 0.5f))
            null
        })
        ScriptableObject.putProperty(engineApi, "setTimeScale", jsFunc { args ->
            timeScale = ((args.getOrNull(0) as? Number)?.toFloat() ?: 1f).coerceIn(0.05f, 4f)
            null
        })
        ScriptableObject.putProperty(engineApi, "setCameraMode", jsFunc { args ->
            camMode = RhinoContext.toString(args.getOrNull(0) ?: "2.5D")
            null
        })
        ScriptableObject.putProperty(engineApi, "setCameraTilt", jsFunc { args ->
            camPitch = (args.getOrNull(0) as? Number)?.toFloat() ?: camPitch
            camDistance = (args.getOrNull(1) as? Number)?.toFloat() ?: camDistance
            camFov = (args.getOrNull(2) as? Number)?.toFloat() ?: camFov
            null
        })
        ScriptableObject.putProperty(engineApi, "setCameraTarget", jsFunc { args ->
            val targetObj = args.getOrNull(0) as? Scriptable
            camTarget = (targetObj as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === targetObj }
            null
        })
        ScriptableObject.putProperty(engineApi, "setCameraDeadzone", jsFunc { args ->
            camDeadzoneX = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
            camDeadzoneY = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
            null
        })
        ScriptableObject.putProperty(engineApi, "setCameraLookAhead", jsFunc { args ->
            camLookAheadX = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
            camLookAheadY = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
            null
        })
        ScriptableObject.putProperty(engineApi, "setCameraBounds", jsFunc { args ->
            camBoundsEnabled = true
            camMinX = (args.getOrNull(0) as? Number)?.toFloat() ?: -99999f
            camMinY = (args.getOrNull(1) as? Number)?.toFloat() ?: -99999f
            camMaxX = (args.getOrNull(2) as? Number)?.toFloat() ?: 99999f
            camMaxY = (args.getOrNull(3) as? Number)?.toFloat() ?: 99999f
            null
        })
        ScriptableObject.putProperty(engineApi, "clearCameraBounds", jsFunc {
            camBoundsEnabled = false
            null
        })
        ScriptableObject.putProperty(engineApi, "getCamera", jsFunc {
            val obj = rhino.newObject(globalScope)
            ScriptableObject.putProperty(obj, "x", camX.toDouble())
            ScriptableObject.putProperty(obj, "y", camY.toDouble())
            ScriptableObject.putProperty(obj, "pitch", camPitch.toDouble())
            ScriptableObject.putProperty(obj, "mode", camMode)
            obj
        })
        ScriptableObject.putProperty(engineApi, "setAmbientLight", jsFunc { args ->
            ambientR = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.52f
            ambientG = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.56f
            ambientB = (args.getOrNull(2) as? Number)?.toFloat() ?: 0.68f
            null
        })
        ScriptableObject.putProperty(engineApi, "shake", jsFunc { args ->
            shakeIntensity = (args.getOrNull(0) as? Number)?.toFloat() ?: 12f
            shakeTimer = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.15f
            null
        })
        ScriptableObject.putProperty(engineApi, "tween", jsFunc { args ->
            val targetObj = args.getOrNull(0) as? Scriptable ?: return@jsFunc null
            val prop = RhinoContext.toString(args.getOrNull(1) ?: "")
            val toVal = (args.getOrNull(2) as? Number)?.toFloat() ?: return@jsFunc null
            val dur = (args.getOrNull(3) as? Number)?.toFloat() ?: 0.25f
            val ease = RhinoContext.toString(args.getOrNull(4) ?: "easeOut")
            val n = (targetObj as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === targetObj } ?: return@jsFunc null
            val startVal = readTweenProperty(n, prop)
            activeTweens.removeAll { it.node === n && it.prop == prop }
            activeTweens.add(TweenTask(n, prop, startVal, toVal, dur, ease))
            null
        })
        ScriptableObject.putProperty(engineApi, "playTone", jsFunc { args ->
            val hz = (args.getOrNull(0) as? Number)?.toInt() ?: 440
            val ms = (args.getOrNull(1) as? Number)?.toInt() ?: 80
            playToneAsync(hz, ms)
            null
        })
        ScriptableObject.putProperty(engineApi, "playBGM", jsFunc { args ->
            val fileName = RhinoContext.toString(args.getOrNull(0) ?: "")
            val loop = (args.getOrNull(1) as? Boolean) ?: true
            val vol = (args.getOrNull(2) as? Number)?.toFloat() ?: 0.8f
            if (fileName.isNotEmpty()) playBGMFile(fileName, loop, vol)
            null
        })
        ScriptableObject.putProperty(engineApi, "stopBGM", jsFunc {
            stopBGMFile()
            null
        })
        ScriptableObject.putProperty(engineApi, "playSFX", jsFunc { args ->
            val fileName = RhinoContext.toString(args.getOrNull(0) ?: "")
            val vol = (args.getOrNull(1) as? Number)?.toFloat() ?: 1.0f
            if (fileName.isNotEmpty()) playSFXFile(fileName, vol)
            null
        })
        ScriptableObject.putProperty(engineApi, "moveAndSlide", jsFunc { args ->
            val selfObj = args.getOrNull(0) as? Scriptable ?: return@jsFunc null
            val dt = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.016f
            val n = (selfObj as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === selfObj } ?: return@jsFunc null
            moveAndSlideNode(n, dt)
            null
        })
        ScriptableObject.putProperty(engineApi, "distance", jsFunc { args ->
            val a = (args.getOrNull(0) as? GameNodeJsProxy)?.n
                ?: nodes.find { it.jsObj === (args.getOrNull(0) as? Scriptable) }
            val b = (args.getOrNull(1) as? GameNodeJsProxy)?.n
                ?: nodes.find { it.jsObj === (args.getOrNull(1) as? Scriptable) }
            if (a != null && b != null) {
                val ga = getGlobalTransform(a)
                val gb = getGlobalTransform(b)
                hypot(ga.gx - gb.gx, ga.gy - gb.gy).toDouble()
            } else 999999.0
        })
        ScriptableObject.putProperty(engineApi, "angleTo", jsFunc { args ->
            val a = (args.getOrNull(0) as? GameNodeJsProxy)?.n
                ?: nodes.find { it.jsObj === (args.getOrNull(0) as? Scriptable) }
            val b = (args.getOrNull(1) as? GameNodeJsProxy)?.n
                ?: nodes.find { it.jsObj === (args.getOrNull(1) as? Scriptable) }
            if (a != null && b != null) {
                val ga = getGlobalTransform(a)
                val gb = getGlobalTransform(b)
                Math.toDegrees(atan2((gb.gy - ga.gy).toDouble(), (gb.gx - ga.gx).toDouble()))
            } else 0.0
        })
        ScriptableObject.putProperty(engineApi, "spawn", jsFunc { args ->
            val cfg = args.getOrNull(0) as? Scriptable ?: return@jsFunc null
            val parentName = RhinoContext.toString(args.getOrNull(1) ?: "")
            val rawLayer = getJsStr(cfg, "layer", "World")
            val spawned = GameNode(
                name = getJsStr(cfg, "name", "Spawn_${System.nanoTime() % 10000}"),
                nodeType = getJsStr(cfg, "nodeType", "Sprite2D"),
                layer = if (rawLayer.startsWith("UI", ignoreCase = true)) "UI" else "World",
                parent = parentName,
                tag = getJsStr(cfg, "tag", "default"),
                x = getJsFloat(cfg, "x", 0f),
                y = getJsFloat(cfg, "y", 0f),
                z = getJsFloat(cfg, "z", 0f),
                width = getJsFloat(cfg, "width", 64f),
                height = getJsFloat(cfg, "height", 64f),
                tiltX = getJsFloat(cfg, "tiltX", 0f),
                billboard = getJsBool(cfg, "billboard", false),
                r = getJsFloat(cfg, "r", 1f),
                g = getJsFloat(cfg, "g", 1f),
                b = getJsFloat(cfg, "b", 1f),
                a = getJsFloat(cfg, "a", 1f),
                collider = getJsBool(cfg, "collider", false),
                isTrigger = getJsBool(cfg, "isTrigger", false),
                oneWay = getJsBool(cfg, "oneWay", false),
                slopeType = getJsStr(cfg, "slopeType", "none"),
                colOffsetX = getJsFloat(cfg, "colOffsetX", 0f),
                colOffsetY = getJsFloat(cfg, "colOffsetY", 0f),
                colWidth = getJsFloat(cfg, "colWidth", 0f),
                colHeight = getJsFloat(cfg, "colHeight", 0f),
                colLayer = getJsInt(cfg, "colLayer", 1),
                colMask = getJsInt(cfg, "colMask", 0xFFFF),
                bodyMode = getJsStr(cfg, "bodyMode", "kinematic"),
                isHitbox = getJsBool(cfg, "isHitbox", false),
                isHurtbox = getJsBool(cfg, "isHurtbox", false),
                damage = getJsFloat(cfg, "damage", 10f),
                shadowCaster = getJsBool(cfg, "shadowCaster", false),
                lightRadius = getJsFloat(cfg, "lightRadius", 400f),
                lightIntensity = getJsFloat(cfg, "lightIntensity", 1.5f),
                script = getJsStr(cfg, "script", "")
            )
            pendingAdd.add(spawned)
            bindNodeScript(spawned)
            spawned.jsObj
        })
        ScriptableObject.putProperty(engineApi, "instantiate", jsFunc { args ->
            val sceneFile = RhinoContext.toString(args.getOrNull(0) ?: "")
            val sx = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val sy = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
            val sz = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f
            val file = File(File(projectDir, "scenes"), sceneFile)
            if (file.exists()) {
                val loaded = mutableListOf<GameNode>()
                parseSceneJson(file.readText(), loaded)
                loaded.forEach {
                    if (it.parent.isEmpty()) { it.x += sx; it.y += sy; it.z += sz }
                    pendingAdd.add(it)
                    bindNodeScript(it)
                }
                loaded.firstOrNull()?.jsObj
            } else null
        })
        ScriptableObject.putProperty(engineApi, "destroy", jsFunc { args ->
            val targetObj = args.getOrNull(0) as? Scriptable
            val target = (targetObj as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === targetObj }
            target?.let { pendingRemove.add(it) }
            null
        })
        ScriptableObject.putProperty(engineApi, "findNode", jsFunc { args ->
            val name = RhinoContext.toString(args.getOrNull(0) ?: "")
            nodes.find { it.name == name }?.jsObj
        })
        ScriptableObject.putProperty(engineApi, "findNodesByTag", jsFunc { args ->
            val t = RhinoContext.toString(args.getOrNull(0) ?: "")
            val matched = nodes.filter { it.tag == t && !pendingRemove.contains(it) }.mapNotNull { it.jsObj }.toTypedArray()
            rhino.newArray(globalScope, matched)
        })
        ScriptableObject.putProperty(engineApi, "on", jsFunc { args ->
            val ev = RhinoContext.toString(args.getOrNull(0) ?: "")
            val fn = args.getOrNull(1) as? Function
            if (ev.isNotEmpty() && fn != null) {
                eventListeners.getOrPut(ev) { mutableListOf() }.add(fn)
            }
            null
        })
        ScriptableObject.putProperty(engineApi, "emit", jsFunc { args ->
            val ev = RhinoContext.toString(args.getOrNull(0) ?: "")
            val payload = args.getOrNull(1)
            eventListeners[ev]?.toList()?.forEach { fn ->
                try { fn.call(rhino, globalScope, globalScope, arrayOf(payload)) } catch (_: Exception) {}
            }
            null
        })
        ScriptableObject.putProperty(globalScope, "Engine", engineApi)

        val inputApi = object : ScriptableObject() {
            override fun getClassName() = "Input"
            override fun get(name: String?, start: Scriptable?): Any? {
                return when (name) {
                    "isTouching" -> isTouching
                    "screenX", "touchX" -> touchScreenX.toDouble()
                    "screenY", "touchY" -> touchScreenY.toDouble()
                    "worldX" -> touchWorldX.toDouble()
                    "worldY" -> touchWorldY.toDouble()
                    else -> super.get(name, start)
                }
            }
        }
        ScriptableObject.putProperty(globalScope, "Input", inputApi)
    }

    private fun bindNodeScript(n: GameNode) {
        val nodeScope = rhino.newObject(globalScope)
        nodeScope.prototype = globalScope
        val selfObj = GameNodeJsProxy(n).apply {
            parentScope = nodeScope
            prototype = nodeScope
        }
        n.jsObj = selfObj

        ScriptableObject.putProperty(selfObj, "jump", jsFunc { args ->
            val jumpForce = (args.getOrNull(0) as? Number)?.toFloat() ?: 920f
            if (n.isGrounded || n.coyoteTimer > 0f) {
                n.vy = -abs(jumpForce)
                n.isGrounded = false
                n.coyoteTimer = 0f
                n.jumpCount = 1
                return@jsFunc true
            } else if (n.jumpCount < n.maxJumps) {
                n.vy = -abs(jumpForce) * 0.9f
                n.jumpCount++
                return@jsFunc true
            }
            false
        })
        ScriptableObject.putProperty(selfObj, "dropThrough", jsFunc { args ->
            val dur = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.22f
            n.dropThroughTimer = dur
            n.isGrounded = false
            null
        })
        ScriptableObject.putProperty(selfObj, "bounce", jsFunc { args ->
            val bvx = (args.getOrNull(0) as? Number)?.toFloat()
            val bvy = (args.getOrNull(1) as? Number)?.toFloat() ?: -760f
            if (bvx != null) n.vx = bvx
            n.vy = bvy
            n.isGrounded = false
            n.jumpCount = 1
            null
        })
        ScriptableObject.putProperty(selfObj, "play", jsFunc { args ->
            val clipName = RhinoContext.toString(args.getOrNull(0) ?: "")
            val forceRestart = (args.getOrNull(1) as? Boolean) ?: false
            val clip = n.clips[clipName]
            if (clip != null) {
                if (!forceRestart && n.animPlaying && n.currentClipName == clipName) {
                    return@jsFunc null
                }
                n.currentClipName = clipName
                n.animStart = clip.start
                n.animEnd = clip.end
                n.animFps = clip.fps
                n.animLoop = clip.loop
                n.currentFrame = clip.start
                n.animTimer = 0f
                n.animPlaying = true
            } else {
                onConsoleLog("[ANIM 警告 @ ${n.name}] 未找到动画片段: $clipName")
            }
            null
        })
        ScriptableObject.putProperty(selfObj, "playAnim", jsFunc { args ->
            n.currentClipName = ""
            n.animStart = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            n.animEnd = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            n.animFps = (args.getOrNull(2) as? Number)?.toFloat() ?: 12f
            n.animLoop = (args.getOrNull(3) as? Boolean) ?: true
            n.currentFrame = n.animStart
            n.animTimer = 0f
            n.animPlaying = true
            null
        })
        ScriptableObject.putProperty(selfObj, "stopAnim", jsFunc {
            n.animPlaying = false
            n.currentClipName = ""
            null
        })
        ScriptableObject.putProperty(selfObj, "flash", jsFunc { args ->
            n.flashTimer = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.12f
            null
        })
        ScriptableObject.putProperty(selfObj, "emitBurst", jsFunc { args ->
            val count = ((args.getOrNull(0) as? Number)?.toInt() ?: 24).coerceIn(1, 120)
            val gt = getGlobalTransform(n)
            repeat(count) { spawnSingleParticle(n, gt.gx, gt.gy) }
            null
        })
        ScriptableObject.putProperty(nodeScope, "self", selfObj)

        if (n.script.isNotBlank()) {
            try {
                rhino.evaluateString(nodeScope, n.script, n.name, 1, null)
            } catch (e: Exception) {
                onConsoleLog("[JS 编译错误 @ ${n.name}] ${e.message}")
            }
        }
    }

    private fun callLifecycle(n: GameNode, fnName: String, arg: Any? = null) {
        val scope = n.jsObj?.prototype ?: return
        val fn = ScriptableObject.getProperty(scope, fnName) as? Function ?: return
        try {
            val args = if (arg != null) arrayOf(arg) else emptyArray()
            fn.call(rhino, scope, n.jsObj, args)
        } catch (e: Exception) {
            onConsoleLog("[JS 运行错误 @ ${n.name}.$fnName] ${e.message}")
        }
    }

    // ==================== P1：空间哈希加速 + 独立 Collider 的 moveAndSlide ====================
    private fun computeSlopeSurfaceY(slope: GameNode, footX: Float): Float {
        val sb = getColliderBounds(slope)
        val sw = sb.hw * 2f
        val sh = sb.hh * 2f
        val left = sb.cx - sb.hw
        val top = sb.cy - sb.hh
        val bottom = sb.cy + sb.hh
        val t = ((footX - left) / max(1f, sw)).coerceIn(0f, 1f)
        return if (slope.slopeType == "left_up") {
            bottom - t * sh
        } else {
            top + t * sh
        }
    }

    private fun moveAndSlideNode(n: GameNode, dt: Float) {
        val collidedSolids = mutableSetOf<GameNode>()

        if (n.bodyMode == "platformer" || n.bodyMode == "rigid") {
            val gMul = if (n.vy > 0f) n.fallMultiplier else 1f
            n.vy = min(n.maxFallSpeed, n.vy + n.gravity * gMul * dt)
            if (n.drag > 0f) {
                n.vx *= max(0f, 1f - n.drag * dt)
            }
        }

        val startBounds = getColliderBounds(n)
        val prevBottom = startBounds.cy + startBounds.hh

        // 预查询移动前后扫过的空间区域（一次查网格，X/Y/Trigger 复用）
        val sweepMinX = min(startBounds.cx, startBounds.cx + n.vx * dt) - startBounds.hw - 32f
        val sweepMaxX = max(startBounds.cx, startBounds.cx + n.vx * dt) + startBounds.hw + 32f
        val sweepMinY = min(startBounds.cy, startBounds.cy + n.vy * dt) - startBounds.hh - 32f
        val sweepMaxY = max(startBounds.cy, startBounds.cy + n.vy * dt) + startBounds.hh + 32f
        querySpatialCandidates(sweepMinX, sweepMaxX, sweepMinY, sweepMaxY, queryCandidates)
        val nearby = queryCandidates.toList()

        // 1. X 轴位移与斜坡平滑爬升
        n.x += n.vx * dt
        var climbedSlope = false
        if (n.collider && !n.isTrigger) {
            for (other in nearby) {
                if (other === n || !other.visible || !other.collider || other.isTrigger || other.oneWay || other.layer != n.layer) continue
                if (!canCollideLayer(n, other)) continue
                if (other.slopeType == "left_up" || other.slopeType == "right_up") {
                    if (checkOverlap(n, other)) {
                        val cb = getColliderBounds(n)
                        val surfY = computeSlopeSurfaceY(other, cb.cx)
                        val footY = cb.cy + cb.hh
                        if (footY >= surfY - 18f && n.vy >= -40f) {
                            n.y += (surfY - footY)
                            n.vy = 0f
                            n.isGrounded = true
                            n.jumpCount = 0
                            n.coyoteTimer = n.coyoteTimeMax
                            climbedSlope = true
                            collidedSolids.add(other)
                        }
                    }
                } else if (checkOverlap(n, other)) {
                    n.x -= n.vx * dt
                    if (n.bodyMode == "rigid") n.vx = -n.vx * n.bounciness else n.vx = 0f
                    collidedSolids.add(other)
                }
            }
        }

        // 2. Y 轴位移与地面/斜坡/单向平台碰撞
        val wasGrounded = n.isGrounded
        if (!climbedSlope) n.isGrounded = false
        n.y += n.vy * dt
        if (n.collider && !n.isTrigger) {
            for (other in nearby) {
                if (other === n || !other.visible || !other.collider || other.isTrigger || other.layer != n.layer) continue
                if (!canCollideLayer(n, other)) continue
                if (other.slopeType == "left_up" || other.slopeType == "right_up") {
                    val cb = getColliderBounds(n)
                    val surfY = computeSlopeSurfaceY(other, cb.cx)
                    val footY = cb.cy + cb.hh
                    if (checkOverlap(n, other) && footY >= surfY && n.vy >= 0f) {
                        n.y += (surfY - footY)
                        n.vy = 0f
                        n.isGrounded = true
                        n.jumpCount = 0
                        n.coyoteTimer = n.coyoteTimeMax
                        collidedSolids.add(other)
                    } else if (wasGrounded && n.vy >= 0f && !n.isGrounded) {
                        val sb = getColliderBounds(other)
                        if (abs(cb.cx - sb.cx) <= sb.hw && surfY - footY in 0f..24f) {
                            n.y += (surfY - footY)
                            n.vy = 0f
                            n.isGrounded = true
                            n.jumpCount = 0
                            n.coyoteTimer = n.coyoteTimeMax
                            collidedSolids.add(other)
                        }
                    }
                } else if (other.oneWay) {
                    if (n.vy >= 0f && n.dropThroughTimer <= 0f) {
                        val ob = getColliderBounds(other)
                        val otherTop = ob.cy - ob.hh
                        if (prevBottom <= otherTop + 14f && checkOverlap(n, other)) {
                            n.y -= n.vy * dt
                            n.vy = 0f
                            n.isGrounded = true
                            n.jumpCount = 0
                            n.coyoteTimer = n.coyoteTimeMax
                            collidedSolids.add(other)
                        }
                    }
                } else if (checkOverlap(n, other)) {
                    val fallingDown = n.vy > 0f
                    n.y -= n.vy * dt
                    if (fallingDown) {
                        n.isGrounded = true
                        n.jumpCount = 0
                        n.coyoteTimer = n.coyoteTimeMax
                    }
                    if (n.bodyMode == "rigid" && abs(n.vy) > 90f) {
                        n.vy = -n.vy * n.bounciness
                    } else {
                        n.vy = 0f
                    }
                    collidedSolids.add(other)
                }
            }
        }

        if (!n.isGrounded) {
            if (wasGrounded) n.coyoteTimer = n.coyoteTimeMax
            else n.coyoteTimer = max(0f, n.coyoteTimer - dt)
        }

        n.z += n.vz * dt

        for (other in collidedSolids) {
            if (!pendingRemove.contains(n) && !pendingRemove.contains(other)) {
                callLifecycle(n, "onCollision", other.jsObj)
                callLifecycle(other, "onCollision", n.jsObj)
            }
        }

        if (n.collider) {
            for (other in nearby) {
                if (other !== n && other.visible && other.collider && other.layer == n.layer) {
                    if (!canCollideLayer(n, other)) continue
                    if (n.isTrigger || other.isTrigger) {
                        if (checkOverlap(n, other)) {
                            if (!pendingRemove.contains(n) && !pendingRemove.contains(other)) {
                                callLifecycle(n, "onTriggerEnter", other.jsObj)
                                callLifecycle(other, "onTriggerEnter", n.jsObj)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkOverlap(a: GameNode, b: GameNode): Boolean {
        val ba = getColliderBounds(a)

        if (b.nodeType == "TileMap") {
            val gb = getGlobalTransform(b)
            val ts = max(8f, b.tileSize)
            val minGx = floor((ba.cx - ba.hw - gb.gx) / ts).toInt()
            val maxGx = floor((ba.cx + ba.hw - gb.gx) / ts).toInt()
            val minGy = floor((ba.cy - ba.hh - gb.gy) / ts).toInt()
            val maxGy = floor((ba.cy + ba.hh - gb.gy) / ts).toInt()
            for (gx in minGx..maxGx) {
                for (gy in minGy..maxGy) {
                    if (b.tileLookup.containsKey(packGridKey(gx, gy))) {
                        return true
                    }
                }
            }
            return false
        }

        val bb = getColliderBounds(b)
        return abs(ba.cx - bb.cx) < (ba.hw + bb.hw) &&
               abs(ba.cy - bb.cy) < (ba.hh + bb.hh)
    }

    data class GlobalTransform(val gx: Float, val gy: Float, val gz: Float, val gRot: Float, val gSx: Float, val gSy: Float)

    private fun getGlobalTransform(n: GameNode, visited: MutableSet<String> = mutableSetOf()): GlobalTransform {
        val pxOffset = if (n.layer == "World" && n.parallaxX != 1f) (camX - 540f) * (1f - n.parallaxX) else 0f
        val pyOffset = if (n.layer == "World" && n.parallaxY != 1f) (camY - 960f) * (1f - n.parallaxY) else 0f

        if (n.parent.isEmpty() || visited.contains(n.name)) {
            return GlobalTransform(n.x + pxOffset, n.y + pyOffset, n.z, n.rotation, n.scaleX, n.scaleY)
        }
        visited.add(n.name)
        val p = nodes.find { it.name == n.parent }
            ?: return GlobalTransform(n.x + pxOffset, n.y + pyOffset, n.z, n.rotation, n.scaleX, n.scaleY)
        val pt = getGlobalTransform(p, visited)
        val rad = Math.toRadians(pt.gRot.toDouble())
        val sx = n.x * pt.gSx
        val sy = n.y * pt.gSy
        return GlobalTransform(
            pt.gx + (sx * cos(rad) - sy * sin(rad)).toFloat(),
            pt.gy + (sx * sin(rad) + sy * cos(rad)).toFloat(),
            pt.gz + n.z,
            pt.gRot + n.rotation,
            pt.gSx * n.scaleX,
            pt.gSy * n.scaleY
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sx = (event.x / max(1, width)) * 1080f
        val sy = (event.y / max(1, height)) * 1920f
        touchScreenX = sx
        touchScreenY = sy
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("down", sx, sy)) }
            }
            MotionEvent.ACTION_MOVE -> {
                synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("move", sx, sy)) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("up", sx, sy)) }
            }
        }
        return true
    }

    private fun flushTouchEvents() {
        val list = synchronized(pendingTouchEvents) {
            val copy = pendingTouchEvents.toList()
            pendingTouchEvents.clear()
            copy
        }
        for ((type, sx, sy) in list) {
            val evObj = rhino.newObject(globalScope)
            ScriptableObject.putProperty(evObj, "type", type)
            ScriptableObject.putProperty(evObj, "screenX", sx.toDouble())
            ScriptableObject.putProperty(evObj, "screenY", sy.toDouble())
            ScriptableObject.putProperty(evObj, "worldX", touchWorldX.toDouble())
            ScriptableObject.putProperty(evObj, "worldY", touchWorldY.toDouble())
            nodes.toList().forEach { callLifecycle(it, "onInput", evObj) }
        }
    }

    private fun parseSceneJson(raw: String, outList: MutableList<GameNode>) {
        try {
            val root = JSONObject(raw)
            val camObj = root.optJSONObject("camera")
            if (camObj != null) {
                camMode = camObj.optString("mode", "2.5D")
                camPitch = camObj.optDouble("pitch", 48.0).toFloat()
                camDistance = camObj.optDouble("distance", 1250.0).toFloat()
            }
            val arr = root.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val clipMap = mutableMapOf<String, AnimClip>()
                val clipsArr = o.optJSONArray("clips")
                if (clipsArr != null) {
                    for (cIdx in 0 until clipsArr.length()) {
                        val co = clipsArr.getJSONObject(cIdx)
                        val cName = co.optString("name", "")
                        if (cName.isNotEmpty()) {
                            val evMap = mutableMapOf<Int, String>()
                            val evObj = co.optJSONObject("events")
                            if (evObj != null) {
                                val keys = evObj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    k.toIntOrNull()?.let { fIdx -> evMap[fIdx] = evObj.optString(k, "") }
                                }
                            }
                            clipMap[cName] = AnimClip(
                                name = cName,
                                start = co.optInt("start", 0),
                                end = co.optInt("end", 0),
                                fps = co.optDouble("fps", 12.0).toFloat(),
                                loop = co.optBoolean("loop", true),
                                events = evMap
                            )
                        }
                    }
                }
                val tileList = mutableListOf<TileCell>()
                val tileMapLookup = HashMap<Long, TileCell>()
                val tilesArr = o.optJSONArray("tiles")
                if (tilesArr != null) {
                    for (tIdx in 0 until tilesArr.length()) {
                        val to = tilesArr.getJSONObject(tIdx)
                        val cell = TileCell(
                            gx = to.optInt("gx", 0),
                            gy = to.optInt("gy", 0),
                            frame = to.optInt("frame", 0)
                        )
                        tileList.add(cell)
                        tileMapLookup[packGridKey(cell.gx, cell.gy)] = cell
                    }
                }
                val defaultClipName = o.optString("defaultClip", "")
                val rawLayer = o.optString("layer", "World")
                val node = GameNode(
                    name = o.optString("name", "Node_$i"),
                    nodeType = o.optString("nodeType", "Sprite2D"),
                    layer = if (rawLayer.startsWith("UI", ignoreCase = true)) "UI" else "World",
                    parent = o.optString("parent", ""),
                    tag = o.optString("tag", "default"),
                    x = o.optDouble("x", 0.0).toFloat(),
                    y = o.optDouble("y", 0.0).toFloat(),
                    z = o.optDouble("z", 0.0).toFloat(),
                    width = o.optDouble("width", 120.0).toFloat(),
                    height = o.optDouble("height", 120.0).toFloat(),
                    rotation = o.optDouble("rotation", 0.0).toFloat(),
                    tiltX = o.optDouble("tiltX", 0.0).toFloat(),
                    billboard = o.optBoolean("billboard", false),
                    scaleX = o.optDouble("scaleX", 1.0).toFloat(),
                    scaleY = o.optDouble("scaleY", 1.0).toFloat(),
                    zIndex = o.optInt("zIndex", 0),
                    visible = o.optBoolean("visible", true),
                    parallaxX = o.optDouble("parallaxX", 1.0).toFloat(),
                    parallaxY = o.optDouble("parallaxY", 1.0).toFloat(),
                    texture = o.optString("texture", ""),
                    text = o.optString("text", ""),
                    fontSize = o.optDouble("fontSize", 44.0).toFloat(),
                    r = o.optDouble("r", 1.0).toFloat(),
                    g = o.optDouble("g", 1.0).toFloat(),
                    b = o.optDouble("b", 1.0).toFloat(),
                    a = o.optDouble("a", 1.0).toFloat(),
                    lightRadius = o.optDouble("lightRadius", 450.0).toFloat(),
                    lightIntensity = o.optDouble("lightIntensity", 1.4).toFloat(),
                    shadowCaster = o.optBoolean("shadowCaster", false),
                    collider = o.optBoolean("collider", false),
                    isTrigger = o.optBoolean("isTrigger", false),
                    oneWay = o.optBoolean("oneWay", false),
                    slopeType = o.optString("slopeType", "none"),
                    colOffsetX = o.optDouble("colOffsetX", 0.0).toFloat(),
                    colOffsetY = o.optDouble("colOffsetY", 0.0).toFloat(),
                    colWidth = o.optDouble("colWidth", 0.0).toFloat(),
                    colHeight = o.optDouble("colHeight", 0.0).toFloat(),
                    colLayer = o.optInt("colLayer", 1),
                    colMask = o.optInt("colMask", 0xFFFF),
                    bodyMode = o.optString("bodyMode", "kinematic"),
                    gravity = o.optDouble("gravity", 2200.0).toFloat(),
                    fallMultiplier = o.optDouble("fallMultiplier", 1.55).toFloat(),
                    maxFallSpeed = o.optDouble("maxFallSpeed", 1450.0).toFloat(),
                    bounciness = o.optDouble("bounciness", 0.5).toFloat(),
                    drag = o.optDouble("drag", 0.0).toFloat(),
                    maxJumps = o.optInt("maxJumps", 2),
                    isHitbox = o.optBoolean("isHitbox", false),
                    isHurtbox = o.optBoolean("isHurtbox", false),
                    damage = o.optDouble("damage", 10.0).toFloat(),
                    hFrames = o.optInt("hFrames", 1),
                    vFrames = o.optInt("vFrames", 1),
                    currentFrame = o.optInt("currentFrame", 0),
                    animFps = o.optDouble("animFps", 12.0).toFloat(),
                    clips = clipMap,
                    tileSize = o.optDouble("tileSize", 90.0).toFloat(),
                    tiles = tileList,
                    tileLookup = tileMapLookup,
                    emitRate = o.optDouble("emitRate", 24.0).toFloat(),
                    particleLife = o.optDouble("particleLife", 1.1).toFloat(),
                    particleSpeed = o.optDouble("particleSpeed", 160.0).toFloat(),
                    particleSize = o.optDouble("particleSize", 16.0).toFloat(),
                    particleGravity = o.optDouble("particleGravity", -60.0).toFloat(),
                    shader = o.optString("shader", ""),
                    script = o.optString("script", "")
                )
                if (defaultClipName.isNotEmpty() && clipMap.containsKey(defaultClipName)) {
                    val dc = clipMap[defaultClipName]!!
                    node.currentClipName = defaultClipName
                    node.animStart = dc.start
                    node.animEnd = dc.end
                    node.animFps = dc.fps
                    node.animLoop = dc.loop
                    node.currentFrame = dc.start
                    node.animPlaying = true
                }
                outList.add(node)
            }
        } catch (e: Exception) {
            onConsoleLog("[场景解析异常] ${e.message}")
        }
    }

    private fun getImageTexture(fileName: String): Int {
        textureCache[fileName]?.let { return it }
        val file = File(File(projectDir, "textures"), fileName)
        if (!file.exists()) return whiteTexId
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return whiteTexId
        val id = uploadBitmapToGL(bmp)
        bmp.recycle()
        textureCache[fileName] = id
        return id
    }

    private fun getTextTexture(text: String, size: Float): Int {
        val key = "$text|$size"
        textTextureCache[key]?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            isFakeBoldText = true
        }
        val w = max(64, (paint.measureText(text) + 24).toInt())
        val h = max(64, (size * 1.5f).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 12f, size, paint)
        val id = uploadBitmapToGL(bmp)
        bmp.recycle()
        textTextureCache[key] = id
        return id
    }

    private fun createSolidWhiteTexture(): Int {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val id = uploadBitmapToGL(bmp)
        bmp.recycle()
        return id
    }

    private fun uploadBitmapToGL(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    private fun buildShaderProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            onConsoleLog("[GLSL 错误] ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun playToneAsync(hz: Int, ms: Int) {
        Thread {
            try {
                val sampleRate = 22050
                val count = (sampleRate * (ms / 1000.0)).toInt()
                val buf = ShortArray(count)
                for (i in 0 until count) {
                    val env = 1.0 - (i.toDouble() / count)
                    buf[i] = (sin(2.0 * PI * i * hz / sampleRate) * 12000 * env).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(ms.toLong() + 30)
                track.release()
            } catch (_: Exception) {}
        }.start()
    }

    private fun jsFunc(block: (Array<out Any?>) -> Any?): org.mozilla.javascript.BaseFunction {
        return object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: RhinoContext?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>): Any? = block(args)
        }
    }

    private fun toJsFloat(v: Any?, def: Float): Float = (v as? Number)?.toFloat() ?: def
    private fun toJsInt(v: Any?, def: Int): Int = (v as? Number)?.toInt() ?: def
    private fun toJsStr(v: Any?, def: String): String = v?.takeIf { it !== Scriptable.NOT_FOUND }?.toString() ?: def
    private fun toJsBool(v: Any?, def: Boolean): Boolean = (v as? Boolean) ?: def

    private fun getJsFloat(s: Scriptable, k: String, def: Float): Float =
        (ScriptableObject.getProperty(s, k) as? Number)?.toFloat() ?: def
    private fun getJsInt(s: Scriptable, k: String, def: Int): Int =
        (ScriptableObject.getProperty(s, k) as? Number)?.toInt() ?: def
    private fun getJsStr(s: Scriptable, k: String, def: String): String =
        ScriptableObject.getProperty(s, k)?.takeIf { it !== Scriptable.NOT_FOUND }?.toString() ?: def
    private fun getJsBool(s: Scriptable, k: String, def: Boolean): Boolean =
        (ScriptableObject.getProperty(s, k) as? Boolean) ?: def

    companion object {
        private val BRIDGED_PROPS = setOf(
            "name", "tag", "x", "y", "z", "vx", "vy", "vz",
            "width", "height", "rotation", "tiltX", "scaleX", "scaleY",
            "parallaxX", "parallaxY", "slopeType",
            "colOffsetX", "colOffsetY", "colWidth", "colHeight", "colLayer", "colMask",
            "bodyMode", "gravity", "fallMultiplier", "maxJumps", "isGrounded",
            "isHitbox", "isHurtbox", "damage", "invulnTimer",
            "r", "g", "b", "a", "visible", "text", "currentClip",
            "lightRadius", "lightIntensity"
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform vec4 uUvRect;
            varying vec2 vTexCoord;
            varying vec3 vWorldPos;
            void main() {
                vec4 worldPos = uModel * aPosition;
                vWorldPos = worldPos.xyz;
                vTexCoord = uUvRect.xy + aTexCoord * uUvRect.zw;
                gl_Position = uMVP * aPosition;
            }
        """

        private const val DEFAULT_LIT_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            uniform float uFlash;
            uniform int uEnableLight;
            uniform vec3 uAmbient;
            uniform int uLightCount;
            uniform vec4 uLightPos[8];
            uniform vec4 uLightColor[8];
            varying vec2 vTexCoord;
            varying vec3 vWorldPos;
            void main() {
                vec4 base = texture2D(uTexture, vTexCoord) * uColor;
                if (base.a < 0.01) discard;
                vec3 litRgb = base.rgb;
                if (uEnableLight != 0) {
                    vec3 totalLight = uAmbient;
                    for (int i = 0; i < 8; i++) {
                        if (i >= uLightCount) break;
                        float dist = distance(vWorldPos.xy, uLightPos[i].xy);
                        float radius = max(1.0, uLightPos[i].w);
                        float atten = clamp(1.0 - (dist / radius), 0.0, 1.0);
                        atten = atten * atten;
                        totalLight += uLightColor[i].rgb * atten;
                    }
                    litRgb = base.rgb * totalLight;
                }
                vec3 finalRgb = mix(litRgb, vec3(1.0, 1.0, 1.0), clamp(uFlash, 0.0, 1.0));
                gl_FragColor = vec4(finalRgb, base.a);
            }
        """
    }
}
package com.zero.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.KeyEvent
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
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

// ==================== ZeroEngine 主视图与渲染/物理/脚本/P7专业动画核心 ====================
class ZeroEngineView(
    context: Context,
    private val projectDir: File,
    private val sceneJson: String,
    private val onConsoleLog: (String) -> Unit
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    data class AtlasFrame(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val sourceW: Float,
        val sourceH: Float
    )

    data class TextureAtlasData(
        val imageFile: String,
        val atlasW: Float,
        val atlasH: Float,
        val frames: Map<String, AtlasFrame>
    )

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

    data class AdditiveLoadReq(
        val sceneFile: String,
        val offsetX: Float,
        val offsetY: Float
    )

    data class GameNode(
        var name: String,
        var nodeType: String = "Sprite2D", // Sprite2D | Label2D | PointLight | ParticleEmitter | TileMap
        var layer: String = "World",       // World | UI
        var parent: String = "",
        var tag: String = "default",
        var sceneGroup: String = "main",
        var persistent: Boolean = false,
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
        var parallaxX: Float = 1f,
        var parallaxY: Float = 1f,
        var texture: String = "",
        var atlas: String = "",
        var atlasFrame: String = "",
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
        // P5：UI 九宫格（9-Slice）与矩形裁剪区（Scissor）
        var sliceLeft: Float = 0f,
        var sliceRight: Float = 0f,
        var sliceTop: Float = 0f,
        var sliceBottom: Float = 0f,
        var clipChildren: Boolean = false,
        var clipX: Float = 0f,
        var clipY: Float = 0f,
        var clipW: Float = 0f,
        var clipH: Float = 0f,
        // 物理、平台、斜坡与独立碰撞体
        var collider: Boolean = false,
        var isTrigger: Boolean = false,
        var oneWay: Boolean = false,
        var slopeType: String = "none",
        var colOffsetX: Float = 0f,
        var colOffsetY: Float = 0f,
        var colWidth: Float = 0f,
        var colHeight: Float = 0f,
        var colLayer: Int = 1,
        var colMask: Int = 0xFFFF,
        var bodyMode: String = "kinematic",
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
        // 战斗判定
        var isHitbox: Boolean = false,
        var isHurtbox: Boolean = false,
        var damage: Float = 10f,
        var invulnTimer: Float = 0f,
        var hitCooldownMap: MutableMap<String, Float> = mutableMapOf(),
        // 旧版序列帧（兼容保留）
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
        // P7：商业级专业动画播放器（逐帧时长/偏移/帧同步Hitbox/骨骼轨道/FSM）
        val animator: ZeroAnimator = ZeroAnimator(),
        // TileMap 稀疏瓦片数据 + VBO + O(1) 查表
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
        // GPU 粒子池
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

    // ==================== 零拷贝 JS 属性代理 ====================
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
                "sceneGroup" -> n.sceneGroup
                "persistent" -> n.persistent
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
                "atlas" -> n.atlas
                "atlasFrame" -> n.atlasFrame
                "sliceLeft" -> n.sliceLeft.toDouble()
                "sliceRight" -> n.sliceRight.toDouble()
                "sliceTop" -> n.sliceTop.toDouble()
                "sliceBottom" -> n.sliceBottom.toDouble()
                "clipChildren" -> n.clipChildren
                "clipX" -> n.clipX.toDouble()
                "clipY" -> n.clipY.toDouble()
                "clipW" -> n.clipW.toDouble()
                "clipH" -> n.clipH.toDouble()
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
                "currentClip" -> n.animator.currentClip?.name ?: n.currentClipName
                "lightRadius" -> n.lightRadius.toDouble()
                "lightIntensity" -> n.lightIntensity.toDouble()
                else -> super.get(name, start)
            }
        }

        override fun put(name: String?, start: Scriptable?, value: Any?) {
            when (name) {
                "tag" -> n.tag = toJsStr(value, n.tag)
                "persistent" -> n.persistent = toJsBool(value, n.persistent)
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
                "atlas" -> n.atlas = toJsStr(value, n.atlas)
                "atlasFrame" -> n.atlasFrame = toJsStr(value, n.atlasFrame)
                "sliceLeft" -> n.sliceLeft = toJsFloat(value, n.sliceLeft)
                "sliceRight" -> n.sliceRight = toJsFloat(value, n.sliceRight)
                "sliceTop" -> n.sliceTop = toJsFloat(value, n.sliceTop)
                "sliceBottom" -> n.sliceBottom = toJsFloat(value, n.sliceBottom)
                "clipChildren" -> n.clipChildren = toJsBool(value, n.clipChildren)
                "clipX" -> n.clipX = toJsFloat(value, n.clipX)
                "clipY" -> n.clipY = toJsFloat(value, n.clipY)
                "clipW" -> n.clipW = toJsFloat(value, n.clipW)
                "clipH" -> n.clipH = toJsFloat(value, n.clipH)
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
                "name", "sceneGroup", "isGrounded", "currentClip" -> Unit
                else -> super.put(name, start, value)
            }
        }
    }

    // 独立子系统实例（已拆分至独立 .kt 文件）
    private val audioSystem = ZeroAudioSystem(projectDir, onConsoleLog)
    private val inputSystem = ZeroInputSystem()
    private val spriteBatch = ZeroSpriteBatch(1024)

    private val moduleCache = mutableMapOf<String, Any?>()
    private val worldFlags = ConcurrentHashMap<String, Any>()
    private val dataTableCache = mutableMapOf<String, Any?>()
    private var currentLang = "zh"
    private val i18nDicts = mutableMapOf<String, MutableMap<String, String>>()
    private val atlasCache = ConcurrentHashMap<String, TextureAtlasData>()

    private val nodes = mutableListOf<GameNode>()
    private val pendingAdd = mutableListOf<GameNode>()
    private val pendingRemove = mutableSetOf<GameNode>()
    private val activeTweens = mutableListOf<TweenTask>()
    private val textureCache = ConcurrentHashMap<String, Int>()
    private val textureSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val eventListeners = mutableMapOf<String, MutableList<Function>>()

    private val spatialCellSize = 256f
    private val spatialGrid = HashMap<Long, MutableList<GameNode>>(256)
    private val activeTileMaps = mutableListOf<GameNode>()
    private val queryCandidates = LinkedHashSet<GameNode>(32)

    private var debugDrawEnabled = false
    private val debugRays = mutableListOf<DebugRay>()
    private val debugVertBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private val globalDataStore = ConcurrentHashMap<String, Any?>()
    private val preloadedSceneCache = ConcurrentHashMap<String, String>()
    private val sceneIoExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var pendingSceneLoad: String? = null
    private val pendingAdditiveLoads = mutableListOf<AdditiveLoadReq>()
    private val pendingUnloadScenes = mutableListOf<String>()

    private var postFxEnabled = true
    private var fboReady = false
    private var sceneFbo = 0
    private var sceneColorTex = 0
    private var lightFbo = 0
    private var lightMapTex = 0
    private var lightW = 540
    private var lightH = 960
    private var lightProgram = 0
    private var postProgram = 0
    private var batchProgram = 0

    private var postBloom = 0.25f
    private var postVignette = 0.28f
    private var postChromatic = 0.0f
    private var postFade = 0.0f
    private var fadeTarget = 0.0f
    private var fadeSpeed = 0.0f
    private var pendingFadeScene: String? = null
    private var fadeSceneDur = 0.35f

    private val fullScreenQuadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            ))
            position(0)
        }

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

    private var smoothedFps = 60f
    private var frameDrawCalls = 0
    private var lastFrameDrawCalls = 0
    private var frameCulled = 0
    private var lastFrameCulled = 0
    private var lastFrameLights = 0

    private var hitstopTimer = 0f
    private var timeScale = 1f

    private var ambientR = 0.52f
    private var ambientG = 0.56f
    private var ambientB = 0.68f

    @Volatile private var isTouching = false
    @Volatile private var touchScreenX = 0f
    @Volatile private var touchScreenY = 0f
    @Volatile private var touchWorldX = 0f
    @Volatile private var touchWorldY = 0f
    private val pendingTouchEvents = mutableListOf<Triple<String, Float, Float>>()

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val invVpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val subModelMatrix = FloatArray(16)
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

    private val slopeLeftUpBuffer: FloatBuffer = ByteBuffer.allocateDirect(3 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                 0.5f, -0.5f, 0f, 1f, 0f,
                -0.5f,  0.5f, 0f, 0f, 1f,
                 0.5f,  0.5f, 0f, 1f, 1f
            ))
            position(0)
        }

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
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun packGridKey(gx: Int, gy: Int): Long =
        (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)

    private fun normalizeSceneName(name: String): String =
        if (name.endsWith(".scene")) name else "$name.scene"

    fun releaseResources() {
        try { sceneIoExecutor.shutdownNow() } catch (_: Exception) {}
        inputSystem.clearAllPointers()
        queueEvent {
            releaseFbos()
            releaseSceneGpuResources(includePersistent = true)
            if (textureCache.isNotEmpty()) {
                val ids = textureCache.values.filter { it != 0 && it != whiteTexId }.toIntArray()
                if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
                textureCache.clear()
                textureSizes.clear()
            }
            if (textTextureCache.isNotEmpty()) {
                val ids = textTextureCache.values.filter { it != 0 && it != whiteTexId }.toIntArray()
                if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
                textTextureCache.clear()
            }
        }
        audioSystem.release()
    }

    // ==================== P6：纹理大图集（TextureAtlas） ====================
    private fun loadAtlasData(atlasName: String): TextureAtlasData? {
        val cleaned = atlasName.trim().replace("\\", "/").removePrefix("./")
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val norm = if (cleaned.endsWith(".json", ignoreCase = true)) cleaned else "$cleaned.json"
        atlasCache[norm]?.let { return it }

        val file = File(File(projectDir, "textures"), norm)
        if (!file.exists()) {
            onConsoleLog("[ATLAS 警告] 找不到图集文件: textures/$norm")
            return null
        }
        return try {
            val root = JSONObject(file.readText())
            val metaObj = root.optJSONObject("meta")
            val imageFile = root.optString("image", "").ifEmpty { metaObj?.optString("image", "") ?: "" }
            if (imageFile.isEmpty()) return null
            getImageTexture(imageFile)
            val imgSize = textureSizes[imageFile]
            val sizeObj = root.optJSONObject("size") ?: metaObj?.optJSONObject("size")
            val atlasW = sizeObj?.optDouble("w", (imgSize?.first ?: 1024).toDouble())?.toFloat()
                ?: (imgSize?.first ?: 1024).toFloat()
            val atlasH = sizeObj?.optDouble("h", (imgSize?.second ?: 1024).toDouble())?.toFloat()
                ?: (imgSize?.second ?: 1024).toFloat()

            val frameMap = HashMap<String, AtlasFrame>()
            val framesObj = root.optJSONObject("frames")
            if (framesObj != null) {
                val keys = framesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val fo = framesObj.optJSONObject(key) ?: continue
                    val rect = fo.optJSONObject("frame") ?: fo
                    val srcSize = fo.optJSONObject("sourceSize")
                    frameMap[key] = AtlasFrame(
                        x = rect.optDouble("x", 0.0).toFloat(),
                        y = rect.optDouble("y", 0.0).toFloat(),
                        w = rect.optDouble("w", 0.0).toFloat(),
                        h = rect.optDouble("h", 0.0).toFloat(),
                        sourceW = srcSize?.optDouble("w", rect.optDouble("w", 0.0))?.toFloat() ?: rect.optDouble("w", 0.0).toFloat(),
                        sourceH = srcSize?.optDouble("h", rect.optDouble("h", 0.0))?.toFloat() ?: rect.optDouble("h", 0.0).toFloat()
                    )
                }
            } else {
                val framesArr = root.optJSONArray("frames")
                if (framesArr != null) {
                    for (i in 0 until framesArr.length()) {
                        val fo = framesArr.optJSONObject(i) ?: continue
                        val key = fo.optString("filename", "frame_$i")
                        val rect = fo.optJSONObject("frame") ?: fo
                        frameMap[key] = AtlasFrame(
                            x = rect.optDouble("x", 0.0).toFloat(),
                            y = rect.optDouble("y", 0.0).toFloat(),
                            w = rect.optDouble("w", 0.0).toFloat(),
                            h = rect.optDouble("h", 0.0).toFloat(),
                            sourceW = rect.optDouble("w", 0.0).toFloat(),
                            sourceH = rect.optDouble("h", 0.0).toFloat()
                        )
                    }
                }
            }
            val data = TextureAtlasData(imageFile, max(1f, atlasW), max(1f, atlasH), frameMap)
            atlasCache[norm] = data
            data
        } catch (e: Exception) {
            onConsoleLog("[ATLAS 解析错误 @ $norm] ${e.message}")
            null
        }
    }

    private fun unloadUnusedTextures(): Int {
        if (textureCache.isEmpty()) return 0
        val activeTexNames = HashSet<String>()
        for (n in nodes) {
            if (pendingRemove.contains(n)) continue
            if (n.texture.isNotEmpty()) activeTexNames.add(n.texture)
            if (n.atlas.isNotEmpty()) loadAtlasData(n.atlas)?.let { activeTexNames.add(it.imageFile) }
        }
        for (n in pendingAdd) {
            if (n.texture.isNotEmpty()) activeTexNames.add(n.texture)
            if (n.atlas.isNotEmpty()) loadAtlasData(n.atlas)?.let { activeTexNames.add(it.imageFile) }
        }
        val toDelete = mutableListOf<String>()
        val ids = mutableListOf<Int>()
        for ((name, texId) in textureCache) {
            if (name !in activeTexNames) {
                toDelete.add(name)
                if (texId != 0 && texId != whiteTexId) ids.add(texId)
            }
        }
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids.toIntArray(), 0)
        for (k in toDelete) {
            textureCache.remove(k)
            textureSizes.remove(k)
        }
        return toDelete.size
    }

    private fun requireScriptModule(rawPath: String): Any? {
        val cleaned = rawPath.trim().replace("\\", "/").removePrefix("./")
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val normPath = if (cleaned.endsWith(".js")) cleaned else "$cleaned.js"
        if (moduleCache.containsKey(normPath)) return moduleCache[normPath]

        val file = File(File(projectDir, "scripts"), normPath)
        if (!file.exists()) {
            onConsoleLog("[MODULE 错误] 找不到脚本模块: scripts/$normPath")
            return null
        }
        return try {
            val code = file.readText()
            val modScope = rhino.newObject(globalScope)
            modScope.prototype = globalScope
            val exportsObj = rhino.newObject(modScope)
            val moduleObj = rhino.newObject(modScope)
            ScriptableObject.putProperty(moduleObj, "exports", exportsObj)
            ScriptableObject.putProperty(modScope, "module", moduleObj)
            ScriptableObject.putProperty(modScope, "exports", exportsObj)

            moduleCache[normPath] = exportsObj
            rhino.evaluateString(modScope, code, "scripts/$normPath", 1, null)
            val finalExports = ScriptableObject.getProperty(moduleObj, "exports")
            moduleCache[normPath] = finalExports
            finalExports
        } catch (e: Exception) {
            moduleCache.remove(normPath)
            onConsoleLog("[MODULE 错误 @ $normPath] ${e.message}")
            null
        }
    }

    private fun resolveNodeScriptText(rawScript: String, scriptFile: String): String {
        val targetFile = when {
            scriptFile.isNotBlank() -> scriptFile.trim()
            rawScript.startsWith("@file:") -> rawScript.removePrefix("@file:").trim()
            else -> ""
        }
        if (targetFile.isEmpty()) return rawScript
        val cleaned = targetFile.replace("\\", "/").removePrefix("./")
        if (cleaned.contains("..")) return rawScript
        val norm = if (cleaned.endsWith(".js")) cleaned else "$cleaned.js"
        val file = File(File(projectDir, "scripts"), norm)
        return if (file.exists()) file.readText() else rawScript
    }

    private fun saveWorldFlagsToDisk(slotName: String): Boolean {
        return try {
            val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_").ifEmpty { "world_flags" }
            val savesDir = File(projectDir, "saves").apply { mkdirs() }
            val jsonObj = JSONObject()
            for ((k, v) in worldFlags) jsonObj.put(k, v)
            File(savesDir, "flags_$safeName.json").writeText(jsonObj.toString())
            true
        } catch (e: Exception) {
            onConsoleLog("[FLAGS 存盘失败] ${e.message}")
            false
        }
    }

    private fun loadWorldFlagsFromDisk(slotName: String): Boolean {
        return try {
            val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_").ifEmpty { "world_flags" }
            val file = File(File(projectDir, "saves"), "flags_$safeName.json")
            if (!file.exists()) return false
            val root = JSONObject(file.readText())
            worldFlags.clear()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = root.opt(k)
                if (v != null && v !== JSONObject.NULL) {
                    worldFlags[k] = if (v is Number) v.toDouble() else v
                }
            }
            true
        } catch (e: Exception) {
            onConsoleLog("[FLAGS 读盘失败] ${e.message}")
            false
        }
    }

    private fun parseJsonStringToJs(raw: String): Any? {
        val reviver = object : Callable {
            override fun call(cx: RhinoContext?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>): Any? = args.getOrNull(1)
        }
        return NativeJSON.parse(rhino, globalScope, raw, reviver)
    }

    private fun loadDataTable(tableName: String): Any? {
        val cleaned = tableName.trim().replace("\\", "/").removePrefix("./")
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val norm = if (cleaned.endsWith(".json", ignoreCase = true)) cleaned else "$cleaned.json"
        if (dataTableCache.containsKey(norm)) return dataTableCache[norm]

        val dataDir = File(projectDir, "data").apply { mkdirs() }
        val file = File(dataDir, norm)
        if (!file.exists()) {
            onConsoleLog("[DATA 警告] 找不到数据表文件: data/$norm")
            return null
        }
        return try {
            val parsed = parseJsonStringToJs(file.readText())
            dataTableCache[norm] = parsed
            parsed
        } catch (e: Exception) {
            onConsoleLog("[DATA 解析错误 @ $norm] ${e.message}")
            null
        }
    }

    private fun loadI18nFromDiskIfNeeded(lang: String) {
        val dataDir = File(projectDir, "data")
        if (!dataDir.exists()) return
        val dict = i18nDicts.getOrPut(lang) { mutableMapOf() }
        val perLangFile = File(dataDir, "i18n_$lang.json")
        if (perLangFile.exists()) {
            try {
                val obj = JSONObject(perLangFile.readText())
                val it = obj.keys()
                while (it.hasNext()) { val k = it.next(); dict[k] = obj.optString(k, "") }
            } catch (_: Exception) {}
            return
        }
        val combinedFile = File(dataDir, "i18n.json")
        if (combinedFile.exists()) {
            try {
                val sub = JSONObject(combinedFile.readText()).optJSONObject(lang) ?: return
                val it = sub.keys()
                while (it.hasNext()) { val k = it.next(); dict[k] = sub.optString(k, "") }
            } catch (_: Exception) {}
        }
    }

    private fun translateKey(key: String, fallback: String): String {
        val dict = i18nDicts[currentLang]
        if (dict != null && dict.containsKey(key)) return dict[key] ?: fallback
        return if (fallback.isNotEmpty()) fallback else key
    }

    private fun releaseNodeGpuResources(n: GameNode) {
        if (n.tileVboId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(n.tileVboId), 0)
            n.tileVboId = 0; n.tileVertexCount = 0
        }
        if (n.customProgram != 0 && n.customProgram != defaultLitProgram) {
            GLES20.glDeleteProgram(n.customProgram)
            n.customProgram = 0
        }
    }

    private fun releaseSceneGpuResources(includePersistent: Boolean) {
        for (n in nodes) {
            if (!includePersistent && n.persistent) continue
            releaseNodeGpuResources(n)
        }
    }

    private fun releaseFbos() {
        fboReady = false
        if (sceneFbo != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0); sceneFbo = 0 }
        if (sceneColorTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(sceneColorTex), 0); sceneColorTex = 0 }
        if (lightFbo != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(lightFbo), 0); lightFbo = 0 }
        if (lightMapTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(lightMapTex), 0); lightMapTex = 0 }
    }

    private fun createFboTarget(w: Int, h: Int): Pair<Int, Int> {
        val fbos = IntArray(1)
        val texs = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        GLES20.glGenTextures(1, texs, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texs[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) fbos[0] to texs[0] else {
            GLES20.glDeleteFramebuffers(1, fbos, 0)
            GLES20.glDeleteTextures(1, texs, 0)
            0 to 0
        }
    }

    private fun recreateFbos(w: Int, h: Int) {
        releaseFbos()
        lightW = max(1, w / 2)
        lightH = max(1, h / 2)
        val (sf, st) = createFboTarget(w, h)
        val (lf, lt) = createFboTarget(lightW, lightH)
        if (sf != 0 && lf != 0) {
            sceneFbo = sf; sceneColorTex = st
            lightFbo = lf; lightMapTex = lt
            fboReady = (lightProgram != 0 && postProgram != 0)
        } else releaseFbos()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        whiteTexId = createSolidWhiteTexture()
        defaultLitProgram = buildShaderProgram(VERTEX_SHADER, DEFAULT_LIT_FRAGMENT_SHADER)
        lightProgram = buildShaderProgram(VERTEX_SHADER, LIGHT_FRAGMENT_SHADER)
        postProgram = buildShaderProgram(POST_VERTEX_SHADER, POST_FRAGMENT_SHADER)
        batchProgram = buildShaderProgram(BATCH_VERTEX_SHADER, BATCH_FRAGMENT_SHADER)
        spriteBatch.init(batchProgram)

        initRhinoVM()
        loadI18nFromDiskIfNeeded(currentLang)
        loadSceneContent(sceneJson, groupName = "main", additive = false)
        lastFrameNs = System.nanoTime()
    }

    private fun readSceneText(sceneFile: String): String? {
        val norm = normalizeSceneName(sceneFile)
        preloadedSceneCache[norm]?.let { return it }
        val file = File(File(projectDir, "scenes"), norm)
        if (!file.exists()) return null
        val txt = file.readText()
        preloadedSceneCache[norm] = txt
        return txt
    }

    private fun preloadSceneAsync(sceneFile: String) {
        val norm = normalizeSceneName(sceneFile)
        if (preloadedSceneCache.containsKey(norm)) return
        sceneIoExecutor.execute {
            try {
                val file = File(File(projectDir, "scenes"), norm)
                if (file.exists()) {
                    preloadedSceneCache[norm] = file.readText()
                    onConsoleLog("[ASYNC] 后台预加载完成: $norm")
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadSceneContent(
        rawJson: String,
        groupName: String,
        additive: Boolean,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ) {
        if (!additive) {
            releaseSceneGpuResources(includePersistent = false)
            val kept = nodes.filter { it.persistent }
            nodes.clear()
            nodes.addAll(kept)
            pendingAdd.removeAll { !it.persistent }
            pendingRemove.clear()
            activeTweens.removeAll { !it.node.persistent }
            debugRays.clear()
            if (camTarget != null && camTarget?.persistent != true) camTarget = null
            camBoundsEnabled = false
        }

        val newNodes = mutableListOf<GameNode>()
        parseSceneJson(rawJson, newNodes, groupName, applyCamera = !additive)
        if (additive && (offsetX != 0f || offsetY != 0f)) {
            for (n in newNodes) {
                if (n.parent.isEmpty() && n.layer == "World") { n.x += offsetX; n.y += offsetY }
            }
        }
        nodes.addAll(newNodes)
        if (!additive) {
            val freed = unloadUnusedTextures()
            if (freed > 0) onConsoleLog("[VRAM] 自动释放旧场景贴图: $freed 张")
        }
        newNodes.forEach { bindNodeScript(it) }
        rebuildSpatialIndex()
        newNodes.forEach { callLifecycle(it, "onReady") }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = max(1, width)
        surfaceH = max(1, height)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        Matrix.orthoM(uiVpMatrix, 0, 0f, 1080f, 1920f, 0f, -100f, 100f)
        recreateFbos(surfaceW, surfaceH)
    }

    private fun getColliderBounds(n: GameNode): ColliderBounds {
        val gt = getGlobalTransform(n)
        val rawW = if (n.colWidth > 0f) n.colWidth else n.width
        val rawH = if (n.colHeight > 0f) n.colHeight else n.height
        val hw = rawW * abs(gt.gSx) * 0.5f
        val hh = rawH * abs(gt.gSy) * 0.5f
        val cx = gt.gx + n.colOffsetX * gt.gSx
        val cy = gt.gy + n.colOffsetY * gt.gSy
        return ColliderBounds(cx, cy, hw, hh)
    }

    private fun getFrameBoxWorldBounds(owner: GameNode, box: FrameBox): ColliderBounds {
        val gt = getGlobalTransform(owner)
        val dirX = if (gt.gSx < 0f) -1f else 1f
        val scaleAbsX = abs(gt.gSx)
        val scaleAbsY = abs(gt.gSy)
        val cx = gt.gx + box.offsetX * scaleAbsX * dirX
        val cy = gt.gy + box.offsetY * scaleAbsY
        return ColliderBounds(cx, cy, box.width * scaleAbsX * 0.5f, box.height * scaleAbsY * 0.5f)
    }

    private fun canCollideLayer(mover: GameNode, target: GameNode): Boolean =
        (mover.colMask and target.colLayer) != 0

    private fun rebuildSpatialIndex() {
        spatialGrid.clear()
        activeTileMaps.clear()
        for (n in nodes) {
            if (!n.visible || pendingRemove.contains(n)) continue
            val hasFrameBox = n.animator.activeFrame?.boxes?.isNotEmpty() == true
            if (!n.collider && !n.isHitbox && !n.isHurtbox && !hasFrameBox) continue
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
                spatialGrid.getOrPut(packGridKey(cx, cy)) { mutableListOf() }.add(n)
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
                for (i in bucket.indices) out.add(bucket[i])
            }
        }
        for (tm in activeTileMaps) out.add(tm)
    }

    private fun updateFadeTransition(rawDt: Float) {
        if (fadeSpeed > 0f) {
            val step = fadeSpeed * rawDt
            postFade = if (postFade < fadeTarget) min(fadeTarget, postFade + step) else max(fadeTarget, postFade - step)
            if (abs(postFade - fadeTarget) < 0.001f) {
                postFade = fadeTarget
                fadeSpeed = 0f
                val nextScene = pendingFadeScene
                if (nextScene != null && postFade >= 0.99f) {
                    pendingFadeScene = null
                    pendingSceneLoad = nextScene
                    fadeTarget = 0f
                    fadeSpeed = 1f / max(0.05f, fadeSceneDur)
                }
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val rawDt = ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        smoothedFps = smoothedFps * 0.9f + (1f / rawDt) * 0.1f

        inputSystem.beginFrame()
        audioSystem.update(rawDt)
        updateFadeTransition(rawDt)

        pendingSceneLoad?.let { nextSceneFile ->
            pendingSceneLoad = null
            val norm = normalizeSceneName(nextSceneFile)
            val txt = readSceneText(norm)
            if (txt != null) {
                onConsoleLog("[SCENE] 切换关卡场景 -> $norm")
                loadSceneContent(txt, groupName = norm, additive = false)
            } else onConsoleLog("[SCENE 错误] 找不到场景文件: $norm")
        }

        if (pendingAdditiveLoads.isNotEmpty()) {
            val reqs = pendingAdditiveLoads.toList()
            pendingAdditiveLoads.clear()
            for (req in reqs) {
                val norm = normalizeSceneName(req.sceneFile)
                val txt = readSceneText(norm)
                if (txt != null) {
                    loadSceneContent(txt, groupName = norm, additive = true, offsetX = req.offsetX, offsetY = req.offsetY)
                }
            }
        }

        if (pendingUnloadScenes.isNotEmpty()) {
            val groups = pendingUnloadScenes.toSet()
            pendingUnloadScenes.clear()
            for (n in nodes) {
                if (!n.persistent && n.sceneGroup in groups) pendingRemove.add(n)
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
                // P7：优先驱动专业动画内核，未启用时自动回退旧序列帧
                if (n.animator.clips.isNotEmpty() || n.animator.currentClip != null) {
                    updateProAnimator(n, dt)
                } else {
                    updateSpriteAnimation(n, dt)
                }
                if (n.nodeType == "ParticleEmitter") updateParticleEmitter(n, dt)
                callLifecycle(n, "onProcess", dt)
            }
        }

        rebuildSpatialIndex()
        processCombatOverlaps()

        if (pendingRemove.isNotEmpty()) {
            for (rem in pendingRemove) releaseNodeGpuResources(rem)
            nodes.removeAll(pendingRemove)
            pendingRemove.clear()
        }

        renderSceneFrame()
    }

    // ==================== P7：专业动画更新（逐帧同步 + 2D骨骼轨道驱动） ====================
    private fun updateProAnimator(n: GameNode, dt: Float) {
        n.animator.update(
            dt = dt,
            onEvent = { clipName, evName -> callFrameEvent(n, clipName, evName) },
            onFinished = { clipName -> callLifecycle(n, "onAnimFinished", clipName) }
        )
        val af = n.animator.activeFrame
        if (af != null) {
            n.currentFrame = af.frameIndex
            if (af.atlasFrame.isNotEmpty()) n.atlasFrame = af.atlasFrame
        }
        if (n.animator.activeBonePoses.isNotEmpty()) {
            for (pose in n.animator.activeBonePoses) {
                val target = nodes.find { it.name == pose.targetNodeName && (it.parent == n.name || it === n) }
                    ?: nodes.find { it.name == pose.targetNodeName }
                    ?: continue
                pose.x?.let { target.x = it }
                pose.y?.let { target.y = it }
                pose.rotation?.let { target.rotation = it }
                pose.scaleX?.let { target.scaleX = it }
                pose.scaleY?.let { target.scaleY = it }
            }
        }
    }

    private fun callFrameEvent(n: GameNode, clipName: String, evName: String) {
        val scope = n.jsObj?.prototype ?: return
        val fn = ScriptableObject.getProperty(scope, "onFrameEvent") as? Function ?: return
        try {
            fn.call(rhino, scope, n.jsObj, arrayOf(clipName, evName))
        } catch (e: Exception) {
            onConsoleLog("[JS 运行错误 @ ${n.name}.onFrameEvent] ${e.message}")
        }
    }

    private fun isBoxInView(minX: Float, maxX: Float, minY: Float, maxY: Float): Boolean =
        maxX >= viewMinX && minX <= viewMaxX && maxY >= viewMinY && minY <= viewMaxY

    private fun resolveUiClipRect(n: GameNode): FloatArray? {
        var left = Float.NEGATIVE_INFINITY
        var top = Float.NEGATIVE_INFINITY
        var right = Float.POSITIVE_INFINITY
        var bottom = Float.POSITIVE_INFINITY
        var hasClip = false

        if (n.clipW > 0f && n.clipH > 0f) {
            left = max(left, n.clipX); top = max(top, n.clipY)
            right = min(right, n.clipX + n.clipW); bottom = min(bottom, n.clipY + n.clipH)
            hasClip = true
        }

        var parentName = n.parent
        var guard = 0
        while (parentName.isNotEmpty() && guard < 16) {
            guard++
            val p = nodes.find { it.name == parentName } ?: break
            if (p.clipChildren && p.layer == "UI") {
                val pgt = getGlobalTransform(p)
                val pw = abs(p.width * pgt.gSx)
                val ph = abs(p.height * pgt.gSy)
                left = max(left, pgt.gx - pw * 0.5f); top = max(top, pgt.gy - ph * 0.5f)
                right = min(right, pgt.gx + pw * 0.5f); bottom = min(bottom, pgt.gy + ph * 0.5f)
                hasClip = true
            }
            parentName = p.parent
        }
        return if (hasClip) floatArrayOf(left, top, right, bottom) else null
    }

    private fun applyUiScissor(rect: FloatArray) {
        val sx = ((rect[0] / 1080f) * surfaceW).toInt().coerceIn(0, surfaceW)
        val ex = ((rect[2] / 1080f) * surfaceW).toInt().coerceIn(0, surfaceW)
        val sy = (((1920f - rect[3]) / 1920f) * surfaceH).toInt().coerceIn(0, surfaceH)
        val ey = (((1920f - rect[1]) / 1920f) * surfaceH).toInt().coerceIn(0, surfaceH)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(sx, sy, max(0, ex - sx), max(0, ey - sy))
    }

    private fun renderSceneFrame() {
        frameDrawCalls = 0
        frameCulled = 0

        val allLights = nodes.filter { it.visible && it.nodeType == "PointLight" }
        lastFrameLights = allLights.size
        val worldNodes = nodes.filter { it.visible && it.layer != "UI" && it.nodeType != "PointLight" }
            .sortedWith(compareBy({ it.zIndex }, { it.nodeType != "TileMap" }, { it.tiltX >= -45f }, { getGlobalTransform(it).gy }))
        val casters = nodes.filter { it.visible && it.layer != "UI" && it.shadowCaster && it.nodeType != "PointLight" }
        val particleNodes = worldNodes.filter { it.nodeType == "ParticleEmitter" }
        val solidWorldNodes = worldNodes.filter { it.nodeType != "ParticleEmitter" }

        val useFbo = postFxEnabled && fboReady

        if (useFbo) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val emptyArr = FloatArray(4)
            spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, 0, null, null) { frameDrawCalls++ }

            if (allLights.isNotEmpty() && casters.isNotEmpty()) {
                for (caster in casters) {
                    val cg = getGlobalTransform(caster)
                    val pad = max(caster.width * abs(cg.gSx), caster.height * abs(cg.gSy)) * 1.6f
                    if (isBoxInView(cg.gx - pad, cg.gx + pad, cg.gy - pad, cg.gy + pad)) {
                        submitDynamicShadowToBatch(caster, allLights)
                    }
                }
            }

            for (n in solidWorldNodes) {
                when {
                    n.nodeType == "TileMap" -> {
                        spriteBatch.flush()
                        renderTileMap(n, vpMatrix, isWorld = true, enableInlineLight = false, 0, emptyArr, emptyArr)
                    }
                    canBatchNode(n) -> submitNodeToBatch(n, isWorld = true)
                    else -> {
                        spriteBatch.flush()
                        renderNodeSingle(n, vpMatrix, isWorld = true, enableInlineLight = false, 0, emptyArr, emptyArr)
                    }
                }
            }
            spriteBatch.end()

            renderLightsToFbo(allLights)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            renderPostComposite()

            if (particleNodes.isNotEmpty()) {
                spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, 0, null, null) { frameDrawCalls++ }
                for (pNode in particleNodes) submitParticlesToBatch(pNode, isWorld = true, fadeAlpha = 1f - postFade)
                spriteBatch.end()
            }
        } else {
            val lights = allLights.take(8)
            val lightPosArr = FloatArray(8 * 4)
            val lightColArr = FloatArray(8 * 4)
            lights.forEachIndexed { idx, l ->
                val gt = getGlobalTransform(l)
                lightPosArr[idx * 4] = gt.gx; lightPosArr[idx * 4 + 1] = gt.gy
                lightPosArr[idx * 4 + 2] = gt.gz; lightPosArr[idx * 4 + 3] = l.lightRadius
                lightColArr[idx * 4] = l.r * l.lightIntensity; lightColArr[idx * 4 + 1] = l.g * l.lightIntensity
                lightColArr[idx * 4 + 2] = l.b * l.lightIntensity; lightColArr[idx * 4 + 3] = 1f
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, lights.size, lightPosArr, lightColArr) { frameDrawCalls++ }
            if (lights.isNotEmpty() && casters.isNotEmpty()) {
                for (caster in casters) {
                    val cg = getGlobalTransform(caster)
                    val pad = max(caster.width * abs(cg.gSx), caster.height * abs(cg.gSy)) * 1.6f
                    if (isBoxInView(cg.gx - pad, cg.gx + pad, cg.gy - pad, cg.gy + pad)) {
                        submitDynamicShadowToBatch(caster, lights)
                    }
                }
            }

            for (n in worldNodes) {
                when {
                    n.nodeType == "ParticleEmitter" -> {
                        spriteBatch.setInlineLight(false)
                        submitParticlesToBatch(n, isWorld = true, fadeAlpha = 1f)
                    }
                    n.nodeType == "TileMap" -> {
                        spriteBatch.flush()
                        renderTileMap(n, vpMatrix, isWorld = true, enableInlineLight = true, lights.size, lightPosArr, lightColArr)
                    }
                    canBatchNode(n) -> {
                        spriteBatch.setInlineLight(true)
                        submitNodeToBatch(n, isWorld = true)
                    }
                    else -> {
                        spriteBatch.flush()
                        renderNodeSingle(n, vpMatrix, isWorld = true, enableInlineLight = true, lights.size, lightPosArr, lightColArr)
                    }
                }
            }
            spriteBatch.end()
        }

        if (debugDrawEnabled) renderDebugOverlay(vpMatrix)

        val emptyUiArr = FloatArray(4)
        val uiNodes = nodes.filter { it.visible && it.layer == "UI" }.sortedBy { it.zIndex }
        if (uiNodes.isNotEmpty()) {
            spriteBatch.begin(uiVpMatrix, false, 1f, 1f, 1f, 0, null, null) { frameDrawCalls++ }
            for (n in uiNodes) {
                val clipRect = resolveUiClipRect(n)
                if (clipRect != null) { spriteBatch.flush(); applyUiScissor(clipRect) }
                when {
                    n.nodeType == "ParticleEmitter" -> submitParticlesToBatch(n, isWorld = false, fadeAlpha = 1f)
                    canBatchNode(n) -> submitNodeToBatch(n, isWorld = false)
                    else -> {
                        spriteBatch.flush()
                        renderNodeSingle(n, uiVpMatrix, isWorld = false, enableInlineLight = false, 0, emptyUiArr, emptyUiArr)
                    }
                }
                if (clipRect != null) { spriteBatch.flush(); GLES20.glDisable(GLES20.GL_SCISSOR_TEST) }
            }
            spriteBatch.end()
        }

        if (debugDrawEnabled) renderTouchZonesDebugOverlay(uiVpMatrix)

        lastFrameDrawCalls = frameDrawCalls
        lastFrameCulled = frameCulled
    }

    private fun canBatchNode(n: GameNode): Boolean =
        n.shader.isBlank() && n.slopeType != "left_up" && n.slopeType != "right_up"

    private fun submitNodeToBatch(n: GameNode, isWorld: Boolean) {
        val gt = getGlobalTransform(n)
        val w = n.width * gt.gSx
        val h = n.height * gt.gSy

        // P7：叠加当前动画帧的重心偏移（自动跟随 scaleX 镜像）
        val af = n.animator.activeFrame
        val renderX = gt.gx + (af?.offsetX ?: 0f) * gt.gSx
        val renderY = gt.gy + (af?.offsetY ?: 0f) * abs(gt.gSy)

        if (isWorld) {
            val boundHalf = max(abs(w), abs(h)) * 0.75f + abs(gt.gz)
            if (!isBoxInView(renderX - boundHalf, renderX + boundHalf, renderY - boundHalf, renderY + boundHalf)) {
                frameCulled++
                return
            }
        }

        val atlasData = if (n.atlas.isNotEmpty()) loadAtlasData(n.atlas) else null
        val atlasFrame = atlasData?.frames?.get(n.atlasFrame)

        val u0: Float; val u1: Float; val v0: Float; val v1: Float
        val texId: Int; val subTexW: Float; val subTexH: Float

        if (atlasData != null && atlasFrame != null && atlasFrame.w > 0f && atlasFrame.h > 0f) {
            texId = getImageTexture(atlasData.imageFile)
            u0 = atlasFrame.x / atlasData.atlasW
            u1 = (atlasFrame.x + atlasFrame.w) / atlasData.atlasW
            v0 = atlasFrame.y / atlasData.atlasH
            v1 = (atlasFrame.y + atlasFrame.h) / atlasData.atlasH
            subTexW = atlasFrame.w
            subTexH = atlasFrame.h
        } else {
            val hf = max(1, n.hFrames)
            val vf = max(1, n.vFrames)
            val fi = n.currentFrame.coerceIn(0, hf * vf - 1)
            val uScale = 1f / hf
            val vScale = 1f / vf
            if (isWorld && camMode == "2.5D" && n.nodeType == "Label2D") {
                u0 = 1f; u1 = 0f
            } else {
                u0 = (fi % hf) * uScale; u1 = u0 + uScale
            }
            v0 = (fi / hf) * vScale; v1 = v0 + vScale
            texId = when {
                n.nodeType == "Label2D" -> getTextTexture(n.text, n.fontSize)
                n.texture.isNotEmpty() -> getImageTexture(n.texture)
                else -> whiteTexId
            }
            val texDim = textureSizes[n.texture]
            subTexW = max(1f, (texDim?.first ?: max(1f, abs(w)).toInt()).toFloat()) / hf
            subTexH = max(1f, (texDim?.second ?: max(1f, abs(h)).toInt()).toFloat()) / vf
        }

        val flashVal = if (n.flashTimer > 0f) (n.flashTimer * 8f).coerceIn(0f, 1f) else 0f
        val isNineSlice = n.nodeType == "Sprite2D" &&
            (n.sliceLeft > 0f || n.sliceRight > 0f || n.sliceTop > 0f || n.sliceBottom > 0f)

        if (!isNineSlice) {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, renderX, renderY, gt.gz)
            if (isWorld && camMode == "2.5D" && n.billboard) {
                Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
            } else if (isWorld && n.tiltX != 0f) {
                Matrix.rotateM(modelMatrix, 0, n.tiltX, 1f, 0f, 0f)
            }
            if (gt.gRot != 0f) Matrix.rotateM(modelMatrix, 0, gt.gRot, 0f, 0f, 1f)
            Matrix.scaleM(modelMatrix, 0, w, h, 1f)
            spriteBatch.drawQuad(texId, modelMatrix, u0, v0, u1, v1, n.r, n.g, n.b, n.a, flashVal, additive = false)
            return
        }

        val absW = max(1f, abs(w))
        val absH = max(1f, abs(h))
        val signX = if (w < 0f) -1f else 1f
        val signY = if (h < 0f) -1f else 1f

        val sl = max(0f, n.sliceLeft); val sr = max(0f, n.sliceRight)
        val st = max(0f, n.sliceTop); val sb = max(0f, n.sliceBottom)
        val scaleClampX = if (sl + sr > absW) absW / (sl + sr) else 1f
        val scaleClampY = if (st + sb > absH) absH / (st + sb) else 1f
        val xs = floatArrayOf(-absW * 0.5f, -absW * 0.5f + sl * scaleClampX, absW * 0.5f - sr * scaleClampX, absW * 0.5f)
        val ys = floatArrayOf(-absH * 0.5f, -absH * 0.5f + st * scaleClampY, absH * 0.5f - sb * scaleClampY, absH * 0.5f)

        val du = u1 - u0; val dv = v1 - v0
        val us = floatArrayOf(u0, u0 + (sl / subTexW).coerceIn(0f, 0.49f) * du, u1 - (sr / subTexW).coerceIn(0f, 0.49f) * du, u1)
        val vs = floatArrayOf(v0, v0 + (st / subTexH).coerceIn(0f, 0.49f) * dv, v1 - (sb / subTexH).coerceIn(0f, 0.49f) * dv, v1)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, renderX, renderY, gt.gz)
        if (isWorld && camMode == "2.5D" && n.billboard) {
            Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
        } else if (isWorld && n.tiltX != 0f) {
            Matrix.rotateM(modelMatrix, 0, n.tiltX, 1f, 0f, 0f)
        }
        if (gt.gRot != 0f) Matrix.rotateM(modelMatrix, 0, gt.gRot, 0f, 0f, 1f)

        for (row in 0..2) {
            val subH = ys[row + 1] - ys[row]
            if (subH <= 0.01f) continue
            val subCy = (ys[row] + ys[row + 1]) * 0.5f
            for (col in 0..2) {
                val subW = xs[col + 1] - xs[col]
                if (subW <= 0.01f) continue
                val subCx = (xs[col] + xs[col + 1]) * 0.5f
                System.arraycopy(modelMatrix, 0, subModelMatrix, 0, 16)
                Matrix.translateM(subModelMatrix, 0, subCx * signX, subCy * signY, 0f)
                Matrix.scaleM(subModelMatrix, 0, subW * signX, subH * signY, 1f)
                spriteBatch.drawQuad(texId, subModelMatrix, us[col], vs[row], us[col + 1], vs[row + 1], n.r, n.g, n.b, n.a, flashVal, additive = false)
            }
        }
    }

    private fun submitDynamicShadowToBatch(caster: GameNode, lights: List<GameNode>) {
        val cg = getGlobalTransform(caster)
        val cw = caster.width * abs(cg.gSx)
        val ch = caster.height * abs(cg.gSy)
        for (light in lights) {
            val lg = getGlobalTransform(light)
            val dx = cg.gx - lg.gx; val dy = cg.gy - lg.gy
            val dist = hypot(dx, dy)
            if (dist < 1f || dist > light.lightRadius) continue
            val nx = dx / dist; val ny = dy / dist
            val atten = (1f - dist / light.lightRadius).coerceIn(0f, 1f)
            val sLen = max(cw, ch) * 1.15f * atten
            val sx = cg.gx + nx * (sLen * 0.5f + cw * 0.35f)
            val sy = cg.gy + ny * (sLen * 0.5f + ch * 0.35f)
            val angleDeg = Math.toDegrees(atan2(ny.toDouble(), nx.toDouble())).toFloat() - 90f

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, sx, sy, cg.gz - 0.5f)
            Matrix.rotateM(modelMatrix, 0, angleDeg, 0f, 0f, 1f)
            Matrix.scaleM(modelMatrix, 0, cw * 0.9f, sLen, 1f)
            spriteBatch.drawQuad(whiteTexId, modelMatrix, 0f, 0f, 1f, 1f, 0.02f, 0.03f, 0.06f, 0.52f * atten, 0f, additive = false)
        }
    }

    private fun submitParticlesToBatch(n: GameNode, isWorld: Boolean, fadeAlpha: Float) {
        if (fadeAlpha <= 0.001f) return
        val gt = getGlobalTransform(n)
        val texId = if (n.texture.isNotEmpty()) getImageTexture(n.texture) else whiteTexId

        for (p in n.particles) {
            if (!p.active) continue
            if (isWorld && !isBoxInView(p.x - p.size, p.x + p.size, p.y - p.size, p.y + p.size)) {
                frameCulled++; continue
            }
            val ratio = (p.life / p.maxLife).coerceIn(0f, 1f)
            val curSize = p.size * (0.25f + 0.75f * ratio)
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, p.x, p.y, gt.gz + 4f)
            if (isWorld && camMode == "2.5D") Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
            Matrix.scaleM(modelMatrix, 0, curSize, curSize, 1f)
            spriteBatch.drawQuad(texId, modelMatrix, 0f, 0f, 1f, 1f, n.r, n.g, n.b, p.alpha * ratio * fadeAlpha, 0f, additive = true)
        }
    }

    private fun renderLightsToFbo(lights: List<GameNode>) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, lightFbo)
        GLES20.glViewport(0, 0, lightW, lightH)
        GLES20.glClearColor(ambientR, ambientG, ambientB, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (lights.isEmpty()) return

        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(lightProgram)

        val posLoc = GLES20.glGetAttribLocation(lightProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(lightProgram, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(lightProgram, "uMVP")
        val modelLoc = GLES20.glGetUniformLocation(lightProgram, "uModel")
        val uvRectLoc = GLES20.glGetUniformLocation(lightProgram, "uUvRect")
        val colLoc = GLES20.glGetUniformLocation(lightProgram, "uColor")

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, quadBuffer)
        quadBuffer.position(3)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, quadBuffer)
        GLES20.glUniform4f(uvRectLoc, 0f, 0f, 1f, 1f)

        for (l in lights) {
            val gt = getGlobalTransform(l)
            val rad = max(8f, l.lightRadius)
            if (!isBoxInView(gt.gx - rad, gt.gx + rad, gt.gy - rad, gt.gy + rad)) { frameCulled++; continue }
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, gt.gx, gt.gy, gt.gz)
            Matrix.scaleM(modelMatrix, 0, rad * 2f, rad * 2f, 1f)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(modelLoc, 1, false, modelMatrix, 0)
            GLES20.glUniform4f(colLoc, l.r * l.lightIntensity, l.g * l.lightIntensity, l.b * l.lightIntensity, 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            frameDrawCalls++
        }
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun renderPostComposite() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(postProgram)

        val posLoc = GLES20.glGetAttribLocation(postProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(postProgram, "aTexCoord")
        fullScreenQuadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, fullScreenQuadBuffer)
        fullScreenQuadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, fullScreenQuadBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneColorTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uSceneTex"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lightMapTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uLightTex"), 1)

        GLES20.glUniform2f(GLES20.glGetUniformLocation(postProgram, "uTexelSize"), 1f / surfaceW, 1f / surfaceH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uBloom"), postBloom)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uVignette"), postVignette)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uChromatic"), postChromatic)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uFade"), postFade)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        frameDrawCalls++

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glEnable(GLES20.GL_BLEND)
    }

    private fun drawDebugRect(posLoc: Int, uvLoc: Int, colLoc: Int, minX: Float, minY: Float, maxX: Float, maxY: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
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

    private fun drawDebugSlope(posLoc: Int, uvLoc: Int, colLoc: Int, minX: Float, minY: Float, maxX: Float, maxY: Float, z: Float, leftUp: Boolean) {
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

    private fun drawDebugLine(posLoc: Int, uvLoc: Int, colLoc: Int, x1: Float, y1: Float, x2: Float, y2: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
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
            val gt = getGlobalTransform(n)

            // P7：绘制帧同步 Hitbox / Hurtbox 调试框
            val activeBoxes = n.animator.activeFrame?.boxes
            if (!activeBoxes.isNullOrEmpty()) {
                for (box in activeBoxes) {
                    val wb = getFrameBoxWorldBounds(n, box)
                    if (box.type == "hitbox") {
                        drawDebugRect(posLoc, uvLoc, colLoc, wb.cx - wb.hw, wb.cy - wb.hh, wb.cx + wb.hw, wb.cy + wb.hh, gt.gz + 9f, 1.0f, 0.15f, 0.15f, 0.95f)
                    } else {
                        drawDebugRect(posLoc, uvLoc, colLoc, wb.cx - wb.hw, wb.cy - wb.hh, wb.cx + wb.hw, wb.cy + wb.hh, gt.gz + 8f, 0.15f, 1.0f, 0.6f, 0.95f)
                    }
                }
            }

            if (!n.collider && !n.isHitbox && !n.isHurtbox) continue
            if (n.nodeType == "TileMap" && n.collider) {
                val ts = max(8f, n.tileSize)
                for (cell in n.tiles) {
                    val x0 = gt.gx + cell.gx * ts; val y0 = gt.gy + cell.gy * ts
                    if (!isBoxInView(x0, x0 + ts, y0, y0 + ts)) continue
                    drawDebugRect(posLoc, uvLoc, colLoc, x0, y0, x0 + ts, y0 + ts, gt.gz + 6f, 0.2f, 0.95f, 0.45f, 0.5f)
                }
                continue
            }

            val cb = getColliderBounds(n)
            val minX = cb.cx - cb.hw; val maxX = cb.cx + cb.hw
            val minY = cb.cy - cb.hh; val maxY = cb.cy + cb.hh
            if (!isBoxInView(minX, maxX, minY, maxY)) continue

            if (n.collider) {
                when (n.slopeType) {
                    "left_up" -> drawDebugSlope(posLoc, uvLoc, colLoc, minX, minY, maxX, maxY, gt.gz + 6f, leftUp = true)
                    "right_up" -> drawDebugSlope(posLoc, uvLoc, colLoc, minX, minY, maxX, maxY, gt.gz + 6f, leftUp = false)
                    else -> {
                        if (n.isTrigger) drawDebugRect(posLoc, uvLoc, colLoc, minX, minY, maxX, maxY, gt.gz + 6f, 0.1f, 0.9f, 1.0f, 0.9f)
                        else drawDebugRect(posLoc, uvLoc, colLoc, minX, minY, maxX, maxY, gt.gz + 6f, 0.2f, 1.0f, 0.35f, 0.9f)
                    }
                }
            }
            if (n.isHurtbox) drawDebugRect(posLoc, uvLoc, colLoc, minX + 3f, minY + 3f, maxX - 3f, maxY - 3f, gt.gz + 7f, 0.3f, 0.55f, 1.0f, 0.95f)
            if (n.isHitbox) drawDebugRect(posLoc, uvLoc, colLoc, minX - 2f, minY - 2f, maxX + 2f, maxY + 2f, gt.gz + 8f, 1.0f, 0.2f, 0.2f, 0.95f)
        }

        for (ray in debugRays) {
            if (ray.hit) drawDebugLine(posLoc, uvLoc, colLoc, ray.x1, ray.y1, ray.x2, ray.y2, 10f, 1.0f, 0.25f, 0.85f, 0.95f)
            else drawDebugLine(posLoc, uvLoc, colLoc, ray.x1, ray.y1, ray.x2, ray.y2, 10f, 1.0f, 0.9f, 0.2f, 0.85f)
        }

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun renderTouchZonesDebugOverlay(uiVp: FloatArray) {
        val zones = inputSystem.getTouchZonesSnapshot()
        if (zones.isEmpty()) return

        val prog = defaultLitProgram
        GLES20.glUseProgram(prog)
        GLES20.glLineWidth(3f)

        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        val colLoc = GLES20.glGetUniformLocation(prog, "uColor")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, uiVp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, identityMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), 0f)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)

        for (z in zones) {
            val active = inputSystem.isActionPressed(z.action)
            val hw = z.w * 0.5f; val hh = z.h * 0.5f
            if (active) drawDebugRect(posLoc, uvLoc, colLoc, z.cx - hw, z.cy - hh, z.cx + hw, z.cy + hh, 20f, 1.0f, 0.85f, 0.1f, 0.95f)
            else drawDebugRect(posLoc, uvLoc, colLoc, z.cx - hw, z.cy - hh, z.cx + hw, z.cy + hh, 20f, 0.2f, 0.85f, 1.0f, 0.55f)
        }

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    // ==================== P7 升级：节点 Hitbox + 帧同步 FrameBox 联合战斗判定 ====================
    private fun overlapBounds(a: ColliderBounds, b: ColliderBounds): Boolean =
        abs(a.cx - b.cx) < (a.hw + b.hw) && abs(a.cy - b.cy) < (a.hh + b.hh)

    private fun triggerHurtCallback(attacker: GameNode, hurt: GameNode, dmg: Float, kbX: Float, kbY: Float) {
        val scope = hurt.jsObj?.prototype ?: return
        val fn = ScriptableObject.getProperty(scope, "onHurt") as? Function ?: return
        try {
            fn.call(rhino, scope, hurt.jsObj, arrayOf(attacker.jsObj, dmg.toDouble(), kbX.toDouble(), kbY.toDouble()))
        } catch (e: Exception) {
            onConsoleLog("[JS 运行错误 @ ${hurt.name}.onHurt] ${e.message}")
        }
    }

    private fun processCombatOverlaps() {
        // 1. 兼容旧版独立 Hitbox 节点
        val hitboxes = nodes.filter { it.visible && it.isHitbox && !pendingRemove.contains(it) }
        for (hb in hitboxes) {
            val cb = getColliderBounds(hb)
            querySpatialCandidates(cb.cx - cb.hw, cb.cx + cb.hw, cb.cy - cb.hh, cb.cy + cb.hh, queryCandidates)
            for (hurt in queryCandidates) {
                if (!hurt.visible || !hurt.isHurtbox || pendingRemove.contains(hurt)) continue
                if (hb === hurt || hb.parent == hurt.name || hurt.parent == hb.name) continue
                if (!canCollideLayer(hb, hurt) || hurt.invulnTimer > 0f) continue
                if (hb.hitCooldownMap.containsKey(hurt.name)) continue
                if (checkOverlap(hb, hurt)) {
                    hb.hitCooldownMap[hurt.name] = 0.25f
                    callLifecycle(hb, "onHit", hurt.jsObj)
                    triggerHurtCallback(hb, hurt, hb.damage, 0f, 0f)
                }
            }
        }

        // 2. P7 帧同步动画 FrameBox 判定（支持自动击退与顿帧）
        for (attacker in nodes) {
            if (!attacker.visible || pendingRemove.contains(attacker)) continue
            val boxes = attacker.animator.activeFrame?.boxes ?: continue
            if (boxes.isEmpty()) continue
            val dirX = if (getGlobalTransform(attacker).gSx < 0f) -1f else 1f

            for (box in boxes) {
                if (box.type != "hitbox") continue
                val hbBounds = getFrameBoxWorldBounds(attacker, box)
                querySpatialCandidates(hbBounds.cx - hbBounds.hw, hbBounds.cx + hbBounds.hw, hbBounds.cy - hbBounds.hh, hbBounds.cy + hbBounds.hh, queryCandidates)
                for (hurt in queryCandidates) {
                    if (!hurt.visible || pendingRemove.contains(hurt)) continue
                    if (attacker === hurt || attacker.parent == hurt.name || hurt.parent == attacker.name) continue
                    if (!canCollideLayer(attacker, hurt) || hurt.invulnTimer > 0f) continue

                    val cdKey = "${box.id}@${hurt.name}"
                    if (attacker.hitCooldownMap.containsKey(cdKey)) continue

                    val hurtBoxes = hurt.animator.activeFrame?.boxes?.filter { it.type == "hurtbox" }
                    val isHit = if (!hurtBoxes.isNullOrEmpty()) {
                        hurtBoxes.any { overlapBounds(hbBounds, getFrameBoxWorldBounds(hurt, it)) }
                    } else if (hurt.isHurtbox) {
                        overlapBounds(hbBounds, getColliderBounds(hurt))
                    } else false

                    if (isHit) {
                        attacker.hitCooldownMap[cdKey] = 0.22f
                        if (box.hitstop > 0f) hitstopTimer = max(hitstopTimer, box.hitstop.coerceIn(0f, 0.35f))
                        val kbX = box.knockbackX * dirX
                        val kbY = box.knockbackY
                        if (hurt.bodyMode != "kinematic" && (kbX != 0f || kbY != 0f)) {
                            hurt.vx = kbX
                            hurt.vy = kbY
                            hurt.isGrounded = false
                        }
                        callLifecycle(attacker, "onHit", hurt.jsObj)
                        triggerHurtCallback(attacker, hurt, box.damage, kbX, kbY)
                    }
                }
            }
        }
    }

    private fun buildTileMapVbo(n: GameNode) {
        if (n.tileVboId != 0) { GLES20.glDeleteBuffers(1, intArrayOf(n.tileVboId), 0); n.tileVboId = 0 }
        n.tileDirty = false
        n.tileLookup.clear()
        if (n.tiles.isEmpty()) { n.tileVertexCount = 0; return }

        val ts = max(8f, n.tileSize)
        val hf = max(1, n.hFrames); val vf = max(1, n.vFrames)
        val uScale = 1f / hf; val vScale = 1f / vf
        val data = FloatArray(n.tiles.size * 30)
        var offset = 0
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY

        for (cell in n.tiles) {
            n.tileLookup[packGridKey(cell.gx, cell.gy)] = cell
            val x0 = cell.gx * ts; val x1 = x0 + ts
            val y0 = cell.gy * ts; val y1 = y0 + ts
            if (x0 < minX) minX = x0; if (x1 > maxX) maxX = x1
            if (y0 < minY) minY = y0; if (y1 > maxY) maxY = y1

            val fi = cell.frame.coerceIn(0, hf * vf - 1)
            val u0 = (fi % hf) * uScale; val v0 = (fi / hf) * vScale
            val u1 = u0 + uScale; val v1 = v0 + vScale

            data[offset++] = x0; data[offset++] = y0; data[offset++] = 0f; data[offset++] = u0; data[offset++] = v0
            data[offset++] = x1; data[offset++] = y0; data[offset++] = 0f; data[offset++] = u1; data[offset++] = v0
            data[offset++] = x0; data[offset++] = y1; data[offset++] = 0f; data[offset++] = u0; data[offset++] = v1
            data[offset++] = x1; data[offset++] = y0; data[offset++] = 0f; data[offset++] = u1; data[offset++] = v0
            data[offset++] = x1; data[offset++] = y1; data[offset++] = 0f; data[offset++] = u1; data[offset++] = v1
            data[offset++] = x0; data[offset++] = y1; data[offset++] = 0f; data[offset++] = u0; data[offset++] = v1
        }

        n.tileMinX = minX; n.tileMaxX = maxX; n.tileMinY = minY; n.tileMaxY = maxY
        n.tileVertexCount = n.tiles.size * 6

        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data); buf.position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        n.tileVboId = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, n.tileVboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun renderTileMap(n: GameNode, activeVp: FloatArray, isWorld: Boolean, enableInlineLight: Boolean, lightCount: Int, lightPos: FloatArray, lightCol: FloatArray) {
        if (n.tiles.isEmpty()) return
        if (n.tileDirty || n.tileVboId == 0) buildTileMapVbo(n)
        if (n.tileVboId == 0 || n.tileVertexCount == 0) return

        val gt = getGlobalTransform(n)
        if (isWorld && !isBoxInView(gt.gx + n.tileMinX, gt.gx + n.tileMaxX, gt.gy + n.tileMinY, gt.gy + n.tileMaxY)) {
            frameCulled++; return
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
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), if (enableInlineLight) 1 else 0)
        if (enableInlineLight) {
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uAmbient"), ambientR, ambientG, ambientB)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLightCount"), lightCount)
            if (lightCount > 0) {
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightPos"), lightCount, lightPos, 0)
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightColor"), lightCount, lightCol, 0)
            }
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
            while (n.emitAccum >= 1f) { n.emitAccum -= 1f; spawnSingleParticle(n, gt.gx, gt.gy) }
        }
        for (p in n.particles) {
            if (!p.active) continue
            p.life -= dt
            if (p.life <= 0f) { p.active = false; continue }
            p.vy += n.particleGravity * dt
            p.x += p.vx * dt; p.y += p.vy * dt
        }
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
            applyTweenProperty(t.node, t.prop, t.startVal + (t.targetVal - t.startVal) * easedP)
            if (rawP >= 1f) iter.remove()
        }
    }

    private fun applyTweenProperty(n: GameNode, prop: String, v: Float) {
        when (prop) {
            "x" -> n.x = v; "y" -> n.y = v; "z" -> n.z = v
            "scaleX" -> n.scaleX = v; "scaleY" -> n.scaleY = v
            "rotation" -> n.rotation = v; "a" -> n.a = v.coerceIn(0f, 1f)
            "lightIntensity" -> n.lightIntensity = v
        }
    }

    private fun readTweenProperty(n: GameNode, prop: String): Float = when (prop) {
        "x" -> n.x; "y" -> n.y; "z" -> n.z
        "scaleX" -> n.scaleX; "scaleY" -> n.scaleY
        "rotation" -> n.rotation; "a" -> n.a
        "lightIntensity" -> n.lightIntensity
        else -> 0f
    }

    private fun unprojectCornerZ0(ndcX: Float, ndcY: Float, outXY: FloatArray): Boolean {
        val nearW = FloatArray(4); val farW = FloatArray(4)
        Matrix.multiplyMV(nearW, 0, invVpMatrix, 0, floatArrayOf(ndcX, ndcY, -1f, 1f), 0)
        Matrix.multiplyMV(farW, 0, invVpMatrix, 0, floatArrayOf(ndcX, ndcY, 1f, 1f), 0)
        if (abs(nearW[3]) < 1e-5f || abs(farW[3]) < 1e-5f) return false
        for (i in 0..2) { nearW[i] /= nearW[3]; farW[i] /= farW[3] }
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
            val dx = (gt.gx + lookX) - camX
            val dy = (gt.gy + camLookAheadY) - camY
            if (abs(dx) > camDeadzoneX) camX += (dx - sign(dx) * camDeadzoneX) * min(1f, camSmoothSpeed * dt)
            if (abs(dy) > camDeadzoneY) camY += (dy - sign(dy) * camDeadzoneY) * min(1f, camSmoothSpeed * dt)
        }
        if (camBoundsEnabled) {
            camX = camX.coerceIn(camMinX, max(camMinX, camMaxX))
            camY = camY.coerceIn(camMinY, max(camMinY, camMaxY))
        }
        var sx = 0f; var sy = 0f
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
            Matrix.setLookAtM(viewMatrix, 0, camX + sx, eyeY, eyeZ, camX + sx, camY + sy, 0f, 0f, -1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)

            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
            val pt = FloatArray(2)
            var valid = 0
            for ((cx, cy) in arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                if (unprojectCornerZ0(cx, cy, pt)) {
                    if (pt[0] < minX) minX = pt[0]; if (pt[0] > maxX) maxX = pt[0]
                    if (pt[1] < minY) minY = pt[1]; if (pt[1] > maxY) maxY = pt[1]
                    valid++
                }
            }
            if (valid == 4) {
                viewMinX = minX - 400f; viewMaxX = maxX + 400f
                viewMinY = minY - 400f; viewMaxY = maxY + 400f
            } else {
                viewMinX = -999999f; viewMaxX = 999999f; viewMinY = -999999f; viewMaxY = 999999f
            }
        } else {
            val halfH = 960f; val halfW = halfH * aspect
            Matrix.orthoM(projMatrix, 0, -halfW, halfW, halfH, -halfH, -2000f, 2000f)
            Matrix.setLookAtM(viewMatrix, 0, camX + sx, camY + sy, 500f, camX + sx, camY + sy, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
            Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)
            viewMinX = camX + sx - halfW - 250f; viewMaxX = camX + sx + halfW + 250f
            viewMinY = camY + sy - halfH - 250f; viewMaxY = camY + sy + halfH + 250f
        }
    }

    private fun updateWorldTouchFromScreen() {
        val ndcX = (touchScreenX / 1080f) * 2f - 1f
        val ndcY = 1f - (touchScreenY / 1920f) * 2f
        val pt = FloatArray(2)
        if (unprojectCornerZ0(ndcX, ndcY, pt)) { touchWorldX = pt[0]; touchWorldY = pt[1] }
    }

    private fun renderNodeSingle(n: GameNode, activeVp: FloatArray, isWorld: Boolean, enableInlineLight: Boolean, lightCount: Int, lightPos: FloatArray, lightCol: FloatArray) {
        val gt = getGlobalTransform(n)
        val w = n.width * gt.gSx; val h = n.height * gt.gSy
        val af = n.animator.activeFrame
        val renderX = gt.gx + (af?.offsetX ?: 0f) * gt.gSx
        val renderY = gt.gy + (af?.offsetY ?: 0f) * abs(gt.gSy)

        if (isWorld) {
            val boundHalf = max(abs(w), abs(h)) * 0.75f + abs(gt.gz)
            if (!isBoxInView(renderX - boundHalf, renderX + boundHalf, renderY - boundHalf, renderY + boundHalf)) {
                frameCulled++; return
            }
        }

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, renderX, renderY, gt.gz)
        if (isWorld && camMode == "2.5D" && n.billboard) Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
        else if (isWorld && n.tiltX != 0f) Matrix.rotateM(modelMatrix, 0, n.tiltX, 1f, 0f, 0f)
        if (gt.gRot != 0f) Matrix.rotateM(modelMatrix, 0, gt.gRot, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, w, h, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, activeVp, 0, modelMatrix, 0)

        val prog = getProgramForNode(n)
        GLES20.glUseProgram(prog)

        val activeBuf = when (n.slopeType) {
            "left_up" -> slopeLeftUpBuffer; "right_up" -> slopeRightUpBuffer; else -> quadBuffer
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

        val atlasData = if (n.atlas.isNotEmpty()) loadAtlasData(n.atlas) else null
        val atlasFrame = atlasData?.frames?.get(n.atlasFrame)
        val texId: Int
        if (atlasData != null && atlasFrame != null && atlasFrame.w > 0f && atlasFrame.h > 0f) {
            texId = getImageTexture(atlasData.imageFile)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"),
                atlasFrame.x / atlasData.atlasW, atlasFrame.y / atlasData.atlasH,
                atlasFrame.w / atlasData.atlasW, atlasFrame.h / atlasData.atlasH)
        } else {
            val hf = max(1, n.hFrames); val vf = max(1, n.vFrames)
            val fi = n.currentFrame.coerceIn(0, hf * vf - 1)
            val uScale = 1f / hf; val vScale = 1f / vf
            val uOffset = (fi % hf) * uScale; val vOffset = (fi / hf) * vScale
            if (isWorld && camMode == "2.5D" && n.nodeType == "Label2D") {
                GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 1f, vOffset, -1f, vScale)
            } else {
                GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), uOffset, vOffset, uScale, vScale)
            }
            texId = when {
                n.nodeType == "Label2D" -> getTextTexture(n.text, n.fontSize)
                n.texture.isNotEmpty() -> getImageTexture(n.texture)
                else -> whiteTexId
            }
        }

        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), if (enableInlineLight) 1 else 0)
        if (enableInlineLight) {
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uAmbient"), ambientR, ambientG, ambientB)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLightCount"), lightCount)
            if (lightCount > 0) {
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightPos"), lightCount, lightPos, 0)
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightColor"), lightCount, lightCol, 0)
            }
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
            clip?.events?.get(n.currentFrame)?.takeIf { it.isNotEmpty() }?.let { callFrameEvent(n, n.currentClipName, it) }
            if (n.currentFrame > n.animEnd) {
                if (n.animLoop) n.currentFrame = n.animStart
                else {
                    n.currentFrame = n.animEnd; n.animPlaying = false
                    val finishedClip = n.currentClipName; n.currentClipName = ""
                    if (finishedClip.isNotEmpty()) callLifecycle(n, "onAnimFinished", finishedClip)
                }
            }
        }
    }

    private fun performRaycast(x1: Float, y1: Float, x2: Float, y2: Float, ignoreTag: String, mask: Int): Scriptable {
        var closestT = 1.0f
        var hitNode: GameNode? = null
        val dx = x2 - x1; val dy = y2 - y1
        val rMinX = min(x1, x2); val rMaxX = max(x1, x2)
        val rMinY = min(y1, y2); val rMaxY = max(y1, y2)

        querySpatialCandidates(rMinX, rMaxX, rMinY, rMaxY, queryCandidates)
        for (other in queryCandidates) {
            if (!other.visible || !other.collider || other.isTrigger || other.layer != "World") continue
            if (ignoreTag.isNotEmpty() && other.tag == ignoreTag) continue
            if ((mask and other.colLayer) == 0) continue

            if (other.nodeType == "TileMap") {
                val gb = getGlobalTransform(other)
                val ts = max(8f, other.tileSize)
                for (gx in floor((rMinX - gb.gx) / ts).toInt()..floor((rMaxX - gb.gx) / ts).toInt()) {
                    for (gy in floor((rMinY - gb.gy) / ts).toInt()..floor((rMaxY - gb.gy) / ts).toInt()) {
                        if (!other.tileLookup.containsKey(packGridKey(gx, gy))) continue
                        val minX = gb.gx + gx * ts; val minY = gb.gy + gy * ts
                        val t = intersectSegmentAABB(x1, y1, dx, dy, minX, minY, minX + ts, minY + ts)
                        if (t != null && t < closestT) { closestT = t; hitNode = other }
                    }
                }
            } else {
                val cb = getColliderBounds(other)
                val t = intersectSegmentAABB(x1, y1, dx, dy, cb.cx - cb.hw, cb.cy - cb.hh, cb.cx + cb.hw, cb.cy + cb.hh)
                if (t != null && t < closestT) { closestT = t; hitNode = other }
            }
        }

        val res = rhino.newObject(globalScope)
        if (hitNode != null) {
            val hx = x1 + dx * closestT; val hy = y1 + dy * closestT
            if (debugDrawEnabled) debugRays.add(DebugRay(x1, y1, hx, hy, hit = true))
            ScriptableObject.putProperty(res, "hit", true)
            ScriptableObject.putProperty(res, "x", hx.toDouble())
            ScriptableObject.putProperty(res, "y", hy.toDouble())
            ScriptableObject.putProperty(res, "distance", hypot(hx - x1, hy - y1).toDouble())
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

    private fun intersectSegmentAABB(x1: Float, y1: Float, dx: Float, dy: Float, minX: Float, minY: Float, maxX: Float, maxY: Float): Float? {
        var t0 = 0f; var t1 = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x1 - minX, maxX - x1, y1 - minY, maxY - y1)
        for (i in 0..3) {
            if (abs(p[i]) < 1e-6f) { if (q[i] < 0f) return null }
            else {
                val r = q[i] / p[i]
                if (p[i] < 0f) { if (r > t1) return null; if (r > t0) t0 = r }
                else { if (r < t0) return null; if (r < t1) t1 = r }
            }
        }
        return if (t0 <= t1) t0 else null
    }

    private fun saveDiskSlot(slotName: String, dataObj: Any?): Boolean = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val savesDir = File(projectDir, "saves").apply { mkdirs() }
        val jsonStr = when (dataObj) {
            is String -> dataObj; null -> "{}"
            else -> RhinoContext.toString(NativeJSON.stringify(rhino, globalScope, dataObj, null, null))
        }
        File(savesDir, "$safeName.json").writeText(jsonStr)
        true
    } catch (_: Exception) { false }

    private fun loadDiskSlot(slotName: String): Any? = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val file = File(File(projectDir, "saves"), "$safeName.json")
        if (file.exists()) parseJsonStringToJs(file.readText()) else null
    } catch (_: Exception) { null }

    private fun deleteDiskSlot(slotName: String): Boolean = try {
        val safeName = slotName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val file = File(File(projectDir, "saves"), "$safeName.json")
        file.exists() && file.delete()
    } catch (_: Exception) { false }

    // ==================== Rhino JS SDK 绑定（P0～P7） ====================
    private fun initRhinoVM() {
        rhino = RhinoContext.enter()
        rhino.optimizationLevel = -1
        globalScope = rhino.initStandardObjects()
        moduleCache.clear(); dataTableCache.clear(); atlasCache.clear()

        ScriptableObject.putProperty(globalScope, "require", jsFunc { args ->
            requireScriptModule(RhinoContext.toString(args.getOrNull(0) ?: ""))
        })

        val engineApi = rhino.newObject(globalScope)
        ScriptableObject.putProperty(engineApi, "log", jsFunc { args ->
            onConsoleLog("[LOG] ${args.joinToString(" ") { RhinoContext.toString(it) }}"); null
        })
        ScriptableObject.putProperty(engineApi, "unloadUnusedTextures", jsFunc { unloadUnusedTextures() })
        ScriptableObject.putProperty(engineApi, "clearModuleCache", jsFunc { moduleCache.clear(); null })
        ScriptableObject.putProperty(engineApi, "loadAtlas", jsFunc { args ->
            val data = loadAtlasData(RhinoContext.toString(args.getOrNull(0) ?: "")) ?: return@jsFunc null
            val obj = rhino.newObject(globalScope)
            ScriptableObject.putProperty(obj, "image", data.imageFile)
            ScriptableObject.putProperty(obj, "width", data.atlasW.toDouble())
            ScriptableObject.putProperty(obj, "height", data.atlasH.toDouble())
            ScriptableObject.putProperty(obj, "frames", rhino.newArray(globalScope, data.frames.keys.sorted().toTypedArray()))
            obj
        })
        ScriptableObject.putProperty(engineApi, "setFlag", jsFunc { args ->
            val key = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (key.isEmpty()) return@jsFunc null
            val rawVal = if (args.size >= 2) args[1] else true
            worldFlags[key] = when (rawVal) {
                is Boolean -> rawVal; is Number -> rawVal.toDouble(); null -> false; else -> RhinoContext.toString(rawVal)
            }
            null
        })
        ScriptableObject.putProperty(engineApi, "getFlag", jsFunc { args ->
            worldFlags[RhinoContext.toString(args.getOrNull(0) ?: "")] ?: if (args.size >= 2) args[1] else false
        })
        ScriptableObject.putProperty(engineApi, "hasFlag", jsFunc { args ->
            when (val v = worldFlags[RhinoContext.toString(args.getOrNull(0) ?: "")]) {
                null -> false; is Boolean -> v; is Number -> v.toDouble() != 0.0; is String -> v.isNotEmpty(); else -> true
            }
        })
        ScriptableObject.putProperty(engineApi, "clearFlag", jsFunc { args -> worldFlags.remove(RhinoContext.toString(args.getOrNull(0) ?: "")); null })
        ScriptableObject.putProperty(engineApi, "clearAllFlags", jsFunc { worldFlags.clear(); null })
        ScriptableObject.putProperty(engineApi, "getAllFlags", jsFunc {
            val obj = rhino.newObject(globalScope)
            for ((k, v) in worldFlags) ScriptableObject.putProperty(obj, k, v)
            obj
        })
        ScriptableObject.putProperty(engineApi, "saveWorldFlags", jsFunc { args -> saveWorldFlagsToDisk(RhinoContext.toString(args.getOrNull(0) ?: "world_flags")) })
        ScriptableObject.putProperty(engineApi, "loadWorldFlags", jsFunc { args -> loadWorldFlagsFromDisk(RhinoContext.toString(args.getOrNull(0) ?: "world_flags")) })
        ScriptableObject.putProperty(engineApi, "loadTable", jsFunc { args -> loadDataTable(RhinoContext.toString(args.getOrNull(0) ?: "")) })
        ScriptableObject.putProperty(engineApi, "reloadTables", jsFunc { dataTableCache.clear(); null })
        ScriptableObject.putProperty(engineApi, "setLanguage", jsFunc { args ->
            val lang = RhinoContext.toString(args.getOrNull(0) ?: "zh").trim().lowercase()
            if (lang.isNotEmpty()) { currentLang = lang; loadI18nFromDiskIfNeeded(lang) }
            currentLang
        })
        ScriptableObject.putProperty(engineApi, "getLanguage", jsFunc { currentLang })
        ScriptableObject.putProperty(engineApi, "setI18nDict", jsFunc { args ->
            val lang = RhinoContext.toString(args.getOrNull(0) ?: "zh").trim().lowercase()
            val obj = args.getOrNull(1) as? Scriptable ?: return@jsFunc null
            val dict = i18nDicts.getOrPut(lang) { mutableMapOf() }
            for (id in obj.ids) {
                val k = id.toString()
                val v = ScriptableObject.getProperty(obj, k)
                if (v != null && v !== Scriptable.NOT_FOUND) dict[k] = RhinoContext.toString(v)
            }
            null
        })
        ScriptableObject.putProperty(engineApi, "t", jsFunc { args ->
            translateKey(RhinoContext.toString(args.getOrNull(0) ?: ""), if (args.size >= 2 && args[1] != null) RhinoContext.toString(args[1]) else "")
        })
        ScriptableObject.putProperty(engineApi, "setDebugDraw", jsFunc { args -> debugDrawEnabled = (args.getOrNull(0) as? Boolean) ?: false; null })
        ScriptableObject.putProperty(engineApi, "isDebugDraw", jsFunc { debugDrawEnabled })
        ScriptableObject.putProperty(engineApi, "setPostFX", jsFunc { args ->
            val cfg = args.getOrNull(0) as? Scriptable ?: return@jsFunc null
            postFxEnabled = getJsBool(cfg, "enabled", postFxEnabled)
            postBloom = getJsFloat(cfg, "bloom", postBloom).coerceIn(0f, 3f)
            postVignette = getJsFloat(cfg, "vignette", postVignette).coerceIn(0f, 2f)
            postChromatic = getJsFloat(cfg, "chromatic", postChromatic).coerceIn(0f, 3f)
            postFade = getJsFloat(cfg, "fade", postFade).coerceIn(0f, 1f)
            null
        })
        ScriptableObject.putProperty(engineApi, "setFade", jsFunc { args ->
            val target = ((args.getOrNull(0) as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
            val dur = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.3f
            pendingFadeScene = null
            if (dur <= 0.01f) { postFade = target; fadeTarget = target; fadeSpeed = 0f }
            else { fadeTarget = target; fadeSpeed = abs(target - postFade) / dur }
            null
        })
        ScriptableObject.putProperty(engineApi, "fadeToScene", jsFunc { args ->
            val sName = RhinoContext.toString(args.getOrNull(0) ?: "")
            val dur = ((args.getOrNull(1) as? Number)?.toFloat() ?: 0.35f).coerceAtLeast(0.05f)
            if (sName.isNotEmpty()) {
                preloadSceneAsync(sName); pendingFadeScene = normalizeSceneName(sName)
                fadeSceneDur = dur; fadeTarget = 1f; fadeSpeed = 1f / dur
            }
            null
        })
        ScriptableObject.putProperty(engineApi, "preloadScene", jsFunc { args ->
            val sName = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (sName.isNotEmpty()) preloadSceneAsync(sName); null
        })
        ScriptableObject.putProperty(engineApi, "loadSceneAdditive", jsFunc { args ->
            val sName = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (sName.isNotEmpty()) pendingAdditiveLoads.add(AdditiveLoadReq(normalizeSceneName(sName), (args.getOrNull(1) as? Number)?.toFloat() ?: 0f, (args.getOrNull(2) as? Number)?.toFloat() ?: 0f))
            null
        })
        ScriptableObject.putProperty(engineApi, "unloadScene", jsFunc { args ->
            val sName = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (sName.isNotEmpty()) pendingUnloadScenes.add(normalizeSceneName(sName)); null
        })
        ScriptableObject.putProperty(engineApi, "clearSceneCache", jsFunc { preloadedSceneCache.clear(); null })
        ScriptableObject.putProperty(engineApi, "getStats", jsFunc {
            val obj = rhino.newObject(globalScope)
            ScriptableObject.putProperty(obj, "fps", smoothedFps.toDouble())
            ScriptableObject.putProperty(obj, "drawCalls", lastFrameDrawCalls)
            ScriptableObject.putProperty(obj, "culled", lastFrameCulled)
            ScriptableObject.putProperty(obj, "nodes", nodes.size)
            ScriptableObject.putProperty(obj, "lights", lastFrameLights)
            ScriptableObject.putProperty(obj, "spatialCells", spatialGrid.size)
            ScriptableObject.putProperty(obj, "modules", moduleCache.size)
            ScriptableObject.putProperty(obj, "atlases", atlasCache.size)
            ScriptableObject.putProperty(obj, "flags", worldFlags.size)
            ScriptableObject.putProperty(obj, "tables", dataTableCache.size)
            ScriptableObject.putProperty(obj, "lang", currentLang)
            ScriptableObject.putProperty(obj, "fbo", postFxEnabled && fboReady)
            ScriptableObject.putProperty(obj, "touches", inputSystem.getTouchCount())
            ScriptableObject.putProperty(obj, "textures", textureCache.size)
            obj
        })
        ScriptableObject.putProperty(engineApi, "loadScene", jsFunc { args ->
            val sName = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (sName.isNotEmpty()) pendingSceneLoad = normalizeSceneName(sName); null
        })
        ScriptableObject.putProperty(engineApi, "setGlobal", jsFunc { args ->
            val k = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (k.isNotEmpty()) globalDataStore[k] = args.getOrNull(1); null
        })
        ScriptableObject.putProperty(engineApi, "getGlobal", jsFunc { args ->
            globalDataStore[RhinoContext.toString(args.getOrNull(0) ?: "")] ?: args.getOrNull(1)
        })
        ScriptableObject.putProperty(engineApi, "saveDisk", jsFunc { args -> saveDiskSlot(RhinoContext.toString(args.getOrNull(0) ?: "slot_1"), args.getOrNull(1)) })
        ScriptableObject.putProperty(engineApi, "loadDisk", jsFunc { args -> loadDiskSlot(RhinoContext.toString(args.getOrNull(0) ?: "slot_1")) })
        ScriptableObject.putProperty(engineApi, "deleteDisk", jsFunc { args -> deleteDiskSlot(RhinoContext.toString(args.getOrNull(0) ?: "slot_1")) })
        ScriptableObject.putProperty(engineApi, "raycast", jsFunc { args ->
            performRaycast(
                (args.getOrNull(0) as? Number)?.toFloat() ?: 0f,
                (args.getOrNull(1) as? Number)?.toFloat() ?: 0f,
                (args.getOrNull(2) as? Number)?.toFloat() ?: 0f,
                (args.getOrNull(3) as? Number)?.toFloat() ?: 0f,
                args.getOrNull(4)?.takeIf { it !== Scriptable.NOT_FOUND }?.toString() ?: "",
                (args.getOrNull(5) as? Number)?.toInt() ?: 0xFFFF
            )
        })
        ScriptableObject.putProperty(engineApi, "hitstop", jsFunc { args ->
            hitstopTimer = max(hitstopTimer, ((args.getOrNull(0) as? Number)?.toFloat() ?: 0.05f).coerceIn(0f, 0.5f)); null
        })
        ScriptableObject.putProperty(engineApi, "setTimeScale", jsFunc { args ->
            timeScale = ((args.getOrNull(0) as? Number)?.toFloat() ?: 1f).coerceIn(0.05f, 4f); null
        })
        ScriptableObject.putProperty(engineApi, "setCameraMode", jsFunc { args -> camMode = RhinoContext.toString(args.getOrNull(0) ?: "2.5D"); null })
        ScriptableObject.putProperty(engineApi, "setCameraTilt", jsFunc { args ->
            camPitch = (args.getOrNull(0) as? Number)?.toFloat() ?: camPitch
            camDistance = (args.getOrNull(1) as? Number)?.toFloat() ?: camDistance
            camFov = (args.getOrNull(2) as? Number)?.toFloat() ?: camFov; null
        })
        ScriptableObject.putProperty(engineApi, "setCameraTarget", jsFunc { args ->
            val targetObj = args.getOrNull(0) as? Scriptable
            camTarget = (targetObj as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === targetObj }; null
        })
        ScriptableObject.putProperty(engineApi, "setCameraDeadzone", jsFunc { args ->
            camDeadzoneX = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
            camDeadzoneY = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f; null
        })
        ScriptableObject.putProperty(engineApi, "setCameraLookAhead", jsFunc { args ->
            camLookAheadX = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
            camLookAheadY = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f; null
        })
        ScriptableObject.putProperty(engineApi, "setCameraBounds", jsFunc { args ->
            camBoundsEnabled = true
            camMinX = (args.getOrNull(0) as? Number)?.toFloat() ?: -99999f
            camMinY = (args.getOrNull(1) as? Number)?.toFloat() ?: -99999f
            camMaxX = (args.getOrNull(2) as? Number)?.toFloat() ?: 99999f
            camMaxY = (args.getOrNull(3) as? Number)?.toFloat() ?: 99999f; null
        })
        ScriptableObject.putProperty(engineApi, "clearCameraBounds", jsFunc { camBoundsEnabled = false; null })
        ScriptableObject.putProperty(engineApi, "getCamera", jsFunc {
            val obj = rhino.newObject(globalScope)
            ScriptableObject.putProperty(obj, "x", camX.toDouble()); ScriptableObject.putProperty(obj, "y", camY.toDouble())
            ScriptableObject.putProperty(obj, "pitch", camPitch.toDouble()); ScriptableObject.putProperty(obj, "mode", camMode); obj
        })
        ScriptableObject.putProperty(engineApi, "setAmbientLight", jsFunc { args ->
            ambientR = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.52f
            ambientG = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.56f
            ambientB = (args.getOrNull(2) as? Number)?.toFloat() ?: 0.68f; null
        })
        ScriptableObject.putProperty(engineApi, "shake", jsFunc { args ->
            shakeIntensity = (args.getOrNull(0) as? Number)?.toFloat() ?: 12f
            shakeTimer = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.15f; null
        })
        ScriptableObject.putProperty(engineApi, "tween", jsFunc { args ->
            val targetObj = args.getOrNull(0) as? Scriptable ?: return@jsFunc null
            val prop = RhinoContext.toString(args.getOrNull(1) ?: "")
            val toVal = (args.getOrNull(2) as? Number)?.toFloat() ?: return@jsFunc null
            val dur = (args.getOrNull(3) as? Number)?.toFloat() ?: 0.25f
            val ease = RhinoContext.toString(args.getOrNull(4) ?: "easeOut")
            val n = (targetObj as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === targetObj } ?: return@jsFunc null
            activeTweens.removeAll { it.node === n && it.prop == prop }
            activeTweens.add(TweenTask(n, prop, readTweenProperty(n, prop), toVal, dur, ease)); null
        })
        ScriptableObject.putProperty(engineApi, "playTone", jsFunc { args -> audioSystem.playToneAsync((args.getOrNull(0) as? Number)?.toInt() ?: 440, (args.getOrNull(1) as? Number)?.toInt() ?: 80); null })
        ScriptableObject.putProperty(engineApi, "playBGM", jsFunc { args ->
            val f = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (f.isNotEmpty()) audioSystem.playBGM(f, (args.getOrNull(1) as? Boolean) ?: true, (args.getOrNull(2) as? Number)?.toFloat() ?: 0.8f); null
        })
        ScriptableObject.putProperty(engineApi, "crossfadeBGM", jsFunc { args ->
            val f = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (f.isNotEmpty()) audioSystem.crossfadeBGM(f, (args.getOrNull(1) as? Number)?.toFloat() ?: 1.2f, (args.getOrNull(2) as? Boolean) ?: true, (args.getOrNull(3) as? Number)?.toFloat() ?: 0.8f); null
        })
        ScriptableObject.putProperty(engineApi, "stopBGM", jsFunc { args -> audioSystem.stopBGM((args.getOrNull(0) as? Number)?.toFloat() ?: 0f); null })
        ScriptableObject.putProperty(engineApi, "playSFX", jsFunc { args ->
            val f = RhinoContext.toString(args.getOrNull(0) ?: "")
            val pMin = (args.getOrNull(2) as? Number)?.toFloat() ?: 1.0f
            if (f.isNotEmpty()) audioSystem.playSFX(f, (args.getOrNull(1) as? Number)?.toFloat() ?: 1.0f, pMin, (args.getOrNull(3) as? Number)?.toFloat() ?: pMin); null
        })
        ScriptableObject.putProperty(engineApi, "setVolume", jsFunc { args -> audioSystem.setVolume(RhinoContext.toString(args.getOrNull(0) ?: "master"), (args.getOrNull(1) as? Number)?.toFloat() ?: 1.0f); null })
        ScriptableObject.putProperty(engineApi, "getVolume", jsFunc { args -> audioSystem.getVolume(RhinoContext.toString(args.getOrNull(0) ?: "master")).toDouble() })
        ScriptableObject.putProperty(engineApi, "moveAndSlide", jsFunc { args ->
            val selfObj = args.getOrNull(0) as? Scriptable ?: return@jsFunc null
            val n = (selfObj as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === selfObj } ?: return@jsFunc null
            moveAndSlideNode(n, (args.getOrNull(1) as? Number)?.toFloat() ?: 0.016f); null
        })
        ScriptableObject.putProperty(engineApi, "distance", jsFunc { args ->
            val a = (args.getOrNull(0) as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === (args.getOrNull(0) as? Scriptable) }
            val b = (args.getOrNull(1) as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === (args.getOrNull(1) as? Scriptable) }
            if (a != null && b != null) { val ga = getGlobalTransform(a); val gb = getGlobalTransform(b); hypot(ga.gx - gb.gx, ga.gy - gb.gy).toDouble() } else 999999.0
        })
        ScriptableObject.putProperty(engineApi, "angleTo", jsFunc { args ->
            val a = (args.getOrNull(0) as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === (args.getOrNull(0) as? Scriptable) }
            val b = (args.getOrNull(1) as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === (args.getOrNull(1) as? Scriptable) }
            if (a != null && b != null) { val ga = getGlobalTransform(a); val gb = getGlobalTransform(b); Math.toDegrees(atan2((gb.gy - ga.gy).toDouble(), (gb.gx - ga.gx).toDouble())) } else 0.0
        })
        ScriptableObject.putProperty(engineApi, "spawn", jsFunc { args ->
            val cfg = args.getOrNull(0) as? Scriptable ?: return@jsFunc null
            val rawLayer = getJsStr(cfg, "layer", "World")
            val spawned = GameNode(
                name = getJsStr(cfg, "name", "Spawn_${System.nanoTime() % 10000}"),
                nodeType = getJsStr(cfg, "nodeType", "Sprite2D"),
                layer = if (rawLayer.startsWith("UI", ignoreCase = true)) "UI" else "World",
                parent = RhinoContext.toString(args.getOrNull(1) ?: ""),
                tag = getJsStr(cfg, "tag", "default"),
                persistent = getJsBool(cfg, "persistent", false),
                x = getJsFloat(cfg, "x", 0f), y = getJsFloat(cfg, "y", 0f), z = getJsFloat(cfg, "z", 0f),
                width = getJsFloat(cfg, "width", 64f), height = getJsFloat(cfg, "height", 64f),
                tiltX = getJsFloat(cfg, "tiltX", 0f), billboard = getJsBool(cfg, "billboard", false),
                zIndex = getJsInt(cfg, "zIndex", 0), texture = getJsStr(cfg, "texture", ""),
                atlas = getJsStr(cfg, "atlas", ""), atlasFrame = getJsStr(cfg, "atlasFrame", ""),
                text = getJsStr(cfg, "text", ""), fontSize = getJsFloat(cfg, "fontSize", 44f),
                r = getJsFloat(cfg, "r", 1f), g = getJsFloat(cfg, "g", 1f), b = getJsFloat(cfg, "b", 1f), a = getJsFloat(cfg, "a", 1f),
                sliceLeft = getJsFloat(cfg, "sliceLeft", 0f), sliceRight = getJsFloat(cfg, "sliceRight", 0f),
                sliceTop = getJsFloat(cfg, "sliceTop", 0f), sliceBottom = getJsFloat(cfg, "sliceBottom", 0f),
                clipChildren = getJsBool(cfg, "clipChildren", false),
                clipX = getJsFloat(cfg, "clipX", 0f), clipY = getJsFloat(cfg, "clipY", 0f),
                clipW = getJsFloat(cfg, "clipW", 0f), clipH = getJsFloat(cfg, "clipH", 0f),
                collider = getJsBool(cfg, "collider", false), isTrigger = getJsBool(cfg, "isTrigger", false),
                oneWay = getJsBool(cfg, "oneWay", false), slopeType = getJsStr(cfg, "slopeType", "none"),
                colOffsetX = getJsFloat(cfg, "colOffsetX", 0f), colOffsetY = getJsFloat(cfg, "colOffsetY", 0f),
                colWidth = getJsFloat(cfg, "colWidth", 0f), colHeight = getJsFloat(cfg, "colHeight", 0f),
                colLayer = getJsInt(cfg, "colLayer", 1), colMask = getJsInt(cfg, "colMask", 0xFFFF),
                bodyMode = getJsStr(cfg, "bodyMode", "kinematic"),
                isHitbox = getJsBool(cfg, "isHitbox", false), isHurtbox = getJsBool(cfg, "isHurtbox", false),
                damage = getJsFloat(cfg, "damage", 10f), shadowCaster = getJsBool(cfg, "shadowCaster", false),
                lightRadius = getJsFloat(cfg, "lightRadius", 400f), lightIntensity = getJsFloat(cfg, "lightIntensity", 1.5f),
                script = resolveNodeScriptText(getJsStr(cfg, "script", ""), getJsStr(cfg, "scriptFile", ""))
            )
            pendingAdd.add(spawned); bindNodeScript(spawned); spawned.jsObj
        })
        ScriptableObject.putProperty(engineApi, "instantiate", jsFunc { args ->
            val sceneFile = RhinoContext.toString(args.getOrNull(0) ?: "")
            val txt = readSceneText(sceneFile) ?: return@jsFunc null
            val loaded = mutableListOf<GameNode>()
            parseSceneJson(txt, loaded, groupName = normalizeSceneName(sceneFile), applyCamera = false)
            val sx = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val sy = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
            val sz = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f
            loaded.forEach {
                if (it.parent.isEmpty()) { it.x += sx; it.y += sy; it.z += sz }
                pendingAdd.add(it); bindNodeScript(it)
            }
            loaded.firstOrNull()?.jsObj
        })
        ScriptableObject.putProperty(engineApi, "destroy", jsFunc { args ->
            val targetObj = args.getOrNull(0) as? Scriptable
            ((targetObj as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === targetObj })?.let { pendingRemove.add(it) }; null
        })
        ScriptableObject.putProperty(engineApi, "findNode", jsFunc { args -> nodes.find { it.name == RhinoContext.toString(args.getOrNull(0) ?: "") }?.jsObj })
        ScriptableObject.putProperty(engineApi, "findNodesByTag", jsFunc { args ->
            val t = RhinoContext.toString(args.getOrNull(0) ?: "")
            rhino.newArray(globalScope, nodes.filter { it.tag == t && !pendingRemove.contains(it) }.mapNotNull { it.jsObj }.toTypedArray())
        })
        ScriptableObject.putProperty(engineApi, "on", jsFunc { args ->
            val ev = RhinoContext.toString(args.getOrNull(0) ?: "")
            val fn = args.getOrNull(1) as? Function
            if (ev.isNotEmpty() && fn != null) eventListeners.getOrPut(ev) { mutableListOf() }.add(fn); null
        })
        ScriptableObject.putProperty(engineApi, "emit", jsFunc { args ->
            eventListeners[RhinoContext.toString(args.getOrNull(0) ?: "")]?.toList()?.forEach { fn ->
                try { fn.call(rhino, globalScope, globalScope, arrayOf(args.getOrNull(1))) } catch (_: Exception) {}
            }; null
        })
        ScriptableObject.putProperty(globalScope, "Engine", engineApi)

        val inputApi = object : ScriptableObject() {
            override fun getClassName() = "Input"
            override fun get(name: String?, start: Scriptable?): Any? = when (name) {
                "isTouching" -> isTouching || inputSystem.getTouchCount() > 0
                "touchCount" -> inputSystem.getTouchCount()
                "screenX", "touchX" -> touchScreenX.toDouble()
                "screenY", "touchY" -> touchScreenY.toDouble()
                "worldX" -> touchWorldX.toDouble()
                "worldY" -> touchWorldY.toDouble()
                else -> super.get(name, start)
            }
        }
        ScriptableObject.putProperty(inputApi, "isActionPressed", jsFunc { args -> inputSystem.isActionPressed(RhinoContext.toString(args.getOrNull(0) ?: "")) })
        ScriptableObject.putProperty(inputApi, "isActionJustPressed", jsFunc { args -> inputSystem.isActionJustPressed(RhinoContext.toString(args.getOrNull(0) ?: "")) })
        ScriptableObject.putProperty(inputApi, "isActionJustReleased", jsFunc { args -> inputSystem.isActionJustReleased(RhinoContext.toString(args.getOrNull(0) ?: "")) })
        ScriptableObject.putProperty(inputApi, "getAxis", jsFunc { args -> inputSystem.getAxis(RhinoContext.toString(args.getOrNull(0) ?: "move_left"), RhinoContext.toString(args.getOrNull(1) ?: "move_right")).toDouble() })
        ScriptableObject.putProperty(inputApi, "bindAction", jsFunc { args ->
            val act = RhinoContext.toString(args.getOrNull(0) ?: "")
            val rawKeys = args.getOrNull(1)
            val list = mutableListOf<String>()
            if (rawKeys is Scriptable) {
                val len = (ScriptableObject.getProperty(rawKeys, "length") as? Number)?.toInt() ?: 0
                for (i in 0 until len) {
                    val v = ScriptableObject.getProperty(rawKeys, i)
                    if (v != null && v !== Scriptable.NOT_FOUND) list.add(RhinoContext.toString(v))
                }
            } else if (rawKeys != null) list.add(RhinoContext.toString(rawKeys))
            if (act.isNotEmpty() && list.isNotEmpty()) inputSystem.bindAction(act, list); null
        })
        ScriptableObject.putProperty(inputApi, "setVirtualAction", jsFunc { args ->
            val act = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (act.isNotEmpty()) inputSystem.setVirtualAction(act, (args.getOrNull(1) as? Boolean) ?: false); null
        })
        ScriptableObject.putProperty(inputApi, "bindTouchZone", jsFunc { args ->
            val act = RhinoContext.toString(args.getOrNull(0) ?: "")
            if (act.isNotEmpty()) inputSystem.bindTouchZone(act, (args.getOrNull(1) as? Number)?.toFloat() ?: 0f, (args.getOrNull(2) as? Number)?.toFloat() ?: 0f, (args.getOrNull(3) as? Number)?.toFloat() ?: 160f, (args.getOrNull(4) as? Number)?.toFloat() ?: 160f); null
        })
        ScriptableObject.putProperty(inputApi, "clearTouchZones", jsFunc { inputSystem.clearTouchZones(); null })
        ScriptableObject.putProperty(globalScope, "Input", inputApi)
    }

    private fun bindNodeScript(n: GameNode) {
        val nodeScope = rhino.newObject(globalScope)
        nodeScope.prototype = globalScope
        val selfObj = GameNodeJsProxy(n).apply { parentScope = nodeScope; prototype = nodeScope }
        n.jsObj = selfObj

        // P7：专业动画与状态机（FSM）JS API
        ScriptableObject.putProperty(selfObj, "setAnimFloat", jsFunc { args ->
            n.animator.fsm.setFloat(RhinoContext.toString(args.getOrNull(0) ?: ""), (args.getOrNull(1) as? Number)?.toFloat() ?: 0f); null
        })
        ScriptableObject.putProperty(selfObj, "setAnimBool", jsFunc { args ->
            n.animator.fsm.setBool(RhinoContext.toString(args.getOrNull(0) ?: ""), (args.getOrNull(1) as? Boolean) ?: false); null
        })
        ScriptableObject.putProperty(selfObj, "triggerAnim", jsFunc { args ->
            n.animator.fsm.setTrigger(RhinoContext.toString(args.getOrNull(0) ?: "")); null
        })
        ScriptableObject.putProperty(selfObj, "setAnimSpeed", jsFunc { args ->
            n.animator.speedScale = ((args.getOrNull(0) as? Number)?.toFloat() ?: 1f).coerceIn(0.05f, 8f); null
        })
        ScriptableObject.putProperty(selfObj, "getAnimState", jsFunc {
            n.animator.fsm.currentState.ifEmpty { n.currentClipName }
        })
        ScriptableObject.putProperty(selfObj, "isAnimCancelable", jsFunc {
            n.animator.activeFrame?.cancelable == true
        })
        ScriptableObject.putProperty(selfObj, "addAnimTransition", jsFunc { args ->
            val from = RhinoContext.toString(args.getOrNull(0) ?: "*")
            val to = RhinoContext.toString(args.getOrNull(1) ?: "")
            val param = RhinoContext.toString(args.getOrNull(2) ?: "")
            val op = RhinoContext.toString(args.getOrNull(3) ?: "trigger")
            val valArg = args.getOrNull(4)
            val priority = (args.getOrNull(5) as? Number)?.toInt() ?: 0
            val reqExit = (args.getOrNull(6) as? Boolean) ?: false
            val reqCancel = (args.getOrNull(7) as? Boolean) ?: false
            val conds = if (param.isNotEmpty()) listOf(StateCondition(param, op, valArg)) else emptyList()
            if (to.isNotEmpty()) n.animator.fsm.addTransition(StateTransition(from, to, priority, reqExit, reqCancel, conds))
            null
        })
        ScriptableObject.putProperty(selfObj, "registerProClip", jsFunc { args ->
            val raw = args.getOrNull(0) ?: return@jsFunc false
            val jsonStr = if (raw is String) raw else RhinoContext.toString(NativeJSON.stringify(rhino, globalScope, raw, null, null))
            try {
                val wrapper = JSONObject().put("proClips", JSONArray().put(JSONObject(jsonStr)))
                ZeroAnimator.parseInto(n.animator, wrapper)
                true
            } catch (e: Exception) {
                onConsoleLog("[ANIM 注册错误 @ ${n.name}] ${e.message}")
                false
            }
        })

        ScriptableObject.putProperty(selfObj, "setAtlas", jsFunc { args ->
            n.atlas = RhinoContext.toString(args.getOrNull(0) ?: n.atlas)
            n.atlasFrame = RhinoContext.toString(args.getOrNull(1) ?: "")
            loadAtlasData(n.atlas); null
        })
        ScriptableObject.putProperty(selfObj, "setNineSlice", jsFunc { args ->
            n.sliceLeft = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
            n.sliceRight = (args.getOrNull(1) as? Number)?.toFloat() ?: n.sliceLeft
            n.sliceTop = (args.getOrNull(2) as? Number)?.toFloat() ?: n.sliceLeft
            n.sliceBottom = (args.getOrNull(3) as? Number)?.toFloat() ?: n.sliceTop; null
        })
        ScriptableObject.putProperty(selfObj, "setClipRect", jsFunc { args ->
            n.clipX = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
            n.clipY = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
            n.clipW = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
            n.clipH = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f; null
        })
        ScriptableObject.putProperty(selfObj, "jump", jsFunc { args ->
            val jumpForce = (args.getOrNull(0) as? Number)?.toFloat() ?: 920f
            if (n.isGrounded || n.coyoteTimer > 0f) {
                n.vy = -abs(jumpForce); n.isGrounded = false; n.coyoteTimer = 0f; n.jumpCount = 1; return@jsFunc true
            } else if (n.jumpCount < n.maxJumps) {
                n.vy = -abs(jumpForce) * 0.9f; n.jumpCount++; return@jsFunc true
            }
            false
        })
        ScriptableObject.putProperty(selfObj, "dropThrough", jsFunc { args ->
            n.dropThroughTimer = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.22f; n.isGrounded = false; null
        })
        ScriptableObject.putProperty(selfObj, "bounce", jsFunc { args ->
            (args.getOrNull(0) as? Number)?.toFloat()?.let { n.vx = it }
            n.vy = (args.getOrNull(1) as? Number)?.toFloat() ?: -760f
            n.isGrounded = false; n.jumpCount = 1; null
        })
        ScriptableObject.putProperty(selfObj, "play", jsFunc { args ->
            val clipName = RhinoContext.toString(args.getOrNull(0) ?: "")
            val forceRestart = (args.getOrNull(1) as? Boolean) ?: false
            // P7：优先走专业动画库
            if (n.animator.play(clipName, forceRestart)) {
                n.currentClipName = clipName
                return@jsFunc null
            }
            val clip = n.clips[clipName]
            if (clip != null) {
                if (!forceRestart && n.animPlaying && n.currentClipName == clipName) return@jsFunc null
                n.currentClipName = clipName; n.animStart = clip.start; n.animEnd = clip.end
                n.animFps = clip.fps; n.animLoop = clip.loop; n.currentFrame = clip.start
                n.animTimer = 0f; n.animPlaying = true
            } else onConsoleLog("[ANIM 警告 @ ${n.name}] 未找到动画片段: $clipName")
            null
        })
        ScriptableObject.putProperty(selfObj, "playAnim", jsFunc { args ->
            n.animator.stop()
            n.currentClipName = ""
            n.animStart = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            n.animEnd = (args.getOrNull(1) as? Number)?.toInt() ?: 0
            n.animFps = (args.getOrNull(2) as? Number)?.toFloat() ?: 12f
            n.animLoop = (args.getOrNull(3) as? Boolean) ?: true
            n.currentFrame = n.animStart; n.animTimer = 0f; n.animPlaying = true; null
        })
        ScriptableObject.putProperty(selfObj, "stopAnim", jsFunc { n.animator.stop(); n.animPlaying = false; n.currentClipName = ""; null })
        ScriptableObject.putProperty(selfObj, "flash", jsFunc { args -> n.flashTimer = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.12f; null })
        ScriptableObject.putProperty(selfObj, "emitBurst", jsFunc { args ->
            val count = ((args.getOrNull(0) as? Number)?.toInt() ?: 24).coerceIn(1, 120)
            val gt = getGlobalTransform(n); repeat(count) { spawnSingleParticle(n, gt.gx, gt.gy) }; null
        })
        ScriptableObject.putProperty(nodeScope, "self", selfObj)

        if (n.script.isNotBlank()) {
            try { rhino.evaluateString(nodeScope, n.script, n.name, 1, null) }
            catch (e: Exception) { onConsoleLog("[JS 编译错误 @ ${n.name}] ${e.message}") }
        }
    }

    private fun callLifecycle(n: GameNode, fnName: String, arg: Any? = null) {
        val scope = n.jsObj?.prototype ?: return
        val fn = ScriptableObject.getProperty(scope, fnName) as? Function ?: return
        try { fn.call(rhino, scope, n.jsObj, if (arg != null) arrayOf(arg) else emptyArray()) }
        catch (e: Exception) { onConsoleLog("[JS 运行错误 @ ${n.name}.$fnName] ${e.message}") }
    }

    private fun computeSlopeSurfaceY(slope: GameNode, footX: Float): Float {
        val sb = getColliderBounds(slope)
        val t = ((footX - (sb.cx - sb.hw)) / max(1f, sb.hw * 2f)).coerceIn(0f, 1f)
        return if (slope.slopeType == "left_up") (sb.cy + sb.hh) - t * (sb.hh * 2f) else (sb.cy - sb.hh) + t * (sb.hh * 2f)
    }

    private fun moveAndSlideNode(n: GameNode, dt: Float) {
        val collidedSolids = mutableSetOf<GameNode>()
        if (n.bodyMode == "platformer" || n.bodyMode == "rigid") {
            val gMul = if (n.vy > 0f) n.fallMultiplier else 1f
            n.vy = min(n.maxFallSpeed, n.vy + n.gravity * gMul * dt)
            if (n.drag > 0f) n.vx *= max(0f, 1f - n.drag * dt)
        }

        val startBounds = getColliderBounds(n)
        val prevBottom = startBounds.cy + startBounds.hh
        val sweepMinX = min(startBounds.cx, startBounds.cx + n.vx * dt) - startBounds.hw - 32f
        val sweepMaxX = max(startBounds.cx, startBounds.cx + n.vx * dt) + startBounds.hw + 32f
        val sweepMinY = min(startBounds.cy, startBounds.cy + n.vy * dt) - startBounds.hh - 32f
        val sweepMaxY = max(startBounds.cy, startBounds.cy + n.vy * dt) + startBounds.hh + 32f
        querySpatialCandidates(sweepMinX, sweepMaxX, sweepMinY, sweepMaxY, queryCandidates)
        val nearby = queryCandidates.toList()

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
                            n.y += (surfY - footY); n.vy = 0f; n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax
                            climbedSlope = true; collidedSolids.add(other)
                        }
                    }
                } else if (checkOverlap(n, other)) {
                    n.x -= n.vx * dt
                    if (n.bodyMode == "rigid") n.vx = -n.vx * n.bounciness else n.vx = 0f
                    collidedSolids.add(other)
                }
            }
        }

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
                        n.y += (surfY - footY); n.vy = 0f; n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax
                        collidedSolids.add(other)
                    } else if (wasGrounded && n.vy >= 0f && !n.isGrounded) {
                        val sb = getColliderBounds(other)
                        if (abs(cb.cx - sb.cx) <= sb.hw && surfY - footY in 0f..24f) {
                            n.y += (surfY - footY); n.vy = 0f; n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax
                            collidedSolids.add(other)
                        }
                    }
                } else if (other.oneWay) {
                    if (n.vy >= 0f && n.dropThroughTimer <= 0f) {
                        val otherTop = getColliderBounds(other).let { it.cy - it.hh }
                        if (prevBottom <= otherTop + 14f && checkOverlap(n, other)) {
                            n.y -= n.vy * dt; n.vy = 0f; n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax
                            collidedSolids.add(other)
                        }
                    }
                } else if (checkOverlap(n, other)) {
                    val fallingDown = n.vy > 0f
                    n.y -= n.vy * dt
                    if (fallingDown) { n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax }
                    if (n.bodyMode == "rigid" && abs(n.vy) > 90f) n.vy = -n.vy * n.bounciness else n.vy = 0f
                    collidedSolids.add(other)
                }
            }
        }

        if (!n.isGrounded) {
            if (wasGrounded) n.coyoteTimer = n.coyoteTimeMax else n.coyoteTimer = max(0f, n.coyoteTimer - dt)
        }
        n.z += n.vz * dt

        for (other in collidedSolids) {
            if (!pendingRemove.contains(n) && !pendingRemove.contains(other)) {
                callLifecycle(n, "onCollision", other.jsObj); callLifecycle(other, "onCollision", n.jsObj)
            }
        }
        if (n.collider) {
            for (other in nearby) {
                if (other !== n && other.visible && other.collider && other.layer == n.layer && canCollideLayer(n, other)) {
                    if ((n.isTrigger || other.isTrigger) && checkOverlap(n, other)) {
                        if (!pendingRemove.contains(n) && !pendingRemove.contains(other)) {
                            callLifecycle(n, "onTriggerEnter", other.jsObj); callLifecycle(other, "onTriggerEnter", n.jsObj)
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
            for (gx in floor((ba.cx - ba.hw - gb.gx) / ts).toInt()..floor((ba.cx + ba.hw - gb.gx) / ts).toInt()) {
                for (gy in floor((ba.cy - ba.hh - gb.gy) / ts).toInt()..floor((ba.cy + ba.hh - gb.gy) / ts).toInt()) {
                    if (b.tileLookup.containsKey(packGridKey(gx, gy))) return true
                }
            }
            return false
        }
        val bb = getColliderBounds(b)
        return abs(ba.cx - bb.cx) < (ba.hw + bb.hw) && abs(ba.cy - bb.cy) < (ba.hh + bb.hh)
    }

    data class GlobalTransform(val gx: Float, val gy: Float, val gz: Float, val gRot: Float, val gSx: Float, val gSy: Float)

    private fun getGlobalTransform(n: GameNode, visited: MutableSet<String> = mutableSetOf()): GlobalTransform {
        val pxOffset = if (n.layer == "World" && n.parallaxX != 1f) (camX - 540f) * (1f - n.parallaxX) else 0f
        val pyOffset = if (n.layer == "World" && n.parallaxY != 1f) (camY - 960f) * (1f - n.parallaxY) else 0f
        if (n.parent.isEmpty() || visited.contains(n.name)) {
            return GlobalTransform(n.x + pxOffset, n.y + pyOffset, n.z, n.rotation, n.scaleX, n.scaleY)
        }
        visited.add(n.name)
        val p = nodes.find { it.name == n.parent } ?: return GlobalTransform(n.x + pxOffset, n.y + pyOffset, n.z, n.rotation, n.scaleX, n.scaleY)
        val pt = getGlobalTransform(p, visited)
        val rad = Math.toRadians(pt.gRot.toDouble())
        val sx = n.x * pt.gSx; val sy = n.y * pt.gSy
        return GlobalTransform(
            pt.gx + (sx * cos(rad) - sy * sin(rad)).toFloat(),
            pt.gy + (sx * sin(rad) + sy * cos(rad)).toFloat(),
            pt.gz + n.z, pt.gRot + n.rotation, pt.gSx * n.scaleX, pt.gSy * n.scaleY
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        inputSystem.onMultiTouch(event, width, height)
        val idx = event.actionIndex
        val sx = (event.getX(idx) / max(1, width)) * 1080f
        val sy = (event.getY(idx) / max(1, height)) * 1920f
        touchScreenX = sx; touchScreenY = sy
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                isTouching = true; synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("down", sx, sy)) }
            }
            MotionEvent.ACTION_MOVE -> synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("move", sx, sy)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) isTouching = false
                synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("up", sx, sy)) }
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (inputSystem.onKeyEvent(keyCode, true)) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (inputSystem.onKeyEvent(keyCode, false)) true else super.onKeyUp(keyCode, event)

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean =
        if (event != null && inputSystem.onGamepadMotion(event)) true else super.onGenericMotionEvent(event)

    private fun flushTouchEvents() {
        val list = synchronized(pendingTouchEvents) { val c = pendingTouchEvents.toList(); pendingTouchEvents.clear(); c }
        for ((type, sx, sy) in list) {
            val evObj = rhino.newObject(globalScope)
            ScriptableObject.putProperty(evObj, "type", type)
            ScriptableObject.putProperty(evObj, "screenX", sx.toDouble()); ScriptableObject.putProperty(evObj, "screenY", sy.toDouble())
            ScriptableObject.putProperty(evObj, "worldX", touchWorldX.toDouble()); ScriptableObject.putProperty(evObj, "worldY", touchWorldY.toDouble())
            nodes.toList().forEach { callLifecycle(it, "onInput", evObj) }
        }
    }

    private fun parseSceneJson(raw: String, outList: MutableList<GameNode>, groupName: String = "main", applyCamera: Boolean = true) {
        try {
            val root = JSONObject(raw)
            val camObj = root.optJSONObject("camera")
            if (applyCamera && camObj != null) {
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
                                while (keys.hasNext()) { val k = keys.next(); k.toIntOrNull()?.let { evMap[it] = evObj.optString(k, "") } }
                            }
                            clipMap[cName] = AnimClip(cName, co.optInt("start", 0), co.optInt("end", 0), co.optDouble("fps", 12.0).toFloat(), co.optBoolean("loop", true), evMap)
                        }
                    }
                }
                val tileList = mutableListOf<TileCell>()
                val tileMapLookup = HashMap<Long, TileCell>()
                val tilesArr = o.optJSONArray("tiles")
                if (tilesArr != null) {
                    for (tIdx in 0 until tilesArr.length()) {
                        val to = tilesArr.getJSONObject(tIdx)
                        val cell = TileCell(to.optInt("gx", 0), to.optInt("gy", 0), to.optInt("frame", 0))
                        tileList.add(cell); tileMapLookup[packGridKey(cell.gx, cell.gy)] = cell
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
                    sceneGroup = groupName,
                    persistent = o.optBoolean("persistent", false),
                    x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(), z = o.optDouble("z", 0.0).toFloat(),
                    width = o.optDouble("width", 120.0).toFloat(), height = o.optDouble("height", 120.0).toFloat(),
                    rotation = o.optDouble("rotation", 0.0).toFloat(), tiltX = o.optDouble("tiltX", 0.0).toFloat(),
                    billboard = o.optBoolean("billboard", false),
                    scaleX = o.optDouble("scaleX", 1.0).toFloat(), scaleY = o.optDouble("scaleY", 1.0).toFloat(),
                    zIndex = o.optInt("zIndex", 0), visible = o.optBoolean("visible", true),
                    parallaxX = o.optDouble("parallaxX", 1.0).toFloat(), parallaxY = o.optDouble("parallaxY", 1.0).toFloat(),
                    texture = o.optString("texture", ""), atlas = o.optString("atlas", ""), atlasFrame = o.optString("atlasFrame", ""),
                    text = o.optString("text", ""), fontSize = o.optDouble("fontSize", 44.0).toFloat(),
                    r = o.optDouble("r", 1.0).toFloat(), g = o.optDouble("g", 1.0).toFloat(), b = o.optDouble("b", 1.0).toFloat(), a = o.optDouble("a", 1.0).toFloat(),
                    lightRadius = o.optDouble("lightRadius", 450.0).toFloat(), lightIntensity = o.optDouble("lightIntensity", 1.4).toFloat(),
                    shadowCaster = o.optBoolean("shadowCaster", false),
                    sliceLeft = o.optDouble("sliceLeft", 0.0).toFloat(), sliceRight = o.optDouble("sliceRight", 0.0).toFloat(),
                    sliceTop = o.optDouble("sliceTop", 0.0).toFloat(), sliceBottom = o.optDouble("sliceBottom", 0.0).toFloat(),
                    clipChildren = o.optBoolean("clipChildren", false),
                    clipX = o.optDouble("clipX", 0.0).toFloat(), clipY = o.optDouble("clipY", 0.0).toFloat(),
                    clipW = o.optDouble("clipW", 0.0).toFloat(), clipH = o.optDouble("clipH", 0.0).toFloat(),
                    collider = o.optBoolean("collider", false), isTrigger = o.optBoolean("isTrigger", false),
                    oneWay = o.optBoolean("oneWay", false), slopeType = o.optString("slopeType", "none"),
                    colOffsetX = o.optDouble("colOffsetX", 0.0).toFloat(), colOffsetY = o.optDouble("colOffsetY", 0.0).toFloat(),
                    colWidth = o.optDouble("colWidth", 0.0).toFloat(), colHeight = o.optDouble("colHeight", 0.0).toFloat(),
                    colLayer = o.optInt("colLayer", 1), colMask = o.optInt("colMask", 0xFFFF),
                    bodyMode = o.optString("bodyMode", "kinematic"),
                    gravity = o.optDouble("gravity", 2200.0).toFloat(), fallMultiplier = o.optDouble("fallMultiplier", 1.55).toFloat(),
                    maxFallSpeed = o.optDouble("maxFallSpeed", 1450.0).toFloat(), bounciness = o.optDouble("bounciness", 0.5).toFloat(),
                    drag = o.optDouble("drag", 0.0).toFloat(), maxJumps = o.optInt("maxJumps", 2),
                    isHitbox = o.optBoolean("isHitbox", false), isHurtbox = o.optBoolean("isHurtbox", false),
                    damage = o.optDouble("damage", 10.0).toFloat(),
                    hFrames = o.optInt("hFrames", 1), vFrames = o.optInt("vFrames", 1),
                    currentFrame = o.optInt("currentFrame", 0), animFps = o.optDouble("animFps", 12.0).toFloat(),
                    clips = clipMap, tileSize = o.optDouble("tileSize", 90.0).toFloat(),
                    tiles = tileList, tileLookup = tileMapLookup,
                    emitRate = o.optDouble("emitRate", 24.0).toFloat(), particleLife = o.optDouble("particleLife", 1.1).toFloat(),
                    particleSpeed = o.optDouble("particleSpeed", 160.0).toFloat(), particleSize = o.optDouble("particleSize", 16.0).toFloat(),
                    particleGravity = o.optDouble("particleGravity", -60.0).toFloat(),
                    shader = o.optString("shader", ""),
                    script = resolveNodeScriptText(o.optString("script", ""), o.optString("scriptFile", ""))
                )
                // P7：解析专业动画库与状态机
                ZeroAnimator.parseInto(node.animator, o)
                if (defaultClipName.isNotEmpty()) {
                    if (node.animator.play(defaultClipName, true)) {
                        node.currentClipName = defaultClipName
                    } else if (clipMap.containsKey(defaultClipName)) {
                        val dc = clipMap[defaultClipName]!!
                        node.currentClipName = defaultClipName; node.animStart = dc.start; node.animEnd = dc.end
                        node.animFps = dc.fps; node.animLoop = dc.loop; node.currentFrame = dc.start; node.animPlaying = true
                    }
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
        textureSizes[fileName] = bmp.width to bmp.height
        val id = uploadBitmapToGL(bmp)
        bmp.recycle()
        textureCache[fileName] = id
        return id
    }

    private fun getTextTexture(text: String, size: Float): Int {
        val key = "$text|$size"
        textTextureCache[key]?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size; isFakeBoldText = true }
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
        val id = uploadBitmapToGL(bmp); bmp.recycle(); return id
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
        GLES20.glAttachShader(prog, vs); GLES20.glAttachShader(prog, fs); GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src); GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            onConsoleLog("[GLSL 错误] ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader); return 0
        }
        return shader
    }

    private fun jsFunc(block: (Array<out Any?>) -> Any?): org.mozilla.javascript.BaseFunction =
        object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: RhinoContext?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>): Any? = block(args)
        }

    private fun toJsFloat(v: Any?, def: Float): Float = (v as? Number)?.toFloat() ?: def
    private fun toJsInt(v: Any?, def: Int): Int = (v as? Number)?.toInt() ?: def
    private fun toJsStr(v: Any?, def: String): String = v?.takeIf { it !== Scriptable.NOT_FOUND }?.toString() ?: def
    private fun toJsBool(v: Any?, def: Boolean): Boolean = (v as? Boolean) ?: def
    private fun getJsFloat(s: Scriptable, k: String, def: Float): Float = (ScriptableObject.getProperty(s, k) as? Number)?.toFloat() ?: def
    private fun getJsInt(s: Scriptable, k: String, def: Int): Int = (ScriptableObject.getProperty(s, k) as? Number)?.toInt() ?: def
    private fun getJsStr(s: Scriptable, k: String, def: String): String = ScriptableObject.getProperty(s, k)?.takeIf { it !== Scriptable.NOT_FOUND }?.toString() ?: def
    private fun getJsBool(s: Scriptable, k: String, def: Boolean): Boolean = (ScriptableObject.getProperty(s, k) as? Boolean) ?: def

    companion object {
        private val BRIDGED_PROPS = setOf(
            "name", "tag", "sceneGroup", "persistent",
            "x", "y", "z", "vx", "vy", "vz",
            "width", "height", "rotation", "tiltX", "scaleX", "scaleY",
            "parallaxX", "parallaxY", "atlas", "atlasFrame",
            "sliceLeft", "sliceRight", "sliceTop", "sliceBottom",
            "clipChildren", "clipX", "clipY", "clipW", "clipH",
            "slopeType", "colOffsetX", "colOffsetY", "colWidth", "colHeight", "colLayer", "colMask",
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

        private const val BATCH_VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            attribute vec4 aColor;
            attribute float aFlash;
            uniform mat4 uVP;
            varying vec2 vTexCoord;
            varying vec4 vColor;
            varying float vFlash;
            varying vec3 vWorldPos;
            void main() {
                vWorldPos = aPosition;
                vTexCoord = aTexCoord;
                vColor = aColor;
                vFlash = aFlash;
                gl_Position = uVP * vec4(aPosition, 1.0);
            }
        """

        private const val BATCH_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform int uEnableLight;
            uniform vec3 uAmbient;
            uniform int uLightCount;
            uniform vec4 uLightPos[8];
            uniform vec4 uLightColor[8];
            varying vec2 vTexCoord;
            varying vec4 vColor;
            varying float vFlash;
            varying vec3 vWorldPos;
            void main() {
                vec4 base = texture2D(uTexture, vTexCoord) * vColor;
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
                vec3 finalRgb = mix(litRgb, vec3(1.0, 1.0, 1.0), clamp(vFlash, 0.0, 1.0));
                gl_FragColor = vec4(finalRgb, base.a);
            }
        """

        private const val LIGHT_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            varying vec2 vTexCoord;
            void main() {
                vec2 d = (vTexCoord - vec2(0.5, 0.5)) * 2.0;
                float dist = length(d);
                float atten = clamp(1.0 - dist, 0.0, 1.0);
                atten = atten * atten;
                gl_FragColor = vec4(uColor.rgb * atten, 1.0);
            }
        """

        private const val POST_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val POST_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uSceneTex;
            uniform sampler2D uLightTex;
            uniform vec2 uTexelSize;
            uniform float uBloom;
            uniform float uVignette;
            uniform float uChromatic;
            uniform float uFade;
            varying vec2 vTexCoord;
            void main() {
                vec2 uv = vTexCoord;
                vec3 baseRgb;
                if (uChromatic > 0.0001) {
                    float off = uChromatic * 0.015;
                    float r = texture2D(uSceneTex, vec2(clamp(uv.x + off, 0.0, 1.0), uv.y)).r;
                    float g = texture2D(uSceneTex, uv).g;
                    float b = texture2D(uSceneTex, vec2(clamp(uv.x - off, 0.0, 1.0), uv.y)).b;
                    baseRgb = vec3(r, g, b);
                } else {
                    baseRgb = texture2D(uSceneTex, uv).rgb;
                }
                vec3 lightRgb = texture2D(uLightTex, uv).rgb;
                vec3 col = baseRgb * lightRgb;

                if (uBloom > 0.001) {
                    vec2 stepUv = uTexelSize * 3.5;
                    vec3 b0 = texture2D(uSceneTex, clamp(uv + vec2( stepUv.x,  stepUv.y), 0.0, 1.0)).rgb * texture2D(uLightTex, clamp(uv + vec2( stepUv.x,  stepUv.y), 0.0, 1.0)).rgb;
                    vec3 b1 = texture2D(uSceneTex, clamp(uv + vec2(-stepUv.x,  stepUv.y), 0.0, 1.0)).rgb * texture2D(uLightTex, clamp(uv + vec2(-stepUv.x,  stepUv.y), 0.0, 1.0)).rgb;
                    vec3 b2 = texture2D(uSceneTex, clamp(uv + vec2( stepUv.x, -stepUv.y), 0.0, 1.0)).rgb * texture2D(uLightTex, clamp(uv + vec2( stepUv.x, -stepUv.y), 0.0, 1.0)).rgb;
                    vec3 b3 = texture2D(uSceneTex, clamp(uv + vec2(-stepUv.x, -stepUv.y), 0.0, 1.0)).rgb * texture2D(uLightTex, clamp(uv + vec2(-stepUv.x, -stepUv.y), 0.0, 1.0)).rgb;
                    vec3 avg = (b0 + b1 + b2 + b3) * 0.25;
                    vec3 bright = max(avg - vec3(0.55), vec3(0.0));
                    col += bright * uBloom * 1.8;
                }

                if (uVignette > 0.001) {
                    vec2 d = uv - vec2(0.5, 0.5);
                    float vig = 1.0 - dot(d, d) * (uVignette * 2.2);
                    col *= clamp(vig, 0.0, 1.0);
                }

                col = mix(col, vec3(0.0, 0.0, 0.0), clamp(uFade, 0.0, 1.0));
                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
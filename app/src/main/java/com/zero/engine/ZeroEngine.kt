package com.zero.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
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

class ZeroEngineView(
    context: Context,
    private val projectDir: File,
    private val sceneJson: String,
    private val onConsoleLog: (String) -> Unit
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    data class AnimClip(val name: String, val start: Int, val end: Int, val fps: Float, val loop: Boolean, val events: Map<Int, String> = emptyMap())
    data class TileCell(val gx: Int, val gy: Int, val frame: Int)
    data class Particle(var active: Boolean = false, var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f, var size: Float = 12f, var life: Float = 0f, var maxLife: Float = 1f, var alpha: Float = 1f)
    data class TweenTask(val node: GameNode, val prop: String, val startVal: Float, val targetVal: Float, val duration: Float, val ease: String, var elapsed: Float = 0f)
    data class AdditiveLoadReq(val sceneFile: String, val offsetX: Float, val offsetY: Float)
    data class GlobalTransform(val gx: Float, val gy: Float, val gz: Float, val gRot: Float, val gSx: Float, val gSy: Float)

    data class GameNode(
        var name: String, var nodeType: String = "Sprite2D", var layer: String = "World", var parent: String = "",
        var tag: String = "default", var sceneGroup: String = "main", var persistent: Boolean = false,
        var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var width: Float = 120f, var height: Float = 120f,
        var rotation: Float = 0f, var tiltX: Float = 0f, var billboard: Boolean = false, var scaleX: Float = 1f, var scaleY: Float = 1f,
        var zIndex: Int = 0, var visible: Boolean = true, var parallaxX: Float = 1f, var parallaxY: Float = 1f,
        var texture: String = "", var atlas: String = "", var atlasFrame: String = "",
        var text: String = "", var fontSize: Float = 44f, var r: Float = 1f, var g: Float = 1f, var b: Float = 1f, var a: Float = 1f,
        var flashTimer: Float = 0f, var lightRadius: Float = 450f, var lightIntensity: Float = 1.4f, var shadowCaster: Boolean = false,
        var sliceLeft: Float = 0f, var sliceRight: Float = 0f, var sliceTop: Float = 0f, var sliceBottom: Float = 0f,
        var clipChildren: Boolean = false, var clipX: Float = 0f, var clipY: Float = 0f, var clipW: Float = 0f, var clipH: Float = 0f,
        var collider: Boolean = false, var isTrigger: Boolean = false, var oneWay: Boolean = false, var slopeType: String = "none",
        var colOffsetX: Float = 0f, var colOffsetY: Float = 0f, var colWidth: Float = 0f, var colHeight: Float = 0f,
        var colLayer: Int = 1, var colMask: Int = 0xFFFF, var bodyMode: String = "kinematic",
        var gravity: Float = 2200f, var fallMultiplier: Float = 1.55f, var maxFallSpeed: Float = 1450f, var bounciness: Float = 0.5f,
        var drag: Float = 0f, var isGrounded: Boolean = false, var coyoteTimer: Float = 0f, var coyoteTimeMax: Float = 0.12f,
        var jumpCount: Int = 0, var maxJumps: Int = 2, var dropThroughTimer: Float = 0f,
        var isHitbox: Boolean = false, var isHurtbox: Boolean = false, var damage: Float = 10f, var invulnTimer: Float = 0f,
        var hitCooldownMap: MutableMap<String, Float> = mutableMapOf(),
        var hFrames: Int = 1, var vFrames: Int = 1, var currentFrame: Int = 0, var animStart: Int = 0, var animEnd: Int = 0,
        var animFps: Float = 12f, var animPlaying: Boolean = false, var animLoop: Boolean = true, var animTimer: Float = 0f,
        var currentClipName: String = "", val clips: MutableMap<String, AnimClip> = mutableMapOf(),
        val animator: ZeroAnimator = ZeroAnimator(),
        var tileSize: Float = 90f, val tiles: MutableList<TileCell> = mutableListOf(), val tileLookup: HashMap<Long, TileCell> = HashMap(),
        var tileVboId: Int = 0, var tileVertexCount: Int = 0, var tileDirty: Boolean = true,
        var tileMinX: Float = 0f, var tileMaxX: Float = 0f, var tileMinY: Float = 0f, var tileMaxY: Float = 0f,
        var emitRate: Float = 24f, var particleLife: Float = 1.1f, var particleSpeed: Float = 160f, var particleSize: Float = 16f,
        var particleGravity: Float = -60f, var emitAccum: Float = 0f, val particles: Array<Particle> = Array(240) { Particle() },
        var vx: Float = 0f, var vy: Float = 0f, var vz: Float = 0f, var shader: String = "", var script: String = "",
        var jsObj: Scriptable? = null, var customProgram: Int = 0
    )

    private inner class GameNodeJsProxy(val n: GameNode) : ScriptableObject() {
        override fun getClassName() = "GameNode"
        override fun has(name: String?, start: Scriptable?): Boolean = if (name != null && name in BRIDGED_PROPS) true else super.has(name, start)
        override fun get(name: String?, start: Scriptable?): Any? = when (name) {
            "name" -> n.name; "tag" -> n.tag; "sceneGroup" -> n.sceneGroup; "persistent" -> n.persistent
            "x" -> n.x.toDouble(); "y" -> n.y.toDouble(); "z" -> n.z.toDouble()
            "vx" -> n.vx.toDouble(); "vy" -> n.vy.toDouble(); "vz" -> n.vz.toDouble()
            "width" -> n.width.toDouble(); "height" -> n.height.toDouble(); "rotation" -> n.rotation.toDouble()
            "tiltX" -> n.tiltX.toDouble(); "scaleX" -> n.scaleX.toDouble(); "scaleY" -> n.scaleY.toDouble()
            "parallaxX" -> n.parallaxX.toDouble(); "parallaxY" -> n.parallaxY.toDouble()
            "atlas" -> n.atlas; "atlasFrame" -> n.atlasFrame
            "sliceLeft" -> n.sliceLeft.toDouble(); "sliceRight" -> n.sliceRight.toDouble()
            "sliceTop" -> n.sliceTop.toDouble(); "sliceBottom" -> n.sliceBottom.toDouble()
            "clipChildren" -> n.clipChildren; "clipX" -> n.clipX.toDouble(); "clipY" -> n.clipY.toDouble()
            "clipW" -> n.clipW.toDouble(); "clipH" -> n.clipH.toDouble()
            "slopeType" -> n.slopeType; "colOffsetX" -> n.colOffsetX.toDouble(); "colOffsetY" -> n.colOffsetY.toDouble()
            "colWidth" -> n.colWidth.toDouble(); "colHeight" -> n.colHeight.toDouble()
            "colLayer" -> n.colLayer; "colMask" -> n.colMask; "bodyMode" -> n.bodyMode
            "gravity" -> n.gravity.toDouble(); "fallMultiplier" -> n.fallMultiplier.toDouble(); "maxJumps" -> n.maxJumps
            "isGrounded" -> n.isGrounded; "isHitbox" -> n.isHitbox; "isHurtbox" -> n.isHurtbox
            "damage" -> n.damage.toDouble(); "invulnTimer" -> n.invulnTimer.toDouble()
            "r" -> n.r.toDouble(); "g" -> n.g.toDouble(); "b" -> n.b.toDouble(); "a" -> n.a.toDouble()
            "visible" -> n.visible; "text" -> n.text
            "currentClip" -> n.animator.currentClip?.name ?: n.currentClipName
            "lightRadius" -> n.lightRadius.toDouble(); "lightIntensity" -> n.lightIntensity.toDouble()
            else -> super.get(name, start)
        }
        override fun put(name: String?, start: Scriptable?, value: Any?) {
            when (name) {
                "tag" -> n.tag = toJsStr(value, n.tag); "persistent" -> n.persistent = toJsBool(value, n.persistent)
                "x" -> n.x = toJsFloat(value, n.x); "y" -> n.y = toJsFloat(value, n.y); "z" -> n.z = toJsFloat(value, n.z)
                "vx" -> n.vx = toJsFloat(value, n.vx); "vy" -> n.vy = toJsFloat(value, n.vy); "vz" -> n.vz = toJsFloat(value, n.vz)
                "width" -> n.width = toJsFloat(value, n.width); "height" -> n.height = toJsFloat(value, n.height)
                "rotation" -> n.rotation = toJsFloat(value, n.rotation); "tiltX" -> n.tiltX = toJsFloat(value, n.tiltX)
                "scaleX" -> n.scaleX = toJsFloat(value, n.scaleX); "scaleY" -> n.scaleY = toJsFloat(value, n.scaleY)
                "parallaxX" -> n.parallaxX = toJsFloat(value, n.parallaxX); "parallaxY" -> n.parallaxY = toJsFloat(value, n.parallaxY)
                "atlas" -> n.atlas = toJsStr(value, n.atlas); "atlasFrame" -> n.atlasFrame = toJsStr(value, n.atlasFrame)
                "sliceLeft" -> n.sliceLeft = toJsFloat(value, n.sliceLeft); "sliceRight" -> n.sliceRight = toJsFloat(value, n.sliceRight)
                "sliceTop" -> n.sliceTop = toJsFloat(value, n.sliceTop); "sliceBottom" -> n.sliceBottom = toJsFloat(value, n.sliceBottom)
                "clipChildren" -> n.clipChildren = toJsBool(value, n.clipChildren)
                "clipX" -> n.clipX = toJsFloat(value, n.clipX); "clipY" -> n.clipY = toJsFloat(value, n.clipY)
                "clipW" -> n.clipW = toJsFloat(value, n.clipW); "clipH" -> n.clipH = toJsFloat(value, n.clipH)
                "slopeType" -> n.slopeType = toJsStr(value, n.slopeType)
                "colOffsetX" -> n.colOffsetX = toJsFloat(value, n.colOffsetX); "colOffsetY" -> n.colOffsetY = toJsFloat(value, n.colOffsetY)
                "colWidth" -> n.colWidth = toJsFloat(value, n.colWidth); "colHeight" -> n.colHeight = toJsFloat(value, n.colHeight)
                "colLayer" -> n.colLayer = toJsInt(value, n.colLayer); "colMask" -> n.colMask = toJsInt(value, n.colMask)
                "bodyMode" -> n.bodyMode = toJsStr(value, n.bodyMode)
                "gravity" -> n.gravity = toJsFloat(value, n.gravity); "fallMultiplier" -> n.fallMultiplier = toJsFloat(value, n.fallMultiplier)
                "maxJumps" -> n.maxJumps = toJsInt(value, n.maxJumps)
                "isHitbox" -> n.isHitbox = toJsBool(value, n.isHitbox); "isHurtbox" -> n.isHurtbox = toJsBool(value, n.isHurtbox)
                "damage" -> n.damage = toJsFloat(value, n.damage); "invulnTimer" -> n.invulnTimer = toJsFloat(value, n.invulnTimer)
                "r" -> n.r = toJsFloat(value, n.r); "g" -> n.g = toJsFloat(value, n.g); "b" -> n.b = toJsFloat(value, n.b); "a" -> n.a = toJsFloat(value, n.a)
                "visible" -> n.visible = toJsBool(value, n.visible); "text" -> n.text = toJsStr(value, n.text)
                "lightRadius" -> n.lightRadius = toJsFloat(value, n.lightRadius); "lightIntensity" -> n.lightIntensity = toJsFloat(value, n.lightIntensity)
                "name", "sceneGroup", "isGrounded", "currentClip" -> Unit
                else -> super.put(name, start, value)
            }
        }
    }

    private val audioSystem = ZeroAudioSystem(projectDir, onConsoleLog)
    private val inputSystem = ZeroInputSystem()
    private val spriteBatch = ZeroSpriteBatch(1024)
    private val dataStore = ZeroDataStore(projectDir, onConsoleLog)
    private val physicsSystem = ZeroPhysicsSystem(onConsoleLog)

    private val atlasCache = ConcurrentHashMap<String, TextureAtlasData>()
    private val nodes = mutableListOf<GameNode>()
    private val pendingAdd = mutableListOf<GameNode>()
    private val pendingRemove = mutableSetOf<GameNode>()
    private val activeTweens = mutableListOf<TweenTask>()
    private val textureCache = ConcurrentHashMap<String, Int>()
    private val textureSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val eventListeners = mutableMapOf<String, MutableList<Function>>()
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private val globalDataStore = ConcurrentHashMap<String, Any?>()
    private val preloadedSceneCache = ConcurrentHashMap<String, String>()
    private val sceneIoExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var pendingSceneLoad: String? = null
    private val pendingAdditiveLoads = mutableListOf<AdditiveLoadReq>()
    private val pendingUnloadScenes = mutableListOf<String>()

    private var postFxEnabled = true; private var fboReady = false
    private var sceneFbo = 0; private var sceneColorTex = 0; private var lightFbo = 0; private var lightMapTex = 0
    private var lightW = 540; private var lightH = 960
    private var lightProgram = 0; private var postProgram = 0; private var batchProgram = 0
    private var postBloom = 0.25f; private var postVignette = 0.28f; private var postChromatic = 0.0f; private var postFade = 0.0f
    private var fadeTarget = 0.0f; private var fadeSpeed = 0.0f; private var pendingFadeScene: String? = null; private var fadeSceneDur = 0.35f

    private val fullScreenQuadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }
    private val textTextureCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            if (size > 64 && eldest != null && eldest.value != 0 && eldest.value != whiteTexId) {
                GLES20.glDeleteTextures(1, intArrayOf(eldest.value), 0); return true
            }
            return false
        }
    }

    private var camMode = "2.5D"; private var camX = 540f; private var camY = 960f; private var camPitch = 48f; private var camDistance = 1250f; private var camFov = 45f
    private var camTarget: GameNode? = null; private var camSmoothSpeed = 9f; private var camDeadzoneX = 0f; private var camDeadzoneY = 0f
    private var camLookAheadX = 0f; private var camLookAheadY = 0f
    private var camBoundsEnabled = false; private var camMinX = -99999f; private var camMinY = -99999f; private var camMaxX = 99999f; private var camMaxY = 99999f
    private var shakeIntensity = 0f; private var shakeTimer = 0f
    private var viewMinX = -99999f; private var viewMaxX = 99999f; private var viewMinY = -99999f; private var viewMaxY = 99999f

    private var smoothedFps = 60f; private var frameDrawCalls = 0; private var lastFrameDrawCalls = 0; private var frameCulled = 0; private var lastFrameCulled = 0; private var lastFrameLights = 0
    private var hitstopTimer = 0f; private var timeScale = 1f
    private var ambientR = 0.52f; private var ambientG = 0.56f; private var ambientB = 0.68f

    @Volatile private var isTouching = false; @Volatile private var touchScreenX = 0f; @Volatile private var touchScreenY = 0f
    @Volatile private var touchWorldX = 0f; @Volatile private var touchWorldY = 0f
    private val pendingTouchEvents = mutableListOf<Triple<String, Float, Float>>()

    private val projMatrix = FloatArray(16); private val viewMatrix = FloatArray(16); private val vpMatrix = FloatArray(16); private val invVpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16); private val subModelMatrix = FloatArray(16); private val mvpMatrix = FloatArray(16); private val uiVpMatrix = FloatArray(16)

    private var defaultLitProgram = 0; private var whiteTexId = 0; private var engineTime = 0f; private var lastFrameNs = 0L; private var surfaceW = 1080; private var surfaceH = 1920
    private lateinit var rhino: RhinoContext; private lateinit var globalScope: ScriptableObject

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-0.5f, -0.5f, 0f, 0f, 0f, 0.5f, -0.5f, 0f, 1f, 0f, -0.5f, 0.5f, 0f, 0f, 1f, 0.5f, 0.5f, 0f, 1f, 1f)); position(0)
    }
    private val slopeLeftUpBuffer: FloatBuffer = ByteBuffer.allocateDirect(3 * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0.5f, -0.5f, 0f, 1f, 0f, -0.5f, 0.5f, 0f, 0f, 1f, 0.5f, 0.5f, 0f, 1f, 1f)); position(0)
    }
    private val slopeRightUpBuffer: FloatBuffer = ByteBuffer.allocateDirect(3 * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-0.5f, -0.5f, 0f, 0f, 0f, -0.5f, 0.5f, 0f, 0f, 1f, 0.5f, 0.5f, 0f, 1f, 1f)); position(0)
    }

    init {
        isFocusable = true; isFocusableInTouchMode = true; requestFocus()
        setEGLContextClientVersion(2); setRenderer(this); renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun normalizeSceneName(name: String): String = if (name.endsWith(".scene")) name else "$name.scene"
    private fun queryAtlas(name: String): TextureAtlasData? =
        ZeroShaders.loadAtlasData(projectDir, name, atlasCache, textureSizes, { getImageTexture(it) }, onConsoleLog)

    fun releaseResources() {
        try { sceneIoExecutor.shutdownNow() } catch (_: Exception) {}
        inputSystem.clearAllPointers()
        queueEvent {
            releaseFbos(); releaseSceneGpuResources(includePersistent = true)
            val texIds = (textureCache.values + textTextureCache.values).filter { it != 0 && it != whiteTexId }.toIntArray()
            if (texIds.isNotEmpty()) GLES20.glDeleteTextures(texIds.size, texIds, 0)
            textureCache.clear(); textureSizes.clear(); textTextureCache.clear()
        }
        audioSystem.release()
    }

    private fun unloadUnusedTextures(): Int {
        if (textureCache.isEmpty()) return 0
        val active = HashSet<String>()
        for (n in (nodes + pendingAdd)) {
            if (pendingRemove.contains(n)) continue
            if (n.texture.isNotEmpty()) active.add(n.texture)
            if (n.atlas.isNotEmpty()) queryAtlas(n.atlas)?.let { active.add(it.imageFile) }
        }
        val delKeys = textureCache.keys.filter { it !in active }
        val ids = delKeys.mapNotNull { textureCache[it] }.filter { it != 0 && it != whiteTexId }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        delKeys.forEach { textureCache.remove(it); textureSizes.remove(it) }
        return delKeys.size
    }

    private fun releaseNodeGpuResources(n: GameNode) {
        if (n.tileVboId != 0) { GLES20.glDeleteBuffers(1, intArrayOf(n.tileVboId), 0); n.tileVboId = 0; n.tileVertexCount = 0 }
        if (n.customProgram != 0 && n.customProgram != defaultLitProgram) { GLES20.glDeleteProgram(n.customProgram); n.customProgram = 0 }
    }

    private fun releaseSceneGpuResources(includePersistent: Boolean) =
        nodes.forEach { if (includePersistent || !it.persistent) releaseNodeGpuResources(it) }

    private fun releaseFbos() {
        fboReady = false
        listOf(sceneFbo, sceneColorTex, lightFbo, lightMapTex).filter { it != 0 }.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        listOf(sceneFbo, lightFbo).filter { it != 0 }.forEach { GLES20.glDeleteFramebuffers(1, intArrayOf(it), 0) }
        sceneFbo = 0; sceneColorTex = 0; lightFbo = 0; lightMapTex = 0
    }

    private fun recreateFbos(w: Int, h: Int) {
        releaseFbos(); lightW = max(1, w / 2); lightH = max(1, h / 2)
        val (sf, st) = ZeroShaders.createFboTarget(w, h); val (lf, lt) = ZeroShaders.createFboTarget(lightW, lightH)
        if (sf != 0 && lf != 0) { sceneFbo = sf; sceneColorTex = st; lightFbo = lf; lightMapTex = lt; fboReady = (lightProgram != 0 && postProgram != 0) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f); GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        whiteTexId = ZeroShaders.createSolidWhiteTexture()
        defaultLitProgram = ZeroShaders.buildShaderProgram(ZeroShaders.VERTEX_SHADER, ZeroShaders.DEFAULT_LIT_FRAGMENT_SHADER, onConsoleLog)
        lightProgram = ZeroShaders.buildShaderProgram(ZeroShaders.VERTEX_SHADER, ZeroShaders.LIGHT_FRAGMENT_SHADER, onConsoleLog)
        postProgram = ZeroShaders.buildShaderProgram(ZeroShaders.POST_VERTEX_SHADER, ZeroShaders.POST_FRAGMENT_SHADER, onConsoleLog)
        batchProgram = ZeroShaders.buildShaderProgram(ZeroShaders.BATCH_VERTEX_SHADER, ZeroShaders.BATCH_FRAGMENT_SHADER, onConsoleLog)
        spriteBatch.init(batchProgram)
        initRhinoVM(); dataStore.loadI18nFromDiskIfNeeded(dataStore.currentLang)
        loadSceneContent(sceneJson, groupName = "main", additive = false); lastFrameNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = max(1, width); surfaceH = max(1, height); GLES20.glViewport(0, 0, surfaceW, surfaceH)
        Matrix.orthoM(uiVpMatrix, 0, 0f, 1080f, 1920f, 0f, -100f, 100f); recreateFbos(surfaceW, surfaceH)
    }

    private fun readSceneText(sceneFile: String): String? {
        val norm = normalizeSceneName(sceneFile)
        preloadedSceneCache[norm]?.let { return it }
        val file = File(File(projectDir, "scenes"), norm)
        return if (file.exists()) file.readText().also { preloadedSceneCache[norm] = it } else null
    }

    private fun preloadSceneAsync(sceneFile: String) {
        val norm = normalizeSceneName(sceneFile)
        if (preloadedSceneCache.containsKey(norm)) return
        sceneIoExecutor.execute {
            try { File(File(projectDir, "scenes"), norm).takeIf { it.exists() }?.let { preloadedSceneCache[norm] = it.readText() } } catch (_: Exception) {}
        }
    }

    private fun loadSceneContent(rawJson: String, groupName: String, additive: Boolean, offsetX: Float = 0f, offsetY: Float = 0f) {
        if (!additive) {
            releaseSceneGpuResources(includePersistent = false)
            val kept = nodes.filter { it.persistent }
            nodes.clear(); nodes.addAll(kept); pendingAdd.removeAll { !it.persistent }; pendingRemove.clear()
            activeTweens.removeAll { !it.node.persistent }; physicsSystem.clearRays()
            if (camTarget?.persistent != true) camTarget = null; camBoundsEnabled = false
        }
        val newNodes = mutableListOf<GameNode>()
        parseSceneJson(rawJson, newNodes, groupName, applyCamera = !additive)
        if (additive && (offsetX != 0f || offsetY != 0f)) {
            newNodes.forEach { if (it.parent.isEmpty() && it.layer == "World") { it.x += offsetX; it.y += offsetY } }
        }
        nodes.addAll(newNodes)
        if (!additive) unloadUnusedTextures()
        newNodes.forEach { bindNodeScript(it) }
        physicsSystem.rebuildSpatialIndex(nodes, pendingRemove) { getGlobalTransform(it) }
        newNodes.forEach { callLifecycle(it, "onReady") }
    }

    private fun updateFadeTransition(rawDt: Float) {
        if (fadeSpeed <= 0f) return
        val step = fadeSpeed * rawDt
        postFade = if (postFade < fadeTarget) min(fadeTarget, postFade + step) else max(fadeTarget, postFade - step)
        if (abs(postFade - fadeTarget) < 0.001f) {
            postFade = fadeTarget; fadeSpeed = 0f
            pendingFadeScene?.takeIf { postFade >= 0.99f }?.let { next ->
                pendingFadeScene = null; pendingSceneLoad = next; fadeTarget = 0f; fadeSpeed = 1f / max(0.05f, fadeSceneDur)
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val rawDt = ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
        lastFrameNs = now; smoothedFps = smoothedFps * 0.9f + (1f / rawDt) * 0.1f

        inputSystem.beginFrame(); audioSystem.update(rawDt); updateFadeTransition(rawDt)
        pendingSceneLoad?.let { pendingSceneLoad = null; readSceneText(it)?.let { txt -> loadSceneContent(txt, normalizeSceneName(it), false) } }
        if (pendingAdditiveLoads.isNotEmpty()) {
            val reqs = pendingAdditiveLoads.toList(); pendingAdditiveLoads.clear()
            reqs.forEach { r -> readSceneText(r.sceneFile)?.let { txt -> loadSceneContent(txt, normalizeSceneName(r.sceneFile), true, r.offsetX, r.offsetY) } }
        }
        if (pendingUnloadScenes.isNotEmpty()) {
            val groups = pendingUnloadScenes.toSet(); pendingUnloadScenes.clear()
            nodes.forEach { if (!it.persistent && it.sceneGroup in groups) pendingRemove.add(it) }
        }
        if (hitstopTimer > 0f) { hitstopTimer = max(0f, hitstopTimer - rawDt); updateCameraMatrices(rawDt); renderSceneFrame(); return }

        val dt = rawDt * timeScale; engineTime += dt; physicsSystem.updateRays(rawDt)
        updateCameraMatrices(dt); updateWorldTouchFromScreen(); updateTweens(dt); flushTouchEvents()

        if (pendingAdd.isNotEmpty()) {
            val added = pendingAdd.toList(); pendingAdd.clear(); nodes.addAll(added)
            added.forEach { bindNodeScript(it); callLifecycle(it, "onReady") }
        }
        physicsSystem.rebuildSpatialIndex(nodes, pendingRemove) { getGlobalTransform(it) }

        nodes.toList().forEach { n ->
            if (!pendingRemove.contains(n)) {
                if (n.flashTimer > 0f) n.flashTimer = max(0f, n.flashTimer - dt)
                if (n.invulnTimer > 0f) n.invulnTimer = max(0f, n.invulnTimer - dt)
                if (n.dropThroughTimer > 0f) n.dropThroughTimer = max(0f, n.dropThroughTimer - dt)
                n.hitCooldownMap.entries.removeAll { (_, v) -> v - dt <= 0f }
                if (n.animator.clips.isNotEmpty() || n.animator.currentClip != null) updateProAnimator(n, dt) else updateSpriteAnimation(n, dt)
                if (n.nodeType == "ParticleEmitter") updateParticleEmitter(n, dt)
                callLifecycle(n, "onProcess", dt)
            }
        }
        physicsSystem.rebuildSpatialIndex(nodes, pendingRemove) { getGlobalTransform(it) }
        physicsSystem.processCombatOverlaps(nodes, pendingRemove, rhino, { getGlobalTransform(it) }, { node, fn, arg -> callLifecycle(node, fn, arg) }) { hs ->
            hitstopTimer = max(hitstopTimer, hs.coerceIn(0f, 0.35f))
        }
        if (pendingRemove.isNotEmpty()) { pendingRemove.forEach { releaseNodeGpuResources(it) }; nodes.removeAll(pendingRemove); pendingRemove.clear() }
        renderSceneFrame()
    }

    private fun updateProAnimator(n: GameNode, dt: Float) {
        n.animator.update(dt, { clip, ev -> callFrameEvent(n, clip, ev) }, { clip -> callLifecycle(n, "onAnimFinished", clip) })
        n.animator.activeFrame?.let { af -> n.currentFrame = af.frameIndex; if (af.atlasFrame.isNotEmpty()) n.atlasFrame = af.atlasFrame }
        n.animator.activeBonePoses.forEach { pose ->
            val target = nodes.find { it.name == pose.targetNodeName && (it.parent == n.name || it === n) } ?: nodes.find { it.name == pose.targetNodeName }
            target?.let { t -> pose.x?.let { t.x = it }; pose.y?.let { t.y = it }; pose.rotation?.let { t.rotation = it }; pose.scaleX?.let { t.scaleX = it }; pose.scaleY?.let { t.scaleY = it } }
        }
    }

    private fun callFrameEvent(n: GameNode, clipName: String, evName: String) {
        val scope = n.jsObj?.prototype ?: return
        (ScriptableObject.getProperty(scope, "onFrameEvent") as? Function)?.let {
            try { it.call(rhino, scope, n.jsObj, arrayOf(clipName, evName)) } catch (e: Exception) { onConsoleLog("[JS 错误 @ ${n.name}.onFrameEvent] ${e.message}") }
        }
    }

    private fun isBoxInView(minX: Float, maxX: Float, minY: Float, maxY: Float): Boolean = maxX >= viewMinX && minX <= viewMaxX && maxY >= viewMinY && minY <= viewMaxY

    private fun resolveUiClipRect(n: GameNode): FloatArray? {
        var left = Float.NEGATIVE_INFINITY; var top = Float.NEGATIVE_INFINITY; var right = Float.POSITIVE_INFINITY; var bottom = Float.POSITIVE_INFINITY; var hasClip = false
        if (n.clipW > 0f && n.clipH > 0f) { left = max(left, n.clipX); top = max(top, n.clipY); right = min(right, n.clipX + n.clipW); bottom = min(bottom, n.clipY + n.clipH); hasClip = true }
        var pName = n.parent; var guard = 0
        while (pName.isNotEmpty() && guard++ < 16) {
            val p = nodes.find { it.name == pName } ?: break
            if (p.clipChildren && p.layer == "UI") {
                val pgt = getGlobalTransform(p); val pw = abs(p.width * pgt.gSx); val ph = abs(p.height * pgt.gSy)
                left = max(left, pgt.gx - pw * 0.5f); top = max(top, pgt.gy - ph * 0.5f); right = min(right, pgt.gx + pw * 0.5f); bottom = min(bottom, pgt.gy + ph * 0.5f); hasClip = true
            }
            pName = p.parent
        }
        return if (hasClip) floatArrayOf(left, top, right, bottom) else null
    }

    private fun applyUiScissor(rect: FloatArray) {
        val sx = ((rect[0] / 1080f) * surfaceW).toInt().coerceIn(0, surfaceW); val ex = ((rect[2] / 1080f) * surfaceW).toInt().coerceIn(0, surfaceW)
        val sy = (((1920f - rect[3]) / 1920f) * surfaceH).toInt().coerceIn(0, surfaceH); val ey = (((1920f - rect[1]) / 1920f) * surfaceH).toInt().coerceIn(0, surfaceH)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST); GLES20.glScissor(sx, sy, max(0, ex - sx), max(0, ey - sy))
    }

    private fun renderSceneFrame() {
        frameDrawCalls = 0; frameCulled = 0
        val allLights = nodes.filter { it.visible && it.nodeType == "PointLight" }; lastFrameLights = allLights.size
        val worldNodes = nodes.filter { it.visible && it.layer != "UI" && it.nodeType != "PointLight" }
            .sortedWith(compareBy({ it.zIndex }, { it.nodeType != "TileMap" }, { it.tiltX >= -45f }, { getGlobalTransform(it).gy }))
        val casters = nodes.filter { it.visible && it.layer != "UI" && it.shadowCaster && it.nodeType != "PointLight" }
        val particleNodes = worldNodes.filter { it.nodeType == "ParticleEmitter" }
        val solidWorldNodes = worldNodes.filter { it.nodeType != "ParticleEmitter" }
        val emptyArr = FloatArray(4)

        if (postFxEnabled && fboReady) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo); GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, 0, null, null) { frameDrawCalls++ }
            if (allLights.isNotEmpty()) casters.forEach { c -> val cg = getGlobalTransform(c); val pad = max(c.width * abs(cg.gSx), c.height * abs(cg.gSy)) * 1.6f; if (isBoxInView(cg.gx - pad, cg.gx + pad, cg.gy - pad, cg.gy + pad)) submitDynamicShadowToBatch(c, allLights) }
            solidWorldNodes.forEach { n ->
                when {
                    n.nodeType == "TileMap" -> { spriteBatch.flush(); renderTileMap(n, vpMatrix, true, false, 0, emptyArr, emptyArr) }
                    canBatchNode(n) -> submitNodeToBatch(n, true)
                    else -> { spriteBatch.flush(); renderNodeSingle(n, vpMatrix, true, false, 0, emptyArr, emptyArr) }
                }
            }
            spriteBatch.end(); renderLightsToFbo(allLights)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); renderPostComposite()
            if (particleNodes.isNotEmpty()) {
                spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, 0, null, null) { frameDrawCalls++ }
                particleNodes.forEach { submitParticlesToBatch(it, true, 1f - postFade) }; spriteBatch.end()
            }
        } else {
            val lights = allLights.take(8); val lightPosArr = FloatArray(32); val lightColArr = FloatArray(32)
            lights.forEachIndexed { idx, l -> val gt = getGlobalTransform(l); lightPosArr[idx * 4] = gt.gx; lightPosArr[idx * 4 + 1] = gt.gy; lightPosArr[idx * 4 + 2] = gt.gz; lightPosArr[idx * 4 + 3] = l.lightRadius; lightColArr[idx * 4] = l.r * l.lightIntensity; lightColArr[idx * 4 + 1] = l.g * l.lightIntensity; lightColArr[idx * 4 + 2] = l.b * l.lightIntensity; lightColArr[idx * 4 + 3] = 1f }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, lights.size, lightPosArr, lightColArr) { frameDrawCalls++ }
            if (lights.isNotEmpty()) casters.forEach { c -> val cg = getGlobalTransform(c); val pad = max(c.width * abs(cg.gSx), c.height * abs(cg.gSy)) * 1.6f; if (isBoxInView(cg.gx - pad, cg.gx + pad, cg.gy - pad, cg.gy + pad)) submitDynamicShadowToBatch(c, lights) }
            worldNodes.forEach { n ->
                when {
                    n.nodeType == "ParticleEmitter" -> { spriteBatch.setInlineLight(false); submitParticlesToBatch(n, true, 1f) }
                    n.nodeType == "TileMap" -> { spriteBatch.flush(); renderTileMap(n, vpMatrix, true, true, lights.size, lightPosArr, lightColArr) }
                    canBatchNode(n) -> { spriteBatch.setInlineLight(true); submitNodeToBatch(n, true) }
                    else -> { spriteBatch.flush(); renderNodeSingle(n, vpMatrix, true, true, lights.size, lightPosArr, lightColArr) }
                }
            }
            spriteBatch.end()
        }

        if (physicsSystem.debugDrawEnabled) renderDebugOverlay(vpMatrix)
        val uiNodes = nodes.filter { it.visible && it.layer == "UI" }.sortedBy { it.zIndex }
        if (uiNodes.isNotEmpty()) {
            spriteBatch.begin(uiVpMatrix, false, 1f, 1f, 1f, 0, null, null) { frameDrawCalls++ }
            uiNodes.forEach { n ->
                val clipRect = resolveUiClipRect(n); if (clipRect != null) { spriteBatch.flush(); applyUiScissor(clipRect) }
                when {
                    n.nodeType == "ParticleEmitter" -> submitParticlesToBatch(n, false, 1f)
                    canBatchNode(n) -> submitNodeToBatch(n, false)
                    else -> { spriteBatch.flush(); renderNodeSingle(n, uiVpMatrix, false, false, 0, emptyArr, emptyArr) }
                }
                if (clipRect != null) { spriteBatch.flush(); GLES20.glDisable(GLES20.GL_SCISSOR_TEST) }
            }
            spriteBatch.end()
        }
        if (physicsSystem.debugDrawEnabled) renderTouchZonesDebugOverlay(uiVpMatrix)
        lastFrameDrawCalls = frameDrawCalls; lastFrameCulled = frameCulled
    }

    private fun canBatchNode(n: GameNode): Boolean = n.shader.isBlank() && n.slopeType != "left_up" && n.slopeType != "right_up"

    private fun submitNodeToBatch(n: GameNode, isWorld: Boolean) {
        val gt = getGlobalTransform(n); val w = n.width * gt.gSx; val h = n.height * gt.gSy
        val af = n.animator.activeFrame; val renderX = gt.gx + (af?.offsetX ?: 0f) * gt.gSx; val renderY = gt.gy + (af?.offsetY ?: 0f) * abs(gt.gSy)
        if (isWorld) { val b = max(abs(w), abs(h)) * 0.75f + abs(gt.gz); if (!isBoxInView(renderX - b, renderX + b, renderY - b, renderY + b)) { frameCulled++; return } }

        val atlasData = if (n.atlas.isNotEmpty()) queryAtlas(n.atlas) else null
        val atlasFrame = atlasData?.frames?.get(n.atlasFrame)
        val u0: Float; val u1: Float; val v0: Float; val v1: Float; val texId: Int; val subTexW: Float; val subTexH: Float
        if (atlasData != null && atlasFrame != null && atlasFrame.w > 0f && atlasFrame.h > 0f) {
            texId = getImageTexture(atlasData.imageFile)
            u0 = atlasFrame.x / atlasData.atlasW; u1 = (atlasFrame.x + atlasFrame.w) / atlasData.atlasW
            v0 = atlasFrame.y / atlasData.atlasH; v1 = (atlasFrame.y + atlasFrame.h) / atlasData.atlasH
            subTexW = atlasFrame.w; subTexH = atlasFrame.h
        } else {
            val hf = max(1, n.hFrames); val vf = max(1, n.vFrames); val fi = n.currentFrame.coerceIn(0, hf * vf - 1)
            val uS = 1f / hf; val vS = 1f / vf
            if (isWorld && camMode == "2.5D" && n.nodeType == "Label2D") { u0 = 1f; u1 = 0f } else { u0 = (fi % hf) * uS; u1 = u0 + uS }
            v0 = (fi / hf) * vS; v1 = v0 + vS
            texId = when { n.nodeType == "Label2D" -> getTextTexture(n.text, n.fontSize); n.texture.isNotEmpty() -> getImageTexture(n.texture); else -> whiteTexId }
            val td = textureSizes[n.texture]; subTexW = max(1f, (td?.first ?: max(1f, abs(w)).toInt()).toFloat()) / hf; subTexH = max(1f, (td?.second ?: max(1f, abs(h)).toInt()).toFloat()) / vf
        }

        val flashVal = if (n.flashTimer > 0f) (n.flashTimer * 8f).coerceIn(0f, 1f) else 0f
        val isNineSlice = n.nodeType == "Sprite2D" && (n.sliceLeft > 0f || n.sliceRight > 0f || n.sliceTop > 0f || n.sliceBottom > 0f)
        Matrix.setIdentityM(modelMatrix, 0); Matrix.translateM(modelMatrix, 0, renderX, renderY, gt.gz)
        if (isWorld && camMode == "2.5D" && n.billboard) Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
        else if (isWorld && n.tiltX != 0f) Matrix.rotateM(modelMatrix, 0, n.tiltX, 1f, 0f, 0f)
        if (gt.gRot != 0f) Matrix.rotateM(modelMatrix, 0, gt.gRot, 0f, 0f, 1f)

        if (!isNineSlice) {
            Matrix.scaleM(modelMatrix, 0, w, h, 1f)
            spriteBatch.drawQuad(texId, modelMatrix, u0, v0, u1, v1, n.r, n.g, n.b, n.a, flashVal, false); return
        }
        val absW = max(1f, abs(w)); val absH = max(1f, abs(h)); val signX = if (w < 0f) -1f else 1f; val signY = if (h < 0f) -1f else 1f
        val cX = if (n.sliceLeft + n.sliceRight > absW) absW / (n.sliceLeft + n.sliceRight) else 1f
        val cY = if (n.sliceTop + n.sliceBottom > absH) absH / (n.sliceTop + n.sliceBottom) else 1f
        val xs = floatArrayOf(-absW * 0.5f, -absW * 0.5f + n.sliceLeft * cX, absW * 0.5f - n.sliceRight * cX, absW * 0.5f)
        val ys = floatArrayOf(-absH * 0.5f, -absH * 0.5f + n.sliceTop * cY, absH * 0.5f - n.sliceBottom * cY, absH * 0.5f)
        val du = u1 - u0; val dv = v1 - v0
        val us = floatArrayOf(u0, u0 + (n.sliceLeft / subTexW).coerceIn(0f, 0.49f) * du, u1 - (n.sliceRight / subTexW).coerceIn(0f, 0.49f) * du, u1)
        val vs = floatArrayOf(v0, v0 + (n.sliceTop / subTexH).coerceIn(0f, 0.49f) * dv, v1 - (n.sliceBottom / subTexH).coerceIn(0f, 0.49f) * dv, v1)
        for (r in 0..2) { val sh = ys[r + 1] - ys[r]; if (sh <= 0.01f) continue; val scy = (ys[r] + ys[r + 1]) * 0.5f
            for (c in 0..2) { val sw = xs[c + 1] - xs[c]; if (sw <= 0.01f) continue; val scx = (xs[c] + xs[c + 1]) * 0.5f
                System.arraycopy(modelMatrix, 0, subModelMatrix, 0, 16); Matrix.translateM(subModelMatrix, 0, scx * signX, scy * signY, 0f); Matrix.scaleM(subModelMatrix, 0, sw * signX, sh * signY, 1f)
                spriteBatch.drawQuad(texId, subModelMatrix, us[c], vs[r], us[c + 1], vs[r + 1], n.r, n.g, n.b, n.a, flashVal, false)
            }
        }
    }

    private fun submitDynamicShadowToBatch(caster: GameNode, lights: List<GameNode>) {
        val cg = getGlobalTransform(caster); val cw = caster.width * abs(cg.gSx); val ch = caster.height * abs(cg.gSy)
        lights.forEach { l -> val lg = getGlobalTransform(l); val dx = cg.gx - lg.gx; val dy = cg.gy - lg.gy; val dist = hypot(dx, dy)
            if (dist in 1f..l.lightRadius) { val atten = (1f - dist / l.lightRadius).coerceIn(0f, 1f); val sLen = max(cw, ch) * 1.15f * atten
                Matrix.setIdentityM(modelMatrix, 0); Matrix.translateM(modelMatrix, 0, cg.gx + (dx / dist) * (sLen * 0.5f + cw * 0.35f), cg.gy + (dy / dist) * (sLen * 0.5f + ch * 0.35f), cg.gz - 0.5f)
                Matrix.rotateM(modelMatrix, 0, Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() - 90f, 0f, 0f, 1f); Matrix.scaleM(modelMatrix, 0, cw * 0.9f, sLen, 1f)
                spriteBatch.drawQuad(whiteTexId, modelMatrix, 0f, 0f, 1f, 1f, 0.02f, 0.03f, 0.06f, 0.52f * atten, 0f, false)
            }
        }
    }

    private fun submitParticlesToBatch(n: GameNode, isWorld: Boolean, fadeAlpha: Float) {
        if (fadeAlpha <= 0.001f) return; val gt = getGlobalTransform(n); val texId = if (n.texture.isNotEmpty()) getImageTexture(n.texture) else whiteTexId
        n.particles.forEach { p -> if (!p.active) return@forEach
            if (isWorld && !isBoxInView(p.x - p.size, p.x + p.size, p.y - p.size, p.y + p.size)) { frameCulled++; return@forEach }
            val ratio = (p.life / p.maxLife).coerceIn(0f, 1f); val sz = p.size * (0.25f + 0.75f * ratio)
            Matrix.setIdentityM(modelMatrix, 0); Matrix.translateM(modelMatrix, 0, p.x, p.y, gt.gz + 4f)
            if (isWorld && camMode == "2.5D") Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
            Matrix.scaleM(modelMatrix, 0, sz, sz, 1f)
            spriteBatch.drawQuad(texId, modelMatrix, 0f, 0f, 1f, 1f, n.r, n.g, n.b, p.alpha * ratio * fadeAlpha, 0f, true)
        }
    }

    private fun renderLightsToFbo(lights: List<GameNode>) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, lightFbo); GLES20.glViewport(0, 0, lightW, lightH)
        GLES20.glClearColor(ambientR, ambientG, ambientB, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (lights.isEmpty()) return
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE); GLES20.glUseProgram(lightProgram)
        val posLoc = GLES20.glGetAttribLocation(lightProgram, "aPosition"); val uvLoc = GLES20.glGetAttribLocation(lightProgram, "aTexCoord")
        quadBuffer.position(0); GLES20.glEnableVertexAttribArray(posLoc); GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, quadBuffer)
        quadBuffer.position(3); GLES20.glEnableVertexAttribArray(uvLoc); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, quadBuffer)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(lightProgram, "uUvRect"), 0f, 0f, 1f, 1f)
        lights.forEach { l -> val gt = getGlobalTransform(l); val rad = max(8f, l.lightRadius)
            if (!isBoxInView(gt.gx - rad, gt.gx + rad, gt.gy - rad, gt.gy + rad)) { frameCulled++; return@forEach }
            Matrix.setIdentityM(modelMatrix, 0); Matrix.translateM(modelMatrix, 0, gt.gx, gt.gy, gt.gz); Matrix.scaleM(modelMatrix, 0, rad * 2f, rad * 2f, 1f); Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lightProgram, "uMVP"), 1, false, mvpMatrix, 0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lightProgram, "uModel"), 1, false, modelMatrix, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(lightProgram, "uColor"), l.r * l.lightIntensity, l.g * l.lightIntensity, l.b * l.lightIntensity, 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); frameDrawCalls++
        }
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun renderPostComposite() {
        GLES20.glDisable(GLES20.GL_BLEND); GLES20.glUseProgram(postProgram)
        val posLoc = GLES20.glGetAttribLocation(postProgram, "aPosition"); val uvLoc = GLES20.glGetAttribLocation(postProgram, "aTexCoord")
        fullScreenQuadBuffer.position(0); GLES20.glEnableVertexAttribArray(posLoc); GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, fullScreenQuadBuffer)
        fullScreenQuadBuffer.position(2); GLES20.glEnableVertexAttribArray(uvLoc); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, fullScreenQuadBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneColorTex); GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uSceneTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lightMapTex); GLES20.glUniform1i(GLES20.glGetUniformLocation(postProgram, "uLightTex"), 1)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(postProgram, "uTexelSize"), 1f / surfaceW, 1f / surfaceH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uBloom"), postBloom); GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uVignette"), postVignette)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uChromatic"), postChromatic); GLES20.glUniform1f(GLES20.glGetUniformLocation(postProgram, "uFade"), postFade)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); frameDrawCalls++
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glEnable(GLES20.GL_BLEND)
    }

    private fun drawDebugShape(posLoc: Int, uvLoc: Int, colLoc: Int, coords: FloatArray, mode: Int, z: Float, r: Float, g: Float, b: Float, a: Float) {
        val buf = physicsSystem.debugVertBuf; buf.clear()
        var i = 0; while (i < coords.size) { buf.put(coords[i]); buf.put(coords[i + 1]); buf.put(z); buf.put(0f); buf.put(0f); i += 2 }
        buf.position(0); GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, buf)
        buf.position(3); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, buf)
        GLES20.glUniform4f(colLoc, r, g, b, a); GLES20.glDrawArrays(mode, 0, coords.size / 2); frameDrawCalls++
    }

    private fun renderDebugOverlay(activeVp: FloatArray) {
        val prog = defaultLitProgram; GLES20.glUseProgram(prog); GLES20.glLineWidth(3f)
        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition"); val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord"); val colLoc = GLES20.glGetUniformLocation(prog, "uColor")
        GLES20.glEnableVertexAttribArray(posLoc); GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, activeVp, 0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, identityMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), 0); GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), 0f); GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTexId); GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)

        nodes.filter { it.visible && it.layer == "World" }.forEach { n ->
            val gt = getGlobalTransform(n)
            n.animator.activeFrame?.boxes?.forEach { box ->
                val wb = physicsSystem.getFrameBoxWorldBounds(n, box) { getGlobalTransform(it) }
                val rect = floatArrayOf(wb.cx - wb.hw, wb.cy - wb.hh, wb.cx + wb.hw, wb.cy - wb.hh, wb.cx + wb.hw, wb.cy + wb.hh, wb.cx - wb.hw, wb.cy + wb.hh)
                if (box.type == "hitbox") drawDebugShape(posLoc, uvLoc, colLoc, rect, GLES20.GL_LINE_LOOP, gt.gz + 9f, 1f, 0.15f, 0.15f, 0.95f)
                else drawDebugShape(posLoc, uvLoc, colLoc, rect, GLES20.GL_LINE_LOOP, gt.gz + 8f, 0.15f, 1f, 0.6f, 0.95f)
            }
            if (!n.collider && !n.isHitbox && !n.isHurtbox) return@forEach
            if (n.nodeType == "TileMap" && n.collider) {
                val ts = max(8f, n.tileSize)
                n.tiles.forEach { c -> val x0 = gt.gx + c.gx * ts; val y0 = gt.gy + c.gy * ts
                    if (isBoxInView(x0, x0 + ts, y0, y0 + ts)) drawDebugShape(posLoc, uvLoc, colLoc, floatArrayOf(x0, y0, x0 + ts, y0, x0 + ts, y0 + ts, x0, y0 + ts), GLES20.GL_LINE_LOOP, gt.gz + 6f, 0.2f, 0.95f, 0.45f, 0.5f)
                }; return@forEach
            }
            val cb = physicsSystem.getColliderBounds(n) { getGlobalTransform(it) }
            val x0 = cb.cx - cb.hw; val x1 = cb.cx + cb.hw; val y0 = cb.cy - cb.hh; val y1 = cb.cy + cb.hh
            if (!isBoxInView(x0, x1, y0, y1)) return@forEach
            if (n.collider) when (n.slopeType) {
                "left_up" -> drawDebugShape(posLoc, uvLoc, colLoc, floatArrayOf(x1, y0, x0, y1, x1, y1), GLES20.GL_LINE_LOOP, gt.gz + 6f, 0.75f, 1f, 0.15f, 0.95f)
                "right_up" -> drawDebugShape(posLoc, uvLoc, colLoc, floatArrayOf(x0, y0, x0, y1, x1, y1), GLES20.GL_LINE_LOOP, gt.gz + 6f, 0.75f, 1f, 0.15f, 0.95f)
                else -> drawDebugShape(posLoc, uvLoc, colLoc, floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), GLES20.GL_LINE_LOOP, gt.gz + 6f, if (n.isTrigger) 0.1f else 0.2f, if (n.isTrigger) 0.9f else 1f, if (n.isTrigger) 1f else 0.35f, 0.9f)
            }
            if (n.isHurtbox) drawDebugShape(posLoc, uvLoc, colLoc, floatArrayOf(x0 + 3f, y0 + 3f, x1 - 3f, y0 + 3f, x1 - 3f, y1 - 3f, x0 + 3f, y1 - 3f), GLES20.GL_LINE_LOOP, gt.gz + 7f, 0.3f, 0.55f, 1f, 0.95f)
            if (n.isHitbox) drawDebugShape(posLoc, uvLoc, colLoc, floatArrayOf(x0 - 2f, y0 - 2f, x1 + 2f, y0 - 2f, x1 + 2f, y1 + 2f, x0 - 2f, y1 + 2f), GLES20.GL_LINE_LOOP, gt.gz + 8f, 1f, 0.2f, 0.2f, 0.95f)
        }
        physicsSystem.debugRays.forEach { ray ->
            drawDebugShape(posLoc, uvLoc, colLoc, floatArrayOf(ray.x1, ray.y1, ray.x2, ray.y2), GLES20.GL_LINES, 10f, 1f, if (ray.hit) 0.25f else 0.9f, if (ray.hit) 0.85f else 0.2f, 0.95f)
        }
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun renderTouchZonesDebugOverlay(uiVp: FloatArray) {
        val zones = inputSystem.getTouchZonesSnapshot(); if (zones.isEmpty()) return
        val prog = defaultLitProgram; GLES20.glUseProgram(prog); GLES20.glLineWidth(3f)
        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition"); val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord"); val colLoc = GLES20.glGetUniformLocation(prog, "uColor")
        GLES20.glEnableVertexAttribArray(posLoc); GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, uiVp, 0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, identityMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), 0); GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), 0f); GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTexId); GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)
        zones.forEach { z -> val act = inputSystem.isActionPressed(z.action); val hw = z.w * 0.5f; val hh = z.h * 0.5f
            drawDebugShape(posLoc, uvLoc, colLoc, floatArrayOf(z.cx - hw, z.cy - hh, z.cx + hw, z.cy - hh, z.cx + hw, z.cy + hh, z.cx - hw, z.cy + hh), GLES20.GL_LINE_LOOP, 20f, if (act) 1f else 0.2f, if (act) 0.85f else 0.85f, if (act) 0.1f else 1f, if (act) 0.95f else 0.55f)
        }
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun buildTileMapVbo(n: GameNode) {
        if (n.tileVboId != 0) { GLES20.glDeleteBuffers(1, intArrayOf(n.tileVboId), 0); n.tileVboId = 0 }
        n.tileDirty = false; n.tileLookup.clear()
        if (n.tiles.isEmpty()) { n.tileVertexCount = 0; return }
        val ts = max(8f, n.tileSize); val hf = max(1, n.hFrames); val vf = max(1, n.vFrames); val uS = 1f / hf; val vS = 1f / vf
        val data = FloatArray(n.tiles.size * 30); var off = 0
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        n.tiles.forEach { c ->
            n.tileLookup[physicsSystem.packGridKey(c.gx, c.gy)] = c
            val x0 = c.gx * ts; val x1 = x0 + ts; val y0 = c.gy * ts; val y1 = y0 + ts
            minX = min(minX, x0); maxX = max(maxX, x1); minY = min(minY, y0); maxY = max(maxY, y1)
            val fi = c.frame.coerceIn(0, hf * vf - 1); val u0 = (fi % hf) * uS; val v0 = (fi / hf) * vS; val u1 = u0 + uS; val v1 = v0 + vS
            data[off++] = x0; data[off++] = y0; data[off++] = 0f; data[off++] = u0; data[off++] = v0
            data[off++] = x1; data[off++] = y0; data[off++] = 0f; data[off++] = u1; data[off++] = v0
            data[off++] = x0; data[off++] = y1; data[off++] = 0f; data[off++] = u0; data[off++] = v1
            data[off++] = x1; data[off++] = y0; data[off++] = 0f; data[off++] = u1; data[off++] = v0
            data[off++] = x1; data[off++] = y1; data[off++] = 0f; data[off++] = u1; data[off++] = v1
            data[off++] = x0; data[off++] = y1; data[off++] = 0f; data[off++] = u0; data[off++] = v1
        }
        n.tileMinX = minX; n.tileMaxX = maxX; n.tileMinY = minY; n.tileMaxY = maxY; n.tileVertexCount = n.tiles.size * 6
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
        val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); n.tileVboId = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, n.tileVboId); GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW); GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun renderTileMap(n: GameNode, activeVp: FloatArray, isWorld: Boolean, enableInlineLight: Boolean, lightCount: Int, lightPos: FloatArray, lightCol: FloatArray) {
        if (n.tiles.isEmpty()) return; if (n.tileDirty || n.tileVboId == 0) buildTileMapVbo(n); if (n.tileVboId == 0 || n.tileVertexCount == 0) return
        val gt = getGlobalTransform(n)
        if (isWorld && !isBoxInView(gt.gx + n.tileMinX, gt.gx + n.tileMaxX, gt.gy + n.tileMinY, gt.gy + n.tileMaxY)) { frameCulled++; return }
        val prog = getProgramForNode(n); GLES20.glUseProgram(prog); GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, n.tileVboId)
        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition"); val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc); GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(uvLoc); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, 12)
        Matrix.setIdentityM(modelMatrix, 0); Matrix.translateM(modelMatrix, 0, gt.gx, gt.gy, gt.gz)
        if (gt.gRot != 0f) Matrix.rotateM(modelMatrix, 0, gt.gRot, 0f, 0f, 1f)
        if (gt.gSx != 1f || gt.gSy != 1f) Matrix.scaleM(modelMatrix, 0, gt.gSx, gt.gSy, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, activeVp, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f); GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), n.r, n.g, n.b, n.a)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTime"), engineTime); GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), 0f)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), if (enableInlineLight) 1 else 0)
        if (enableInlineLight) {
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uAmbient"), ambientR, ambientG, ambientB); GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLightCount"), lightCount)
            if (lightCount > 0) { GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightPos"), lightCount, lightPos, 0); GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightColor"), lightCount, lightCol, 0) }
        }
        val texId = if (n.texture.isNotEmpty()) getImageTexture(n.texture) else whiteTexId
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId); GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n.tileVertexCount); frameDrawCalls++
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc); GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun spawnSingleParticle(n: GameNode, gx: Float, gy: Float) {
        val slot = n.particles.firstOrNull { !it.active } ?: n.particles.first()
        val angle = Math.random() * Math.PI * 2.0; val spd = n.particleSpeed * (0.35 + Math.random() * 0.85).toFloat()
        slot.active = true; slot.x = gx + ((Math.random() - 0.5) * n.width * 0.4).toFloat(); slot.y = gy + ((Math.random() - 0.5) * n.height * 0.4).toFloat()
        slot.vx = (cos(angle) * spd).toFloat(); slot.vy = (sin(angle) * spd).toFloat()
        slot.size = n.particleSize * (0.6 + Math.random() * 0.8).toFloat(); slot.maxLife = max(0.15f, n.particleLife * (0.7 + Math.random() * 0.6).toFloat())
        slot.life = slot.maxLife; slot.alpha = n.a
    }

    private fun updateParticleEmitter(n: GameNode, dt: Float) {
        val gt = getGlobalTransform(n)
        if (n.emitRate > 0f) { n.emitAccum += dt * n.emitRate; while (n.emitAccum >= 1f) { n.emitAccum -= 1f; spawnSingleParticle(n, gt.gx, gt.gy) } }
        n.particles.forEach { p -> if (!p.active) return@forEach; p.life -= dt; if (p.life <= 0f) { p.active = false; return@forEach }; p.vy += n.particleGravity * dt; p.x += p.vx * dt; p.y += p.vy * dt }
    }

    private fun updateTweens(dt: Float) {
        activeTweens.removeAll { t ->
            t.elapsed += dt; val rawP = (t.elapsed / max(0.01f, t.duration)).coerceIn(0f, 1f)
            val e = when (t.ease) { "easeOut" -> 1f - (1f - rawP).pow(2); "easeInOut" -> if (rawP < 0.5f) 2f * rawP * rawP else 1f - (-2f * rawP + 2f).pow(2) / 2f; else -> rawP }
            val v = t.startVal + (t.targetVal - t.startVal) * e
            when (t.prop) { "x" -> t.node.x = v; "y" -> t.node.y = v; "z" -> t.node.z = v; "scaleX" -> t.node.scaleX = v; "scaleY" -> t.node.scaleY = v; "rotation" -> t.node.rotation = v; "a" -> t.node.a = v.coerceIn(0f, 1f); "lightIntensity" -> t.node.lightIntensity = v }
            rawP >= 1f
        }
    }

    private fun readTweenProperty(n: GameNode, prop: String): Float = when (prop) {
        "x" -> n.x; "y" -> n.y; "z" -> n.z; "scaleX" -> n.scaleX; "scaleY" -> n.scaleY; "rotation" -> n.rotation; "a" -> n.a; "lightIntensity" -> n.lightIntensity; else -> 0f
    }

    private fun unprojectCornerZ0(ndcX: Float, ndcY: Float, outXY: FloatArray): Boolean {
        val nearW = FloatArray(4); val farW = FloatArray(4)
        Matrix.multiplyMV(nearW, 0, invVpMatrix, 0, floatArrayOf(ndcX, ndcY, -1f, 1f), 0); Matrix.multiplyMV(farW, 0, invVpMatrix, 0, floatArrayOf(ndcX, ndcY, 1f, 1f), 0)
        if (abs(nearW[3]) < 1e-5f || abs(farW[3]) < 1e-5f) return false
        for (i in 0..2) { nearW[i] /= nearW[3]; farW[i] /= farW[3] }
        val dz = farW[2] - nearW[2]; if (abs(dz) < 1e-5f) return false
        val t = -nearW[2] / dz; outXY[0] = nearW[0] + t * (farW[0] - nearW[0]); outXY[1] = nearW[1] + t * (farW[1] - nearW[1]); return true
    }

    private fun updateCameraMatrices(dt: Float) {
        camTarget?.let { target ->
            val gt = getGlobalTransform(target); val lookX = if (target.scaleX < 0f) -camLookAheadX else camLookAheadX
            val dx = (gt.gx + lookX) - camX; val dy = (gt.gy + camLookAheadY) - camY
            if (abs(dx) > camDeadzoneX) camX += (dx - sign(dx) * camDeadzoneX) * min(1f, camSmoothSpeed * dt)
            if (abs(dy) > camDeadzoneY) camY += (dy - sign(dy) * camDeadzoneY) * min(1f, camSmoothSpeed * dt)
        }
        if (camBoundsEnabled) { camX = camX.coerceIn(camMinX, max(camMinX, camMaxX)); camY = camY.coerceIn(camMinY, max(camMinY, camMaxY)) }
        var sx = 0f; var sy = 0f
        if (shakeTimer > 0f) { shakeTimer -= dt; sx = ((Math.random() - 0.5) * 2.0 * shakeIntensity).toFloat(); sy = ((Math.random() - 0.5) * 2.0 * shakeIntensity).toFloat() }
        val aspect = surfaceW.toFloat() / surfaceH.toFloat()
        if (camMode == "2.5D") {
            Matrix.perspectiveM(projMatrix, 0, camFov, aspect, 10f, 10000f)
            val rad = Math.toRadians(camPitch.toDouble())
            Matrix.setLookAtM(viewMatrix, 0, camX + sx, camY + sin(rad).toFloat() * camDistance + sy, cos(rad).toFloat() * camDistance, camX + sx, camY + sy, 0f, 0f, -1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0); Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)
            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; val pt = FloatArray(2); var valid = 0
            for ((cx, cy) in arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                if (unprojectCornerZ0(cx, cy, pt)) { minX = min(minX, pt[0]); maxX = max(maxX, pt[0]); minY = min(minY, pt[1]); maxY = max(maxY, pt[1]); valid++ }
            }
            if (valid == 4) { viewMinX = minX - 400f; viewMaxX = maxX + 400f; viewMinY = minY - 400f; viewMaxY = maxY + 400f }
            else { viewMinX = -999999f; viewMaxX = 999999f; viewMinY = -999999f; viewMaxY = 999999f }
        } else {
            val halfH = 960f; val halfW = halfH * aspect
            Matrix.orthoM(projMatrix, 0, -halfW, halfW, halfH, -halfH, -2000f, 2000f)
            Matrix.setLookAtM(viewMatrix, 0, camX + sx, camY + sy, 500f, camX + sx, camY + sy, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0); Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)
            viewMinX = camX + sx - halfW - 250f; viewMaxX = camX + sx + halfW + 250f; viewMinY = camY + sy - halfH - 250f; viewMaxY = camY + sy + halfH + 250f
        }
    }

    private fun updateWorldTouchFromScreen() {
        val pt = FloatArray(2)
        if (unprojectCornerZ0((touchScreenX / 1080f) * 2f - 1f, 1f - (touchScreenY / 1920f) * 2f, pt)) { touchWorldX = pt[0]; touchWorldY = pt[1] }
    }

    private fun renderNodeSingle(n: GameNode, activeVp: FloatArray, isWorld: Boolean, enableInlineLight: Boolean, lightCount: Int, lightPos: FloatArray, lightCol: FloatArray) {
        val gt = getGlobalTransform(n); val w = n.width * gt.gSx; val h = n.height * gt.gSy
        val af = n.animator.activeFrame; val renderX = gt.gx + (af?.offsetX ?: 0f) * gt.gSx; val renderY = gt.gy + (af?.offsetY ?: 0f) * abs(gt.gSy)
        if (isWorld) { val b = max(abs(w), abs(h)) * 0.75f + abs(gt.gz); if (!isBoxInView(renderX - b, renderX + b, renderY - b, renderY + b)) { frameCulled++; return } }
        Matrix.setIdentityM(modelMatrix, 0); Matrix.translateM(modelMatrix, 0, renderX, renderY, gt.gz)
        if (isWorld && camMode == "2.5D" && n.billboard) Matrix.rotateM(modelMatrix, 0, -90f + camPitch * 0.65f, 1f, 0f, 0f)
        else if (isWorld && n.tiltX != 0f) Matrix.rotateM(modelMatrix, 0, n.tiltX, 1f, 0f, 0f)
        if (gt.gRot != 0f) Matrix.rotateM(modelMatrix, 0, gt.gRot, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, w, h, 1f); Matrix.multiplyMM(mvpMatrix, 0, activeVp, 0, modelMatrix, 0)

        val prog = getProgramForNode(n); GLES20.glUseProgram(prog)
        val activeBuf = when (n.slopeType) { "left_up" -> slopeLeftUpBuffer; "right_up" -> slopeRightUpBuffer; else -> quadBuffer }
        val vertCount = if (n.slopeType == "left_up" || n.slopeType == "right_up") 3 else 4
        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition"); val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        activeBuf.position(0); GLES20.glEnableVertexAttribArray(posLoc); GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, activeBuf)
        activeBuf.position(3); GLES20.glEnableVertexAttribArray(uvLoc); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, activeBuf)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), n.r, n.g, n.b, n.a); GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTime"), engineTime)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFlash"), if (n.flashTimer > 0f) (n.flashTimer * 8f).coerceIn(0f, 1f) else 0f)

        val atlasData = if (n.atlas.isNotEmpty()) queryAtlas(n.atlas) else null
        val atlasFrame = atlasData?.frames?.get(n.atlasFrame)
        val texId = if (atlasData != null && atlasFrame != null && atlasFrame.w > 0f && atlasFrame.h > 0f) {
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), atlasFrame.x / atlasData.atlasW, atlasFrame.y / atlasData.atlasH, atlasFrame.w / atlasData.atlasW, atlasFrame.h / atlasData.atlasH)
            getImageTexture(atlasData.imageFile)
        } else {
            val hf = max(1, n.hFrames); val vf = max(1, n.vFrames); val fi = n.currentFrame.coerceIn(0, hf * vf - 1)
            val uS = 1f / hf; val vS = 1f / vf; val uO = (fi % hf) * uS; val vO = (fi / hf) * vS
            if (isWorld && camMode == "2.5D" && n.nodeType == "Label2D") GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 1f, vO, -1f, vS)
            else GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), uO, vO, uS, vS)
            when { n.nodeType == "Label2D" -> getTextTexture(n.text, n.fontSize); n.texture.isNotEmpty() -> getImageTexture(n.texture); else -> whiteTexId }
        }

        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uEnableLight"), if (enableInlineLight) 1 else 0)
        if (enableInlineLight) {
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uAmbient"), ambientR, ambientG, ambientB); GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLightCount"), lightCount)
            if (lightCount > 0) { GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightPos"), lightCount, lightPos, 0); GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uLightColor"), lightCount, lightCol, 0) }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId); GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)
        GLES20.glDrawArrays(if (vertCount == 3) GLES20.GL_TRIANGLES else GLES20.GL_TRIANGLE_STRIP, 0, vertCount); frameDrawCalls++
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun getProgramForNode(n: GameNode): Int {
        if (n.shader.isBlank()) return defaultLitProgram
        if (n.customProgram != 0) return n.customProgram
        val p = ZeroShaders.buildShaderProgram(ZeroShaders.VERTEX_SHADER, n.shader, onConsoleLog)
        n.customProgram = if (p != 0) p else defaultLitProgram; return n.customProgram
    }

    private fun updateSpriteAnimation(n: GameNode, dt: Float) {
        if (!n.animPlaying || n.animFps <= 0f) return; n.animTimer += dt; val dur = 1f / n.animFps
        while (n.animTimer >= dur) {
            n.animTimer -= dur; n.currentFrame++
            n.clips[n.currentClipName]?.events?.get(n.currentFrame)?.takeIf { it.isNotEmpty() }?.let { callFrameEvent(n, n.currentClipName, it) }
            if (n.currentFrame > n.animEnd) {
                if (n.animLoop) n.currentFrame = n.animStart
                else { n.currentFrame = n.animEnd; n.animPlaying = false; val fc = n.currentClipName; n.currentClipName = ""; if (fc.isNotEmpty()) callLifecycle(n, "onAnimFinished", fc) }
            }
        }
    }

    private fun initRhinoVM() {
        rhino = RhinoContext.enter().apply { optimizationLevel = -1 }; globalScope = rhino.initStandardObjects(); dataStore.clearRuntimeCaches(); atlasCache.clear()
        ScriptableObject.putProperty(globalScope, "require", jsFunc { args -> dataStore.requireScriptModule(rhino, globalScope, RhinoContext.toString(args.getOrNull(0) ?: "")) })
        val engineApi = rhino.newObject(globalScope)
        ScriptableObject.putProperty(engineApi, "log", jsFunc { args -> onConsoleLog("[LOG] ${args.joinToString(" ") { RhinoContext.toString(it) }}"); null })
        ScriptableObject.putProperty(engineApi, "unloadUnusedTextures", jsFunc { unloadUnusedTextures() })
        ScriptableObject.putProperty(engineApi, "clearModuleCache", jsFunc { dataStore.moduleCache.clear(); null })
        ScriptableObject.putProperty(engineApi, "loadAtlas", jsFunc { args ->
            val d = queryAtlas(RhinoContext.toString(args.getOrNull(0) ?: "")) ?: return@jsFunc null
            rhino.newObject(globalScope).apply {
                ScriptableObject.putProperty(this, "image", d.imageFile); ScriptableObject.putProperty(this, "width", d.atlasW.toDouble()); ScriptableObject.putProperty(this, "height", d.atlasH.toDouble())
                ScriptableObject.putProperty(this, "frames", rhino.newArray(globalScope, d.frames.keys.sorted().toTypedArray()))
            }
        })
        ScriptableObject.putProperty(engineApi, "setFlag", jsFunc { args -> val k = RhinoContext.toString(args.getOrNull(0) ?: ""); if (k.isNotEmpty()) { val v = if (args.size >= 2) args[1] else true; dataStore.worldFlags[k] = when (v) { is Boolean -> v; is Number -> v.toDouble(); null -> false; else -> RhinoContext.toString(v) } }; null })
        ScriptableObject.putProperty(engineApi, "getFlag", jsFunc { args -> dataStore.worldFlags[RhinoContext.toString(args.getOrNull(0) ?: "")] ?: if (args.size >= 2) args[1] else false })
        ScriptableObject.putProperty(engineApi, "hasFlag", jsFunc { args -> when (val v = dataStore.worldFlags[RhinoContext.toString(args.getOrNull(0) ?: "")]) { null -> false; is Boolean -> v; is Number -> v.toDouble() != 0.0; is String -> v.isNotEmpty(); else -> true } })
        ScriptableObject.putProperty(engineApi, "clearFlag", jsFunc { args -> dataStore.worldFlags.remove(RhinoContext.toString(args.getOrNull(0) ?: "")); null })
        ScriptableObject.putProperty(engineApi, "clearAllFlags", jsFunc { dataStore.worldFlags.clear(); null })
        ScriptableObject.putProperty(engineApi, "getAllFlags", jsFunc { rhino.newObject(globalScope).apply { dataStore.worldFlags.forEach { (k, v) -> ScriptableObject.putProperty(this, k, v) } } })
        ScriptableObject.putProperty(engineApi, "saveWorldFlags", jsFunc { args -> dataStore.saveWorldFlagsToDisk(RhinoContext.toString(args.getOrNull(0) ?: "world_flags")) })
        ScriptableObject.putProperty(engineApi, "loadWorldFlags", jsFunc { args -> dataStore.loadWorldFlagsFromDisk(RhinoContext.toString(args.getOrNull(0) ?: "world_flags")) })
        ScriptableObject.putProperty(engineApi, "loadTable", jsFunc { args -> dataStore.loadDataTable(rhino, globalScope, RhinoContext.toString(args.getOrNull(0) ?: "")) })
        ScriptableObject.putProperty(engineApi, "reloadTables", jsFunc { dataStore.dataTableCache.clear(); null })
        ScriptableObject.putProperty(engineApi, "setLanguage", jsFunc { args -> dataStore.setLanguage(RhinoContext.toString(args.getOrNull(0) ?: "zh")) })
        ScriptableObject.putProperty(engineApi, "getLanguage", jsFunc { dataStore.currentLang })
        ScriptableObject.putProperty(engineApi, "setI18nDict", jsFunc { args -> (args.getOrNull(1) as? Scriptable)?.let { dataStore.setI18nDict(RhinoContext.toString(args.getOrNull(0) ?: "zh"), it) }; null })
        ScriptableObject.putProperty(engineApi, "t", jsFunc { args -> dataStore.translateKey(RhinoContext.toString(args.getOrNull(0) ?: ""), if (args.size >= 2 && args[1] != null) RhinoContext.toString(args[1]) else "") })
        ScriptableObject.putProperty(engineApi, "setDebugDraw", jsFunc { args -> physicsSystem.debugDrawEnabled = (args.getOrNull(0) as? Boolean) ?: false; null })
        ScriptableObject.putProperty(engineApi, "isDebugDraw", jsFunc { physicsSystem.debugDrawEnabled })
        ScriptableObject.putProperty(engineApi, "setPostFX", jsFunc { args -> (args.getOrNull(0) as? Scriptable)?.let { cfg -> postFxEnabled = getJsBool(cfg, "enabled", postFxEnabled); postBloom = getJsFloat(cfg, "bloom", postBloom).coerceIn(0f, 3f); postVignette = getJsFloat(cfg, "vignette", postVignette).coerceIn(0f, 2f); postChromatic = getJsFloat(cfg, "chromatic", postChromatic).coerceIn(0f, 3f); postFade = getJsFloat(cfg, "fade", postFade).coerceIn(0f, 1f) }; null })
        ScriptableObject.putProperty(engineApi, "setFade", jsFunc { args -> val tg = ((args.getOrNull(0) as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f); val d = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.3f; pendingFadeScene = null; if (d <= 0.01f) { postFade = tg; fadeTarget = tg; fadeSpeed = 0f } else { fadeTarget = tg; fadeSpeed = abs(tg - postFade) / d }; null })
        ScriptableObject.putProperty(engineApi, "fadeToScene", jsFunc { args -> val s = RhinoContext.toString(args.getOrNull(0) ?: ""); val d = ((args.getOrNull(1) as? Number)?.toFloat() ?: 0.35f).coerceAtLeast(0.05f); if (s.isNotEmpty()) { preloadSceneAsync(s); pendingFadeScene = normalizeSceneName(s); fadeSceneDur = d; fadeTarget = 1f; fadeSpeed = 1f / d }; null })
        ScriptableObject.putProperty(engineApi, "preloadScene", jsFunc { args -> val s = RhinoContext.toString(args.getOrNull(0) ?: ""); if (s.isNotEmpty()) preloadSceneAsync(s); null })
        ScriptableObject.putProperty(engineApi, "loadSceneAdditive", jsFunc { args -> val s = RhinoContext.toString(args.getOrNull(0) ?: ""); if (s.isNotEmpty()) pendingAdditiveLoads.add(AdditiveLoadReq(normalizeSceneName(s), (args.getOrNull(1) as? Number)?.toFloat() ?: 0f, (args.getOrNull(2) as? Number)?.toFloat() ?: 0f)); null })
        ScriptableObject.putProperty(engineApi, "unloadScene", jsFunc { args -> val s = RhinoContext.toString(args.getOrNull(0) ?: ""); if (s.isNotEmpty()) pendingUnloadScenes.add(normalizeSceneName(s)); null })
        ScriptableObject.putProperty(engineApi, "clearSceneCache", jsFunc { preloadedSceneCache.clear(); null })
        ScriptableObject.putProperty(engineApi, "getStats", jsFunc {
            rhino.newObject(globalScope).apply {
                ScriptableObject.putProperty(this, "fps", smoothedFps.toDouble()); ScriptableObject.putProperty(this, "drawCalls", lastFrameDrawCalls); ScriptableObject.putProperty(this, "culled", lastFrameCulled)
                ScriptableObject.putProperty(this, "nodes", nodes.size); ScriptableObject.putProperty(this, "lights", lastFrameLights); ScriptableObject.putProperty(this, "spatialCells", physicsSystem.spatialCellsCount)
                ScriptableObject.putProperty(this, "modules", dataStore.moduleCache.size); ScriptableObject.putProperty(this, "atlases", atlasCache.size); ScriptableObject.putProperty(this, "flags", dataStore.worldFlags.size)
                ScriptableObject.putProperty(this, "tables", dataStore.dataTableCache.size); ScriptableObject.putProperty(this, "lang", dataStore.currentLang); ScriptableObject.putProperty(this, "fbo", postFxEnabled && fboReady)
                ScriptableObject.putProperty(this, "touches", inputSystem.getTouchCount()); ScriptableObject.putProperty(this, "textures", textureCache.size)
            }
        })
        ScriptableObject.putProperty(engineApi, "loadScene", jsFunc { args -> val s = RhinoContext.toString(args.getOrNull(0) ?: ""); if (s.isNotEmpty()) pendingSceneLoad = normalizeSceneName(s); null })
        ScriptableObject.putProperty(engineApi, "setGlobal", jsFunc { args -> val k = RhinoContext.toString(args.getOrNull(0) ?: ""); if (k.isNotEmpty()) globalDataStore[k] = args.getOrNull(1); null })
        ScriptableObject.putProperty(engineApi, "getGlobal", jsFunc { args -> globalDataStore[RhinoContext.toString(args.getOrNull(0) ?: "")] ?: args.getOrNull(1) })
        ScriptableObject.putProperty(engineApi, "saveDisk", jsFunc { args -> dataStore.saveDiskSlot(rhino, globalScope, RhinoContext.toString(args.getOrNull(0) ?: "slot_1"), args.getOrNull(1)) })
        ScriptableObject.putProperty(engineApi, "loadDisk", jsFunc { args -> dataStore.loadDiskSlot(rhino, globalScope, RhinoContext.toString(args.getOrNull(0) ?: "slot_1")) })
        ScriptableObject.putProperty(engineApi, "deleteDisk", jsFunc { args -> dataStore.deleteDiskSlot(RhinoContext.toString(args.getOrNull(0) ?: "slot_1")) })
        ScriptableObject.putProperty(engineApi, "raycast", jsFunc { args -> physicsSystem.raycast(rhino, globalScope, (args.getOrNull(0) as? Number)?.toFloat() ?: 0f, (args.getOrNull(1) as? Number)?.toFloat() ?: 0f, (args.getOrNull(2) as? Number)?.toFloat() ?: 0f, (args.getOrNull(3) as? Number)?.toFloat() ?: 0f, args.getOrNull(4)?.takeIf { it !== Scriptable.NOT_FOUND }?.toString() ?: "", (args.getOrNull(5) as? Number)?.toInt() ?: 0xFFFF) { getGlobalTransform(it) } })
        ScriptableObject.putProperty(engineApi, "hitstop", jsFunc { args -> hitstopTimer = max(hitstopTimer, ((args.getOrNull(0) as? Number)?.toFloat() ?: 0.05f).coerceIn(0f, 0.5f)); null })
        ScriptableObject.putProperty(engineApi, "setTimeScale", jsFunc { args -> timeScale = ((args.getOrNull(0) as? Number)?.toFloat() ?: 1f).coerceIn(0.05f, 4f); null })
        ScriptableObject.putProperty(engineApi, "setCameraMode", jsFunc { args -> camMode = RhinoContext.toString(args.getOrNull(0) ?: "2.5D"); null })
        ScriptableObject.putProperty(engineApi, "setCameraTilt", jsFunc { args -> camPitch = (args.getOrNull(0) as? Number)?.toFloat() ?: camPitch; camDistance = (args.getOrNull(1) as? Number)?.toFloat() ?: camDistance; camFov = (args.getOrNull(2) as? Number)?.toFloat() ?: camFov; null })
        ScriptableObject.putProperty(engineApi, "setCameraTarget", jsFunc { args -> val t = args.getOrNull(0) as? Scriptable; camTarget = (t as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === t }; null })
        ScriptableObject.putProperty(engineApi, "setCameraDeadzone", jsFunc { args -> camDeadzoneX = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f; camDeadzoneY = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f; null })
        ScriptableObject.putProperty(engineApi, "setCameraLookAhead", jsFunc { args -> camLookAheadX = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f; camLookAheadY = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f; null })
        ScriptableObject.putProperty(engineApi, "setCameraBounds", jsFunc { args -> camBoundsEnabled = true; camMinX = (args.getOrNull(0) as? Number)?.toFloat() ?: -99999f; camMinY = (args.getOrNull(1) as? Number)?.toFloat() ?: -99999f; camMaxX = (args.getOrNull(2) as? Number)?.toFloat() ?: 99999f; camMaxY = (args.getOrNull(3) as? Number)?.toFloat() ?: 99999f; null })
        ScriptableObject.putProperty(engineApi, "clearCameraBounds", jsFunc { camBoundsEnabled = false; null })
        ScriptableObject.putProperty(engineApi, "getCamera", jsFunc { rhino.newObject(globalScope).apply { ScriptableObject.putProperty(this, "x", camX.toDouble()); ScriptableObject.putProperty(this, "y", camY.toDouble()); ScriptableObject.putProperty(this, "pitch", camPitch.toDouble()); ScriptableObject.putProperty(this, "mode", camMode) } })
        ScriptableObject.putProperty(engineApi, "setAmbientLight", jsFunc { args -> ambientR = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.52f; ambientG = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.56f; ambientB = (args.getOrNull(2) as? Number)?.toFloat() ?: 0.68f; null })
        ScriptableObject.putProperty(engineApi, "shake", jsFunc { args -> shakeIntensity = (args.getOrNull(0) as? Number)?.toFloat() ?: 12f; shakeTimer = (args.getOrNull(1) as? Number)?.toFloat() ?: 0.15f; null })
        ScriptableObject.putProperty(engineApi, "tween", jsFunc { args -> val o = args.getOrNull(0) as? Scriptable ?: return@jsFunc null; val p = RhinoContext.toString(args.getOrNull(1) ?: ""); val tv = (args.getOrNull(2) as? Number)?.toFloat() ?: return@jsFunc null; val n = (o as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === o } ?: return@jsFunc null; activeTweens.removeAll { it.node === n && it.prop == p }; activeTweens.add(TweenTask(n, p, readTweenProperty(n, p), tv, (args.getOrNull(3) as? Number)?.toFloat() ?: 0.25f, RhinoContext.toString(args.getOrNull(4) ?: "easeOut"))); null })
        ScriptableObject.putProperty(engineApi, "playTone", jsFunc { args -> audioSystem.playToneAsync((args.getOrNull(0) as? Number)?.toInt() ?: 440, (args.getOrNull(1) as? Number)?.toInt() ?: 80); null })
        ScriptableObject.putProperty(engineApi, "playBGM", jsFunc { args -> val f = RhinoContext.toString(args.getOrNull(0) ?: ""); if (f.isNotEmpty()) audioSystem.playBGM(f, (args.getOrNull(1) as? Boolean) ?: true, (args.getOrNull(2) as? Number)?.toFloat() ?: 0.8f); null })
        ScriptableObject.putProperty(engineApi, "crossfadeBGM", jsFunc { args -> val f = RhinoContext.toString(args.getOrNull(0) ?: ""); if (f.isNotEmpty()) audioSystem.crossfadeBGM(f, (args.getOrNull(1) as? Number)?.toFloat() ?: 1.2f, (args.getOrNull(2) as? Boolean) ?: true, (args.getOrNull(3) as? Number)?.toFloat() ?: 0.8f); null })
        ScriptableObject.putProperty(engineApi, "stopBGM", jsFunc { args -> audioSystem.stopBGM((args.getOrNull(0) as? Number)?.toFloat() ?: 0f); null })
        ScriptableObject.putProperty(engineApi, "playSFX", jsFunc { args -> val f = RhinoContext.toString(args.getOrNull(0) ?: ""); val pMin = (args.getOrNull(2) as? Number)?.toFloat() ?: 1.0f; if (f.isNotEmpty()) audioSystem.playSFX(f, (args.getOrNull(1) as? Number)?.toFloat() ?: 1.0f, pMin, (args.getOrNull(3) as? Number)?.toFloat() ?: pMin); null })
        ScriptableObject.putProperty(engineApi, "setVolume", jsFunc { args -> audioSystem.setVolume(RhinoContext.toString(args.getOrNull(0) ?: "master"), (args.getOrNull(1) as? Number)?.toFloat() ?: 1.0f); null })
        ScriptableObject.putProperty(engineApi, "getVolume", jsFunc { args -> audioSystem.getVolume(RhinoContext.toString(args.getOrNull(0) ?: "master")).toDouble() })
        ScriptableObject.putProperty(engineApi, "moveAndSlide", jsFunc { args -> val o = args.getOrNull(0) as? Scriptable ?: return@jsFunc null; val n = (o as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === o } ?: return@jsFunc null; physicsSystem.moveAndSlide(n, (args.getOrNull(1) as? Number)?.toFloat() ?: 0.016f, pendingRemove, { getGlobalTransform(it) }) { nd, fn, ag -> callLifecycle(nd, fn, ag) }; null })
        ScriptableObject.putProperty(engineApi, "distance", jsFunc { args -> val a = (args.getOrNull(0) as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === (args.getOrNull(0) as? Scriptable) }; val b = (args.getOrNull(1) as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === (args.getOrNull(1) as? Scriptable) }; if (a != null && b != null) { val ga = getGlobalTransform(a); val gb = getGlobalTransform(b); hypot(ga.gx - gb.gx, ga.gy - gb.gy).toDouble() } else 999999.0 })
        ScriptableObject.putProperty(engineApi, "angleTo", jsFunc { args -> val a = (args.getOrNull(0) as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === (args.getOrNull(0) as? Scriptable) }; val b = (args.getOrNull(1) as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === (args.getOrNull(1) as? Scriptable) }; if (a != null && b != null) { val ga = getGlobalTransform(a); val gb = getGlobalTransform(b); Math.toDegrees(atan2((gb.gy - ga.gy).toDouble(), (gb.gx - ga.gx).toDouble())) } else 0.0 })
        ScriptableObject.putProperty(engineApi, "spawn", jsFunc { args ->
            val cfg = args.getOrNull(0) as? Scriptable ?: return@jsFunc null; val rawLayer = getJsStr(cfg, "layer", "World")
            val sp = GameNode(name = getJsStr(cfg, "name", "Spawn_${System.nanoTime() % 10000}"), nodeType = getJsStr(cfg, "nodeType", "Sprite2D"), layer = if (rawLayer.startsWith("UI", ignoreCase = true)) "UI" else "World", parent = RhinoContext.toString(args.getOrNull(1) ?: ""), tag = getJsStr(cfg, "tag", "default"), persistent = getJsBool(cfg, "persistent", false), x = getJsFloat(cfg, "x", 0f), y = getJsFloat(cfg, "y", 0f), z = getJsFloat(cfg, "z", 0f), width = getJsFloat(cfg, "width", 64f), height = getJsFloat(cfg, "height", 64f), tiltX = getJsFloat(cfg, "tiltX", 0f), billboard = getJsBool(cfg, "billboard", false), zIndex = getJsInt(cfg, "zIndex", 0), texture = getJsStr(cfg, "texture", ""), atlas = getJsStr(cfg, "atlas", ""), atlasFrame = getJsStr(cfg, "atlasFrame", ""), text = getJsStr(cfg, "text", ""), fontSize = getJsFloat(cfg, "fontSize", 44f), r = getJsFloat(cfg, "r", 1f), g = getJsFloat(cfg, "g", 1f), b = getJsFloat(cfg, "b", 1f), a = getJsFloat(cfg, "a", 1f), sliceLeft = getJsFloat(cfg, "sliceLeft", 0f), sliceRight = getJsFloat(cfg, "sliceRight", 0f), sliceTop = getJsFloat(cfg, "sliceTop", 0f), sliceBottom = getJsFloat(cfg, "sliceBottom", 0f), clipChildren = getJsBool(cfg, "clipChildren", false), clipX = getJsFloat(cfg, "clipX", 0f), clipY = getJsFloat(cfg, "clipY", 0f), clipW = getJsFloat(cfg, "clipW", 0f), clipH = getJsFloat(cfg, "clipH", 0f), collider = getJsBool(cfg, "collider", false), isTrigger = getJsBool(cfg, "isTrigger", false), oneWay = getJsBool(cfg, "oneWay", false), slopeType = getJsStr(cfg, "slopeType", "none"), colOffsetX = getJsFloat(cfg, "colOffsetX", 0f), colOffsetY = getJsFloat(cfg, "colOffsetY", 0f), colWidth = getJsFloat(cfg, "colWidth", 0f), colHeight = getJsFloat(cfg, "colHeight", 0f), colLayer = getJsInt(cfg, "colLayer", 1), colMask = getJsInt(cfg, "colMask", 0xFFFF), bodyMode = getJsStr(cfg, "bodyMode", "kinematic"), isHitbox = getJsBool(cfg, "isHitbox", false), isHurtbox = getJsBool(cfg, "isHurtbox", false), damage = getJsFloat(cfg, "damage", 10f), shadowCaster = getJsBool(cfg, "shadowCaster", false), lightRadius = getJsFloat(cfg, "lightRadius", 400f), lightIntensity = getJsFloat(cfg, "lightIntensity", 1.5f), script = dataStore.resolveNodeScriptText(getJsStr(cfg, "script", ""), getJsStr(cfg, "scriptFile", "")))
            pendingAdd.add(sp); bindNodeScript(sp); sp.jsObj
        })
        ScriptableObject.putProperty(engineApi, "instantiate", jsFunc { args -> val txt = readSceneText(RhinoContext.toString(args.getOrNull(0) ?: "")) ?: return@jsFunc null; val loaded = mutableListOf<GameNode>(); parseSceneJson(txt, loaded, normalizeSceneName(RhinoContext.toString(args.getOrNull(0) ?: "")), false); val sx = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f; val sy = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f; val sz = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f; loaded.forEach { if (it.parent.isEmpty()) { it.x += sx; it.y += sy; it.z += sz }; pendingAdd.add(it); bindNodeScript(it) }; loaded.firstOrNull()?.jsObj })
        ScriptableObject.putProperty(engineApi, "destroy", jsFunc { args -> val t = args.getOrNull(0) as? Scriptable; ((t as? GameNodeJsProxy)?.n ?: nodes.find { it.jsObj === t })?.let { pendingRemove.add(it) }; null })
        ScriptableObject.putProperty(engineApi, "findNode", jsFunc { args -> nodes.find { it.name == RhinoContext.toString(args.getOrNull(0) ?: "") }?.jsObj })
        ScriptableObject.putProperty(engineApi, "findNodesByTag", jsFunc { args -> val tg = RhinoContext.toString(args.getOrNull(0) ?: ""); rhino.newArray(globalScope, nodes.filter { it.tag == tg && !pendingRemove.contains(it) }.mapNotNull { it.jsObj }.toTypedArray()) })
        ScriptableObject.putProperty(engineApi, "on", jsFunc { args -> val ev = RhinoContext.toString(args.getOrNull(0) ?: ""); val fn = args.getOrNull(1) as? Function; if (ev.isNotEmpty() && fn != null) eventListeners.getOrPut(ev) { mutableListOf() }.add(fn); null })
        ScriptableObject.putProperty(engineApi, "emit", jsFunc { args -> eventListeners[RhinoContext.toString(args.getOrNull(0) ?: "")]?.toList()?.forEach { try { it.call(rhino, globalScope, globalScope, arrayOf(args.getOrNull(1))) } catch (_: Exception) {} }; null })
        ScriptableObject.putProperty(globalScope, "Engine", engineApi)

        val inputApi = object : ScriptableObject() {
            override fun getClassName() = "Input"
            override fun get(name: String?, start: Scriptable?): Any? = when (name) { "isTouching" -> isTouching || inputSystem.getTouchCount() > 0; "touchCount" -> inputSystem.getTouchCount(); "screenX", "touchX" -> touchScreenX.toDouble(); "screenY", "touchY" -> touchScreenY.toDouble(); "worldX" -> touchWorldX.toDouble(); "worldY" -> touchWorldY.toDouble(); else -> super.get(name, start) }
        }
        ScriptableObject.putProperty(inputApi, "isActionPressed", jsFunc { args -> inputSystem.isActionPressed(RhinoContext.toString(args.getOrNull(0) ?: "")) })
        ScriptableObject.putProperty(inputApi, "isActionJustPressed", jsFunc { args -> inputSystem.isActionJustPressed(RhinoContext.toString(args.getOrNull(0) ?: "")) })
        ScriptableObject.putProperty(inputApi, "isActionJustReleased", jsFunc { args -> inputSystem.isActionJustReleased(RhinoContext.toString(args.getOrNull(0) ?: "")) })
        ScriptableObject.putProperty(inputApi, "getAxis", jsFunc { args -> inputSystem.getAxis(RhinoContext.toString(args.getOrNull(0) ?: "move_left"), RhinoContext.toString(args.getOrNull(1) ?: "move_right")).toDouble() })
        ScriptableObject.putProperty(inputApi, "bindAction", jsFunc { args -> val act = RhinoContext.toString(args.getOrNull(0) ?: ""); val rk = args.getOrNull(1); val list = mutableListOf<String>(); if (rk is Scriptable) { val len = (ScriptableObject.getProperty(rk, "length") as? Number)?.toInt() ?: 0; for (i in 0 until len) ScriptableObject.getProperty(rk, i)?.takeIf { it !== Scriptable.NOT_FOUND }?.let { list.add(RhinoContext.toString(it)) } } else if (rk != null) list.add(RhinoContext.toString(rk)); if (act.isNotEmpty() && list.isNotEmpty()) inputSystem.bindAction(act, list); null })
        ScriptableObject.putProperty(inputApi, "setVirtualAction", jsFunc { args -> val act = RhinoContext.toString(args.getOrNull(0) ?: ""); if (act.isNotEmpty()) inputSystem.setVirtualAction(act, (args.getOrNull(1) as? Boolean) ?: false); null })
        ScriptableObject.putProperty(inputApi, "bindTouchZone", jsFunc { args -> val act = RhinoContext.toString(args.getOrNull(0) ?: ""); if (act.isNotEmpty()) inputSystem.bindTouchZone(act, (args.getOrNull(1) as? Number)?.toFloat() ?: 0f, (args.getOrNull(2) as? Number)?.toFloat() ?: 0f, (args.getOrNull(3) as? Number)?.toFloat() ?: 160f, (args.getOrNull(4) as? Number)?.toFloat() ?: 160f); null })
        ScriptableObject.putProperty(inputApi, "clearTouchZones", jsFunc { inputSystem.clearTouchZones(); null })
        ScriptableObject.putProperty(globalScope, "Input", inputApi)
    }

    private fun bindNodeScript(n: GameNode) {
        val nodeScope = rhino.newObject(globalScope).apply { prototype = globalScope }
        val selfObj = GameNodeJsProxy(n).apply { parentScope = nodeScope; prototype = nodeScope }
        n.jsObj = selfObj

        ScriptableObject.putProperty(selfObj, "setAnimFloat", jsFunc { args -> n.animator.fsm.setFloat(RhinoContext.toString(args.getOrNull(0) ?: ""), (args.getOrNull(1) as? Number)?.toFloat() ?: 0f); null })
        ScriptableObject.putProperty(selfObj, "setAnimBool", jsFunc { args -> n.animator.fsm.setBool(RhinoContext.toString(args.getOrNull(0) ?: ""), (args.getOrNull(1) as? Boolean) ?: false); null })
        ScriptableObject.putProperty(selfObj, "triggerAnim", jsFunc { args -> n.animator.fsm.setTrigger(RhinoContext.toString(args.getOrNull(0) ?: "")); null })
        ScriptableObject.putProperty(selfObj, "setAnimSpeed", jsFunc { args -> n.animator.speedScale = ((args.getOrNull(0) as? Number)?.toFloat() ?: 1f).coerceIn(0.05f, 8f); null })
        ScriptableObject.putProperty(selfObj, "getAnimState", jsFunc { n.animator.fsm.currentState.ifEmpty { n.currentClipName } })
        ScriptableObject.putProperty(selfObj, "isAnimCancelable", jsFunc { n.animator.activeFrame?.cancelable == true })
        ScriptableObject.putProperty(selfObj, "addAnimTransition", jsFunc { args -> val to = RhinoContext.toString(args.getOrNull(1) ?: ""); val param = RhinoContext.toString(args.getOrNull(2) ?: ""); if (to.isNotEmpty()) n.animator.fsm.addTransition(StateTransition(RhinoContext.toString(args.getOrNull(0) ?: "*"), to, (args.getOrNull(5) as? Number)?.toInt() ?: 0, (args.getOrNull(6) as? Boolean) ?: false, (args.getOrNull(7) as? Boolean) ?: false, if (param.isNotEmpty()) listOf(StateCondition(param, RhinoContext.toString(args.getOrNull(3) ?: "trigger"), args.getOrNull(4))) else emptyList())); null })
        ScriptableObject.putProperty(selfObj, "registerProClip", jsFunc { args -> val raw = args.getOrNull(0) ?: return@jsFunc false; val s = if (raw is String) raw else RhinoContext.toString(NativeJSON.stringify(rhino, globalScope, raw, null, null)); try { ZeroAnimator.parseInto(n.animator, JSONObject().put("proClips", JSONArray().put(JSONObject(s)))); true } catch (e: Exception) { onConsoleLog("[ANIM 错误 @ ${n.name}] ${e.message}"); false } })
        ScriptableObject.putProperty(selfObj, "setAtlas", jsFunc { args -> n.atlas = RhinoContext.toString(args.getOrNull(0) ?: n.atlas); n.atlasFrame = RhinoContext.toString(args.getOrNull(1) ?: ""); queryAtlas(n.atlas); null })
        ScriptableObject.putProperty(selfObj, "setNineSlice", jsFunc { args -> n.sliceLeft = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f; n.sliceRight = (args.getOrNull(1) as? Number)?.toFloat() ?: n.sliceLeft; n.sliceTop = (args.getOrNull(2) as? Number)?.toFloat() ?: n.sliceLeft; n.sliceBottom = (args.getOrNull(3) as? Number)?.toFloat() ?: n.sliceTop; null })
        ScriptableObject.putProperty(selfObj, "setClipRect", jsFunc { args -> n.clipX = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f; n.clipY = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f; n.clipW = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f; n.clipH = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f; null })
        ScriptableObject.putProperty(selfObj, "jump", jsFunc { args -> val jf = (args.getOrNull(0) as? Number)?.toFloat() ?: 920f; if (n.isGrounded || n.coyoteTimer > 0f) { n.vy = -abs(jf); n.isGrounded = false; n.coyoteTimer = 0f; n.jumpCount = 1; true } else if (n.jumpCount < n.maxJumps) { n.vy = -abs(jf) * 0.9f; n.jumpCount++; true } else false })
        ScriptableObject.putProperty(selfObj, "dropThrough", jsFunc { args -> n.dropThroughTimer = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.22f; n.isGrounded = false; null })
        ScriptableObject.putProperty(selfObj, "bounce", jsFunc { args -> (args.getOrNull(0) as? Number)?.toFloat()?.let { n.vx = it }; n.vy = (args.getOrNull(1) as? Number)?.toFloat() ?: -760f; n.isGrounded = false; n.jumpCount = 1; null })
        ScriptableObject.putProperty(selfObj, "play", jsFunc { args -> val cn = RhinoContext.toString(args.getOrNull(0) ?: ""); val fr = (args.getOrNull(1) as? Boolean) ?: false; if (n.animator.play(cn, fr)) { n.currentClipName = cn; return@jsFunc null }; n.clips[cn]?.let { c -> if (!fr && n.animPlaying && n.currentClipName == cn) return@jsFunc null; n.currentClipName = cn; n.animStart = c.start; n.animEnd = c.end; n.animFps = c.fps; n.animLoop = c.loop; n.currentFrame = c.start; n.animTimer = 0f; n.animPlaying = true } ?: onConsoleLog("[ANIM 警告 @ ${n.name}] 未找到: $cn"); null })
        ScriptableObject.putProperty(selfObj, "playAnim", jsFunc { args -> n.animator.stop(); n.currentClipName = ""; n.animStart = (args.getOrNull(0) as? Number)?.toInt() ?: 0; n.animEnd = (args.getOrNull(1) as? Number)?.toInt() ?: 0; n.animFps = (args.getOrNull(2) as? Number)?.toFloat() ?: 12f; n.animLoop = (args.getOrNull(3) as? Boolean) ?: true; n.currentFrame = n.animStart; n.animTimer = 0f; n.animPlaying = true; null })
        ScriptableObject.putProperty(selfObj, "stopAnim", jsFunc { n.animator.stop(); n.animPlaying = false; n.currentClipName = ""; null })
        ScriptableObject.putProperty(selfObj, "flash", jsFunc { args -> n.flashTimer = (args.getOrNull(0) as? Number)?.toFloat() ?: 0.12f; null })
        ScriptableObject.putProperty(selfObj, "emitBurst", jsFunc { args -> val cnt = ((args.getOrNull(0) as? Number)?.toInt() ?: 24).coerceIn(1, 120); val gt = getGlobalTransform(n); repeat(cnt) { spawnSingleParticle(n, gt.gx, gt.gy) }; null })
        ScriptableObject.putProperty(nodeScope, "self", selfObj)
        if (n.script.isNotBlank()) try { rhino.evaluateString(nodeScope, n.script, n.name, 1, null) } catch (e: Exception) { onConsoleLog("[JS 编译错误 @ ${n.name}] ${e.message}") }
    }

    private fun callLifecycle(n: GameNode, fnName: String, arg: Any? = null) {
        val sc = n.jsObj?.prototype ?: return
        (ScriptableObject.getProperty(sc, fnName) as? Function)?.let { try { it.call(rhino, sc, n.jsObj, if (arg != null) arrayOf(arg) else emptyArray()) } catch (e: Exception) { onConsoleLog("[JS 错误 @ ${n.name}.$fnName] ${e.message}") } }
    }

    private fun getGlobalTransform(n: GameNode, visited: MutableSet<String> = mutableSetOf()): GlobalTransform {
        val px = if (n.layer == "World" && n.parallaxX != 1f) (camX - 540f) * (1f - n.parallaxX) else 0f
        val py = if (n.layer == "World" && n.parallaxY != 1f) (camY - 960f) * (1f - n.parallaxY) else 0f
        if (n.parent.isEmpty() || visited.contains(n.name)) return GlobalTransform(n.x + px, n.y + py, n.z, n.rotation, n.scaleX, n.scaleY)
        visited.add(n.name); val p = nodes.find { it.name == n.parent } ?: return GlobalTransform(n.x + px, n.y + py, n.z, n.rotation, n.scaleX, n.scaleY)
        val pt = getGlobalTransform(p, visited); val rad = Math.toRadians(pt.gRot.toDouble()); val sx = n.x * pt.gSx; val sy = n.y * pt.gSy
        return GlobalTransform(pt.gx + (sx * cos(rad) - sy * sin(rad)).toFloat(), pt.gy + (sx * sin(rad) + sy * cos(rad)).toFloat(), pt.gz + n.z, pt.gRot + n.rotation, pt.gSx * n.scaleX, pt.gSy * n.scaleY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        physicsSystem.onMultiTouch(inputSystem, event, width, height); val idx = event.actionIndex; val sx = (event.getX(idx) / max(1, width)) * 1080f; val sy = (event.getY(idx) / max(1, height)) * 1920f
        touchScreenX = sx; touchScreenY = sy
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { isTouching = true; synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("down", sx, sy)) } }
            MotionEvent.ACTION_MOVE -> synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("move", sx, sy)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> { if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) isTouching = false; synchronized(pendingTouchEvents) { pendingTouchEvents.add(Triple("up", sx, sy)) } }
        }; return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = if (inputSystem.onKeyEvent(keyCode, true)) true else super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = if (inputSystem.onKeyEvent(keyCode, false)) true else super.onKeyUp(keyCode, event)
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean = if (event != null && inputSystem.onGamepadMotion(event)) true else super.onGenericMotionEvent(event)

    private fun flushTouchEvents() {
        val list = synchronized(pendingTouchEvents) { val c = pendingTouchEvents.toList(); pendingTouchEvents.clear(); c }
        list.forEach { (tp, sx, sy) -> val ev = rhino.newObject(globalScope).apply { ScriptableObject.putProperty(this, "type", tp); ScriptableObject.putProperty(this, "screenX", sx.toDouble()); ScriptableObject.putProperty(this, "screenY", sy.toDouble()); ScriptableObject.putProperty(this, "worldX", touchWorldX.toDouble()); ScriptableObject.putProperty(this, "worldY", touchWorldY.toDouble()) }; nodes.toList().forEach { callLifecycle(it, "onInput", ev) } }
    }

    private fun parseSceneJson(raw: String, outList: MutableList<GameNode>, groupName: String = "main", applyCamera: Boolean = true) {
        try {
            val root = JSONObject(raw); if (applyCamera) root.optJSONObject("camera")?.let { camMode = it.optString("mode", "2.5D"); camPitch = it.optDouble("pitch", 48.0).toFloat(); camDistance = it.optDouble("distance", 1250.0).toFloat() }
            val arr = root.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val clipMap = mutableMapOf<String, AnimClip>()
                o.optJSONArray("clips")?.let { ca -> for (ci in 0 until ca.length()) { val co = ca.getJSONObject(ci); val cn = co.optString("name", ""); if (cn.isNotEmpty()) { val ev = mutableMapOf<Int, String>(); co.optJSONObject("events")?.let { eo -> val ks = eo.keys(); while (ks.hasNext()) { val k = ks.next(); k.toIntOrNull()?.let { ev[it] = eo.optString(k, "") } } }; clipMap[cn] = AnimClip(cn, co.optInt("start", 0), co.optInt("end", 0), co.optDouble("fps", 12.0).toFloat(), co.optBoolean("loop", true), ev) } } }
                val tileList = mutableListOf<TileCell>(); val tileMap = HashMap<Long, TileCell>()
                o.optJSONArray("tiles")?.let { ta -> for (ti in 0 until ta.length()) { val to = ta.getJSONObject(ti); val c = TileCell(to.optInt("gx", 0), to.optInt("gy", 0), to.optInt("frame", 0)); tileList.add(c); tileMap[physicsSystem.packGridKey(c.gx, c.gy)] = c } }
                val rawLayer = o.optString("layer", "World")
                val node = GameNode(name = o.optString("name", "Node_$i"), nodeType = o.optString("nodeType", "Sprite2D"), layer = if (rawLayer.startsWith("UI", ignoreCase = true)) "UI" else "World", parent = o.optString("parent", ""), tag = o.optString("tag", "default"), sceneGroup = groupName, persistent = o.optBoolean("persistent", false), x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(), z = o.optDouble("z", 0.0).toFloat(), width = o.optDouble("width", 120.0).toFloat(), height = o.optDouble("height", 120.0).toFloat(), rotation = o.optDouble("rotation", 0.0).toFloat(), tiltX = o.optDouble("tiltX", 0.0).toFloat(), billboard = o.optBoolean("billboard", false), scaleX = o.optDouble("scaleX", 1.0).toFloat(), scaleY = o.optDouble("scaleY", 1.0).toFloat(), zIndex = o.optInt("zIndex", 0), visible = o.optBoolean("visible", true), parallaxX = o.optDouble("parallaxX", 1.0).toFloat(), parallaxY = o.optDouble("parallaxY", 1.0).toFloat(), texture = o.optString("texture", ""), atlas = o.optString("atlas", ""), atlasFrame = o.optString("atlasFrame", ""), text = o.optString("text", ""), fontSize = o.optDouble("fontSize", 44.0).toFloat(), r = o.optDouble("r", 1.0).toFloat(), g = o.optDouble("g", 1.0).toFloat(), b = o.optDouble("b", 1.0).toFloat(), a = o.optDouble("a", 1.0).toFloat(), lightRadius = o.optDouble("lightRadius", 450.0).toFloat(), lightIntensity = o.optDouble("lightIntensity", 1.4).toFloat(), shadowCaster = o.optBoolean("shadowCaster", false), sliceLeft = o.optDouble("sliceLeft", 0.0).toFloat(), sliceRight = o.optDouble("sliceRight", 0.0).toFloat(), sliceTop = o.optDouble("sliceTop", 0.0).toFloat(), sliceBottom = o.optDouble("sliceBottom", 0.0).toFloat(), clipChildren = o.optBoolean("clipChildren", false), clipX = o.optDouble("clipX", 0.0).toFloat(), clipY = o.optDouble("clipY", 0.0).toFloat(), clipW = o.optDouble("clipW", 0.0).toFloat(), clipH = o.optDouble("clipH", 0.0).toFloat(), collider = o.optBoolean("collider", false), isTrigger = o.optBoolean("isTrigger", false), oneWay = o.optBoolean("oneWay", false), slopeType = o.optString("slopeType", "none"), colOffsetX = o.optDouble("colOffsetX", 0.0).toFloat(), colOffsetY = o.optDouble("colOffsetY", 0.0).toFloat(), colWidth = o.optDouble("colWidth", 0.0).toFloat(), colHeight = o.optDouble("colHeight", 0.0).toFloat(), colLayer = o.optInt("colLayer", 1), colMask = o.optInt("colMask", 0xFFFF), bodyMode = o.optString("bodyMode", "kinematic"), gravity = o.optDouble("gravity", 2200.0).toFloat(), fallMultiplier = o.optDouble("fallMultiplier", 1.55).toFloat(), maxFallSpeed = o.optDouble("maxFallSpeed", 1450.0).toFloat(), bounciness = o.optDouble("bounciness", 0.5).toFloat(), drag = o.optDouble("drag", 0.0).toFloat(), maxJumps = o.optInt("maxJumps", 2), isHitbox = o.optBoolean("isHitbox", false), isHurtbox = o.optBoolean("isHurtbox", false), damage = o.optDouble("damage", 10.0).toFloat(), hFrames = o.optInt("hFrames", 1), vFrames = o.optInt("vFrames", 1), currentFrame = o.optInt("currentFrame", 0), animFps = o.optDouble("animFps", 12.0).toFloat(), clips = clipMap, tileSize = o.optDouble("tileSize", 90.0).toFloat(), tiles = tileList, tileLookup = tileMap, emitRate = o.optDouble("emitRate", 24.0).toFloat(), particleLife = o.optDouble("particleLife", 1.1).toFloat(), particleSpeed = o.optDouble("particleSpeed", 160.0).toFloat(), particleSize = o.optDouble("particleSize", 16.0).toFloat(), particleGravity = o.optDouble("particleGravity", -60.0).toFloat(), shader = o.optString("shader", ""), script = dataStore.resolveNodeScriptText(o.optString("script", ""), o.optString("scriptFile", "")))
                ZeroAnimator.parseInto(node.animator, o)
                val dcName = o.optString("defaultClip", "")
                if (dcName.isNotEmpty()) {
                    if (node.animator.play(dcName, true)) node.currentClipName = dcName
                    else clipMap[dcName]?.let { dc -> node.currentClipName = dcName; node.animStart = dc.start; node.animEnd = dc.end; node.animFps = dc.fps; node.animLoop = dc.loop; node.currentFrame = dc.start; node.animPlaying = true }
                }
                outList.add(node)
            }
        } catch (e: Exception) { onConsoleLog("[场景解析异常] ${e.message}") }
    }

    private fun getImageTexture(fileName: String): Int {
        textureCache[fileName]?.let { return it }
        val file = File(File(projectDir, "textures"), fileName); if (!file.exists()) return whiteTexId
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return whiteTexId
        textureSizes[fileName] = bmp.width to bmp.height; val id = ZeroShaders.uploadBitmapToGL(bmp); bmp.recycle(); textureCache[fileName] = id; return id
    }

    private fun getTextTexture(text: String, size: Float): Int {
        val key = "$text|$size"; textTextureCache[key]?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size; isFakeBoldText = true }
        val bmp = android.graphics.Bitmap.createBitmap(max(64, (paint.measureText(text) + 24).toInt()), max(64, (size * 1.5f).toInt()), android.graphics.Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 12f, size, paint); val id = ZeroShaders.uploadBitmapToGL(bmp); bmp.recycle(); textTextureCache[key] = id; return id
    }

    private fun jsFunc(block: (Array<out Any?>) -> Any?): org.mozilla.javascript.BaseFunction = object : org.mozilla.javascript.BaseFunction() {
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
        private val BRIDGED_PROPS = setOf("name", "tag", "sceneGroup", "persistent", "x", "y", "z", "vx", "vy", "vz", "width", "height", "rotation", "tiltX", "scaleX", "scaleY", "parallaxX", "parallaxY", "atlas", "atlasFrame", "sliceLeft", "sliceRight", "sliceTop", "sliceBottom", "clipChildren", "clipX", "clipY", "clipW", "clipH", "slopeType", "colOffsetX", "colOffsetY", "colWidth", "colHeight", "colLayer", "colMask", "bodyMode", "gravity", "fallMultiplier", "maxJumps", "isGrounded", "isHitbox", "isHurtbox", "damage", "invulnTimer", "r", "g", "b", "a", "visible", "text", "currentClip", "lightRadius", "lightIntensity")
    }
}
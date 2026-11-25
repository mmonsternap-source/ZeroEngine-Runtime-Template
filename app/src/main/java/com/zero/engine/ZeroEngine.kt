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
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
        var normalMap: String = "", var normalStrength: Float = 1.0f, var specular: Float = 0.25f,
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
        val nodeId: Int = nextNodeId.incrementAndGet(), var customProgram: Int = 0
    )

    private val audioSystem = ZeroAudioSystem(projectDir, onConsoleLog)
    private val inputSystem = ZeroInputSystem()
    private val spriteBatch = ZeroSpriteBatch(1024)
    private val dataStore = ZeroDataStore(projectDir, onConsoleLog)
    private val physicsSystem = ZeroPhysicsSystem(onConsoleLog)
    private val lightingSystem = ZeroLightingSystem(projectDir, onConsoleLog)
    private val rigSystem = ZeroRigAndClothSystem(onConsoleLog)
    private val meshSystem = ZeroMeshSkinningSystem(onConsoleLog)
    private val giSystem = ZeroGlobalIlluminationSystem(onConsoleLog)

    private val atlasCache = ConcurrentHashMap<String, TextureAtlasData>()
    private val nodes = mutableListOf<GameNode>()
    private val nodeById = HashMap<Int, GameNode>(256)
    private val pendingAdd = mutableListOf<GameNode>()
    private val pendingRemove = mutableSetOf<GameNode>()
    private val activeTweens = mutableListOf<TweenTask>()
    private val textureCache = ConcurrentHashMap<String, Int>()
    private val textureSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

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

    @Volatile private var smoothedFps = 60f
    private var frameDrawCalls = 0
    @Volatile private var lastFrameDrawCalls = 0
    private var frameCulled = 0
    @Volatile private var lastFrameCulled = 0
    @Volatile private var lastFrameLights = 0
    @Volatile private var lastFrameNodes = 0
    @Volatile private var lastFrameTextures = 0

    private var hitstopTimer = 0f; private var timeScale = 1f
    private var ambientR = 0.52f; private var ambientG = 0.56f; private var ambientB = 0.68f

    @Volatile private var isTouching = false; @Volatile private var touchScreenX = 0f; @Volatile private var touchScreenY = 0f
    @Volatile private var touchWorldX = 0f; @Volatile private var touchWorldY = 0f
    private val pendingTouchEvents = mutableListOf<Triple<String, Float, Float>>()

    private val projMatrix = FloatArray(16); private val viewMatrix = FloatArray(16); private val vpMatrix = FloatArray(16); private val invVpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16); private val subModelMatrix = FloatArray(16); private val mvpMatrix = FloatArray(16); private val uiVpMatrix = FloatArray(16)

    private var defaultLitProgram = 0; private var whiteTexId = 0; private var engineTime = 0f; private var lastFrameNs = 0L; private var surfaceW = 1080; private var surfaceH = 1920

    // QuickJS 虚拟机与固定入口函数（零 JSObject 泄漏）
    private var qjs: QuickJSContext? = null
    private var fnBindNode: JSFunction? = null
    private var fnDestroyNode: JSFunction? = null
    private var fnCallHook: JSFunction? = null
    private var fnDispatchTouch: JSFunction? = null
    private var fnAdCallback: JSFunction? = null

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

    fun getPerfSummaryText(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        return String.format(
            Locale.US,
            "FPS: %.1f | DC: %d | 剔除: %d\n节点: %d | 光源: %d | 纹理: %d | 内存: %d/%dMB\nVM: QuickJS | GI: %s | 阴影: %s",
            smoothedFps, lastFrameDrawCalls, lastFrameCulled,
            lastFrameNodes, lastFrameLights, lastFrameTextures, usedMb, maxMb,
            if (giSystem.isReady) "ON" else "OFF",
            if (lightingSystem.sdfShadowEnabled) "ON" else "OFF"
        )
    }

    private fun normalizeSceneName(name: String): String = if (name.endsWith(".scene")) name else "$name.scene"
    private fun queryAtlas(name: String): TextureAtlasData? =
        ZeroShaders.loadAtlasData(projectDir, name, atlasCache, textureSizes, { getImageTexture(it) }, onConsoleLog)

    private fun destroyQuickJsVM() {
        try { fnBindNode?.release() } catch (_: Exception) {}
        try { fnDestroyNode?.release() } catch (_: Exception) {}
        try { fnCallHook?.release() } catch (_: Exception) {}
        try { fnDispatchTouch?.release() } catch (_: Exception) {}
        try { fnAdCallback?.release() } catch (_: Exception) {}
        fnBindNode = null; fnDestroyNode = null; fnCallHook = null; fnDispatchTouch = null; fnAdCallback = null
        try { qjs?.destroy() } catch (_: Exception) {}
        qjs = null
    }

    fun releaseResources() {
        try { sceneIoExecutor.shutdownNow() } catch (_: Exception) {}
        inputSystem.clearAllPointers()
        queueEvent {
            destroyQuickJsVM()
            releaseFbos(); releaseSceneGpuResources(includePersistent = true)
            lightingSystem.releaseGL(); rigSystem.releaseGL(); meshSystem.releaseGL(); giSystem.releaseGL()
            val texIds = (textureCache.values + textTextureCache.values).filter { it != 0 && it != whiteTexId }.toIntArray()
            if (texIds.isNotEmpty()) GLES20.glDeleteTextures(texIds.size, texIds, 0)
            textureCache.clear(); textureSizes.clear(); textTextureCache.clear()
        }
        audioSystem.release()
    }

    private fun unloadUnusedTextures(): Int {
        val active = HashSet<String>(); val activeNormals = HashSet<String>()
        for (n in (nodes + pendingAdd)) {
            if (pendingRemove.contains(n)) continue
            if (n.texture.isNotEmpty()) active.add(n.texture)
            if (n.normalMap.isNotEmpty()) activeNormals.add(n.normalMap)
            if (n.atlas.isNotEmpty()) queryAtlas(n.atlas)?.let { active.add(it.imageFile) }
        }
        lightingSystem.unloadUnusedNormals(activeNormals)
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
        listOf(sceneColorTex, lightMapTex).filter { it != 0 }.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
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
        lightingSystem.initGL()
        rigSystem.initGL()
        meshSystem.initGL()
        giSystem.initGL()
        ZeroAdBridge.init(context, onConsoleLog)
        initQuickJsVM(); dataStore.loadI18nFromDiskIfNeeded(dataStore.currentLang)
        loadSceneContent(sceneJson, groupName = "main", additive = false); lastFrameNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = max(1, width); surfaceH = max(1, height); GLES20.glViewport(0, 0, surfaceW, surfaceH)
        Matrix.orthoM(uiVpMatrix, 0, 0f, 1080f, 1920f, 0f, -100f, 100f)
        recreateFbos(surfaceW, surfaceH)
        giSystem.resize(surfaceW, surfaceH)
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
            nodes.filter { !it.persistent }.forEach {
                nodeById.remove(it.nodeId)
                try { fnDestroyNode?.call(it.nodeId) } catch (_: Exception) {}
            }
            val kept = nodes.filter { it.persistent }
            nodes.clear(); nodes.addAll(kept); pendingAdd.removeAll { !it.persistent }; pendingRemove.clear()
            activeTweens.removeAll { !it.node.persistent }; physicsSystem.clearRays(); rigSystem.clearAll(); meshSystem.clearAll()
            if (camTarget?.persistent != true) camTarget = null; camBoundsEnabled = false
        }
        val newNodes = mutableListOf<GameNode>()
        parseSceneJson(rawJson, newNodes, groupName, applyCamera = !additive)
        if (additive && (offsetX != 0f || offsetY != 0f)) {
            newNodes.forEach { if (it.parent.isEmpty() && it.layer == "World") { it.x += offsetX; it.y += offsetY } }
        }
        nodes.addAll(newNodes)
        newNodes.forEach { nodeById[it.nodeId] = it }
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
            added.forEach { nodeById[it.nodeId] = it }
            added.forEach { bindNodeScript(it); callLifecycle(it, "onReady") }
        }
        physicsSystem.rebuildSpatialIndex(nodes, pendingRemove) { getGlobalTransform(it) }

        nodes.toList().forEach { n ->
            if (!pendingRemove.contains(n)) {
                if (n.flashTimer > 0f) n.flashTimer = max(0f, n.flashTimer - dt)
                if (n.invulnTimer > 0f) n.invulnTimer = max(0f, n.invulnTimer - dt)
                if (n.dropThroughTimer > 0f) n.dropThroughTimer = max(0f, n.dropThroughTimer - dt)
                if (n.hitCooldownMap.isNotEmpty()) {
                    val it = n.hitCooldownMap.iterator()
                    while (it.hasNext()) { val e = it.next(); val left = e.value - dt; if (left <= 0f) it.remove() else e.setValue(left) }
                }
                if (n.animator.clips.isNotEmpty() || n.animator.currentClip != null) updateProAnimator(n, dt) else updateSpriteAnimation(n, dt)
                if (n.nodeType == "ParticleEmitter") updateParticleEmitter(n, dt)
                callLifecycle(n, "onProcess", dt.toDouble())
            }
        }
        rigSystem.update(dt, nodes) { getGlobalTransform(it) }
        meshSystem.update(dt, nodes) { getGlobalTransform(it) }

        physicsSystem.rebuildSpatialIndex(nodes, pendingRemove) { getGlobalTransform(it) }
        physicsSystem.processCombatOverlaps(
            nodes = nodes,
            pendingRemove = pendingRemove,
            getGlobalTransform = { getGlobalTransform(it) },
            onHit = { attacker, hurt -> callNodeEvent(attacker, "onHit", hurt) },
            onHurt = { hurt, attacker, dmg, kbX, kbY -> callHurtCallback(hurt, attacker, dmg, kbX, kbY) },
            addHitstop = { hs -> hitstopTimer = max(hitstopTimer, hs.coerceIn(0f, 0.35f)) }
        )
        if (pendingRemove.isNotEmpty()) {
            pendingRemove.forEach {
                releaseNodeGpuResources(it)
                meshSystem.removeMesh(it.name)
                nodeById.remove(it.nodeId)
                try { fnDestroyNode?.call(it.nodeId) } catch (_: Exception) {}
            }
            nodes.removeAll(pendingRemove); pendingRemove.clear()
        }
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

    private fun packLightsArrays(lights: List<GameNode>): Pair<FloatArray, FloatArray> {
        val pos = FloatArray(32); val col = FloatArray(32)
        lights.take(ZeroLightingSystem.MAX_LIGHTS).forEachIndexed { idx, l ->
            val gt = getGlobalTransform(l)
            pos[idx * 4] = gt.gx; pos[idx * 4 + 1] = gt.gy; pos[idx * 4 + 2] = gt.gz; pos[idx * 4 + 3] = l.lightRadius
            col[idx * 4] = l.r * l.lightIntensity; col[idx * 4 + 1] = l.g * l.lightIntensity; col[idx * 4 + 2] = l.b * l.lightIntensity; col[idx * 4 + 3] = 1f
        }
        return pos to col
    }

    private fun renderSceneFrame() {
        frameDrawCalls = 0; frameCulled = 0
        val allLights = nodes.filter { it.visible && it.nodeType == "PointLight" }; lastFrameLights = allLights.size
        val worldNodes = nodes.filter { it.visible && it.layer != "UI" && it.nodeType != "PointLight" }
            .sortedWith(compareBy({ it.zIndex }, { it.nodeType != "TileMap" }, { it.tiltX >= -45f }, { getGlobalTransform(it).gy }))
        val particleNodes = worldNodes.filter { it.nodeType == "ParticleEmitter" }
        val nonSkinnedWorld = worldNodes.filter { it.nodeType != "ParticleEmitter" && !meshSystem.hasMesh(it.name) }
        val normalNodes = nonSkinnedWorld.filter { it.nodeType != "TileMap" && it.normalMap.isNotEmpty() }
        val solidWorldNodes = nonSkinnedWorld.filter { it.nodeType == "TileMap" || it.normalMap.isEmpty() }
        val emptyArr = FloatArray(4)

        lightingSystem.collectOccluders(nodes, { x0, x1, y0, y1 -> isBoxInView(x0, x1, y0, y1) }, { getGlobalTransform(it) })
        val (lightPosArr, lightColArr) = packLightsArrays(allLights)
        val clampedLightCount = min(allLights.size, ZeroLightingSystem.MAX_LIGHTS)

        if (postFxEnabled && fboReady) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo); GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, 0, null, null) { frameDrawCalls++ }
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

            giSystem.renderAndComposite(sceneColorTex, lightMapTex, surfaceW, surfaceH, 1f - postFade, engineTime) { frameDrawCalls++ }

            if (normalNodes.isNotEmpty() && postFade < 0.99f) {
                normalNodes.forEach { renderNodeSingle(it, vpMatrix, true, true, clampedLightCount, lightPosArr, lightColArr) }
            }
            meshSystem.renderSkinnedMeshes(vpMatrix, nodes, 1f - postFade, ambientR, ambientG, ambientB, clampedLightCount, lightPosArr, lightColArr, whiteTexId, { getImageTexture(it) }, { getGlobalTransform(it) }) { frameDrawCalls++ }
            rigSystem.renderCloths(vpMatrix, nodes, 1f - postFade, whiteTexId, { getImageTexture(it) }, { getGlobalTransform(it) }) { frameDrawCalls++ }
            if (particleNodes.isNotEmpty()) {
                spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, 0, null, null) { frameDrawCalls++ }
                particleNodes.forEach { submitParticlesToBatch(it, true, 1f - postFade) }; spriteBatch.end()
            }
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, clampedLightCount, lightPosArr, lightColArr) { frameDrawCalls++ }
            worldNodes.forEach { n ->
                if (meshSystem.hasMesh(n.name)) return@forEach
                when {
                    n.nodeType == "ParticleEmitter" -> { spriteBatch.setInlineLight(false); submitParticlesToBatch(n, true, 1f) }
                    n.nodeType == "TileMap" -> { spriteBatch.flush(); renderTileMap(n, vpMatrix, true, true, clampedLightCount, lightPosArr, lightColArr) }
                    canBatchNode(n) -> { spriteBatch.setInlineLight(true); submitNodeToBatch(n, true) }
                    else -> { spriteBatch.flush(); renderNodeSingle(n, vpMatrix, true, true, clampedLightCount, lightPosArr, lightColArr) }
                }
            }
            spriteBatch.end()
            meshSystem.renderSkinnedMeshes(vpMatrix, nodes, 1f, ambientR, ambientG, ambientB, clampedLightCount, lightPosArr, lightColArr, whiteTexId, { getImageTexture(it) }, { getGlobalTransform(it) }) { frameDrawCalls++ }
            rigSystem.renderCloths(vpMatrix, nodes, 1f, whiteTexId, { getImageTexture(it) }, { getGlobalTransform(it) }) { frameDrawCalls++ }
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
        lastFrameDrawCalls = frameDrawCalls
        lastFrameCulled = frameCulled
        lastFrameNodes = nodes.size
        lastFrameTextures = textureCache.size
    }

    private fun canBatchNode(n: GameNode): Boolean =
        n.shader.isBlank() && n.normalMap.isBlank() && n.slopeType != "left_up" && n.slopeType != "right_up"

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
        val prog = if (lightingSystem.p8LightFboProgram != 0) lightingSystem.p8LightFboProgram else lightProgram
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE); GLES20.glUseProgram(prog)
        if (prog == lightingSystem.p8LightFboProgram) lightingSystem.bindSdfUniforms(prog)
        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition"); val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        val centerLoc = GLES20.glGetUniformLocation(prog, "uLightCenter")
        quadBuffer.position(0); GLES20.glEnableVertexAttribArray(posLoc); GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, quadBuffer)
        quadBuffer.position(3); GLES20.glEnableVertexAttribArray(uvLoc); GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, quadBuffer)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), 0f, 0f, 1f, 1f)
        lights.forEach { l -> val gt = getGlobalTransform(l); val rad = max(8f, l.lightRadius)
            if (!isBoxInView(gt.gx - rad, gt.gx + rad, gt.gy - rad, gt.gy + rad)) { frameCulled++; return@forEach }
            Matrix.setIdentityM(modelMatrix, 0); Matrix.translateM(modelMatrix, 0, gt.gx, gt.gy, gt.gz); Matrix.scaleM(modelMatrix, 0, rad * 2f, rad * 2f, 1f); Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
            if (centerLoc >= 0) GLES20.glUniform2f(centerLoc, gt.gx, gt.gy)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), l.r * l.lightIntensity, l.g * l.lightIntensity, l.b * l.lightIntensity, 1f)
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
        meshSystem.renderDebugWireframe(posLoc, uvLoc, colLoc, nodes, { getGlobalTransform(it) }) { frameDrawCalls++ }
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
            if (prog == lightingSystem.p8LitProgram) { lightingSystem.bindNormalMaterial(prog, n.normalMap, n.normalStrength, n.specular, false); lightingSystem.bindSdfUniforms(prog) }
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

        val fadeMul = if (isWorld && postFxEnabled && fboReady && n.normalMap.isNotEmpty()) (1f - postFade) else 1f
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uColor"), n.r * fadeMul, n.g * fadeMul, n.b * fadeMul, n.a); GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTime"), engineTime)
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
            if (prog == lightingSystem.p8LitProgram) {
                lightingSystem.bindNormalMaterial(prog, n.normalMap, n.normalStrength, n.specular, gt.gSx < 0f)
                lightingSystem.bindSdfUniforms(prog)
            }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId); GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexture"), 0)
        GLES20.glDrawArrays(if (vertCount == 3) GLES20.GL_TRIANGLES else GLES20.GL_TRIANGLE_STRIP, 0, vertCount); frameDrawCalls++
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc)
    }

    private fun getProgramForNode(n: GameNode): Int {
        if (n.shader.isBlank()) {
            return if (n.normalMap.isNotEmpty() && lightingSystem.p8LitProgram != 0) lightingSystem.p8LitProgram else defaultLitProgram
        }
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

    private fun resolveNodeUvRect(n: GameNode): FloatArray {
        val atlasData = if (n.atlas.isNotEmpty()) queryAtlas(n.atlas) else null
        val af = atlasData?.frames?.get(n.atlasFrame)
        if (atlasData != null && af != null && af.w > 0f && af.h > 0f) {
            val u0 = af.x / atlasData.atlasW
            val v0 = af.y / atlasData.atlasH
            return floatArrayOf(u0, v0, u0 + af.w / atlasData.atlasW, v0 + af.h / atlasData.atlasH)
        }
        val hf = max(1, n.hFrames); val vf = max(1, n.vFrames); val fi = n.currentFrame.coerceIn(0, hf * vf - 1)
        val uS = 1f / hf; val vS = 1f / vf
        val u0 = (fi % hf) * uS; val v0 = (fi / hf) * vS
        return floatArrayOf(u0, v0, u0 + uS, v0 + vS)
    }

    private fun parseJsonStringList(rawJson: String): List<String> {
        val list = mutableListOf<String>()
        if (rawJson.isBlank()) return list
        try {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) list.add(s)
            }
        } catch (_: Exception) {
            list.add(rawJson)
        }
        return list
    }

    private fun createClothFromJson(cfgJson: String, defaultAnchor: String): String {
        val cfg = try { JSONObject(cfgJson) } catch (_: Exception) { JSONObject() }
        val anchorName = cfg.optString("anchor", defaultAnchor)
        val id = cfg.optString("id", "${anchorName}_cloth_${System.nanoTime() % 1000}")
        val anchorNode = nodes.find { it.name == anchorName }
        val gt = anchorNode?.let { getGlobalTransform(it) }
        rigSystem.createCloth(
            id = id,
            anchorNodeName = anchorName,
            startX = gt?.gx ?: cfg.optDouble("x", 540.0).toFloat(),
            startY = gt?.gy ?: cfg.optDouble("y", 960.0).toFloat(),
            offsetX = cfg.optDouble("offsetX", 0.0).toFloat(),
            offsetY = cfg.optDouble("offsetY", 0.0).toFloat(),
            segments = cfg.optInt("segments", 10),
            segmentLen = cfg.optDouble("segmentLen", 18.0).toFloat(),
            widthStart = cfg.optDouble("widthStart", 38.0).toFloat(),
            widthEnd = cfg.optDouble("widthEnd", 10.0).toFloat(),
            r = cfg.optDouble("r", 0.88).toFloat(),
            g = cfg.optDouble("g", 0.18).toFloat(),
            b = cfg.optDouble("b", 0.24).toFloat(),
            a = cfg.optDouble("a", 0.95).toFloat(),
            gravityY = cfg.optDouble("gravityY", 980.0).toFloat(),
            windX = cfg.optDouble("windX", -120.0).toFloat(),
            turbulence = cfg.optDouble("turbulence", 180.0).toFloat(),
            texture = cfg.optString("texture", "")
        )
        return id
    }

    // ==================== QuickJS 核心初始化与属性/方法桥接 ====================
    private fun getNodeProp(n: GameNode, name: String): Any? = when (name) {
        "name" -> n.name; "tag" -> n.tag; "sceneGroup" -> n.sceneGroup; "persistent" -> n.persistent
        "x" -> n.x.toDouble(); "y" -> n.y.toDouble(); "z" -> n.z.toDouble()
        "vx" -> n.vx.toDouble(); "vy" -> n.vy.toDouble(); "vz" -> n.vz.toDouble()
        "width" -> n.width.toDouble(); "height" -> n.height.toDouble(); "rotation" -> n.rotation.toDouble()
        "tiltX" -> n.tiltX.toDouble(); "scaleX" -> n.scaleX.toDouble(); "scaleY" -> n.scaleY.toDouble()
        "parallaxX" -> n.parallaxX.toDouble(); "parallaxY" -> n.parallaxY.toDouble()
        "atlas" -> n.atlas; "atlasFrame" -> n.atlasFrame
        "normalMap" -> n.normalMap; "normalStrength" -> n.normalStrength.toDouble(); "specular" -> n.specular.toDouble()
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
        else -> null
    }

    private fun setNodeProp(n: GameNode, name: String, value: Any?) {
        when (name) {
            "tag" -> n.tag = toJsStr(value, n.tag); "persistent" -> n.persistent = toJsBool(value, n.persistent)
            "x" -> n.x = toJsFloat(value, n.x); "y" -> n.y = toJsFloat(value, n.y); "z" -> n.z = toJsFloat(value, n.z)
            "vx" -> n.vx = toJsFloat(value, n.vx); "vy" -> n.vy = toJsFloat(value, n.vy); "vz" -> n.vz = toJsFloat(value, n.vz)
            "width" -> n.width = toJsFloat(value, n.width); "height" -> n.height = toJsFloat(value, n.height)
            "rotation" -> n.rotation = toJsFloat(value, n.rotation); "tiltX" -> n.tiltX = toJsFloat(value, n.tiltX)
            "scaleX" -> n.scaleX = toJsFloat(value, n.scaleX); "scaleY" -> n.scaleY = toJsFloat(value, n.scaleY)
            "parallaxX" -> n.parallaxX = toJsFloat(value, n.parallaxX); "parallaxY" -> n.parallaxY = toJsFloat(value, n.parallaxY)
            "atlas" -> n.atlas = toJsStr(value, n.atlas); "atlasFrame" -> n.atlasFrame = toJsStr(value, n.atlasFrame)
            "normalMap" -> n.normalMap = toJsStr(value, n.normalMap)
            "normalStrength" -> n.normalStrength = toJsFloat(value, n.normalStrength)
            "specular" -> n.specular = toJsFloat(value, n.specular)
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
        }
    }

    private fun invokeNodeMethod(n: GameNode, method: String, args: Array<out Any?>): Any? {
        return when (method) {
            "createSkinnedMesh" -> {
                val uv = resolveNodeUvRect(n)
                meshSystem.createGridMesh(n, toJsInt(args.getOrNull(2), 8), toJsInt(args.getOrNull(3), 12), uv[0], uv[1], uv[2], uv[3]) { getGlobalTransform(it) }
                true
            }
            "bindBones" -> {
                val boneList = parseJsonStringList(toJsStr(args.getOrNull(2), "[]"))
                val smooth = toJsFloat(args.getOrNull(3), 2.2f)
                meshSystem.bindBones(n.name, boneList, nodes, smooth) { getGlobalTransform(it) }
            }
            "setMeshWave" -> {
                meshSystem.setMeshWave(n.name, toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 6f), toJsFloat(args.getOrNull(5), 4f)); null
            }
            "removeSkinnedMesh" -> { meshSystem.removeMesh(n.name); null }
            "attachCloth" -> createClothFromJson(toJsStr(args.getOrNull(2), "{}"), n.name)
            "setNormalMap" -> {
                n.normalMap = toJsStr(args.getOrNull(2), "")
                (args.getOrNull(3) as? Number)?.toFloat()?.let { n.normalStrength = it }
                (args.getOrNull(4) as? Number)?.toFloat()?.let { n.specular = it }
                null
            }
            "setAnimFloat" -> { n.animator.fsm.setFloat(toJsStr(args.getOrNull(2), ""), toJsFloat(args.getOrNull(3), 0f)); null }
            "setAnimBool" -> { n.animator.fsm.setBool(toJsStr(args.getOrNull(2), ""), toJsBool(args.getOrNull(3), false)); null }
            "triggerAnim" -> { n.animator.fsm.setTrigger(toJsStr(args.getOrNull(2), "")); null }
            "setAnimSpeed" -> { n.animator.speedScale = toJsFloat(args.getOrNull(2), 1f).coerceIn(0.05f, 8f); null }
            "getAnimState" -> n.animator.fsm.currentState.ifEmpty { n.currentClipName }
            "isAnimCancelable" -> n.animator.activeFrame?.cancelable == true
            "addAnimTransition" -> {
                val from = toJsStr(args.getOrNull(2), "*")
                val to = toJsStr(args.getOrNull(3), "")
                val param = toJsStr(args.getOrNull(4), "")
                val op = toJsStr(args.getOrNull(5), "trigger")
                val cmpVal = args.getOrNull(6)
                val prio = toJsInt(args.getOrNull(7), 0)
                val hasExit = toJsBool(args.getOrNull(8), false)
                val onlyCancel = toJsBool(args.getOrNull(9), false)
                if (to.isNotEmpty()) {
                    n.animator.fsm.addTransition(StateTransition(from, to, prio, hasExit, onlyCancel, if (param.isNotEmpty()) listOf(StateCondition(param, op, cmpVal)) else emptyList()))
                }
                null
            }
            "registerProClip" -> {
                val s = toJsStr(args.getOrNull(2), "")
                if (s.isBlank()) false else try {
                    ZeroAnimator.parseInto(n.animator, JSONObject().put("proClips", JSONArray().put(JSONObject(s)))); true
                } catch (e: Exception) { onConsoleLog("[ANIM 错误 @ ${n.name}] ${e.message}"); false }
            }
            "setAtlas" -> { n.atlas = toJsStr(args.getOrNull(2), n.atlas); n.atlasFrame = toJsStr(args.getOrNull(3), ""); queryAtlas(n.atlas); null }
            "setNineSlice" -> {
                val l = toJsFloat(args.getOrNull(2), 0f)
                n.sliceLeft = l; n.sliceRight = toJsFloat(args.getOrNull(3), l); n.sliceTop = toJsFloat(args.getOrNull(4), l); n.sliceBottom = toJsFloat(args.getOrNull(5), n.sliceTop); null
            }
            "setClipRect" -> {
                n.clipX = toJsFloat(args.getOrNull(2), 0f); n.clipY = toJsFloat(args.getOrNull(3), 0f); n.clipW = toJsFloat(args.getOrNull(4), 0f); n.clipH = toJsFloat(args.getOrNull(5), 0f); null
            }
            "jump" -> {
                val jf = toJsFloat(args.getOrNull(2), 920f)
                if (n.isGrounded || n.coyoteTimer > 0f) { n.vy = -abs(jf); n.isGrounded = false; n.coyoteTimer = 0f; n.jumpCount = 1; true }
                else if (n.jumpCount < n.maxJumps) { n.vy = -abs(jf) * 0.9f; n.jumpCount++; true }
                else false
            }
            "dropThrough" -> { n.dropThroughTimer = toJsFloat(args.getOrNull(2), 0.22f); n.isGrounded = false; null }
            "bounce" -> { (args.getOrNull(2) as? Number)?.toFloat()?.let { n.vx = it }; n.vy = toJsFloat(args.getOrNull(3), -760f); n.isGrounded = false; n.jumpCount = 1; null }
            "play" -> {
                val cn = toJsStr(args.getOrNull(2), ""); val fr = toJsBool(args.getOrNull(3), false)
                if (n.animator.play(cn, fr)) { n.currentClipName = cn }
                else n.clips[cn]?.let { c ->
                    if (!fr && n.animPlaying && n.currentClipName == cn) return null
                    n.currentClipName = cn; n.animStart = c.start; n.animEnd = c.end; n.animFps = c.fps; n.animLoop = c.loop; n.currentFrame = c.start; n.animTimer = 0f; n.animPlaying = true
                } ?: onConsoleLog("[ANIM 警告 @ ${n.name}] 未找到: $cn")
                null
            }
            "playAnim" -> {
                n.animator.stop(); n.currentClipName = ""
                n.animStart = toJsInt(args.getOrNull(2), 0); n.animEnd = toJsInt(args.getOrNull(3), 0)
                n.animFps = toJsFloat(args.getOrNull(4), 12f); n.animLoop = toJsBool(args.getOrNull(5), true)
                n.currentFrame = n.animStart; n.animTimer = 0f; n.animPlaying = true; null
            }
            "stopAnim" -> { n.animator.stop(); n.animPlaying = false; n.currentClipName = ""; null }
            "flash" -> { n.flashTimer = toJsFloat(args.getOrNull(2), 0.12f); null }
            "emitBurst" -> { val cnt = toJsInt(args.getOrNull(2), 24).coerceIn(1, 120); val gt = getGlobalTransform(n); repeat(cnt) { spawnSingleParticle(n, gt.gx, gt.gy) }; null }
            else -> null
        }
    }

    private fun invokeEngineMethod(method: String, args: Array<out Any?>): Any? {
        return when (method) {
            "unloadUnusedTextures" -> unloadUnusedTextures()
            "setSdfShadow" -> {
                lightingSystem.sdfShadowEnabled = toJsBool(args.getOrNull(1), true)
                (args.getOrNull(2) as? Number)?.toFloat()?.let { lightingSystem.shadowSoftness = it.coerceIn(1f, 40f) }
                (args.getOrNull(3) as? Number)?.toFloat()?.let { lightingSystem.shadowMinLight = it.coerceIn(0f, 1f) }
                null
            }
            "setGI" -> {
                val raw = toJsStr(args.getOrNull(1), "")
                if (raw == "true" || raw == "false") {
                    giSystem.enabled = raw == "true"
                } else if (raw.startsWith("{")) {
                    try {
                        val o = JSONObject(raw)
                        giSystem.enabled = o.optBoolean("enabled", giSystem.enabled)
                        giSystem.intensity = o.optDouble("intensity", giSystem.intensity.toDouble()).toFloat().coerceIn(0f, 3f)
                        giSystem.radius = o.optDouble("radius", giSystem.radius.toDouble()).toFloat().coerceIn(0.02f, 0.45f)
                        giSystem.threshold = o.optDouble("threshold", giSystem.threshold.toDouble()).toFloat().coerceIn(0f, 1.5f)
                        giSystem.saturation = o.optDouble("saturation", giSystem.saturation.toDouble()).toFloat().coerceIn(0f, 3f)
                        giSystem.skyR = o.optDouble("skyR", giSystem.skyR.toDouble()).toFloat().coerceIn(0f, 1f)
                        giSystem.skyG = o.optDouble("skyG", giSystem.skyG.toDouble()).toFloat().coerceIn(0f, 1f)
                        giSystem.skyB = o.optDouble("skyB", giSystem.skyB.toDouble()).toFloat().coerceIn(0f, 1f)
                    } catch (_: Exception) {}
                }
                null
            }
            "createCloth" -> createClothFromJson(toJsStr(args.getOrNull(1), "{}"), "")
            "removeCloth" -> { rigSystem.removeCloth(toJsStr(args.getOrNull(1), "")); null }
            "setGlobalWind" -> { rigSystem.setGlobalWind(toJsFloat(args.getOrNull(1), 0f), toJsFloat(args.getOrNull(2), 0f)); null }
            "solveIK" -> {
                val root = nodes.find { it.name == toJsStr(args.getOrNull(1), "") } ?: return false
                val mid = nodes.find { it.name == toJsStr(args.getOrNull(2), "") } ?: return false
                rigSystem.solveTwoBoneIK(root, mid, toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 0f), toJsFloat(args.getOrNull(5), 80f), toJsFloat(args.getOrNull(6), 80f), toJsBool(args.getOrNull(7), false)) { getGlobalTransform(it) }
            }
            "registerIK" -> {
                rigSystem.registerTwoBoneIK(toJsStr(args.getOrNull(1), ""), toJsStr(args.getOrNull(2), ""), toJsStr(args.getOrNull(3), ""), toJsFloat(args.getOrNull(4), 80f), toJsFloat(args.getOrNull(5), 80f), toJsBool(args.getOrNull(6), false)); null
            }
            "setIKTarget" -> { rigSystem.setIkTarget(toJsStr(args.getOrNull(1), ""), toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f), toJsBool(args.getOrNull(4), true)); null }
            "removeIK" -> { rigSystem.removeIk(toJsStr(args.getOrNull(1), "")); null }
            "createSkinnedMesh" -> {
                val target = nodes.find { it.name == toJsStr(args.getOrNull(1), "") } ?: return false
                val uv = resolveNodeUvRect(target)
                meshSystem.createGridMesh(target, toJsInt(args.getOrNull(2), 8), toJsInt(args.getOrNull(3), 12), uv[0], uv[1], uv[2], uv[3]) { getGlobalTransform(it) }
                true
            }
            "bindBones" -> {
                meshSystem.bindBones(toJsStr(args.getOrNull(1), ""), parseJsonStringList(toJsStr(args.getOrNull(2), "[]")), nodes, toJsFloat(args.getOrNull(3), 2.2f)) { getGlobalTransform(it) }
            }
            "setMeshWave" -> {
                meshSystem.setMeshWave(toJsStr(args.getOrNull(1), ""), toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 6f), toJsFloat(args.getOrNull(5), 4f)); null
            }
            "removeSkinnedMesh" -> { meshSystem.removeMesh(toJsStr(args.getOrNull(1), "")); null }
            "loadAtlas" -> {
                val d = queryAtlas(toJsStr(args.getOrNull(1), "")) ?: return null
                JSONObject().apply {
                    put("image", d.imageFile); put("width", d.atlasW.toDouble()); put("height", d.atlasH.toDouble())
                    put("frames", JSONArray(d.frames.keys.sorted()))
                }.toString()
            }
            "setFlag" -> {
                val k = toJsStr(args.getOrNull(1), "")
                if (k.isNotEmpty()) {
                    val v = if (args.size >= 3) args[2] else true
                    dataStore.worldFlags[k] = when (v) { is Boolean -> v; is Number -> v.toDouble(); null -> false; else -> v.toString() }
                }
                null
            }
            "getFlag" -> dataStore.worldFlags[toJsStr(args.getOrNull(1), "")] ?: if (args.size >= 3) args[2] else false
            "hasFlag" -> when (val v = dataStore.worldFlags[toJsStr(args.getOrNull(1), "")]) {
                null -> false; is Boolean -> v; is Number -> v.toDouble() != 0.0; is String -> v.isNotEmpty(); else -> true
            }
            "clearFlag" -> { dataStore.worldFlags.remove(toJsStr(args.getOrNull(1), "")); null }
            "clearAllFlags" -> { dataStore.worldFlags.clear(); null }
            "getAllFlags" -> JSONObject().apply { dataStore.worldFlags.forEach { (k, v) -> put(k, v) } }.toString()
            "saveWorldFlags" -> dataStore.saveWorldFlagsToDisk(toJsStr(args.getOrNull(1), "world_flags"))
            "loadWorldFlags" -> dataStore.loadWorldFlagsFromDisk(toJsStr(args.getOrNull(1), "world_flags"))
            "readTable" -> dataStore.readDataTableJson(toJsStr(args.getOrNull(1), ""))?.second
            "setLanguage" -> dataStore.setLanguage(toJsStr(args.getOrNull(1), "zh"))
            "getLanguage" -> dataStore.currentLang
            "setI18nDict" -> { dataStore.setI18nDictJson(toJsStr(args.getOrNull(1), "zh"), toJsStr(args.getOrNull(2), "{}")); null }
            "t" -> dataStore.translateKey(toJsStr(args.getOrNull(1), ""), toJsStr(args.getOrNull(2), ""))
            "setDebugDraw" -> { physicsSystem.debugDrawEnabled = toJsBool(args.getOrNull(1), false); null }
            "isDebugDraw" -> physicsSystem.debugDrawEnabled
            "setPostFX" -> {
                try {
                    val cfg = JSONObject(toJsStr(args.getOrNull(1), "{}"))
                    postFxEnabled = cfg.optBoolean("enabled", postFxEnabled)
                    postBloom = cfg.optDouble("bloom", postBloom.toDouble()).toFloat().coerceIn(0f, 3f)
                    postVignette = cfg.optDouble("vignette", postVignette.toDouble()).toFloat().coerceIn(0f, 2f)
                    postChromatic = cfg.optDouble("chromatic", postChromatic.toDouble()).toFloat().coerceIn(0f, 3f)
                    postFade = cfg.optDouble("fade", postFade.toDouble()).toFloat().coerceIn(0f, 1f)
                } catch (_: Exception) {}
                null
            }
            "setFade" -> {
                val tg = toJsFloat(args.getOrNull(1), 0f).coerceIn(0f, 1f); val d = toJsFloat(args.getOrNull(2), 0.3f)
                pendingFadeScene = null
                if (d <= 0.01f) { postFade = tg; fadeTarget = tg; fadeSpeed = 0f } else { fadeTarget = tg; fadeSpeed = abs(tg - postFade) / d }
                null
            }
            "fadeToScene" -> {
                val s = toJsStr(args.getOrNull(1), ""); val d = toJsFloat(args.getOrNull(2), 0.35f).coerceAtLeast(0.05f)
                if (s.isNotEmpty()) { preloadSceneAsync(s); pendingFadeScene = normalizeSceneName(s); fadeSceneDur = d; fadeTarget = 1f; fadeSpeed = 1f / d }
                null
            }
            "preloadScene" -> { val s = toJsStr(args.getOrNull(1), ""); if (s.isNotEmpty()) preloadSceneAsync(s); null }
            "loadSceneAdditive" -> {
                val s = toJsStr(args.getOrNull(1), "")
                if (s.isNotEmpty()) pendingAdditiveLoads.add(AdditiveLoadReq(normalizeSceneName(s), toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f)))
                null
            }
            "unloadScene" -> { val s = toJsStr(args.getOrNull(1), ""); if (s.isNotEmpty()) pendingUnloadScenes.add(normalizeSceneName(s)); null }
            "clearSceneCache" -> { preloadedSceneCache.clear(); null }
            "getStats" -> JSONObject().apply {
                put("vm", "QuickJS")
                put("fps", smoothedFps.toDouble()); put("drawCalls", lastFrameDrawCalls); put("culled", lastFrameCulled)
                put("nodes", nodes.size); put("lights", lastFrameLights); put("occluders", lightingSystem.activeOccluderCount)
                put("cloths", rigSystem.activeClothCount); put("iks", rigSystem.activeIkCount); put("skinnedMeshes", meshSystem.activeMeshCount)
                put("gi", giSystem.isReady); put("sdfShadow", lightingSystem.sdfShadowEnabled); put("spatialCells", physicsSystem.spatialCellsCount)
                put("atlases", atlasCache.size); put("flags", dataStore.worldFlags.size); put("lang", dataStore.currentLang)
                put("fbo", postFxEnabled && fboReady); put("touches", inputSystem.getTouchCount()); put("textures", textureCache.size)
            }.toString()
            "loadScene" -> { val s = toJsStr(args.getOrNull(1), ""); if (s.isNotEmpty()) pendingSceneLoad = normalizeSceneName(s); null }
            "saveDisk" -> dataStore.saveDiskSlotJson(toJsStr(args.getOrNull(1), "slot_1"), toJsStr(args.getOrNull(2), "{}"))
            "loadDisk" -> dataStore.loadDiskSlotJson(toJsStr(args.getOrNull(1), "slot_1"))
            "deleteDisk" -> dataStore.deleteDiskSlot(toJsStr(args.getOrNull(1), "slot_1"))
            "raycast" -> {
                val r = physicsSystem.raycast(
                    toJsFloat(args.getOrNull(1), 0f), toJsFloat(args.getOrNull(2), 0f),
                    toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 0f),
                    toJsStr(args.getOrNull(5), ""), toJsInt(args.getOrNull(6), 0xFFFF)
                ) { getGlobalTransform(it) }
                JSONObject().apply {
                    put("hit", r.hit); put("x", r.x.toDouble()); put("y", r.y.toDouble()); put("distance", r.distance.toDouble())
                    put("nodeId", r.node?.nodeId ?: -1)
                }.toString()
            }
            "hitstop" -> { hitstopTimer = max(hitstopTimer, toJsFloat(args.getOrNull(1), 0.05f).coerceIn(0f, 0.5f)); null }
            "setTimeScale" -> { timeScale = toJsFloat(args.getOrNull(1), 1f).coerceIn(0.05f, 4f); null }
            "setCameraMode" -> { camMode = toJsStr(args.getOrNull(1), "2.5D"); null }
            "setCameraTilt" -> {
                camPitch = toJsFloat(args.getOrNull(1), camPitch); camDistance = toJsFloat(args.getOrNull(2), camDistance); camFov = toJsFloat(args.getOrNull(3), camFov); null
            }
            "setCameraTarget" -> { camTarget = nodeById[toJsInt(args.getOrNull(1), -1)]; null }
            "setCameraDeadzone" -> { camDeadzoneX = toJsFloat(args.getOrNull(1), 0f); camDeadzoneY = toJsFloat(args.getOrNull(2), 0f); null }
            "setCameraLookAhead" -> { camLookAheadX = toJsFloat(args.getOrNull(1), 0f); camLookAheadY = toJsFloat(args.getOrNull(2), 0f); null }
            "setCameraBounds" -> {
                camBoundsEnabled = true; camMinX = toJsFloat(args.getOrNull(1), -99999f); camMinY = toJsFloat(args.getOrNull(2), -99999f)
                camMaxX = toJsFloat(args.getOrNull(3), 99999f); camMaxY = toJsFloat(args.getOrNull(4), 99999f); null
            }
            "clearCameraBounds" -> { camBoundsEnabled = false; null }
            "getCamera" -> JSONObject().apply { put("x", camX.toDouble()); put("y", camY.toDouble()); put("pitch", camPitch.toDouble()); put("mode", camMode) }.toString()
            "setAmbientLight" -> { ambientR = toJsFloat(args.getOrNull(1), 0.52f); ambientG = toJsFloat(args.getOrNull(2), 0.56f); ambientB = toJsFloat(args.getOrNull(3), 0.68f); null }
            "shake" -> { shakeIntensity = toJsFloat(args.getOrNull(1), 12f); shakeTimer = toJsFloat(args.getOrNull(2), 0.15f); null }
            "tween" -> {
                val n = nodeById[toJsInt(args.getOrNull(1), -1)] ?: return null
                val p = toJsStr(args.getOrNull(2), ""); val tv = (args.getOrNull(3) as? Number)?.toFloat() ?: return null
                activeTweens.removeAll { it.node === n && it.prop == p }
                activeTweens.add(TweenTask(n, p, readTweenProperty(n, p), tv, toJsFloat(args.getOrNull(4), 0.25f), toJsStr(args.getOrNull(5), "easeOut"))); null
            }
            "playTone" -> { audioSystem.playToneAsync(toJsInt(args.getOrNull(1), 440), toJsInt(args.getOrNull(2), 80)); null }
            "playBGM" -> { val f = toJsStr(args.getOrNull(1), ""); if (f.isNotEmpty()) audioSystem.playBGM(f, toJsBool(args.getOrNull(2), true), toJsFloat(args.getOrNull(3), 0.8f)); null }
            "crossfadeBGM" -> { val f = toJsStr(args.getOrNull(1), ""); if (f.isNotEmpty()) audioSystem.crossfadeBGM(f, toJsFloat(args.getOrNull(2), 1.2f), toJsBool(args.getOrNull(3), true), toJsFloat(args.getOrNull(4), 0.8f)); null }
            "stopBGM" -> { audioSystem.stopBGM(toJsFloat(args.getOrNull(1), 0f)); null }
            "playSFX" -> { val f = toJsStr(args.getOrNull(1), ""); val pMin = toJsFloat(args.getOrNull(3), 1.0f); if (f.isNotEmpty()) audioSystem.playSFX(f, toJsFloat(args.getOrNull(2), 1.0f), pMin, toJsFloat(args.getOrNull(4), pMin)); null }
            "setVolume" -> { audioSystem.setVolume(toJsStr(args.getOrNull(1), "master"), toJsFloat(args.getOrNull(2), 1.0f)); null }
            "getVolume" -> audioSystem.getVolume(toJsStr(args.getOrNull(1), "master")).toDouble()
            "moveAndSlide" -> {
                val n = nodeById[toJsInt(args.getOrNull(1), -1)] ?: return null
                physicsSystem.moveAndSlide(n, toJsFloat(args.getOrNull(2), 0.016f), pendingRemove, { getGlobalTransform(it) }) { nd, fn, other -> callNodeEvent(nd, fn, other) }; null
            }
            "distance" -> {
                val a = nodeById[toJsInt(args.getOrNull(1), -1)]; val b = nodeById[toJsInt(args.getOrNull(2), -1)]
                if (a != null && b != null) { val ga = getGlobalTransform(a); val gb = getGlobalTransform(b); hypot(ga.gx - gb.gx, ga.gy - gb.gy).toDouble() } else 999999.0
            }
            "angleTo" -> {
                val a = nodeById[toJsInt(args.getOrNull(1), -1)]; val b = nodeById[toJsInt(args.getOrNull(2), -1)]
                if (a != null && b != null) { val ga = getGlobalTransform(a); val gb = getGlobalTransform(b); Math.toDegrees(atan2((gb.gy - ga.gy).toDouble(), (gb.gx - ga.gx).toDouble())) } else 0.0
            }
            "spawn" -> {
                val cfg = try { JSONObject(toJsStr(args.getOrNull(1), "{}")) } catch (_: Exception) { return -1 }
                val rawLayer = cfg.optString("layer", "World")
                val sp = GameNode(
                    name = cfg.optString("name", "Spawn_${System.nanoTime() % 10000}"), nodeType = cfg.optString("nodeType", "Sprite2D"),
                    layer = if (rawLayer.startsWith("UI", ignoreCase = true)) "UI" else "World", parent = toJsStr(args.getOrNull(2), ""),
                    tag = cfg.optString("tag", "default"), persistent = cfg.optBoolean("persistent", false),
                    x = cfg.optDouble("x", 0.0).toFloat(), y = cfg.optDouble("y", 0.0).toFloat(), z = cfg.optDouble("z", 0.0).toFloat(),
                    width = cfg.optDouble("width", 64.0).toFloat(), height = cfg.optDouble("height", 64.0).toFloat(),
                    tiltX = cfg.optDouble("tiltX", 0.0).toFloat(), billboard = cfg.optBoolean("billboard", false), zIndex = cfg.optInt("zIndex", 0),
                    texture = cfg.optString("texture", ""), atlas = cfg.optString("atlas", ""), atlasFrame = cfg.optString("atlasFrame", ""),
                    normalMap = cfg.optString("normalMap", ""), normalStrength = cfg.optDouble("normalStrength", 1.0).toFloat(), specular = cfg.optDouble("specular", 0.25).toFloat(),
                    text = cfg.optString("text", ""), fontSize = cfg.optDouble("fontSize", 44.0).toFloat(),
                    r = cfg.optDouble("r", 1.0).toFloat(), g = cfg.optDouble("g", 1.0).toFloat(), b = cfg.optDouble("b", 1.0).toFloat(), a = cfg.optDouble("a", 1.0).toFloat(),
                    sliceLeft = cfg.optDouble("sliceLeft", 0.0).toFloat(), sliceRight = cfg.optDouble("sliceRight", 0.0).toFloat(),
                    sliceTop = cfg.optDouble("sliceTop", 0.0).toFloat(), sliceBottom = cfg.optDouble("sliceBottom", 0.0).toFloat(),
                    clipChildren = cfg.optBoolean("clipChildren", false), clipX = cfg.optDouble("clipX", 0.0).toFloat(),
                    clipY = cfg.optDouble("clipY", 0.0).toFloat(), clipW = cfg.optDouble("clipW", 0.0).toFloat(), clipH = cfg.optDouble("clipH", 0.0).toFloat(),
                    collider = cfg.optBoolean("collider", false), isTrigger = cfg.optBoolean("isTrigger", false), oneWay = cfg.optBoolean("oneWay", false),
                    slopeType = cfg.optString("slopeType", "none"), colOffsetX = cfg.optDouble("colOffsetX", 0.0).toFloat(), colOffsetY = cfg.optDouble("colOffsetY", 0.0).toFloat(),
                    colWidth = cfg.optDouble("colWidth", 0.0).toFloat(), colHeight = cfg.optDouble("colHeight", 0.0).toFloat(),
                    colLayer = cfg.optInt("colLayer", 1), colMask = cfg.optInt("colMask", 0xFFFF), bodyMode = cfg.optString("bodyMode", "kinematic"),
                    isHitbox = cfg.optBoolean("isHitbox", false), isHurtbox = cfg.optBoolean("isHurtbox", false), damage = cfg.optDouble("damage", 10.0).toFloat(),
                    shadowCaster = cfg.optBoolean("shadowCaster", false), lightRadius = cfg.optDouble("lightRadius", 400.0).toFloat(), lightIntensity = cfg.optDouble("lightIntensity", 1.5).toFloat(),
                    script = dataStore.resolveNodeScriptText(cfg.optString("script", ""), cfg.optString("scriptFile", ""))
                )
                pendingAdd.add(sp); nodeById[sp.nodeId] = sp; bindNodeScript(sp); sp.nodeId
            }
            "instantiate" -> {
                val sName = toJsStr(args.getOrNull(1), "")
                val txt = readSceneText(sName) ?: return -1
                val loaded = mutableListOf<GameNode>(); parseSceneJson(txt, loaded, normalizeSceneName(sName), false)
                val sx = toJsFloat(args.getOrNull(2), 0f); val sy = toJsFloat(args.getOrNull(3), 0f); val sz = toJsFloat(args.getOrNull(4), 0f)
                loaded.forEach { if (it.parent.isEmpty()) { it.x += sx; it.y += sy; it.z += sz }; pendingAdd.add(it); nodeById[it.nodeId] = it; bindNodeScript(it) }
                loaded.firstOrNull()?.nodeId ?: -1
            }
            "destroy" -> { nodeById[toJsInt(args.getOrNull(1), -1)]?.let { pendingRemove.add(it) }; null }
            "findNode" -> nodes.find { it.name == toJsStr(args.getOrNull(1), "") && !pendingRemove.contains(it) }?.nodeId ?: -1
            "findNodesByTag" -> {
                val tg = toJsStr(args.getOrNull(1), "")
                JSONArray(nodes.filter { it.tag == tg && !pendingRemove.contains(it) }.map { it.nodeId }).toString()
            }
            else -> null
        }
    }

    private fun initQuickJsVM() {
        destroyQuickJsVM()
        if (!qjsLibInited) {
            try { QuickJSLoader.init(); qjsLibInited = true } catch (e: Throwable) { onConsoleLog("[QuickJS 加载失败] ${e.message}") }
        }
        val ctx = QuickJSContext.create()
        qjs = ctx
        dataStore.clearRuntimeCaches(); atlasCache.clear()

        val g = ctx.getGlobalObject()
        g.setProperty("__zLog", JSCallFunction { args -> onConsoleLog(toJsStr(args.getOrNull(0), "")); null })
        g.setProperty("__zReadScript", JSCallFunction { args -> dataStore.readScriptModuleText(toJsStr(args.getOrNull(0), ""))?.let { JSONArray().put(it.first).put(it.second).toString() } })
        g.setProperty("__zNodeGet", JSCallFunction { args -> nodeById[toJsInt(args.getOrNull(0), -1)]?.let { getNodeProp(it, toJsStr(args.getOrNull(1), "")) } })
        g.setProperty("__zNodeSet", JSCallFunction { args -> nodeById[toJsInt(args.getOrNull(0), -1)]?.let { setNodeProp(it, toJsStr(args.getOrNull(1), ""), args.getOrNull(2)) }; null })
        g.setProperty("__zNodeCall", JSCallFunction { args -> nodeById[toJsInt(args.getOrNull(0), -1)]?.let { invokeNodeMethod(it, toJsStr(args.getOrNull(1), ""), args) } })
        g.setProperty("__zEngineCall", JSCallFunction { args -> invokeEngineMethod(toJsStr(args.getOrNull(0), ""), args) })
        g.setProperty("__zAdReady", JSCallFunction { args -> ZeroAdBridge.isReady(toJsStr(args.getOrNull(0), "reward")) })
        g.setProperty("__zAdShowInter", JSCallFunction { args ->
            val cbId = toJsInt(args.getOrNull(0), -1)
            ZeroAdBridge.showInterstitial(context) { ok ->
                if (cbId >= 0) queueEvent { try { fnAdCallback?.call(cbId, ok) } catch (_: Exception) {} }
            }
            null
        })
        g.setProperty("__zAdShowReward", JSCallFunction { args ->
            val cbId = toJsInt(args.getOrNull(0), -1)
            ZeroAdBridge.showRewarded(context) { ok ->
                if (cbId >= 0) queueEvent { try { fnAdCallback?.call(cbId, ok) } catch (_: Exception) {} }
            }
            null
        })
        g.setProperty("__zInputGet", JSCallFunction { args ->
            when (toJsStr(args.getOrNull(0), "")) {
                "isTouching" -> isTouching || inputSystem.getTouchCount() > 0
                "touchCount" -> inputSystem.getTouchCount()
                "screenX", "touchX" -> touchScreenX.toDouble()
                "screenY", "touchY" -> touchScreenY.toDouble()
                "worldX" -> touchWorldX.toDouble()
                "worldY" -> touchWorldY.toDouble()
                else -> null
            }
        })
        g.setProperty("__zInputCall", JSCallFunction { args ->
            when (toJsStr(args.getOrNull(0), "")) {
                "isActionPressed" -> inputSystem.isActionPressed(toJsStr(args.getOrNull(1), ""))
                "isActionJustPressed" -> inputSystem.isActionJustPressed(toJsStr(args.getOrNull(1), ""))
                "isActionJustReleased" -> inputSystem.isActionJustReleased(toJsStr(args.getOrNull(1), ""))
                "getAxis" -> inputSystem.getAxis(toJsStr(args.getOrNull(1), "move_left"), toJsStr(args.getOrNull(2), "move_right")).toDouble()
                "bindAction" -> {
                    val act = toJsStr(args.getOrNull(1), ""); val list = parseJsonStringList(toJsStr(args.getOrNull(2), "[]"))
                    if (act.isNotEmpty() && list.isNotEmpty()) inputSystem.bindAction(act, list); null
                }
                "setVirtualAction" -> { val act = toJsStr(args.getOrNull(1), ""); if (act.isNotEmpty()) inputSystem.setVirtualAction(act, toJsBool(args.getOrNull(2), false)); null }
                "bindTouchZone" -> {
                    val act = toJsStr(args.getOrNull(1), "")
                    if (act.isNotEmpty()) inputSystem.bindTouchZone(act, toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 160f), toJsFloat(args.getOrNull(5), 160f)); null
                }
                "clearTouchZones" -> { inputSystem.clearTouchZones(); null }
                else -> null
            }
        })

        ctx.evaluate(ZERO_QJS_BOOTSTRAP, "zero_bootstrap.js")
        fnBindNode = g.getJSFunction("__zBindNode")
        fnDestroyNode = g.getJSFunction("__zDestroyNode")
        fnCallHook = g.getJSFunction("__zCallHook")
        fnDispatchTouch = g.getJSFunction("__zDispatchTouch")
        fnAdCallback = g.getJSFunction("__zOnAdResult")
        onConsoleLog("[VM] QuickJS ES6+ 高性能脚本引擎已启动")
    }

    private fun bindNodeScript(n: GameNode) {
        try {
            fnBindNode?.call(n.nodeId, n.name, n.script)
        } catch (e: Exception) {
            onConsoleLog("[JS 编译错误 @ ${n.name}] ${e.message}")
        }
    }

    private fun callLifecycle(n: GameNode, fnName: String, arg: Any? = null) {
        try {
            fnCallHook?.call(n.nodeId, fnName, "val", arg)
        } catch (e: Exception) {
            onConsoleLog("[JS 错误 @ ${n.name}.$fnName] ${e.message}")
        }
    }

    private fun callNodeEvent(n: GameNode, fnName: String, other: GameNode) {
        try {
            fnCallHook?.call(n.nodeId, fnName, "node", other.nodeId)
        } catch (e: Exception) {
            onConsoleLog("[JS 错误 @ ${n.name}.$fnName] ${e.message}")
        }
    }

    private fun callFrameEvent(n: GameNode, clipName: String, evName: String) {
        try {
            fnCallHook?.call(n.nodeId, "onFrameEvent", "two", clipName, evName)
        } catch (e: Exception) {
            onConsoleLog("[JS 错误 @ ${n.name}.onFrameEvent] ${e.message}")
        }
    }

    private fun callHurtCallback(hurt: GameNode, attacker: GameNode, dmg: Float, kbX: Float, kbY: Float) {
        try {
            fnCallHook?.call(hurt.nodeId, "onHurt", "hurt", attacker.nodeId, dmg.toDouble(), kbX.toDouble(), kbY.toDouble())
        } catch (e: Exception) {
            onConsoleLog("[JS 运行错误 @ ${hurt.name}.onHurt] ${e.message}")
        }
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
        list.forEach { (tp, sx, sy) ->
            try { fnDispatchTouch?.call(tp, sx.toDouble(), sy.toDouble(), touchWorldX.toDouble(), touchWorldY.toDouble()) } catch (_: Exception) {}
        }
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
                val node = GameNode(name = o.optString("name", "Node_$i"), nodeType = o.optString("nodeType", "Sprite2D"), layer = if (rawLayer.startsWith("UI", ignoreCase = true)) "UI" else "World", parent = o.optString("parent", ""), tag = o.optString("tag", "default"), sceneGroup = groupName, persistent = o.optBoolean("persistent", false), x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(), z = o.optDouble("z", 0.0).toFloat(), width = o.optDouble("width", 120.0).toFloat(), height = o.optDouble("height", 120.0).toFloat(), rotation = o.optDouble("rotation", 0.0).toFloat(), tiltX = o.optDouble("tiltX", 0.0).toFloat(), billboard = o.optBoolean("billboard", false), scaleX = o.optDouble("scaleX", 1.0).toFloat(), scaleY = o.optDouble("scaleY", 1.0).toFloat(), zIndex = o.optInt("zIndex", 0), visible = o.optBoolean("visible", true), parallaxX = o.optDouble("parallaxX", 1.0).toFloat(), parallaxY = o.optDouble("parallaxY", 1.0).toFloat(), texture = o.optString("texture", ""), atlas = o.optString("atlas", ""), atlasFrame = o.optString("atlasFrame", ""), normalMap = o.optString("normalMap", ""), normalStrength = o.optDouble("normalStrength", 1.0).toFloat(), specular = o.optDouble("specular", 0.25).toFloat(), text = o.optString("text", ""), fontSize = o.optDouble("fontSize", 44.0).toFloat(), r = o.optDouble("r", 1.0).toFloat(), g = o.optDouble("g", 1.0).toFloat(), b = o.optDouble("b", 1.0).toFloat(), a = o.optDouble("a", 1.0).toFloat(), lightRadius = o.optDouble("lightRadius", 450.0).toFloat(), lightIntensity = o.optDouble("lightIntensity", 1.4).toFloat(), shadowCaster = o.optBoolean("shadowCaster", false), sliceLeft = o.optDouble("sliceLeft", 0.0).toFloat(), sliceRight = o.optDouble("sliceRight", 0.0).toFloat(), sliceTop = o.optDouble("sliceTop", 0.0).toFloat(), sliceBottom = o.optDouble("sliceBottom", 0.0).toFloat(), clipChildren = o.optBoolean("clipChildren", false), clipX = o.optDouble("clipX", 0.0).toFloat(), clipY = o.optDouble("clipY", 0.0).toFloat(), clipW = o.optDouble("clipW", 0.0).toFloat(), clipH = o.optDouble("clipH", 0.0).toFloat(), collider = o.optBoolean("collider", false), isTrigger = o.optBoolean("isTrigger", false), oneWay = o.optBoolean("oneWay", false), slopeType = o.optString("slopeType", "none"), colOffsetX = o.optDouble("colOffsetX", 0.0).toFloat(), colOffsetY = o.optDouble("colOffsetY", 0.0).toFloat(), colWidth = o.optDouble("colWidth", 0.0).toFloat(), colHeight = o.optDouble("colHeight", 0.0).toFloat(), colLayer = o.optInt("colLayer", 1), colMask = o.optInt("colMask", 0xFFFF), bodyMode = o.optString("bodyMode", "kinematic"), gravity = o.optDouble("gravity", 2200.0).toFloat(), fallMultiplier = o.optDouble("fallMultiplier", 1.55).toFloat(), maxFallSpeed = o.optDouble("maxFallSpeed", 1450.0).toFloat(), bounciness = o.optDouble("bounciness", 0.5).toFloat(), drag = o.optDouble("drag", 0.0).toFloat(), maxJumps = o.optInt("maxJumps", 2), isHitbox = o.optBoolean("isHitbox", false), isHurtbox = o.optBoolean("isHurtbox", false), damage = o.optDouble("damage", 10.0).toFloat(), hFrames = o.optInt("hFrames", 1), vFrames = o.optInt("vFrames", 1), currentFrame = o.optInt("currentFrame", 0), animFps = o.optDouble("animFps", 12.0).toFloat(), clips = clipMap, tileSize = o.optDouble("tileSize", 90.0).toFloat(), tiles = tileList, tileLookup = tileMap, emitRate = o.optDouble("emitRate", 24.0).toFloat(), particleLife = o.optDouble("particleLife", 1.1).toFloat(), particleSpeed = o.optDouble("particleSpeed", 160.0).toFloat(), particleSize = o.optDouble("particleSize", 16.0).toFloat(), particleGravity = o.optDouble("particleGravity", -60.0).toFloat(), shader = o.optString("shader", ""), script = dataStore.resolveNodeScriptText(o.optString("script", ""), o.optString("scriptFile", "")))
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

    private fun toJsFloat(v: Any?, def: Float): Float = (v as? Number)?.toFloat() ?: def
    private fun toJsInt(v: Any?, def: Int): Int = (v as? Number)?.toInt() ?: def
    private fun toJsStr(v: Any?, def: String): String = v?.toString() ?: def
    private fun toJsBool(v: Any?, def: Boolean): Boolean = (v as? Boolean) ?: def

    companion object {
        private val nextNodeId = AtomicInteger(0)
        @Volatile private var qjsLibInited = false

        private val ZERO_QJS_BOOTSTRAP = """
            (function() {
              const BRIDGED = new Set([
                "name","tag","sceneGroup","persistent","x","y","z","vx","vy","vz","width","height","rotation",
                "tiltX","scaleX","scaleY","parallaxX","parallaxY","atlas","atlasFrame","normalMap","normalStrength",
                "specular","sliceLeft","sliceRight","sliceTop","sliceBottom","clipChildren","clipX","clipY","clipW",
                "clipH","slopeType","colOffsetX","colOffsetY","colWidth","colHeight","colLayer","colMask","bodyMode",
                "gravity","fallMultiplier","maxJumps","isGrounded","isHitbox","isHurtbox","damage","invulnTimer",
                "r","g","b","a","visible","text","currentClip","lightRadius","lightIntensity"
              ]);
              const READONLY = new Set(["name","sceneGroup","isGrounded","currentClip"]);
              const __zNodes = Object.create(null);
              const __zHooks = Object.create(null);
              const __zModules = Object.create(null);
              const __zTables = Object.create(null);
              const __zGlobals = Object.create(null);
              const __zEvents = Object.create(null);
              const __zAdCbs = Object.create(null);
              let __zAdCbSeq = 1;

              const nid = (n) => (n && typeof n === "object" && n.__zid) ? n.__zid : -1;
              const arrJson = (v) => JSON.stringify(Array.isArray(v) ? v.map(String) : (v != null ? [String(v)] : []));
              const fmtArgs = (a) => Array.from(a).map(x => (typeof x === "object" && x !== null) ? (() => { try { return JSON.stringify(x); } catch(_) { return String(x); } })() : String(x)).join(" ");

              globalThis.console = {
                log: (...a) => __zLog("[LOG] " + fmtArgs(a)),
                warn: (...a) => __zLog("[WARN] " + fmtArgs(a)),
                error: (...a) => __zLog("[ERR] " + fmtArgs(a))
              };

              globalThis.__zOnAdResult = function(cbId, ok) {
                const fn = __zAdCbs[cbId];
                delete __zAdCbs[cbId];
                if (typeof fn === "function") {
                  try { fn(!!ok); } catch (e) { __zLog("[ADS 回调错误] " + (e && e.message ? e.message : e)); }
                }
              };

              globalThis.ZeroAds = {
                isReady: (type) => !!__zAdReady(String(type || "reward")),
                showInterstitial: (cb) => {
                  let id = -1;
                  if (typeof cb === "function") { id = __zAdCbSeq++; __zAdCbs[id] = cb; }
                  __zAdShowInter(id);
                },
                showReward: (cb) => {
                  let id = -1;
                  if (typeof cb === "function") { id = __zAdCbSeq++; __zAdCbs[id] = cb; }
                  __zAdShowReward(id);
                },
                showRewarded: (cb) => globalThis.ZeroAds.showReward(cb)
              };

              const nodeProto = {
                createSkinnedMesh(c, r) { return __zNodeCall(this.__zid, "createSkinnedMesh", c, r); },
                bindBones(bones, smooth) { return __zNodeCall(this.__zid, "bindBones", arrJson(bones), smooth); },
                setMeshWave(ax, ay, freq, spd) { return __zNodeCall(this.__zid, "setMeshWave", ax, ay, freq, spd); },
                removeSkinnedMesh() { return __zNodeCall(this.__zid, "removeSkinnedMesh"); },
                attachCloth(cfg) { return __zNodeCall(this.__zid, "attachCloth", JSON.stringify(cfg || {})); },
                setNormalMap(p, s, sp) { return __zNodeCall(this.__zid, "setNormalMap", p, s, sp); },
                setAnimFloat(k, v) { return __zNodeCall(this.__zid, "setAnimFloat", k, v); },
                setAnimBool(k, v) { return __zNodeCall(this.__zid, "setAnimBool", k, v); },
                triggerAnim(k) { return __zNodeCall(this.__zid, "triggerAnim", k); },
                setAnimSpeed(s) { return __zNodeCall(this.__zid, "setAnimSpeed", s); },
                getAnimState() { return __zNodeCall(this.__zid, "getAnimState"); },
                isAnimCancelable() { return __zNodeCall(this.__zid, "isAnimCancelable"); },
                addAnimTransition(a,b,c,d,e,f,g,h) { return __zNodeCall(this.__zid, "addAnimTransition", a,b,c,d,e,f,g,h); },
                registerProClip(clip) { return __zNodeCall(this.__zid, "registerProClip", typeof clip === "string" ? clip : JSON.stringify(clip || {})); },
                setAtlas(a, f) { return __zNodeCall(this.__zid, "setAtlas", a, f); },
                setNineSlice(l, r, t, b) { return __zNodeCall(this.__zid, "setNineSlice", l, r, t, b); },
                setClipRect(x, y, w, h) { return __zNodeCall(this.__zid, "setClipRect", x, y, w, h); },
                jump(f) { return __zNodeCall(this.__zid, "jump", f); },
                dropThrough(d) { return __zNodeCall(this.__zid, "dropThrough", d); },
                bounce(vx, vy) { return __zNodeCall(this.__zid, "bounce", vx, vy); },
                play(c, f) { return __zNodeCall(this.__zid, "play", c, f); },
                playAnim(s, e, fps, loop) { return __zNodeCall(this.__zid, "playAnim", s, e, fps, loop); },
                stopAnim() { return __zNodeCall(this.__zid, "stopAnim"); },
                flash(d) { return __zNodeCall(this.__zid, "flash", d); },
                emitBurst(c) { return __zNodeCall(this.__zid, "emitBurst", c); }
              };

              function createNodeProxy(id) {
                if (__zNodes[id]) return __zNodes[id];
                const raw = Object.create(nodeProto);
                raw.__zid = id;
                const proxy = new Proxy(raw, {
                  get(target, prop, receiver) {
                    if (typeof prop === "string" && BRIDGED.has(prop)) return __zNodeGet(id, prop);
                    return Reflect.get(target, prop, receiver);
                  },
                  set(target, prop, value, receiver) {
                    if (typeof prop === "string" && BRIDGED.has(prop)) {
                      if (!READONLY.has(prop)) __zNodeSet(id, prop, value);
                      return true;
                    }
                    return Reflect.set(target, prop, value, receiver);
                  },
                  has(target, prop) {
                    return (typeof prop === "string" && BRIDGED.has(prop)) || Reflect.has(target, prop);
                  }
                });
                __zNodes[id] = proxy;
                return proxy;
              }

              globalThis.require = function(rawPath) {
                const resJson = __zReadScript(String(rawPath || ""));
                if (!resJson) return null;
                const pair = JSON.parse(resJson);
                const normPath = pair[0], code = pair[1];
                if (Object.prototype.hasOwnProperty.call(__zModules, normPath)) return __zModules[normPath];
                const mod = { exports: {} };
                __zModules[normPath] = mod.exports;
                try {
                  const fn = new Function("module", "exports", "require", code);
                  fn(mod, mod.exports, globalThis.require);
                  __zModules[normPath] = mod.exports;
                  return mod.exports;
                } catch (e) {
                  delete __zModules[normPath];
                  __zLog("[MODULE 错误 @ " + normPath + "] " + (e && e.message ? e.message : e));
                  return null;
                }
              };

              globalThis.Engine = {
                log: (...a) => __zLog("[LOG] " + fmtArgs(a)),
                unloadUnusedTextures: () => __zEngineCall("unloadUnusedTextures"),
                clearModuleCache: () => { for (const k in __zModules) delete __zModules[k]; },
                setSdfShadow: (e, s, m) => __zEngineCall("setSdfShadow", e, s, m),
                setGI: (cfg) => __zEngineCall("setGI", typeof cfg === "boolean" ? String(cfg) : JSON.stringify(cfg || {})),
                createCloth: (cfg) => __zEngineCall("createCloth", JSON.stringify(cfg || {})),
                removeCloth: (id) => __zEngineCall("removeCloth", id),
                setGlobalWind: (wx, wy) => __zEngineCall("setGlobalWind", wx, wy),
                solveIK: (a,b,c,d,e,f,g) => __zEngineCall("solveIK", a,b,c,d,e,f,g),
                registerIK: (a,b,c,d,e,f) => __zEngineCall("registerIK", a,b,c,d,e,f),
                setIKTarget: (a,b,c,d) => __zEngineCall("setIKTarget", a,b,c,d),
                removeIK: (id) => __zEngineCall("removeIK", id),
                createSkinnedMesh: (n, c, r) => __zEngineCall("createSkinnedMesh", n, c, r),
                bindBones: (n, b, s) => __zEngineCall("bindBones", n, arrJson(b), s),
                setMeshWave: (n, ax, ay, f, s) => __zEngineCall("setMeshWave", n, ax, ay, f, s),
                removeSkinnedMesh: (n) => __zEngineCall("removeSkinnedMesh", n),
                loadAtlas: (n) => { const s = __zEngineCall("loadAtlas", n); return s ? JSON.parse(s) : null; },
                setFlag: (k, v) => __zEngineCall("setFlag", k, arguments.length >= 2 ? v : true),
                getFlag: (k, def) => __zEngineCall("getFlag", k, def),
                hasFlag: (k) => __zEngineCall("hasFlag", k),
                clearFlag: (k) => __zEngineCall("clearFlag", k),
                clearAllFlags: () => __zEngineCall("clearAllFlags"),
                getAllFlags: () => JSON.parse(__zEngineCall("getAllFlags") || "{}"),
                saveWorldFlags: (s) => __zEngineCall("saveWorldFlags", s),
                loadWorldFlags: (s) => __zEngineCall("loadWorldFlags", s),
                loadTable: (name) => {
                  const key = String(name || "");
                  if (Object.prototype.hasOwnProperty.call(__zTables, key)) return __zTables[key];
                  const raw = __zEngineCall("readTable", key);
                  if (!raw) return null;
                  try { const p = JSON.parse(raw); __zTables[key] = p; return p; }
                  catch (e) { __zLog("[DATA 解析错误 @ " + key + "] " + e.message); return null; }
                },
                reloadTables: () => { for (const k in __zTables) delete __zTables[k]; },
                setLanguage: (l) => __zEngineCall("setLanguage", l),
                getLanguage: () => __zEngineCall("getLanguage"),
                setI18nDict: (l, obj) => __zEngineCall("setI18nDict", l, JSON.stringify(obj || {})),
                t: (k, fb) => __zEngineCall("t", k, fb != null ? String(fb) : ""),
                setDebugDraw: (e) => __zEngineCall("setDebugDraw", !!e),
                isDebugDraw: () => __zEngineCall("isDebugDraw"),
                setPostFX: (cfg) => __zEngineCall("setPostFX", JSON.stringify(cfg || {})),
                setFade: (tg, d) => __zEngineCall("setFade", tg, d),
                fadeToScene: (s, d) => __zEngineCall("fadeToScene", s, d),
                preloadScene: (s) => __zEngineCall("preloadScene", s),
                loadSceneAdditive: (s, ox, oy) => __zEngineCall("loadSceneAdditive", s, ox, oy),
                unloadScene: (s) => __zEngineCall("unloadScene", s),
                clearSceneCache: () => __zEngineCall("clearSceneCache"),
                getStats: () => {
                  const s = JSON.parse(__zEngineCall("getStats") || "{}");
                  s.modules = Object.keys(__zModules).length;
                  s.tables = Object.keys(__zTables).length;
                  return s;
                },
                loadScene: (s) => __zEngineCall("loadScene", s),
                setGlobal: (k, v) => { if (k) __zGlobals[String(k)] = v; },
                getGlobal: (k, def) => Object.prototype.hasOwnProperty.call(__zGlobals, String(k)) ? __zGlobals[String(k)] : def,
                saveDisk: (slot, data) => __zEngineCall("saveDisk", slot, typeof data === "string" ? data : JSON.stringify(data == null ? {} : data)),
                loadDisk: (slot) => { const r = __zEngineCall("loadDisk", slot); return r ? JSON.parse(r) : null; },
                deleteDisk: (slot) => __zEngineCall("deleteDisk", slot),
                raycast: (x1, y1, x2, y2, tag, mask) => {
                  const r = JSON.parse(__zEngineCall("raycast", x1, y1, x2, y2, tag || "", mask == null ? 0xFFFF : mask));
                  return { hit: r.hit, x: r.x, y: r.y, distance: r.distance, node: r.nodeId > 0 ? (__zNodes[r.nodeId] || null) : null };
                },
                hitstop: (d) => __zEngineCall("hitstop", d),
                setTimeScale: (s) => __zEngineCall("setTimeScale", s),
                setCameraMode: (m) => __zEngineCall("setCameraMode", m),
                setCameraTilt: (p, d, f) => __zEngineCall("setCameraTilt", p, d, f),
                setCameraTarget: (n) => __zEngineCall("setCameraTarget", nid(n)),
                setCameraDeadzone: (x, y) => __zEngineCall("setCameraDeadzone", x, y),
                setCameraLookAhead: (x, y) => __zEngineCall("setCameraLookAhead", x, y),
                setCameraBounds: (a,b,c,d) => __zEngineCall("setCameraBounds", a,b,c,d),
                clearCameraBounds: () => __zEngineCall("clearCameraBounds"),
                getCamera: () => JSON.parse(__zEngineCall("getCamera") || "{}"),
                setAmbientLight: (r, g, b) => __zEngineCall("setAmbientLight", r, g, b),
                shake: (i, d) => __zEngineCall("shake", i, d),
                tween: (n, p, tv, d, e) => __zEngineCall("tween", nid(n), p, tv, d, e),
                playTone: (f, d) => __zEngineCall("playTone", f, d),
                playBGM: (f, l, v) => __zEngineCall("playBGM", f, l, v),
                crossfadeBGM: (f, d, l, v) => __zEngineCall("crossfadeBGM", f, d, l, v),
                stopBGM: (d) => __zEngineCall("stopBGM", d),
                playSFX: (f, v, p1, p2) => __zEngineCall("playSFX", f, v, p1, p2),
                setVolume: (c, v) => __zEngineCall("setVolume", c, v),
                getVolume: (c) => __zEngineCall("getVolume", c),
                moveAndSlide: (n, dt) => __zEngineCall("moveAndSlide", nid(n), dt),
                distance: (a, b) => __zEngineCall("distance", nid(a), nid(b)),
                angleTo: (a, b) => __zEngineCall("angleTo", nid(a), nid(b)),
                spawn: (cfg, parent) => { const id = __zEngineCall("spawn", JSON.stringify(cfg || {}), parent || ""); return id > 0 ? createNodeProxy(id) : null; },
                instantiate: (s, x, y, z) => { const id = __zEngineCall("instantiate", s, x, y, z); return id > 0 ? createNodeProxy(id) : null; },
                destroy: (n) => __zEngineCall("destroy", nid(n)),
                findNode: (name) => { const id = __zEngineCall("findNode", name); return id > 0 ? (__zNodes[id] || null) : null; },
                findNodesByTag: (tag) => JSON.parse(__zEngineCall("findNodesByTag", tag) || "[]").map(id => __zNodes[id]).filter(Boolean),
                on: (ev, fn) => { if (ev && typeof fn === "function") (__zEvents[ev] || (__zEvents[ev] = [])).push(fn); },
                emit: (ev, data) => { const list = __zEvents[ev]; if (list) list.slice().forEach(fn => { try { fn(data); } catch(_) {} }); }
              };

              globalThis.Input = {
                get isTouching() { return __zInputGet("isTouching"); },
                get touchCount() { return __zInputGet("touchCount"); },
                get screenX() { return __zInputGet("screenX"); },
                get touchX() { return __zInputGet("touchX"); },
                get screenY() { return __zInputGet("screenY"); },
                get touchY() { return __zInputGet("touchY"); },
                get worldX() { return __zInputGet("worldX"); },
                get worldY() { return __zInputGet("worldY"); },
                isActionPressed: (a) => __zInputCall("isActionPressed", a),
                isActionJustPressed: (a) => __zInputCall("isActionJustPressed", a),
                isActionJustReleased: (a) => __zInputCall("isActionJustReleased", a),
                getAxis: (a, b) => __zInputCall("getAxis", a, b),
                bindAction: (a, keys) => __zInputCall("bindAction", a, arrJson(keys)),
                setVirtualAction: (a, v) => __zInputCall("setVirtualAction", a, !!v),
                bindTouchZone: (a, x, y, w, h) => __zInputCall("bindTouchZone", a, x, y, w, h),
                clearTouchZones: () => __zInputCall("clearTouchZones")
              };

              globalThis.__zBindNode = function(id, name, code) {
                const self = createNodeProxy(id);
                const hooks = {};
                if (code && code.trim().length > 0) {
                  try {
                    const wrapped = code + "\n;return {" +
                      "onReady: typeof onReady === 'function' ? onReady : null," +
                      "onProcess: typeof onProcess === 'function' ? onProcess : null," +
                      "onCollision: typeof onCollision === 'function' ? onCollision : null," +
                      "onTriggerEnter: typeof onTriggerEnter === 'function' ? onTriggerEnter : null," +
                      "onHit: typeof onHit === 'function' ? onHit : null," +
                      "onHurt: typeof onHurt === 'function' ? onHurt : null," +
                      "onInput: typeof onInput === 'function' ? onInput : null," +
                      "onAnimFinished: typeof onAnimFinished === 'function' ? onAnimFinished : null," +
                      "onFrameEvent: typeof onFrameEvent === 'function' ? onFrameEvent : null" +
                    "};";
                    const factory = new Function("self", wrapped);
                    const res = factory.call(self, self);
                    if (res) { for (const k in res) if (res[k]) hooks[k] = res[k]; }
                  } catch (e) {
                    __zLog("[JS 编译错误 @ " + name + "] " + (e && e.message ? e.message : e));
                  }
                }
                __zHooks[id] = hooks;
              };

              globalThis.__zDestroyNode = function(id) {
                delete __zHooks[id];
                delete __zNodes[id];
              };

              globalThis.__zCallHook = function(id, fnName, mode, a1, a2, a3, a4) {
                const h = __zHooks[id];
                if (!h) return;
                const fn = h[fnName];
                if (!fn) return;
                const self = __zNodes[id];
                try {
                  if (mode === "node") fn.call(self, __zNodes[a1] || null);
                  else if (mode === "hurt") fn.call(self, __zNodes[a1] || null, a2, a3, a4);
                  else if (mode === "two") fn.call(self, a1, a2);
                  else if (a1 !== undefined && a1 !== null) fn.call(self, a1);
                  else fn.call(self);
                } catch (e) {
                  __zLog("[JS 错误 @ " + (self ? self.name : id) + "." + fnName + "] " + (e && e.message ? e.message : e));
                }
              };

              globalThis.__zDispatchTouch = function(type, sx, sy, wx, wy) {
                const ev = { type, screenX: sx, screenY: sy, worldX: wx, worldY: wy };
                const ids = Object.keys(__zHooks);
                for (let i = 0; i < ids.length; i++) {
                  const id = ids[i], h = __zHooks[id];
                  if (h && h.onInput) {
                    const self = __zNodes[id];
                    try { h.onInput.call(self, ev); } catch (e) {
                      __zLog("[JS 错误 @ " + (self ? self.name : id) + ".onInput] " + (e && e.message ? e.message : e));
                    }
                  }
                }
              };
            })();
        """.trimIndent()
    }
}
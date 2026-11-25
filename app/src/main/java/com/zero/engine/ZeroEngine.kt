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
    internal val onConsoleLog: (String) -> Unit
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    data class AnimClip(val name: String, val start: Int, val end: Int, val fps: Float, val loop: Boolean, val events: Map<Int, String> = emptyMap())
    data class TileCell(val gx: Int, val gy: Int, val frame: Int)
    data class Particle(var active: Boolean = false, var x: Float = 0f, var y: Float = 0f, var vx: Float = 0f, var vy: Float = 0f, var size: Float = 12f, var life: Float = 0f, var maxLife: Float = 1f, var alpha: Float = 1f)
    data class TweenTask(val node: GameNode, val prop: String, val startVal: Float, val targetVal: Float, val duration: Float, val ease: String, var elapsed: Float = 0f)
    data class AdditiveLoadReq(val sceneFile: String, val offsetX: Float, val offsetY: Float)
    data class GlobalTransform(val gx: Float, val gy: Float, val gz: Float, val gRot: Float, val gSx: Float, val gSy: Float)
    data class ScriptInstanceCmd(
        val texture: String, val x: Float, val y: Float, val z: Float,
        val w: Float, val h: Float, val rotDeg: Float,
        val r: Float, val g: Float, val b: Float, val a: Float, val additive: Boolean
    )

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

    internal val audioSystem = ZeroAudioSystem(projectDir, onConsoleLog)
    internal val inputSystem = ZeroInputSystem()
    private val spriteBatch = ZeroSpriteBatch(1024)
    private val instancedBatch = ZeroInstancedBatch(4096)
    internal val dataStore = ZeroDataStore(projectDir, onConsoleLog)
    internal val physicsSystem = ZeroPhysicsSystem(onConsoleLog)
    internal val lightingSystem = ZeroLightingSystem(projectDir, onConsoleLog)
    internal val rigSystem = ZeroRigAndClothSystem(onConsoleLog)
    internal val meshSystem = ZeroMeshSkinningSystem(onConsoleLog)
    internal val giSystem = ZeroGlobalIlluminationSystem(onConsoleLog)
    private val scriptBridge = ZeroScriptBridge(this)

    internal val atlasCache = ConcurrentHashMap<String, TextureAtlasData>()
    internal val nodes = mutableListOf<GameNode>()
    internal val nodeById = HashMap<Int, GameNode>(256)
    internal val nodeByName = HashMap<String, GameNode>(256)
    internal val pendingAdd = mutableListOf<GameNode>()
    internal val pendingRemove = mutableSetOf<GameNode>()
    internal val activeTweens = mutableListOf<TweenTask>()
    internal val scriptInstances = mutableListOf<ScriptInstanceCmd>()
    internal val textureCache = ConcurrentHashMap<String, Int>()
    private val textureSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var useTransformCache = false
    private val renderTransformCache = HashMap<Int, GlobalTransform>(256)

    internal val preloadedSceneCache = ConcurrentHashMap<String, String>()
    private val sceneIoExecutor = Executors.newSingleThreadExecutor()
    @Volatile internal var pendingSceneLoad: String? = null
    internal val pendingAdditiveLoads = mutableListOf<AdditiveLoadReq>()
    internal val pendingUnloadScenes = mutableListOf<String>()

    internal var postFxEnabled = true; internal var fboReady = false
    private var sceneFbo = 0; private var sceneColorTex = 0; private var lightFbo = 0; private var lightMapTex = 0
    private var lightW = 540; private var lightH = 960
    private var lightProgram = 0; private var postProgram = 0; private var batchProgram = 0
    internal var postBloom = 0.25f; internal var postVignette = 0.28f; internal var postChromatic = 0.0f; internal var postFade = 0.0f
    internal var fadeTarget = 0.0f; internal var fadeSpeed = 0.0f; internal var pendingFadeScene: String? = null; internal var fadeSceneDur = 0.35f

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

    internal var camMode = "2.5D"; internal var camX = 540f; internal var camY = 960f; internal var camPitch = 48f; internal var camDistance = 1250f; internal var camFov = 45f
    internal var camTarget: GameNode? = null; private var camSmoothSpeed = 9f; internal var camDeadzoneX = 0f; internal var camDeadzoneY = 0f
    internal var camLookAheadX = 0f; internal var camLookAheadY = 0f
    internal var camBoundsEnabled = false; internal var camMinX = -99999f; internal var camMinY = -99999f; internal var camMaxX = 99999f; internal var camMaxY = 99999f
    internal var shakeIntensity = 0f; internal var shakeTimer = 0f
    private var viewMinX = -99999f; private var viewMaxX = 99999f; private var viewMinY = -99999f; private var viewMaxY = 99999f

    @Volatile internal var smoothedFps = 60f
    private var frameDrawCalls = 0
    @Volatile internal var lastFrameDrawCalls = 0
    private var frameCulled = 0
    @Volatile internal var lastFrameCulled = 0
    @Volatile internal var lastFrameLights = 0
    @Volatile internal var lastFrameInstances = 0
    @Volatile private var lastFrameNodes = 0
    @Volatile private var lastFrameTextures = 0

    internal var hitstopTimer = 0f; internal var timeScale = 1f
    internal var ambientR = 0.52f; internal var ambientG = 0.56f; internal var ambientB = 0.68f

    @Volatile internal var isTouching = false; @Volatile internal var touchScreenX = 0f; @Volatile internal var touchScreenY = 0f
    @Volatile internal var touchWorldX = 0f; @Volatile internal var touchWorldY = 0f
    private val pendingTouchEvents = mutableListOf<Triple<String, Float, Float>>()

    private val projMatrix = FloatArray(16); private val viewMatrix = FloatArray(16); private val vpMatrix = FloatArray(16); private val invVpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16); private val subModelMatrix = FloatArray(16); private val mvpMatrix = FloatArray(16); private val uiVpMatrix = FloatArray(16)

    private var defaultLitProgram = 0; private var whiteTexId = 0; private var engineTime = 0f; private var lastFrameNs = 0L; private var surfaceW = 1080; private var surfaceH = 1920

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
        setEGLContextClientVersion(3); setRenderer(this); renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun getPerfSummaryText(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val giLabel = when {
            giSystem.isJfaReady -> "RC+JFA"
            giSystem.isReady -> "ON"
            else -> "OFF"
        }
        return String.format(
            Locale.US,
            "FPS: %.1f | DC: %d | Inst: %d | 剔除: %d\n节点: %d | 光源: %d | 纹理: %d | 内存: %d/%dMB\nGL: ES3.0 | VM: QuickJS | GI: %s | 阴影: %s",
            smoothedFps, lastFrameDrawCalls, lastFrameInstances, lastFrameCulled,
            lastFrameNodes, lastFrameLights, lastFrameTextures, usedMb, maxMb,
            giLabel,
            if (lightingSystem.sdfShadowEnabled) "ON" else "OFF"
        )
    }

    internal fun findNodeByName(name: String): GameNode? {
        if (name.isEmpty()) return null
        val cached = nodeByName[name]
        if (cached != null && !pendingRemove.contains(cached)) return cached
        return nodes.find { it.name == name && !pendingRemove.contains(it) }
    }

    internal fun indexNode(n: GameNode) {
        nodeById[n.nodeId] = n
        nodeByName[n.name] = n
    }

    private fun unindexNode(n: GameNode) {
        nodeById.remove(n.nodeId)
        if (nodeByName[n.name] === n) nodeByName.remove(n.name)
    }

    internal fun normalizeSceneName(name: String): String = if (name.endsWith(".scene")) name else "$name.scene"
    internal fun queryAtlas(name: String): TextureAtlasData? =
        ZeroShaders.loadAtlasData(projectDir, name, atlasCache, textureSizes, { getImageTexture(it) }, onConsoleLog)

    fun releaseResources() {
        try { sceneIoExecutor.shutdownNow() } catch (_: Exception) {}
        inputSystem.clearAllPointers()
        queueEvent {
            scriptBridge.destroyVM()
            releaseFbos(); releaseSceneGpuResources(includePersistent = true)
            instancedBatch.releaseGL()
            lightingSystem.releaseGL(); rigSystem.releaseGL(); meshSystem.releaseGL(); giSystem.releaseGL()
            val texIds = (textureCache.values + textTextureCache.values).filter { it != 0 && it != whiteTexId }.toIntArray()
            if (texIds.isNotEmpty()) GLES20.glDeleteTextures(texIds.size, texIds, 0)
            textureCache.clear(); textureSizes.clear(); textTextureCache.clear()
        }
        audioSystem.release()
    }

    internal fun unloadUnusedTextures(): Int {
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
        instancedBatch.initGL(onConsoleLog)
        lightingSystem.initGL(); rigSystem.initGL(); meshSystem.initGL(); giSystem.initGL()
        ZeroAdBridge.init(context, onConsoleLog)
        scriptBridge.initVM(); dataStore.loadI18nFromDiskIfNeeded(dataStore.currentLang)
        loadSceneContent(sceneJson, groupName = "main", additive = false); lastFrameNs = System.nanoTime()
        onConsoleLog("[GPU] OpenGL ES 3.0 + GPU Instancing + JFA Radiance GI 已就绪")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = max(1, width); surfaceH = max(1, height); GLES20.glViewport(0, 0, surfaceW, surfaceH)
        Matrix.orthoM(uiVpMatrix, 0, 0f, 1080f, 1920f, 0f, -100f, 100f)
        recreateFbos(surfaceW, surfaceH); giSystem.resize(surfaceW, surfaceH)
    }

    internal fun readSceneText(sceneFile: String): String? {
        val norm = normalizeSceneName(sceneFile)
        preloadedSceneCache[norm]?.let { return it }
        val file = File(File(projectDir, "scenes"), norm)
        return if (file.exists()) file.readText().also { preloadedSceneCache[norm] = it } else null
    }

    internal fun preloadSceneAsync(sceneFile: String) {
        val norm = normalizeSceneName(sceneFile)
        if (preloadedSceneCache.containsKey(norm)) return
        sceneIoExecutor.execute {
            try { File(File(projectDir, "scenes"), norm).takeIf { it.exists() }?.let { preloadedSceneCache[norm] = it.readText() } } catch (_: Exception) {}
        }
    }

    private fun loadSceneContent(rawJson: String, groupName: String, additive: Boolean, offsetX: Float = 0f, offsetY: Float = 0f) {
        if (!additive) {
            releaseSceneGpuResources(includePersistent = false)
            nodes.filter { !it.persistent }.forEach { unindexNode(it); scriptBridge.destroyNode(it.nodeId) }
            val kept = nodes.filter { it.persistent }
            nodes.clear(); nodes.addAll(kept)
            nodeById.clear(); nodeByName.clear(); kept.forEach { indexNode(it) }
            pendingAdd.removeAll { !it.persistent }; pendingRemove.clear(); scriptInstances.clear()
            activeTweens.removeAll { !it.node.persistent }; physicsSystem.clearRays(); rigSystem.clearAll(); meshSystem.clearAll()
            if (camTarget?.persistent != true) camTarget = null; camBoundsEnabled = false
        }
        val newNodes = mutableListOf<GameNode>()
        ZeroNodeFactory.parseSceneJson(
            raw = rawJson,
            outList = newNodes,
            dataStore = dataStore,
            physicsSystem = physicsSystem,
            onCameraParsed = if (!additive) { m, p, d -> camMode = m; camPitch = p; camDistance = d } else null,
            groupName = groupName,
            onConsoleLog = onConsoleLog
        )
        if (additive && (offsetX != 0f || offsetY != 0f)) {
            newNodes.forEach { if (it.parent.isEmpty() && it.layer == "World") { it.x += offsetX; it.y += offsetY } }
        }
        nodes.addAll(newNodes)
        newNodes.forEach { indexNode(it) }
        if (!additive) unloadUnusedTextures()
        newNodes.forEach { scriptBridge.bindNodeScript(it) }
        physicsSystem.rebuildSpatialIndex(nodes, pendingRemove) { getGlobalTransform(it) }
        newNodes.forEach { scriptBridge.callLifecycle(it, "onReady") }
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

        scriptInstances.clear()
        val dt = rawDt * timeScale; engineTime += dt; physicsSystem.updateRays(rawDt)
        updateCameraMatrices(dt); updateWorldTouchFromScreen(); updateTweens(dt); flushTouchEvents()

        if (pendingAdd.isNotEmpty()) {
            val added = pendingAdd.toList(); pendingAdd.clear(); nodes.addAll(added)
            added.forEach { indexNode(it) }
            added.forEach { scriptBridge.bindNodeScript(it); scriptBridge.callLifecycle(it, "onReady") }
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
                scriptBridge.callLifecycle(n, "onProcess", dt.toDouble())
            }
        }
        rigSystem.update(dt, nodes) { getGlobalTransform(it) }
        meshSystem.update(dt, nodes) { getGlobalTransform(it) }

        physicsSystem.rebuildSpatialIndex(nodes, pendingRemove) { getGlobalTransform(it) }
        physicsSystem.processCombatOverlaps(
            nodes = nodes,
            pendingRemove = pendingRemove,
            getGlobalTransform = { getGlobalTransform(it) },
            onHit = { attacker, hurt -> scriptBridge.callNodeEvent(attacker, "onHit", hurt) },
            onHurt = { hurt, attacker, dmg, kbX, kbY -> scriptBridge.callHurtCallback(hurt, attacker, dmg, kbX, kbY) },
            addHitstop = { hs -> hitstopTimer = max(hitstopTimer, hs.coerceIn(0f, 0.35f)) }
        )
        if (pendingRemove.isNotEmpty()) {
            pendingRemove.forEach {
                releaseNodeGpuResources(it); meshSystem.removeMesh(it.name)
                unindexNode(it); scriptBridge.destroyNode(it.nodeId)
            }
            nodes.removeAll(pendingRemove); pendingRemove.clear()
        }
        renderSceneFrame()
    }

    private fun updateProAnimator(n: GameNode, dt: Float) {
        n.animator.update(dt, { clip, ev -> scriptBridge.callFrameEvent(n, clip, ev) }, { clip -> scriptBridge.callLifecycle(n, "onAnimFinished", clip) })
        n.animator.activeFrame?.let { af -> n.currentFrame = af.frameIndex; if (af.atlasFrame.isNotEmpty()) n.atlasFrame = af.atlasFrame }
        n.animator.activeBonePoses.forEach { pose ->
            val target = findNodeByName(pose.targetNodeName)
            target?.let { t -> pose.x?.let { t.x = it }; pose.y?.let { t.y = it }; pose.rotation?.let { t.rotation = it }; pose.scaleX?.let { t.scaleX = it }; pose.scaleY?.let { t.scaleY = it } }
        }
    }

    private fun isBoxInView(minX: Float, maxX: Float, minY: Float, maxY: Float): Boolean = maxX >= viewMinX && minX <= viewMaxX && maxY >= viewMinY && minY <= viewMaxY

    private fun resolveUiClipRect(n: GameNode): FloatArray? {
        var left = Float.NEGATIVE_INFINITY; var top = Float.NEGATIVE_INFINITY; var right = Float.POSITIVE_INFINITY; var bottom = Float.POSITIVE_INFINITY; var hasClip = false
        if (n.clipW > 0f && n.clipH > 0f) { left = max(left, n.clipX); top = max(top, n.clipY); right = min(right, n.clipX + n.clipW); bottom = min(bottom, n.clipY + n.clipH); hasClip = true }
        var pName = n.parent; var guard = 0
        while (pName.isNotEmpty() && guard++ < 16) {
            val p = findNodeByName(pName) ?: break
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

    private fun renderInstancedWorldPass(particleNodes: List<GameNode>, fadeAlpha: Float) {
        if (fadeAlpha <= 0.001f || (particleNodes.isEmpty() && scriptInstances.isEmpty())) return
        if (!instancedBatch.isReady) {
            spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, 0, null, null) { frameDrawCalls++ }
            particleNodes.forEach { submitParticlesToBatch(it, true, fadeAlpha) }
            spriteBatch.end()
            return
        }
        val tilt = if (camMode == "2.5D") (-90f + camPitch * 0.65f) else 0f
        instancedBatch.begin(vpMatrix, tilt) { frameDrawCalls++ }

        particleNodes.forEach { n ->
            val gt = getGlobalTransform(n)
            val texId = if (n.texture.isNotEmpty()) getImageTexture(n.texture) else whiteTexId
            n.particles.forEach { p ->
                if (!p.active) return@forEach
                if (!isBoxInView(p.x - p.size, p.x + p.size, p.y - p.size, p.y + p.size)) { frameCulled++; return@forEach }
                val ratio = (p.life / p.maxLife).coerceIn(0f, 1f)
                val sz = p.size * (0.25f + 0.75f * ratio)
                instancedBatch.pushInstance(
                    texId = texId, x = p.x, y = p.y, z = gt.gz + 4f,
                    w = sz, h = sz, rotDeg = 0f,
                    r = n.r, g = n.g, b = n.b, a = p.alpha * ratio * fadeAlpha,
                    additive = true
                )
            }
        }

        scriptInstances.forEach { cmd ->
            val b = max(abs(cmd.w), abs(cmd.h)) * 0.75f
            if (!isBoxInView(cmd.x - b, cmd.x + b, cmd.y - b, cmd.y + b)) { frameCulled++; return@forEach }
            val texId = if (cmd.texture.isNotEmpty()) getImageTexture(cmd.texture) else whiteTexId
            instancedBatch.pushInstance(
                texId = texId, x = cmd.x, y = cmd.y, z = cmd.z,
                w = cmd.w, h = cmd.h, rotDeg = cmd.rotDeg,
                r = cmd.r, g = cmd.g, b = cmd.b, a = cmd.a * fadeAlpha,
                additive = cmd.additive
            )
        }

        instancedBatch.end()
    }

    private fun renderJfaOccludersPass(worldNodes: List<GameNode>, emptyArr: FloatArray) {
        if (!giSystem.isJfaReady) return
        val casters = worldNodes.filter { it.shadowCaster && it.nodeType != "ParticleEmitter" }
        if (casters.isEmpty()) return
        if (!giSystem.beginOccluderPass()) return

        spriteBatch.begin(vpMatrix, false, 1f, 1f, 1f, 0, null, null) { frameDrawCalls++ }
        casters.forEach { n ->
            when {
                n.nodeType == "TileMap" -> { spriteBatch.flush(); renderTileMap(n, vpMatrix, true, false, 0, emptyArr, emptyArr) }
                canBatchNode(n) -> submitNodeToBatch(n, true)
                else -> { spriteBatch.flush(); renderNodeSingle(n, vpMatrix, true, false, 0, emptyArr, emptyArr) }
            }
        }
        spriteBatch.end()
        giSystem.buildJfaDistanceField { frameDrawCalls++ }
    }

    private fun renderSceneFrame() {
        frameDrawCalls = 0; frameCulled = 0
        instancedBatch.resetFrameCounter()
        renderTransformCache.clear()
        useTransformCache = true

        try {
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
                spriteBatch.end()
                renderLightsToFbo(allLights)
                renderJfaOccludersPass(worldNodes, emptyArr)

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, surfaceW, surfaceH)
                GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); renderPostComposite()

                giSystem.renderAndComposite(sceneColorTex, lightMapTex, surfaceW, surfaceH, 1f - postFade, engineTime) { frameDrawCalls++ }

                if (normalNodes.isNotEmpty() && postFade < 0.99f) {
                    normalNodes.forEach { renderNodeSingle(it, vpMatrix, true, true, clampedLightCount, lightPosArr, lightColArr) }
                }
                meshSystem.renderSkinnedMeshes(vpMatrix, nodes, 1f - postFade, ambientR, ambientG, ambientB, clampedLightCount, lightPosArr, lightColArr, whiteTexId, { getImageTexture(it) }, { getGlobalTransform(it) }) { frameDrawCalls++ }
                rigSystem.renderCloths(vpMatrix, nodes, 1f - postFade, whiteTexId, { getImageTexture(it) }, { getGlobalTransform(it) }) { frameDrawCalls++ }
                renderInstancedWorldPass(particleNodes, 1f - postFade)
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, surfaceW, surfaceH)
                GLES20.glClearColor(0.04f, 0.05f, 0.08f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                spriteBatch.begin(vpMatrix, false, ambientR, ambientG, ambientB, clampedLightCount, lightPosArr, lightColArr) { frameDrawCalls++ }
                worldNodes.forEach { n ->
                    if (meshSystem.hasMesh(n.name) || n.nodeType == "ParticleEmitter") return@forEach
                    when {
                        n.nodeType == "TileMap" -> { spriteBatch.flush(); renderTileMap(n, vpMatrix, true, true, clampedLightCount, lightPosArr, lightColArr) }
                        canBatchNode(n) -> { spriteBatch.setInlineLight(true); submitNodeToBatch(n, true) }
                        else -> { spriteBatch.flush(); renderNodeSingle(n, vpMatrix, true, true, clampedLightCount, lightPosArr, lightColArr) }
                    }
                }
                spriteBatch.end()
                meshSystem.renderSkinnedMeshes(vpMatrix, nodes, 1f, ambientR, ambientG, ambientB, clampedLightCount, lightPosArr, lightColArr, whiteTexId, { getImageTexture(it) }, { getGlobalTransform(it) }) { frameDrawCalls++ }
                rigSystem.renderCloths(vpMatrix, nodes, 1f, whiteTexId, { getImageTexture(it) }, { getGlobalTransform(it) }) { frameDrawCalls++ }
                renderInstancedWorldPass(particleNodes, 1f)
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
        } finally {
            useTransformCache = false
        }
        lastFrameDrawCalls = frameDrawCalls; lastFrameCulled = frameCulled
        lastFrameInstances = instancedBatch.frameInstanceCount
        lastFrameNodes = nodes.size; lastFrameTextures = textureCache.size
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
        for (r in 0..2) {
            val sh = ys[r + 1] - ys[r]; if (sh <= 0.01f) continue; val scy = (ys[r] + ys[r + 1]) * 0.5f
            for (c in 0..2) {
                val sw = xs[c + 1] - xs[c]; if (sw <= 0.01f) continue; val scx = (xs[c] + xs[c + 1]) * 0.5f
                System.arraycopy(modelMatrix, 0, subModelMatrix, 0, 16); Matrix.translateM(subModelMatrix, 0, scx * signX, scy * signY, 0f); Matrix.scaleM(subModelMatrix, 0, sw * signX, sh * signY, 1f)
                spriteBatch.drawQuad(texId, subModelMatrix, us[c], vs[r], us[c + 1], vs[r + 1], n.r, n.g, n.b, n.a, flashVal, false)
            }
        }
    }

    private fun submitParticlesToBatch(n: GameNode, isWorld: Boolean, fadeAlpha: Float) {
        if (fadeAlpha <= 0.001f) return; val gt = getGlobalTransform(n); val texId = if (n.texture.isNotEmpty()) getImageTexture(n.texture) else whiteTexId
        n.particles.forEach { p ->
            if (!p.active) return@forEach
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
        lights.forEach { l ->
            val gt = getGlobalTransform(l); val rad = max(8f, l.lightRadius)
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
                n.tiles.forEach { c ->
                    val x0 = gt.gx + c.gx * ts; val y0 = gt.gy + c.gy * ts
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
        zones.forEach { z ->
            val act = inputSystem.isActionPressed(z.action); val hw = z.w * 0.5f; val hh = z.h * 0.5f
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

    internal fun spawnSingleParticle(n: GameNode, gx: Float, gy: Float) {
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

    internal fun readTweenProperty(n: GameNode, prop: String): Float = when (prop) {
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
            n.clips[n.currentClipName]?.events?.get(n.currentFrame)?.takeIf { it.isNotEmpty() }?.let { scriptBridge.callFrameEvent(n, n.currentClipName, it) }
            if (n.currentFrame > n.animEnd) {
                if (n.animLoop) n.currentFrame = n.animStart
                else { n.currentFrame = n.animEnd; n.animPlaying = false; val fc = n.currentClipName; n.currentClipName = ""; if (fc.isNotEmpty()) scriptBridge.callLifecycle(n, "onAnimFinished", fc) }
            }
        }
    }

    internal fun resolveNodeUvRect(n: GameNode): FloatArray {
        val atlasData = if (n.atlas.isNotEmpty()) queryAtlas(n.atlas) else null
        val af = atlasData?.frames?.get(n.atlasFrame)
        if (atlasData != null && af != null && af.w > 0f && af.h > 0f) {
            val u0 = af.x / atlasData.atlasW; val v0 = af.y / atlasData.atlasH
            return floatArrayOf(u0, v0, u0 + af.w / atlasData.atlasW, v0 + af.h / atlasData.atlasH)
        }
        val hf = max(1, n.hFrames); val vf = max(1, n.vFrames); val fi = n.currentFrame.coerceIn(0, hf * vf - 1)
        val uS = 1f / hf; val vS = 1f / vf; val u0 = (fi % hf) * uS; val v0 = (fi / hf) * vS
        return floatArrayOf(u0, v0, u0 + uS, v0 + vS)
    }

    internal fun getGlobalTransform(n: GameNode, visited: MutableSet<String> = mutableSetOf()): GlobalTransform {
        if (useTransformCache) renderTransformCache[n.nodeId]?.let { return it }
        val px = if (n.layer == "World" && n.parallaxX != 1f) (camX - 540f) * (1f - n.parallaxX) else 0f
        val py = if (n.layer == "World" && n.parallaxY != 1f) (camY - 960f) * (1f - n.parallaxY) else 0f
        val res = if (n.parent.isEmpty() || visited.contains(n.name)) {
            GlobalTransform(n.x + px, n.y + py, n.z, n.rotation, n.scaleX, n.scaleY)
        } else {
            visited.add(n.name)
            val p = findNodeByName(n.parent)
            if (p == null) {
                GlobalTransform(n.x + px, n.y + py, n.z, n.rotation, n.scaleX, n.scaleY)
            } else {
                val pt = getGlobalTransform(p, visited)
                val rad = Math.toRadians(pt.gRot.toDouble())
                val sx = n.x * pt.gSx; val sy = n.y * pt.gSy
                GlobalTransform(
                    pt.gx + (sx * cos(rad) - sy * sin(rad)).toFloat(),
                    pt.gy + (sx * sin(rad) + sy * cos(rad)).toFloat(),
                    pt.gz + n.z, pt.gRot + n.rotation, pt.gSx * n.scaleX, pt.gSy * n.scaleY
                )
            }
        }
        if (useTransformCache) renderTransformCache[n.nodeId] = res
        return res
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
        list.forEach { (tp, sx, sy) -> scriptBridge.dispatchTouch(tp, sx, sy, touchWorldX, touchWorldY) }
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

    companion object {
        private val nextNodeId = AtomicInteger(0)
    }
}
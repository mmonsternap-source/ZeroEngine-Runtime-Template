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
        // 通用物理、平台与斜坡参数
        var collider: Boolean = false,
        var isTrigger: Boolean = false,
        var oneWay: Boolean = false,
        var slopeType: String = "none",     // none | left_up (/) | right_up (\)
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
        // TileMap 稀疏瓦片数据
        var tileSize: Float = 90f,
        val tiles: MutableList<TileCell> = mutableListOf(),
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

    private val nodes = mutableListOf<GameNode>()
    private val pendingAdd = mutableListOf<GameNode>()
    private val pendingRemove = mutableSetOf<GameNode>()
    private val activeTweens = mutableListOf<TweenTask>()
    private val textureCache = ConcurrentHashMap<String, Int>()
    private val textTextureCache = ConcurrentHashMap<String, Int>()
    private val eventListeners = mutableMapOf<String, MutableList<Function>>()

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

    // 2D / 2.5D 摄像机状态
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

    fun releaseResources() {
        try {
            bgmPlayer?.stop()
            bgmPlayer?.release()
            bgmPlayer = null
        } catch (_: Exception) {}
        try {
            soundPool.release()
        } catch (_: Exception) {}
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
        nodes.clear()
        pendingAdd.clear()
        pendingRemove.clear()
        activeTweens.clear()
        camTarget = null
        camBoundsEnabled = false
        parseSceneJson(rawJson, nodes)
        nodes.forEach { bindNodeScript(it) }
        nodes.toList().forEach { callLifecycle(it, "onReady") }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = max(1, width)
        surfaceH = max(1, height)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        Matrix.orthoM(uiVpMatrix, 0, 0f, 1080f, 1920f, 0f, -100f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val rawDt = ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
        lastFrameNs = now

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

        processCombatOverlaps()

        if (pendingRemove.isNotEmpty()) {
            nodes.removeAll(pendingRemove)
            pendingRemove.clear()
        }

        renderSceneFrame()
    }

    private fun renderSceneFrame() {
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
                renderDynamicShadow(caster, lights, vpMatrix)
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

        val uiNodes = nodes.filter { it.visible && it.layer == "UI" }.sortedBy { it.zIndex }
        for (n in uiNodes) {
            if (n.nodeType == "ParticleEmitter") {
                renderParticles(n, uiVpMatrix, isWorld = false)
            } else {
                renderNode(n, uiVpMatrix, isWorld = false, 0, lightPosArr, lightColArr)
            }
        }
    }

    private fun processCombatOverlaps() {
        val hitboxes = nodes.filter { it.visible && it.isHitbox && !pendingRemove.contains(it) }
        val hurtboxes = nodes.filter { it.visible && it.isHurtbox && !pendingRemove.contains(it) }
        if (hitboxes.isEmpty() || hurtboxes.isEmpty()) return

        for (hb in hitboxes) {
            for (hurt in hurtboxes) {
                if (hb === hurt || hb.parent == hurt.name || hurt.parent == hb.name) continue
                if (hurt.invulnTimer > 0f) continue
                if (hb.hitCooldownMap.containsKey(hurt.name)) continue
                if (checkOverlap(hb, hurt)) {
                    hb.hitCooldownMap[hurt.name] = 0.25f
                    syncToJs(hb)
                    syncToJs(hurt)
                    callLifecycle(hb, "onHit", hurt.jsObj)
                    val scope = hurt.jsObj?.prototype
                    val fn = if (scope != null) ScriptableObject.getProperty(scope, "onHurt") as? Function else null
                    if (fn != null && scope != null) {
                        try {
                            fn.call(rhino, scope, hurt.jsObj, arrayOf(hb.jsObj, hb.damage.toDouble()))
                            syncFromJs(hurt)
                        } catch (e: Exception) {
                            onConsoleLog("[JS 运行错误 @ ${hurt.name}.onHurt] ${e.message}")
                        }
                    }
                }
            }
        }
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
        val gt = getGlobalTransform(n)
        val ts = max(8f, n.tileSize)
        val prog = getProgramForNode(n)
        GLES20.glUseProgram(prog)

        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, quadBuffer)
        quadBuffer.position(3)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, quadBuffer)

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

        val hf = max(1, n.hFrames)
        val vf = max(1, n.vFrames)
        val uScale = 1f / hf
        val vScale = 1f / vf

        for (cell in n.tiles) {
            val wx = gt.gx + (cell.gx + 0.5f) * ts
            val wy = gt.gy + (cell.gy + 0.5f) * ts
            val fi = cell.frame.coerceIn(0, hf * vf - 1)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), (fi % hf) * uScale, (fi / hf) * vScale, uScale, vScale)

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, wx, wy, gt.gz)
            Matrix.scaleM(modelMatrix, 0, ts, ts, 1f)
            Matrix.multiplyMM(mvpMatrix, 0, activeVp, 0, modelMatrix, 0)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, modelMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
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
        }
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
        Matrix.invertM(invVpMatrix, 0, vpMatrix, 0)
    }

    private fun updateWorldTouchFromScreen() {
        val ndcX = (touchScreenX / 1080f) * 2f - 1f
        val ndcY = 1f - (touchScreenY / 1920f) * 2f
        val nearPt = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val farPt = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)

        Matrix.multiplyMV(nearWorld, 0, invVpMatrix, 0, nearPt, 0)
        Matrix.multiplyMV(farWorld, 0, invVpMatrix, 0, farPt, 0)
        if (abs(nearWorld[3]) > 1e-5f) {
            for (i in 0..2) nearWorld[i] /= nearWorld[3]
        }
        if (abs(farWorld[3]) > 1e-5f) {
            for (i in 0..2) farWorld[i] /= farWorld[3]
        }
        val dz = farWorld[2] - nearWorld[2]
        if (abs(dz) > 1e-5f) {
            val t = -nearWorld[2] / dz
            touchWorldX = nearWorld[0] + t * (farWorld[0] - nearWorld[0])
            touchWorldY = nearWorld[1] + t * (farWorld[1] - nearWorld[1])
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
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uUvRect"), uOffset, vOffset, uScale, vScale)

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

    // ==================== 拼图 2：通用射线检测 (支持实体包围盒与 TileMap) ====================
    private fun performRaycast(x1: Float, y1: Float, x2: Float, y2: Float, ignoreTag: String): Scriptable {
        var closestT = 1.0f
        var hitNode: GameNode? = null

        val dx = x2 - x1
        val dy = y2 - y1

        for (other in nodes) {
            if (!other.visible || !other.collider || other.isTrigger || other.layer != "World") continue
            if (ignoreTag.isNotEmpty() && other.tag == ignoreTag) continue

            val gb = getGlobalTransform(other)
            if (other.nodeType == "TileMap") {
                val ts = max(8f, other.tileSize)
                for (cell in other.tiles) {
                    val minX = gb.gx + cell.gx * ts
                    val maxX = minX + ts
                    val minY = gb.gy + cell.gy * ts
                    val maxY = minY + ts
                    val t = intersectSegmentAABB(x1, y1, dx, dy, minX, minY, maxX, maxY)
                    if (t != null && t < closestT) {
                        closestT = t
                        hitNode = other
                    }
                }
            } else {
                val hw = (other.width * abs(gb.gSx)) * 0.5f
                val hh = (other.height * abs(gb.gSy)) * 0.5f
                val t = intersectSegmentAABB(x1, y1, dx, dy, gb.gx - hw, gb.gy - hh, gb.gx + hw, gb.gy + hh)
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
            ScriptableObject.putProperty(res, "hit", true)
            ScriptableObject.putProperty(res, "x", hx.toDouble())
            ScriptableObject.putProperty(res, "y", hy.toDouble())
            ScriptableObject.putProperty(res, "distance", dist.toDouble())
            ScriptableObject.putProperty(res, "node", hitNode.jsObj)
        } else {
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

    // ==================== 拼图 3：本地磁盘永久存档 (长椅/多槽位存档) ====================
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
            NativeJSON.parse(rhino, globalScope, raw) { _, _, value -> value }
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
            performRaycast(x1, y1, x2, y2, ignoreTag)
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
            camTarget = nodes.find { it.jsObj === targetObj }
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
            val n = nodes.find { it.jsObj === targetObj } ?: return@jsFunc null
            syncFromJs(n)
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
            val n = nodes.find { it.jsObj === selfObj } ?: return@jsFunc null
            syncFromJs(n)
            moveAndSlideNode(n, dt)
            syncToJs(n)
            null
        })
        ScriptableObject.putProperty(engineApi, "distance", jsFunc { args ->
            val a = nodes.find { it.jsObj === (args.getOrNull(0) as? Scriptable) }
            val b = nodes.find { it.jsObj === (args.getOrNull(1) as? Scriptable) }
            if (a != null && b != null) {
                val ga = getGlobalTransform(a)
                val gb = getGlobalTransform(b)
                hypot(ga.gx - gb.gx, ga.gy - gb.gy).toDouble()
            } else 999999.0
        })
        ScriptableObject.putProperty(engineApi, "angleTo", jsFunc { args ->
            val a = nodes.find { it.jsObj === (args.getOrNull(0) as? Scriptable) }
            val b = nodes.find { it.jsObj === (args.getOrNull(1) as? Scriptable) }
            if (a != null && b != null) {
                val ga = getGlobalTransform(a)
                val gb = getGlobalTransform(b)
                Math.toDegrees(atan2((gb.gy - ga.gy).toDouble(), (gb.gx - ga.gx).toDouble()))
            } else 0.0
        })
        ScriptableObject.putProperty(engineApi, "spawn", jsFunc { args ->
            val cfg = args.getOrNull(0) as? Scriptable ?: return@jsFunc null
            val parentName = RhinoContext.toString(args.getOrNull(1) ?: "")
            val spawned = GameNode(
                name = getJsStr(cfg, "name", "Spawn_${System.nanoTime() % 10000}"),
                nodeType = getJsStr(cfg, "nodeType", "Sprite2D"),
                layer = getJsStr(cfg, "layer", "World"),
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
            nodes.find { it.jsObj === targetObj }?.let { pendingRemove.add(it) }
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
        val selfObj = rhino.newObject(nodeScope)
        n.jsObj = selfObj
        syncToJs(n)

        ScriptableObject.putProperty(selfObj, "jump", jsFunc { args ->
            val jumpForce = (args.getOrNull(0) as? Number)?.toFloat() ?: 920f
            syncFromJs(n)
            if (n.isGrounded || n.coyoteTimer > 0f) {
                n.vy = -abs(jumpForce)
                n.isGrounded = false
                n.coyoteTimer = 0f
                n.jumpCount = 1
                syncToJs(n)
                return@jsFunc true
            } else if (n.jumpCount < n.maxJumps) {
                n.vy = -abs(jumpForce) * 0.9f
                n.jumpCount++
                syncToJs(n)
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
            syncFromJs(n)
            if (bvx != null) n.vx = bvx
            n.vy = bvy
            n.isGrounded = false
            n.jumpCount = 1
            syncToJs(n)
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
        n.jsObj?.prototype = nodeScope
    }

    private fun callLifecycle(n: GameNode, fnName: String, arg: Any? = null) {
        val scope = n.jsObj?.prototype ?: return
        val fn = ScriptableObject.getProperty(scope, fnName) as? Function ?: return
        syncToJs(n)
        try {
            val args = if (arg != null) arrayOf(arg) else emptyArray()
            fn.call(rhino, scope, n.jsObj, args)
            syncFromJs(n)
        } catch (e: Exception) {
            onConsoleLog("[JS 运行错误 @ ${n.name}.$fnName] ${e.message}")
        }
    }

    private fun syncToJs(n: GameNode) {
        val o = n.jsObj ?: return
        ScriptableObject.putProperty(o, "name", n.name)
        ScriptableObject.putProperty(o, "tag", n.tag)
        ScriptableObject.putProperty(o, "x", n.x.toDouble())
        ScriptableObject.putProperty(o, "y", n.y.toDouble())
        ScriptableObject.putProperty(o, "z", n.z.toDouble())
        ScriptableObject.putProperty(o, "vx", n.vx.toDouble())
        ScriptableObject.putProperty(o, "vy", n.vy.toDouble())
        ScriptableObject.putProperty(o, "vz", n.vz.toDouble())
        ScriptableObject.putProperty(o, "width", n.width.toDouble())
        ScriptableObject.putProperty(o, "height", n.height.toDouble())
        ScriptableObject.putProperty(o, "rotation", n.rotation.toDouble())
        ScriptableObject.putProperty(o, "tiltX", n.tiltX.toDouble())
        ScriptableObject.putProperty(o, "scaleX", n.scaleX.toDouble())
        ScriptableObject.putProperty(o, "scaleY", n.scaleY.toDouble())
        ScriptableObject.putProperty(o, "parallaxX", n.parallaxX.toDouble())
        ScriptableObject.putProperty(o, "parallaxY", n.parallaxY.toDouble())
        ScriptableObject.putProperty(o, "slopeType", n.slopeType)
        ScriptableObject.putProperty(o, "bodyMode", n.bodyMode)
        ScriptableObject.putProperty(o, "gravity", n.gravity.toDouble())
        ScriptableObject.putProperty(o, "fallMultiplier", n.fallMultiplier.toDouble())
        ScriptableObject.putProperty(o, "maxJumps", n.maxJumps)
        ScriptableObject.putProperty(o, "isGrounded", n.isGrounded)
        ScriptableObject.putProperty(o, "isHitbox", n.isHitbox)
        ScriptableObject.putProperty(o, "isHurtbox", n.isHurtbox)
        ScriptableObject.putProperty(o, "damage", n.damage.toDouble())
        ScriptableObject.putProperty(o, "invulnTimer", n.invulnTimer.toDouble())
        ScriptableObject.putProperty(o, "r", n.r.toDouble())
        ScriptableObject.putProperty(o, "g", n.g.toDouble())
        ScriptableObject.putProperty(o, "b", n.b.toDouble())
        ScriptableObject.putProperty(o, "a", n.a.toDouble())
        ScriptableObject.putProperty(o, "visible", n.visible)
        ScriptableObject.putProperty(o, "text", n.text)
        ScriptableObject.putProperty(o, "currentClip", n.currentClipName)
        ScriptableObject.putProperty(o, "lightRadius", n.lightRadius.toDouble())
        ScriptableObject.putProperty(o, "lightIntensity", n.lightIntensity.toDouble())
    }

    private fun syncFromJs(n: GameNode) {
        val o = n.jsObj ?: return
        n.x = getJsFloat(o, "x", n.x)
        n.y = getJsFloat(o, "y", n.y)
        n.z = getJsFloat(o, "z", n.z)
        n.vx = getJsFloat(o, "vx", n.vx)
        n.vy = getJsFloat(o, "vy", n.vy)
        n.vz = getJsFloat(o, "vz", n.vz)
        n.width = getJsFloat(o, "width", n.width)
        n.height = getJsFloat(o, "height", n.height)
        n.rotation = getJsFloat(o, "rotation", n.rotation)
        n.tiltX = getJsFloat(o, "tiltX", n.tiltX)
        n.scaleX = getJsFloat(o, "scaleX", n.scaleX)
        n.scaleY = getJsFloat(o, "scaleY", n.scaleY)
        n.parallaxX = getJsFloat(o, "parallaxX", n.parallaxX)
        n.parallaxY = getJsFloat(o, "parallaxY", n.parallaxY)
        n.slopeType = getJsStr(o, "slopeType", n.slopeType)
        n.bodyMode = getJsStr(o, "bodyMode", n.bodyMode)
        n.gravity = getJsFloat(o, "gravity", n.gravity)
        n.fallMultiplier = getJsFloat(o, "fallMultiplier", n.fallMultiplier)
        n.maxJumps = getJsInt(o, "maxJumps", n.maxJumps)
        n.isHitbox = getJsBool(o, "isHitbox", n.isHitbox)
        n.isHurtbox = getJsBool(o, "isHurtbox", n.isHurtbox)
        n.damage = getJsFloat(o, "damage", n.damage)
        n.invulnTimer = getJsFloat(o, "invulnTimer", n.invulnTimer)
        n.r = getJsFloat(o, "r", n.r)
        n.g = getJsFloat(o, "g", n.g)
        n.b = getJsFloat(o, "b", n.b)
        n.a = getJsFloat(o, "a", n.a)
        n.visible = getJsBool(o, "visible", n.visible)
        n.text = getJsStr(o, "text", n.text)
        n.lightRadius = getJsFloat(o, "lightRadius", n.lightRadius)
        n.lightIntensity = getJsFloat(o, "lightIntensity", n.lightIntensity)
    }

    // ==================== 拼图 1：含斜坡自动爬升与贴地的 moveAndSlide ====================
    private fun computeSlopeSurfaceY(slope: GameNode, footX: Float): Float {
        val gb = getGlobalTransform(slope)
        val sw = slope.width * abs(gb.gSx)
        val sh = slope.height * abs(gb.gSy)
        val left = gb.gx - sw * 0.5f
        val top = gb.gy - sh * 0.5f
        val bottom = gb.gy + sh * 0.5f
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

        val halfH = (n.height * abs(n.scaleY)) * 0.5f
        val prevBottom = n.y + halfH

        // 1. X 轴位移与斜坡平滑爬升
        n.x += n.vx * dt
        var climbedSlope = false
        if (n.collider && !n.isTrigger) {
            for (other in nodes) {
                if (other !== n && other.visible && other.collider && !other.isTrigger && !other.oneWay && other.layer == n.layer) {
                    if (other.slopeType == "left_up" || other.slopeType == "right_up") {
                        if (checkOverlap(n, other)) {
                            val ga = getGlobalTransform(n)
                            val surfY = computeSlopeSurfaceY(other, ga.gx)
                            val footY = n.y + halfH
                            if (footY >= surfY - 18f && n.vy >= -40f) {
                                n.y = surfY - halfH
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
        }

        // 2. Y 轴位移与地面/斜坡/单向平台碰撞
        val wasGrounded = n.isGrounded
        if (!climbedSlope) n.isGrounded = false
        n.y += n.vy * dt
        if (n.collider && !n.isTrigger) {
            for (other in nodes) {
                if (other !== n && other.visible && other.collider && !other.isTrigger && other.layer == n.layer) {
                    if (other.slopeType == "left_up" || other.slopeType == "right_up") {
                        val ga = getGlobalTransform(n)
                        val surfY = computeSlopeSurfaceY(other, ga.gx)
                        val footY = n.y + halfH
                        if (checkOverlap(n, other) && footY >= surfY && n.vy >= 0f) {
                            n.y = surfY - halfH
                            n.vy = 0f
                            n.isGrounded = true
                            n.jumpCount = 0
                            n.coyoteTimer = n.coyoteTimeMax
                            collidedSolids.add(other)
                        } else if (wasGrounded && n.vy >= 0f && !n.isGrounded) {
                            // 下坡吸附：防止角色走下斜坡时一跳一顿
                            val gb = getGlobalTransform(other)
                            val sw = other.width * abs(gb.gSx)
                            if (abs(ga.gx - gb.gx) <= sw * 0.5f && surfY - footY in 0f..24f) {
                                n.y = surfY - halfH
                                n.vy = 0f
                                n.isGrounded = true
                                n.jumpCount = 0
                                n.coyoteTimer = n.coyoteTimeMax
                                collidedSolids.add(other)
                            }
                        }
                    } else if (other.oneWay) {
                        if (n.vy >= 0f && n.dropThroughTimer <= 0f) {
                            val gb = getGlobalTransform(other)
                            val otherTop = gb.gy - (other.height * abs(gb.gSy)) * 0.5f
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
            for (other in nodes.toList()) {
                if (other !== n && other.visible && other.collider && other.layer == n.layer) {
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
        val ga = getGlobalTransform(a)
        val gb = getGlobalTransform(b)
        val aw = a.width * abs(ga.gSx)
        val ah = a.height * abs(ga.gSy)

        if (b.nodeType == "TileMap") {
            val ts = max(8f, b.tileSize)
            for (cell in b.tiles) {
                val cx = gb.gx + (cell.gx + 0.5f) * ts
                val cy = gb.gy + (cell.gy + 0.5f) * ts
                if (abs(ga.gx - cx) < (aw + ts) * 0.5f && abs(ga.gy - cy) < (ah + ts) * 0.5f) {
                    return true
                }
            }
            return false
        }

        return abs(ga.gx - gb.gx) < (aw + b.width * abs(gb.gSx)) * 0.5f &&
               abs(ga.gy - gb.gy) < (ah + b.height * abs(gb.gSy)) * 0.5f
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
                val tilesArr = o.optJSONArray("tiles")
                if (tilesArr != null) {
                    for (tIdx in 0 until tilesArr.length()) {
                        val to = tilesArr.getJSONObject(tIdx)
                        tileList.add(
                            TileCell(
                                gx = to.optInt("gx", 0),
                                gy = to.optInt("gy", 0),
                                frame = to.optInt("frame", 0)
                            )
                        )
                    }
                }
                val defaultClipName = o.optString("defaultClip", "")
                val node = GameNode(
                    name = o.optString("name", "Node_$i"),
                    nodeType = o.optString("nodeType", "Sprite2D"),
                    layer = o.optString("layer", "World"),
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

    private fun getJsFloat(s: Scriptable, k: String, def: Float): Float =
        (ScriptableObject.getProperty(s, k) as? Number)?.toFloat() ?: def
    private fun getJsInt(s: Scriptable, k: String, def: Int): Int =
        (ScriptableObject.getProperty(s, k) as? Number)?.toInt() ?: def
    private fun getJsStr(s: Scriptable, k: String, def: String): String =
        ScriptableObject.getProperty(s, k)?.takeIf { it !== Scriptable.NOT_FOUND }?.toString() ?: def
    private fun getJsBool(s: Scriptable, k: String, def: Boolean): Boolean =
        (ScriptableObject.getProperty(s, k) as? Boolean) ?: def

    companion object {
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
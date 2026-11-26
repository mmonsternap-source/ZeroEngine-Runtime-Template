package com.zero.engine

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.SoundPool
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

/**
 * P20-B + P20-D 战斗表现与触觉反馈系统：
 * - P20-B: GLES 3.0 节点刀光/移动拖尾、冲刺残影（手动/自动）、世界空间伤害飘字
 * - P20-D: 2D 距离衰减与左右声道立体声空间音效 + Android 原生马达震动（无权限时自动降级触觉反馈）
 */
class ZeroCombatVfxSystem(
    private val onConsoleLog: (String) -> Unit
) {
    private data class TrailPoint(var x: Float, var y: Float, var z: Float, var age: Float)

    private class TrailStrip(
        val id: String,
        val nodeName: String,
        var offsetX: Float,
        var offsetY: Float,
        var width: Float,
        var maxLife: Float,
        var r: Float,
        var g: Float,
        var b: Float,
        var a: Float,
        var additive: Boolean,
        var enabled: Boolean,
        val points: ArrayDeque<TrailPoint> = ArrayDeque(MAX_TRAIL_POINTS + 2)
    )

    private data class Afterimage(
        val x: Float,
        val y: Float,
        val z: Float,
        val w: Float,
        val h: Float,
        val rotDeg: Float,
        val texture: String,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float,
        var age: Float,
        val maxAge: Float
    )

    private data class AutoGhostCfg(
        val nodeName: String,
        var interval: Float,
        var timer: Float,
        var duration: Float,
        var r: Float,
        var g: Float,
        var b: Float,
        var a: Float
    )

    data class FloatingText(
        var x: Float,
        var y: Float,
        var z: Float,
        val text: String,
        val colorInt: Int,
        val scale: Float,
        val crit: Boolean,
        var age: Float,
        val maxAge: Float,
        var vx: Float,
        var vy: Float
    )

    private val trails = ConcurrentHashMap<String, TrailStrip>()
    private val ghosts = mutableListOf<Afterimage>()
    private val autoGhosts = ConcurrentHashMap<String, AutoGhostCfg>()
    val floatingTexts = CopyOnWriteArrayList<FloatingText>()

    // 供原生 HUD Canvas 投影世界坐标使用
    val latestVpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    @Volatile var hasVpMatrix = false

    private var trailProgram = 0
    private var ghostProgram = 0

    // 动态条带缓冲（最多 32 点 -> 64 顶点 * 5 float = 320 float）
    private val trailVertArray = FloatArray(MAX_TRAIL_POINTS * 2 * 5)
    private val trailVertBuf: FloatBuffer = ByteBuffer.allocateDirect(trailVertArray.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val ghostQuadBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -0.5f, -0.5f, 0f, 0f,
                 0.5f, -0.5f, 1f, 0f,
                -0.5f,  0.5f, 0f, 1f,
                 0.5f,  0.5f, 1f, 1f
            ))
            position(0)
        }

    // P20-D 独立空间音效池（支持左右声道独立音量）
    private var spatialPool: SoundPool? = null
    private val soundIdCache = ConcurrentHashMap<String, Int>()
    private val pendingSpatialPlays = ConcurrentHashMap<Int, Triple<Float, Float, Float>>() // soundId -> (left, right, pitch)

    fun initGL() {
        trailProgram = ZeroShaders.buildShaderProgram(TRAIL_VS, TRAIL_FS, onConsoleLog)
        ghostProgram = ZeroShaders.buildShaderProgram(GHOST_VS, GHOST_FS, onConsoleLog)
    }

    fun releaseGL() {
        clear()
        if (trailProgram != 0) { GLES20.glDeleteProgram(trailProgram); trailProgram = 0 }
        if (ghostProgram != 0) { GLES20.glDeleteProgram(ghostProgram); ghostProgram = 0 }
        try { spatialPool?.release() } catch (_: Exception) {}
        spatialPool = null
        soundIdCache.clear()
        pendingSpatialPlays.clear()
    }

    fun clear() {
        trails.clear()
        ghosts.clear()
        autoGhosts.clear()
        floatingTexts.clear()
    }

    fun copyVpMatrix(vp: FloatArray) {
        System.arraycopy(vp, 0, latestVpMatrix, 0, 16)
        hasVpMatrix = true
    }

    // ==================== P20-B 拖尾 ====================
    fun attachTrail(
        id: String,
        nodeName: String,
        offsetX: Float,
        offsetY: Float,
        width: Float,
        life: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        additive: Boolean,
        enabled: Boolean
    ): String {
        val key = id.ifEmpty { "${nodeName}_trail" }
        trails[key] = TrailStrip(
            id = key,
            nodeName = nodeName,
            offsetX = offsetX,
            offsetY = offsetY,
            width = width.coerceIn(2f, 220f),
            maxLife = life.coerceIn(0.05f, 2.5f),
            r = r, g = g, b = b, a = a.coerceIn(0.05f, 1f),
            additive = additive,
            enabled = enabled
        )
        return key
    }

    fun setTrailEnabled(id: String, enabled: Boolean) {
        trails[id]?.enabled = enabled
    }

    fun removeTrail(id: String) {
        trails.remove(id)
    }

    // ==================== P20-B 残影 ====================
    fun spawnAfterimage(
        n: ZeroEngineView.GameNode,
        gt: ZeroEngineView.GlobalTransform,
        duration: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        if (ghosts.size >= MAX_GHOSTS) ghosts.removeAt(0)
        ghosts.add(
            Afterimage(
                x = gt.gx, y = gt.gy, z = gt.gz - 0.5f,
                w = n.width * gt.gSx, h = n.height * gt.gSy,
                rotDeg = gt.gRot,
                texture = n.texture,
                r = r, g = g, b = b, a = a.coerceIn(0.05f, 1f),
                age = 0f,
                maxAge = duration.coerceIn(0.05f, 2.0f)
            )
        )
    }

    fun setAutoAfterimage(
        nodeName: String,
        enabled: Boolean,
        interval: Float,
        duration: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ) {
        if (!enabled || nodeName.isEmpty()) {
            autoGhosts.remove(nodeName)
            return
        }
        autoGhosts[nodeName] = AutoGhostCfg(
            nodeName = nodeName,
            interval = interval.coerceIn(0.02f, 0.5f),
            timer = 0f,
            duration = duration.coerceIn(0.05f, 1.5f),
            r = r, g = g, b = b, a = a.coerceIn(0.05f, 1f)
        )
    }

    // ==================== P20-B 伤害飘字 ====================
    fun spawnFloatingText(
        x: Float,
        y: Float,
        text: String,
        colorHex: String,
        scale: Float,
        crit: Boolean,
        duration: Float
    ) {
        if (text.isEmpty()) return
        if (floatingTexts.size >= 40) floatingTexts.removeAt(0)
        val col = try { Color.parseColor(colorHex) } catch (_: Exception) {
            if (crit) Color.parseColor("#FACC15") else Color.WHITE
        }
        val vx = ((Math.random() - 0.5) * (if (crit) 110.0 else 60.0)).toFloat()
        val vy = if (crit) -210f else -145f
        floatingTexts.add(
            FloatingText(
                x = x, y = y, z = 12f,
                text = text,
                colorInt = col,
                scale = scale.coerceIn(0.5f, 2.8f),
                crit = crit,
                age = 0f,
                maxAge = duration.coerceIn(0.25f, 3.0f),
                vx = vx,
                vy = vy
            )
        )
    }

    // ==================== P20-D 2D 空间音效 ====================
    private fun ensureSpatialPool(): SoundPool {
        spatialPool?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
        pool.setOnLoadCompleteListener { sp, sampleId, status ->
            if (status == 0) {
                pendingSpatialPlays.remove(sampleId)?.let { (l, r, pitch) ->
                    sp.play(sampleId, l, r, 1, 0, pitch)
                }
            }
        }
        spatialPool = pool
        return pool
    }

    fun playSpatialSfx(
        projectDir: File,
        fileName: String,
        wx: Float,
        wy: Float,
        camX: Float,
        camY: Float,
        maxDist: Float,
        baseVol: Float,
        pitch: Float
    ) {
        if (fileName.isBlank()) return
        val dx = wx - camX
        val dy = wy - camY
        val dist = hypot(dx, dy)
        val range = max(60f, maxDist)
        if (dist >= range) return

        val att = (1f - dist / range).coerceIn(0f, 1f)
        val gain = (att * att * baseVol).coerceIn(0f, 1f)
        if (gain < 0.01f) return

        val pan = (dx / (range * 0.65f)).coerceIn(-1f, 1f)
        val leftVol = (gain * min(1f, 1f - pan)).coerceIn(0f, 1f)
        val rightVol = (gain * min(1f, 1f + pan)).coerceIn(0f, 1f)
        val safePitch = pitch.coerceIn(0.5f, 2.0f)

        val f = File(File(projectDir, "audio"), fileName)
        if (!f.exists()) {
            onConsoleLog("[SFX 空间音效] 未找到文件: audio/$fileName")
            return
        }
        val pool = ensureSpatialPool()
        val cached = soundIdCache[fileName]
        if (cached != null) {
            pool.play(cached, leftVol, rightVol, 1, 0, safePitch)
        } else {
            val sid = pool.load(f.absolutePath, 1)
            soundIdCache[fileName] = sid
            pendingSpatialPlays[sid] = Triple(leftVol, rightVol, safePitch)
        }
    }

    // ==================== P20-D 马达震动反馈 ====================
    @Suppress("DEPRECATION")
    fun vibrate(engine: ZeroEngineView, style: String, durationMs: Long) {
        val ctx = engine.context
        val fallbackHaptic = {
            engine.post {
                val code = if (style == "heavy" || style == "explosion") {
                    HapticFeedbackConstants.LONG_PRESS
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
                engine.performHapticFeedback(code, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            }
        }
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib == null || !vib.hasVibrator()) {
                fallbackHaptic()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (style.lowercase()) {
                    "light" -> VibrationEffect.createOneShot(18L, 75)
                    "medium" -> VibrationEffect.createOneShot(36L, 160)
                    "heavy" -> VibrationEffect.createOneShot(72L, 250)
                    "hit" -> VibrationEffect.createOneShot(24L, 230)
                    "double" -> VibrationEffect.createWaveform(longArrayOf(0, 22, 48, 32), intArrayOf(0, 180, 0, 230), -1)
                    "explosion" -> VibrationEffect.createWaveform(longArrayOf(0, 45, 28, 90, 24, 42), intArrayOf(0, 255, 0, 220, 0, 140), -1)
                    else -> VibrationEffect.createOneShot(durationMs.coerceIn(10L, 400L), VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vib.vibrate(effect)
            } else {
                vib.vibrate(durationMs.coerceIn(10L, 400L))
            }
        } catch (_: Throwable) {
            // 若 Manifest 未声明 VIBRATE 权限，自动无缝降级为 View 触觉反馈
            fallbackHaptic()
        }
    }

    // ==================== 每帧更新与渲染 ====================
    fun update(
        dt: Float,
        nodes: List<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        if (trails.isEmpty() && ghosts.isEmpty() && autoGhosts.isEmpty() && floatingTexts.isEmpty()) return

        val nodeMap = if (trails.isNotEmpty() || autoGhosts.isNotEmpty()) {
            HashMap<String, ZeroEngineView.GameNode>(nodes.size).apply { nodes.forEach { put(it.name, it) } }
        } else null

        // 1. 更新拖尾采样点
        trails.values.forEach { t ->
            val it = t.points.iterator()
            while (it.hasNext()) {
                val pt = it.next()
                pt.age += dt
                if (pt.age >= t.maxLife) it.remove()
            }
            if (t.enabled) {
                val anchor = nodeMap?.get(t.nodeName)
                if (anchor != null && anchor.visible) {
                    val gt = getGlobalTransform(anchor)
                    val sx = if (gt.gSx < 0f) -1f else 1f
                    val px = gt.gx + t.offsetX * sx
                    val py = gt.gy + t.offsetY
                    val pz = gt.gz + 3f
                    val last = t.points.lastOrNull()
                    if (last == null || hypot(px - last.x, py - last.y) >= 4.5f) {
                        if (t.points.size >= MAX_TRAIL_POINTS) t.points.removeFirst()
                        t.points.addLast(TrailPoint(px, py, pz, 0f))
                    }
                }
            }
        }

        // 2. 更新自动残影生成器
        autoGhosts.values.forEach { cfg ->
            cfg.timer -= dt
            if (cfg.timer <= 0f) {
                cfg.timer = cfg.interval
                val anchor = nodeMap?.get(cfg.nodeName)
                if (anchor != null && anchor.visible) {
                    spawnAfterimage(anchor, getGlobalTransform(anchor), cfg.duration, cfg.r, cfg.g, cfg.b, cfg.a)
                }
            }
        }

        // 3. 更新残影衰减
        val gIt = ghosts.iterator()
        while (gIt.hasNext()) {
            val g = gIt.next()
            g.age += dt
            if (g.age >= g.maxAge) gIt.remove()
        }

        // 4. 更新世界空间飘字
        val ftIt = floatingTexts.iterator()
        val toRemove = mutableListOf<FloatingText>()
        while (ftIt.hasNext()) {
            val f = ftIt.next()
            f.age += dt
            if (f.age >= f.maxAge) {
                toRemove.add(f)
            } else {
                f.x += f.vx * dt
                f.y += f.vy * dt
                f.vy += 320f * dt // 轻微重力抛物线
            }
        }
        if (toRemove.isNotEmpty()) floatingTexts.removeAll(toRemove)
    }

    fun render(
        vpMatrix: FloatArray,
        fadeAlpha: Float,
        whiteTexId: Int,
        getImageTexture: (String) -> Int,
        onDrawCall: () -> Unit
    ) {
        copyVpMatrix(vpMatrix)
        if (fadeAlpha <= 0.001f) return
        if (ghosts.isEmpty() && trails.isEmpty()) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // 1. 渲染冲刺残影
        if (ghosts.isNotEmpty() && ghostProgram != 0) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            GLES20.glUseProgram(ghostProgram)
            val uVpLoc = GLES20.glGetUniformLocation(ghostProgram, "uVP")
            val uTransformLoc = GLES20.glGetUniformLocation(ghostProgram, "uTransform")
            val uRotZLoc = GLES20.glGetUniformLocation(ghostProgram, "uRotZ")
            val uColorLoc = GLES20.glGetUniformLocation(ghostProgram, "uColor")
            val uTexLoc = GLES20.glGetUniformLocation(ghostProgram, "uTexture")

            GLES20.glUniformMatrix4fv(uVpLoc, 1, false, vpMatrix, 0)
            ghostQuadBuf.position(0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, ghostQuadBuf)
            ghostQuadBuf.position(2)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, ghostQuadBuf)

            ghosts.forEach { g ->
                val remain = (1f - g.age / g.maxAge).coerceIn(0f, 1f)
                val alpha = g.a * remain * remain * fadeAlpha
                GLES20.glUniform4f(uTransformLoc, g.x, g.y, g.w, g.h)
                GLES20.glUniform2f(uRotZLoc, Math.toRadians(g.rotDeg.toDouble()).toFloat(), g.z)
                GLES20.glUniform4f(uColorLoc, g.r, g.g, g.b, alpha)
                val texId = if (g.texture.isNotEmpty()) getImageTexture(g.texture) else whiteTexId
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glUniform1i(uTexLoc, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                onDrawCall()
            }
            GLES20.glDisableVertexAttribArray(0)
            GLES20.glDisableVertexAttribArray(1)
        }

        // 2. 渲染刀光/移动拖尾条带
        if (trails.isNotEmpty() && trailProgram != 0) {
            GLES20.glUseProgram(trailProgram)
            val uVpLoc = GLES20.glGetUniformLocation(trailProgram, "uVP")
            val uColorLoc = GLES20.glGetUniformLocation(trailProgram, "uColor")
            GLES20.glUniformMatrix4fv(uVpLoc, 1, false, vpMatrix, 0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glEnableVertexAttribArray(1)

            trails.values.forEach { t ->
                val n = t.points.size
                if (n < 2) return@forEach
                val pts = t.points.toList()
                var off = 0
                for (i in 0 until n) {
                    val p = pts[i]
                    val prev = pts[max(0, i - 1)]
                    val next = pts[min(n - 1, i + 1)]
                    val tx = next.x - prev.x
                    val ty = next.y - prev.y
                    val len = hypot(tx, ty)
                    val nx = if (len > 0.001f) -ty / len else 0f
                    val ny = if (len > 0.001f) tx / len else 1f

                    val lifeRatio = (1f - p.age / t.maxLife).coerceIn(0f, 1f)
                    val idxRatio = i.toFloat() / max(1, n - 1).toFloat()
                    val halfW = t.width * 0.5f * lifeRatio * idxRatio
                    val edgeAlpha = lifeRatio * idxRatio

                    // 左侧顶点
                    trailVertArray[off++] = p.x - nx * halfW
                    trailVertArray[off++] = p.y - ny * halfW
                    trailVertArray[off++] = p.z
                    trailVertArray[off++] = edgeAlpha
                    trailVertArray[off++] = 0f

                    // 右侧顶点
                    trailVertArray[off++] = p.x + nx * halfW
                    trailVertArray[off++] = p.y + ny * halfW
                    trailVertArray[off++] = p.z
                    trailVertArray[off++] = edgeAlpha
                    trailVertArray[off++] = 1f
                }

                trailVertBuf.clear()
                trailVertBuf.put(trailVertArray, 0, off)
                trailVertBuf.position(0)
                GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 20, trailVertBuf)
                trailVertBuf.position(3)
                GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 20, trailVertBuf)

                GLES20.glBlendFunc(
                    GLES20.GL_SRC_ALPHA,
                    if (t.additive) GLES20.GL_ONE else GLES20.GL_ONE_MINUS_SRC_ALPHA
                )
                GLES20.glUniform4f(uColorLoc, t.r, t.g, t.b, t.a * fadeAlpha)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, n * 2)
                onDrawCall()
            }

            GLES20.glDisableVertexAttribArray(0)
            GLES20.glDisableVertexAttribArray(1)
        }

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    companion object {
        private const val MAX_TRAIL_POINTS = 30
        private const val MAX_GHOSTS = 48

        private val TRAIL_VS = """#version 300 es
            precision highp float;
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aMeta; // x=alphaRatio, y=across[0..1]
            uniform mat4 uVP;
            out vec2 vMeta;
            void main() {
                vMeta = aMeta;
                gl_Position = uVP * vec4(aPos, 1.0);
            }
        """.trimIndent()

        private val TRAIL_FS = """#version 300 es
            precision mediump float;
            uniform vec4 uColor;
            in vec2 vMeta;
            out vec4 fragColor;
            void main() {
                float core = 1.0 - abs(vMeta.y * 2.0 - 1.0);
                float glow = smoothstep(0.0, 0.85, core);
                vec3 rgb = mix(uColor.rgb, vec3(1.0), glow * 0.45 * vMeta.x);
                float a = uColor.a * vMeta.x * glow;
                if (a < 0.01) discard;
                fragColor = vec4(rgb, a);
            }
        """.trimIndent()

        private val GHOST_VS = """#version 300 es
            precision highp float;
            layout(location = 0) in vec2 aLocal;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uVP;
            uniform vec4 uTransform; // xy=center, zw=size
            uniform vec2 uRotZ;      // x=rotRad, y=z
            out vec2 vUv;
            void main() {
                vec2 p = aLocal * uTransform.zw;
                float c = cos(uRotZ.x);
                float s = sin(uRotZ.x);
                vec2 rp = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
                vUv = aUv;
                gl_Position = uVP * vec4(uTransform.xy + rp, uRotZ.y, 1.0);
            }
        """.trimIndent()

        private val GHOST_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec4 tex = texture(uTexture, vUv);
                if (tex.a < 0.05) discard;
                fragColor = vec4(uColor.rgb * (0.65 + 0.35 * tex.rgb), tex.a * uColor.a);
            }
        """.trimIndent()
    }
}
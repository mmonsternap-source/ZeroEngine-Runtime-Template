package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

/**
 * P20-C 潜行守卫 AI 状态机 + 地面墙体遮挡视野锥可视化系统：
 * - 四态闭环：patrol（巡逻） -> suspect（疑心 ?） -> alert（发现并追击 !） -> search（搜寻张望）
 * - 自动联动 P18-A 视觉/听觉感知与 A* 寻路，支持无烘焙时直线平滑追击
 * - 实时射线裁剪地面扇形视野锥（单次 GL_TRIANGLE_FAN / 守卫）
 */
class ZeroStealthGuardSystem(
    private val onConsoleLog: (String) -> Unit
) {
    data class GuardHudMarker(
        val id: String,
        val x: Float,
        val y: Float,
        val state: String,
        val suspicion: Float
    )

    class GuardAgent(
        val id: String,
        val nodeName: String,
        var targetName: String,
        var waypoints: List<Pair<Float, Float>>,
        var patrolSpeed: Float,
        var chaseSpeed: Float,
        var viewRange: Float,
        var fovDeg: Float,
        var hearMul: Float,
        var suspectTime: Float,
        var loseTime: Float,
        var wallMask: Int,
        var showCone: Boolean
    ) {
        @Volatile var state: String = "patrol" // patrol | suspect | alert | search
        @Volatile var suspicion: Float = 0f
        @Volatile var facingDeg: Float = 0f
        var wpIndex: Int = 0
        var lastKnownX: Float = 0f
        var lastKnownY: Float = 0f
        var hasLastKnown: Boolean = false
        var loseTimer: Float = 0f
        var searchTimer: Float = 0f
        var searchBaseFacing: Float = 0f
        var pathTimer: Float = 0f
        var currentPath: List<Pair<Float, Float>> = emptyList()
        var pathIdx: Int = 0

        // 视野锥顶点缓冲：中心点 + (RAY_STEPS + 1) 个边缘点，每点 3 float (x, y, radialFactor)
        val coneVerts: FloatArray = FloatArray((RAY_STEPS + 2) * 3)
        var coneValid: Boolean = false
        var coneZ: Float = 0.8f
    }

    private val guards = ConcurrentHashMap<String, GuardAgent>()
    val hudMarkers = CopyOnWriteArrayList<GuardHudMarker>()

    private var coneProgram = 0
    private var elapsedTime = 0f
    private val coneBuf: FloatBuffer = ByteBuffer.allocateDirect((RAY_STEPS + 2) * 3 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun initGL() {
        coneProgram = ZeroShaders.buildShaderProgram(CONE_VS, CONE_FS, onConsoleLog)
    }

    fun releaseGL() {
        clear()
        if (coneProgram != 0) {
            GLES20.glDeleteProgram(coneProgram)
            coneProgram = 0
        }
    }

    fun clear() {
        guards.clear()
        hudMarkers.clear()
    }

    fun createGuard(
        id: String,
        nodeName: String,
        targetName: String,
        waypoints: List<Pair<Float, Float>>,
        patrolSpeed: Float,
        chaseSpeed: Float,
        viewRange: Float,
        fovDeg: Float,
        hearMul: Float,
        suspectTime: Float,
        loseTime: Float,
        wallMask: Int,
        showCone: Boolean
    ): String {
        val key = id.ifEmpty { nodeName.ifEmpty { "guard_${guards.size + 1}" } }
        guards[key] = GuardAgent(
            id = key,
            nodeName = nodeName,
            targetName = targetName.ifEmpty { "Hero" },
            waypoints = waypoints,
            patrolSpeed = patrolSpeed.coerceIn(20f, 900f),
            chaseSpeed = chaseSpeed.coerceIn(30f, 1200f),
            viewRange = viewRange.coerceIn(80f, 1200f),
            fovDeg = fovDeg.coerceIn(15f, 180f),
            hearMul = hearMul.coerceIn(0f, 3f),
            suspectTime = suspectTime.coerceIn(0.15f, 5f),
            loseTime = loseTime.coerceIn(0.5f, 12f),
            wallMask = wallMask,
            showCone = showCone
        )
        return key
    }

    fun removeGuard(id: String) {
        guards.remove(id)
    }

    fun getGuard(id: String): GuardAgent? = guards[id]

    fun update(dt: Float, engine: ZeroEngineView) {
        elapsedTime += dt
        if (guards.isEmpty()) {
            if (hudMarkers.isNotEmpty()) hudMarkers.clear()
            return
        }

        val markers = mutableListOf<GuardHudMarker>()
        guards.values.forEach { g ->
            val node = engine.findNodeByName(g.nodeName) ?: return@forEach
            if (!node.visible) return@forEach
            val gt = engine.getGlobalTransform(node)
            val target = engine.findNodeByName(g.targetName)

            // 1. 视觉感知
            val canSeeTarget = if (target != null && target.visible) {
                engine.aiNavSystem.canSee(
                    viewer = node,
                    target = target,
                    range = g.viewRange,
                    fovDeg = g.fovDeg,
                    facingDegOverride = g.facingDeg,
                    wallMask = g.wallMask,
                    nodes = engine.nodes,
                    pendingRemove = engine.pendingRemove,
                    physicsSystem = engine.physicsSystem,
                    getGlobalTransform = { engine.getGlobalTransform(it) }
                )
            } else false

            if (canSeeTarget && target != null) {
                val tgt = engine.getGlobalTransform(target)
                g.lastKnownX = tgt.gx
                g.lastKnownY = tgt.gy
                g.hasLastKnown = true
                g.loseTimer = g.loseTime
                if (g.state == "alert") {
                    g.suspicion = 1f
                } else {
                    g.suspicion = (g.suspicion + dt / g.suspectTime).coerceIn(0f, 1f)
                    if (g.suspicion >= 1f) {
                        g.state = "alert"
                    } else {
                        g.state = "suspect"
                    }
                }
            } else {
                // 2. 听觉感知
                val sounds = if (g.hearMul > 0.01f) {
                    engine.aiNavSystem.hearSounds(gt.gx, gt.gy, g.hearMul, "")
                } else emptyList()
                val heard = sounds.firstOrNull { it.sourceName != g.nodeName }
                if (heard != null) {
                    g.lastKnownX = heard.x
                    g.lastKnownY = heard.y
                    g.hasLastKnown = true
                    if (g.state == "patrol") {
                        g.state = "suspect"
                        g.suspicion = max(g.suspicion, 0.45f)
                        g.searchTimer = 2.4f
                    }
                }

                // 状态自然衰减
                when (g.state) {
                    "alert" -> {
                        g.loseTimer -= dt
                        if (g.loseTimer <= 0f) {
                            g.state = "search"
                            g.searchTimer = 2.8f
                            g.searchBaseFacing = g.facingDeg
                        }
                    }
                    "suspect" -> {
                        g.suspicion = max(0f, g.suspicion - dt * 0.45f)
                        if (g.suspicion <= 0.01f) {
                            g.state = if (g.hasLastKnown) "search" else "patrol"
                            g.searchTimer = 2.0f
                            g.searchBaseFacing = g.facingDeg
                        }
                    }
                }
            }

            // 3. 按当前状态移动与转向
            when (g.state) {
                "patrol" -> {
                    if (g.waypoints.isNotEmpty()) {
                        val (wx, wy) = g.waypoints[g.wpIndex % g.waypoints.size]
                        if (moveTowards(node, gt.gx, gt.gy, wx, wy, g.patrolSpeed, dt, g)) {
                            g.wpIndex = (g.wpIndex + 1) % g.waypoints.size
                        }
                    }
                }
                "suspect" -> {
                    if (g.hasLastKnown) {
                        turnTowards(g, g.lastKnownX - gt.gx, g.lastKnownY - gt.gy, dt * 7f)
                        moveTowards(node, gt.gx, gt.gy, g.lastKnownX, g.lastKnownY, g.patrolSpeed * 0.55f, dt, g)
                    }
                }
                "alert" -> {
                    if (g.hasLastKnown) {
                        followTargetSmart(engine, node, gt.gx, gt.gy, g.lastKnownX, g.lastKnownY, g.chaseSpeed, dt, g)
                    }
                }
                "search" -> {
                    if (g.hasLastKnown && hypot(g.lastKnownX - gt.gx, g.lastKnownY - gt.gy) > 26f) {
                        followTargetSmart(engine, node, gt.gx, gt.gy, g.lastKnownX, g.lastKnownY, g.patrolSpeed * 1.15f, dt, g)
                    } else {
                        g.searchTimer -= dt
                        g.facingDeg = g.searchBaseFacing + sin(elapsedTime * 3.2f) * 58f
                        if (g.searchTimer <= 0f) {
                            g.hasLastKnown = false
                            g.suspicion = 0f
                            g.state = "patrol"
                            g.currentPath = emptyList()
                        }
                    }
                }
            }

            // 4. 射线采样墙体遮挡视野锥多边形
            if (g.showCone) {
                val nGt = engine.getGlobalTransform(node)
                val halfFov = g.fovDeg * 0.5f
                var off = 0
                g.coneVerts[off++] = nGt.gx
                g.coneVerts[off++] = nGt.gy
                g.coneVerts[off++] = 0f
                for (i in 0..RAY_STEPS) {
                    val frac = i.toFloat() / RAY_STEPS.toFloat()
                    val deg = g.facingDeg - halfFov + g.fovDeg * frac
                    val rad = Math.toRadians(deg.toDouble())
                    val ex = nGt.gx + cos(rad).toFloat() * g.viewRange
                    val ey = nGt.gy + sin(rad).toFloat() * g.viewRange
                    val hit = engine.physicsSystem.raycast(
                        nGt.gx, nGt.gy, ex, ey,
                        ignoreTag = node.tag,
                        mask = g.wallMask
                    ) { engine.getGlobalTransform(it) }
                    if (hit.hit) {
                        g.coneVerts[off++] = hit.x
                        g.coneVerts[off++] = hit.y
                        g.coneVerts[off++] = (hit.distance / g.viewRange).coerceIn(0.05f, 1f)
                    } else {
                        g.coneVerts[off++] = ex
                        g.coneVerts[off++] = ey
                        g.coneVerts[off++] = 1f
                    }
                }
                g.coneZ = nGt.gz + 0.8f
                g.coneValid = true
            } else {
                g.coneValid = false
            }

            if (g.state != "patrol" || g.suspicion > 0.05f) {
                val headY = gt.gy - abs(node.height * gt.gSy) * 0.65f - 14f
                markers.add(GuardHudMarker(g.id, gt.gx, headY, g.state, g.suspicion))
            }
        }

        hudMarkers.clear()
        if (markers.isNotEmpty()) hudMarkers.addAll(markers)
    }

    private fun followTargetSmart(
        engine: ZeroEngineView,
        node: ZeroEngineView.GameNode,
        gx: Float,
        gy: Float,
        tx: Float,
        ty: Float,
        speed: Float,
        dt: Float,
        g: GuardAgent
    ) {
        if (engine.aiNavSystem.isBaked) {
            g.pathTimer -= dt
            if (g.pathTimer <= 0f || g.currentPath.isEmpty()) {
                g.pathTimer = 0.45f
                g.currentPath = engine.aiNavSystem.findPath(gx, gy, tx, ty, true)
                g.pathIdx = if (g.currentPath.size > 1) 1 else 0
            }
            if (g.pathIdx in g.currentPath.indices) {
                val (nx, ny) = g.currentPath[g.pathIdx]
                if (moveTowards(node, gx, gy, nx, ny, speed, dt, g)) {
                    g.pathIdx++
                }
                return
            }
        }
        moveTowards(node, gx, gy, tx, ty, speed, dt, g)
    }

    private fun moveTowards(
        node: ZeroEngineView.GameNode,
        gx: Float,
        gy: Float,
        tx: Float,
        ty: Float,
        speed: Float,
        dt: Float,
        g: GuardAgent
    ): Boolean {
        val dx = tx - gx
        val dy = ty - gy
        val dist = hypot(dx, dy)
        if (dist <= max(8f, speed * dt)) return true
        turnTowards(g, dx, dy, dt * 10f)
        val step = speed * dt
        node.x += (dx / dist) * step
        node.y += (dy / dist) * step
        return false
    }

    private fun turnTowards(g: GuardAgent, dx: Float, dy: Float, blend: Float) {
        if (abs(dx) < 0.01f && abs(dy) < 0.01f) return
        val targetDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        var diff = targetDeg - g.facingDeg
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        g.facingDeg += diff * blend.coerceIn(0.05f, 1f)
    }

    fun renderCones(
        vpMatrix: FloatArray,
        fadeAlpha: Float,
        onDrawCall: () -> Unit
    ) {
        if (guards.isEmpty() || coneProgram == 0 || fadeAlpha <= 0.001f) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(coneProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        val uVpLoc = GLES20.glGetUniformLocation(coneProgram, "uVP")
        val uZLoc = GLES20.glGetUniformLocation(coneProgram, "uZ")
        val uColorLoc = GLES20.glGetUniformLocation(coneProgram, "uColor")

        GLES20.glUniformMatrix4fv(uVpLoc, 1, false, vpMatrix, 0)
        GLES20.glEnableVertexAttribArray(0)

        guards.values.forEach { g ->
            if (!g.showCone || !g.coneValid) return@forEach
            val pulse = if (g.state == "alert") (0.82f + 0.18f * sin(elapsedTime * 10f)) else 1f
            val r: Float; val gr: Float; val b: Float; val a: Float
            when (g.state) {
                "alert" -> { r = 1.0f; gr = 0.16f; b = 0.20f; a = 0.42f * pulse }
                "suspect" -> { r = 1.0f; gr = 0.78f; b = 0.12f; a = 0.36f }
                "search" -> { r = 1.0f; gr = 0.48f; b = 0.12f; a = 0.34f }
                else -> { r = 0.18f; gr = 0.88f; b = 0.72f; a = 0.24f }
            }

            coneBuf.clear()
            coneBuf.put(g.coneVerts)
            coneBuf.position(0)
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 12, coneBuf)
            GLES20.glUniform1f(uZLoc, g.coneZ)
            GLES20.glUniform4f(uColorLoc, r, gr, b, a * fadeAlpha)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, RAY_STEPS + 2)
            onDrawCall()
        }

        GLES20.glDisableVertexAttribArray(0)
    }

    companion object {
        private const val RAY_STEPS = 20

        private val CONE_VS = """#version 300 es
            precision highp float;
            layout(location = 0) in vec3 aData; // xy=worldPos, z=radialDist[0..1]
            uniform mat4 uVP;
            uniform float uZ;
            out float vRadial;
            void main() {
                vRadial = aData.z;
                gl_Position = uVP * vec4(aData.xy, uZ, 1.0);
            }
        """.trimIndent()

        private val CONE_FS = """#version 300 es
            precision mediump float;
            uniform vec4 uColor;
            in float vRadial;
            out vec4 fragColor;
            void main() {
                float fade = 1.0 - smoothstep(0.12, 1.0, vRadial) * 0.72;
                float rim = smoothstep(0.92, 0.99, vRadial) * 0.45;
                float a = uColor.a * (fade + rim);
                if (a < 0.01) discard;
                fragColor = vec4(uColor.rgb + vec3(rim * 0.35), clamp(a, 0.0, 0.75));
            }
        """.trimIndent()
    }
}
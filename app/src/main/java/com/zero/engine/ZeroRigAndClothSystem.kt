package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/**
 * P9 商业级 2D 骨骼 IK 反向动力学 + Verlet 披风/丝带布料物理与动态网格渲染系统
 */
class ZeroRigAndClothSystem(
    private val onConsoleLog: (String) -> Unit
) {
    data class VerletPoint(
        var x: Float,
        var y: Float,
        var oldX: Float,
        var oldY: Float
    )

    data class ClothRibbon(
        val id: String,
        var anchorNodeName: String,
        var offsetX: Float = 0f,
        var offsetY: Float = 0f,
        val segments: Int = 10,
        var segmentLen: Float = 18f,
        var widthStart: Float = 38f,
        var widthEnd: Float = 10f,
        var r: Float = 0.88f,
        var g: Float = 0.18f,
        var b: Float = 0.24f,
        var a: Float = 0.95f,
        var gravityX: Float = 0f,
        var gravityY: Float = 980f,
        var windX: Float = -120f,
        var windY: Float = 0f,
        var turbulence: Float = 180f,
        var damping: Float = 0.96f,
        var zOffset: Float = -1f,
        var texture: String = "",
        var visible: Boolean = true,
        val points: Array<VerletPoint>
    )

    data class TwoBoneIkConstraint(
        val id: String,
        val rootNodeName: String,
        val midNodeName: String,
        var len1: Float,
        var len2: Float,
        var targetX: Float = 0f,
        var targetY: Float = 0f,
        var flipBend: Boolean = false,
        var enabled: Boolean = true
    )

    private val cloths = LinkedHashMap<String, ClothRibbon>()
    private val ikConstraints = LinkedHashMap<String, TwoBoneIkConstraint>()

    private var globalWindX = 0f
    private var globalWindY = 0f
    private var simTime = 0f

    private var ribbonProgram = 0
    private val maxRibbonVerts = 128 // 支持单条丝带最高 63 段
    private val ribbonData = FloatArray(maxRibbonVerts * 5)
    private val ribbonBuffer: FloatBuffer = ByteBuffer.allocateDirect(ribbonData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    val activeClothCount: Int get() = cloths.size
    val activeIkCount: Int get() = ikConstraints.size

    fun initGL() {
        ribbonProgram = ZeroShaders.buildShaderProgram(RIBBON_VS, RIBBON_FS, onConsoleLog)
    }

    fun releaseGL() {
        if (ribbonProgram != 0) {
            GLES20.glDeleteProgram(ribbonProgram)
            ribbonProgram = 0
        }
    }

    fun clearAll() {
        cloths.clear()
        ikConstraints.clear()
    }

    fun setGlobalWind(wx: Float, wy: Float) {
        globalWindX = wx
        globalWindY = wy
    }

    // ==================== 1. 2D 两段骨骼解析 IK（Law of Cosines） ====================

    /**
     * 单次即时求解两段骨骼 IK，直接写入 rootNode 与 midNode 的局部 rotation（角度制）
     */
    fun solveTwoBoneIK(
        rootNode: ZeroEngineView.GameNode,
        midNode: ZeroEngineView.GameNode,
        targetX: Float,
        targetY: Float,
        len1: Float,
        len2: Float,
        flipBend: Boolean,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Boolean {
        val l1 = max(1f, len1)
        val l2 = max(1f, len2)
        val rootGt = getGlobalTransform(rootNode)

        val dx = targetX - rootGt.gx
        val dy = targetY - rootGt.gy
        val rawDist = hypot(dx, dy)
        val minReach = abs(l1 - l2) + 0.5f
        val maxReach = (l1 + l2) - 0.5f
        val dist = rawDist.coerceIn(minReach, maxReach)

        val baseAngle = atan2(dy.toDouble(), dx.toDouble())
        val cosA = ((l1 * l1 + dist * dist - l2 * l2) / (2.0 * l1 * dist)).coerceIn(-1.0, 1.0)
        val cosB = ((l1 * l1 + l2 * l2 - dist * dist) / (2.0 * l1 * l2)).coerceIn(-1.0, 1.0)

        val angleA = acos(cosA)
        val angleB = acos(cosB)

        val bendSign = if (flipBend) -1.0 else 1.0
        val worldRootRad = baseAngle - angleA * bendSign
        val localMidRad = (PI - angleB) * bendSign

        // 减去父节点自身带来的世界旋转，换算回局部 rotation
        val parentRotDeg = rootGt.gRot - rootNode.rotation
        rootNode.rotation = Math.toDegrees(worldRootRad).toFloat() - parentRotDeg
        midNode.rotation = Math.toDegrees(localMidRad).toFloat()
        return rawDist <= (l1 + l2)
    }

    fun registerTwoBoneIK(
        id: String,
        rootName: String,
        midName: String,
        len1: Float,
        len2: Float,
        flipBend: Boolean
    ) {
        if (id.isBlank() || rootName.isBlank() || midName.isBlank()) return
        ikConstraints[id] = TwoBoneIkConstraint(id, rootName, midName, max(1f, len1), max(1f, len2), 0f, 0f, flipBend, true)
    }

    fun setIkTarget(id: String, tx: Float, ty: Float, enabled: Boolean = true) {
        ikConstraints[id]?.let {
            it.targetX = tx
            it.targetY = ty
            it.enabled = enabled
        }
    }

    fun removeIk(id: String) {
        ikConstraints.remove(id)
    }

    // ==================== 2. Verlet 2D 布料/披风/丝带物理 ====================

    fun createCloth(
        id: String,
        anchorNodeName: String,
        startX: Float,
        startY: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        segments: Int = 10,
        segmentLen: Float = 18f,
        widthStart: Float = 38f,
        widthEnd: Float = 10f,
        r: Float = 0.88f,
        g: Float = 0.18f,
        b: Float = 0.24f,
        a: Float = 0.95f,
        gravityY: Float = 980f,
        windX: Float = -120f,
        turbulence: Float = 180f,
        texture: String = ""
    ): ClothRibbon {
        val segs = segments.coerceIn(2, 60)
        val sLen = max(2f, segmentLen)
        val pts = Array(segs + 1) { i ->
            val px = startX + offsetX
            val py = startY + offsetY + i * sLen
            VerletPoint(px, py, px, py)
        }
        val ribbon = ClothRibbon(
            id = id,
            anchorNodeName = anchorNodeName,
            offsetX = offsetX,
            offsetY = offsetY,
            segments = segs,
            segmentLen = sLen,
            widthStart = max(1f, widthStart),
            widthEnd = max(0.5f, widthEnd),
            r = r, g = g, b = b, a = a,
            gravityY = gravityY,
            windX = windX,
            turbulence = turbulence,
            texture = texture,
            points = pts
        )
        cloths[id] = ribbon
        return ribbon
    }

    fun getCloth(id: String): ClothRibbon? = cloths[id]

    fun removeCloth(id: String) {
        cloths.remove(id)
    }

    fun update(
        dt: Float,
        nodes: List<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        simTime += dt

        // 1. 求解所有已注册的常驻 IK 约束
        if (ikConstraints.isNotEmpty()) {
            for (c in ikConstraints.values) {
                if (!c.enabled) continue
                val root = nodes.find { it.name == c.rootNodeName } ?: continue
                val mid = nodes.find { it.name == c.midNodeName } ?: continue
                solveTwoBoneIK(root, mid, c.targetX, c.targetY, c.len1, c.len2, c.flipBend, getGlobalTransform)
            }
        }

        // 2. 步进所有 Verlet 布料/丝带
        if (cloths.isEmpty()) return
        val dtSq = dt * dt

        for (cloth in cloths.values) {
            if (!cloth.visible) continue
            val anchor = nodes.find { it.name == cloth.anchorNodeName }
            val anchorGt = anchor?.let { getGlobalTransform(it) }
            val dirX = if (anchorGt != null && anchorGt.gSx < 0f) -1f else 1f
            val pinX = (anchorGt?.gx ?: cloth.points[0].x) + cloth.offsetX * dirX
            val pinY = (anchorGt?.gy ?: cloth.points[0].y) + cloth.offsetY

            val pts = cloth.points
            // 根节点强制钉死在角色锚点上
            pts[0].x = pinX; pts[0].y = pinY
            pts[0].oldX = pinX; pts[0].oldY = pinY

            // Verlet 积分
            for (i in 1 until pts.size) {
                val p = pts[i]
                val vx = (p.x - p.oldX) * cloth.damping
                val vy = (p.y - p.oldY) * cloth.damping
                p.oldX = p.x
                p.oldY = p.y

                val wave = sin((simTime * 7.5f - i * 0.65f).toDouble()).toFloat() * cloth.turbulence
                val ax = cloth.gravityX + cloth.windX * dirX + globalWindX + wave
                val ay = cloth.gravityY + cloth.windY + globalWindY + wave * 0.35f

                p.x += vx + ax * dtSq
                p.y += vy + ay * dtSq
            }

            // 6 次距离约束松弛迭代，保证披风绝不被拉断或过度伸长
            val segLen = cloth.segmentLen
            repeat(6) {
                pts[0].x = pinX
                pts[0].y = pinY
                for (i in 0 until pts.size - 1) {
                    val p1 = pts[i]
                    val p2 = pts[i + 1]
                    val dx = p2.x - p1.x
                    val dy = p2.y - p1.y
                    val dist = hypot(dx, dy).coerceAtLeast(0.0001f)
                    val diff = (dist - segLen) / dist
                    if (i == 0) {
                        p2.x -= dx * diff
                        p2.y -= dy * diff
                    } else {
                        val half = diff * 0.5f
                        p1.x += dx * half
                        p1.y += dy * half
                        p2.x -= dx * half
                        p2.y -= dy * half
                    }
                }
            }
        }
    }

    // ==================== 3. GPU 动态丝带/披风网格渲染 ====================

    fun renderCloths(
        vpMatrix: FloatArray,
        nodes: List<ZeroEngineView.GameNode>,
        fadeAlpha: Float,
        whiteTexId: Int,
        getTextureId: (String) -> Int,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform,
        onDrawCall: () -> Unit
    ) {
        if (cloths.isEmpty() || ribbonProgram == 0 || fadeAlpha <= 0.001f) return

        GLES20.glUseProgram(ribbonProgram)
        val posLoc = GLES20.glGetAttribLocation(ribbonProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(ribbonProgram, "aTexCoord")
        val vpLoc = GLES20.glGetUniformLocation(ribbonProgram, "uVP")
        val colLoc = GLES20.glGetUniformLocation(ribbonProgram, "uColor")
        val texLoc = GLES20.glGetUniformLocation(ribbonProgram, "uTexture")

        GLES20.glUniformMatrix4fv(vpLoc, 1, false, vpMatrix, 0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)

        for (cloth in cloths.values) {
            if (!cloth.visible) continue
            val pts = cloth.points
            if (pts.size < 2) continue

            val anchor = nodes.find { it.name == cloth.anchorNodeName }
            val baseZ = (anchor?.let { getGlobalTransform(it).gz } ?: 0f) + cloth.zOffset

            var fPos = 0
            val lastIdx = pts.size - 1
            for (i in pts.indices) {
                val t = i.toFloat() / lastIdx.toFloat()
                val halfW = (cloth.widthStart + (cloth.widthEnd - cloth.widthStart) * t) * 0.5f

                // 计算当前节点的切线与垂直法线
                val tx: Float
                val ty: Float
                when (i) {
                    0 -> { tx = pts[1].x - pts[0].x; ty = pts[1].y - pts[0].y }
                    lastIdx -> { tx = pts[lastIdx].x - pts[lastIdx - 1].x; ty = pts[lastIdx].y - pts[lastIdx - 1].y }
                    else -> { tx = pts[i + 1].x - pts[i - 1].x; ty = pts[i + 1].y - pts[i - 1].y }
                }
                val tLen = hypot(tx, ty).coerceAtLeast(0.0001f)
                val nx = -ty / tLen
                val ny = tx / tLen

                val px = pts[i].x
                val py = pts[i].y

                // 左边缘顶点
                ribbonData[fPos++] = px + nx * halfW
                ribbonData[fPos++] = py + ny * halfW
                ribbonData[fPos++] = baseZ
                ribbonData[fPos++] = 0f
                ribbonData[fPos++] = t

                // 右边缘顶点
                ribbonData[fPos++] = px - nx * halfW
                ribbonData[fPos++] = py - ny * halfW
                ribbonData[fPos++] = baseZ
                ribbonData[fPos++] = 1f
                ribbonData[fPos++] = t
            }

            ribbonBuffer.clear()
            ribbonBuffer.put(ribbonData, 0, fPos)

            ribbonBuffer.position(0)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, ribbonBuffer)
            ribbonBuffer.position(3)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, ribbonBuffer)

            val texId = if (cloth.texture.isNotEmpty()) getTextureId(cloth.texture) else whiteTexId
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(texLoc, 0)
            GLES20.glUniform4f(colLoc, cloth.r, cloth.g, cloth.b, cloth.a * fadeAlpha)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, pts.size * 2)
            onDrawCall()
        }

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    companion object {
        private const val RIBBON_VS = """
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uVP;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = uVP * vec4(aPosition, 1.0);
            }
        """

        private const val RIBBON_FS = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            varying vec2 vTexCoord;
            void main() {
                vec4 base = texture2D(uTexture, vTexCoord) * uColor;
                if (base.a < 0.01) discard;
                gl_FragColor = base;
            }
        """
    }
}

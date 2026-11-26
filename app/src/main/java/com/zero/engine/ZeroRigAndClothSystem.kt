package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * P17 + P18-C + P19-A + P19-B GLES 3.0 GPU 布料飘带 + 2-Bone IK + 水面/天气/交互草海联动托管
 */
class ZeroRigAndClothSystem(
    private val onConsoleLog: (String) -> Unit
) {
    private data class ClothNode(
        var x: Float,
        var y: Float,
        var oldX: Float,
        var oldY: Float
    )

    private class ClothStrip(
        val id: String,
        val anchorNodeName: String,
        val offsetX: Float,
        val offsetY: Float,
        val segmentLen: Float,
        val widthStart: Float,
        val widthEnd: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float,
        val gravityY: Float,
        val windX: Float,
        val turbulence: Float,
        val texture: String,
        val nodes: Array<ClothNode>,
        val uniformNodes: FloatArray = FloatArray(MAX_CLOTH_NODES * 4),
        var vboId: Int = 0,
        var vertexCount: Int = 0
    )

    private data class IkChain(
        val id: String,
        val rootName: String,
        val midName: String,
        val len1: Float,
        val len2: Float,
        val flipBend: Boolean,
        var targetX: Float = 0f,
        var targetY: Float = 0f,
        var active: Boolean = false
    )

    private val cloths = ConcurrentHashMap<String, ClothStrip>()
    private val ikChains = ConcurrentHashMap<String, IkChain>()
    val waterSystem = ZeroWaterSystem(onConsoleLog)
    val weatherSystem = ZeroWeatherSystem(onConsoleLog)
    val foliageSystem = ZeroFoliageSystem(onConsoleLog)

    private var clothProgram = 0
    private var globalWindX = 0f
    private var globalWindY = 0f
    private var elapsedTime = 0f

    val activeClothCount: Int get() = cloths.size
    val activeIkCount: Int get() = ikChains.size

    fun initGL() {
        clothProgram = ZeroShaders.buildShaderProgram(CLOTH_VS, CLOTH_FS, onConsoleLog)
        waterSystem.initGL()
        weatherSystem.initGL()
        foliageSystem.initGL()
    }

    fun releaseGL() {
        cloths.values.forEach { releaseClothVbo(it) }
        cloths.clear()
        ikChains.clear()
        waterSystem.releaseGL()
        weatherSystem.releaseGL()
        foliageSystem.releaseGL()
        if (clothProgram != 0) {
            GLES20.glDeleteProgram(clothProgram)
            clothProgram = 0
        }
    }

    fun clearAll() {
        cloths.values.forEach { releaseClothVbo(it) }
        cloths.clear()
        ikChains.clear()
        waterSystem.clear()
        weatherSystem.clear()
        foliageSystem.clear()
    }

    private fun releaseClothVbo(c: ClothStrip) {
        if (c.vboId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(c.vboId), 0)
            c.vboId = 0
        }
    }

    fun setGlobalWind(wx: Float, wy: Float) {
        globalWindX = wx
        globalWindY = wy
    }

    fun createCloth(
        id: String,
        anchorNodeName: String,
        startX: Float,
        startY: Float,
        offsetX: Float,
        offsetY: Float,
        segments: Int,
        segmentLen: Float,
        widthStart: Float,
        widthEnd: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        gravityY: Float,
        windX: Float,
        turbulence: Float,
        texture: String
    ) {
        removeCloth(id)
        val segs = segments.coerceIn(2, MAX_CLOTH_NODES - 1)
        val count = segs + 1
        val sx = startX + offsetX
        val sy = startY + offsetY
        val nodes = Array(count) { i ->
            ClothNode(sx, sy + i * segmentLen, sx, sy + i * segmentLen)
        }
        cloths[id] = ClothStrip(
            id = id,
            anchorNodeName = anchorNodeName,
            offsetX = offsetX,
            offsetY = offsetY,
            segmentLen = max(2f, segmentLen),
            widthStart = max(2f, widthStart),
            widthEnd = max(1f, widthEnd),
            r = r, g = g, b = b, a = a,
            gravityY = gravityY,
            windX = windX,
            turbulence = turbulence,
            texture = texture,
            nodes = nodes
        )
    }

    fun removeCloth(id: String) {
        cloths.remove(id)?.let { releaseClothVbo(it) }
    }

    fun registerTwoBoneIK(
        id: String,
        rootName: String,
        midName: String,
        len1: Float,
        len2: Float,
        flipBend: Boolean
    ) {
        if (id.isEmpty()) return
        ikChains[id] = IkChain(id, rootName, midName, max(1f, len1), max(1f, len2), flipBend)
    }

    fun setIkTarget(id: String, targetX: Float, targetY: Float, active: Boolean) {
        ikChains[id]?.let {
            it.targetX = targetX
            it.targetY = targetY
            it.active = active
        }
    }

    fun removeIk(id: String) {
        ikChains.remove(id)
    }

    fun solveTwoBoneIK(
        root: ZeroEngineView.GameNode,
        mid: ZeroEngineView.GameNode,
        targetX: Float,
        targetY: Float,
        len1: Float,
        len2: Float,
        flipBend: Boolean,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Boolean {
        val rGt = getGlobalTransform(root)
        val dx = targetX - rGt.gx
        val dy = targetY - rGt.gy
        val l1 = max(1f, len1)
        val l2 = max(1f, len2)
        val dist = hypot(dx, dy).coerceIn(abs(l1 - l2) + 0.01f, l1 + l2 - 0.01f)

        val baseAngle = atan2(dy, dx)
        val cosA = ((l1 * l1 + dist * dist - l2 * l2) / (2f * l1 * dist)).coerceIn(-1f, 1f)
        val cosB = ((l1 * l1 + l2 * l2 - dist * dist) / (2f * l1 * l2)).coerceIn(-1f, 1f)
        val bendSign = if (flipBend) -1f else 1f

        val rootWorldRad = baseAngle + bendSign * acos(cosA)
        val midRelRad = bendSign * (Math.PI.toFloat() - acos(cosB))

        val parentRot = rGt.gRot - root.rotation
        root.rotation = Math.toDegrees(rootWorldRad.toDouble()).toFloat() - parentRot
        mid.rotation = Math.toDegrees(midRelRad.toDouble()).toFloat()
        return true
    }

    fun update(
        dt: Float,
        nodes: List<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        waterSystem.update(dt, nodes, getGlobalTransform)
        weatherSystem.update(dt, waterSystem)
        foliageSystem.update(dt, nodes, globalWindX + weatherSystem.currentWindX, getGlobalTransform)
        elapsedTime += dt
        if (cloths.isEmpty() && ikChains.isEmpty()) return

        val nodeMap = HashMap<String, ZeroEngineView.GameNode>(nodes.size)
        nodes.forEach { nodeMap[it.name] = it }

        ikChains.values.forEach { ik ->
            if (!ik.active) return@forEach
            val root = nodeMap[ik.rootName] ?: return@forEach
            val mid = nodeMap[ik.midName] ?: return@forEach
            solveTwoBoneIK(root, mid, ik.targetX, ik.targetY, ik.len1, ik.len2, ik.flipBend, getGlobalTransform)
        }

        val stepDt = dt.coerceIn(0.001f, 0.033f)
        val dtSq = stepDt * stepDt

        cloths.values.forEach { c ->
            val anchor = if (c.anchorNodeName.isNotEmpty()) nodeMap[c.anchorNodeName] else null
            if (anchor != null) {
                val gt = getGlobalTransform(anchor)
                val sx = if (gt.gSx < 0f) -1f else 1f
                c.nodes[0].x = gt.gx + c.offsetX * sx
                c.nodes[0].y = gt.gy + c.offsetY
                c.nodes[0].oldX = c.nodes[0].x
                c.nodes[0].oldY = c.nodes[0].y
            }

            val totalWindX = c.windX + globalWindX + weatherSystem.currentWindX
            val totalGravY = c.gravityY + globalWindY + weatherSystem.currentWindY
            val damping = 0.965f

            for (i in 1 until c.nodes.size) {
                val p = c.nodes[i]
                val vx = (p.x - p.oldX) * damping
                val vy = (p.y - p.oldY) * damping
                p.oldX = p.x
                p.oldY = p.y

                val turb = sin(elapsedTime * 5.2f + i * 0.75f) * c.turbulence
                p.x += vx + (totalWindX + turb) * dtSq
                p.y += vy + totalGravY * dtSq
            }

            repeat(4) {
                for (i in 0 until c.nodes.size - 1) {
                    val p0 = c.nodes[i]
                    val p1 = c.nodes[i + 1]
                    val dx = p1.x - p0.x
                    val dy = p1.y - p0.y
                    val d = hypot(dx, dy)
                    if (d < 0.0001f) continue
                    val diff = (d - c.segmentLen) / d
                    if (i == 0) {
                        p1.x -= dx * diff
                        p1.y -= dy * diff
                    } else {
                        val half = diff * 0.5f
                        p0.x += dx * half; p0.y += dy * half
                        p1.x -= dx * half; p1.y -= dy * half
                    }
                }
            }

            val maxIdx = max(1, c.nodes.size - 1).toFloat()
            for (i in c.nodes.indices) {
                val t = i / maxIdx
                val halfW = (c.widthStart + (c.widthEnd - c.widthStart) * t) * 0.5f
                val off = i * 4
                c.uniformNodes[off] = c.nodes[i].x
                c.uniformNodes[off + 1] = c.nodes[i].y
                c.uniformNodes[off + 2] = halfW
                c.uniformNodes[off + 3] = t
            }
        }
    }

    private fun ensureClothVbo(c: ClothStrip) {
        if (c.vboId != 0) return
        val count = c.nodes.size
        val vCount = count * 2
        val data = FloatArray(vCount * 4)
        val maxIdx = max(1, count - 1).toFloat()
        var off = 0
        for (i in 0 until count) {
            val v = i / maxIdx
            data[off++] = i.toFloat(); data[off++] = -1f; data[off++] = 0f; data[off++] = v
            data[off++] = i.toFloat(); data[off++] = 1f; data[off++] = 1f; data[off++] = v
        }

        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        c.vboId = ids[0]
        c.vertexCount = vCount
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, c.vboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun renderCloths(
        vpMatrix: FloatArray,
        nodes: List<ZeroEngineView.GameNode>,
        fadeAlpha: Float,
        whiteTexId: Int,
        getImageTexture: (String) -> Int,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform,
        onDrawCall: () -> Unit
    ) {
        waterSystem.render(
            vpMatrix, fadeAlpha,
            ZeroGlobalIlluminationSystem.lastSceneTex,
            ZeroGlobalIlluminationSystem.lastLightTex,
            onDrawCall
        )
        foliageSystem.render(
            vpMatrix, fadeAlpha,
            ZeroGlobalIlluminationSystem.lastLightTex,
            onDrawCall
        )

        if (cloths.isNotEmpty() && clothProgram != 0 && fadeAlpha > 0.001f) {
            val nodeMap = HashMap<String, ZeroEngineView.GameNode>(nodes.size)
            nodes.forEach { nodeMap[it.name] = it }

            GLES20.glUseProgram(clothProgram)
            val uVpLoc = GLES20.glGetUniformLocation(clothProgram, "uVP")
            val uZLoc = GLES20.glGetUniformLocation(clothProgram, "uZ")
            val uCountLoc = GLES20.glGetUniformLocation(clothProgram, "uNodeCount")
            val uNodesLoc = GLES20.glGetUniformLocation(clothProgram, "uNodes")
            val uTimeLoc = GLES20.glGetUniformLocation(clothProgram, "uTime")
            val uFlutterLoc = GLES20.glGetUniformLocation(clothProgram, "uFlutterAmp")
            val uColorLoc = GLES20.glGetUniformLocation(clothProgram, "uColor")
            val uTexLoc = GLES20.glGetUniformLocation(clothProgram, "uTexture")

            GLES20.glUniformMatrix4fv(uVpLoc, 1, false, vpMatrix, 0)
            GLES20.glUniform1f(uTimeLoc, elapsedTime)
            GLES20.glEnableVertexAttribArray(0)

            cloths.values.forEach { c ->
                val anchor = if (c.anchorNodeName.isNotEmpty()) nodeMap[c.anchorNodeName] else null
                if (anchor != null && !anchor.visible) return@forEach
                ensureClothVbo(c)
                if (c.vboId == 0 || c.vertexCount < 4) return@forEach

                val z = (anchor?.let { getGlobalTransform(it).gz } ?: 0f) + 2f
                val flutterAmp = (c.turbulence * 0.035f).coerceIn(0f, 14f)

                GLES20.glUniform1f(uZLoc, z)
                GLES20.glUniform1i(uCountLoc, c.nodes.size)
                GLES20.glUniform4fv(uNodesLoc, c.nodes.size, c.uniformNodes, 0)
                GLES20.glUniform1f(uFlutterLoc, flutterAmp)
                GLES20.glUniform4f(uColorLoc, c.r * fadeAlpha, c.g * fadeAlpha, c.b * fadeAlpha, c.a)

                val texId = if (c.texture.isNotEmpty()) getImageTexture(c.texture) else whiteTexId
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glUniform1i(uTexLoc, 0)

                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, c.vboId)
                GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 16, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, c.vertexCount)
                onDrawCall()
            }

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glDisableVertexAttribArray(0)
        }

        weatherSystem.render(fadeAlpha, onDrawCall)
    }

    companion object {
        private const val MAX_CLOTH_NODES = 32

        private val CLOTH_VS = """#version 300 es
            precision highp float;

            layout(location = 0) in vec4 aRibbonData;

            uniform mat4 uVP;
            uniform float uZ;
            uniform int uNodeCount;
            uniform vec4 uNodes[32];
            uniform float uTime;
            uniform float uFlutterAmp;

            out vec2 vTexCoord;
            out float vShade;

            void main() {
                int idx = clamp(int(aRibbonData.x + 0.5), 0, max(0, uNodeCount - 1));
                int prevIdx = max(0, idx - 1);
                int nextIdx = min(max(0, uNodeCount - 1), idx + 1);

                vec4 curr = uNodes[idx];
                vec2 tangent = uNodes[nextIdx].xy - uNodes[prevIdx].xy;
                float tLen = length(tangent);
                vec2 dir = tLen > 0.0001 ? tangent / tLen : vec2(0.0, 1.0);
                vec2 normal = vec2(-dir.y, dir.x);

                float wave = sin(uTime * 11.5 - curr.w * 10.0 + aRibbonData.y * 0.85);
                float flutter = wave * uFlutterAmp * curr.w;
                vec2 worldPos = curr.xy + normal * (aRibbonData.y * curr.z + flutter);

                vTexCoord = aRibbonData.zw;
                vShade = 0.86 + 0.14 * wave * curr.w;
                gl_Position = uVP * vec4(worldPos, uZ, 1.0);
            }
        """.trimIndent()

        private val CLOTH_FS = """#version 300 es
            precision mediump float;

            uniform sampler2D uTexture;
            uniform vec4 uColor;

            in vec2 vTexCoord;
            in float vShade;
            out vec4 fragColor;

            void main() {
                vec4 tex = texture(uTexture, vTexCoord) * uColor;
                if (tex.a < 0.01) discard;
                fragColor = vec4(tex.rgb * vShade, tex.a);
            }
        """.trimIndent()
    }
}
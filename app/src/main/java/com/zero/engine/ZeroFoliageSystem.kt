package com.zero.engine

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * P19-B GLES 3.0 GPU 硬件实例化交互草海系统：
 * - 单次 DrawCall 渲染最多 2500 株/片的动态草叶（自动由后到前 Y 排序）
 * - 自动检测最多 16 个世界角色/物体，走过草丛时草叶实时向两侧弯折
 * - 自动联动全局风场与 P19-A 天气阵风，并采样 lightMapTex 实现实时动态受光
 */
class ZeroFoliageSystem(
    private val onConsoleLog: (String) -> Unit
) {
    data class GrassPatch(
        val id: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val width: Float,
        val height: Float,
        val count: Int,
        val cols: Int,
        val rows: Int,
        val bladeW: Float,
        val bladeH: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val windStrength: Float,
        val bendStrength: Float,
        val seed: Float
    )

    private val patches = ConcurrentHashMap<String, GrassPatch>()
    private val actorUniforms = FloatArray(MAX_ACTORS * 4)
    private var activeActorCount = 0

    private var grassProgram = 0
    private var elapsedTime = 0f
    private var activeWindX = 0f

    // 7 顶点渐变尖端草叶（TRIANGLE_STRIP），x: [-0.5, 0.5], y: [0.0(根部) -> 1.0(叶尖)]
    private val bladeBuf: FloatBuffer = ByteBuffer.allocateDirect(7 * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -0.50f, 0.00f,
                 0.50f, 0.00f,
                -0.38f, 0.36f,
                 0.38f, 0.36f,
                -0.20f, 0.72f,
                 0.20f, 0.72f,
                 0.00f, 1.00f
            ))
            position(0)
        }

    fun initGL() {
        grassProgram = ZeroShaders.buildShaderProgram(GRASS_VS, GRASS_FS, onConsoleLog)
    }

    fun releaseGL() {
        clear()
        if (grassProgram != 0) {
            GLES20.glDeleteProgram(grassProgram)
            grassProgram = 0
        }
    }

    fun clear() {
        patches.clear()
        activeActorCount = 0
    }

    fun createGrassPatch(
        id: String,
        x: Float,
        y: Float,
        z: Float,
        width: Float,
        height: Float,
        count: Int,
        bladeW: Float,
        bladeH: Float,
        r: Float,
        g: Float,
        b: Float,
        windStrength: Float,
        bendStrength: Float
    ): String {
        val key = id.ifEmpty { "grass_${System.nanoTime() % 10000}" }
        val w = max(32f, width)
        val h = max(24f, height)
        val clampedCount = count.coerceIn(16, MAX_BLADES_PER_PATCH)
        val aspect = (w / h).coerceIn(0.25f, 8.0f)
        val cols = max(2, sqrt(clampedCount * aspect).roundToInt())
        val rows = max(1, ceil(clampedCount.toFloat() / cols).toInt())

        patches[key] = GrassPatch(
            id = key,
            x = x, y = y, z = z,
            width = w, height = h,
            count = cols * rows,
            cols = cols, rows = rows,
            bladeW = bladeW.coerceIn(3f, 48f),
            bladeH = bladeH.coerceIn(12f, 160f),
            r = r, g = g, b = b,
            windStrength = windStrength.coerceIn(0f, 3.0f),
            bendStrength = bendStrength.coerceIn(0f, 3.0f),
            seed = ((key.hashCode() and 0xFFFF) / 65535f) * 100f + 1.37f
        )
        return key
    }

    fun removeGrassPatch(id: String) {
        patches.remove(id)
    }

    fun update(
        dt: Float,
        nodes: List<ZeroEngineView.GameNode>,
        totalWindX: Float,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        elapsedTime += dt
        activeWindX = totalWindX
        if (patches.isEmpty()) {
            activeActorCount = 0
            return
        }

        // 收集当前活跃的世界节点作为踩草交互体（最多 16 个）
        var idx = 0
        for (n in nodes) {
            if (idx >= MAX_ACTORS) break
            if (!n.visible || n.layer != "World") continue
            if (n.nodeType == "TileMap" || n.nodeType == "PointLight" || n.nodeType == "ParticleEmitter") continue
            val gt = getGlobalTransform(n)
            val rad = max(36f, max(abs(n.width * gt.gSx), abs(n.height * gt.gSy)) * 0.72f)
            val footY = gt.gy + abs(n.height * gt.gSy) * 0.28f
            val off = idx * 4
            actorUniforms[off] = gt.gx
            actorUniforms[off + 1] = footY
            actorUniforms[off + 2] = rad
            actorUniforms[off + 3] = 1.0f
            idx++
        }
        activeActorCount = idx
    }

    fun render(
        vpMatrix: FloatArray,
        fadeAlpha: Float,
        lightMapTex: Int,
        onDrawCall: () -> Unit
    ) {
        if (patches.isEmpty() || grassProgram == 0 || fadeAlpha <= 0.001f) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(grassProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        val uVpLoc = GLES20.glGetUniformLocation(grassProgram, "uVP")
        val uRectLoc = GLES20.glGetUniformLocation(grassProgram, "uPatchRect")
        val uGridLoc = GLES20.glGetUniformLocation(grassProgram, "uGridInfo")
        val uBladeLoc = GLES20.glGetUniformLocation(grassProgram, "uBladeParams")
        val uColorLoc = GLES20.glGetUniformLocation(grassProgram, "uBaseColor")
        val uTimeLoc = GLES20.glGetUniformLocation(grassProgram, "uTime")
        val uWindLoc = GLES20.glGetUniformLocation(grassProgram, "uWindX")
        val uHasLightLoc = GLES20.glGetUniformLocation(grassProgram, "uHasLight")
        val uActorCntLoc = GLES20.glGetUniformLocation(grassProgram, "uActorCount")
        val uActorsLoc = GLES20.glGetUniformLocation(grassProgram, "uActors")

        GLES20.glUniformMatrix4fv(uVpLoc, 1, false, vpMatrix, 0)
        GLES20.glUniform1f(uTimeLoc, elapsedTime)
        GLES20.glUniform1f(uWindLoc, activeWindX)
        GLES20.glUniform1i(uActorCntLoc, activeActorCount)
        if (activeActorCount > 0) {
            GLES20.glUniform4fv(uActorsLoc, activeActorCount, actorUniforms, 0)
        }

        GLES20.glUniform1i(uHasLightLoc, if (lightMapTex != 0) 1 else 0)
        if (lightMapTex != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lightMapTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(grassProgram, "uLightTex"), 0)
        }

        bladeBuf.position(0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, bladeBuf)

        patches.values.forEach { p ->
            GLES20.glUniform4f(uRectLoc, p.x, p.y, p.width, p.height)
            GLES20.glUniform4f(uGridLoc, p.cols.toFloat(), p.rows.toFloat(), p.z, p.seed)
            GLES20.glUniform4f(uBladeLoc, p.bladeW, p.bladeH, p.windStrength, p.bendStrength)
            GLES20.glUniform4f(uColorLoc, p.r * fadeAlpha, p.g * fadeAlpha, p.b * fadeAlpha, fadeAlpha)
            GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLE_STRIP, 0, 7, p.count)
            onDrawCall()
        }

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    companion object {
        private const val MAX_ACTORS = 16
        private const val MAX_BLADES_PER_PATCH = 2500

        private val GRASS_VS = """#version 300 es
            precision highp float;

            layout(location = 0) in vec2 aBladePos; // x: [-0.5..0.5], y: [0..1]

            uniform mat4 uVP;
            uniform vec4 uPatchRect;   // xy=center, zw=size
            uniform vec4 uGridInfo;    // x=cols, y=rows, z=baseZ, w=seed
            uniform vec4 uBladeParams; // x=bladeW, y=bladeH, z=windStr, w=bendStr
            uniform float uTime;
            uniform float uWindX;
            uniform int uActorCount;
            uniform vec4 uActors[16];  // xy=footPos, z=radius, w=weight

            out float vHeight;
            out float vTint;
            out vec4 vClipPos;

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                float id = float(gl_InstanceID);
                float cols = max(1.0, uGridInfo.x);
                float rows = max(1.0, uGridInfo.y);
                float col = mod(id, cols);
                float row = floor(id / cols);

                float r1 = hash12(vec2(col, row) + uGridInfo.w);
                float r2 = hash12(vec2(row * 1.7 + 3.1, col * 1.3 + 7.9) + uGridInfo.w);
                float r3 = hash12(vec2(id * 0.37, uGridInfo.w));

                // 分层网格 + 随机抖动（保证分布均匀且自带由后到前 Y 排序）
                float nx = (col + 0.12 + r1 * 0.76) / cols - 0.5;
                float ny = (row + 0.12 + r2 * 0.76) / rows - 0.5;
                vec2 rootPos = uPatchRect.xy + vec2(nx * uPatchRect.z, ny * uPatchRect.w);

                float h = aBladePos.y;
                float bendFactor = h * h;
                float bW = uBladeParams.x * (0.75 + 0.55 * r3);
                float bH = uBladeParams.y * (0.68 + 0.68 * r1);
                float lean = (r2 - 0.5) * bH * 0.38;

                // 1. 阵风 + 行进草浪
                float windBias = clamp(uWindX * 0.095, -42.0, 42.0) * uBladeParams.z;
                float gustWave = sin(uTime * 2.8 + rootPos.x * 0.014 + rootPos.y * 0.008 + r3 * 2.5);
                float fineFlutter = sin(uTime * 6.2 + id * 0.7) * 2.4;
                float swayX = (windBias + gustWave * (11.0 + abs(windBias) * 0.35) + fineFlutter) * uBladeParams.z;

                // 2. 角色/物体踩踏向两侧弯折
                vec2 pushOffset = vec2(0.0);
                float squash = 0.0;
                if (uBladeParams.w > 0.001) {
                    for (int i = 0; i < 16; i++) {
                        if (i >= uActorCount) break;
                        vec4 act = uActors[i];
                        vec2 diff = rootPos - act.xy;
                        float dist = length(diff);
                        float rad = max(12.0, act.z);
                        if (dist < rad) {
                            float t = 1.0 - dist / rad;
                            float str = t * t * uBladeParams.w * act.w;
                            vec2 dir = dist > 0.5 ? diff / dist : vec2(r1 > 0.5 ? 1.0 : -1.0, 0.2);
                            pushOffset += dir * (str * bH * 0.92);
                            squash = max(squash, str * 0.58);
                        }
                    }
                }

                float dx = aBladePos.x * bW + (lean + swayX + pushOffset.x) * bendFactor;
                float dy = -h * bH * (1.0 - squash * bendFactor) + pushOffset.y * bendFactor * 0.35;
                vec2 worldPos = rootPos + vec2(dx, dy);
                float worldZ = uGridInfo.z + h * 8.0;

                vec4 clip = uVP * vec4(worldPos, worldZ, 1.0);
                vHeight = h;
                vTint = 0.80 + 0.38 * r3;
                vClipPos = clip;
                gl_Position = clip;
            }
        """.trimIndent()

        private val GRASS_FS = """#version 300 es
            precision mediump float;

            uniform sampler2D uLightTex;
            uniform int uHasLight;
            uniform vec4 uBaseColor;

            in float vHeight;
            in float vTint;
            in vec4 vClipPos;
            out vec4 fragColor;

            void main() {
                // 根部压暗 AO，叶尖偏暖亮
                vec3 rootCol = uBaseColor.rgb * 0.36;
                vec3 tipCol = (uBaseColor.rgb * 1.25 + vec3(0.08, 0.12, 0.02)) * vTint;
                vec3 col = mix(rootCol, tipCol, smoothstep(0.0, 0.92, vHeight));

                if (uHasLight != 0) {
                    vec2 screenUv = clamp((vClipPos.xy / max(0.0001, vClipPos.w)) * 0.5 + 0.5, 0.0, 1.0);
                    vec3 lightMul = max(vec3(0.24), texture(uLightTex, screenUv).rgb);
                    col *= lightMul;
                }

                fragColor = vec4(col, uBaseColor.a);
            }
        """.trimIndent()
    }
}
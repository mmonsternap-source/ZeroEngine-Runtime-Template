package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * P18-C + P19-A GLES 3.0 2D/2.5D 交互式水面系统：
 * - 实时屏幕空间倒影（沿水面岸线镜像翻转 + 法线波纹折射）
 * - 程序化水底焦散 + 动态光源着色
 * - 最多 16 道并发物理扩散涟漪 + 节点踩水/雨滴自动触发波纹
 */
class ZeroWaterSystem(
    private val onConsoleLog: (String) -> Unit
) {
    data class WaterZone(
        val id: String,
        var x: Float,
        var y: Float,
        var z: Float,
        var width: Float,
        var height: Float,
        var r: Float,
        var g: Float,
        var b: Float,
        var a: Float,
        var reflectStrength: Float,
        var waveScale: Float,
        var waveSpeed: Float,
        var causticStrength: Float,
        var autoSplash: Boolean
    )

    private data class Ripple(
        val x: Float,
        val y: Float,
        var age: Float,
        val maxAge: Float,
        val maxRadius: Float,
        val strength: Float
    )

    private val zones = ConcurrentHashMap<String, WaterZone>()
    val activeZones: Collection<WaterZone> get() = zones.values

    private val ripples = mutableListOf<Ripple>()
    private val rippleUniforms = FloatArray(MAX_RIPPLES * 4)
    private val nodeLastPos = HashMap<Int, Pair<Float, Float>>()

    private var waterProgram = 0
    private var elapsedTime = 0f

    private val quadBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -0.5f, -0.5f, 0f, 0f,
                 0.5f, -0.5f, 1f, 0f,
                -0.5f,  0.5f, 0f, 1f,
                 0.5f,  0.5f, 1f, 1f
            ))
            position(0)
        }

    fun initGL() {
        waterProgram = ZeroShaders.buildShaderProgram(WATER_VS, WATER_FS, onConsoleLog)
    }

    fun releaseGL() {
        clear()
        if (waterProgram != 0) {
            GLES20.glDeleteProgram(waterProgram)
            waterProgram = 0
        }
    }

    fun clear() {
        zones.clear()
        ripples.clear()
        nodeLastPos.clear()
    }

    fun createWater(
        id: String,
        x: Float,
        y: Float,
        z: Float,
        width: Float,
        height: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        reflectStrength: Float,
        waveScale: Float,
        waveSpeed: Float,
        causticStrength: Float,
        autoSplash: Boolean
    ): String {
        val key = id.ifEmpty { "water_${System.nanoTime() % 10000}" }
        zones[key] = WaterZone(
            id = key,
            x = x, y = y, z = z,
            width = max(16f, width),
            height = max(16f, height),
            r = r, g = g, b = b, a = a.coerceIn(0.05f, 1f),
            reflectStrength = reflectStrength.coerceIn(0f, 1.5f),
            waveScale = waveScale.coerceIn(0.2f, 4f),
            waveSpeed = waveSpeed.coerceIn(0f, 5f),
            causticStrength = causticStrength.coerceIn(0f, 1.5f),
            autoSplash = autoSplash
        )
        return key
    }

    fun removeWater(id: String) {
        zones.remove(id)
    }

    fun addRipple(x: Float, y: Float, maxRadius: Float = 150f, strength: Float = 1.0f, duration: Float = 1.3f) {
        if (ripples.size >= MAX_RIPPLES) ripples.removeAt(0)
        ripples.add(
            Ripple(
                x = x,
                y = y,
                age = 0f,
                maxAge = duration.coerceIn(0.2f, 4.0f),
                maxRadius = maxRadius.coerceIn(20f, 600f),
                strength = strength.coerceIn(0.1f, 3.0f)
            )
        )
    }

    fun update(
        dt: Float,
        nodes: List<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        elapsedTime += dt
        if (zones.isEmpty() && ripples.isEmpty()) return

        val it = ripples.iterator()
        while (it.hasNext()) {
            val rp = it.next()
            rp.age += dt
            if (rp.age >= rp.maxAge) it.remove()
        }

        if (zones.values.any { it.autoSplash }) {
            for (n in nodes) {
                if (!n.visible || n.layer != "World") continue
                if (n.nodeType == "TileMap" || n.nodeType == "PointLight" || n.nodeType == "ParticleEmitter") continue
                val gt = getGlobalTransform(n)
                val prev = nodeLastPos[n.nodeId]
                if (prev == null) {
                    nodeLastPos[n.nodeId] = gt.gx to gt.gy
                    continue
                }
                val moved = hypot(gt.gx - prev.first, gt.gy - prev.second)
                if (moved >= 22f) {
                    val footY = gt.gy + abs(n.height * gt.gSy) * 0.25f
                    val inWater = zones.values.any { w ->
                        w.autoSplash &&
                            abs(gt.gx - w.x) <= w.width * 0.5f &&
                            abs(footY - w.y) <= w.height * 0.5f
                    }
                    if (inWater) {
                        addRipple(gt.gx, footY, maxRadius = 115f, strength = 0.85f, duration = 1.05f)
                        nodeLastPos[n.nodeId] = gt.gx to gt.gy
                    } else if (moved >= 40f) {
                        nodeLastPos[n.nodeId] = gt.gx to gt.gy
                    }
                }
            }
        }
    }

    fun render(
        vpMatrix: FloatArray,
        fadeAlpha: Float,
        sceneColorTex: Int,
        lightMapTex: Int,
        onDrawCall: () -> Unit
    ) {
        if (zones.isEmpty() || waterProgram == 0 || fadeAlpha <= 0.001f) return

        val activeCount = min(ripples.size, MAX_RIPPLES)
        for (i in 0 until activeCount) {
            val rp = ripples[i]
            val p = (rp.age / rp.maxAge).coerceIn(0f, 1f)
            val curRad = rp.maxRadius * sqrt(p)
            val amp = rp.strength * (1f - p) * (1f - p)
            val off = i * 4
            rippleUniforms[off] = rp.x
            rippleUniforms[off + 1] = rp.y
            rippleUniforms[off + 2] = curRad
            rippleUniforms[off + 3] = amp
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(waterProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        val uVpLoc = GLES20.glGetUniformLocation(waterProgram, "uVP")
        val uRectLoc = GLES20.glGetUniformLocation(waterProgram, "uWaterRect")
        val uZLoc = GLES20.glGetUniformLocation(waterProgram, "uZ")
        val uColorLoc = GLES20.glGetUniformLocation(waterProgram, "uColor")
        val uParamsLoc = GLES20.glGetUniformLocation(waterProgram, "uParams")
        val uTimeLoc = GLES20.glGetUniformLocation(waterProgram, "uTime")
        val uHasSceneLoc = GLES20.glGetUniformLocation(waterProgram, "uHasScene")
        val uHasLightLoc = GLES20.glGetUniformLocation(waterProgram, "uHasLight")
        val uRippleCntLoc = GLES20.glGetUniformLocation(waterProgram, "uRippleCount")
        val uRipplesLoc = GLES20.glGetUniformLocation(waterProgram, "uRipples")

        GLES20.glUniformMatrix4fv(uVpLoc, 1, false, vpMatrix, 0)
        GLES20.glUniform1f(uTimeLoc, elapsedTime)
        GLES20.glUniform1i(uRippleCntLoc, activeCount)
        if (activeCount > 0) {
            GLES20.glUniform4fv(uRipplesLoc, activeCount, rippleUniforms, 0)
        }

        GLES20.glUniform1i(uHasSceneLoc, if (sceneColorTex != 0) 1 else 0)
        if (sceneColorTex != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneColorTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(waterProgram, "uSceneTex"), 0)
        }

        GLES20.glUniform1i(uHasLightLoc, if (lightMapTex != 0) 1 else 0)
        if (lightMapTex != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lightMapTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(waterProgram, "uLightTex"), 1)
        }

        quadBuf.position(0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
        quadBuf.position(2)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quadBuf)

        zones.values.forEach { w ->
            GLES20.glUniform4f(uRectLoc, w.x, w.y, w.width, w.height)
            GLES20.glUniform1f(uZLoc, w.z)
            GLES20.glUniform4f(uColorLoc, w.r * fadeAlpha, w.g * fadeAlpha, w.b * fadeAlpha, w.a * fadeAlpha)
            GLES20.glUniform4f(uParamsLoc, w.reflectStrength, w.waveScale, w.waveSpeed, w.causticStrength)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            onDrawCall()
        }

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    companion object {
        private const val MAX_RIPPLES = 16

        private val WATER_VS = """#version 300 es
            precision highp float;

            layout(location = 0) in vec2 aLocalPos;
            layout(location = 1) in vec2 aUv;

            uniform mat4 uVP;
            uniform vec4 uWaterRect;
            uniform float uZ;

            out vec2 vUv;
            out vec2 vWorldPos;
            out vec4 vClipPos;
            out float vTopScreenY;

            void main() {
                vec2 worldPos = uWaterRect.xy + aLocalPos * uWaterRect.zw;
                vec4 clip = uVP * vec4(worldPos, uZ, 1.0);

                vec2 topWorld = vec2(worldPos.x, uWaterRect.y - uWaterRect.w * 0.5);
                vec4 topClip = uVP * vec4(topWorld, uZ, 1.0);
                vTopScreenY = (topClip.y / max(0.0001, topClip.w)) * 0.5 + 0.5;

                vUv = aUv;
                vWorldPos = worldPos;
                vClipPos = clip;
                gl_Position = clip;
            }
        """.trimIndent()

        private val WATER_FS = """#version 300 es
            precision highp float;

            uniform sampler2D uSceneTex;
            uniform sampler2D uLightTex;
            uniform int uHasScene;
            uniform int uHasLight;
            uniform vec4 uColor;
            uniform vec4 uParams;
            uniform float uTime;
            uniform int uRippleCount;
            uniform vec4 uRipples[16];

            in vec2 vUv;
            in vec2 vWorldPos;
            in vec4 vClipPos;
            in float vTopScreenY;

            out vec4 fragColor;

            void main() {
                vec2 screenUv = (vClipPos.xy / max(0.0001, vClipPos.w)) * 0.5 + 0.5;
                float t = uTime * uParams.z;
                float ws = uParams.y;

                float w1 = sin(vWorldPos.x * 0.045 * ws + t * 2.4 + cos(vWorldPos.y * 0.03 * ws - t * 1.5));
                float w2 = cos(vWorldPos.y * 0.065 * ws - t * 2.9 + sin(vWorldPos.x * 0.025 * ws + t));
                vec2 distort = vec2(w1, w2) * 0.0045;

                float rippleSpec = 0.0;
                for (int i = 0; i < 16; i++) {
                    if (i >= uRippleCount) break;
                    vec4 rp = uRipples[i];
                    vec2 diff = vWorldPos - rp.xy;
                    float dist = length(diff);
                    float delta = dist - rp.z;
                    float band = exp(-abs(delta) * 0.085);
                    if (band > 0.01) {
                        float wave = sin(delta * 0.32) * band * rp.w;
                        vec2 dir = dist > 0.01 ? diff / dist : vec2(0.0, 1.0);
                        distort += dir * wave * 0.018;
                        rippleSpec += max(0.0, wave) * 0.65;
                    }
                }

                vec3 reflColor = uColor.rgb * 0.65;
                float reflFactor = 0.0;
                if (uHasScene != 0 && uParams.x > 0.001) {
                    float mirrorY = vTopScreenY + (vTopScreenY - screenUv.y) * 0.92;
                    vec2 reflUv = vec2(screenUv.x, mirrorY) + distort;
                    if (reflUv.x > 0.01 && reflUv.x < 0.99 && reflUv.y > 0.01 && reflUv.y < 0.99) {
                        vec3 sCol = texture(uSceneTex, reflUv).rgb;
                        float edgeFade = smoothstep(0.0, 0.12, reflUv.y) * smoothstep(1.0, 0.88, reflUv.y);
                        reflColor = sCol;
                        reflFactor = uParams.x * edgeFade * (0.38 + 0.42 * (1.0 - vUv.y));
                    }
                }

                float c1 = sin(vWorldPos.x * 0.055 * ws + t * 1.9 + sin(vWorldPos.y * 0.04 * ws - t * 1.3));
                float c2 = cos(vWorldPos.y * 0.055 * ws - t * 1.6 + cos(vWorldPos.x * 0.045 * ws + t * 1.1));
                float caustic = pow(clamp((c1 * c2) * 0.5 + 0.5, 0.0, 1.0), 4.0) * uParams.w;

                vec3 lightMul = vec3(1.0);
                if (uHasLight != 0) {
                    lightMul = max(vec3(0.22), texture(uLightTex, clamp(screenUv + distort * 0.5, 0.0, 1.0)).rgb);
                }

                float shoreEdge = (1.0 - smoothstep(0.0, 0.06, vUv.y)) * 0.28;
                vec3 baseWater = mix(uColor.rgb * 1.15, uColor.rgb * 0.55, vUv.y);
                vec3 finalRgb = mix(baseWater, reflColor, clamp(reflFactor, 0.0, 0.82));
                finalRgb += vec3(0.45, 0.82, 1.0) * (caustic * 0.45 + rippleSpec + shoreEdge);
                finalRgb *= lightMul;

                float edgeAlpha = smoothstep(0.0, 0.04, vUv.x) * smoothstep(1.0, 0.96, vUv.x) *
                                  smoothstep(0.0, 0.05, vUv.y) * smoothstep(1.0, 0.95, vUv.y);
                fragColor = vec4(finalRgb, uColor.a * edgeAlpha);
            }
        """.trimIndent()
    }
}
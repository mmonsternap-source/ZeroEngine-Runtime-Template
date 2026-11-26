package com.zero.engine

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*
import kotlin.random.Random

/**
 * P19-A GLES 3.0 全局联动天气系统：
 * - 单次 DrawCall 硬件实例化渲染雨丝 / 飞雪 / 落叶（带 3D 景深视差）
 * - 自动向 P18-C 水面系统注入雨滴物理涟漪
 * - 自动向 P17 布料系统输出动态阵风风力
 * - 支持自动/手动触发雷电双脉冲闪光与程序化闪电裂纹
 */
class ZeroWeatherSystem(
    private val onConsoleLog: (String) -> Unit
) {
    // 0=none, 1=rain, 2=snow, 3=leaves
    var weatherType: Int = 0
        private set
    var intensity: Float = 0.0f
    var baseWindX: Float = -180f
    var lightningEnabled: Boolean = false
    var splashEnabled: Boolean = true

    var currentWindX: Float = 0f
        private set
    var currentWindY: Float = 0f
        private set

    private var particleProgram = 0
    private var flashProgram = 0
    private var elapsedTime = 0f

    // 雷电状态
    private var lightningTimer = 3.5f
    private var flashAge = 99f
    private var boltSeed = 0.5f

    // 雨滴落水计时
    private var splashAccum = 0f

    private val quadBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f))
            position(0)
        }

    private val fullQuadBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

    fun initGL() {
        particleProgram = ZeroShaders.buildShaderProgram(WEATHER_VS, WEATHER_FS, onConsoleLog)
        flashProgram = ZeroShaders.buildShaderProgram(FLASH_VS, FLASH_FS, onConsoleLog)
    }

    fun releaseGL() {
        clear()
        if (particleProgram != 0) { GLES20.glDeleteProgram(particleProgram); particleProgram = 0 }
        if (flashProgram != 0) { GLES20.glDeleteProgram(flashProgram); flashProgram = 0 }
    }

    fun clear() {
        weatherType = 0
        intensity = 0f
        lightningEnabled = false
        currentWindX = 0f
        currentWindY = 0f
        flashAge = 99f
        splashAccum = 0f
    }

    fun setWeather(
        typeName: String,
        intensityVal: Float,
        windX: Float,
        lightning: Boolean,
        splash: Boolean
    ) {
        weatherType = when (typeName.lowercase()) {
            "rain", "storm" -> 1
            "snow" -> 2
            "leaves", "leaf", "sakura" -> 3
            else -> 0
        }
        intensity = if (weatherType == 0) 0f else intensityVal.coerceIn(0f, 1.5f)
        baseWindX = windX.coerceIn(-900f, 900f)
        lightningEnabled = lightning && weatherType == 1
        splashEnabled = splash
        if (lightningEnabled) {
            lightningTimer = Random.nextFloat() * 2.5f + 1.5f
        }
        if (weatherType == 0) {
            currentWindX = 0f
            currentWindY = 0f
        }
    }

    fun triggerLightning() {
        flashAge = 0f
        boltSeed = Random.nextFloat() * 0.72f + 0.14f
    }

    fun update(dt: Float, waterSystem: ZeroWaterSystem) {
        elapsedTime += dt
        if (flashAge < 2.0f) flashAge += dt

        if (weatherType == 0 || intensity <= 0.01f) {
            currentWindX = 0f
            currentWindY = 0f
            return
        }

        // 1. 计算带阵风起伏的实时风力（同步吹动 P17 布料与披风）
        val gust = 1.0f + 0.45f * sin(elapsedTime * 1.15f) + 0.25f * sin(elapsedTime * 2.9f)
        currentWindX = baseWindX * gust * intensity.coerceAtMost(1.2f)
        currentWindY = if (weatherType == 1) 45f * intensity else 0f

        // 2. 自动雷电倒计时
        if (lightningEnabled) {
            lightningTimer -= dt
            if (lightningTimer <= 0f) {
                triggerLightning()
                val baseInterval = (6.5f - intensity * 2.5f).coerceIn(2.0f, 6.5f)
                lightningTimer = baseInterval + Random.nextFloat() * 3.5f
            }
        }

        // 3. 雨天自动向 P18-C 水池注入雨滴涟漪
        if (weatherType == 1 && splashEnabled) {
            val zones = waterSystem.activeZones
            if (zones.isNotEmpty()) {
                val rate = (6f + intensity * 12f).coerceIn(2f, 20f)
                splashAccum += dt * rate
                val zoneList = zones.toList()
                while (splashAccum >= 1f) {
                    splashAccum -= 1f
                    val z = zoneList[Random.nextInt(zoneList.size)]
                    val rx = z.x + (Random.nextFloat() - 0.5f) * z.width * 0.92f
                    val ry = z.y + (Random.nextFloat() - 0.5f) * z.height * 0.92f
                    waterSystem.addRipple(
                        x = rx,
                        y = ry,
                        maxRadius = 48f + Random.nextFloat() * 46f,
                        strength = 0.55f + Random.nextFloat() * 0.45f,
                        duration = 0.65f + Random.nextFloat() * 0.4f
                    )
                }
            }
        }
    }

    fun render(fadeAlpha: Float, onDrawCall: () -> Unit) {
        if (fadeAlpha <= 0.001f) return
        val hasParticles = weatherType != 0 && intensity > 0.01f && particleProgram != 0
        val hasFlash = flashAge < 0.75f && flashProgram != 0
        if (!hasParticles && !hasFlash) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // 1. 硬件实例化渲染雨 / 雪 / 落叶（单次 DrawCall）
        if (hasParticles) {
            val maxCount = when (weatherType) {
                1 -> 520
                2 -> 360
                else -> 180
            }
            val count = (maxCount * intensity.coerceIn(0.05f, 1.3f)).toInt().coerceIn(16, 650)

            GLES20.glBlendFunc(
                GLES20.GL_SRC_ALPHA,
                if (weatherType == 3) GLES20.GL_ONE_MINUS_SRC_ALPHA else GLES20.GL_ONE
            )
            GLES20.glUseProgram(particleProgram)

            val windNdc = (currentWindX / 450f).coerceIn(-1.6f, 1.6f)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(particleProgram, "uType"), weatherType)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uTime"), elapsedTime)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uWind"), windNdc)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(particleProgram, "uAlpha"), fadeAlpha * min(1f, 0.45f + intensity * 0.55f))

            quadBuf.position(0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, quadBuf)

            GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLE_STRIP, 0, 4, count)
            onDrawCall()
            GLES20.glDisableVertexAttribArray(0)
        }

        // 2. 雷电双闪 + 程序化锯齿闪电
        if (hasFlash) {
            val pulse1 = exp(-flashAge * 9.5f)
            val pulse2 = exp(-abs(flashAge - 0.14f) * 26f) * 0.72f
            val flashVal = ((pulse1 + pulse2) * 0.65f * fadeAlpha).coerceIn(0f, 0.85f)
            if (flashVal > 0.008f) {
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                GLES20.glUseProgram(flashProgram)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(flashProgram, "uFlash"), flashVal)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(flashProgram, "uBoltX"), boltSeed)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(flashProgram, "uBoltVis"), exp(-flashAge * 12f))

                fullQuadBuf.position(0)
                GLES20.glEnableVertexAttribArray(0)
                GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, fullQuadBuf)
                fullQuadBuf.position(2)
                GLES20.glEnableVertexAttribArray(1)
                GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, fullQuadBuf)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                onDrawCall()
                GLES20.glDisableVertexAttribArray(0)
                GLES20.glDisableVertexAttribArray(1)
            }
        }

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    companion object {
        private val WEATHER_VS = """#version 300 es
            precision highp float;

            layout(location = 0) in vec2 aLocalPos;

            uniform int uType;
            uniform float uTime;
            uniform float uWind;
            uniform float uAlpha;

            out vec2 vLocal;
            out vec4 vColor;

            float hash11(float p) {
                p = fract(p * 0.1031);
                p *= p + 33.33;
                p *= p + p;
                return fract(p);
            }

            void main() {
                float id = float(gl_InstanceID) + 1.0;
                float r1 = hash11(id * 1.13);
                float r2 = hash11(id * 2.37 + 19.1);
                float r3 = hash11(id * 3.71 + 47.3);
                float depth = 0.38 + 0.62 * r3;

                vec2 center;
                vec2 size;
                float angle = 0.0;
                vec3 rgb = vec3(0.8);
                float a = uAlpha * depth;

                if (uType == 1) {
                    // 暴雨：高速斜落细雨丝
                    float spd = (1.65 + 1.25 * r2) * depth;
                    float y = 1.15 - fract(r2 + uTime * spd) * 2.30;
                    float x = fract(r1 + uTime * uWind * 0.42 * depth) * 2.30 - 1.15;
                    center = vec2(x, y);
                    size = vec2(0.0042 * depth, (0.065 + 0.045 * r3) * depth);
                    angle = atan(uWind * 0.42, -spd) + 1.570796;
                    rgb = vec3(0.62, 0.82, 1.0);
                    a *= 0.62;
                } else if (uType == 2) {
                    // 飞雪：柔光缓慢飘落 + 左右摇曳
                    float spd = (0.14 + 0.16 * r2) * depth;
                    float y = 1.12 - fract(r2 + uTime * spd) * 2.24;
                    float sway = sin(uTime * (1.8 + r1 * 2.0) + id) * 0.045 * depth;
                    float x = fract(r1 + uTime * uWind * 0.14 * depth) * 2.24 - 1.12 + sway;
                    center = vec2(x, y);
                    float sz = (0.011 + 0.014 * r3) * depth;
                    size = vec2(sz, sz * 0.56);
                    rgb = vec3(0.88, 0.95, 1.0);
                    a *= 0.85;
                } else {
                    // 落叶/花瓣：翻滚漂移
                    float spd = (0.20 + 0.22 * r2) * depth;
                    float y = 1.12 - fract(r2 + uTime * spd) * 2.24;
                    float sway = sin(uTime * (2.2 + r1 * 2.4) + id * 1.7) * 0.07 * depth;
                    float x = fract(r1 + uTime * uWind * 0.24 * depth) * 2.24 - 1.12 + sway;
                    center = vec2(x, y);
                    float tumble = 0.45 + 0.55 * abs(sin(uTime * 3.5 + id));
                    size = vec2(0.022 * depth, 0.011 * depth * tumble);
                    angle = uTime * (1.5 + r2 * 2.5) + id;
                    rgb = mix(vec3(0.95, 0.42, 0.16), vec3(0.98, 0.76, 0.22), r1);
                    a *= 0.92;
                }

                float c = cos(angle);
                float s = sin(angle);
                vec2 p = aLocalPos * size;
                vec2 rotP = vec2(p.x * c - p.y * s, p.x * s + p.y * c);

                vLocal = aLocalPos * 2.0;
                vColor = vec4(rgb, a);
                gl_Position = vec4(center + rotP, 0.0, 1.0);
            }
        """.trimIndent()

        private val WEATHER_FS = """#version 300 es
            precision mediump float;

            uniform int uType;
            in vec2 vLocal;
            in vec4 vColor;
            out vec4 fragColor;

            void main() {
                float alpha = vColor.a;
                if (uType == 1) {
                    float d = 1.0 - abs(vLocal.x);
                    float tip = 1.0 - abs(vLocal.y);
                    alpha *= smoothstep(0.0, 0.85, d) * smoothstep(0.0, 0.5, tip);
                } else if (uType == 2) {
                    float dist = length(vLocal);
                    if (dist > 1.0) discard;
                    alpha *= smoothstep(1.0, 0.15, dist);
                } else {
                    vec2 q = vLocal * vec2(1.0, 1.35);
                    float dist = length(q);
                    if (dist > 1.0) discard;
                    alpha *= smoothstep(1.0, 0.55, dist);
                }
                if (alpha < 0.01) discard;
                fragColor = vec4(vColor.rgb, alpha);
            }
        """.trimIndent()

        private val FLASH_VS = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vUv;
            void main() {
                vUv = aTexCoord;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        private val FLASH_FS = """#version 300 es
            precision highp float;

            uniform float uFlash;
            uniform float uBoltX;
            uniform float uBoltVis;

            in vec2 vUv;
            out vec4 fragColor;

            void main() {
                // 全局闪电冷白闪光
                vec3 skyFlash = vec3(0.55, 0.72, 1.0) * uFlash * 0.55;

                // 顶部锯齿状闪电主干
                float jag = sin(vUv.y * 28.0 + uBoltX * 40.0) * 0.025 +
                            sin(vUv.y * 71.0 - uBoltX * 19.0) * 0.011;
                float dist = abs(vUv.x - (uBoltX + jag));
                float heightMask = smoothstep(0.32, 0.55, vUv.y);
                float core = exp(-dist * 150.0) * 1.4 + exp(-dist * 32.0) * 0.45;
                vec3 bolt = vec3(0.82, 0.92, 1.0) * core * heightMask * uBoltVis;

                fragColor = vec4(skyFlash + bolt, 1.0);
            }
        """.trimIndent()
    }
}
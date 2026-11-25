package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * P15 次世代 2D 光影系统（OpenGL ES 3.0）：
 * 1. 全像素级 JFA (Jump Flood Algorithm) 距离场：支持任意透明轮廓 PNG、斜坡与整张 TileMap，无遮挡数量上限
 * 2. 2-Cascade Radiance Gathering（双级联辐射采集）+ SDF 球体步进：光线遇墙自动反弹并截断，彻底杜绝穿墙漏光
 * 3. 内置 2D SDF 环境光遮蔽（AO）与分离高斯降噪
 */
class ZeroGlobalIlluminationSystem(
    private val onConsoleLog: (String) -> Unit
) {
    var enabled: Boolean = true
    var jfaEnabled: Boolean = true
    var intensity: Float = 0.72f
    var radius: Float = 0.22f       // 屏幕空间最大光线反弹扩散半径 (0.04 ~ 0.45)
    var threshold: Float = 0.18f    // 触发光线反弹的亮度阈值
    var saturation: Float = 1.40f   // 间接光溢色饱和度增强
    var aoStrength: Float = 0.65f   // 2D SDF 墙角接触暗部强度 (0 ~ 1)
    var skyR: Float = 0.04f
    var skyG: Float = 0.06f
    var skyB: Float = 0.10f

    private var seedProgram = 0
    private var jfaProgram = 0
    private var sdfProgram = 0
    private var rcGiProgram = 0
    private var blurProgram = 0
    private var compositeProgram = 0

    // 1/4 分辨率管线 FBO
    private var occluderFbo = 0
    private var occluderTex = 0
    private var jfaFboA = 0
    private var jfaTexA = 0
    private var jfaFboB = 0
    private var jfaTexB = 0
    private var sdfFbo = 0
    private var sdfTex = 0
    private var giFboA = 0
    private var giTexA = 0
    private var giFboB = 0
    private var giTexB = 0

    private var giW = 270
    private var giH = 480
    private var fboReady = false
    private var sdfBuiltThisFrame = false

    val isReady: Boolean get() = enabled && fboReady
    val isJfaReady: Boolean get() = enabled && jfaEnabled && fboReady && seedProgram != 0 && jfaProgram != 0 && sdfProgram != 0

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            ))
            position(0)
        }

    fun initGL() {
        seedProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, JFA_SEED_FS, onConsoleLog)
        jfaProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, JFA_FLOOD_FS, onConsoleLog)
        sdfProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, JFA_SDF_FS, onConsoleLog)
        rcGiProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, RC_GI_FS, onConsoleLog)
        blurProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, GI_BLUR_FS, onConsoleLog)
        compositeProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, GI_COMPOSITE_FS, onConsoleLog)
    }

    fun resize(surfaceW: Int, surfaceH: Int) {
        releaseFbos()
        giW = max(32, surfaceW / 4)
        giH = max(32, surfaceH / 4)

        val (of, ot) = createFbo(giW, giH, GLES20.GL_NEAREST)
        val (ja, jta) = createFbo(giW, giH, GLES20.GL_NEAREST)
        val (jb, jtb) = createFbo(giW, giH, GLES20.GL_NEAREST)
        val (sf, st) = createFbo(giW, giH, GLES20.GL_LINEAR)
        val (ga, gta) = createFbo(giW, giH, GLES20.GL_LINEAR)
        val (gb, gtb) = createFbo(giW, giH, GLES20.GL_LINEAR)

        if (of != 0 && ja != 0 && jb != 0 && sf != 0 && ga != 0 && gb != 0) {
            occluderFbo = of; occluderTex = ot
            jfaFboA = ja; jfaTexA = jta
            jfaFboB = jb; jfaTexB = jtb
            sdfFbo = sf; sdfTex = st
            giFboA = ga; giTexA = gta
            giFboB = gb; giTexB = gtb
            fboReady = (rcGiProgram != 0 && blurProgram != 0 && compositeProgram != 0)
        } else {
            releaseFbos()
        }
    }

    private fun createFbo(w: Int, h: Int, filter: Int): Pair<Int, Int> {
        val fbos = IntArray(1)
        val texs = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        GLES20.glGenTextures(1, texs, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texs[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) fbos[0] to texs[0] else {
            GLES20.glDeleteFramebuffers(1, fbos, 0)
            GLES20.glDeleteTextures(1, texs, 0)
            0 to 0
        }
    }

    private fun releaseFbos() {
        fboReady = false
        sdfBuiltThisFrame = false
        val texs = listOf(occluderTex, jfaTexA, jfaTexB, sdfTex, giTexA, giTexB).filter { it != 0 }.toIntArray()
        val fbos = listOf(occluderFbo, jfaFboA, jfaFboB, sdfFbo, giFboA, giFboB).filter { it != 0 }.toIntArray()
        if (texs.isNotEmpty()) GLES20.glDeleteTextures(texs.size, texs, 0)
        if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        occluderFbo = 0; occluderTex = 0
        jfaFboA = 0; jfaTexA = 0; jfaFboB = 0; jfaTexB = 0
        sdfFbo = 0; sdfTex = 0
        giFboA = 0; giTexA = 0; giFboB = 0; giTexB = 0
    }

    fun releaseGL() {
        releaseFbos()
        val progs = listOf(seedProgram, jfaProgram, sdfProgram, rcGiProgram, blurProgram, compositeProgram).filter { it != 0 }
        progs.forEach { GLES20.glDeleteProgram(it) }
        seedProgram = 0; jfaProgram = 0; sdfProgram = 0
        rcGiProgram = 0; blurProgram = 0; compositeProgram = 0
    }

    /**
     * 绑定 1/4 分辨率遮挡物 FBO，清空为全透明，准备光栅化所有 shadowCaster 节点
     */
    fun beginOccluderPass(): Boolean {
        sdfBuiltThisFrame = false
        if (!isJfaReady) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, occluderFbo)
        GLES20.glViewport(0, 0, giW, giH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        return true
    }

    /**
     * 执行 JFA 种子提取 -> 8 趟 Jump Flood -> 全屏精确距离场生成
     */
    fun buildJfaDistanceField(onDrawCall: () -> Unit) {
        if (!isJfaReady) return
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glViewport(0, 0, giW, giH)

        // 1. Seed Pass: occluderTex -> jfaFboA
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, jfaFboA)
        GLES20.glUseProgram(seedProgram)
        bindQuadAttribs()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, occluderTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(seedProgram, "uOccluderTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        onDrawCall()

        // 2. Jump Flood 8 趟（覆盖最大 256px 半径，足以横跨 1/4 分辨率整屏）
        val maxDim = max(giW, giH)
        var step = 1
        while (step * 2 < maxDim) step *= 2
        if (step > 128) step = 128

        val aspectX = giW.toFloat() / max(1, giH).toFloat()
        var srcTex = jfaTexA
        var dstFbo = jfaFboB
        var dstTex = jfaTexB
        var srcFbo = jfaFboA

        GLES20.glUseProgram(jfaProgram)
        val uTexLoc = GLES20.glGetUniformLocation(jfaProgram, "uJfaTex")
        val uTexelLoc = GLES20.glGetUniformLocation(jfaProgram, "uTexelSize")
        val uStepLoc = GLES20.glGetUniformLocation(jfaProgram, "uStep")
        val uAspectLoc = GLES20.glGetUniformLocation(jfaProgram, "uAspect")
        GLES20.glUniform2f(uTexelLoc, 1f / giW, 1f / giH)
        GLES20.glUniform2f(uAspectLoc, aspectX, 1f)

        while (step >= 1) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            GLES20.glUniform1i(uTexLoc, 0)
            GLES20.glUniform1f(uStepLoc, step.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            onDrawCall()

            // Ping-Pong 交换
            val tmpF = srcFbo; srcFbo = dstFbo; dstFbo = tmpF
            val tmpT = srcTex; srcTex = dstTex; dstTex = tmpT
            step /= 2
        }

        // 3. Distance Field Pass: srcTex -> sdfFbo
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sdfFbo)
        GLES20.glUseProgram(sdfProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(sdfProgram, "uJfaTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(sdfProgram, "uAspect"), aspectX, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        onDrawCall()

        unbindQuadAttribs()
        GLES20.glEnable(GLES20.GL_BLEND)
        sdfBuiltThisFrame = true
    }

    /**
     * 执行双级联辐射 GI（SDF 球体步进 -> 水平降噪 -> 垂直降噪 -> 滤色叠加至屏幕）
     */
    fun renderAndComposite(
        sceneColorTex: Int,
        lightMapTex: Int,
        surfaceW: Int,
        surfaceH: Int,
        fadeAlpha: Float,
        time: Float,
        onDrawCall: () -> Unit
    ) {
        if (!enabled || !fboReady || intensity <= 0.001f || fadeAlpha <= 0.001f) return
        if (sceneColorTex == 0 || lightMapTex == 0) return

        GLES20.glDisable(GLES20.GL_BLEND)

        // Pass 1：双级联辐射采集（带全像素 JFA 墙体遮挡与接触 AO）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, giFboA)
        GLES20.glViewport(0, 0, giW, giH)
        GLES20.glUseProgram(rcGiProgram)
        bindQuadAttribs()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneColorTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(rcGiProgram, "uSceneTex"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lightMapTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(rcGiProgram, "uLightTex"), 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sdfTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(rcGiProgram, "uSdfTex"), 2)

        val invAspectX = giH.toFloat() / max(1, giW).toFloat()
        GLES20.glUniform2f(GLES20.glGetUniformLocation(rcGiProgram, "uInvAspect"), invAspectX, 1.0f)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(rcGiProgram, "uUseJfa"), if (sdfBuiltThisFrame && jfaEnabled) 1 else 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(rcGiProgram, "uRadius"), radius)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(rcGiProgram, "uThreshold"), threshold)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(rcGiProgram, "uSaturation"), saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(rcGiProgram, "uAoStrength"), aoStrength)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(rcGiProgram, "uSkyColor"), skyR, skyG, skyB)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(rcGiProgram, "uTime"), time)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        onDrawCall()
        unbindQuadAttribs()

        // Pass 2 & 3：分离高斯降噪
        runBlurPass(giTexA, giFboB, 1.6f / giW, 0f, onDrawCall)
        runBlurPass(giTexB, giFboA, 0f, 1.6f / giH, onDrawCall)

        // Pass 4：全分辨率 Screen 滤色叠加至屏幕 FBO 0
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)

        GLES20.glUseProgram(compositeProgram)
        bindQuadAttribs()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, giTexA)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uGiTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compositeProgram, "uIntensity"), intensity * fadeAlpha)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        onDrawCall()
        unbindQuadAttribs()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun runBlurPass(srcTex: Int, dstFbo: Int, dirX: Float, dirY: Float, onDrawCall: () -> Unit) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, giW, giH)
        GLES20.glUseProgram(blurProgram)
        bindQuadAttribs()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "uDir"), dirX, dirY)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        onDrawCall()
        unbindQuadAttribs()
    }

    private fun bindQuadAttribs() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
    }

    private fun unbindQuadAttribs() {
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }

    companion object {
        private val FULLSCREEN_VS = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        private val JFA_SEED_FS = """#version 300 es
            precision highp float;
            uniform sampler2D uOccluderTex;
            in vec2 vTexCoord;
            out vec4 fragColor;

            vec2 pack16(float v) {
                float s = clamp(v, 0.0, 1.0) * 65535.0;
                float hi = floor(s / 256.0);
                float lo = floor(s - hi * 256.0);
                return vec2(hi / 255.0, lo / 255.0);
            }

            void main() {
                float a = texture(uOccluderTex, vTexCoord).a;
                if (a > 0.1) {
                    vec2 uv = max(vTexCoord, vec2(0.0001));
                    fragColor = vec4(pack16(uv.x), pack16(uv.y));
                } else {
                    fragColor = vec4(0.0);
                }
            }
        """.trimIndent()

        private val JFA_FLOOD_FS = """#version 300 es
            precision highp float;
            uniform sampler2D uJfaTex;
            uniform vec2 uTexelSize;
            uniform float uStep;
            uniform vec2 uAspect;
            in vec2 vTexCoord;
            out vec4 fragColor;

            float unpack16(vec2 p) {
                float hi = floor(p.x * 255.0 + 0.5);
                float lo = floor(p.y * 255.0 + 0.5);
                return (hi * 256.0 + lo) / 65535.0;
            }

            void main() {
                vec4 best = vec4(0.0);
                float bestDistSq = 99999.0;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        vec2 suv = vTexCoord + vec2(float(dx), float(dy)) * uTexelSize * uStep;
                        if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) continue;
                        vec4 s = texture(uJfaTex, suv);
                        if (dot(s, vec4(1.0)) < 0.0001) continue;
                        vec2 seedUv = vec2(unpack16(s.rg), unpack16(s.ba));
                        vec2 diff = (vTexCoord - seedUv) * uAspect;
                        float d2 = dot(diff, diff);
                        if (d2 < bestDistSq) {
                            bestDistSq = d2;
                            best = s;
                        }
                    }
                }
                fragColor = best;
            }
        """.trimIndent()

        private val JFA_SDF_FS = """#version 300 es
            precision highp float;
            uniform sampler2D uJfaTex;
            uniform vec2 uAspect;
            in vec2 vTexCoord;
            out vec4 fragColor;

            float unpack16(vec2 p) {
                float hi = floor(p.x * 255.0 + 0.5);
                float lo = floor(p.y * 255.0 + 0.5);
                return (hi * 256.0 + lo) / 65535.0;
            }

            void main() {
                vec4 s = texture(uJfaTex, vTexCoord);
                if (dot(s, vec4(1.0)) < 0.0001) {
                    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec2 seedUv = vec2(unpack16(s.rg), unpack16(s.ba));
                float dist = length((vTexCoord - seedUv) * uAspect);
                float inside = dist < 0.0025 ? 1.0 : 0.0;
                fragColor = vec4(clamp(dist, 0.0, 1.0), inside, 0.0, 1.0);
            }
        """.trimIndent()

        private val RC_GI_FS = """#version 300 es
            precision highp float;

            uniform sampler2D uSceneTex;
            uniform sampler2D uLightTex;
            uniform sampler2D uSdfTex;
            uniform vec2 uInvAspect;
            uniform int uUseJfa;
            uniform float uRadius;
            uniform float uThreshold;
            uniform float uSaturation;
            uniform float uAoStrength;
            uniform vec3 uSkyColor;
            uniform float uTime;

            in vec2 vTexCoord;
            out vec4 fragColor;

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            vec3 boostSat(vec3 c, float sat) {
                float luma = dot(c, vec3(0.299, 0.587, 0.114));
                return max(vec3(0.0), mix(vec3(luma), c, sat));
            }

            vec3 traceCascadeRay(vec2 dir, float tStart, float tMax, int maxSteps, float minStep) {
                float t = tStart;
                vec3 rayAccum = vec3(0.0);
                for (int s = 0; s < 10; s++) {
                    if (s >= maxSteps || t > tMax) break;
                    vec2 suv = vTexCoord + dir * t;
                    if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) break;

                    vec3 direct = texture(uSceneTex, suv).rgb * texture(uLightTex, suv).rgb;
                    float lum = max(direct.r, max(direct.g, direct.b));
                    float falloff = 1.0 - (t / max(0.001, tMax)) * 0.72;

                    if (uUseJfa != 0) {
                        float d = texture(uSdfTex, suv).r;
                        if (d < 0.0045) {
                            // 命中墙体/遮挡体：采集表面彩色漫反射反弹光并截断射线（彻底防穿墙漏光）
                            if (lum > uThreshold * 0.75) {
                                rayAccum += direct * falloff * smoothstep(uThreshold * 0.75, uThreshold + 0.28, lum) * 1.35;
                            }
                            break;
                        }
                        if (lum > uThreshold) {
                            rayAccum += direct * falloff * smoothstep(uThreshold, uThreshold + 0.35, lum) * 0.32;
                        }
                        t += max(minStep, d * 0.85);
                    } else {
                        if (lum > uThreshold) {
                            rayAccum += direct * falloff * smoothstep(uThreshold, uThreshold + 0.35, lum) * 0.30;
                        }
                        t += minStep * 1.45;
                    }
                }
                return rayAccum;
            }

            void main() {
                float jitter = hash12(gl_FragCoord.xy + fract(uTime) * 19.0) * 0.39269908;
                vec3 nearAccum = vec3(0.0);
                vec3 farAccum = vec3(0.0);

                float nearMax = uRadius * 0.42;
                float farStart = uRadius * 0.24;

                // 双级联交错方向采集：Cascade 0（近场精细遮挡）+ Cascade 1（远场大范围溢色）
                for (int i = 0; i < 16; i++) {
                    float ang0 = jitter + float(i) * 0.39269908;
                    vec2 dir0 = vec2(cos(ang0), sin(ang0)) * uInvAspect;
                    nearAccum += traceCascadeRay(dir0, 0.008, nearMax, 7, nearMax / 7.0);

                    float ang1 = ang0 + 0.19634954;
                    vec2 dir1 = vec2(cos(ang1), sin(ang1)) * uInvAspect;
                    farAccum += traceCascadeRay(dir1, farStart, uRadius, 9, (uRadius - farStart) / 9.0);
                }

                float ao = 1.0;
                if (uUseJfa != 0 && uAoStrength > 0.001) {
                    float selfDist = texture(uSdfTex, vTexCoord).r;
                    ao = mix(1.0, smoothstep(0.0, 0.045, selfDist), clamp(uAoStrength, 0.0, 1.0));
                }

                vec3 combined = (nearAccum * 0.62 + farAccum * 0.48) / 16.0;
                vec3 gi = boostSat(combined, uSaturation) + uSkyColor * ao;
                fragColor = vec4(gi, 1.0);
            }
        """.trimIndent()

        private val GI_BLUR_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            in vec2 vTexCoord;
            out vec4 fragColor;
            void main() {
                vec3 sum = texture(uTex, vTexCoord).rgb * 0.227027;
                sum += texture(uTex, clamp(vTexCoord + uDir * 1.3846, 0.0, 1.0)).rgb * 0.316216;
                sum += texture(uTex, clamp(vTexCoord - uDir * 1.3846, 0.0, 1.0)).rgb * 0.316216;
                sum += texture(uTex, clamp(vTexCoord + uDir * 3.2307, 0.0, 1.0)).rgb * 0.070270;
                sum += texture(uTex, clamp(vTexCoord - uDir * 3.2307, 0.0, 1.0)).rgb * 0.070270;
                fragColor = vec4(sum, 1.0);
            }
        """.trimIndent()

        private val GI_COMPOSITE_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uGiTex;
            uniform float uIntensity;
            in vec2 vTexCoord;
            out vec4 fragColor;
            void main() {
                vec3 gi = texture(uGiTex, vTexCoord).rgb * uIntensity;
                fragColor = vec4(gi, 1.0);
            }
        """.trimIndent()
    }
}
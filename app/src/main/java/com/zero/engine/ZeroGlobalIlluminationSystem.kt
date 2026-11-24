package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * P11 次世代 2D 实时全局光照系统（2D Screen-Space Radiance GI）：
 * 1. 在 1/4 分辨率 FBO 上对直接光照结果（SceneAlbedo * LightMap）执行 16 方向抖动光线步进
 * 2. 自动计算彩色表面漫反射光线反弹（Color Bleeding）与柔和天光补光（Sky Fill）
 * 3. 通过双 Pass 分离高斯模糊消除射线噪点，并以 Screen 滤色模式叠加至最终画面
 */
class ZeroGlobalIlluminationSystem(
    private val onConsoleLog: (String) -> Unit
) {
    var enabled: Boolean = true
    var intensity: Float = 0.65f
    var radius: Float = 0.14f       // 屏幕空间最大光线反弹扩散半径 (0.04 ~ 0.35)
    var threshold: Float = 0.22f    // 触发光线反弹的亮度阈值
    var saturation: Float = 1.35f   // 间接光溢色饱和度增强
    var skyR: Float = 0.04f
    var skyG: Float = 0.06f
    var skyB: Float = 0.10f

    private var giProgram = 0
    private var blurProgram = 0
    private var compositeProgram = 0

    private var giFboA = 0
    private var giTexA = 0
    private var giFboB = 0
    private var giTexB = 0
    private var giW = 270
    private var giH = 480
    private var fboReady = false

    val isReady: Boolean get() = enabled && fboReady

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
        giProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, GI_RAYMARCH_FS, onConsoleLog)
        blurProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, GI_BLUR_FS, onConsoleLog)
        compositeProgram = ZeroShaders.buildShaderProgram(FULLSCREEN_VS, GI_COMPOSITE_FS, onConsoleLog)
    }

    fun resize(surfaceW: Int, surfaceH: Int) {
        releaseFbos()
        giW = max(32, surfaceW / 4)
        giH = max(32, surfaceH / 4)
        val (fa, ta) = ZeroShaders.createFboTarget(giW, giH)
        val (fb, tb) = ZeroShaders.createFboTarget(giW, giH)
        if (fa != 0 && fb != 0) {
            giFboA = fa; giTexA = ta
            giFboB = fb; giTexB = tb
            fboReady = (giProgram != 0 && blurProgram != 0 && compositeProgram != 0)
        } else {
            releaseFbos()
        }
    }

    private fun releaseFbos() {
        fboReady = false
        listOf(giTexA, giTexB).filter { it != 0 }.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        listOf(giFboA, giFboB).filter { it != 0 }.forEach { GLES20.glDeleteFramebuffers(1, intArrayOf(it), 0) }
        giFboA = 0; giTexA = 0; giFboB = 0; giTexB = 0
    }

    fun releaseGL() {
        releaseFbos()
        listOf(giProgram, blurProgram, compositeProgram).filter { it != 0 }.forEach { GLES20.glDeleteProgram(it) }
        giProgram = 0; blurProgram = 0; compositeProgram = 0
    }

    /**
     * 执行完整 4-Pass 2D 全局光照（光线步进 -> 水平降噪 -> 垂直降噪 -> 滤色叠加至屏幕）
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

        // Pass 1：1/4 分辨率 16 方向抖动光线步进，收集彩色漫反射反弹
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, giFboA)
        GLES20.glViewport(0, 0, giW, giH)
        GLES20.glUseProgram(giProgram)
        bindQuadAttribs(giProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneColorTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(giProgram, "uSceneTex"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lightMapTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(giProgram, "uLightTex"), 1)

        GLES20.glUniform2f(GLES20.glGetUniformLocation(giProgram, "uInvAspect"), giH.toFloat() / giW.toFloat(), 1.0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(giProgram, "uRadius"), radius)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(giProgram, "uThreshold"), threshold)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(giProgram, "uSaturation"), saturation)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(giProgram, "uSkyColor"), skyR, skyG, skyB)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(giProgram, "uTime"), time)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        onDrawCall()
        unbindQuadAttribs(giProgram)

        // Pass 2：水平分离高斯降噪 (A -> B)
        runBlurPass(giTexA, giFboB, 1.8f / giW, 0f, onDrawCall)

        // Pass 3：垂直分离高斯降噪 (B -> A)
        runBlurPass(giTexB, giFboA, 0f, 1.8f / giH, onDrawCall)

        // Pass 4：全分辨率 Screen 滤色叠加至屏幕 FBO 0
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)

        GLES20.glUseProgram(compositeProgram)
        bindQuadAttribs(compositeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, giTexA)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositeProgram, "uGiTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compositeProgram, "uIntensity"), intensity * fadeAlpha)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        onDrawCall()
        unbindQuadAttribs(compositeProgram)

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun runBlurPass(srcTex: Int, dstFbo: Int, dirX: Float, dirY: Float, onDrawCall: () -> Unit) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, giW, giH)
        GLES20.glUseProgram(blurProgram)
        bindQuadAttribs(blurProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "uDir"), dirX, dirY)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        onDrawCall()
        unbindQuadAttribs(blurProgram)
    }

    private fun bindQuadAttribs(prog: Int) {
        val posLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
    }

    private fun unbindQuadAttribs(prog: Int) {
        GLES20.glDisableVertexAttribArray(GLES20.glGetAttribLocation(prog, "aPosition"))
        GLES20.glDisableVertexAttribArray(GLES20.glGetAttribLocation(prog, "aTexCoord"))
    }

    companion object {
        private const val FULLSCREEN_VS = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        private const val GI_RAYMARCH_FS = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform sampler2D uSceneTex;
            uniform sampler2D uLightTex;
            uniform vec2 uInvAspect;
            uniform float uRadius;
            uniform float uThreshold;
            uniform float uSaturation;
            uniform vec3 uSkyColor;
            uniform float uTime;

            varying vec2 vTexCoord;

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            vec3 boostSat(vec3 c, float sat) {
                float luma = dot(c, vec3(0.299, 0.587, 0.114));
                return max(vec3(0.0), mix(vec3(luma), c, sat));
            }

            void main() {
                float jitter = hash12(gl_FragCoord.xy + fract(uTime) * 17.0) * 6.2831853;
                vec3 accum = vec3(0.0);

                // 16 方向环形射线 × 6 级指数步进采集间接漫反射光场
                for (int i = 0; i < 16; i++) {
                    float angle = jitter + float(i) * 0.39269908; // 2*PI / 16
                    vec2 dir = vec2(cos(angle), sin(angle)) * uInvAspect;
                    vec3 rayLight = vec3(0.0);

                    for (int s = 1; s <= 6; s++) {
                        float frac = float(s) / 6.0;
                        float dist = uRadius * (frac * frac);
                        vec2 suv = vTexCoord + dir * dist;
                        if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) break;

                        vec3 direct = texture2D(uSceneTex, suv).rgb * texture2D(uLightTex, suv).rgb;
                        float lum = max(direct.r, max(direct.g, direct.b));
                        if (lum > uThreshold) {
                            float weight = (1.0 - frac * 0.72) * smoothstep(uThreshold, uThreshold + 0.35, lum);
                            rayLight += direct * weight;
                        }
                    }
                    accum += rayLight * 0.24;
                }

                vec3 gi = boostSat(accum / 16.0, uSaturation) + uSkyColor;
                gl_FragColor = vec4(gi, 1.0);
            }
        """

        private const val GI_BLUR_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uDir;
            varying vec2 vTexCoord;
            void main() {
                vec3 sum = texture2D(uTex, vTexCoord).rgb * 0.227027;
                sum += texture2D(uTex, clamp(vTexCoord + uDir * 1.3846, 0.0, 1.0)).rgb * 0.316216;
                sum += texture2D(uTex, clamp(vTexCoord - uDir * 1.3846, 0.0, 1.0)).rgb * 0.316216;
                sum += texture2D(uTex, clamp(vTexCoord + uDir * 3.2307, 0.0, 1.0)).rgb * 0.070270;
                sum += texture2D(uTex, clamp(vTexCoord - uDir * 3.2307, 0.0, 1.0)).rgb * 0.070270;
                gl_FragColor = vec4(sum, 1.0);
            }
        """

        private const val GI_COMPOSITE_FS = """
            precision mediump float;
            uniform sampler2D uGiTex;
            uniform float uIntensity;
            varying vec2 vTexCoord;
            void main() {
                vec3 gi = texture2D(uGiTex, vTexCoord).rgb * uIntensity;
                gl_FragColor = vec4(gi, 1.0);
            }
        """
    }
}

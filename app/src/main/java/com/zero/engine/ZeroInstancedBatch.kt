package com.zero.engine

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * P14 OpenGL ES 3.0 GPU 硬件实例化渲染器 (Hardware GPU Instancing)
 * 单次 DrawCall 最高绘制 4096 个粒子 / 弹幕 / 批量精灵，顶点展开与旋转全部由 GPU 并行计算
 */
class ZeroInstancedBatch(
    private val maxInstances: Int = 4096
) {
    companion object {
        private const val FLOATS_PER_INSTANCE = 16
        private const val INSTANCE_STRIDE_BYTES = FLOATS_PER_INSTANCE * 4

        private val INSTANCED_VS = """#version 300 es
            layout(location = 0) in vec2 aQuadPos;
            layout(location = 1) in vec2 aQuadUv;
            layout(location = 2) in vec4 aInstTransform; // (x, y, z, rotRad)
            layout(location = 3) in vec4 aInstSizeFlash; // (w, h, flash, 0)
            layout(location = 4) in vec4 aInstColor;     // (r, g, b, a)
            layout(location = 5) in vec4 aInstUvRect;    // (u0, v0, du, dv)

            uniform mat4 uVP;
            uniform mat4 uBillboardRot;

            out vec2 vTexCoord;
            out vec4 vColor;
            out float vFlash;

            void main() {
                vec2 local = aQuadPos * aInstSizeFlash.xy;
                float c = cos(aInstTransform.w);
                float s = sin(aInstTransform.w);
                vec2 rot = vec2(local.x * c - local.y * s, local.x * s + local.y * c);
                vec3 offset = (uBillboardRot * vec4(rot, 0.0, 0.0)).xyz;
                vec3 worldPos = aInstTransform.xyz + offset;

                vTexCoord = aInstUvRect.xy + aQuadUv * aInstUvRect.zw;
                vColor = aInstColor;
                vFlash = aInstSizeFlash.z;
                gl_Position = uVP * vec4(worldPos, 1.0);
            }
        """.trimIndent()

        private val INSTANCED_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTexture;

            in vec2 vTexCoord;
            in vec4 vColor;
            in float vFlash;

            out vec4 fragColor;

            void main() {
                vec4 base = texture(uTexture, vTexCoord) * vColor;
                if (base.a < 0.01) discard;
                vec3 rgb = mix(base.rgb, vec3(1.0), clamp(vFlash, 0.0, 1.0));
                fragColor = vec4(rgb, base.a);
            }
        """.trimIndent()
    }

    private var program = 0
    private var quadVbo = 0
    private var instVbo = 0
    private var uVpLoc = -1
    private var uBillboardLoc = -1
    private var uTexLoc = -1

    private val instanceData = FloatArray(maxInstances * FLOATS_PER_INSTANCE)
    private val instanceBuffer: FloatBuffer = ByteBuffer.allocateDirect(instanceData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val billboardMat = FloatArray(16)
    private var activeVp = FloatArray(16)
    private var currentTexId = 0
    private var currentAdditive = false
    private var instanceCount = 0
    private var floatPos = 0
    private var onDrawCall: (() -> Unit)? = null

    var frameInstanceCount: Int = 0
        private set

    val isReady: Boolean get() = program != 0 && quadVbo != 0 && instVbo != 0

    fun initGL(onConsoleLog: (String) -> Unit) {
        program = ZeroShaders.buildShaderProgram(INSTANCED_VS, INSTANCED_FS, onConsoleLog)
        if (program == 0) return

        uVpLoc = GLES20.glGetUniformLocation(program, "uVP")
        uBillboardLoc = GLES20.glGetUniformLocation(program, "uBillboardRot")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")

        val vbos = IntArray(2)
        GLES20.glGenBuffers(2, vbos, 0)
        quadVbo = vbos[0]
        instVbo = vbos[1]

        val quadVerts = floatArrayOf(
            -0.5f, -0.5f, 0f, 0f,
             0.5f, -0.5f, 1f, 0f,
            -0.5f,  0.5f, 0f, 1f,
             0.5f,  0.5f, 1f, 1f
        )
        val qBuf = ByteBuffer.allocateDirect(quadVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadVerts); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadVerts.size * 4, qBuf, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, instVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, instanceData.size * 4, null, GLES20.GL_STREAM_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun releaseGL() {
        val vbos = listOf(quadVbo, instVbo).filter { it != 0 }.toIntArray()
        if (vbos.isNotEmpty()) GLES20.glDeleteBuffers(vbos.size, vbos, 0)
        quadVbo = 0; instVbo = 0
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
    }

    fun resetFrameCounter() {
        frameInstanceCount = 0
    }

    fun begin(vpMatrix: FloatArray, billboardTiltDeg: Float, drawCounter: () -> Unit) {
        activeVp = vpMatrix
        Matrix.setIdentityM(billboardMat, 0)
        if (billboardTiltDeg != 0f) {
            Matrix.rotateM(billboardMat, 0, billboardTiltDeg, 1f, 0f, 0f)
        }
        onDrawCall = drawCounter
        instanceCount = 0
        floatPos = 0
        currentTexId = 0
        currentAdditive = false
    }

    fun pushInstance(
        texId: Int,
        x: Float, y: Float, z: Float,
        w: Float, h: Float,
        rotDeg: Float,
        r: Float, g: Float, b: Float, a: Float,
        flash: Float = 0f,
        u0: Float = 0f, v0: Float = 0f, du: Float = 1f, dv: Float = 1f,
        additive: Boolean = false
    ) {
        if (!isReady) return
        if (instanceCount > 0 && (texId != currentTexId || additive != currentAdditive || instanceCount >= maxInstances)) {
            flush()
        }
        if (instanceCount == 0) {
            currentTexId = texId
            currentAdditive = additive
        }

        var p = floatPos
        instanceData[p++] = x
        instanceData[p++] = y
        instanceData[p++] = z
        instanceData[p++] = Math.toRadians(rotDeg.toDouble()).toFloat()

        instanceData[p++] = w
        instanceData[p++] = h
        instanceData[p++] = flash
        instanceData[p++] = 0f

        instanceData[p++] = r
        instanceData[p++] = g
        instanceData[p++] = b
        instanceData[p++] = a

        instanceData[p++] = u0
        instanceData[p++] = v0
        instanceData[p++] = du
        instanceData[p++] = dv

        floatPos = p
        instanceCount++
        frameInstanceCount++
    }

    fun flush() {
        if (instanceCount == 0 || !isReady) return

        if (currentAdditive) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        } else {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVpLoc, 1, false, activeVp, 0)
        GLES20.glUniformMatrix4fv(uBillboardLoc, 1, false, billboardMat, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexId)
        GLES20.glUniform1i(uTexLoc, 0)

        // 绑定单位矩形顶点 (location 0, 1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribDivisor(0, 0)

        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES30.glVertexAttribDivisor(1, 0)

        // 上传并绑定实例数据 (location 2..5)
        instanceBuffer.clear()
        instanceBuffer.put(instanceData, 0, floatPos)
        instanceBuffer.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, instVbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, floatPos * 4, instanceBuffer)

        for (loc in 2..5) {
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, 4, GLES20.GL_FLOAT, false, INSTANCE_STRIDE_BYTES, (loc - 2) * 16)
            GLES30.glVertexAttribDivisor(loc, 1)
        }

        GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLE_STRIP, 0, 4, instanceCount)
        onDrawCall?.invoke()

        // 还原 Divisor 与 VBO 绑定，确保不污染其他 GLES 2.0 渲染模块
        for (loc in 2..5) {
            GLES30.glVertexAttribDivisor(loc, 0)
            GLES20.glDisableVertexAttribArray(loc)
        }
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        if (currentAdditive) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        instanceCount = 0
        floatPos = 0
    }

    fun end() {
        flush()
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }
}
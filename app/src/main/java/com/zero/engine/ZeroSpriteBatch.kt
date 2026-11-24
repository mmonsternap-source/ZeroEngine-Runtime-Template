package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class ZeroSpriteBatch(
    private val maxQuads: Int = 1024
) {
    private val floatsPerVertex = 10
    private val strideBytes = floatsPerVertex * 4
    private val vertexData = FloatArray(maxQuads * 4 * floatsPerVertex)
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(maxQuads * 6 * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            val indices = ShortArray(maxQuads * 6)
            var idx = 0
            for (i in 0 until maxQuads) {
                val v = (i * 4).toShort()
                indices[idx++] = v
                indices[idx++] = (v + 1).toShort()
                indices[idx++] = (v + 2).toShort()
                indices[idx++] = v
                indices[idx++] = (v + 2).toShort()
                indices[idx++] = (v + 3).toShort()
            }
            put(indices)
            position(0)
        }

    private var program = 0
    private var aPosLoc = -1
    private var aUvLoc = -1
    private var aColLoc = -1
    private var aFlashLoc = -1
    private var uVpLoc = -1
    private var uTexLoc = -1
    private var uEnableLightLoc = -1
    private var uAmbientLoc = -1
    private var uLightCountLoc = -1
    private var uLightPosLoc = -1
    private var uLightColLoc = -1

    private var activeVp: FloatArray = FloatArray(16)
    private var currentTexId = 0
    private var currentAdditive = false
    private var currentLit = false
    private var ambR = 0.5f
    private var ambG = 0.5f
    private var ambB = 0.5f
    private var lightCount = 0
    private var lightPosArr: FloatArray? = null
    private var lightColArr: FloatArray? = null

    private var quadCount = 0
    private var floatPos = 0
    private var onDrawCall: (() -> Unit)? = null

    fun init(batchProgramId: Int) {
        program = batchProgramId
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aUvLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        aColLoc = GLES20.glGetAttribLocation(program, "aColor")
        aFlashLoc = GLES20.glGetAttribLocation(program, "aFlash")
        uVpLoc = GLES20.glGetUniformLocation(program, "uVP")
        uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uEnableLightLoc = GLES20.glGetUniformLocation(program, "uEnableLight")
        uAmbientLoc = GLES20.glGetUniformLocation(program, "uAmbient")
        uLightCountLoc = GLES20.glGetUniformLocation(program, "uLightCount")
        uLightPosLoc = GLES20.glGetUniformLocation(program, "uLightPos")
        uLightColLoc = GLES20.glGetUniformLocation(program, "uLightColor")
    }

    fun begin(
        vpMatrix: FloatArray,
        enableInlineLight: Boolean,
        ambientR: Float,
        ambientG: Float,
        ambientB: Float,
        lights: Int,
        lightPos: FloatArray?,
        lightCol: FloatArray?,
        drawCounter: () -> Unit
    ) {
        activeVp = vpMatrix
        currentLit = enableInlineLight
        ambR = ambientR
        ambG = ambientG
        ambB = ambientB
        lightCount = lights
        lightPosArr = lightPos
        lightColArr = lightCol
        onDrawCall = drawCounter
        quadCount = 0
        floatPos = 0
        currentTexId = 0
        currentAdditive = false
    }

    fun setInlineLight(enabled: Boolean) {
        if (enabled != currentLit) {
            flush()
            currentLit = enabled
        }
    }

    fun drawQuad(
        texId: Int,
        m: FloatArray,
        u0: Float, v0: Float, u1: Float, v1: Float,
        r: Float, g: Float, b: Float, a: Float,
        flash: Float,
        additive: Boolean
    ) {
        if (program == 0) return
        if (quadCount > 0 && (texId != currentTexId || additive != currentAdditive || quadCount >= maxQuads)) {
            flush()
        }
        if (quadCount == 0) {
            currentTexId = texId
            currentAdditive = additive
        }

        val m0 = m[0]; val m1 = m[1]; val m2 = m[2]
        val m4 = m[4]; val m5 = m[5]; val m6 = m[6]
        val m12 = m[12]; val m13 = m[13]; val m14 = m[14]

        val hx0 = m0 * 0.5f; val hx1 = m1 * 0.5f; val hx2 = m2 * 0.5f
        val hy0 = m4 * 0.5f; val hy1 = m5 * 0.5f; val hy2 = m6 * 0.5f

        var p = floatPos
        vertexData[p++] = m12 - hx0 - hy0; vertexData[p++] = m13 - hx1 - hy1; vertexData[p++] = m14 - hx2 - hy2
        vertexData[p++] = u0; vertexData[p++] = v0
        vertexData[p++] = r; vertexData[p++] = g; vertexData[p++] = b; vertexData[p++] = a; vertexData[p++] = flash

        vertexData[p++] = m12 + hx0 - hy0; vertexData[p++] = m13 + hx1 - hy1; vertexData[p++] = m14 + hx2 - hy2
        vertexData[p++] = u1; vertexData[p++] = v0
        vertexData[p++] = r; vertexData[p++] = g; vertexData[p++] = b; vertexData[p++] = a; vertexData[p++] = flash

        vertexData[p++] = m12 + hx0 + hy0; vertexData[p++] = m13 + hx1 + hy1; vertexData[p++] = m14 + hx2 + hy2
        vertexData[p++] = u1; vertexData[p++] = v1
        vertexData[p++] = r; vertexData[p++] = g; vertexData[p++] = b; vertexData[p++] = a; vertexData[p++] = flash

        vertexData[p++] = m12 - hx0 + hy0; vertexData[p++] = m13 - hx1 + hy1; vertexData[p++] = m14 - hx2 + hy2
        vertexData[p++] = u0; vertexData[p++] = v1
        vertexData[p++] = r; vertexData[p++] = g; vertexData[p++] = b; vertexData[p++] = a; vertexData[p++] = flash

        floatPos = p
        quadCount++
    }

    fun flush() {
        if (quadCount == 0 || program == 0) return

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (currentAdditive) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        } else {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uVpLoc, 1, false, activeVp, 0)
        GLES20.glUniform1i(uEnableLightLoc, if (currentLit) 1 else 0)
        if (currentLit) {
            GLES20.glUniform3f(uAmbientLoc, ambR, ambG, ambB)
            GLES20.glUniform1i(uLightCountLoc, lightCount)
            if (lightCount > 0 && lightPosArr != null && lightColArr != null) {
                GLES20.glUniform4fv(uLightPosLoc, lightCount, lightPosArr, 0)
                GLES20.glUniform4fv(uLightColLoc, lightCount, lightColArr, 0)
            }
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexId)
        GLES20.glUniform1i(uTexLoc, 0)

        vertexBuffer.clear()
        vertexBuffer.put(vertexData, 0, floatPos)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(aUvLoc)
        GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        vertexBuffer.position(5)
        GLES20.glEnableVertexAttribArray(aColLoc)
        GLES20.glVertexAttribPointer(aColLoc, 4, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        vertexBuffer.position(9)
        GLES20.glEnableVertexAttribArray(aFlashLoc)
        GLES20.glVertexAttribPointer(aFlashLoc, 1, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, quadCount * 6, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        onDrawCall?.invoke()

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aUvLoc)
        GLES20.glDisableVertexAttribArray(aColLoc)
        GLES20.glDisableVertexAttribArray(aFlashLoc)

        if (currentAdditive) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        quadCount = 0
        floatPos = 0
    }

    fun end() {
        flush()
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }
}

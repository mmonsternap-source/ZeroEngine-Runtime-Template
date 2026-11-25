package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * P17 GLES 3.0 GPU 硬件骨骼蒙皮系统：
 * - 网格顶点、UV、4 骨骼索引与权重一次性写入 GPU 静态 VBO + EBO（每帧零顶点上传）
 * - 每帧 CPU 仅更新最多 32 根骨骼的 2x3 仿射矩阵并传入顶点着色器
 * - 蒙皮混合与程序化风浪扭曲全部在 GPU 顶点着色器并行执行
 */
class ZeroMeshSkinningSystem(
    private val onConsoleLog: (String) -> Unit
) {
    private data class Mat2D(
        var a: Float = 1f, var b: Float = 0f, var tx: Float = 0f,
        var c: Float = 0f, var d: Float = 1f, var ty: Float = 0f
    ) {
        fun transformX(x: Float, y: Float): Float = a * x + b * y + tx
        fun transformY(x: Float, y: Float): Float = c * x + d * y + ty

        fun multiply(other: Mat2D): Mat2D = Mat2D(
            a = a * other.a + b * other.c,
            b = a * other.b + b * other.d,
            tx = a * other.tx + b * other.ty + tx,
            c = c * other.a + d * other.c,
            d = c * other.b + d * other.d,
            ty = c * other.tx + d * other.ty + ty
        )

        fun invert(): Mat2D {
            val det = a * d - b * c
            if (abs(det) < 1e-6f) return Mat2D()
            val inv = 1f / det
            return Mat2D(
                a = d * inv,
                b = -b * inv,
                tx = (b * ty - d * tx) * inv,
                c = -c * inv,
                d = a * inv,
                ty = (c * tx - a * ty) * inv
            )
        }
    }

    private data class BoneBinding(
        val boneName: String,
        val bindWorldToBoneLocal: Mat2D // M_boneBind^-1 * M_meshBind
    )

    private class SkinnedMesh(
        val nodeName: String,
        val cols: Int,
        val rows: Int,
        val vertexCount: Int,
        val indexCount: Int,
        val localXs: FloatArray,
        val localYs: FloatArray,
        val uvsU: FloatArray,
        val uvsV: FloatArray,
        val gridU: FloatArray,
        val gridV: FloatArray,
        val boneIndices: IntArray,     // vertexCount * 4
        val boneWeights: FloatArray,   // vertexCount * 4
        val indices: ShortArray,
        var meshBindTransform: Mat2D,
        val bones: MutableList<BoneBinding> = mutableListOf(),
        val boneRow0: FloatArray = FloatArray(MAX_BONES * 4),
        val boneRow1: FloatArray = FloatArray(MAX_BONES * 4),
        var waveAmpX: Float = 0f,
        var waveAmpY: Float = 0f,
        var waveFreq: Float = 6f,
        var waveSpeed: Float = 4f,
        var vboId: Int = 0,
        var eboId: Int = 0,
        var gpuDirty: Boolean = true
    )

    private val meshes = ConcurrentHashMap<String, SkinnedMesh>()
    private var skinProgram = 0
    private var elapsedTime = 0f

    private val debugLineBuf: FloatBuffer = ByteBuffer.allocateDirect(4096 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    val activeMeshCount: Int get() = meshes.size

    fun initGL() {
        skinProgram = ZeroShaders.buildShaderProgram(SKIN_VS, SKIN_FS, onConsoleLog)
    }

    fun releaseGL() {
        meshes.values.forEach { releaseMeshBuffers(it) }
        meshes.clear()
        if (skinProgram != 0) {
            GLES20.glDeleteProgram(skinProgram)
            skinProgram = 0
        }
    }

    fun clearAll() {
        meshes.values.forEach { releaseMeshBuffers(it) }
        meshes.clear()
    }

    fun hasMesh(nodeName: String): Boolean = meshes.containsKey(nodeName)

    fun removeMesh(nodeName: String) {
        meshes.remove(nodeName)?.let { releaseMeshBuffers(it) }
    }

    private fun releaseMeshBuffers(m: SkinnedMesh) {
        if (m.vboId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(m.vboId), 0)
            m.vboId = 0
        }
        if (m.eboId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(m.eboId), 0)
            m.eboId = 0
        }
    }

    private fun buildNodeMat2D(gt: ZeroEngineView.GlobalTransform): Mat2D {
        val rad = Math.toRadians(gt.gRot.toDouble())
        val cs = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        return Mat2D(
            a = cs * gt.gSx,
            b = -sn * gt.gSy,
            tx = gt.gx,
            c = sn * gt.gSx,
            d = cs * gt.gSy,
            ty = gt.gy
        )
    }

    fun createGridMesh(
        node: ZeroEngineView.GameNode,
        cols: Int,
        rows: Int,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        removeMesh(node.name)
        val c = cols.coerceIn(1, 32)
        val r = rows.coerceIn(1, 32)
        val vCount = (c + 1) * (r + 1)
        val iCount = c * r * 6

        val localXs = FloatArray(vCount)
        val localYs = FloatArray(vCount)
        val uvsU = FloatArray(vCount)
        val uvsV = FloatArray(vCount)
        val gridU = FloatArray(vCount)
        val gridV = FloatArray(vCount)
        val boneIndices = IntArray(vCount * 4)
        val boneWeights = FloatArray(vCount * 4)

        val halfW = node.width * 0.5f
        val halfH = node.height * 0.5f

        var vi = 0
        for (iy in 0..r) {
            val ty = iy.toFloat() / r.toFloat()
            val ly = -halfH + ty * node.height
            val v = v0 + (v1 - v0) * ty
            for (ix in 0..c) {
                val tx = ix.toFloat() / c.toFloat()
                localXs[vi] = -halfW + tx * node.width
                localYs[vi] = ly
                uvsU[vi] = u0 + (u1 - u0) * tx
                uvsV[vi] = v
                gridU[vi] = tx
                gridV[vi] = ty
                boneIndices[vi * 4] = 0
                boneWeights[vi * 4] = 1f
                vi++
            }
        }

        val indices = ShortArray(iCount)
        var ii = 0
        for (iy in 0 until r) {
            for (ix in 0 until c) {
                val tl = (iy * (c + 1) + ix).toShort()
                val tr = (tl + 1).toShort()
                val bl = ((iy + 1) * (c + 1) + ix).toShort()
                val br = (bl + 1).toShort()
                indices[ii++] = tl; indices[ii++] = bl; indices[ii++] = tr
                indices[ii++] = tr; indices[ii++] = bl; indices[ii++] = br
            }
        }

        val meshBind = buildNodeMat2D(getGlobalTransform(node))
        val mesh = SkinnedMesh(
            nodeName = node.name,
            cols = c,
            rows = r,
            vertexCount = vCount,
            indexCount = iCount,
            localXs = localXs,
            localYs = localYs,
            uvsU = uvsU,
            uvsV = uvsV,
            gridU = gridU,
            gridV = gridV,
            boneIndices = boneIndices,
            boneWeights = boneWeights,
            indices = indices,
            meshBindTransform = meshBind
        )
        meshes[node.name] = mesh
    }

    fun bindBones(
        meshNodeName: String,
        boneNames: List<String>,
        nodes: List<ZeroEngineView.GameNode>,
        smoothness: Float,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Boolean {
        val mesh = meshes[meshNodeName] ?: return false
        val meshNode = nodes.find { it.name == meshNodeName } ?: return false
        val meshBind = buildNodeMat2D(getGlobalTransform(meshNode))
        mesh.meshBindTransform = meshBind
        mesh.bones.clear()

        val bonePositions = mutableListOf<Pair<Float, Float>>()
        for (bName in boneNames.take(MAX_BONES)) {
            val bNode = nodes.find { it.name == bName } ?: continue
            val bGt = getGlobalTransform(bNode)
            val bBind = buildNodeMat2D(bGt)
            val invBoneBind = bBind.invert()
            mesh.bones.add(BoneBinding(bName, invBoneBind.multiply(meshBind)))
            bonePositions.add(bGt.gx to bGt.gy)
        }
        if (mesh.bones.isEmpty()) return false

        val power = smoothness.coerceIn(0.6f, 6f)
        val bCount = mesh.bones.size

        // 为每个顶点计算前 4 根最近骨骼的归一化权重（一次性计算，写入 GPU VBO）
        for (v in 0 until mesh.vertexCount) {
            val wx = meshBind.transformX(mesh.localXs[v], mesh.localYs[v])
            val wy = meshBind.transformY(mesh.localXs[v], mesh.localYs[v])

            val topIdx = IntArray(4)
            val topW = FloatArray(4)

            for (bi in 0 until bCount) {
                val (bx, by) = bonePositions[bi]
                val dist = hypot(wx - bx, wy - by) + 12f
                val w = 1f / dist.pow(power)

                var minSlot = 0
                for (s in 1..3) {
                    if (topW[s] < topW[minSlot]) minSlot = s
                }
                if (w > topW[minSlot]) {
                    topW[minSlot] = w
                    topIdx[minSlot] = bi
                }
            }

            val sum = topW[0] + topW[1] + topW[2] + topW[3]
            val base = v * 4
            if (sum > 1e-6f) {
                for (s in 0..3) {
                    mesh.boneIndices[base + s] = topIdx[s]
                    mesh.boneWeights[base + s] = topW[s] / sum
                }
            } else {
                mesh.boneIndices[base] = 0
                mesh.boneWeights[base] = 1f
                for (s in 1..3) {
                    mesh.boneIndices[base + s] = 0
                    mesh.boneWeights[base + s] = 0f
                }
            }
        }

        mesh.gpuDirty = true
        return true
    }

    fun setMeshWave(meshNodeName: String, ampX: Float, ampY: Float, freq: Float, speed: Float) {
        val m = meshes[meshNodeName] ?: return
        m.waveAmpX = ampX
        m.waveAmpY = ampY
        m.waveFreq = freq
        m.waveSpeed = speed
    }

    fun update(
        dt: Float,
        nodes: List<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        elapsedTime += dt
        if (meshes.isEmpty()) return

        val nodeMap = HashMap<String, ZeroEngineView.GameNode>(nodes.size)
        nodes.forEach { nodeMap[it.name] = it }

        meshes.values.forEach { mesh ->
            val owner = nodeMap[mesh.nodeName] ?: return@forEach
            if (mesh.bones.isEmpty()) {
                val m = buildNodeMat2D(getGlobalTransform(owner))
                writeBoneMatrix(mesh, 0, m)
            } else {
                mesh.bones.forEachIndexed { idx, binding ->
                    val bNode = nodeMap[binding.boneName]
                    val curr = if (bNode != null) buildNodeMat2D(getGlobalTransform(bNode)) else buildNodeMat2D(getGlobalTransform(owner))
                    val skinMat = curr.multiply(binding.bindWorldToBoneLocal)
                    writeBoneMatrix(mesh, idx, skinMat)
                }
            }
        }
    }

    private fun writeBoneMatrix(mesh: SkinnedMesh, idx: Int, m: Mat2D) {
        val off = idx * 4
        mesh.boneRow0[off] = m.a
        mesh.boneRow0[off + 1] = m.b
        mesh.boneRow0[off + 2] = m.tx
        mesh.boneRow0[off + 3] = 0f
        mesh.boneRow1[off] = m.c
        mesh.boneRow1[off + 1] = m.d
        mesh.boneRow1[off + 2] = m.ty
        mesh.boneRow1[off + 3] = 0f
    }

    private fun ensureGpuBuffers(m: SkinnedMesh) {
        if (!m.gpuDirty && m.vboId != 0 && m.eboId != 0) return
        releaseMeshBuffers(m)

        // 顶点格式：14 floats = 56 bytes
        // [bindX, bindY, u, v, gridU, gridV, bIdx0..3, bW0..3]
        val strideFloats = 14
        val vData = FloatArray(m.vertexCount * strideFloats)
        var off = 0
        for (i in 0 until m.vertexCount) {
            vData[off++] = m.localXs[i]
            vData[off++] = m.localYs[i]
            vData[off++] = m.uvsU[i]
            vData[off++] = m.uvsV[i]
            vData[off++] = m.gridU[i]
            vData[off++] = m.gridV[i]
            val b = i * 4
            vData[off++] = m.boneIndices[b].toFloat()
            vData[off++] = m.boneIndices[b + 1].toFloat()
            vData[off++] = m.boneIndices[b + 2].toFloat()
            vData[off++] = m.boneIndices[b + 3].toFloat()
            vData[off++] = m.boneWeights[b]
            vData[off++] = m.boneWeights[b + 1]
            vData[off++] = m.boneWeights[b + 2]
            vData[off++] = m.boneWeights[b + 3]
        }

        val vBuf = ByteBuffer.allocateDirect(vData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vData); position(0)
        }
        val iBuf: ShortBuffer = ByteBuffer.allocateDirect(m.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(m.indices); position(0)
        }

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        m.vboId = ids[0]
        m.eboId = ids[1]

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vData.size * 4, vBuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.eboId)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 2, iBuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        m.gpuDirty = false
    }

    fun renderSkinnedMeshes(
        vpMatrix: FloatArray,
        nodes: List<ZeroEngineView.GameNode>,
        fadeAlpha: Float,
        ambientR: Float,
        ambientG: Float,
        ambientB: Float,
        lightCount: Int,
        lightPos: FloatArray,
        lightCol: FloatArray,
        whiteTexId: Int,
        getImageTexture: (String) -> Int,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform,
        onDrawCall: () -> Unit
    ) {
        if (meshes.isEmpty() || skinProgram == 0 || fadeAlpha <= 0.001f) return

        val nodeMap = HashMap<String, ZeroEngineView.GameNode>(nodes.size)
        nodes.forEach { nodeMap[it.name] = it }

        GLES20.glUseProgram(skinProgram)
        val uVpLoc = GLES20.glGetUniformLocation(skinProgram, "uVP")
        val uZLoc = GLES20.glGetUniformLocation(skinProgram, "uZ")
        val uBone0Loc = GLES20.glGetUniformLocation(skinProgram, "uBoneRow0")
        val uBone1Loc = GLES20.glGetUniformLocation(skinProgram, "uBoneRow1")
        val uWaveLoc = GLES20.glGetUniformLocation(skinProgram, "uWaveParams")
        val uTimeLoc = GLES20.glGetUniformLocation(skinProgram, "uTime")
        val uColorLoc = GLES20.glGetUniformLocation(skinProgram, "uColor")
        val uAmbientLoc = GLES20.glGetUniformLocation(skinProgram, "uAmbient")
        val uLightCntLoc = GLES20.glGetUniformLocation(skinProgram, "uLightCount")
        val uLightPosLoc = GLES20.glGetUniformLocation(skinProgram, "uLightPos")
        val uLightColLoc = GLES20.glGetUniformLocation(skinProgram, "uLightColor")
        val uTexLoc = GLES20.glGetUniformLocation(skinProgram, "uTexture")

        GLES20.glUniformMatrix4fv(uVpLoc, 1, false, vpMatrix, 0)
        GLES20.glUniform1f(uTimeLoc, elapsedTime)
        GLES20.glUniform3f(uAmbientLoc, ambientR, ambientG, ambientB)
        GLES20.glUniform1i(uLightCntLoc, lightCount)
        if (lightCount > 0) {
            GLES20.glUniform4fv(uLightPosLoc, lightCount, lightPos, 0)
            GLES20.glUniform4fv(uLightColLoc, lightCount, lightCol, 0)
        }

        val strideBytes = 14 * 4
        for (i in 0..4) GLES20.glEnableVertexAttribArray(i)

        meshes.values.forEach { m ->
            val node = nodeMap[m.nodeName] ?: return@forEach
            if (!node.visible) return@forEach
            ensureGpuBuffers(m)
            if (m.vboId == 0 || m.eboId == 0) return@forEach

            val gt = getGlobalTransform(node)
            val activeBones = max(1, m.bones.size)
            GLES20.glUniform1f(uZLoc, gt.gz)
            GLES20.glUniform4fv(uBone0Loc, activeBones, m.boneRow0, 0)
            GLES20.glUniform4fv(uBone1Loc, activeBones, m.boneRow1, 0)
            GLES20.glUniform4f(uWaveLoc, m.waveAmpX, m.waveAmpY, m.waveFreq, m.waveSpeed)
            GLES20.glUniform4f(uColorLoc, node.r * fadeAlpha, node.g * fadeAlpha, node.b * fadeAlpha, node.a)

            val texId = if (node.texture.isNotEmpty()) getImageTexture(node.texture) else whiteTexId
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(uTexLoc, 0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vboId)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, strideBytes, 0)
            GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, strideBytes, 8)
            GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, strideBytes, 16)
            GLES20.glVertexAttribPointer(3, 4, GLES20.GL_FLOAT, false, strideBytes, 24)
            GLES20.glVertexAttribPointer(4, 4, GLES20.GL_FLOAT, false, strideBytes, 40)

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.eboId)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
            onDrawCall()
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        for (i in 0..4) GLES20.glDisableVertexAttribArray(i)
    }

    fun renderDebugWireframe(
        posLoc: Int,
        uvLoc: Int,
        colLoc: Int,
        nodes: List<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform,
        onDrawCall: () -> Unit
    ) {
        if (meshes.isEmpty()) return
        val nodeMap = HashMap<String, ZeroEngineView.GameNode>(nodes.size)
        nodes.forEach { nodeMap[it.name] = it }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUniform4f(colLoc, 0.25f, 0.95f, 0.95f, 0.75f)

        meshes.values.forEach { m ->
            val node = nodeMap[m.nodeName] ?: return@forEach
            if (!node.visible) return@forEach
            val gt = getGlobalTransform(node)
            val z = gt.gz + 9f

            // 仅在开启调试线框时在 CPU 端采样外轮廓与骨骼连线
            val wx = FloatArray(m.vertexCount)
            val wy = FloatArray(m.vertexCount)
            for (v in 0 until m.vertexCount) {
                var sx = 0f; var sy = 0f
                val b = v * 4
                for (s in 0..3) {
                    val w = m.boneWeights[b + s]
                    if (w <= 0.0001f) continue
                    val off = m.boneIndices[b + s].coerceIn(0, MAX_BONES - 1) * 4
                    sx += (m.boneRow0[off] * m.localXs[v] + m.boneRow0[off + 1] * m.localYs[v] + m.boneRow0[off + 2]) * w
                    sy += (m.boneRow1[off] * m.localXs[v] + m.boneRow1[off + 1] * m.localYs[v] + m.boneRow1[off + 2]) * w
                }
                wx[v] = sx; wy[v] = sy
            }

            debugLineBuf.clear()
            var segVerts = 0
            val maxVerts = debugLineBuf.capacity() / 5
            fun pushLine(i0: Int, i1: Int) {
                if (segVerts + 2 > maxVerts) return
                debugLineBuf.put(wx[i0]); debugLineBuf.put(wy[i0]); debugLineBuf.put(z); debugLineBuf.put(0f); debugLineBuf.put(0f)
                debugLineBuf.put(wx[i1]); debugLineBuf.put(wy[i1]); debugLineBuf.put(z); debugLineBuf.put(0f); debugLineBuf.put(0f)
                segVerts += 2
            }

            val stride = m.cols + 1
            for (iy in 0..m.rows) {
                for (ix in 0 until m.cols) pushLine(iy * stride + ix, iy * stride + ix + 1)
            }
            for (iy in 0 until m.rows) {
                for (ix in 0..m.cols) pushLine(iy * stride + ix, (iy + 1) * stride + ix)
            }

            if (segVerts > 0) {
                debugLineBuf.position(0)
                GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, debugLineBuf)
                debugLineBuf.position(3)
                GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, debugLineBuf)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, segVerts)
                onDrawCall()
            }
        }
    }

    companion object {
        private const val MAX_BONES = 32

        private val SKIN_VS = """#version 300 es
            precision highp float;

            layout(location = 0) in vec2 aBindPos;
            layout(location = 1) in vec2 aTexCoord;
            layout(location = 2) in vec2 aGridUv;
            layout(location = 3) in vec4 aBoneIndices;
            layout(location = 4) in vec4 aBoneWeights;

            uniform mat4 uVP;
            uniform float uZ;
            uniform vec4 uBoneRow0[32];
            uniform vec4 uBoneRow1[32];
            uniform vec4 uWaveParams;
            uniform float uTime;

            out vec2 vTexCoord;
            out vec3 vWorldPos;

            vec2 applyBone(int idx, vec2 p) {
                vec4 r0 = uBoneRow0[idx];
                vec4 r1 = uBoneRow1[idx];
                return vec2(r0.x * p.x + r0.y * p.y + r0.z, r1.x * p.x + r1.y * p.y + r1.z);
            }

            void main() {
                int i0 = clamp(int(aBoneIndices.x + 0.5), 0, 31);
                int i1 = clamp(int(aBoneIndices.y + 0.5), 0, 31);
                int i2 = clamp(int(aBoneIndices.z + 0.5), 0, 31);
                int i3 = clamp(int(aBoneIndices.w + 0.5), 0, 31);

                vec2 pos = vec2(0.0);
                if (aBoneWeights.x > 0.0001) pos += applyBone(i0, aBindPos) * aBoneWeights.x;
                if (aBoneWeights.y > 0.0001) pos += applyBone(i1, aBindPos) * aBoneWeights.y;
                if (aBoneWeights.z > 0.0001) pos += applyBone(i2, aBindPos) * aBoneWeights.z;
                if (aBoneWeights.w > 0.0001) pos += applyBone(i3, aBindPos) * aBoneWeights.w;

                // GPU 程序化风浪与飘动扭曲
                if (abs(uWaveParams.x) > 0.001 || abs(uWaveParams.y) > 0.001) {
                    float phase = uTime * uWaveParams.w + aGridUv.y * uWaveParams.z + aGridUv.x * 1.7;
                    float weight = aGridUv.y;
                    pos.x += sin(phase) * uWaveParams.x * weight;
                    pos.y += cos(phase * 0.85) * uWaveParams.y * weight;
                }

                vWorldPos = vec3(pos, uZ);
                vTexCoord = aTexCoord;
                gl_Position = uVP * vec4(pos, uZ, 1.0);
            }
        """.trimIndent()

        private val SKIN_FS = """#version 300 es
            precision mediump float;

            uniform sampler2D uTexture;
            uniform vec4 uColor;
            uniform vec3 uAmbient;
            uniform int uLightCount;
            uniform vec4 uLightPos[8];
            uniform vec4 uLightColor[8];

            in vec2 vTexCoord;
            in vec3 vWorldPos;
            out vec4 fragColor;

            void main() {
                vec4 tex = texture(uTexture, vTexCoord) * uColor;
                if (tex.a < 0.01) discard;

                vec3 lightSum = uAmbient;
                for (int i = 0; i < 8; i++) {
                    if (i >= uLightCount) break;
                    float dist = length(vWorldPos.xy - uLightPos[i].xy);
                    float rad = max(1.0, uLightPos[i].w);
                    float atten = clamp(1.0 - dist / rad, 0.0, 1.0);
                    lightSum += uLightColor[i].rgb * (atten * atten);
                }

                fragColor = vec4(tex.rgb * lightSum, tex.a);
            }
        """.trimIndent()
    }
}
package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.*

/**
 * P10 次世代 2D 骨骼蒙皮与自由网格变形系统（2D Mesh Skinning & FFD）：
 * 1. 自动将任意节点剖分为高密度三角网格（支持图集/序列帧 UV 区域映射）
 * 2. 支持最多 4 骨骼线性混合蒙皮（Linear Blend Skinning），内置骨段距离平滑自动刷权重
 * 3. 支持正弦波浪顶点变形（呼吸、水波、风吹布料微颤）
 * 4. 完整接入场景多光源光照、受击闪白与实时三角网格线框调试
 */
class ZeroMeshSkinningSystem(
    private val onConsoleLog: (String) -> Unit
) {
    data class SkinnedVertex(
        val bindX: Float,
        val bindY: Float,
        val localNormX: Float,
        val localNormY: Float,
        val u: Float,
        val v: Float,
        val boneIndices: IntArray = intArrayOf(0, 0, 0, 0),
        val boneWeights: FloatArray = floatArrayOf(1f, 0f, 0f, 0f),
        var deformedX: Float = bindX,
        var deformedY: Float = bindY
    )

    data class BoneBindInfo(
        val nodeName: String,
        val bindPosX: Float,
        val bindPosY: Float,
        val invA: Float,
        val invB: Float,
        val invC: Float,
        val invD: Float,
        val invTx: Float,
        val invTy: Float,
        var deltaA: Float = 1f,
        var deltaB: Float = 0f,
        var deltaC: Float = 0f,
        var deltaD: Float = 1f,
        var deltaTx: Float = 0f,
        var deltaTy: Float = 0f
    )

    data class SkinnedMesh(
        val ownerNodeName: String,
        val cols: Int,
        val rows: Int,
        val vertices: Array<SkinnedVertex>,
        val indexCount: Int,
        val indexBuffer: ShortBuffer,
        val bones: MutableList<BoneBindInfo> = mutableListOf(),
        var waveAmpX: Float = 0f,
        var waveAmpY: Float = 0f,
        var waveFreq: Float = 6f,
        var waveSpeed: Float = 4f,
        var visible: Boolean = true
    )

    private val meshes = LinkedHashMap<String, SkinnedMesh>()
    private var simTime = 0f

    private var meshProgram = 0
    private var maxVertsCapacity = 2048
    private var vertexData = FloatArray(maxVertsCapacity * 5)
    private var vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    val activeMeshCount: Int get() = meshes.size

    fun initGL() {
        meshProgram = ZeroShaders.buildShaderProgram(MESH_VS, MESH_FS, onConsoleLog)
    }

    fun releaseGL() {
        if (meshProgram != 0) {
            GLES20.glDeleteProgram(meshProgram)
            meshProgram = 0
        }
    }

    fun clearAll() {
        meshes.clear()
    }

    fun hasMesh(nodeName: String): Boolean = meshes.containsKey(nodeName)

    fun removeMesh(nodeName: String) {
        meshes.remove(nodeName)
    }

    fun setMeshWave(nodeName: String, ampX: Float, ampY: Float, freq: Float = 6f, speed: Float = 4f) {
        meshes[nodeName]?.let {
            it.waveAmpX = ampX
            it.waveAmpY = ampY
            it.waveFreq = freq
            it.waveSpeed = speed
        }
    }

    /**
     * 为指定节点创建细分三角网格，记录当前世界变换下的绑定姿态（Bind Pose）
     */
    fun createGridMesh(
        owner: ZeroEngineView.GameNode,
        cols: Int,
        rows: Int,
        u0: Float = 0f,
        v0: Float = 0f,
        u1: Float = 1f,
        v1: Float = 1f,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): SkinnedMesh {
        val c = cols.coerceIn(1, 32)
        val r = rows.coerceIn(1, 32)
        val gt = getGlobalTransform(owner)
        val w = owner.width * gt.gSx
        val h = owner.height * gt.gSy
        val rad = Math.toRadians(gt.gRot.toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()

        val vertCount = (c + 1) * (r + 1)
        ensureBufferCapacity(vertCount)

        val verts = Array(vertCount) { idx ->
            val ix = idx % (c + 1)
            val iy = idx / (c + 1)
            val tx = ix.toFloat() / c.toFloat()
            val ty = iy.toFloat() / r.toFloat()

            val lx = (tx - 0.5f) * w
            val ly = (ty - 0.5f) * h
            val wx = gt.gx + (lx * cosR - ly * sinR)
            val wy = gt.gy + (lx * sinR + ly * cosR)

            SkinnedVertex(
                bindX = wx,
                bindY = wy,
                localNormX = tx,
                localNormY = ty,
                u = u0 + (u1 - u0) * tx,
                v = v0 + (v1 - v0) * ty
            )
        }

        val indices = ShortArray(c * r * 6)
        var ptr = 0
        for (iy in 0 until r) {
            for (ix in 0 until c) {
                val i0 = (iy * (c + 1) + ix).toShort()
                val i1 = (i0 + 1).toShort()
                val i2 = (i0 + (c + 1)).toShort()
                val i3 = (i2 + 1).toShort()
                indices[ptr++] = i0; indices[ptr++] = i1; indices[ptr++] = i2
                indices[ptr++] = i1; indices[ptr++] = i3; indices[ptr++] = i2
            }
        }

        val idxBuf = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(indices)
                position(0)
            }

        val mesh = SkinnedMesh(
            ownerNodeName = owner.name,
            cols = c,
            rows = r,
            vertices = verts,
            indexCount = indices.size,
            indexBuffer = idxBuf
        )
        meshes[owner.name] = mesh
        return mesh
    }

    /**
     * 将一组骨骼节点绑定到蒙皮网格，并基于骨段距离自动计算平滑蒙皮权重（Top-4 Bones）
     */
    fun bindBones(
        ownerNodeName: String,
        boneNodeNames: List<String>,
        nodes: List<ZeroEngineView.GameNode>,
        smoothness: Float = 2.2f,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Boolean {
        val mesh = meshes[ownerNodeName] ?: return false
        mesh.bones.clear()

        val boneNodes = mutableListOf<ZeroEngineView.GameNode>()
        for (bName in boneNodeNames) {
            val bn = nodes.find { it.name == bName } ?: continue
            val gt = getGlobalTransform(bn)
            val rad = Math.toRadians(gt.gRot.toDouble())
            val cosR = cos(rad).toFloat()
            val sinR = sin(rad).toFloat()

            val a = gt.gSx * cosR
            val b = gt.gSx * sinR
            val c = -gt.gSy * sinR
            val d = gt.gSy * cosR
            val tx = gt.gx
            val ty = gt.gy

            var det = a * d - b * c
            if (abs(det) < 1e-6f) det = if (det < 0f) -1e-6f else 1e-6f
            val invA = d / det
            val invB = -b / det
            val invC = -c / det
            val invD = a / det
            val invTx = -(invA * tx + invC * ty)
            val invTy = -(invB * tx + invD * ty)

            mesh.bones.add(BoneBindInfo(bName, tx, ty, invA, invB, invC, invD, invTx, invTy))
            boneNodes.add(bn)
        }

        if (mesh.bones.isEmpty()) return false

        // 为每根骨骼查找子骨骼终点（用于骨段胶囊距离计算，关节过渡更自然）
        val segEndX = FloatArray(mesh.bones.size)
        val segEndY = FloatArray(mesh.bones.size)
        for (i in mesh.bones.indices) {
            val bInfo = mesh.bones[i]
            val childIdx = boneNodes.indexOfFirst { it.parent == bInfo.nodeName }
            if (childIdx >= 0) {
                segEndX[i] = mesh.bones[childIdx].bindPosX
                segEndY[i] = mesh.bones[childIdx].bindPosY
            } else {
                segEndX[i] = bInfo.bindPosX
                segEndY[i] = bInfo.bindPosY
            }
        }

        val power = smoothness.coerceIn(0.5f, 6.0f)
        val rawWeights = FloatArray(mesh.bones.size)

        for (v in mesh.vertices) {
            for (i in mesh.bones.indices) {
                val bi = mesh.bones[i]
                val dist = distToSegment(v.bindX, v.bindY, bi.bindPosX, bi.bindPosY, segEndX[i], segEndY[i])
                rawWeights[i] = 1f / (dist + 8f).pow(power)
            }

            // 选出权重最大的前 4 根骨骼并归一化
            for (slot in 0..3) {
                v.boneIndices[slot] = 0
                v.boneWeights[slot] = 0f
            }
            var sumW = 0f
            val count = min(4, mesh.bones.size)
            for (slot in 0 until count) {
                var bestIdx = -1
                var bestVal = -1f
                for (i in rawWeights.indices) {
                    if (rawWeights[i] > bestVal) {
                        bestVal = rawWeights[i]
                        bestIdx = i
                    }
                }
                if (bestIdx >= 0 && bestVal > 0f) {
                    v.boneIndices[slot] = bestIdx
                    v.boneWeights[slot] = bestVal
                    sumW += bestVal
                    rawWeights[bestIdx] = -1f
                }
            }
            if (sumW > 1e-6f) {
                for (slot in 0 until count) v.boneWeights[slot] /= sumW
            } else {
                v.boneWeights[0] = 1f
            }
        }
        return true
    }

    /**
     * 每帧求解所有蒙皮网格的顶点变形（4 骨骼线性混合 + 正弦波浪偏移）
     */
    fun update(
        dt: Float,
        nodes: List<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        if (meshes.isEmpty()) return
        simTime += dt

        for (mesh in meshes.values) {
            if (!mesh.visible) continue
            val hasBones = mesh.bones.isNotEmpty()

            if (hasBones) {
                for (bi in mesh.bones) {
                    val bn = nodes.find { it.name == bi.nodeName } ?: continue
                    val gt = getGlobalTransform(bn)
                    val rad = Math.toRadians(gt.gRot.toDouble())
                    val cosR = cos(rad).toFloat()
                    val sinR = sin(rad).toFloat()

                    val ca = gt.gSx * cosR
                    val cb = gt.gSx * sinR
                    val cc = -gt.gSy * sinR
                    val cd = gt.gSy * cosR
                    val ctx = gt.gx
                    val cty = gt.gy

                    bi.deltaA = ca * bi.invA + cc * bi.invB
                    bi.deltaB = cb * bi.invA + cd * bi.invB
                    bi.deltaC = ca * bi.invC + cc * bi.invD
                    bi.deltaD = cb * bi.invC + cd * bi.invD
                    bi.deltaTx = ca * bi.invTx + cc * bi.invTy + ctx
                    bi.deltaTy = cb * bi.invTx + cd * bi.invTy + cty
                }
            }

            // 若无骨骼绑点，则直接跟随所属节点整体变换
            val ownerNode = nodes.find { it.name == mesh.ownerNodeName }
            val ownerGt = ownerNode?.let { getGlobalTransform(it) }
            val ow = (ownerNode?.width ?: 120f) * (ownerGt?.gSx ?: 1f)
            val oh = (ownerNode?.height ?: 120f) * (ownerGt?.gSy ?: 1f)
            val oRad = Math.toRadians((ownerGt?.gRot ?: 0f).toDouble())
            val oCos = cos(oRad).toFloat()
            val oSin = sin(oRad).toFloat()

            for (v in mesh.vertices) {
                var fx = 0f
                var fy = 0f
                if (hasBones) {
                    for (slot in 0..3) {
                        val w = v.boneWeights[slot]
                        if (w <= 0.0001f) continue
                        val b = mesh.bones[v.boneIndices[slot]]
                        fx += w * (b.deltaA * v.bindX + b.deltaC * v.bindY + b.deltaTx)
                        fy += w * (b.deltaB * v.bindX + b.deltaD * v.bindY + b.deltaTy)
                    }
                } else if (ownerGt != null) {
                    val lx = (v.localNormX - 0.5f) * ow
                    val ly = (v.localNormY - 0.5f) * oh
                    fx = ownerGt.gx + (lx * oCos - ly * oSin)
                    fy = ownerGt.gy + (lx * oSin + ly * oCos)
                } else {
                    fx = v.bindX
                    fy = v.bindY
                }

                if (mesh.waveAmpX != 0f || mesh.waveAmpY != 0f) {
                    val phase = simTime * mesh.waveSpeed + v.localNormY * mesh.waveFreq
                    fx += sin(phase.toDouble()).toFloat() * mesh.waveAmpX * v.localNormY
                    fy += cos(phase.toDouble()).toFloat() * mesh.waveAmpY * v.localNormX
                }

                v.deformedX = fx
                v.deformedY = fy
            }
        }
    }

    /**
     * 渲染所有激活的 2D 蒙皮网格（支持多光源、受击闪白与转场渐隐）
     */
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
        getTextureId: (String) -> Int,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform,
        onDrawCall: () -> Unit
    ) {
        if (meshes.isEmpty() || meshProgram == 0 || fadeAlpha <= 0.001f) return

        GLES20.glUseProgram(meshProgram)
        val posLoc = GLES20.glGetAttribLocation(meshProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(meshProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProgram, "uVP"), 1, false, vpMatrix, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(meshProgram, "uAmbient"), ambientR, ambientG, ambientB)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProgram, "uLightCount"), lightCount)
        if (lightCount > 0) {
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(meshProgram, "uLightPos"), lightCount, lightPos, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(meshProgram, "uLightColor"), lightCount, lightCol, 0)
        }

        for (mesh in meshes.values) {
            if (!mesh.visible) continue
            val owner = nodes.find { it.name == mesh.ownerNodeName } ?: continue
            if (!owner.visible) continue

            val gt = getGlobalTransform(owner)
            val z = gt.gz
            val verts = mesh.vertices
            ensureBufferCapacity(verts.size)

            var p = 0
            for (v in verts) {
                vertexData[p++] = v.deformedX
                vertexData[p++] = v.deformedY
                vertexData[p++] = z
                vertexData[p++] = v.u
                vertexData[p++] = v.v
            }

            vertexBuffer.clear()
            vertexBuffer.put(vertexData, 0, p)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer)
            vertexBuffer.position(3)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer)

            val flashVal = if (owner.flashTimer > 0f) (owner.flashTimer * 8f).coerceIn(0f, 1f) else 0f
            GLES20.glUniform4f(GLES20.glGetUniformLocation(meshProgram, "uColor"), owner.r, owner.g, owner.b, owner.a * fadeAlpha)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgram, "uFlash"), flashVal)

            val texId = if (owner.texture.isNotEmpty()) getTextureId(owner.texture) else whiteTexId
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProgram, "uTexture"), 0)

            mesh.indexBuffer.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indexBuffer)
            onDrawCall()
        }

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    /**
     * 开启 DebugDraw 时实时绘制变形后的三角网格线框
     */
    fun renderDebugWireframe(
        posLoc: Int,
        uvLoc: Int,
        colLoc: Int,
        nodes: List<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform,
        onDrawCall: () -> Unit
    ) {
        if (meshes.isEmpty()) return
        GLES20.glUniform4f(colLoc, 0.15f, 0.95f, 1.0f, 0.72f)
        for (mesh in meshes.values) {
            if (!mesh.visible) continue
            val owner = nodes.find { it.name == mesh.ownerNodeName } ?: continue
            if (!owner.visible) continue
            val z = getGlobalTransform(owner).gz + 5f
            val verts = mesh.vertices
            ensureBufferCapacity(verts.size)

            var p = 0
            for (v in verts) {
                vertexData[p++] = v.deformedX
                vertexData[p++] = v.deformedY
                vertexData[p++] = z
                vertexData[p++] = 0f
                vertexData[p++] = 0f
            }
            vertexBuffer.clear()
            vertexBuffer.put(vertexData, 0, p)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer)
            vertexBuffer.position(3)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer)

            mesh.indexBuffer.position(0)
            GLES20.glDrawElements(GLES20.GL_LINE_STRIP, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indexBuffer)
            onDrawCall()
        }
    }

    private fun ensureBufferCapacity(vertCount: Int) {
        if (vertCount <= maxVertsCapacity) return
        maxVertsCapacity = max(vertCount, maxVertsCapacity * 2)
        vertexData = FloatArray(maxVertsCapacity * 5)
        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-4f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    companion object {
        private const val MESH_VS = """
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uVP;
            varying vec2 vTexCoord;
            varying vec3 vWorldPos;
            void main() {
                vWorldPos = aPosition;
                vTexCoord = aTexCoord;
                gl_Position = uVP * vec4(aPosition, 1.0);
            }
        """

        private const val MESH_FS = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            uniform float uFlash;
            uniform vec3 uAmbient;
            uniform int uLightCount;
            uniform vec4 uLightPos[8];
            uniform vec4 uLightColor[8];
            varying vec2 vTexCoord;
            varying vec3 vWorldPos;
            void main() {
                vec4 base = texture2D(uTexture, vTexCoord) * uColor;
                if (base.a < 0.01) discard;
                vec3 totalLight = uAmbient;
                for (int i = 0; i < 8; i++) {
                    if (i >= uLightCount) break;
                    float dist = distance(vWorldPos.xy, uLightPos[i].xy);
                    float radius = max(1.0, uLightPos[i].w);
                    float atten = clamp(1.0 - (dist / radius), 0.0, 1.0);
                    atten = atten * atten;
                    totalLight += uLightColor[i].rgb * atten;
                }
                vec3 litRgb = base.rgb * totalLight;
                vec3 finalRgb = mix(litRgb, vec3(1.0, 1.0, 1.0), clamp(uFlash, 0.0, 1.0));
                gl_FragColor = vec4(finalRgb, base.a);
            }
        """
    }
}

package com.zero.engine

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/**
 * P18-A 原生 AI 导航与潜行感知系统：
 * 1. 自动从 TileMap 与静态碰撞体烘焙 2D 寻路网格，提供高性能原生 A* 寻路与路径拉直平滑
 * 2. 带精确墙体遮挡检测的扇形视野判定（canSee）
 * 3. 带生命周期的声音波纹广播与听觉检索（emitSound / hearSounds）
 * 4. 支持在调试线框模式下实时绘制 A* 路径与声音扩散圈
 */
class ZeroAiNavSystem(
    private val onConsoleLog: (String) -> Unit
) {
    data class SoundEvent(
        val x: Float,
        val y: Float,
        val radius: Float,
        val tag: String,
        val sourceName: String,
        var ttl: Float,
        val maxTtl: Float
    )

    data class HeardSound(
        val x: Float,
        val y: Float,
        val radius: Float,
        val tag: String,
        val sourceName: String,
        val dist: Float
    )

    private var cellSize = 48f
    private var originX = 0f
    private var originY = 0f
    private var gridW = 0
    private var gridH = 0
    private var blocked = BooleanArray(0)
    var isBaked: Boolean = false
        private set

    private val sounds = mutableListOf<SoundEvent>()
    private var lastDebugPath: List<Pair<Float, Float>> = emptyList()

    private val debugBuf: FloatBuffer = ByteBuffer.allocateDirect(2048 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    val activeSoundCount: Int get() = sounds.size

    fun clear() {
        isBaked = false
        gridW = 0
        gridH = 0
        blocked = BooleanArray(0)
        sounds.clear()
        lastDebugPath = emptyList()
    }

    fun clearSounds() {
        sounds.clear()
    }

    fun update(dt: Float) {
        if (sounds.isEmpty()) return
        val it = sounds.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.ttl -= dt
            if (s.ttl <= 0f) it.remove()
        }
    }

    /**
     * 自动扫描场景中所有有效碰撞体与 TileMap，烘焙 2D 寻路网格
     */
    fun bakeNavGrid(
        nodes: List<ZeroEngineView.GameNode>,
        pendingRemove: Set<ZeroEngineView.GameNode>,
        requestedCellSize: Float,
        agentPadding: Float,
        colMask: Int,
        physicsSystem: ZeroPhysicsSystem,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Int {
        val baseCell = requestedCellSize.coerceIn(16f, 256f)
        val pad = agentPadding.coerceIn(0f, 128f)

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        val solidNodes = nodes.filter { n ->
            !pendingRemove.contains(n) && n.visible && n.layer == "World" &&
                n.collider && !n.isTrigger && !n.oneWay && (n.colLayer and colMask) != 0
        }

        // 先统计场景边界（包含所有世界节点，保证可行走区域完整覆盖）
        nodes.forEach { n ->
            if (pendingRemove.contains(n) || n.layer != "World") return@forEach
            val gt = getGlobalTransform(n)
            if (n.nodeType == "TileMap" && n.tiles.isNotEmpty()) {
                val ts = max(8f, n.tileSize)
                n.tiles.forEach { c ->
                    val x0 = gt.gx + c.gx * ts
                    val y0 = gt.gy + c.gy * ts
                    minX = min(minX, x0); maxX = max(maxX, x0 + ts)
                    minY = min(minY, y0); maxY = max(maxY, y0 + ts)
                }
            } else {
                val hw = abs(n.width * gt.gSx) * 0.5f
                val hh = abs(n.height * gt.gSy) * 0.5f
                minX = min(minX, gt.gx - hw); maxX = max(maxX, gt.gx + hw)
                minY = min(minY, gt.gy - hh); maxY = max(maxY, gt.gy + hh)
            }
        }

        if (!minX.isFinite() || !minY.isFinite()) {
            minX = -600f; maxX = 1800f; minY = -600f; maxY = 2400f
        }

        val margin = baseCell * 4f
        minX -= margin; minY -= margin; maxX += margin; maxY += margin

        // 防止超大场景导致内存膨胀，自动放大格距限制在 240x240 以内
        var actualCell = baseCell
        var w = ceil((maxX - minX) / actualCell).toInt().coerceAtLeast(8)
        var h = ceil((maxY - minY) / actualCell).toInt().coerceAtLeast(8)
        if (w > MAX_GRID_DIM || h > MAX_GRID_DIM) {
            val scale = max(w.toFloat() / MAX_GRID_DIM, h.toFloat() / MAX_GRID_DIM)
            actualCell *= scale
            w = ceil((maxX - minX) / actualCell).toInt().coerceIn(8, MAX_GRID_DIM)
            h = ceil((maxY - minY) / actualCell).toInt().coerceIn(8, MAX_GRID_DIM)
        }

        cellSize = actualCell
        originX = minX
        originY = minY
        gridW = w
        gridH = h
        blocked = BooleanArray(w * h)

        var blockedCount = 0
        fun markRect(rx0: Float, ry0: Float, rx1: Float, ry1: Float) {
            val gx0 = floor((rx0 - originX) / cellSize).toInt().coerceIn(0, gridW - 1)
            val gx1 = floor((rx1 - originX) / cellSize).toInt().coerceIn(0, gridW - 1)
            val gy0 = floor((ry0 - originY) / cellSize).toInt().coerceIn(0, gridH - 1)
            val gy1 = floor((ry1 - originY) / cellSize).toInt().coerceIn(0, gridH - 1)
            for (gy in gy0..gy1) {
                val rowOff = gy * gridW
                for (gx in gx0..gx1) {
                    val idx = rowOff + gx
                    if (!blocked[idx]) {
                        blocked[idx] = true
                        blockedCount++
                    }
                }
            }
        }

        solidNodes.forEach { n ->
            if (n.nodeType == "TileMap") {
                val gt = getGlobalTransform(n)
                val ts = max(8f, n.tileSize)
                n.tiles.forEach { c ->
                    val x0 = gt.gx + c.gx * ts - pad
                    val y0 = gt.gy + c.gy * ts - pad
                    markRect(x0, y0, x0 + ts + pad * 2f, y0 + ts + pad * 2f)
                }
            } else {
                val cb = physicsSystem.getColliderBounds(n, getGlobalTransform)
                markRect(cb.cx - cb.hw - pad, cb.cy - cb.hh - pad, cb.cx + cb.hw + pad, cb.cy + cb.hh + pad)
            }
        }

        isBaked = true
        onConsoleLog("[AI-NAV] 寻路网格烘焙完成: ${gridW}x${gridH} (格距 ${cellSize.toInt()}px, 障碍格 $blockedCount)")
        return gridW * gridH - blockedCount
    }

    fun isWalkable(worldX: Float, worldY: Float): Boolean {
        if (!isBaked) return true
        val gx = floor((worldX - originX) / cellSize).toInt()
        val gy = floor((worldY - originY) / cellSize).toInt()
        if (gx !in 0 until gridW || gy !in 0 until gridH) return false
        return !blocked[gy * gridW + gx]
    }

    /**
     * 高性能原生 A* 寻路（内置贴墙吸附 + 视线拉直平滑）
     */
    fun findPath(
        startX: Float,
        startY: Float,
        goalX: Float,
        goalY: Float,
        allowDiagonal: Boolean = true
    ): List<Pair<Float, Float>> {
        if (!isBaked || gridW <= 0 || gridH <= 0) {
            return listOf(goalX to goalY)
        }

        var sx = floor((startX - originX) / cellSize).toInt().coerceIn(0, gridW - 1)
        var sy = floor((startY - originY) / cellSize).toInt().coerceIn(0, gridH - 1)
        var gx = floor((goalX - originX) / cellSize).toInt().coerceIn(0, gridW - 1)
        var gy = floor((goalY - originY) / cellSize).toInt().coerceIn(0, gridH - 1)

        if (blocked[sy * gridW + sx]) {
            val snapped = findNearestWalkable(sx, sy, 4) ?: return emptyList()
            sx = snapped.first; sy = snapped.second
        }
        if (blocked[gy * gridW + gx]) {
            val snapped = findNearestWalkable(gx, gy, 4) ?: return emptyList()
            gx = snapped.first; gy = snapped.second
        }

        val startIdx = sy * gridW + sx
        val goalIdx = gy * gridW + gx
        if (startIdx == goalIdx) {
            val res = listOf(goalX to goalY)
            lastDebugPath = listOf(startX to startY, goalX to goalY)
            return res
        }

        val total = gridW * gridH
        val gScore = FloatArray(total) { Float.POSITIVE_INFINITY }
        val parent = IntArray(total) { -1 }
        val closed = BooleanArray(total)

        // 原始类型最小堆（零节点对象分配）
        val heapNodes = IntArray(total)
        val heapF = FloatArray(total)
        var heapSize = 0

        fun heapPush(nodeIdx: Int, fVal: Float) {
            if (heapSize >= total) return
            var i = heapSize++
            heapNodes[i] = nodeIdx
            heapF[i] = fVal
            while (i > 0) {
                val p = (i - 1) ushr 1
                if (heapF[p] <= heapF[i]) break
                val tn = heapNodes[p]; heapNodes[p] = heapNodes[i]; heapNodes[i] = tn
                val tf = heapF[p]; heapF[p] = heapF[i]; heapF[i] = tf
                i = p
            }
        }

        fun heapPop(): Int {
            val top = heapNodes[0]
            heapSize--
            if (heapSize > 0) {
                heapNodes[0] = heapNodes[heapSize]
                heapF[0] = heapF[heapSize]
                var i = 0
                while (true) {
                    val l = (i shl 1) + 1
                    val r = l + 1
                    var smallest = i
                    if (l < heapSize && heapF[l] < heapF[smallest]) smallest = l
                    if (r < heapSize && heapF[r] < heapF[smallest]) smallest = r
                    if (smallest == i) break
                    val tn = heapNodes[i]; heapNodes[i] = heapNodes[smallest]; heapNodes[smallest] = tn
                    val tf = heapF[i]; heapF[i] = heapF[smallest]; heapF[smallest] = tf
                    i = smallest
                }
            }
            return top
        }

        fun heuristic(x: Int, y: Int): Float {
            val dx = abs(x - gx).toFloat()
            val dy = abs(y - gy).toFloat()
            return if (allowDiagonal) {
                val mn = min(dx, dy)
                (dx + dy) + (1.4142135f - 2f) * mn
            } else {
                dx + dy
            }
        }

        gScore[startIdx] = 0f
        heapPush(startIdx, heuristic(sx, sy))

        val dirsX = if (allowDiagonal) intArrayOf(1, -1, 0, 0, 1, 1, -1, -1) else intArrayOf(1, -1, 0, 0)
        val dirsY = if (allowDiagonal) intArrayOf(0, 0, 1, -1, 1, -1, 1, -1) else intArrayOf(0, 0, 1, -1)
        val dirsCost = if (allowDiagonal) floatArrayOf(1f, 1f, 1f, 1f, 1.4142135f, 1.4142135f, 1.4142135f, 1.4142135f) else floatArrayOf(1f, 1f, 1f, 1f)

        var found = false
        var expanded = 0
        val maxExpand = 12000

        while (heapSize > 0 && expanded++ < maxExpand) {
            val curr = heapPop()
            if (closed[curr]) continue
            if (curr == goalIdx) {
                found = true
                break
            }
            closed[curr] = true

            val cx = curr % gridW
            val cy = curr / gridW
            val currG = gScore[curr]

            for (d in dirsX.indices) {
                val nx = cx + dirsX[d]
                val ny = cy + dirsY[d]
                if (nx !in 0 until gridW || ny !in 0 until gridH) continue
                val nIdx = ny * gridW + nx
                if (closed[nIdx] || blocked[nIdx]) continue

                // 对角移动禁止切墙角
                if (d >= 4) {
                    if (blocked[cy * gridW + nx] || blocked[ny * gridW + cx]) continue
                }

                val tentativeG = currG + dirsCost[d]
                if (tentativeG < gScore[nIdx]) {
                    gScore[nIdx] = tentativeG
                    parent[nIdx] = curr
                    heapPush(nIdx, tentativeG + heuristic(nx, ny))
                }
            }
        }

        if (!found) return emptyList()

        // 回溯网格路径
        val rawCells = ArrayList<Int>(64)
        var cur = goalIdx
        while (cur != -1) {
            rawCells.add(cur)
            if (cur == startIdx) break
            cur = parent[cur]
        }
        rawCells.reverse()

        // 视线拉直平滑（剔除多余锯齿拐点）
        val smoothed = ArrayList<Int>(rawCells.size)
        var anchor = 0
        smoothed.add(rawCells[0])
        while (anchor < rawCells.size - 1) {
            var next = rawCells.size - 1
            while (next > anchor + 1) {
                val ax = rawCells[anchor] % gridW
                val ay = rawCells[anchor] / gridW
                val bx = rawCells[next] % gridW
                val by = rawCells[next] / gridW
                if (gridLineClear(ax, ay, bx, by)) break
                next--
            }
            smoothed.add(rawCells[next])
            anchor = next
        }

        val worldPath = ArrayList<Pair<Float, Float>>(smoothed.size)
        for (i in 1 until smoothed.size) {
            if (i == smoothed.size - 1) {
                worldPath.add(goalX to goalY)
            } else {
                val cell = smoothed[i]
                val wx = originX + (cell % gridW + 0.5f) * cellSize
                val wy = originY + (cell / gridW + 0.5f) * cellSize
                worldPath.add(wx to wy)
            }
        }

        lastDebugPath = buildList {
            add(startX to startY)
            addAll(worldPath)
        }
        return worldPath
    }

    private fun findNearestWalkable(cx: Int, cy: Int, maxRadius: Int): Pair<Int, Int>? {
        for (r in 1..maxRadius) {
            for (dy in -r..r) {
                for (dx in -r..r) {
                    if (abs(dx) != r && abs(dy) != r) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx in 0 until gridW && ny in 0 until gridH && !blocked[ny * gridW + nx]) {
                        return nx to ny
                    }
                }
            }
        }
        return null
    }

    private fun gridLineClear(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        var cx = x0
        var cy = y0
        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy

        while (true) {
            if (blocked[cy * gridW + cx]) return false
            if (cx == x1 && cy == y1) return true
            val e2 = err * 2
            var movedX = false
            var movedY = false
            if (e2 > -dy) { err -= dy; cx += sx; movedX = true }
            if (e2 < dx) { err += dx; cy += sy; movedY = true }
            if (movedX && movedY) {
                if (blocked[cy * gridW + (cx - sx)] || blocked[(cy - sy) * gridW + cx]) return false
            }
        }
    }

    /**
     * 潜行扇形视野 + 精确墙体遮挡检测
     */
    fun canSee(
        viewer: ZeroEngineView.GameNode,
        target: ZeroEngineView.GameNode,
        range: Float,
        fovDeg: Float,
        facingDegOverride: Float,
        wallMask: Int,
        nodes: List<ZeroEngineView.GameNode>,
        pendingRemove: Set<ZeroEngineView.GameNode>,
        physicsSystem: ZeroPhysicsSystem,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Boolean {
        val vGt = getGlobalTransform(viewer)
        val tGt = getGlobalTransform(target)
        val dx = tGt.gx - vGt.gx
        val dy = tGt.gy - vGt.gy
        val dist = hypot(dx, dy)
        if (dist > range) return false
        if (dist < 1f) return true

        // 1. 扇形视锥角度判定
        val clampedFov = fovDeg.coerceIn(1f, 360f)
        if (clampedFov < 359.5f) {
            val facing = if (facingDegOverride < 900000f) {
                facingDegOverride
            } else if (abs(vGt.gRot) > 0.01f) {
                vGt.gRot
            } else if (vGt.gSx < 0f) {
                180f
            } else {
                0f
            }
            val angleToTarget = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            var diff = (angleToTarget - facing) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            if (abs(diff) > clampedFov * 0.5f) return false
        }

        // 2. 射线-碰撞盒遮挡判定（自动跳过观察者与目标自身）
        val segMinX = min(vGt.gx, tGt.gx)
        val segMaxX = max(vGt.gx, tGt.gx)
        val segMinY = min(vGt.gy, tGt.gy)
        val segMaxY = max(vGt.gy, tGt.gy)

        for (n in nodes) {
            if (n === viewer || n === target || pendingRemove.contains(n)) continue
            if (!n.visible || n.layer != "World" || !n.collider || n.isTrigger || n.oneWay) continue
            if ((n.colLayer and wallMask) == 0) continue

            if (n.nodeType == "TileMap") {
                val ngt = getGlobalTransform(n)
                val ts = max(8f, n.tileSize)
                for (c in n.tiles) {
                    val x0 = ngt.gx + c.gx * ts
                    val y0 = ngt.gy + c.gy * ts
                    val x1 = x0 + ts
                    val y1 = y0 + ts
                    if (x1 < segMinX || x0 > segMaxX || y1 < segMinY || y0 > segMaxY) continue
                    if (segmentIntersectsAabb(vGt.gx, vGt.gy, tGt.gx, tGt.gy, x0, y0, x1, y1)) {
                        return false
                    }
                }
            } else {
                val cb = physicsSystem.getColliderBounds(n, getGlobalTransform)
                val x0 = cb.cx - cb.hw; val x1 = cb.cx + cb.hw
                val y0 = cb.cy - cb.hh; val y1 = cb.cy + cb.hh
                if (x1 < segMinX || x0 > segMaxX || y1 < segMinY || y0 > segMaxY) continue
                if (segmentIntersectsAabb(vGt.gx, vGt.gy, tGt.gx, tGt.gy, x0, y0, x1, y1)) {
                    return false
                }
            }
        }
        return true
    }

    private fun segmentIntersectsAabb(
        x1: Float, y1: Float, x2: Float, y2: Float,
        minX: Float, minY: Float, maxX: Float, maxY: Float
    ): Boolean {
        var tMin = 0f
        var tMax = 1f
        val dx = x2 - x1
        val dy = y2 - y1

        if (abs(dx) < 1e-6f) {
            if (x1 < minX || x1 > maxX) return false
        } else {
            val invD = 1f / dx
            var t1 = (minX - x1) * invD
            var t2 = (maxX - x1) * invD
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = max(tMin, t1)
            tMax = min(tMax, t2)
            if (tMin > tMax) return false
        }

        if (abs(dy) < 1e-6f) {
            if (y1 < minY || y1 > maxY) return false
        } else {
            val invD = 1f / dy
            var t1 = (minY - y1) * invD
            var t2 = (maxY - y1) * invD
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = max(tMin, t1)
            tMax = min(tMax, t2)
            if (tMin > tMax) return false
        }
        return true
    }

    /**
     * 发出声音波纹事件（用于潜行听声辨位）
     */
    fun emitSound(x: Float, y: Float, radius: Float, tag: String, sourceName: String, duration: Float) {
        if (sounds.size >= 64) sounds.removeAt(0)
        val dur = duration.coerceIn(0.05f, 10f)
        sounds.add(SoundEvent(x, y, max(1f, radius), tag, sourceName, dur, dur))
    }

    /**
     * 检索某位置当前能听到的所有声音（按距离升序返回）
     */
    fun hearSounds(listenerX: Float, listenerY: Float, hearingMul: Float, filterTag: String): List<HeardSound> {
        if (sounds.isEmpty()) return emptyList()
        val mul = hearingMul.coerceIn(0.05f, 5f)
        val result = ArrayList<HeardSound>()
        for (s in sounds) {
            if (filterTag.isNotEmpty() && s.tag != filterTag) continue
            val d = hypot(s.x - listenerX, s.y - listenerY)
            if (d <= s.radius * mul) {
                result.add(HeardSound(s.x, s.y, s.radius, s.tag, s.sourceName, d))
            }
        }
        result.sortBy { it.dist }
        return result
    }

    /**
     * 调试线框模式：绘制最新 A* 寻路折线（金色）与声音波纹（橙色圆环）
     */
    fun renderDebug(
        posLoc: Int,
        uvLoc: Int,
        colLoc: Int,
        onDrawCall: () -> Unit
    ) {
        val path = lastDebugPath
        val maxVerts = debugBuf.capacity() / 5

        if (path.size >= 2) {
            debugBuf.clear()
            var vCount = 0
            for (i in 0 until path.size - 1) {
                if (vCount + 2 > maxVerts) break
                val (x0, y0) = path[i]
                val (x1, y1) = path[i + 1]
                debugBuf.put(x0); debugBuf.put(y0); debugBuf.put(12f); debugBuf.put(0f); debugBuf.put(0f)
                debugBuf.put(x1); debugBuf.put(y1); debugBuf.put(12f); debugBuf.put(0f); debugBuf.put(0f)
                vCount += 2
            }
            if (vCount > 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                debugBuf.position(0)
                GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, debugBuf)
                debugBuf.position(3)
                GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, debugBuf)
                GLES20.glUniform4f(colLoc, 1.0f, 0.85f, 0.15f, 0.95f)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, vCount)
                onDrawCall()
            }
        }

        if (sounds.isNotEmpty()) {
            debugBuf.clear()
            var vCount = 0
            val segs = 16
            val step = (Math.PI * 2.0 / segs).toFloat()
            for (s in sounds) {
                val progress = 1f - (s.ttl / max(0.01f, s.maxTtl)).coerceIn(0f, 1f)
                val curRad = s.radius * (0.25f + 0.75f * progress)
                for (i in 0 until segs) {
                    if (vCount + 2 > maxVerts) break
                    val a0 = i * step
                    val a1 = (i + 1) * step
                    debugBuf.put(s.x + cos(a0) * curRad); debugBuf.put(s.y + sin(a0) * curRad); debugBuf.put(13f); debugBuf.put(0f); debugBuf.put(0f)
                    debugBuf.put(s.x + cos(a1) * curRad); debugBuf.put(s.y + sin(a1) * curRad); debugBuf.put(13f); debugBuf.put(0f); debugBuf.put(0f)
                    vCount += 2
                }
            }
            if (vCount > 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                debugBuf.position(0)
                GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, debugBuf)
                debugBuf.position(3)
                GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, debugBuf)
                GLES20.glUniform4f(colLoc, 1.0f, 0.45f, 0.15f, 0.9f)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, vCount)
                onDrawCall()
            }
        }
    }

    companion object {
        private const val MAX_GRID_DIM = 240
    }
}
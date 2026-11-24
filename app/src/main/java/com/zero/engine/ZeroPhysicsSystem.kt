package com.zero.engine

import android.view.MotionEvent
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

class ZeroPhysicsSystem(
    private val onConsoleLog: (String) -> Unit
) {
    data class ColliderBounds(
        val cx: Float,
        val cy: Float,
        val hw: Float,
        val hh: Float
    )

    data class DebugRay(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val hit: Boolean,
        var ttl: Float = 0.16f
    )

    private val spatialCellSize = 256f
    private val spatialGrid = HashMap<Long, MutableList<ZeroEngineView.GameNode>>(256)
    private val activeTileMaps = mutableListOf<ZeroEngineView.GameNode>()
    private val queryCandidates = LinkedHashSet<ZeroEngineView.GameNode>(32)

    var debugDrawEnabled = false
    val debugRays = mutableListOf<DebugRay>()
    val debugVertBuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 5 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    var spatialCellsCount: Int = 0
        private set

    fun packGridKey(gx: Int, gy: Int): Long =
        (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)

    fun clearRays() {
        debugRays.clear()
    }

    fun updateRays(rawDt: Float) {
        if (debugRays.isEmpty()) return
        val iter = debugRays.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            r.ttl -= rawDt
            if (r.ttl <= 0f) iter.remove()
        }
    }

    fun getColliderBounds(
        n: ZeroEngineView.GameNode,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): ColliderBounds {
        val gt = getGlobalTransform(n)
        val rawW = if (n.colWidth > 0f) n.colWidth else n.width
        val rawH = if (n.colHeight > 0f) n.colHeight else n.height
        val hw = rawW * abs(gt.gSx) * 0.5f
        val hh = rawH * abs(gt.gSy) * 0.5f
        val cx = gt.gx + n.colOffsetX * gt.gSx
        val cy = gt.gy + n.colOffsetY * gt.gSy
        return ColliderBounds(cx, cy, hw, hh)
    }

    fun getFrameBoxWorldBounds(
        owner: ZeroEngineView.GameNode,
        box: FrameBox,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): ColliderBounds {
        val gt = getGlobalTransform(owner)
        val dirX = if (gt.gSx < 0f) -1f else 1f
        val scaleAbsX = abs(gt.gSx)
        val scaleAbsY = abs(gt.gSy)
        val cx = gt.gx + box.offsetX * scaleAbsX * dirX
        val cy = gt.gy + box.offsetY * scaleAbsY
        return ColliderBounds(cx, cy, box.width * scaleAbsX * 0.5f, box.height * scaleAbsY * 0.5f)
    }

    private fun canCollideLayer(mover: ZeroEngineView.GameNode, target: ZeroEngineView.GameNode): Boolean =
        (mover.colMask and target.colLayer) != 0

    fun rebuildSpatialIndex(
        nodes: List<ZeroEngineView.GameNode>,
        pendingRemove: Set<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ) {
        spatialGrid.clear()
        activeTileMaps.clear()
        for (n in nodes) {
            if (!n.visible || pendingRemove.contains(n)) continue
            val hasFrameBox = n.animator.activeFrame?.boxes?.isNotEmpty() == true
            if (!n.collider && !n.isHitbox && !n.isHurtbox && !hasFrameBox) continue
            if (n.nodeType == "TileMap") {
                if (n.tiles.isNotEmpty()) activeTileMaps.add(n)
                continue
            }
            val b = getColliderBounds(n, getGlobalTransform)
            val minCx = floor((b.cx - b.hw) / spatialCellSize).toInt()
            val maxCx = floor((b.cx + b.hw) / spatialCellSize).toInt()
            val minCy = floor((b.cy - b.hh) / spatialCellSize).toInt()
            val maxCy = floor((b.cy + b.hh) / spatialCellSize).toInt()
            for (cx in minCx..maxCx) {
                for (cy in minCy..maxCy) {
                    spatialGrid.getOrPut(packGridKey(cx, cy)) { mutableListOf() }.add(n)
                }
            }
        }
        spatialCellsCount = spatialGrid.size
    }

    private fun querySpatialCandidates(minX: Float, maxX: Float, minY: Float, maxY: Float, out: MutableSet<ZeroEngineView.GameNode>) {
        out.clear()
        val minCx = floor(minX / spatialCellSize).toInt()
        val maxCx = floor(maxX / spatialCellSize).toInt()
        val minCy = floor(minY / spatialCellSize).toInt()
        val maxCy = floor(maxY / spatialCellSize).toInt()
        for (cx in minCx..maxCx) {
            for (cy in minCy..maxCy) {
                val bucket = spatialGrid[packGridKey(cx, cy)] ?: continue
                for (i in bucket.indices) out.add(bucket[i])
            }
        }
        for (tm in activeTileMaps) out.add(tm)
    }

    private fun computeSlopeSurfaceY(
        slope: ZeroEngineView.GameNode,
        footX: Float,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Float {
        val sb = getColliderBounds(slope, getGlobalTransform)
        val t = ((footX - (sb.cx - sb.hw)) / max(1f, sb.hw * 2f)).coerceIn(0f, 1f)
        return if (slope.slopeType == "left_up") (sb.cy + sb.hh) - t * (sb.hh * 2f) else (sb.cy - sb.hh) + t * (sb.hh * 2f)
    }

    fun checkOverlap(
        a: ZeroEngineView.GameNode,
        b: ZeroEngineView.GameNode,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Boolean {
        val ba = getColliderBounds(a, getGlobalTransform)
        if (b.nodeType == "TileMap") {
            val gb = getGlobalTransform(b)
            val ts = max(8f, b.tileSize)
            for (gx in floor((ba.cx - ba.hw - gb.gx) / ts).toInt()..floor((ba.cx + ba.hw - gb.gx) / ts).toInt()) {
                for (gy in floor((ba.cy - ba.hh - gb.gy) / ts).toInt()..floor((ba.cy + ba.hh - gb.gy) / ts).toInt()) {
                    if (b.tileLookup.containsKey(packGridKey(gx, gy))) return true
                }
            }
            return false
        }
        val bb = getColliderBounds(b, getGlobalTransform)
        return abs(ba.cx - bb.cx) < (ba.hw + bb.hw) && abs(ba.cy - bb.cy) < (ba.hh + bb.hh)
    }

    private fun overlapBounds(a: ColliderBounds, b: ColliderBounds): Boolean =
        abs(a.cx - b.cx) < (a.hw + b.hw) && abs(a.cy - b.cy) < (a.hh + b.hh)

    fun moveAndSlide(
        n: ZeroEngineView.GameNode,
        dt: Float,
        pendingRemove: Set<ZeroEngineView.GameNode>,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform,
        callLifecycle: (ZeroEngineView.GameNode, String, Any?) -> Unit
    ) {
        val collidedSolids = mutableSetOf<ZeroEngineView.GameNode>()
        if (n.bodyMode == "platformer" || n.bodyMode == "rigid") {
            val gMul = if (n.vy > 0f) n.fallMultiplier else 1f
            n.vy = min(n.maxFallSpeed, n.vy + n.gravity * gMul * dt)
            if (n.drag > 0f) n.vx *= max(0f, 1f - n.drag * dt)
        }

        val startBounds = getColliderBounds(n, getGlobalTransform)
        val prevBottom = startBounds.cy + startBounds.hh
        val sweepMinX = min(startBounds.cx, startBounds.cx + n.vx * dt) - startBounds.hw - 32f
        val sweepMaxX = max(startBounds.cx, startBounds.cx + n.vx * dt) + startBounds.hw + 32f
        val sweepMinY = min(startBounds.cy, startBounds.cy + n.vy * dt) - startBounds.hh - 32f
        val sweepMaxY = max(startBounds.cy, startBounds.cy + n.vy * dt) + startBounds.hh + 32f
        querySpatialCandidates(sweepMinX, sweepMaxX, sweepMinY, sweepMaxY, queryCandidates)
        val nearby = queryCandidates.toList()

        n.x += n.vx * dt
        var climbedSlope = false
        if (n.collider && !n.isTrigger) {
            for (other in nearby) {
                if (other === n || !other.visible || !other.collider || other.isTrigger || other.oneWay || other.layer != n.layer) continue
                if (!canCollideLayer(n, other)) continue
                if (other.slopeType == "left_up" || other.slopeType == "right_up") {
                    if (checkOverlap(n, other, getGlobalTransform)) {
                        val cb = getColliderBounds(n, getGlobalTransform)
                        val surfY = computeSlopeSurfaceY(other, cb.cx, getGlobalTransform)
                        val footY = cb.cy + cb.hh
                        if (footY >= surfY - 18f && n.vy >= -40f) {
                            n.y += (surfY - footY); n.vy = 0f; n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax
                            climbedSlope = true; collidedSolids.add(other)
                        }
                    }
                } else if (checkOverlap(n, other, getGlobalTransform)) {
                    n.x -= n.vx * dt
                    if (n.bodyMode == "rigid") n.vx = -n.vx * n.bounciness else n.vx = 0f
                    collidedSolids.add(other)
                }
            }
        }

        val wasGrounded = n.isGrounded
        if (!climbedSlope) n.isGrounded = false
        n.y += n.vy * dt
        if (n.collider && !n.isTrigger) {
            for (other in nearby) {
                if (other === n || !other.visible || !other.collider || other.isTrigger || other.layer != n.layer) continue
                if (!canCollideLayer(n, other)) continue
                if (other.slopeType == "left_up" || other.slopeType == "right_up") {
                    val cb = getColliderBounds(n, getGlobalTransform)
                    val surfY = computeSlopeSurfaceY(other, cb.cx, getGlobalTransform)
                    val footY = cb.cy + cb.hh
                    if (checkOverlap(n, other, getGlobalTransform) && footY >= surfY && n.vy >= 0f) {
                        n.y += (surfY - footY); n.vy = 0f; n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax
                        collidedSolids.add(other)
                    } else if (wasGrounded && n.vy >= 0f && !n.isGrounded) {
                        val sb = getColliderBounds(other, getGlobalTransform)
                        if (abs(cb.cx - sb.cx) <= sb.hw && surfY - footY in 0f..24f) {
                            n.y += (surfY - footY); n.vy = 0f; n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax
                            collidedSolids.add(other)
                        }
                    }
                } else if (other.oneWay) {
                    if (n.vy >= 0f && n.dropThroughTimer <= 0f) {
                        val otherTop = getColliderBounds(other, getGlobalTransform).let { it.cy - it.hh }
                        if (prevBottom <= otherTop + 14f && checkOverlap(n, other, getGlobalTransform)) {
                            n.y -= n.vy * dt; n.vy = 0f; n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax
                            collidedSolids.add(other)
                        }
                    }
                } else if (checkOverlap(n, other, getGlobalTransform)) {
                    val fallingDown = n.vy > 0f
                    n.y -= n.vy * dt
                    if (fallingDown) { n.isGrounded = true; n.jumpCount = 0; n.coyoteTimer = n.coyoteTimeMax }
                    if (n.bodyMode == "rigid" && abs(n.vy) > 90f) n.vy = -n.vy * n.bounciness else n.vy = 0f
                    collidedSolids.add(other)
                }
            }
        }

        if (!n.isGrounded) {
            if (wasGrounded) n.coyoteTimer = n.coyoteTimeMax else n.coyoteTimer = max(0f, n.coyoteTimer - dt)
        }
        n.z += n.vz * dt

        for (other in collidedSolids) {
            if (!pendingRemove.contains(n) && !pendingRemove.contains(other)) {
                callLifecycle(n, "onCollision", other.jsObj)
                callLifecycle(other, "onCollision", n.jsObj)
            }
        }
        if (n.collider) {
            for (other in nearby) {
                if (other !== n && other.visible && other.collider && other.layer == n.layer && canCollideLayer(n, other)) {
                    if ((n.isTrigger || other.isTrigger) && checkOverlap(n, other, getGlobalTransform)) {
                        if (!pendingRemove.contains(n) && !pendingRemove.contains(other)) {
                            callLifecycle(n, "onTriggerEnter", other.jsObj)
                            callLifecycle(other, "onTriggerEnter", n.jsObj)
                        }
                    }
                }
            }
        }
    }

    fun processCombatOverlaps(
        nodes: List<ZeroEngineView.GameNode>,
        pendingRemove: Set<ZeroEngineView.GameNode>,
        rhino: RhinoContext,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform,
        callLifecycle: (ZeroEngineView.GameNode, String, Any?) -> Unit,
        addHitstop: (Float) -> Unit
    ) {
        val hitboxes = nodes.filter { it.visible && it.isHitbox && !pendingRemove.contains(it) }
        for (hb in hitboxes) {
            val cb = getColliderBounds(hb, getGlobalTransform)
            querySpatialCandidates(cb.cx - cb.hw, cb.cx + cb.hw, cb.cy - cb.hh, cb.cy + cb.hh, queryCandidates)
            for (hurt in queryCandidates) {
                if (!hurt.visible || !hurt.isHurtbox || pendingRemove.contains(hurt)) continue
                if (hb === hurt || hb.parent == hurt.name || hurt.parent == hb.name) continue
                if (!canCollideLayer(hb, hurt) || hurt.invulnTimer > 0f) continue
                if (hb.hitCooldownMap.containsKey(hurt.name)) continue
                if (checkOverlap(hb, hurt, getGlobalTransform)) {
                    hb.hitCooldownMap[hurt.name] = 0.25f
                    callLifecycle(hb, "onHit", hurt.jsObj)
                    triggerHurtCallback(rhino, hb, hurt, hb.damage, 0f, 0f)
                }
            }
        }

        for (attacker in nodes) {
            if (!attacker.visible || pendingRemove.contains(attacker)) continue
            val boxes = attacker.animator.activeFrame?.boxes ?: continue
            if (boxes.isEmpty()) continue
            val dirX = if (getGlobalTransform(attacker).gSx < 0f) -1f else 1f

            for (box in boxes) {
                if (box.type != "hitbox") continue
                val hbBounds = getFrameBoxWorldBounds(attacker, box, getGlobalTransform)
                querySpatialCandidates(hbBounds.cx - hbBounds.hw, hbBounds.cx + hbBounds.hw, hbBounds.cy - hbBounds.hh, hbBounds.cy + hbBounds.hh, queryCandidates)
                for (hurt in queryCandidates) {
                    if (!hurt.visible || pendingRemove.contains(hurt)) continue
                    if (attacker === hurt || attacker.parent == hurt.name || hurt.parent == attacker.name) continue
                    if (!canCollideLayer(attacker, hurt) || hurt.invulnTimer > 0f) continue

                    val cdKey = "${box.id}@${hurt.name}"
                    if (attacker.hitCooldownMap.containsKey(cdKey)) continue

                    val hurtBoxes = hurt.animator.activeFrame?.boxes?.filter { it.type == "hurtbox" }
                    val isHit = if (!hurtBoxes.isNullOrEmpty()) {
                        hurtBoxes.any { overlapBounds(hbBounds, getFrameBoxWorldBounds(hurt, it, getGlobalTransform)) }
                    } else if (hurt.isHurtbox) {
                        overlapBounds(hbBounds, getColliderBounds(hurt, getGlobalTransform))
                    } else false

                    if (isHit) {
                        attacker.hitCooldownMap[cdKey] = 0.22f
                        if (box.hitstop > 0f) addHitstop(box.hitstop)
                        val kbX = box.knockbackX * dirX
                        val kbY = box.knockbackY
                        if (hurt.bodyMode != "kinematic" && (kbX != 0f || kbY != 0f)) {
                            hurt.vx = kbX; hurt.vy = kbY; hurt.isGrounded = false
                        }
                        callLifecycle(attacker, "onHit", hurt.jsObj)
                        triggerHurtCallback(rhino, attacker, hurt, box.damage, kbX, kbY)
                    }
                }
            }
        }
    }

    private fun triggerHurtCallback(
        rhino: RhinoContext,
        attacker: ZeroEngineView.GameNode,
        hurt: ZeroEngineView.GameNode,
        dmg: Float,
        kbX: Float,
        kbY: Float
    ) {
        val scope = hurt.jsObj?.prototype ?: return
        val fn = ScriptableObject.getProperty(scope, "onHurt") as? Function ?: return
        try {
            fn.call(rhino, scope, hurt.jsObj, arrayOf(attacker.jsObj, dmg.toDouble(), kbX.toDouble(), kbY.toDouble()))
        } catch (e: Exception) {
            onConsoleLog("[JS 运行错误 @ ${hurt.name}.onHurt] ${e.message}")
        }
    }

    fun raycast(
        rhino: RhinoContext,
        globalScope: ScriptableObject,
        x1: Float, y1: Float, x2: Float, y2: Float,
        ignoreTag: String, mask: Int,
        getGlobalTransform: (ZeroEngineView.GameNode) -> ZeroEngineView.GlobalTransform
    ): Scriptable {
        var closestT = 1.0f
        var hitNode: ZeroEngineView.GameNode? = null
        val dx = x2 - x1; val dy = y2 - y1
        val rMinX = min(x1, x2); val rMaxX = max(x1, x2)
        val rMinY = min(y1, y2); val rMaxY = max(y1, y2)

        querySpatialCandidates(rMinX, rMaxX, rMinY, rMaxY, queryCandidates)
        for (other in queryCandidates) {
            if (!other.visible || !other.collider || other.isTrigger || other.layer != "World") continue
            if (ignoreTag.isNotEmpty() && other.tag == ignoreTag) continue
            if ((mask and other.colLayer) == 0) continue

            if (other.nodeType == "TileMap") {
                val gb = getGlobalTransform(other)
                val ts = max(8f, other.tileSize)
                for (gx in floor((rMinX - gb.gx) / ts).toInt()..floor((rMaxX - gb.gx) / ts).toInt()) {
                    for (gy in floor((rMinY - gb.gy) / ts).toInt()..floor((rMaxY - gb.gy) / ts).toInt()) {
                        if (!other.tileLookup.containsKey(packGridKey(gx, gy))) continue
                        val minX = gb.gx + gx * ts; val minY = gb.gy + gy * ts
                        val t = intersectSegmentAABB(x1, y1, dx, dy, minX, minY, minX + ts, minY + ts)
                        if (t != null && t < closestT) { closestT = t; hitNode = other }
                    }
                }
            } else {
                val cb = getColliderBounds(other, getGlobalTransform)
                val t = intersectSegmentAABB(x1, y1, dx, dy, cb.cx - cb.hw, cb.cy - cb.hh, cb.cx + cb.hw, cb.cy + cb.hh)
                if (t != null && t < closestT) { closestT = t; hitNode = other }
            }
        }

        val res = rhino.newObject(globalScope)
        if (hitNode != null) {
            val hx = x1 + dx * closestT; val hy = y1 + dy * closestT
            if (debugDrawEnabled) debugRays.add(DebugRay(x1, y1, hx, hy, hit = true))
            ScriptableObject.putProperty(res, "hit", true)
            ScriptableObject.putProperty(res, "x", hx.toDouble())
            ScriptableObject.putProperty(res, "y", hy.toDouble())
            ScriptableObject.putProperty(res, "distance", hypot(hx - x1, hy - y1).toDouble())
            ScriptableObject.putProperty(res, "node", hitNode.jsObj)
        } else {
            if (debugDrawEnabled) debugRays.add(DebugRay(x1, y1, x2, y2, hit = false))
            ScriptableObject.putProperty(res, "hit", false)
            ScriptableObject.putProperty(res, "x", x2.toDouble())
            ScriptableObject.putProperty(res, "y", y2.toDouble())
            ScriptableObject.putProperty(res, "distance", hypot(dx, dy).toDouble())
            ScriptableObject.putProperty(res, "node", null)
        }
        return res
    }

    private fun intersectSegmentAABB(x1: Float, y1: Float, dx: Float, dy: Float, minX: Fl

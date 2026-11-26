package com.zero.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * P20-A/B/C 原生多指虚拟摇杆 + 技能按键 + 血条 + 世界空间伤害飘字与守卫警戒标覆层
 */
class ZeroTouchHudSystem(
    private val engine: ZeroEngineView
) {
    data class HudButton(
        val id: String,
        var label: String,
        var x: Float,
        var y: Float,
        var radius: Float,
        var colorInt: Int,
        var action: String,
        var defaultCooldown: Float,
        @Volatile var cooldownLeft: Float = 0f,
        @Volatile var cooldownTotal: Float = 0f,
        @Volatile var isDown: Boolean = false,
        @Volatile var queuedPress: Boolean = false,
        @Volatile var frameJustPressed: Boolean = false,
        @Volatile var pointerId: Int = -1
    )

    data class HudBar(
        val id: String,
        var label: String,
        var x: Float,
        var y: Float,
        var w: Float,
        var h: Float,
        var colorInt: Int,
        @Volatile var value: Float,
        @Volatile var maxValue: Float,
        @Volatile var trailValue: Float
    )

    @Volatile var joyEnabled: Boolean = false
    @Volatile var joyBaseX: Float = 0.18f
    @Volatile var joyBaseY: Float = 0.84f
    @Volatile var joyRadius: Float = 115f
    @Volatile var joyFloating: Boolean = true
    @Volatile var joyActive: Boolean = false
    @Volatile var joyDx: Float = 0f
    @Volatile var joyDy: Float = 0f
    @Volatile var joyMag: Float = 0f
    @Volatile var joyAngleDeg: Float = 0f

    private var joyPointerId: Int = -1
    private var joyAnchorPxX: Float = 0f
    private var joyAnchorPxY: Float = 0f
    private var joyThumbPxX: Float = 0f
    private var joyThumbPxY: Float = 0f

    private val buttons = ConcurrentHashMap<String, HudButton>()
    private val bars = ConcurrentHashMap<String, HudBar>()

    private var hudView: TouchHudCanvasView? = null
    private var hiddenByCutscene: Boolean = false
    private var lastHasWorldFx: Boolean = false

    fun setupJoystick(enabled: Boolean, x: Float, y: Float, radius: Float, floating: Boolean) {
        joyEnabled = enabled
        joyBaseX = x
        joyBaseY = y
        joyRadius = radius.coerceIn(48f, 240f)
        joyFloating = floating
        if (!enabled) resetJoystick()
        syncOverlayVisibility()
    }

    fun addButton(
        id: String,
        label: String,
        x: Float,
        y: Float,
        radius: Float,
        colorHex: String,
        action: String,
        cooldown: Float
    ) {
        val key = id.ifEmpty { "btn_${buttons.size + 1}" }
        val col = parseColorSafe(colorHex, Color.parseColor("#EF4444"))
        buttons[key] = HudButton(
            id = key,
            label = label.ifEmpty { key },
            x = x,
            y = y,
            radius = radius.coerceIn(32f, 150f),
            colorInt = col,
            action = action.ifEmpty { key },
            defaultCooldown = max(0f, cooldown)
        )
        syncOverlayVisibility()
    }

    fun removeButton(id: String) {
        buttons.remove(id)?.let { b ->
            if (b.isDown) engine.inputSystem.setVirtualAction(b.action, false)
        }
        syncOverlayVisibility()
    }

    fun isButtonDown(id: String): Boolean = buttons[id]?.isDown == true
    fun isButtonJustPressed(id: String): Boolean = buttons[id]?.frameJustPressed == true

    fun startButtonCooldown(id: String, seconds: Float) {
        val b = buttons[id] ?: return
        val sec = max(0f, seconds)
        b.cooldownTotal = sec
        b.cooldownLeft = sec
        hudView?.postInvalidate()
    }

    fun getButtonCooldown(id: String): Float = buttons[id]?.cooldownLeft ?: 0f

    fun setBar(
        id: String,
        label: String,
        value: Float,
        maxValue: Float,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        colorHex: String
    ) {
        val key = id.ifEmpty { "hp" }
        val maxV = max(1f, maxValue)
        val clamped = value.coerceIn(0f, maxV)
        val col = parseColorSafe(colorHex, Color.parseColor("#22C55E"))
        val existing = bars[key]
        if (existing != null) {
            existing.label = label
            existing.maxValue = maxV
            existing.value = clamped
            if (existing.trailValue < clamped) existing.trailValue = clamped
            existing.x = x; existing.y = y; existing.w = w; existing.h = h
            existing.colorInt = col
        } else {
            bars[key] = HudBar(key, label, x, y, w, h, col, clamped, maxV, clamped)
        }
        syncOverlayVisibility()
    }

    fun removeBar(id: String) {
        bars.remove(id)
        syncOverlayVisibility()
    }

    fun clear() {
        joyEnabled = false
        resetJoystick()
        buttons.values.forEach { b ->
            if (b.isDown) engine.inputSystem.setVirtualAction(b.action, false)
        }
        buttons.clear()
        bars.clear()
        lastHasWorldFx = false
        syncOverlayVisibility()
    }

    private fun hasTouchControls(): Boolean = joyEnabled || buttons.isNotEmpty() || bars.isNotEmpty()

    private fun hasWorldOverlays(): Boolean =
        engine.rigSystem.combatVfxSystem.floatingTexts.isNotEmpty() ||
            engine.rigSystem.stealthGuardSystem.hudMarkers.isNotEmpty()

    fun update(dt: Float, cutscenePlaying: Boolean) {
        if (cutscenePlaying != hiddenByCutscene) {
            hiddenByCutscene = cutscenePlaying
            if (cutscenePlaying) {
                resetJoystick()
                buttons.values.forEach { b ->
                    if (b.isDown) {
                        b.isDown = false
                        b.pointerId = -1
                        engine.inputSystem.setVirtualAction(b.action, false)
                    }
                }
            }
            syncOverlayVisibility()
        }

        val worldFxNow = hasWorldOverlays()
        if (worldFxNow != lastHasWorldFx) {
            lastHasWorldFx = worldFxNow
            syncOverlayVisibility()
        }

        var needRedraw = worldFxNow
        buttons.values.forEach { b ->
            b.frameJustPressed = b.queuedPress
            b.queuedPress = false
            if (b.cooldownLeft > 0f) {
                b.cooldownLeft = max(0f, b.cooldownLeft - dt)
                needRedraw = true
            }
        }
        bars.values.forEach { bar ->
            if (bar.trailValue > bar.value) {
                val diff = bar.trailValue - bar.value
                bar.trailValue = max(bar.value, bar.trailValue - max(bar.maxValue * 0.35f, diff * 3.5f) * dt)
                needRedraw = true
            }
        }
        if (needRedraw) hudView?.postInvalidate()
    }

    private fun resetJoystick() {
        joyPointerId = -1
        joyActive = false
        joyDx = 0f
        joyDy = 0f
        joyMag = 0f
    }

    private fun syncOverlayVisibility() {
        val shouldShow = (!hiddenByCutscene && hasTouchControls()) || hasWorldOverlays()
        engine.post {
            if (shouldShow) {
                ensureViewOnUi()
                hudView?.visibility = View.VISIBLE
                hudView?.invalidate()
            } else {
                hudView?.visibility = View.GONE
            }
        }
    }

    private fun ensureViewOnUi() {
        if (hudView != null) return
        val parent = engine.parent as? ViewGroup ?: return
        val v = TouchHudCanvasView(engine.context)
        val insertIdx = min(1, parent.childCount)
        parent.addView(
            v,
            insertIdx,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        hudView = v
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int {
        if (hex.isBlank()) return fallback
        return try { Color.parseColor(hex) } catch (_: Exception) { fallback }
    }

    private inner class TouchHudCanvasView(ctx: Context) : View(ctx) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC020617")
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val barTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            typeface = Typeface.MONOSPACE
        }
        private val rectTmp = RectF()
        private val projIn = FloatArray(4)
        private val projOut = FloatArray(4)
        private val screenPt = FloatArray(2)

        private fun resolveX(v: Float): Float = if (v in 0f..1f) v * width else (v / 1080f) * width
        private fun resolveY(v: Float): Float = if (v in 0f..1f) v * height else (v / 1920f) * height
        private fun uiScale(): Float = max(0.4f, width / 1080f)

        private fun projectWorld(wx: Float, wy: Float, wz: Float, vp: FloatArray, out: FloatArray): Boolean {
            projIn[0] = wx; projIn[1] = wy; projIn[2] = wz; projIn[3] = 1f
            Matrix.multiplyMV(projOut, 0, vp, 0, projIn, 0)
            if (projOut[3] <= 0.0001f) return false
            val ndcX = projOut[0] / projOut[3]
            val ndcY = projOut[1] / projOut[3]
            if (ndcX < -1.2f || ndcX > 1.2f || ndcY < -1.2f || ndcY > 1.2f) return false
            out[0] = (ndcX * 0.5f + 0.5f) * width
            out[1] = (0.5f - ndcY * 0.5f) * height
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!hasTouchControls() || hiddenByCutscene) return false
            val scale = uiScale()
            val action = event.actionMasked
            val idx = event.actionIndex
            val pid = event.getPointerId(idx)

            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val tx = event.getX(idx)
                    val ty = event.getY(idx)

                    for (b in buttons.values) {
                        val bx = resolveX(b.x)
                        val by = resolveY(b.y)
                        val br = b.radius * scale
                        if (hypot(tx - bx, ty - by) <= br * 1.18f) {
                            b.pointerId = pid
                            b.isDown = true
                            engine.inputSystem.setVirtualAction(b.action, true)
                            if (b.cooldownLeft <= 0f) {
                                b.queuedPress = true
                                if (b.defaultCooldown > 0f) {
                                    b.cooldownTotal = b.defaultCooldown
                                    b.cooldownLeft = b.defaultCooldown
                                }
                            }
                            invalidate()
                            return true
                        }
                    }

                    if (joyEnabled && joyPointerId == -1) {
                        val radPx = joyRadius * scale
                        val bx = resolveX(joyBaseX)
                        val by = resolveY(joyBaseY)
                        val hitJoy = if (joyFloating) {
                            tx <= width * 0.48f && ty >= height * 0.45f
                        } else {
                            hypot(tx - bx, ty - by) <= radPx * 1.65f
                        }
                        if (hitJoy) {
                            joyPointerId = pid
                            joyActive = true
                            joyAnchorPxX = if (joyFloating) tx.coerceIn(radPx, width * 0.46f) else bx
                            joyAnchorPxY = if (joyFloating) ty.coerceIn(height * 0.50f, height - radPx) else by
                            updateJoyThumb(tx, ty, radPx)
                            invalidate()
                            return true
                        }
                    }

                    return action != MotionEvent.ACTION_DOWN
                }

                MotionEvent.ACTION_MOVE -> {
                    if (joyPointerId != -1) {
                        val pIdx = event.findPointerIndex(joyPointerId)
                        if (pIdx >= 0) {
                            updateJoyThumb(event.getX(pIdx), event.getY(pIdx), joyRadius * scale)
                            invalidate()
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (action == MotionEvent.ACTION_CANCEL) {
                        resetJoystick()
                        buttons.values.forEach { b ->
                            if (b.isDown) {
                                b.isDown = false; b.pointerId = -1
                                engine.inputSystem.setVirtualAction(b.action, false)
                            }
                        }
                    } else {
                        if (pid == joyPointerId) resetJoystick()
                        buttons.values.forEach { b ->
                            if (b.pointerId == pid) {
                                b.pointerId = -1; b.isDown = false
                                engine.inputSystem.setVirtualAction(b.action, false)
                            }
                        }
                    }
                    invalidate()
                    return true
                }
            }
            return false
        }

        private fun updateJoyThumb(tx: Float, ty: Float, radPx: Float) {
            val dx = tx - joyAnchorPxX
            val dy = ty - joyAnchorPxY
            val dist = hypot(dx, dy)
            val clamped = min(dist, radPx)
            val nx = if (dist > 0.001f) dx / dist else 0f
            val ny = if (dist > 0.001f) dy / dist else 0f

            joyThumbPxX = joyAnchorPxX + nx * clamped
            joyThumbPxY = joyAnchorPxY + ny * clamped
            val mag = (clamped / max(1f, radPx)).coerceIn(0f, 1f)
            joyMag = mag
            joyDx = nx * mag
            joyDy = ny * mag
            if (mag > 0.05f) {
                joyAngleDeg = Math.toDegrees(atan2(ny.toDouble(), nx.toDouble())).toFloat()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = uiScale()

            // ==================== 0. 世界空间覆层（守卫警戒标 + 伤害飘字） ====================
            val vfx = engine.rigSystem.combatVfxSystem
            if (vfx.hasVpMatrix) {
                val vp = vfx.latestVpMatrix

                // P20-C 守卫头顶 ? / ! 警戒标
                for (m in engine.rigSystem.stealthGuardSystem.hudMarkers) {
                    if (!projectWorld(m.x, m.y, 12f, vp, screenPt)) continue
                    val sx = screenPt[0]
                    val sy = screenPt[1]
                    val isAlert = m.state == "alert"
                    val symbol = if (isAlert) "!" else "?"
                    val sz = (if (isAlert) 56f else 46f) * scale
                    val col = when (m.state) {
                        "alert" -> Color.parseColor("#EF4444")
                        "suspect" -> Color.parseColor("#FACC15")
                        else -> Color.parseColor("#FB923C")
                    }
                    textStrokePaint.textSize = sz
                    textStrokePaint.strokeWidth = 7f * scale
                    textStrokePaint.color = Color.argb(220, 2, 6, 23)
                    canvas.drawText(symbol, sx, sy, textStrokePaint)

                    textPaint.textSize = sz
                    textPaint.color = col
                    canvas.drawText(symbol, sx, sy, textPaint)

                    if (!isAlert && m.suspicion > 0.02f) {
                        val bw = 54f * scale
                        val bh = 7f * scale
                        rectTmp.set(sx - bw * 0.5f, sy + 6f * scale, sx + bw * 0.5f, sy + 6f * scale + bh)
                        fillPaint.color = Color.argb(180, 15, 23, 42)
                        canvas.drawRoundRect(rectTmp, 4f, 4f, fillPaint)
                        rectTmp.set(sx - bw * 0.5f, sy + 6f * scale, sx - bw * 0.5f + bw * m.suspicion.coerceIn(0f, 1f), sy + 6f * scale + bh)
                        fillPaint.color = col
                        canvas.drawRoundRect(rectTmp, 4f, 4f, fillPaint)
                    }
                }

                // P20-B 世界空间伤害飘字
                for (f in vfx.floatingTexts) {
                    if (!projectWorld(f.x, f.y, f.z, vp, screenPt)) continue
                    val p = (f.age / f.maxAge).coerceIn(0f, 1f)
                    val pop = if (p < 0.18f) {
                        val k = p / 0.18f
                        (0.6f + 0.75f * sin(k * Math.PI * 0.5).toFloat()) * (if (f.crit) 1.25f else 1.0f)
                    } else {
                        if (f.crit) 1.15f else 1.0f
                    }
                    val alpha = if (p > 0.65f) ((1f - p) / 0.35f).coerceIn(0f, 1f) else 1f
                    val aInt = (alpha * 255f).toInt().coerceIn(0, 255)
                    val sz = (if (f.crit) 48f else 36f) * f.scale * pop * scale

                    textStrokePaint.textSize = sz
                    textStrokePaint.strokeWidth = 6f * scale
                    textStrokePaint.color = Color.argb((aInt * 0.85f).toInt(), 2, 6, 23)
                    canvas.drawText(f.text, screenPt[0], screenPt[1], textStrokePaint)

                    textPaint.textSize = sz
                    textPaint.color = Color.argb(aInt, Color.red(f.colorInt), Color.green(f.colorInt), Color.blue(f.colorInt))
                    canvas.drawText(f.text, screenPt[0], screenPt[1], textPaint)
                }
            }

            if (hiddenByCutscene) return

            // ==================== 1. 绘制状态条 ====================
            bars.values.forEach { bar ->
                val bx = resolveX(bar.x)
                val by = resolveY(bar.y)
                val bw = resolveX(bar.w)
                val bh = max(14f, bar.h * scale)
                val corner = bh * 0.42f

                rectTmp.set(bx, by, bx + bw, by + bh)
                fillPaint.color = Color.argb(185, 11, 17, 32)
                canvas.drawRoundRect(rectTmp, corner, corner, fillPaint)

                val trailRatio = (bar.trailValue / bar.maxValue).coerceIn(0f, 1f)
                if (trailRatio > 0.001f) {
                    rectTmp.set(bx + 2f, by + 2f, bx + 2f + (bw - 4f) * trailRatio, by + bh - 2f)
                    fillPaint.color = Color.argb(210, 248, 250, 252)
                    canvas.drawRoundRect(rectTmp, corner, corner, fillPaint)
                }

                val ratio = (bar.value / bar.maxValue).coerceIn(0f, 1f)
                if (ratio > 0.001f) {
                    rectTmp.set(bx + 2f, by + 2f, bx + 2f + (bw - 4f) * ratio, by + bh - 2f)
                    fillPaint.color = bar.colorInt
                    canvas.drawRoundRect(rectTmp, corner, corner, fillPaint)
                }

                rectTmp.set(bx, by, bx + bw, by + bh)
                strokePaint.color = Color.argb(180, 148, 163, 184)
                strokePaint.strokeWidth = 2.2f * scale
                canvas.drawRoundRect(rectTmp, corner, corner, strokePaint)

                barTextPaint.textSize = max(18f, bh * 0.72f)
                val txt = if (bar.label.isNotEmpty()) {
                    "${bar.label} ${bar.value.toInt()}/${bar.maxValue.toInt()}"
                } else {
                    "${bar.value.toInt()}/${bar.maxValue.toInt()}"
                }
                canvas.drawText(txt, bx + 10f * scale, by + bh * 0.76f, barTextPaint)
            }

            // ==================== 2. 绘制虚拟摇杆 ====================
            if (joyEnabled) {
                val radPx = joyRadius * scale
                val cx = if (joyActive) joyAnchorPxX else resolveX(joyBaseX)
                val cy = if (joyActive) joyAnchorPxY else resolveY(joyBaseY)
                val tx = if (joyActive) joyThumbPxX else cx
                val ty = if (joyActive) joyThumbPxY else cy

                fillPaint.color = if (joyActive) Color.argb(95, 15, 23, 42) else Color.argb(65, 15, 23, 42)
                canvas.drawCircle(cx, cy, radPx, fillPaint)

                strokePaint.color = if (joyActive) Color.argb(210, 56, 189, 248) else Color.argb(120, 148, 163, 184)
                strokePaint.strokeWidth = 4f * scale
                canvas.drawCircle(cx, cy, radPx, strokePaint)

                fillPaint.color = if (joyActive) Color.argb(220, 56, 189, 248) else Color.argb(150, 226, 232, 240)
                canvas.drawCircle(tx, ty, radPx * 0.42f, fillPaint)
            }

            // ==================== 3. 绘制技能按键 ====================
            buttons.values.forEach { b ->
                val bx = resolveX(b.x)
                val by = resolveY(b.y)
                val br = b.radius * scale * if (b.isDown) 0.92f else 1.0f

                val baseAlpha = if (b.isDown) 235 else 185
                fillPaint.color = Color.argb(baseAlpha, Color.red(b.colorInt), Color.green(b.colorInt), Color.blue(b.colorInt))
                canvas.drawCircle(bx, by, br, fillPaint)

                strokePaint.color = if (b.isDown) Color.WHITE else Color.argb(200, 226, 232, 240)
                strokePaint.strokeWidth = (if (b.isDown) 5f else 3f) * scale
                canvas.drawCircle(bx, by, br, strokePaint)

                if (b.cooldownLeft > 0.01f && b.cooldownTotal > 0.01f) {
                    val ratio = (b.cooldownLeft / b.cooldownTotal).coerceIn(0f, 1f)
                    rectTmp.set(bx - br, by - br, bx + br, by + br)
                    fillPaint.color = Color.argb(175, 2, 6, 23)
                    canvas.drawArc(rectTmp, -90f, 360f * ratio, true, fillPaint)

                    textPaint.textSize = br * 0.62f
                    textPaint.color = Color.parseColor("#FBBF24")
                    val cdStr = String.format(Locale.US, "%.1f", b.cooldownLeft)
                    canvas.drawText(cdStr, bx, by + textPaint.textSize * 0.35f, textPaint)
                } else {
                    textPaint.textSize = br * 0.52f
                    textPaint.color = Color.WHITE
                    canvas.drawText(b.label, bx, by + textPaint.textSize * 0.35f, textPaint)
                }
            }
        }
    }
}
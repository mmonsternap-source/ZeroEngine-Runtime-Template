package com.zero.engine

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.*

/**
 * Zeno 统一开屏 Logo 动画 & 自定义开屏配置器
 * - 编辑器启动：播放 Zeno 官方棱镜流光动效
 * - 运行/导出游戏：默认播放 Zeno 动效，支持读取项目内 splash.json + splash_logo.png 自定义
 */
object ZeroSplashOverlay {

    data class SplashConfig(
        var mode: String = "default",        // default | custom | both | none
        var title: String = "MY STUDIO",
        var subtitle: String = "POWERED BY ZENO ENGINE",
        var bgColor: String = "#060913",
        var accentColor: String = "#38BDF8",
        var durationMs: Int = 3000
    )

    private fun isZh(ctx: Context): Boolean {
        val lang = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ctx.resources.configuration.locales.get(0)?.language
            } else {
                Locale.getDefault().language
            }
        } catch (_: Exception) {
            Locale.getDefault().language
        } ?: "en"
        return lang.lowercase(Locale.US).startsWith("zh")
    }

    fun loadConfig(ctx: Context, projectDir: File?): SplashConfig {
        val cfg = SplashConfig()
        val raw = try {
            val f = projectDir?.let { File(it, "splash.json") }
            when {
                f != null && f.exists() -> f.readText()
                else -> ctx.assets.open("splash.json").bufferedReader().use { it.readText() }
            }
        } catch (_: Exception) {
            return cfg
        }
        return try {
            val o = JSONObject(raw)
            cfg.mode = o.optString("mode", "default")
            cfg.title = o.optString("title", "MY STUDIO")
            cfg.subtitle = o.optString("subtitle", "POWERED BY ZENO ENGINE")
            cfg.bgColor = o.optString("bgColor", "#060913")
            cfg.accentColor = o.optString("accentColor", "#38BDF8")
            cfg.durationMs = o.optInt("durationMs", 3000).coerceIn(1000, 6000)
            cfg
        } catch (_: Exception) {
            cfg
        }
    }

    fun saveConfig(projectDir: File, cfg: SplashConfig) {
        projectDir.mkdirs()
        val o = JSONObject().apply {
            put("mode", cfg.mode)
            put("title", cfg.title)
            put("subtitle", cfg.subtitle)
            put("bgColor", cfg.bgColor)
            put("accentColor", cfg.accentColor)
            put("durationMs", cfg.durationMs)
        }
        File(projectDir, "splash.json").writeText(o.toString(2))
    }

    private fun loadCustomBitmap(ctx: Context, projectDir: File?): Bitmap? {
        try {
            val f = projectDir?.let { File(it, "splash_logo.png") }
            if (f != null && f.exists()) {
                return BitmapFactory.decodeFile(f.absolutePath)
            }
        } catch (_: Exception) {}
        return try {
            ctx.assets.open("splash_logo.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun show(
        ctx: Context,
        container: ViewGroup,
        projectDir: File? = null,
        isEditorLaunch: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val cfg = if (isEditorLaunch) SplashConfig(mode = "default", durationMs = 2800) else loadConfig(ctx, projectDir)
        if (!isEditorLaunch && cfg.mode == "none") {
            onComplete?.invoke()
            return
        }

        val customBmp = if (!isEditorLaunch && (cfg.mode == "custom" || cfg.mode == "both")) {
            loadCustomBitmap(ctx, projectDir)
        } else null

        fun playSingle(isCustomStage: Boolean, next: () -> Unit) {
            val view = SplashAnimView(
                ctx = ctx,
                isCustom = isCustomStage,
                cfg = cfg,
                customBitmap = if (isCustomStage) customBmp else null
            )
            container.addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            view.bringToFront()
            view.startAnimation {
                try { container.removeView(view) } catch (_: Exception) {}
                next()
            }
        }

        when {
            isEditorLaunch || cfg.mode == "default" -> playSingle(false) { onComplete?.invoke() }
            cfg.mode == "custom" -> playSingle(true) { onComplete?.invoke() }
            cfg.mode == "both" -> playSingle(false) { playSingle(true) { onComplete?.invoke() } }
            else -> onComplete?.invoke()
        }
    }

    fun showConfigDialog(
        ctx: Context,
        projectDir: File,
        previewContainer: ViewGroup,
        onPickLogoImage: () -> Unit,
        onSaved: () -> Unit
    ) {
        val zh = isZh(ctx)
        val cfg = loadConfig(ctx, projectDir)
        val logoFile = File(projectDir, "splash_logo.png")

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(34, 28, 34, 22)
        }

        val titleTv = TextView(ctx).apply {
            text = if (zh) "✨ 游戏开屏 Logo 动画设置" else "✨ Game Splash Logo Settings"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 8)
        }

        val descTv = TextView(ctx).apply {
            text = if (zh) {
                "导出的游戏默认播放 Zeno 动画，你也可以改成自己的 Logo 或双开屏："
            } else {
                "Exported games show the Zeno logo by default. Customize or replace it here:"
            }
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 12)
        }

        val modes = listOf("default", "custom", "both", "none")
        val modeLabels = if (zh) {
            arrayOf(
                "默认：仅播放 Zeno 官方 Logo 动画",
                "自定义：仅播放我制作的 Logo 动画",
                "双开屏：先播 Zeno，再播我的 Logo",
                "关闭：游戏直接进入画面（无开屏）"
            )
        } else {
            arrayOf(
                "Default: Zeno Logo only",
                "Custom: My Custom Logo only",
                "Both: Zeno -> My Custom Logo",
                "Off: Skip splash screen"
            )
        }

        val radioGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
        modes.forEachIndexed { idx, m ->
            val rb = RadioButton(ctx).apply {
                id = idx + 100
                text = modeLabels[idx]
                textSize = 13f
                setTextColor(Color.parseColor("#E2E8F0"))
                isChecked = cfg.mode == m
            }
            radioGroup.addView(rb)
        }

        fun makeInput(hintStr: String, initVal: String): EditText = EditText(ctx).apply {
            hint = hintStr
            setText(initVal)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setPadding(20, 14, 20, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                setStroke(2, Color.parseColor("#334155"))
                cornerRadius = 12f
            }
        }

        val etTitle = makeInput(if (zh) "自定义主标题（如：我的工作室）" else "Custom Title (e.g. MY STUDIO)", cfg.title)
        val etSub = makeInput(if (zh) "副标题（如：POWERED BY ZENO ENGINE）" else "Subtitle (e.g. POWERED BY ZENO ENGINE)", cfg.subtitle)
        val etAccent = makeInput(if (zh) "主题流光色（十六进制，如 #38BDF8）" else "Accent Hex Color (e.g. #38BDF8)", cfg.accentColor)

        val imgStatus = TextView(ctx).apply {
            textSize = 12f
            setTextColor(if (logoFile.exists()) Color.parseColor("#4ADE80") else Color.parseColor("#94A3B8"))
            text = if (logoFile.exists()) {
                if (zh) "✅ 已上传自定义 Logo 图片 (splash_logo.png)" else "✅ Custom logo image set (splash_logo.png)"
            } else {
                if (zh) "💡 未上传图片时，自定义模式会使用炫酷矢量文字动画" else "💡 No image uploaded: custom mode uses sleek vector text"
            }
            setPadding(0, 10, 0, 8)
        }

        val imgBtnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPickImg = Button(ctx).apply {
            text = if (zh) "🖼 选择自定义 Logo 图片" else "🖼 Pick Custom Logo"
            textSize = 12.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = 12f
            }
        }
        val btnClearImg = Button(ctx).apply {
            text = if (zh) "清除图片" else "Clear"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#FCA5A5"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                setStroke(2, Color.parseColor("#7F1D1D"))
                cornerRadius = 12f
            }
            visibility = if (logoFile.exists()) View.VISIBLE else View.GONE
        }
        imgBtnRow.addView(btnPickImg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })
        imgBtnRow.addView(btnClearImg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(titleTv)
        root.addView(descTv)
        root.addView(radioGroup)
        val lpField = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 }
        root.addView(etTitle, lpField)
        root.addView(etSub, lpField)
        root.addView(etAccent, lpField)
        root.addView(imgStatus)
        root.addView(imgBtnRow)

        val scroll = ScrollView(ctx).apply { addView(root) }

        fun collectConfig(): SplashConfig {
            val checkedIdx = (radioGroup.checkedRadioButtonId - 100).coerceIn(0, modes.lastIndex)
            return SplashConfig(
                mode = modes[checkedIdx],
                title = etTitle.text?.toString()?.trim().orEmpty().ifEmpty { "MY STUDIO" },
                subtitle = etSub.text?.toString()?.trim().orEmpty(),
                bgColor = cfg.bgColor,
                accentColor = etAccent.text?.toString()?.trim().orEmpty().ifEmpty { "#38BDF8" },
                durationMs = cfg.durationMs
            )
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton(if (zh) "💾 保存设置" else "💾 Save", null)
            .setNeutralButton(if (zh) "▶ 预览动画" else "▶ Preview", null)
            .setNegativeButton(if (zh) "关闭" else "Close", null)
            .create()

        btnPickImg.setOnClickListener {
            saveConfig(projectDir, collectConfig())
            onSaved()
            dialog.dismiss()
            onPickLogoImage()
        }

        btnClearImg.setOnClickListener {
            logoFile.delete()
            imgStatus.text = if (zh) "已清除自定义图片" else "Custom image cleared"
            imgStatus.setTextColor(Color.parseColor("#94A3B8"))
            btnClearImg.visibility = View.GONE
            onSaved()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                saveConfig(projectDir, collectConfig())
                onSaved()
                Toast.makeText(ctx, if (zh) "✅ 开屏设置已保存" else "✅ Splash settings saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                saveConfig(projectDir, collectConfig())
                onSaved()
                dialog.dismiss()
                show(ctx, previewContainer, projectDir, isEditorLaunch = false)
            }
        }
        dialog.show()
    }

    /**
     * 60fps Zeno 棱镜流光开屏动画视图
     */
    private class SplashAnimView(
        ctx: Context,
        private val isCustom: Boolean,
        private val cfg: SplashConfig,
        private val customBitmap: Bitmap?
    ) : View(ctx) {

        private var progress = 0f
        private var animator: ValueAnimator? = null
        private var finished = false

        private val bgColorInt = parseColorSafe(if (isCustom) cfg.bgColor else "#060913", Color.parseColor("#060913"))
        private val accentInt = parseColorSafe(if (isCustom) cfg.accentColor else "#38BDF8", Color.parseColor("#38BDF8"))
        private val secondaryInt = Color.parseColor("#8B5CF6")

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val hexFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0B1324")
        }
        private val hexStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val zFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.28f
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.18f
        }
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val arcRect = RectF()
        private val hexPath = Path()
        private val partialHexPath = Path()
        private val pathMeasure = PathMeasure()
        private val zTopPath = Path()
        private val zDiagPath = Path()
        private val zBotPath = Path()

        init {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (progress > 0.28f) finishNow()
            }
        }

        private var onDoneCallback: (() -> Unit)? = null

        fun startAnimation(onDone: () -> Unit) {
            onDoneCallback = onDone
            val dur = cfg.durationMs.toLong()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = dur
                interpolator = DecelerateInterpolator(1.15f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        finishNow()
                    }
                })
                start()
            }
        }

        private fun finishNow() {
            if (finished) return
            finished = true
            animator?.cancel()
            onDoneCallback?.invoke()
        }

        private fun parseColorSafe(hex: String, fallback: Int): Int =
            try { Color.parseColor(hex) } catch (_: Exception) { fallback }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val fadeOut = if (progress > 0.85f) ((1f - progress) / 0.15f).coerceIn(0f, 1f) else 1f
            val masterAlpha = (fadeOut * 255f).toInt().coerceIn(0, 255)

            // 1. 深空黑曜石径向光晕背景
            bgPaint.shader = RadialGradient(
                w * 0.5f, h * 0.43f, max(w, h) * 0.72f,
                Color.argb(masterAlpha, Color.red(accentInt) / 6, Color.green(accentInt) / 5, Color.blue(accentInt) / 4 + 32),
                Color.argb(masterAlpha, Color.red(bgColorInt), Color.green(bgColorInt), Color.blue(bgColorInt)),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val cx = w * 0.5f
            val cy = h * 0.43f
            val baseR = min(w, h) * 0.165f

            // 2. 能量波扩散光环
            val waveP = ((progress - 0.18f) / 0.45f).coerceIn(0f, 1f)
            if (waveP > 0f && waveP < 1f) {
                val waveAlpha = ((1f - waveP) * 110f * fadeOut).toInt().coerceIn(0, 255)
                glowPaint.color = Color.argb(waveAlpha, Color.red(accentInt), Color.green(accentInt), Color.blue(accentInt))
                glowPaint.strokeWidth = 6f * (1f - waveP) + 2f
                canvas.drawCircle(cx, cy, baseR * (0.9f + waveP * 1.35f), glowPaint)
            }

            if (isCustom && customBitmap != null) {
                val popP = (progress / 0.35f).coerceIn(0f, 1f)
                val scale = 0.78f + 0.22f * sin(popP * Math.PI * 0.5).toFloat()
                val imgAlpha = (popP * fadeOut * 255f).toInt().coerceIn(0, 255)
                val maxSide = min(w, h) * 0.46f
                val ratio = min(maxSide / customBitmap.width, maxSide / customBitmap.height)
                val dw = customBitmap.width * ratio * scale
                val dh = customBitmap.height * ratio * scale
                bmpPaint.alpha = imgAlpha
                val dst = RectF(cx - dw * 0.5f, cy - dh * 0.5f, cx + dw * 0.5f, cy + dh * 0.5f)
                canvas.drawBitmap(customBitmap, null, dst, bmpPaint)
            } else if (isCustom) {
                val ringP = (progress / 0.42f).coerceIn(0f, 1f)
                arcRect.set(cx - baseR, cy - baseR, cx + baseR, cy + baseR)
                ringPaint.strokeWidth = baseR * 0.10f
                ringPaint.color = Color.argb((220 * fadeOut).toInt(), Color.red(accentInt), Color.green(accentInt), Color.blue(accentInt))
                canvas.drawArc(arcRect, -90f, 360f * ringP, false, ringPaint)

                val initial = cfg.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "Z"
                titlePaint.textSize = baseR * 0.95f
                titlePaint.alpha = (ringP * fadeOut * 255f).toInt().coerceIn(0, 255)
                canvas.drawText(initial, cx, cy + baseR * 0.34f, titlePaint)
            } else {
                // ==================== Zeno 官方六边形棱镜 + 光刃 Z 动效 ====================
                hexPath.reset()
                for (i in 0 until 6) {
                    val angle = Math.toRadians((i * 60.0) - 90.0)
                    val px = cx + (baseR * cos(angle)).toFloat()
                    val py = cy + (baseR * sin(angle)).toFloat()
                    if (i == 0) hexPath.moveTo(px, py) else hexPath.lineTo(px, py)
                }
                hexPath.close()

                val fillAlpha = (((progress - 0.10f) / 0.32f).coerceIn(0f, 1f) * fadeOut * 235f).toInt().coerceIn(0, 255)
                hexFillPaint.alpha = fillAlpha
                canvas.drawPath(hexPath, hexFillPaint)

                val strokeP = (progress / 0.44f).coerceIn(0f, 1f)
                pathMeasure.setPath(hexPath, false)
                partialHexPath.reset()
                pathMeasure.getSegment(0f, pathMeasure.length * strokeP, partialHexPath, true)

                hexStrokePaint.strokeWidth = baseR * 0.085f
                hexStrokePaint.alpha = masterAlpha
                hexStrokePaint.shader = LinearGradient(
                    cx - baseR, cy - baseR, cx + baseR, cy + baseR,
                    accentInt, secondaryInt, Shader.TileMode.CLAMP
                )
                canvas.drawPath(partialHexPath, hexStrokePaint)
                hexStrokePaint.shader = null

                val zP = ((progress - 0.16f) / 0.38f).coerceIn(0f, 1f)
                if (zP > 0f) {
                    val zScale = 0.80f + 0.20f * sin(zP * Math.PI * 0.5).toFloat()
                    val zAlpha = (zP * fadeOut * 255f).toInt().coerceIn(0, 255)
                    canvas.save()
                    canvas.scale(zScale, zScale, cx, cy)

                    val half = baseR * 0.52f
                    val thick = baseR * 0.19f

                    zTopPath.reset()
                    zTopPath.moveTo(cx - half, cy - half)
                    zTopPath.lineTo(cx + half, cy - half)
                    zTopPath.lineTo(cx + half - thick * 0.85f, cy - half + thick)
                    zTopPath.lineTo(cx - half, cy - half + thick)
                    zTopPath.close()

                    zDiagPath.reset()
                    zDiagPath.moveTo(cx + half, cy - half)
                    zDiagPath.lineTo(cx - half + thick * 0.9f, cy + half)
                    zDiagPath.lineTo(cx - half, cy + half)
                    zDiagPath.lineTo(cx + half - thick * 0.9f, cy - half)
                    zDiagPath.close()

                    zBotPath.reset()
                    zBotPath.moveTo(cx - half + thick * 0.85f, cy + half - thick)
                    zBotPath.lineTo(cx + half, cy + half - thick)
                    zBotPath.lineTo(cx + half, cy + half)
                    zBotPath.lineTo(cx - half, cy + half)
                    zBotPath.close()

                    zFillPaint.alpha = zAlpha
                    zFillPaint.shader = LinearGradient(
                        cx - half, cy - half, cx + half, cy + half,
                        intArrayOf(
                            Color.parseColor("#E0F2FE"),
                            Color.parseColor("#38BDF8"),
                            Color.parseColor("#A855F7")
                        ),
                        floatArrayOf(0f, 0.48f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(zTopPath, zFillPaint)
                    canvas.drawPath(zDiagPath, zFillPaint)
                    canvas.drawPath(zBotPath, zFillPaint)
                    zFillPaint.shader = null

                    // 镜面流光扫过
                    if (progress > 0.48f) {
                        val sweepT = ((progress - 0.48f) / 0.34f).coerceIn(0f, 1f)
                        val sweepX = cx - baseR * 1.3f + baseR * 2.6f * sweepT
                        canvas.save()
                        canvas.clipPath(hexPath)
                        sweepPaint.shader = LinearGradient(
                            sweepX - baseR * 0.35f, cy - baseR,
                            sweepX + baseR * 0.35f, cy + baseR,
                            intArrayOf(
                                Color.TRANSPARENT,
                                Color.argb((120 * fadeOut).toInt(), 255, 255, 255),
                                Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 0.5f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(cx - baseR, cy - baseR, cx + baseR, cy + baseR, sweepPaint)
                        sweepPaint.shader = null
                        canvas.restore()
                    }
                    canvas.restore()
                }
            }

            // 3. 标题与副标题滑入淡出
            val textP = ((progress - 0.24f) / 0.30f).coerceIn(0f, 1f)
            val textAlpha = (textP * fadeOut * 255f).toInt().coerceIn(0, 255)
            val slideY = (1f - textP) * 32f

            val mainTitle = if (isCustom) cfg.title else "ZENO"
            val subTitle = if (isCustom) cfg.subtitle else "POWERED BY ZENO ENGINE"

            titlePaint.textSize = min(w, h) * 0.068f
            titlePaint.alpha = textAlpha
            canvas.drawText(mainTitle, cx, cy + baseR * 1.65f + slideY, titlePaint)

            if (subTitle.isNotBlank()) {
                subPaint.textSize = min(w, h) * 0.025f
                subPaint.alpha = (textAlpha * 0.88f).toInt()
                canvas.drawText(subTitle, cx, cy + baseR * 2.02f + slideY, subPaint)
            }
        }
    }
}
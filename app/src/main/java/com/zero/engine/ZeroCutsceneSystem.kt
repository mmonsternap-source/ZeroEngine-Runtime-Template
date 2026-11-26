package com.zero.engine

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * P19-C + P20-A/C JSON 驱动剧情对话与过场导演系统（同时托管原生虚拟摇杆、HUD 覆层与守卫 AI 更新）：
 * 支持指令：say / choice / wait / camera / move / shake / fade / flag / sfx / bgm / jump
 */
class ZeroCutsceneSystem(
    private val engine: ZeroEngineView
) {
    val touchHud = ZeroTouchHudSystem(engine)

    @Volatile var isPlaying: Boolean = false
        private set

    private var steps: List<JSONObject> = emptyList()
    private val labelIndex = HashMap<String, Int>()
    private var curIdx = -1

    // 当前步骤运行态
    private var stepType = ""
    private var waitLeft = 0f

    // 打字机状态
    private var fullText = ""
    private var shownChars = 0
    private var charTimer = 0f
    private var charsPerSec = 34f
    private var textFinished = false

    // 镜头/节点插值状态
    private var tweenElapsed = 0f
    private var tweenDuration = 0f
    private var camStartX = 0f
    private var camStartY = 0f
    private var camEndX = 0f
    private var camEndY = 0f
    private var moveNode: ZeroEngineView.GameNode? = null
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var moveEndX = 0f
    private var moveEndY = 0f

    // 原生 UI 覆层句柄（仅在 UI 线程访问）
    private var overlayRoot: FrameLayout? = null
    private var dialogBox: LinearLayout? = null
    private var portraitView: ImageView? = null
    private var speakerView: TextView? = null
    private var bodyView: TextView? = null
    private var hintView: TextView? = null
    private var choicesBox: LinearLayout? = null
    private var lastPortraitFile: String = ""

    /**
     * 播放剧情：支持传入 data/ 下的 json 文件名（如 "intro.json"），或直接传入 JSON 字符串
     */
    fun play(source: String): Boolean {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return false

        val rawJson = if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            trimmed
        } else {
            val fileName = if (trimmed.endsWith(".json")) trimmed else "$trimmed.json"
            val f = File(File(engine.projectDir, "data"), fileName)
            if (!f.exists()) {
                engine.onConsoleLog("[CUTSCENE] 未找到剧情文件: data/$fileName")
                return false
            }
            f.readText()
        }

        val parsed = mutableListOf<JSONObject>()
        try {
            val arr = if (rawJson.trim().startsWith("[")) {
                JSONArray(rawJson)
            } else {
                JSONObject(rawJson).optJSONArray("steps") ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parsed.add(it) }
            }
        } catch (e: Exception) {
            engine.onConsoleLog("[CUTSCENE JSON 解析错误] ${e.message}")
            return false
        }

        if (parsed.isEmpty()) return false
        steps = parsed
        labelIndex.clear()
        parsed.forEachIndexed { idx, obj ->
            val lb = obj.optString("label", "")
            if (lb.isNotEmpty()) labelIndex[lb] = idx
        }

        isPlaying = true
        curIdx = -1
        advanceTo(0)
        return true
    }

    /** 仅结束当前剧情对话，保留虚拟摇杆与 HUD */
    fun stop() {
        isPlaying = false
        steps = emptyList()
        labelIndex.clear()
        curIdx = -1
        stepType = ""
        moveNode = null
        hideOverlayUi()
    }

    /** 切换场景或销毁引擎时调用：清空剧情并一并清理虚拟摇杆与 HUD */
    fun clear() {
        stop()
        touchHud.clear()
    }

    fun update(dt: Float) {
        engine.rigSystem.stealthGuardSystem.update(dt, engine)
        touchHud.update(dt, isPlaying)
        if (!isPlaying) return
        when (stepType) {
            "say", "choice" -> {
                if (!textFinished) {
                    charTimer += dt * charsPerSec
                    val nextChars = min(fullText.length, charTimer.toInt())
                    if (nextChars != shownChars) {
                        shownChars = nextChars
                        val partial = fullText.substring(0, shownChars)
                        val done = shownChars >= fullText.length
                        if (done) textFinished = true
                        updateTextUi(partial, done, stepType == "choice")
                    }
                }
            }
            "wait" -> {
                waitLeft -= dt
                if (waitLeft <= 0f) advanceTo(curIdx + 1)
            }
            "camera" -> {
                tweenElapsed += dt
                val p = (tweenElapsed / max(0.01f, tweenDuration)).coerceIn(0f, 1f)
                val e = p * p * (3f - 2f * p)
                engine.camX = camStartX + (camEndX - camStartX) * e
                engine.camY = camStartY + (camEndY - camStartY) * e
                if (p >= 1f) advanceTo(curIdx + 1)
            }
            "move" -> {
                tweenElapsed += dt
                val p = (tweenElapsed / max(0.01f, tweenDuration)).coerceIn(0f, 1f)
                val e = p * p * (3f - 2f * p)
                moveNode?.let { n ->
                    n.x = moveStartX + (moveEndX - moveStartX) * e
                    n.y = moveStartY + (moveEndY - moveStartY) * e
                }
                if (p >= 1f) {
                    moveNode = null
                    advanceTo(curIdx + 1)
                }
            }
        }
    }

    private fun advanceTo(index: Int) {
        var idx = index
        var guard = 0
        while (idx in steps.indices && guard++ < 64) {
            curIdx = idx
            val s = steps[idx]
            val type = s.optString("type", "say").lowercase()
            stepType = type

            when (type) {
                "say" -> {
                    val speaker = s.optString("speaker", "")
                    val rawText = s.optString("text", "")
                    val portrait = s.optString("portrait", "")
                    charsPerSec = s.optDouble("speed", 36.0).toFloat().coerceIn(5f, 200f)
                    fullText = rawText
                    shownChars = 0
                    charTimer = 0f
                    textFinished = rawText.isEmpty()
                    showDialogUi(speaker, portrait, if (textFinished) fullText else "", textFinished, null)
                    return
                }
                "choice" -> {
                    val speaker = s.optString("speaker", "")
                    val rawText = s.optString("text", "")
                    val portrait = s.optString("portrait", "")
                    val opts = s.optJSONArray("options") ?: JSONArray()
                    charsPerSec = s.optDouble("speed", 42.0).toFloat().coerceIn(5f, 200f)
                    fullText = rawText
                    shownChars = 0
                    charTimer = 0f
                    textFinished = rawText.isEmpty()
                    showDialogUi(speaker, portrait, if (textFinished) fullText else "", textFinished, opts)
                    return
                }
                "wait" -> {
                    hideOverlayUi()
                    waitLeft = s.optDouble("duration", 0.6).toFloat().coerceAtLeast(0.01f)
                    return
                }
                "camera" -> {
                    val targetName = s.optString("target", "")
                    val dur = s.optDouble("duration", 0.6).toFloat()
                    if (targetName.isNotEmpty()) {
                        val tn = engine.findNodeByName(targetName)
                        if (tn != null) {
                            if (dur <= 0.01f) {
                                engine.camTarget = tn
                                idx++; continue
                            } else {
                                engine.camTarget = null
                                val gt = engine.getGlobalTransform(tn)
                                camStartX = engine.camX; camStartY = engine.camY
                                camEndX = gt.gx; camEndY = gt.gy
                                tweenElapsed = 0f; tweenDuration = dur
                                return
                            }
                        }
                    } else {
                        engine.camTarget = null
                        val tx = s.optDouble("x", engine.camX.toDouble()).toFloat()
                        val ty = s.optDouble("y", engine.camY.toDouble()).toFloat()
                        if (dur <= 0.01f) {
                            engine.camX = tx; engine.camY = ty
                            idx++; continue
                        }
                        camStartX = engine.camX; camStartY = engine.camY
                        camEndX = tx; camEndY = ty
                        tweenElapsed = 0f; tweenDuration = dur
                        return
                    }
                    idx++
                }
                "move" -> {
                    val node = engine.findNodeByName(s.optString("node", ""))
                    val dur = s.optDouble("duration", 0.8).toFloat()
                    if (node != null) {
                        val tx = s.optDouble("x", node.x.toDouble()).toFloat()
                        val ty = s.optDouble("y", node.y.toDouble()).toFloat()
                        if (dur <= 0.01f) {
                            node.x = tx; node.y = ty
                            idx++; continue
                        }
                        moveNode = node
                        moveStartX = node.x; moveStartY = node.y
                        moveEndX = tx; moveEndY = ty
                        tweenElapsed = 0f; tweenDuration = dur
                        return
                    }
                    idx++
                }
                "shake" -> {
                    engine.shakeIntensity = s.optDouble("intensity", 14.0).toFloat()
                    engine.shakeTimer = s.optDouble("duration", 0.2).toFloat()
                    idx++
                }
                "fade" -> {
                    val alpha = s.optDouble("alpha", 0.0).toFloat().coerceIn(0f, 1f)
                    val dur = s.optDouble("duration", 0.35).toFloat()
                    if (dur <= 0.01f) {
                        engine.postFade = alpha; engine.fadeTarget = alpha; engine.fadeSpeed = 0f
                    } else {
                        engine.fadeTarget = alpha
                        engine.fadeSpeed = kotlin.math.abs(alpha - engine.postFade) / dur
                    }
                    idx++
                }
                "flag" -> {
                    val k = s.optString("key", "")
                    if (k.isNotEmpty()) {
                        engine.dataStore.worldFlags[k] = s.opt("value") ?: true
                    }
                    idx++
                }
                "sfx" -> {
                    val f = s.optString("file", "")
                    if (f.isNotEmpty()) engine.audioSystem.playSFX(f, s.optDouble("volume", 1.0).toFloat(), 1f, 1f)
                    idx++
                }
                "bgm" -> {
                    val f = s.optString("file", "")
                    if (f.isNotEmpty()) engine.audioSystem.playBGM(f, true, s.optDouble("volume", 0.8).toFloat())
                    idx++
                }
                "jump" -> {
                    val condFlag = s.optString("ifFlag", "")
                    val pass = if (condFlag.isEmpty()) true else {
                        when (val v = engine.dataStore.worldFlags[condFlag]) {
                            is Boolean -> v
                            is Number -> v.toDouble() != 0.0
                            is String -> v.isNotEmpty()
                            else -> false
                        }
                    }
                    val targetLabel = s.optString("to", "")
                    if (pass && targetLabel.isNotEmpty() && labelIndex.containsKey(targetLabel)) {
                        idx = labelIndex[targetLabel]!!
                    } else {
                        idx++
                    }
                }
                else -> idx++
            }
        }

        // 所有步骤执行完毕，仅结束剧情，保留摇杆与 HUD
        stop()
    }

    private fun onDialogTapGl() {
        if (!isPlaying) return
        if (stepType == "say") {
            if (!textFinished) {
                shownChars = fullText.length
                textFinished = true
                updateTextUi(fullText, true, false)
            } else {
                advanceTo(curIdx + 1)
            }
        } else if (stepType == "choice" && !textFinished) {
            shownChars = fullText.length
            textFinished = true
            updateTextUi(fullText, true, true)
        }
    }

    private fun onChoiceSelectedGl(flagKey: String, jumpLabel: String) {
        if (!isPlaying) return
        if (flagKey.isNotEmpty()) engine.dataStore.worldFlags[flagKey] = true
        if (jumpLabel.isNotEmpty() && labelIndex.containsKey(jumpLabel)) {
            advanceTo(labelIndex[jumpLabel]!!)
        } else {
            advanceTo(curIdx + 1)
        }
    }

    private fun ensureOverlayOnUi() {
        if (overlayRoot != null) return
        val parent = engine.parent as? ViewGroup ?: return
        val ctx = engine.context

        val root = FrameLayout(ctx).apply {
            isClickable = false
            visibility = View.GONE
        }

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 34)
        }

        val choices = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 14)
            visibility = View.GONE
        }
        choicesBox = choices

        val boxBg = GradientDrawable().apply {
            setColor(Color.parseColor("#E60B1120"))
            setStroke(3, Color.parseColor("#38BDF8"))
            cornerRadius = 18f
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = boxBg
            setPadding(22, 18, 22, 18)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { engine.queueEvent { onDialogTapGl() } }
        }
        dialogBox = box

        val portrait = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        portraitView = portrait

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val speaker = TextView(ctx).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FBBF24"))
            setPadding(0, 0, 0, 6)
        }
        speakerView = speaker

        val body = TextView(ctx).apply {
            textSize = 14.5f
            setTextColor(Color.parseColor("#F8FAFC"))
            setLineSpacing(6f, 1f)
            minLines = 2
        }
        bodyView = body

        val hint = TextView(ctx).apply {
            text = "▼ 点击继续"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }
        hintView = hint

        textCol.addView(speaker)
        textCol.addView(body)
        textCol.addView(hint)

        box.addView(portrait, LinearLayout.LayoutParams(132, 132).apply { rightMargin = 18 })
        box.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        wrapper.addView(choices, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrapper.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            wrapper,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        parent.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlayRoot = root
    }

    private fun showDialogUi(speaker: String, portraitFile: String, text: String, done: Boolean, options: JSONArray?) {
        val optsSnapshot = options?.toString()
        engine.post {
            ensureOverlayOnUi()
            overlayRoot?.visibility = View.VISIBLE
            speakerView?.apply {
                this.text = speaker
                visibility = if (speaker.isNotEmpty()) View.VISIBLE else View.GONE
            }
            bodyView?.text = text
            hintView?.visibility = if (done && optsSnapshot == null) View.VISIBLE else View.GONE

            portraitView?.let { pv ->
                if (portraitFile.isEmpty()) {
                    pv.visibility = View.GONE
                    lastPortraitFile = ""
                } else if (portraitFile != lastPortraitFile) {
                    val f = File(File(engine.projectDir, "textures"), portraitFile)
                    val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                    if (bmp != null) {
                        pv.setImageBitmap(bmp)
                        pv.visibility = View.VISIBLE
                        lastPortraitFile = portraitFile
                    } else {
                        pv.visibility = View.GONE
                    }
                } else {
                    pv.visibility = View.VISIBLE
                }
            }

            choicesBox?.let { cb ->
                cb.removeAllViews()
                if (optsSnapshot != null) {
                    val arr = JSONArray(optsSnapshot)
                    val ctx = engine.context
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val label = o.optString("text", "选项 ${i + 1}")
                        val flag = o.optString("flag", "")
                        val jump = o.optString("jump", "")
                        val btnBg = GradientDrawable().apply {
                            setColor(Color.parseColor("#E61E293B"))
                            setStroke(2, Color.parseColor("#60A5FA"))
                            cornerRadius = 14f
                        }
                        val btn = Button(ctx).apply {
                            this.text = label
                            textSize = 13.5f
                            isAllCaps = false
                            setTextColor(Color.WHITE)
                            background = btnBg
                            setPadding(24, 16, 24, 16)
                            setOnClickListener { engine.queueEvent { onChoiceSelectedGl(flag, jump) } }
                        }
                        cb.addView(
                            btn,
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = 10
                            }
                        )
                    }
                    cb.visibility = if (done) View.VISIBLE else View.GONE
                } else {
                    cb.visibility = View.GONE
                }
            }
        }
    }

    private fun updateTextUi(partial: String, done: Boolean, hasChoices: Boolean) {
        engine.post {
            bodyView?.text = partial
            if (done) {
                hintView?.visibility = if (hasChoices) View.GONE else View.VISIBLE
                if (hasChoices) choicesBox?.visibility = View.VISIBLE
            }
        }
    }

    private fun hideOverlayUi() {
        engine.post {
            overlayRoot?.visibility = View.GONE
        }
    }
}
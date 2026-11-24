package com.zero.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

// ==================== P7 商业级 2D 动画引擎核心 ====================

/**
 * 帧同步判定框（支持攻击框 Hitbox / 受击框 Hurtbox）
 */
data class FrameBox(
    val id: String = "box",
    val type: String = "hitbox", // hitbox | hurtbox
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val width: Float = 80f,
    val height: Float = 80f,
    val damage: Float = 15f,
    val knockbackX: Float = 320f,
    val knockbackY: Float = -260f,
    val hitstop: Float = 0.05f
)

/**
 * 专业逐帧数据：支持独立帧时长、逐帧重心偏移、图集帧名、事件、取消窗口、多判定框
 */
data class ProAnimFrame(
    val frameIndex: Int = 0,
    val atlasFrame: String = "",
    val durationMs: Float = 80f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val event: String = "",
    val cancelable: Boolean = false,
    val boxes: List<FrameBox> = emptyList()
)

/**
 * 2D 骨骼/部件关键帧（驱动子节点的平移、旋转、缩放与缓动曲线）
 */
data class BoneKeyframe(
    val timeSec: Float = 0f,
    val x: Float? = null,
    val y: Float? = null,
    val rotation: Float? = null,
    val scaleX: Float? = null,
    val scaleY: Float? = null,
    val ease: String = "linear", // linear | easeIn | easeOut | easeInOut | bezier
    val cx1: Float = 0.25f,
    val cy1: Float = 0.1f,
    val cx2: Float = 0.25f,
    val cy2: Float = 1.0f
)

/**
 * 单个骨骼/子节点的动画轨道
 */
data class BoneTrack(
    val targetNodeName: String,
    val keyframes: List<BoneKeyframe>
)

/**
 * 骨骼采样结果
 */
data class BonePose(
    val targetNodeName: String,
    val x: Float?,
    val y: Float?,
    val rotation: Float?,
    val scaleX: Float?,
    val scaleY: Float?
)

/**
 * 完整专业动画片段（同时支持逐帧/图集轨道 + 2D 骨骼关键帧轨道）
 */
data class ProAnimClip(
    val name: String,
    val loop: Boolean = true,
    val speed: Float = 1.0f,
    val frames: List<ProAnimFrame> = emptyList(),
    val boneTracks: List<BoneTrack> = emptyList()
) {
    val totalDurationSec: Float by lazy {
        val frameDur = frames.sumOf { max(1.0, it.durationMs.toDouble()) }.toFloat() / 1000f
        val boneDur = boneTracks.maxOfOrNull { tr -> tr.keyframes.maxOfOrNull { it.timeSec } ?: 0f } ?: 0f
        max(0.016f, max(frameDur, boneDur))
    }
}

/**
 * 状态机流转条件
 */
data class StateCondition(
    val param: String,
    val op: String, // == | != | > | < | >= | <= | trigger
    val value: Any? = null
)

/**
 * 状态机连线转换规则
 */
data class StateTransition(
    val fromState: String, // "*" 表示任意状态可打断
    val toState: String,
    val priority: Int = 0,
    val requireExitTime: Boolean = false,
    val requireCancelWindow: Boolean = false,
    val conditions: List<StateCondition> = emptyList()
)

/**
 * 动画状态机（FSM）
 */
class AnimStateMachine {
    private val floatParams = mutableMapOf<String, Float>()
    private val boolParams = mutableMapOf<String, Boolean>()
    private val triggers = mutableSetOf<String>()
    private val transitions = mutableListOf<StateTransition>()
    var currentState: String = ""
        private set

    fun setFloat(name: String, v: Float) { floatParams[name] = v }
    fun setBool(name: String, v: Boolean) { boolParams[name] = v }
    fun setTrigger(name: String) { triggers.add(name) }
    fun clearTriggers() { triggers.clear() }

    fun addTransition(t: StateTransition) {
        transitions.add(t)
        transitions.sortByDescending { it.priority }
    }

    fun forceState(state: String) {
        currentState = state
    }

    /**
     * 评估状态流转，若触发切换则返回目标状态名
     */
    fun evaluate(isClipFinished: Boolean, inCancelWindow: Boolean): String? {
        for (tr in transitions) {
            if (tr.fromState != "*" && tr.fromState != currentState) continue
            if (tr.toState == currentState) continue
            if (tr.requireExitTime && !isClipFinished) continue
            if (tr.requireCancelWindow && !inCancelWindow && !isClipFinished) continue
            if (checkAllConditions(tr.conditions)) {
                consumeUsedTriggers(tr.conditions)
                currentState = tr.toState
                return tr.toState
            }
        }
        return null
    }

    private fun checkAllConditions(conds: List<StateCondition>): Boolean {
        if (conds.isEmpty()) return true
        for (c in conds) {
            val ok = when (c.op) {
                "trigger" -> triggers.contains(c.param)
                "==" -> compareParam(c.param, c.value) == 0
                "!=" -> compareParam(c.param, c.value) != 0
                ">" -> compareParam(c.param, c.value) > 0
                "<" -> compareParam(c.param, c.value) < 0
                ">=" -> compareParam(c.param, c.value) >= 0
                "<=" -> compareParam(c.param, c.value) <= 0
                else -> false
            }
            if (!ok) return false
        }
        return true
    }

    private fun compareParam(param: String, target: Any?): Int {
        if (target is Boolean) {
            val cur = boolParams[param] ?: false
            return cur.compareTo(target)
        }
        val curF = floatParams[param] ?: if (boolParams[param] == true) 1f else 0f
        val targetF = (target as? Number)?.toFloat() ?: 0f
        return when {
            abs(curF - targetF) < 1e-4f -> 0
            curF > targetF -> 1
            else -> -1
        }
    }

    private fun consumeUsedTriggers(conds: List<StateCondition>) {
        for (c in conds) {
            if (c.op == "trigger") triggers.remove(c.param)
        }
    }
}

/**
 * 挂载在每个 GameNode 上的专业动画播放器
 */
class ZeroAnimator {
    val clips = mutableMapOf<String, ProAnimClip>()
    val fsm = AnimStateMachine()

    var currentClip: ProAnimClip? = null
        private set
    var currentFrameIdx: Int = 0
        private set
    var frameElapsedMs: Float = 0f
        private set
    var clipElapsedSec: Float = 0f
        private set
    var isPlaying: Boolean = false
        private set
    var isFinished: Boolean = false
        private set
    var speedScale: Float = 1.0f

    // 当前帧输出状态（供渲染器与物理判定直接零开销读取）
    var activeFrame: ProAnimFrame? = null
        private set
    val activeBonePoses = mutableListOf<BonePose>()

    fun registerClip(clip: ProAnimClip) {
        clips[clip.name] = clip
    }

    fun play(clipName: String, forceRestart: Boolean = false): Boolean {
        val clip = clips[clipName] ?: return false
        if (!forceRestart && currentClip?.name == clipName && isPlaying) return true
        currentClip = clip
        currentFrameIdx = 0
        frameElapsedMs = 0f
        clipElapsedSec = 0f
        isPlaying = true
        isFinished = false
        activeFrame = clip.frames.firstOrNull()
        fsm.forceState(clipName)
        sampleBoneTracks(clip, 0f)
        return true
    }

    fun stop() {
        isPlaying = false
    }

    fun update(
        dt: Float,
        onEvent: (clipName: String, eventName: String) -> Unit,
        onFinished: (clipName: String) -> Unit
    ) {
        // 1. 先检查 FSM 状态自动流转
        val inCancel = activeFrame?.cancelable == true
        val nextState = fsm.evaluate(isFinished, inCancel)
        if (nextState != null && clips.containsKey(nextState)) {
            play(nextState, forceRestart = true)
            activeFrame?.event?.takeIf { it.isNotEmpty() }?.let { onEvent(nextState, it) }
            return
        }

        val clip = currentClip ?: return
        if (!isPlaying) return

        val scaledDt = dt * clip.speed * speedScale
        if (scaledDt <= 0f) return

        clipElapsedSec += scaledDt

        // 2. 推进逐帧/图集轨道（支持每帧独立时长）
        if (clip.frames.isNotEmpty()) {
            frameElapsedMs += scaledDt * 1000f
            var guard = 0
            while (guard < 16) {
                guard++
                val cur = clip.frames[currentFrameIdx.coerceIn(0, clip.frames.lastIndex)]
                val dur = max(1f, cur.durationMs)
                if (frameElapsedMs < dur) break
                frameElapsedMs -= dur
                currentFrameIdx++
                if (currentFrameIdx >= clip.frames.size) {
                    if (clip.loop) {
                        currentFrameIdx = 0
                        clipElapsedSec = 0f
                    } else {
                        currentFrameIdx = clip.frames.lastIndex
                        isPlaying = false
                        isFinished = true
                        onFinished(clip.name)
                        break
                    }
                }
                val newFrame = clip.frames[currentFrameIdx]
                activeFrame = newFrame
                if (newFrame.event.isNotEmpty()) {
                    onEvent(clip.name, newFrame.event)
                }
            }
            activeFrame = clip.frames[currentFrameIdx.coerceIn(0, clip.frames.lastIndex)]
        } else {
            // 纯骨骼动画的循环/结束控制
            val total = clip.totalDurationSec
            if (clipElapsedSec >= total) {
                if (clip.loop) {
                    clipElapsedSec %= total
                } else {
                    clipElapsedSec = total
                    isPlaying = false
                    isFinished = true
                    onFinished(clip.name)
                }
            }
        }

        // 3. 采样 2D 骨骼关键帧插值
        sampleBoneTracks(clip, clipElapsedSec)
    }

    private fun sampleBoneTracks(clip: ProAnimClip, timeSec: Float) {
        activeBonePoses.clear()
        if (clip.boneTracks.isEmpty()) return

        for (track in clip.boneTracks) {
            val kfs = track.keyframes
            if (kfs.isEmpty()) continue
            if (kfs.size == 1 || timeSec <= kfs.first().timeSec) {
                val k = kfs.first()
                activeBonePoses.add(BonePose(track.targetNodeName, k.x, k.y, k.rotation, k.scaleX, k.scaleY))
                continue
            }
            if (timeSec >= kfs.last().timeSec) {
                val k = kfs.last()
                activeBonePoses.add(BonePose(track.targetNodeName, k.x, k.y, k.rotation, k.scaleX, k.scaleY))
                continue
            }

            var k0 = kfs.first()
            var k1 = kfs.last()
            for (i in 0 until kfs.size - 1) {
                if (timeSec >= kfs[i].timeSec && timeSec <= kfs[i + 1].timeSec) {
                    k0 = kfs[i]
                    k1 = kfs[i + 1]
                    break
                }
            }

            val span = max(1e-5f, k1.timeSec - k0.timeSec)
            val rawT = ((timeSec - k0.timeSec) / span).coerceIn(0f, 1f)
            val t = applyEase(rawT, k0.ease, k0.cx1, k0.cy1, k0.cx2, k0.cy2)

            activeBonePoses.add(
                BonePose(
                    targetNodeName = track.targetNodeName,
                    x = lerpNullable(k0.x, k1.x, t),
                    y = lerpNullable(k0.y, k1.y, t),
                    rotation = lerpAngleNullable(k0.rotation, k1.rotation, t),
                    scaleX = lerpNullable(k0.scaleX, k1.scaleX, t),
                    scaleY = lerpNullable(k0.scaleY, k1.scaleY, t)
                )
            )
        }
    }

    private fun lerpNullable(a: Float?, b: Float?, t: Float): Float? {
        if (a == null && b == null) return null
        if (a == null) return b
        if (b == null) return a
        return a + (b - a) * t
    }

    private fun lerpAngleNullable(a: Float?, b: Float?, t: Float): Float? {
        if (a == null && b == null) return null
        if (a == null) return b
        if (b == null) return a
        var diff = ((b - a + 540f) % 360f) - 180f
        if (diff < -180f) diff += 360f
        return a + diff * t
    }

    private fun applyEase(t: Float, ease: String, cx1: Float, cy1: Float, cx2: Float, cy2: Float): Float {
        return when (ease) {
            "easeIn" -> t * t
            "easeOut" -> 1f - (1f - t) * (1f - t)
            "easeInOut" -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).pow(2) / 2f
            "bezier" -> evalCubicBezierY(t, cy1, cy2)
            else -> t
        }
    }

    private fun evalCubicBezierY(t: Float, y1: Float, y2: Float): Float {
        val inv = 1f - t
        return 3f * inv * inv * t * y1 + 3f * inv * t * t * y2 + t * t * t
    }

    companion object {
        /**
         * 从场景 JSON 的 proClips / fsmTransitions 解析专业动画库
         */
        fun parseInto(animator: ZeroAnimator, nodeJson: JSONObject) {
            val proClipsArr = nodeJson.optJSONArray("proClips")
            if (proClipsArr != null) {
                for (i in 0 until proClipsArr.length()) {
                    val co = proClipsArr.optJSONObject(i) ?: continue
                    val name = co.optString("name", "").trim()
                    if (name.isEmpty()) continue

                    val framesList = mutableListOf<ProAnimFrame>()
                    val fArr = co.optJSONArray("frames")
                    if (fArr != null) {
                        for (fi in 0 until fArr.length()) {
                            val fo = fArr.optJSONObject(fi) ?: continue
                            val boxes = mutableListOf<FrameBox>()
                            val bArr = fo.optJSONArray("boxes")
                            if (bArr != null) {
                                for (bi in 0 until bArr.length()) {
                                    val bo = bArr.optJSONObject(bi) ?: continue
                                    boxes.add(
                                        FrameBox(
                                            id = bo.optString("id", "box_$bi"),
                                            type = bo.optString("type", "hitbox"),
                                            offsetX = bo.optDouble("offsetX", 0.0).toFloat(),
                                            offsetY = bo.optDouble("offsetY", 0.0).toFloat(),
                                            width = bo.optDouble("width", 80.0).toFloat(),
                                            height = bo.optDouble("height", 80.0).toFloat(),
                                            damage = bo.optDouble("damage", 15.0).toFloat(),
                                            knockbackX = bo.optDouble("knockbackX", 320.0).toFloat(),
                                            knockbackY = bo.optDouble("knockbackY", -260.0).toFloat(),
                                            hitstop = bo.optDouble("hitstop", 0.05).toFloat()
                                        )
                                    )
                                }
                            }
                            framesList.add(
                                ProAnimFrame(
                                    frameIndex = fo.optInt("frameIndex", 0),
                                    atlasFrame = fo.optString("atlasFrame", ""),
                                    durationMs = fo.optDouble("durationMs", 80.0).toFloat(),
                                    offsetX = fo.optDouble("offsetX", 0.0).toFloat(),
                                    offsetY = fo.optDouble("offsetY", 0.0).toFloat(),
                                    event = fo.optString("event", ""),
                                    cancelable = fo.optBoolean("cancelable", false),
                                    boxes = boxes
                                )
                            )
                        }
                    }

                    val tracksList = mutableListOf<BoneTrack>()
                    val tArr = co.optJSONArray("boneTracks")
                    if (tArr != null) {
                        for (ti in 0 until tArr.length()) {
                            val to = tArr.optJSONObject(ti) ?: continue
                            val target = to.optString("target", "").trim()
                            if (target.isEmpty()) continue
                            val kfs = mutableListOf<BoneKeyframe>()
                            val kArr = to.optJSONArray("keyframes")
                            if (kArr != null) {
                                for (ki in 0 until kArr.length()) {
                                    val ko = kArr.optJSONObject(ki) ?: continue
                                    kfs.add(
                                        BoneKeyframe(
                                            timeSec = ko.optDouble("time", 0.0).toFloat(),
                                            x = if (ko.has("x")) ko.optDouble("x").toFloat() else null,
                                            y = if (ko.has("y")) ko.optDouble("y").toFloat() else null,
                                            rotation = if (ko.has("rotation")) ko.optDouble("rotation").toFloat() else null,
                                            scaleX = if (ko.has("scaleX")) ko.optDouble("scaleX").toFloat() else null,
                                            scaleY = if (ko.has("scaleY")) ko.optDouble("scaleY").toFloat() else null,
                                            ease = ko.optString("ease", "linear"),
                                            cx1 = ko.optDouble("cx1", 0.25).toFloat(),
                                            cy1 = ko.optDouble("cy1", 0.1).toFloat(),
                                            cx2 = ko.optDouble("cx2", 0.25).toFloat(),
                                            cy2 = ko.optDouble("cy2", 1.0).toFloat()
                                        )
                                    )
                                }
                            }
                            kfs.sortBy { it.timeSec }
                            tracksList.add(BoneTrack(target, kfs))
                        }
                    }

                    animator.registerClip(
                        ProAnimClip(
                            name = name,
                            loop = co.optBoolean("loop", true),
                            speed = co.optDouble("speed", 1.0).toFloat(),
                            frames = framesList,
                            boneTracks = tracksList
                        )
                    )
                }
            }

            val trArr = nodeJson.optJSONArray("fsmTransitions")
            if (trArr != null) {
                for (i in 0 until trArr.length()) {
                    val to = trArr.optJSONObject(i) ?: continue
                    val conds = mutableListOf<StateCondition>()
                    val cArr = to.optJSONArray("conditions")
                    if (cArr != null) {
                        for (ci in 0 until cArr.length()) {
                            val co = cArr.optJSONObject(ci) ?: continue
                            val rawV = co.opt("value")
                            conds.add(
                                StateCondition(
                                    param = co.optString("param", ""),
                                    op = co.optString("op", "=="),
                                    value = if (rawV === JSONObject.NULL) null else rawV
                                )
                            )
                        }
                    }
                    animator.fsm.addTransition(
                        StateTransition(
                            fromState = to.optString("from", "*"),
                            toState = to.optString("to", ""),
                            priority = to.optInt("priority", 0),
                            requireExitTime = to.optBoolean("requireExitTime", false),
                            requireCancelWindow = to.optBoolean("requireCancelWindow", false),
                            conditions = conds
                        )
                    )
                }
            }
        }
    }
}

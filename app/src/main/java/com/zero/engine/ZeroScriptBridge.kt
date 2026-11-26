package com.zero.engine

import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

class ZeroScriptBridge(
    private val engine: ZeroEngineView
) {
    private var qjs: QuickJSContext? = null
    private var fnBindNode: JSFunction? = null
    private var fnDestroyNode: JSFunction? = null
    private var fnCallHook: JSFunction? = null
    private var fnDispatchTouch: JSFunction? = null
    private var fnAdCallback: JSFunction? = null

    fun destroyVM() {
        try { fnBindNode?.release() } catch (_: Exception) {}
        try { fnDestroyNode?.release() } catch (_: Exception) {}
        try { fnCallHook?.release() } catch (_: Exception) {}
        try { fnDispatchTouch?.release() } catch (_: Exception) {}
        try { fnAdCallback?.release() } catch (_: Exception) {}
        fnBindNode = null; fnDestroyNode = null; fnCallHook = null; fnDispatchTouch = null; fnAdCallback = null
        try { qjs?.destroy() } catch (_: Exception) {}
        qjs = null
    }

    fun initVM() {
        destroyVM()
        if (!qjsLibInited) {
            try { QuickJSLoader.init(); qjsLibInited = true } catch (e: Throwable) { engine.onConsoleLog("[QuickJS 加载失败] ${e.message}") }
        }
        val ctx = QuickJSContext.create()
        qjs = ctx
        engine.dataStore.clearRuntimeCaches()
        engine.atlasCache.clear()

        val g = ctx.getGlobalObject()
        g.setProperty("__zLog", JSCallFunction { args -> engine.onConsoleLog(toJsStr(args.getOrNull(0), "")); null })
        g.setProperty("__zReadScript", JSCallFunction { args ->
            engine.dataStore.readScriptModuleText(toJsStr(args.getOrNull(0), ""))?.let { JSONArray().put(it.first).put(it.second).toString() }
        })
        g.setProperty("__zNodeGet", JSCallFunction { args -> engine.nodeById[toJsInt(args.getOrNull(0), -1)]?.let { getNodeProp(it, toJsStr(args.getOrNull(1), "")) } })
        g.setProperty("__zNodeSet", JSCallFunction { args -> engine.nodeById[toJsInt(args.getOrNull(0), -1)]?.let { setNodeProp(it, toJsStr(args.getOrNull(1), ""), args.getOrNull(2)) }; null })
        g.setProperty("__zNodeCall", JSCallFunction { args -> engine.nodeById[toJsInt(args.getOrNull(0), -1)]?.let { invokeNodeMethod(it, toJsStr(args.getOrNull(1), ""), args) } })
        g.setProperty("__zEngineCall", JSCallFunction { args -> invokeEngineMethod(toJsStr(args.getOrNull(0), ""), args) })
        g.setProperty("__zAdReady", JSCallFunction { args -> ZeroAdBridge.isReady(toJsStr(args.getOrNull(0), "reward")) })
        g.setProperty("__zAdShowInter", JSCallFunction { args ->
            val cbId = toJsInt(args.getOrNull(0), -1)
            ZeroAdBridge.showInterstitial(engine.context) { ok ->
                if (cbId >= 0) engine.queueEvent { try { fnAdCallback?.call(cbId, ok) } catch (_: Exception) {} }
            }
            null
        })
        g.setProperty("__zAdShowReward", JSCallFunction { args ->
            val cbId = toJsInt(args.getOrNull(0), -1)
            ZeroAdBridge.showRewarded(engine.context) { ok ->
                if (cbId >= 0) engine.queueEvent { try { fnAdCallback?.call(cbId, ok) } catch (_: Exception) {} }
            }
            null
        })
        g.setProperty("__zInputGet", JSCallFunction { args ->
            when (toJsStr(args.getOrNull(0), "")) {
                "isTouching" -> engine.isTouching || engine.inputSystem.getTouchCount() > 0
                "touchCount" -> engine.inputSystem.getTouchCount()
                "screenX", "touchX" -> engine.touchScreenX.toDouble()
                "screenY", "touchY" -> engine.touchScreenY.toDouble()
                "worldX" -> engine.touchWorldX.toDouble()
                "worldY" -> engine.touchWorldY.toDouble()
                else -> null
            }
        })
        g.setProperty("__zInputCall", JSCallFunction { args ->
            when (toJsStr(args.getOrNull(0), "")) {
                "isActionPressed" -> engine.inputSystem.isActionPressed(toJsStr(args.getOrNull(1), ""))
                "isActionJustPressed" -> engine.inputSystem.isActionJustPressed(toJsStr(args.getOrNull(1), ""))
                "isActionJustReleased" -> engine.inputSystem.isActionJustReleased(toJsStr(args.getOrNull(1), ""))
                "getAxis" -> engine.inputSystem.getAxis(toJsStr(args.getOrNull(1), "move_left"), toJsStr(args.getOrNull(2), "move_right")).toDouble()
                "bindAction" -> {
                    val act = toJsStr(args.getOrNull(1), "")
                    val list = parseJsonStringList(toJsStr(args.getOrNull(2), "[]"))
                    if (act.isNotEmpty() && list.isNotEmpty()) engine.inputSystem.bindAction(act, list)
                    null
                }
                "setVirtualAction" -> {
                    val act = toJsStr(args.getOrNull(1), "")
                    if (act.isNotEmpty()) engine.inputSystem.setVirtualAction(act, toJsBool(args.getOrNull(2), false))
                    null
                }
                "bindTouchZone" -> {
                    val act = toJsStr(args.getOrNull(1), "")
                    if (act.isNotEmpty()) engine.inputSystem.bindTouchZone(act, toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 160f), toJsFloat(args.getOrNull(5), 160f))
                    null
                }
                "clearTouchZones" -> { engine.inputSystem.clearTouchZones(); null }
                else -> null
            }
        })

        val bootstrap = try {
            engine.context.assets.open("zero_bootstrap.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            engine.onConsoleLog("[VM 错误] 缺少 assets/zero_bootstrap.js: ${e.message}")
            return
        }
        ctx.evaluate(bootstrap, "zero_bootstrap.js")
        ctx.evaluate(P18_AI_JS_SHIM, "zero_p18_ai.js")
        fnBindNode = g.getJSFunction("__zBindNode")
        fnDestroyNode = g.getJSFunction("__zDestroyNode")
        fnCallHook = g.getJSFunction("__zCallHook")
        fnDispatchTouch = g.getJSFunction("__zDispatchTouch")
        fnAdCallback = g.getJSFunction("__zOnAdResult")
        engine.onConsoleLog("[VM] QuickJS ES6+ 高性能脚本引擎已启动")
    }

    fun bindNodeScript(n: ZeroEngineView.GameNode) {
        try { fnBindNode?.call(n.nodeId, n.name, n.script) }
        catch (e: Exception) { engine.onConsoleLog("[JS 编译错误 @ ${n.name}] ${e.message}") }
    }

    fun destroyNode(nodeId: Int) {
        try { fnDestroyNode?.call(nodeId) } catch (_: Exception) {}
    }

    fun callLifecycle(n: ZeroEngineView.GameNode, fnName: String, arg: Any? = null) {
        try { fnCallHook?.call(n.nodeId, fnName, "val", arg) }
        catch (e: Exception) { engine.onConsoleLog("[JS 错误 @ ${n.name}.$fnName] ${e.message}") }
    }

    fun callNodeEvent(n: ZeroEngineView.GameNode, fnName: String, other: ZeroEngineView.GameNode) {
        try { fnCallHook?.call(n.nodeId, fnName, "node", other.nodeId) }
        catch (e: Exception) { engine.onConsoleLog("[JS 错误 @ ${n.name}.$fnName] ${e.message}") }
    }

    fun callFrameEvent(n: ZeroEngineView.GameNode, clipName: String, evName: String) {
        try { fnCallHook?.call(n.nodeId, "onFrameEvent", "two", clipName, evName) }
        catch (e: Exception) { engine.onConsoleLog("[JS 错误 @ ${n.name}.onFrameEvent] ${e.message}") }
    }

    fun callHurtCallback(hurt: ZeroEngineView.GameNode, attacker: ZeroEngineView.GameNode, dmg: Float, kbX: Float, kbY: Float) {
        try { fnCallHook?.call(hurt.nodeId, "onHurt", "hurt", attacker.nodeId, dmg.toDouble(), kbX.toDouble(), kbY.toDouble()) }
        catch (e: Exception) { engine.onConsoleLog("[JS 运行错误 @ ${hurt.name}.onHurt] ${e.message}") }
    }

    fun dispatchTouch(type: String, sx: Float, sy: Float, wx: Float, wy: Float) {
        try { fnDispatchTouch?.call(type, sx.toDouble(), sy.toDouble(), wx.toDouble(), wy.toDouble()) } catch (_: Exception) {}
    }

    private fun getNodeProp(n: ZeroEngineView.GameNode, name: String): Any? = when (name) {
        "name" -> n.name; "tag" -> n.tag; "sceneGroup" -> n.sceneGroup; "persistent" -> n.persistent
        "x" -> n.x.toDouble(); "y" -> n.y.toDouble(); "z" -> n.z.toDouble()
        "vx" -> n.vx.toDouble(); "vy" -> n.vy.toDouble(); "vz" -> n.vz.toDouble()
        "width" -> n.width.toDouble(); "height" -> n.height.toDouble(); "rotation" -> n.rotation.toDouble()
        "tiltX" -> n.tiltX.toDouble(); "scaleX" -> n.scaleX.toDouble(); "scaleY" -> n.scaleY.toDouble()
        "parallaxX" -> n.parallaxX.toDouble(); "parallaxY" -> n.parallaxY.toDouble()
        "atlas" -> n.atlas; "atlasFrame" -> n.atlasFrame
        "normalMap" -> n.normalMap; "normalStrength" -> n.normalStrength.toDouble(); "specular" -> n.specular.toDouble()
        "sliceLeft" -> n.sliceLeft.toDouble(); "sliceRight" -> n.sliceRight.toDouble()
        "sliceTop" -> n.sliceTop.toDouble(); "sliceBottom" -> n.sliceBottom.toDouble()
        "clipChildren" -> n.clipChildren; "clipX" -> n.clipX.toDouble(); "clipY" -> n.clipY.toDouble()
        "clipW" -> n.clipW.toDouble(); "clipH" -> n.clipH.toDouble()
        "slopeType" -> n.slopeType; "colOffsetX" -> n.colOffsetX.toDouble(); "colOffsetY" -> n.colOffsetY.toDouble()
        "colWidth" -> n.colWidth.toDouble(); "colHeight" -> n.colHeight.toDouble()
        "colLayer" -> n.colLayer; "colMask" -> n.colMask; "bodyMode" -> n.bodyMode
        "gravity" -> n.gravity.toDouble(); "fallMultiplier" -> n.fallMultiplier.toDouble(); "maxJumps" -> n.maxJumps
        "isGrounded" -> n.isGrounded; "isHitbox" -> n.isHitbox; "isHurtbox" -> n.isHurtbox
        "damage" -> n.damage.toDouble(); "invulnTimer" -> n.invulnTimer.toDouble()
        "r" -> n.r.toDouble(); "g" -> n.g.toDouble(); "b" -> n.b.toDouble(); "a" -> n.a.toDouble()
        "visible" -> n.visible; "text" -> n.text
        "currentClip" -> n.animator.currentClip?.name ?: n.currentClipName
        "lightRadius" -> n.lightRadius.toDouble(); "lightIntensity" -> n.lightIntensity.toDouble()
        else -> null
    }

    private fun setNodeProp(n: ZeroEngineView.GameNode, name: String, value: Any?) {
        when (name) {
            "tag" -> n.tag = toJsStr(value, n.tag); "persistent" -> n.persistent = toJsBool(value, n.persistent)
            "x" -> n.x = toJsFloat(value, n.x); "y" -> n.y = toJsFloat(value, n.y); "z" -> n.z = toJsFloat(value, n.z)
            "vx" -> n.vx = toJsFloat(value, n.vx); "vy" -> n.vy = toJsFloat(value, n.vy); "vz" -> n.vz = toJsFloat(value, n.vz)
            "width" -> n.width = toJsFloat(value, n.width); "height" -> n.height = toJsFloat(value, n.height)
            "rotation" -> n.rotation = toJsFloat(value, n.rotation); "tiltX" -> n.tiltX = toJsFloat(value, n.tiltX)
            "scaleX" -> n.scaleX = toJsFloat(value, n.scaleX); "scaleY" -> n.scaleY = toJsFloat(value, n.scaleY)
            "parallaxX" -> n.parallaxX = toJsFloat(value, n.parallaxX); "parallaxY" -> n.parallaxY = toJsFloat(value, n.parallaxY)
            "atlas" -> n.atlas = toJsStr(value, n.atlas); "atlasFrame" -> n.atlasFrame = toJsStr(value, n.atlasFrame)
            "normalMap" -> n.normalMap = toJsStr(value, n.normalMap)
            "normalStrength" -> n.normalStrength = toJsFloat(value, n.normalStrength)
            "specular" -> n.specular = toJsFloat(value, n.specular)
            "sliceLeft" -> n.sliceLeft = toJsFloat(value, n.sliceLeft); "sliceRight" -> n.sliceRight = toJsFloat(value, n.sliceRight)
            "sliceTop" -> n.sliceTop = toJsFloat(value, n.sliceTop); "sliceBottom" -> n.sliceBottom = toJsFloat(value, n.sliceBottom)
            "clipChildren" -> n.clipChildren = toJsBool(value, n.clipChildren)
            "clipX" -> n.clipX = toJsFloat(value, n.clipX); "clipY" -> n.clipY = toJsFloat(value, n.clipY)
            "clipW" -> n.clipW = toJsFloat(value, n.clipW); "clipH" -> n.clipH = toJsFloat(value, n.clipH)
            "slopeType" -> n.slopeType = toJsStr(value, n.slopeType)
            "colOffsetX" -> n.colOffsetX = toJsFloat(value, n.colOffsetX); "colOffsetY" -> n.colOffsetY = toJsFloat(value, n.colOffsetY)
            "colWidth" -> n.colWidth = toJsFloat(value, n.colWidth); "colHeight" -> n.colHeight = toJsFloat(value, n.colHeight)
            "colLayer" -> n.colLayer = toJsInt(value, n.colLayer); "colMask" -> n.colMask = toJsInt(value, n.colMask)
            "bodyMode" -> n.bodyMode = toJsStr(value, n.bodyMode)
            "gravity" -> n.gravity = toJsFloat(value, n.gravity); "fallMultiplier" -> n.fallMultiplier = toJsFloat(value, n.fallMultiplier)
            "maxJumps" -> n.maxJumps = toJsInt(value, n.maxJumps)
            "isHitbox" -> n.isHitbox = toJsBool(value, n.isHitbox); "isHurtbox" -> n.isHurtbox = toJsBool(value, n.isHurtbox)
            "damage" -> n.damage = toJsFloat(value, n.damage); "invulnTimer" -> n.invulnTimer = toJsFloat(value, n.invulnTimer)
            "r" -> n.r = toJsFloat(value, n.r); "g" -> n.g = toJsFloat(value, n.g); "b" -> n.b = toJsFloat(value, n.b); "a" -> n.a = toJsFloat(value, n.a)
            "visible" -> n.visible = toJsBool(value, n.visible); "text" -> n.text = toJsStr(value, n.text)
            "lightRadius" -> n.lightRadius = toJsFloat(value, n.lightRadius); "lightIntensity" -> n.lightIntensity = toJsFloat(value, n.lightIntensity)
        }
    }

    private fun invokeNodeMethod(n: ZeroEngineView.GameNode, method: String, args: Array<out Any?>): Any? {
        return when (method) {
            "createSkinnedMesh" -> {
                val uv = engine.resolveNodeUvRect(n)
                engine.meshSystem.createGridMesh(n, toJsInt(args.getOrNull(2), 8), toJsInt(args.getOrNull(3), 12), uv[0], uv[1], uv[2], uv[3]) { engine.getGlobalTransform(it) }
                true
            }
            "bindBones" -> {
                val boneList = parseJsonStringList(toJsStr(args.getOrNull(2), "[]"))
                val smooth = toJsFloat(args.getOrNull(3), 2.2f)
                engine.meshSystem.bindBones(n.name, boneList, engine.nodes, smooth) { engine.getGlobalTransform(it) }
            }
            "setMeshWave" -> {
                engine.meshSystem.setMeshWave(n.name, toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 6f), toJsFloat(args.getOrNull(5), 4f)); null
            }
            "removeSkinnedMesh" -> { engine.meshSystem.removeMesh(n.name); null }
            "attachCloth" -> createClothFromJson(toJsStr(args.getOrNull(2), "{}"), n.name)
            "setNormalMap" -> {
                n.normalMap = toJsStr(args.getOrNull(2), "")
                (args.getOrNull(3) as? Number)?.toFloat()?.let { n.normalStrength = it }
                (args.getOrNull(4) as? Number)?.toFloat()?.let { n.specular = it }
                null
            }
            "setAnimFloat" -> { n.animator.fsm.setFloat(toJsStr(args.getOrNull(2), ""), toJsFloat(args.getOrNull(3), 0f)); null }
            "setAnimBool" -> { n.animator.fsm.setBool(toJsStr(args.getOrNull(2), ""), toJsBool(args.getOrNull(3), false)); null }
            "triggerAnim" -> { n.animator.fsm.setTrigger(toJsStr(args.getOrNull(2), "")); null }
            "setAnimSpeed" -> { n.animator.speedScale = toJsFloat(args.getOrNull(2), 1f).coerceIn(0.05f, 8f); null }
            "getAnimState" -> n.animator.fsm.currentState.ifEmpty { n.currentClipName }
            "isAnimCancelable" -> n.animator.activeFrame?.cancelable == true
            "addAnimTransition" -> {
                val from = toJsStr(args.getOrNull(2), "*")
                val to = toJsStr(args.getOrNull(3), "")
                val param = toJsStr(args.getOrNull(4), "")
                val op = toJsStr(args.getOrNull(5), "trigger")
                val cmpVal = args.getOrNull(6)
                val prio = toJsInt(args.getOrNull(7), 0)
                val hasExit = toJsBool(args.getOrNull(8), false)
                val onlyCancel = toJsBool(args.getOrNull(9), false)
                if (to.isNotEmpty()) {
                    n.animator.fsm.addTransition(StateTransition(from, to, prio, hasExit, onlyCancel, if (param.isNotEmpty()) listOf(StateCondition(param, op, cmpVal)) else emptyList()))
                }
                null
            }
            "registerProClip" -> {
                val s = toJsStr(args.getOrNull(2), "")
                if (s.isBlank()) false else try {
                    ZeroAnimator.parseInto(n.animator, JSONObject().put("proClips", JSONArray().put(JSONObject(s)))); true
                } catch (e: Exception) { engine.onConsoleLog("[ANIM 错误 @ ${n.name}] ${e.message}"); false }
            }
            "setAtlas" -> { n.atlas = toJsStr(args.getOrNull(2), n.atlas); n.atlasFrame = toJsStr(args.getOrNull(3), ""); engine.queryAtlas(n.atlas); null }
            "setNineSlice" -> {
                val l = toJsFloat(args.getOrNull(2), 0f)
                n.sliceLeft = l; n.sliceRight = toJsFloat(args.getOrNull(3), l); n.sliceTop = toJsFloat(args.getOrNull(4), l); n.sliceBottom = toJsFloat(args.getOrNull(5), n.sliceTop); null
            }
            "setClipRect" -> {
                n.clipX = toJsFloat(args.getOrNull(2), 0f); n.clipY = toJsFloat(args.getOrNull(3), 0f); n.clipW = toJsFloat(args.getOrNull(4), 0f); n.clipH = toJsFloat(args.getOrNull(5), 0f); null
            }
            "jump" -> {
                val jf = toJsFloat(args.getOrNull(2), 920f)
                if (n.isGrounded || n.coyoteTimer > 0f) { n.vy = -abs(jf); n.isGrounded = false; n.coyoteTimer = 0f; n.jumpCount = 1; true }
                else if (n.jumpCount < n.maxJumps) { n.vy = -abs(jf) * 0.9f; n.jumpCount++; true }
                else false
            }
            "dropThrough" -> { n.dropThroughTimer = toJsFloat(args.getOrNull(2), 0.22f); n.isGrounded = false; null }
            "bounce" -> { (args.getOrNull(2) as? Number)?.toFloat()?.let { n.vx = it }; n.vy = toJsFloat(args.getOrNull(3), -760f); n.isGrounded = false; n.jumpCount = 1; null }
            "play" -> {
                val cn = toJsStr(args.getOrNull(2), ""); val fr = toJsBool(args.getOrNull(3), false)
                if (n.animator.play(cn, fr)) { n.currentClipName = cn }
                else n.clips[cn]?.let { c ->
                    if (!fr && n.animPlaying && n.currentClipName == cn) return null
                    n.currentClipName = cn; n.animStart = c.start; n.animEnd = c.end; n.animFps = c.fps; n.animLoop = c.loop; n.currentFrame = c.start; n.animTimer = 0f; n.animPlaying = true
                } ?: engine.onConsoleLog("[ANIM 警告 @ ${n.name}] 未找到: $cn")
                null
            }
            "playAnim" -> {
                n.animator.stop(); n.currentClipName = ""
                n.animStart = toJsInt(args.getOrNull(2), 0); n.animEnd = toJsInt(args.getOrNull(3), 0)
                n.animFps = toJsFloat(args.getOrNull(4), 12f); n.animLoop = toJsBool(args.getOrNull(5), true)
                n.currentFrame = n.animStart; n.animTimer = 0f; n.animPlaying = true; null
            }
            "stopAnim" -> { n.animator.stop(); n.animPlaying = false; n.currentClipName = ""; null }
            "flash" -> { n.flashTimer = toJsFloat(args.getOrNull(2), 0.12f); null }
            "emitBurst" -> { val cnt = toJsInt(args.getOrNull(2), 24).coerceIn(1, 120); val gt = engine.getGlobalTransform(n); repeat(cnt) { engine.spawnSingleParticle(n, gt.gx, gt.gy) }; null }
            else -> null
        }
    }

    private fun invokeEngineMethod(method: String, args: Array<out Any?>): Any? {
        return when (method) {
            "createGrass" -> {
                val cfg = try { JSONObject(toJsStr(args.getOrNull(1), "{}")) } catch (_: Exception) { JSONObject() }
                engine.rigSystem.foliageSystem.createGrassPatch(
                    id = cfg.optString("id", ""),
                    x = cfg.optDouble("x", 540.0).toFloat(),
                    y = cfg.optDouble("y", 960.0).toFloat(),
                    z = cfg.optDouble("z", 1.5).toFloat(),
                    width = cfg.optDouble("width", 780.0).toFloat(),
                    height = cfg.optDouble("height", 360.0).toFloat(),
                    count = cfg.optInt("count", 1400),
                    bladeW = cfg.optDouble("bladeW", 12.0).toFloat(),
                    bladeH = cfg.optDouble("bladeH", 54.0).toFloat(),
                    r = cfg.optDouble("r", 0.16).toFloat(),
                    g = cfg.optDouble("g", 0.62).toFloat(),
                    b = cfg.optDouble("b", 0.24).toFloat(),
                    windStrength = cfg.optDouble("wind", 1.0).toFloat(),
                    bendStrength = cfg.optDouble("bend", 1.25).toFloat()
                )
            }
            "removeGrass" -> { engine.rigSystem.foliageSystem.removeGrassPatch(toJsStr(args.getOrNull(1), "")); null }
            "clearGrass" -> { engine.rigSystem.foliageSystem.clear(); null }
            "setWeather" -> {
                val cfg = try { JSONObject(toJsStr(args.getOrNull(1), "{}")) } catch (_: Exception) { JSONObject() }
                engine.rigSystem.weatherSystem.setWeather(
                    typeName = cfg.optString("type", "rain"),
                    intensityVal = cfg.optDouble("intensity", 0.85).toFloat(),
                    windX = cfg.optDouble("wind", -220.0).toFloat(),
                    lightning = cfg.optBoolean("lightning", true),
                    splash = cfg.optBoolean("splash", true)
                )
                null
            }
            "triggerLightning" -> { engine.rigSystem.weatherSystem.triggerLightning(); null }
            "clearWeather" -> { engine.rigSystem.weatherSystem.clear(); null }
            "createWater" -> {
                val cfg = try { JSONObject(toJsStr(args.getOrNull(1), "{}")) } catch (_: Exception) { JSONObject() }
                engine.rigSystem.waterSystem.createWater(
                    id = cfg.optString("id", ""),
                    x = cfg.optDouble("x", 540.0).toFloat(),
                    y = cfg.optDouble("y", 1100.0).toFloat(),
                    z = cfg.optDouble("z", 1.0).toFloat(),
                    width = cfg.optDouble("width", 760.0).toFloat(),
                    height = cfg.optDouble("height", 360.0).toFloat(),
                    r = cfg.optDouble("r", 0.08).toFloat(),
                    g = cfg.optDouble("g", 0.32).toFloat(),
                    b = cfg.optDouble("b", 0.48).toFloat(),
                    a = cfg.optDouble("a", 0.82).toFloat(),
                    reflectStrength = cfg.optDouble("reflect", 0.95).toFloat(),
                    waveScale = cfg.optDouble("waveScale", 1.1).toFloat(),
                    waveSpeed = cfg.optDouble("waveSpeed", 1.0).toFloat(),
                    causticStrength = cfg.optDouble("caustics", 0.75).toFloat(),
                    autoSplash = cfg.optBoolean("autoSplash", true)
                )
            }
            "removeWater" -> { engine.rigSystem.waterSystem.removeWater(toJsStr(args.getOrNull(1), "")); null }
            "addRipple" -> {
                engine.rigSystem.waterSystem.addRipple(
                    x = toJsFloat(args.getOrNull(1), 540f),
                    y = toJsFloat(args.getOrNull(2), 960f),
                    maxRadius = toJsFloat(args.getOrNull(3), 160f),
                    strength = toJsFloat(args.getOrNull(4), 1.1f),
                    duration = toJsFloat(args.getOrNull(5), 1.3f)
                )
                null
            }
            "clearWater" -> { engine.rigSystem.waterSystem.clear(); null }
            "bakeNavGrid" -> engine.aiNavSystem.bakeNavGrid(
                nodes = engine.nodes,
                pendingRemove = engine.pendingRemove,
                requestedCellSize = toJsFloat(args.getOrNull(1), 48f),
                agentPadding = toJsFloat(args.getOrNull(2), 12f),
                colMask = toJsInt(args.getOrNull(3), 0xFFFF),
                physicsSystem = engine.physicsSystem,
                getGlobalTransform = { engine.getGlobalTransform(it) }
            )
            "findPath" -> {
                val path = engine.aiNavSystem.findPath(
                    startX = toJsFloat(args.getOrNull(1), 0f),
                    startY = toJsFloat(args.getOrNull(2), 0f),
                    goalX = toJsFloat(args.getOrNull(3), 0f),
                    goalY = toJsFloat(args.getOrNull(4), 0f),
                    allowDiagonal = toJsBool(args.getOrNull(5), true)
                )
                val arr = JSONArray()
                path.forEach { (px, py) ->
                    arr.put(JSONObject().put("x", px.toDouble()).put("y", py.toDouble()))
                }
                arr.toString()
            }
            "isWalkable" -> engine.aiNavSystem.isWalkable(
                toJsFloat(args.getOrNull(1), 0f),
                toJsFloat(args.getOrNull(2), 0f)
            )
            "canSee" -> {
                val viewer = engine.findNodeByName(toJsStr(args.getOrNull(1), "")) ?: return false
                val target = engine.findNodeByName(toJsStr(args.getOrNull(2), "")) ?: return false
                engine.aiNavSystem.canSee(
                    viewer = viewer,
                    target = target,
                    range = toJsFloat(args.getOrNull(3), 500f),
                    fovDeg = toJsFloat(args.getOrNull(4), 90f),
                    facingDegOverride = toJsFloat(args.getOrNull(5), 999999f),
                    wallMask = toJsInt(args.getOrNull(6), 0xFFFF),
                    nodes = engine.nodes,
                    pendingRemove = engine.pendingRemove,
                    physicsSystem = engine.physicsSystem,
                    getGlobalTransform = { engine.getGlobalTransform(it) }
                )
            }
            "emitSound" -> {
                engine.aiNavSystem.emitSound(
                    x = toJsFloat(args.getOrNull(1), 0f),
                    y = toJsFloat(args.getOrNull(2), 0f),
                    radius = toJsFloat(args.getOrNull(3), 360f),
                    tag = toJsStr(args.getOrNull(4), "noise"),
                    sourceName = toJsStr(args.getOrNull(5), ""),
                    duration = toJsFloat(args.getOrNull(6), 0.8f)
                )
                null
            }
            "hearSounds" -> {
                val list = engine.aiNavSystem.hearSounds(
                    listenerX = toJsFloat(args.getOrNull(1), 0f),
                    listenerY = toJsFloat(args.getOrNull(2), 0f),
                    hearingMul = toJsFloat(args.getOrNull(3), 1.0f),
                    filterTag = toJsStr(args.getOrNull(4), "")
                )
                val arr = JSONArray()
                list.forEach { s ->
                    arr.put(
                        JSONObject()
                            .put("x", s.x.toDouble())
                            .put("y", s.y.toDouble())
                            .put("radius", s.radius.toDouble())
                            .put("tag", s.tag)
                            .put("source", s.sourceName)
                            .put("dist", s.dist.toDouble())
                    )
                }
                arr.toString()
            }
            "clearSounds" -> { engine.aiNavSystem.clearSounds(); null }
            "drawInstanced" -> {
                engine.scriptInstances.add(
                    ZeroEngineView.ScriptInstanceCmd(
                        texture = toJsStr(args.getOrNull(1), ""),
                        x = toJsFloat(args.getOrNull(2), 0f),
                        y = toJsFloat(args.getOrNull(3), 0f),
                        w = toJsFloat(args.getOrNull(4), 32f),
                        h = toJsFloat(args.getOrNull(5), 32f),
                        rotDeg = toJsFloat(args.getOrNull(6), 0f),
                        r = toJsFloat(args.getOrNull(7), 1f),
                        g = toJsFloat(args.getOrNull(8), 1f),
                        b = toJsFloat(args.getOrNull(9), 1f),
                        a = toJsFloat(args.getOrNull(10), 1f),
                        additive = toJsBool(args.getOrNull(11), false),
                        z = toJsFloat(args.getOrNull(12), 2f)
                    )
                )
                null
            }
            "unloadUnusedTextures" -> engine.unloadUnusedTextures()
            "setSdfShadow" -> {
                engine.lightingSystem.sdfShadowEnabled = toJsBool(args.getOrNull(1), true)
                (args.getOrNull(2) as? Number)?.toFloat()?.let { engine.lightingSystem.shadowSoftness = it.coerceIn(1f, 40f) }
                (args.getOrNull(3) as? Number)?.toFloat()?.let { engine.lightingSystem.shadowMinLight = it.coerceIn(0f, 1f) }
                null
            }
            "setGI" -> {
                val raw = toJsStr(args.getOrNull(1), "")
                if (raw == "true" || raw == "false") {
                    engine.giSystem.enabled = raw == "true"
                } else if (raw.startsWith("{")) {
                    try {
                        val o = JSONObject(raw)
                        engine.giSystem.enabled = o.optBoolean("enabled", engine.giSystem.enabled)
                        engine.giSystem.jfaEnabled = o.optBoolean("jfa", engine.giSystem.jfaEnabled)
                        engine.giSystem.intensity = o.optDouble("intensity", engine.giSystem.intensity.toDouble()).toFloat().coerceIn(0f, 3f)
                        engine.giSystem.radius = o.optDouble("radius", engine.giSystem.radius.toDouble()).toFloat().coerceIn(0.02f, 0.45f)
                        engine.giSystem.threshold = o.optDouble("threshold", engine.giSystem.threshold.toDouble()).toFloat().coerceIn(0f, 1.5f)
                        engine.giSystem.saturation = o.optDouble("saturation", engine.giSystem.saturation.toDouble()).toFloat().coerceIn(0f, 3f)
                        engine.giSystem.aoStrength = o.optDouble("ao", engine.giSystem.aoStrength.toDouble()).toFloat().coerceIn(0f, 1f)
                        engine.giSystem.skyR = o.optDouble("skyR", engine.giSystem.skyR.toDouble()).toFloat().coerceIn(0f, 1f)
                        engine.giSystem.skyG = o.optDouble("skyG", engine.giSystem.skyG.toDouble()).toFloat().coerceIn(0f, 1f)
                        engine.giSystem.skyB = o.optDouble("skyB", engine.giSystem.skyB.toDouble()).toFloat().coerceIn(0f, 1f)
                    } catch (_: Exception) {}
                }
                null
            }
            "createCloth" -> createClothFromJson(toJsStr(args.getOrNull(1), "{}"), "")
            "removeCloth" -> { engine.rigSystem.removeCloth(toJsStr(args.getOrNull(1), "")); null }
            "setGlobalWind" -> { engine.rigSystem.setGlobalWind(toJsFloat(args.getOrNull(1), 0f), toJsFloat(args.getOrNull(2), 0f)); null }
            "solveIK" -> {
                val root = engine.findNodeByName(toJsStr(args.getOrNull(1), "")) ?: return false
                val mid = engine.findNodeByName(toJsStr(args.getOrNull(2), "")) ?: return false
                engine.rigSystem.solveTwoBoneIK(root, mid, toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 0f), toJsFloat(args.getOrNull(5), 80f), toJsFloat(args.getOrNull(6), 80f), toJsBool(args.getOrNull(7), false)) { engine.getGlobalTransform(it) }
            }
            "registerIK" -> {
                engine.rigSystem.registerTwoBoneIK(toJsStr(args.getOrNull(1), ""), toJsStr(args.getOrNull(2), ""), toJsStr(args.getOrNull(3), ""), toJsFloat(args.getOrNull(4), 80f), toJsFloat(args.getOrNull(5), 80f), toJsBool(args.getOrNull(6), false)); null
            }
            "setIKTarget" -> { engine.rigSystem.setIkTarget(toJsStr(args.getOrNull(1), ""), toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f), toJsBool(args.getOrNull(4), true)); null }
            "removeIK" -> { engine.rigSystem.removeIk(toJsStr(args.getOrNull(1), "")); null }
            "createSkinnedMesh" -> {
                val target = engine.findNodeByName(toJsStr(args.getOrNull(1), "")) ?: return false
                val uv = engine.resolveNodeUvRect(target)
                engine.meshSystem.createGridMesh(target, toJsInt(args.getOrNull(2), 8), toJsInt(args.getOrNull(3), 12), uv[0], uv[1], uv[2], uv[3]) { engine.getGlobalTransform(it) }
                true
            }
            "bindBones" -> {
                engine.meshSystem.bindBones(toJsStr(args.getOrNull(1), ""), parseJsonStringList(toJsStr(args.getOrNull(2), "[]")), engine.nodes, toJsFloat(args.getOrNull(3), 2.2f)) { engine.getGlobalTransform(it) }
            }
            "setMeshWave" -> {
                engine.meshSystem.setMeshWave(toJsStr(args.getOrNull(1), ""), toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 6f), toJsFloat(args.getOrNull(5), 4f)); null
            }
            "removeSkinnedMesh" -> { engine.meshSystem.removeMesh(toJsStr(args.getOrNull(1), "")); null }
            "loadAtlas" -> {
                val d = engine.queryAtlas(toJsStr(args.getOrNull(1), "")) ?: return null
                JSONObject().apply {
                    put("image", d.imageFile); put("width", d.atlasW.toDouble()); put("height", d.atlasH.toDouble())
                    put("frames", JSONArray(d.frames.keys.sorted()))
                }.toString()
            }
            "setFlag" -> {
                val k = toJsStr(args.getOrNull(1), "")
                if (k.isNotEmpty()) {
                    val v = if (args.size >= 3) args[2] else true
                    engine.dataStore.worldFlags[k] = when (v) { is Boolean -> v; is Number -> v.toDouble(); null -> false; else -> v.toString() }
                }
                null
            }
            "getFlag" -> engine.dataStore.worldFlags[toJsStr(args.getOrNull(1), "")] ?: if (args.size >= 3) args[2] else false
            "hasFlag" -> when (val v = engine.dataStore.worldFlags[toJsStr(args.getOrNull(1), "")]) {
                null -> false; is Boolean -> v; is Number -> v.toDouble() != 0.0; is String -> v.isNotEmpty(); else -> true
            }
            "clearFlag" -> { engine.dataStore.worldFlags.remove(toJsStr(args.getOrNull(1), "")); null }
            "clearAllFlags" -> { engine.dataStore.worldFlags.clear(); null }
            "getAllFlags" -> JSONObject().apply { engine.dataStore.worldFlags.forEach { (k, v) -> put(k, v) } }.toString()
            "saveWorldFlags" -> engine.dataStore.saveWorldFlagsToDisk(toJsStr(args.getOrNull(1), "world_flags"))
            "loadWorldFlags" -> engine.dataStore.loadWorldFlagsFromDisk(toJsStr(args.getOrNull(1), "world_flags"))
            "readTable" -> engine.dataStore.readDataTableJson(toJsStr(args.getOrNull(1), ""))?.second
            "setLanguage" -> engine.dataStore.setLanguage(toJsStr(args.getOrNull(1), "zh"))
            "getLanguage" -> engine.dataStore.currentLang
            "setI18nDict" -> { engine.dataStore.setI18nDictJson(toJsStr(args.getOrNull(1), "zh"), toJsStr(args.getOrNull(2), "{}")); null }
            "t" -> engine.dataStore.translateKey(toJsStr(args.getOrNull(1), ""), toJsStr(args.getOrNull(2), ""))
            "setDebugDraw" -> { engine.physicsSystem.debugDrawEnabled = toJsBool(args.getOrNull(1), false); null }
            "isDebugDraw" -> engine.physicsSystem.debugDrawEnabled
            "setPostFX" -> {
                try {
                    val cfg = JSONObject(toJsStr(args.getOrNull(1), "{}"))
                    engine.postFxEnabled = cfg.optBoolean("enabled", engine.postFxEnabled)
                    engine.postBloom = cfg.optDouble("bloom", engine.postBloom.toDouble()).toFloat().coerceIn(0f, 3f)
                    engine.postVignette = cfg.optDouble("vignette", engine.postVignette.toDouble()).toFloat().coerceIn(0f, 2f)
                    engine.postChromatic = cfg.optDouble("chromatic", engine.postChromatic.toDouble()).toFloat().coerceIn(0f, 3f)
                    engine.postFade = cfg.optDouble("fade", engine.postFade.toDouble()).toFloat().coerceIn(0f, 1f)
                } catch (_: Exception) {}
                null
            }
            "setFade" -> {
                val tg = toJsFloat(args.getOrNull(1), 0f).coerceIn(0f, 1f); val d = toJsFloat(args.getOrNull(2), 0.3f)
                engine.pendingFadeScene = null
                if (d <= 0.01f) { engine.postFade = tg; engine.fadeTarget = tg; engine.fadeSpeed = 0f }
                else { engine.fadeTarget = tg; engine.fadeSpeed = abs(tg - engine.postFade) / d }
                null
            }
            "fadeToScene" -> {
                val s = toJsStr(args.getOrNull(1), ""); val d = toJsFloat(args.getOrNull(2), 0.35f).coerceAtLeast(0.05f)
                if (s.isNotEmpty()) {
                    engine.preloadSceneAsync(s)
                    engine.pendingFadeScene = engine.normalizeSceneName(s)
                    engine.fadeSceneDur = d; engine.fadeTarget = 1f; engine.fadeSpeed = 1f / d
                }
                null
            }
            "preloadScene" -> { val s = toJsStr(args.getOrNull(1), ""); if (s.isNotEmpty()) engine.preloadSceneAsync(s); null }
            "loadSceneAdditive" -> {
                val s = toJsStr(args.getOrNull(1), "")
                if (s.isNotEmpty()) engine.pendingAdditiveLoads.add(ZeroEngineView.AdditiveLoadReq(engine.normalizeSceneName(s), toJsFloat(args.getOrNull(2), 0f), toJsFloat(args.getOrNull(3), 0f)))
                null
            }
            "unloadScene" -> { val s = toJsStr(args.getOrNull(1), ""); if (s.isNotEmpty()) engine.pendingUnloadScenes.add(engine.normalizeSceneName(s)); null }
            "clearSceneCache" -> { engine.preloadedSceneCache.clear(); null }
            "getStats" -> JSONObject().apply {
                put("vm", "QuickJS"); put("gl", "ES3.0")
                put("fps", engine.smoothedFps.toDouble()); put("drawCalls", engine.lastFrameDrawCalls); put("instances", engine.lastFrameInstances); put("culled", engine.lastFrameCulled)
                put("nodes", engine.nodes.size); put("lights", engine.lastFrameLights); put("occluders", engine.lightingSystem.activeOccluderCount)
                put("cloths", engine.rigSystem.activeClothCount); put("iks", engine.rigSystem.activeIkCount); put("skinnedMeshes", engine.meshSystem.activeMeshCount)
                put("gi", engine.giSystem.isReady); put("jfa", engine.giSystem.isJfaReady); put("sdfShadow", engine.lightingSystem.sdfShadowEnabled); put("spatialCells", engine.physicsSystem.spatialCellsCount)
                put("navReady", engine.aiNavSystem.isBaked); put("sounds", engine.aiNavSystem.activeSoundCount)
                put("atlases", engine.atlasCache.size); put("flags", engine.dataStore.worldFlags.size); put("lang", engine.dataStore.currentLang)
                put("fbo", engine.postFxEnabled && engine.fboReady); put("touches", engine.inputSystem.getTouchCount()); put("textures", engine.textureCache.size)
            }.toString()
            "loadScene" -> { val s = toJsStr(args.getOrNull(1), ""); if (s.isNotEmpty()) engine.pendingSceneLoad = engine.normalizeSceneName(s); null }
            "saveDisk" -> engine.dataStore.saveDiskSlotJson(toJsStr(args.getOrNull(1), "slot_1"), toJsStr(args.getOrNull(2), "{}"))
            "loadDisk" -> engine.dataStore.loadDiskSlotJson(toJsStr(args.getOrNull(1), "slot_1"))
            "deleteDisk" -> engine.dataStore.deleteDiskSlot(toJsStr(args.getOrNull(1), "slot_1"))
            "raycast" -> {
                val r = engine.physicsSystem.raycast(
                    toJsFloat(args.getOrNull(1), 0f), toJsFloat(args.getOrNull(2), 0f),
                    toJsFloat(args.getOrNull(3), 0f), toJsFloat(args.getOrNull(4), 0f),
                    toJsStr(args.getOrNull(5), ""), toJsInt(args.getOrNull(6), 0xFFFF)
                ) { engine.getGlobalTransform(it) }
                JSONObject().apply {
                    put("hit", r.hit); put("x", r.x.toDouble()); put("y", r.y.toDouble()); put("distance", r.distance.toDouble())
                    put("nodeId", r.node?.nodeId ?: -1)
                }.toString()
            }
            "hitstop" -> { engine.hitstopTimer = max(engine.hitstopTimer, toJsFloat(args.getOrNull(1), 0.05f).coerceIn(0f, 0.5f)); null }
            "setTimeScale" -> { engine.timeScale = toJsFloat(args.getOrNull(1), 1f).coerceIn(0.05f, 4f); null }
            "setCameraMode" -> { engine.camMode = toJsStr(args.getOrNull(1), "2.5D"); null }
            "setCameraTilt" -> {
                engine.camPitch = toJsFloat(args.getOrNull(1), engine.camPitch)
                engine.camDistance = toJsFloat(args.getOrNull(2), engine.camDistance)
                engine.camFov = toJsFloat(args.getOrNull(3), engine.camFov)
                null
            }
            "setCameraTarget" -> { engine.camTarget = engine.nodeById[toJsInt(args.getOrNull(1), -1)]; null }
            "setCameraDeadzone" -> { engine.camDeadzoneX = toJsFloat(args.getOrNull(1), 0f); engine.camDeadzoneY = toJsFloat(args.getOrNull(2), 0f); null }
            "setCameraLookAhead" -> { engine.camLookAheadX = toJsFloat(args.getOrNull(1), 0f); engine.camLookAheadY = toJsFloat(args.getOrNull(2), 0f); null }
            "setCameraBounds" -> {
                engine.camBoundsEnabled = true
                engine.camMinX = toJsFloat(args.getOrNull(1), -99999f); engine.camMinY = toJsFloat(args.getOrNull(2), -99999f)
                engine.camMaxX = toJsFloat(args.getOrNull(3), 99999f); engine.camMaxY = toJsFloat(args.getOrNull(4), 99999f)
                null
            }
            "clearCameraBounds" -> { engine.camBoundsEnabled = false; null }
            "getCamera" -> JSONObject().apply {
                put("x", engine.camX.toDouble()); put("y", engine.camY.toDouble())
                put("pitch", engine.camPitch.toDouble()); put("mode", engine.camMode)
            }.toString()
            "setAmbientLight" -> {
                engine.ambientR = toJsFloat(args.getOrNull(1), 0.52f)
                engine.ambientG = toJsFloat(args.getOrNull(2), 0.56f)
                engine.ambientB = toJsFloat(args.getOrNull(3), 0.68f)
                null
            }
            "shake" -> { engine.shakeIntensity = toJsFloat(args.getOrNull(1), 12f); engine.shakeTimer = toJsFloat(args.getOrNull(2), 0.15f); null }
            "tween" -> {
                val n = engine.nodeById[toJsInt(args.getOrNull(1), -1)] ?: return null
                val p = toJsStr(args.getOrNull(2), ""); val tv = (args.getOrNull(3) as? Number)?.toFloat() ?: return null
                engine.activeTweens.removeAll { it.node === n && it.prop == p }
                engine.activeTweens.add(ZeroEngineView.TweenTask(n, p, engine.readTweenProperty(n, p), tv, toJsFloat(args.getOrNull(4), 0.25f), toJsStr(args.getOrNull(5), "easeOut")))
                null
            }
            "playTone" -> { engine.audioSystem.playToneAsync(toJsInt(args.getOrNull(1), 440), toJsInt(args.getOrNull(2), 80)); null }
            "playBGM" -> { val f = toJsStr(args.getOrNull(1), ""); if (f.isNotEmpty()) engine.audioSystem.playBGM(f, toJsBool(args.getOrNull(2), true), toJsFloat(args.getOrNull(3), 0.8f)); null }
            "crossfadeBGM" -> { val f = toJsStr(args.getOrNull(1), ""); if (f.isNotEmpty()) engine.audioSystem.crossfadeBGM(f, toJsFloat(args.getOrNull(2), 1.2f), toJsBool(args.getOrNull(3), true), toJsFloat(args.getOrNull(4), 0.8f)); null }
            "stopBGM" -> { engine.audioSystem.stopBGM(toJsFloat(args.getOrNull(1), 0f)); null }
            "playSFX" -> { val f = toJsStr(args.getOrNull(1), ""); val pMin = toJsFloat(args.getOrNull(3), 1.0f); if (f.isNotEmpty()) engine.audioSystem.playSFX(f, toJsFloat(args.getOrNull(2), 1.0f), pMin, toJsFloat(args.getOrNull(4), pMin)); null }
            "setVolume" -> { engine.audioSystem.setVolume(toJsStr(args.getOrNull(1), "master"), toJsFloat(args.getOrNull(2), 1.0f)); null }
            "getVolume" -> engine.audioSystem.getVolume(toJsStr(args.getOrNull(1), "master")).toDouble()
            "moveAndSlide" -> {
                val n = engine.nodeById[toJsInt(args.getOrNull(1), -1)] ?: return null
                engine.physicsSystem.moveAndSlide(n, toJsFloat(args.getOrNull(2), 0.016f), engine.pendingRemove, { engine.getGlobalTransform(it) }) { nd, fn, other -> callNodeEvent(nd, fn, other) }
                null
            }
            "distance" -> {
                val a = engine.nodeById[toJsInt(args.getOrNull(1), -1)]; val b = engine.nodeById[toJsInt(args.getOrNull(2), -1)]
                if (a != null && b != null) { val ga = engine.getGlobalTransform(a); val gb = engine.getGlobalTransform(b); hypot(ga.gx - gb.gx, ga.gy - gb.gy).toDouble() } else 999999.0
            }
            "angleTo" -> {
                val a = engine.nodeById[toJsInt(args.getOrNull(1), -1)]; val b = engine.nodeById[toJsInt(args.getOrNull(2), -1)]
                if (a != null && b != null) { val ga = engine.getGlobalTransform(a); val gb = engine.getGlobalTransform(b); Math.toDegrees(atan2((gb.gy - ga.gy).toDouble(), (gb.gx - ga.gx).toDouble())) } else 0.0
            }
            "spawn" -> {
                val cfg = try { JSONObject(toJsStr(args.getOrNull(1), "{}")) } catch (_: Exception) { return -1 }
                val sp = ZeroNodeFactory.parseNodeFromJson(
                    o = cfg,
                    dataStore = engine.dataStore,
                    physicsSystem = engine.physicsSystem,
                    defaultName = "Spawn_${System.nanoTime() % 10000}",
                    defaultSize = 64.0,
                    defaultLightRadius = 400.0,
                    defaultLightIntensity = 1.5,
                    overrideParent = toJsStr(args.getOrNull(2), "")
                )
                engine.pendingAdd.add(sp); engine.indexNode(sp); bindNodeScript(sp); sp.nodeId
            }
            "instantiate" -> {
                val sName = toJsStr(args.getOrNull(1), "")
                val txt = engine.readSceneText(sName) ?: return -1
                val loaded = mutableListOf<ZeroEngineView.GameNode>()
                ZeroNodeFactory.parseSceneJson(txt, loaded, engine.dataStore, engine.physicsSystem, null, engine.normalizeSceneName(sName), engine.onConsoleLog)
                val sx = toJsFloat(args.getOrNull(2), 0f); val sy = toJsFloat(args.getOrNull(3), 0f); val sz = toJsFloat(args.getOrNull(4), 0f)
                loaded.forEach {
                    if (it.parent.isEmpty()) { it.x += sx; it.y += sy; it.z += sz }
                    engine.pendingAdd.add(it); engine.indexNode(it); bindNodeScript(it)
                }
                loaded.firstOrNull()?.nodeId ?: -1
            }
            "destroy" -> { engine.nodeById[toJsInt(args.getOrNull(1), -1)]?.let { engine.pendingRemove.add(it) }; null }
            "findNode" -> engine.findNodeByName(toJsStr(args.getOrNull(1), ""))?.nodeId ?: -1
            "findNodesByTag" -> {
                val tg = toJsStr(args.getOrNull(1), "")
                JSONArray(engine.nodes.filter { it.tag == tg && !engine.pendingRemove.contains(it) }.map { it.nodeId }).toString()
            }
            else -> null
        }
    }

    private fun createClothFromJson(cfgJson: String, defaultAnchor: String): String {
        val cfg = try { JSONObject(cfgJson) } catch (_: Exception) { JSONObject() }
        val anchorName = cfg.optString("anchor", defaultAnchor)
        val id = cfg.optString("id", "${anchorName}_cloth_${System.nanoTime() % 1000}")
        val anchorNode = engine.findNodeByName(anchorName)
        val gt = anchorNode?.let { engine.getGlobalTransform(it) }
        engine.rigSystem.createCloth(
            id = id,
            anchorNodeName = anchorName,
            startX = gt?.gx ?: cfg.optDouble("x", 540.0).toFloat(),
            startY = gt?.gy ?: cfg.optDouble("y", 960.0).toFloat(),
            offsetX = cfg.optDouble("offsetX", 0.0).toFloat(),
            offsetY = cfg.optDouble("offsetY", 0.0).toFloat(),
            segments = cfg.optInt("segments", 10),
            segmentLen = cfg.optDouble("segmentLen", 18.0).toFloat(),
            widthStart = cfg.optDouble("widthStart", 38.0).toFloat(),
            widthEnd = cfg.optDouble("widthEnd", 10.0).toFloat(),
            r = cfg.optDouble("r", 0.88).toFloat(),
            g = cfg.optDouble("g", 0.18).toFloat(),
            b = cfg.optDouble("b", 0.24).toFloat(),
            a = cfg.optDouble("a", 0.95).toFloat(),
            gravityY = cfg.optDouble("gravityY", 980.0).toFloat(),
            windX = cfg.optDouble("windX", -120.0).toFloat(),
            turbulence = cfg.optDouble("turbulence", 180.0).toFloat(),
            texture = cfg.optString("texture", "")
        )
        return id
    }

    private fun parseJsonStringList(rawJson: String): List<String> {
        val list = mutableListOf<String>()
        if (rawJson.isBlank()) return list
        try {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) list.add(s)
            }
        } catch (_: Exception) { list.add(rawJson) }
        return list
    }

    private fun toJsFloat(v: Any?, def: Float): Float = (v as? Number)?.toFloat() ?: def
    private fun toJsInt(v: Any?, def: Int): Int = (v as? Number)?.toInt() ?: def
    private fun toJsStr(v: Any?, def: String): String = v?.toString() ?: def
    private fun toJsBool(v: Any?, def: Boolean): Boolean = (v as? Boolean) ?: def

    companion object {
        @Volatile private var qjsLibInited = false

        private val P18_AI_JS_SHIM = """
            (function() {
              if (typeof Engine === "undefined") return;
              Engine.bakeNavGrid = function(cellSize, pad, mask) {
                return __zEngineCall("bakeNavGrid",
                  cellSize !== undefined ? cellSize : 48,
                  pad !== undefined ? pad : 12,
                  mask !== undefined ? mask : 65535);
              };
              Engine.findPath = function(x1, y1, x2, y2, allowDiag) {
                var raw = __zEngineCall("findPath", x1, y1, x2, y2, allowDiag !== false);
                return raw ? JSON.parse(raw) : [];
              };
              Engine.isWalkable = function(x, y) {
                return !!__zEngineCall("isWalkable", x, y);
              };
              Engine.canSee = function(viewer, target, range, fovDeg, facingDeg, wallMask) {
                var vName = (typeof viewer === "string") ? viewer : (viewer && viewer.name ? viewer.name : "");
                var tName = (typeof target === "string") ? target : (target && target.name ? target.name : "");
                var fDeg = (facingDeg !== undefined && facingDeg !== null) ? facingDeg : 999999;
                return !!__zEngineCall("canSee",
                  vName, tName,
                  range !== undefined ? range : 500,
                  fovDeg !== undefined ? fovDeg : 90,
                  fDeg,
                  wallMask !== undefined ? wallMask : 65535);
              };
              Engine.emitSound = function(x, y, radius, tag, sourceName, duration) {
                __zEngineCall("emitSound",
                  x, y,
                  radius !== undefined ? radius : 360,
                  tag || "noise",
                  sourceName || "",
                  duration !== undefined ? duration : 0.8);
              };
              Engine.hearSounds = function(x, y, hearingMul, filterTag) {
                var raw = __zEngineCall("hearSounds",
                  x, y,
                  hearingMul !== undefined ? hearingMul : 1.0,
                  filterTag || "");
                return raw ? JSON.parse(raw) : [];
              };
              Engine.clearSounds = function() {
                __zEngineCall("clearSounds");
              };
              Engine.createWater = function(cfg) {
                return __zEngineCall("createWater", JSON.stringify(cfg || {}));
              };
              Engine.removeWater = function(id) {
                __zEngineCall("removeWater", id || "");
              };
              Engine.addRipple = function(x, y, radius, strength, duration) {
                __zEngineCall("addRipple", x, y,
                  radius !== undefined ? radius : 160,
                  strength !== undefined ? strength : 1.1,
                  duration !== undefined ? duration : 1.3);
              };
              Engine.clearWater = function() {
                __zEngineCall("clearWater");
              };
              Engine.setWeather = function(cfg) {
                __zEngineCall("setWeather", JSON.stringify(cfg || {}));
              };
              Engine.triggerLightning = function() {
                __zEngineCall("triggerLightning");
              };
              Engine.clearWeather = function() {
                __zEngineCall("clearWeather");
              };
              Engine.createGrass = function(cfg) {
                return __zEngineCall("createGrass", JSON.stringify(cfg || {}));
              };
              Engine.removeGrass = function(id) {
                __zEngineCall("removeGrass", id || "");
              };
              Engine.clearGrass = function() {
                __zEngineCall("clearGrass");
              };
            })();
        """.trimIndent()
    }
}
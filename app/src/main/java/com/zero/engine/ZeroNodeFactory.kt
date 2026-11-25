package com.zero.engine

import org.json.JSONArray
import org.json.JSONObject

object ZeroNodeFactory {

    fun parseSceneJson(
        raw: String,
        outList: MutableList<ZeroEngineView.GameNode>,
        dataStore: ZeroDataStore,
        physicsSystem: ZeroPhysicsSystem,
        onCameraParsed: ((mode: String, pitch: Float, distance: Float) -> Unit)? = null,
        groupName: String = "main",
        onConsoleLog: (String) -> Unit
    ) {
        try {
            val root = JSONObject(raw)
            if (onCameraParsed != null) {
                root.optJSONObject("camera")?.let {
                    onCameraParsed(
                        it.optString("mode", "2.5D"),
                        it.optDouble("pitch", 48.0).toFloat(),
                        it.optDouble("distance", 1250.0).toFloat()
                    )
                }
            }
            val arr = root.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                outList.add(
                    parseNodeFromJson(
                        o = o,
                        dataStore = dataStore,
                        physicsSystem = physicsSystem,
                        groupName = groupName,
                        defaultName = "Node_$i"
                    )
                )
            }
        } catch (e: Exception) {
            onConsoleLog("[场景解析异常] ${e.message}")
        }
    }

    fun parseNodeFromJson(
        o: JSONObject,
        dataStore: ZeroDataStore,
        physicsSystem: ZeroPhysicsSystem,
        groupName: String = "main",
        defaultName: String = "Node",
        defaultSize: Double = 120.0,
        defaultLightRadius: Double = 450.0,
        defaultLightIntensity: Double = 1.4,
        overrideParent: String? = null
    ): ZeroEngineView.GameNode {
        val clipMap = mutableMapOf<String, ZeroEngineView.AnimClip>()
        o.optJSONArray("clips")?.let { ca ->
            for (ci in 0 until ca.length()) {
                val co = ca.getJSONObject(ci)
                val cn = co.optString("name", "")
                if (cn.isNotEmpty()) {
                    val ev = mutableMapOf<Int, String>()
                    co.optJSONObject("events")?.let { eo ->
                        val ks = eo.keys()
                        while (ks.hasNext()) {
                            val k = ks.next()
                            k.toIntOrNull()?.let { ev[it] = eo.optString(k, "") }
                        }
                    }
                    clipMap[cn] = ZeroEngineView.AnimClip(
                        cn,
                        co.optInt("start", 0),
                        co.optInt("end", 0),
                        co.optDouble("fps", 12.0).toFloat(),
                        co.optBoolean("loop", true),
                        ev
                    )
                }
            }
        }

        val tileList = mutableListOf<ZeroEngineView.TileCell>()
        val tileMap = HashMap<Long, ZeroEngineView.TileCell>()
        o.optJSONArray("tiles")?.let { ta ->
            for (ti in 0 until ta.length()) {
                val to = ta.getJSONObject(ti)
                val c = ZeroEngineView.TileCell(to.optInt("gx", 0), to.optInt("gy", 0), to.optInt("frame", 0))
                tileList.add(c)
                tileMap[physicsSystem.packGridKey(c.gx, c.gy)] = c
            }
        }

        val rawLayer = o.optString("layer", "World")
        val node = ZeroEngineView.GameNode(
            name = o.optString("name", defaultName),
            nodeType = o.optString("nodeType", "Sprite2D"),
            layer = if (rawLayer.startsWith("UI", ignoreCase = true)) "UI" else "World",
            parent = overrideParent ?: o.optString("parent", ""),
            tag = o.optString("tag", "default"),
            sceneGroup = groupName,
            persistent = o.optBoolean("persistent", false),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
            z = o.optDouble("z", 0.0).toFloat(),
            width = o.optDouble("width", defaultSize).toFloat(),
            height = o.optDouble("height", defaultSize).toFloat(),
            rotation = o.optDouble("rotation", 0.0).toFloat(),
            tiltX = o.optDouble("tiltX", 0.0).toFloat(),
            billboard = o.optBoolean("billboard", false),
            scaleX = o.optDouble("scaleX", 1.0).toFloat(),
            scaleY = o.optDouble("scaleY", 1.0).toFloat(),
            zIndex = o.optInt("zIndex", 0),
            visible = o.optBoolean("visible", true),
            parallaxX = o.optDouble("parallaxX", 1.0).toFloat(),
            parallaxY = o.optDouble("parallaxY", 1.0).toFloat(),
            texture = o.optString("texture", ""),
            atlas = o.optString("atlas", ""),
            atlasFrame = o.optString("atlasFrame", ""),
            normalMap = o.optString("normalMap", ""),
            normalStrength = o.optDouble("normalStrength", 1.0).toFloat(),
            specular = o.optDouble("specular", 0.25).toFloat(),
            text = o.optString("text", ""),
            fontSize = o.optDouble("fontSize", 44.0).toFloat(),
            r = o.optDouble("r", 1.0).toFloat(),
            g = o.optDouble("g", 1.0).toFloat(),
            b = o.optDouble("b", 1.0).toFloat(),
            a = o.optDouble("a", 1.0).toFloat(),
            lightRadius = o.optDouble("lightRadius", defaultLightRadius).toFloat(),
            lightIntensity = o.optDouble("lightIntensity", defaultLightIntensity).toFloat(),
            shadowCaster = o.optBoolean("shadowCaster", false),
            sliceLeft = o.optDouble("sliceLeft", 0.0).toFloat(),
            sliceRight = o.optDouble("sliceRight", 0.0).toFloat(),
            sliceTop = o.optDouble("sliceTop", 0.0).toFloat(),
            sliceBottom = o.optDouble("sliceBottom", 0.0).toFloat(),
            clipChildren = o.optBoolean("clipChildren", false),
            clipX = o.optDouble("clipX", 0.0).toFloat(),
            clipY = o.optDouble("clipY", 0.0).toFloat(),
            clipW = o.optDouble("clipW", 0.0).toFloat(),
            clipH = o.optDouble("clipH", 0.0).toFloat(),
            collider = o.optBoolean("collider", false),
            isTrigger = o.optBoolean("isTrigger", false),
            oneWay = o.optBoolean("oneWay", false),
            slopeType = o.optString("slopeType", "none"),
            colOffsetX = o.optDouble("colOffsetX", 0.0).toFloat(),
            colOffsetY = o.optDouble("colOffsetY", 0.0).toFloat(),
            colWidth = o.optDouble("colWidth", 0.0).toFloat(),
            colHeight = o.optDouble("colHeight", 0.0).toFloat(),
            colLayer = o.optInt("colLayer", 1),
            colMask = o.optInt("colMask", 0xFFFF),
            bodyMode = o.optString("bodyMode", "kinematic"),
            gravity = o.optDouble("gravity", 2200.0).toFloat(),
            fallMultiplier = o.optDouble("fallMultiplier", 1.55).toFloat(),
            maxFallSpeed = o.optDouble("maxFallSpeed", 1450.0).toFloat(),
            bounciness = o.optDouble("bounciness", 0.5).toFloat(),
            drag = o.optDouble("drag", 0.0).toFloat(),
            maxJumps = o.optInt("maxJumps", 2),
            isHitbox = o.optBoolean("isHitbox", false),
            isHurtbox = o.optBoolean("isHurtbox", false),
            damage = o.optDouble("damage", 10.0).toFloat(),
            hFrames = o.optInt("hFrames", 1),
            vFrames = o.optInt("vFrames", 1),
            currentFrame = o.optInt("currentFrame", 0),
            animFps = o.optDouble("animFps", 12.0).toFloat(),
            clips = clipMap,
            tileSize = o.optDouble("tileSize", 90.0).toFloat(),
            tiles = tileList,
            tileLookup = tileMap,
            emitRate = o.optDouble("emitRate", 24.0).toFloat(),
            particleLife = o.optDouble("particleLife", 1.1).toFloat(),
            particleSpeed = o.optDouble("particleSpeed", 160.0).toFloat(),
            particleSize = o.optDouble("particleSize", 16.0).toFloat(),
            particleGravity = o.optDouble("particleGravity", -60.0).toFloat(),
            shader = o.optString("shader", ""),
            script = dataStore.resolveNodeScriptText(o.optString("script", ""), o.optString("scriptFile", ""))
        )

        ZeroAnimator.parseInto(node.animator, o)
        val dcName = o.optString("defaultClip", "")
        if (dcName.isNotEmpty()) {
            if (node.animator.play(dcName, true)) {
                node.currentClipName = dcName
            } else {
                clipMap[dcName]?.let { dc ->
                    node.currentClipName = dcName
                    node.animStart = dc.start
                    node.animEnd = dc.end
                    node.animFps = dc.fps
                    node.animLoop = dc.loop
                    node.currentFrame = dc.start
                    node.animPlaying = true
                }
            }
        }
        return node
    }
}
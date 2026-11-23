package com.zero.engine

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private val rootNode = Node2D("Root")

    private val playerCollider = Collider2D("PlayerCollider", 130f, 130f, "player")

    private val playerNode = object : Sprite2D("Player", 130f, 130f, r = 0.2f, g = 0.85f, b = 0.6f) {
        override fun onProcess(delta: Float) {
            // 实时检测是否撞到场景里的任何障碍物
            val allColliders = rootNode.findNodesByType<Collider2D>()
            val hit = allColliders.any { it !== playerCollider && playerCollider.intersects(it) }
            if (hit) {
                r = 0.95f; g = 0.25f; b = 0.25f // 撞墙变红报警
            } else {
                r = 0.2f; g = 0.85f; b = 0.6f   // 正常绿色
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 1. 从 JSON 加载关卡与障碍物
        SceneLoader.loadFromAssets(this, "main.scene.json", rootNode)

        // 2. 挂载主角及碰撞体
        playerNode.x = 540f
        playerNode.y = 850f
        playerNode.addChild(playerCollider)
        rootNode.addChild(playerNode)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(GLRenderer(this@MainActivity, rootNode))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            playerNode.x = event.x
            playerNode.y = event.y
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }
}

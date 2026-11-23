package com.zero.engine

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private val rootNode = Node2D("Root")
    private val camera = Camera2D("MainCamera")
    private val playerCollider = Collider2D("PlayerCollider", 130f, 130f, "player")
    private var wasHitting = false

    private val playerNode = object : Sprite2D("Player", 130f, 130f, r = 0.2f, g = 0.85f, b = 0.6f) {
        override fun onProcess(delta: Float) {
            val allColliders = rootNode.findNodesByType<Collider2D>()
            val hit = allColliders.any { it !== playerCollider && playerCollider.intersects(it) }
            if (hit) {
                r = 0.95f; g = 0.25f; b = 0.25f
                if (!wasHitting) {
                    // 刚撞上的瞬间触发强烈屏幕震动！
                    camera.shake(intensity = 28f, duration = 0.25f)
                }
            } else {
                r = 0.2f; g = 0.85f; b = 0.6f
            }
            wasHitting = hit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 设置相机默认中心点
        camera.x = 540f
        camera.y = 960f
        rootNode.addChild(camera)

        // 加载 JSON 关卡
        SceneLoader.loadFromAssets(this, "main.scene.json", rootNode)

        // 挂载主角
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

    override fun onDestroy() {
        super.onDestroy()
        AudioEngine.release()
    }
}

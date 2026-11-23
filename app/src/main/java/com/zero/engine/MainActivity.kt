package com.zero.engine

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import com.zero.engine.core.Node2D
import com.zero.engine.render.GLRenderer

class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private val rootNode = Node2D("Root")

    private val playerNode = object : Node2D("Player") {
        override fun onProcess(delta: Float) {
            rotation = (rotation + 90f * delta) % 360f
        }

        override fun onDraw(renderer: Any) {
            if (renderer is GLRenderer) {
                renderer.drawRect(globalX, globalY, 140f, 140f, rotation, 0.2f, 0.85f, 0.6f)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerNode.x = 500f
        playerNode.y = 800f
        rootNode.addChild(playerNode)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(GLRenderer(rootNode))
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

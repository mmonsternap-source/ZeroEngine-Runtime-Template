package com.zero.engine

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * ZeroEngine-Runtime-Template 纯运行态入口（独立商业游戏发布壳）
 * 启动时自动释放 assets/game/ 至本地运行目录，播放开屏 Logo 动画并全屏加载 scenes/main.scene
 */
class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private var engineView: ZeroEngineView? = null
    private var lastBackPressMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(container)

        try {
            val runtimeDir = prepareRuntimeGameDir()
            val sceneJson = loadEntrySceneJson(runtimeDir)
            if (sceneJson.isBlank()) {
                showStartupError("未找到入口场景 scenes/main.scene\n请通过 ZeroEngine 的「🚀发布」按钮重新打包工程。")
                return
            }

            val surface = ZeroEngineView(
                context = this,
                projectDir = runtimeDir,
                sceneJson = sceneJson,
                onConsoleLog = { msg -> Log.d("ZeroRuntime", msg) }
            )
            engineView = surface
            container.addView(
                surface,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            // 播放开屏 Logo 动画（默认 Zero Engine 动效，支持读取项目自定义 splash.json / splash_logo.png）
            ZeroSplashOverlay.show(this, container, runtimeDir, isEditorLaunch = false)
        } catch (e: Exception) {
            Log.e("ZeroRuntime", "Boot failed", e)
            showStartupError("游戏启动异常：${e.message}")
        }
    }

    private fun prepareRuntimeGameDir(): File {
        val runtimeDir = File(filesDir, "game_runtime").apply { mkdirs() }
        val stampFile = File(runtimeDir, ".runtime_stamp")
        val currentStamp = computeBuildStamp()

        val needExtract = !stampFile.exists() ||
            stampFile.readText().trim() != currentStamp ||
            !File(runtimeDir, "scenes/main.scene").exists()

        if (needExtract) {
            listOf("scenes", "textures", "audio", "scripts", "data").forEach { sub ->
                val subDir = File(runtimeDir, sub)
                if (subDir.exists()) subDir.deleteRecursively()
                subDir.mkdirs()
            }
            extractAssetFolder("game", runtimeDir)
            stampFile.writeText(currentStamp)
        }
        return runtimeDir
    }

    private fun computeBuildStamp(): String {
        val metaText = try {
            assets.open("game/game_meta.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
        val verCode = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: Exception) {
            1L
        }
        return "${verCode}_${metaText.hashCode()}"
    }

    private fun extractAssetFolder(assetPath: String, destDir: File) {
        val entries = assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            try {
                assets.open(assetPath).use { input ->
                    destDir.parentFile?.mkdirs()
                    destDir.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
            }
            return
        }
        destDir.mkdirs()
        for (name in entries) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val childDest = File(destDir, name)
            val subEntries = assets.list(childAsset)
            if (subEntries != null && subEntries.isNotEmpty()) {
                extractAssetFolder(childAsset, childDest)
            } else {
                try {
                    assets.open(childAsset).use { input ->
                        childDest.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun loadEntrySceneJson(runtimeDir: File): String {
        val mainScene = File(runtimeDir, "scenes/main.scene")
        if (mainScene.exists()) return mainScene.readText()
        val legacyMain = File(runtimeDir, "main.scene.json")
        if (legacyMain.exists()) return legacyMain.readText()
        return try {
            assets.open("game/scenes/main.scene").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    private fun showStartupError(message: String) {
        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#F87171"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        container.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs < 1800L) {
            finish()
        } else {
            lastBackPressMs = now
            Toast.makeText(this, "再按一次返回键退出游戏", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        engineView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        engineView?.onPause()
    }

    override fun onDestroy() {
        engineView?.releaseResources()
        super.onDestroy()
    }
}
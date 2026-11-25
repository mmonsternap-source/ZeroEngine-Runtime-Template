package com.zero.engine

import android.content.Context

/**
 * 编辑器预览 & 无广告模式下的轻量广告桥接（零 SDK 体积占用）
 * 云端开启 AdMob 开关时，release_build.yml 会自动替换为 Google Mobile Ads 真机版
 */
object ZeroAdBridge {
    private var logger: ((String) -> Unit)? = null

    fun init(context: Context, onLog: (String) -> Unit) {
        logger = onLog
    }

    fun isReady(type: String): Boolean = true

    fun showInterstitial(context: Context, onDone: (Boolean) -> Unit) {
        logger?.invoke("[ADS 模拟] 播放插屏广告成功（正式出包开启 AdMob 后将展示真实广告）")
        onDone(true)
    }

    fun showRewarded(context: Context, onResult: (Boolean) -> Unit) {
        logger?.invoke("[ADS 模拟] 激励视频播放完毕，已发放奖励！")
        onResult(true)
    }
}
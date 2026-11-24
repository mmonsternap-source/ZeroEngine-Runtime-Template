package com.zero.engine

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * P13 面向全体开发者的多租户云端一键发布引擎（零服务器成本）：
 * 1. 自动识别当前开发者的 GitHub 身份，首次发布自动从官方公开模板克隆出他自己的私有打包仓库
 * 2. 自动将工程资源（scenes/textures/audio/scripts/data）打包推送并触发云端编译
 * 3. 实时轮询编译进度，构建完成后自动下载并解压出正式版 .apk 与 .aab 到手机本地
 */
class ZeroCloudPublisher(
    private val context: Context,
    private val onProgress: (percent: Int, message: String) -> Unit,
    private val onFinished: (success: Boolean, message: String, outputDir: String) -> Unit
) {
    companion object {
        // 将来对外分发时，把这里改成你存放公开运行时模板仓库的 GitHub 用户名
        const val DEFAULT_TEMPLATE_OWNER = "YOUR_GITHUB_USERNAME"
        const val DEFAULT_TEMPLATE_REPO = "ZeroEngine-Runtime-Template"
        private const val PREF_NAME = "zero_cloud_publish_prefs"
        private const val WORKFLOW_FILE = "release_build.yml"
    }

    data class PublishConfig(
        val githubToken: String,
        val repoName: String = "ZeroGame-CloudBuilder",
        val templateOwner: String = DEFAULT_TEMPLATE_OWNER,
        val templateRepo: String = DEFAULT_TEMPLATE_REPO,
        val appName: String = "MyZeroGame",
        val packageName: String = "com.developer.myzerogame",
        val versionName: String = "1.0.0",
        val versionCode: Int = 1,
        val buildFormat: String = "both" // "apk", "aab", "both"
    )

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var isRunning = false

    fun saveConfig(cfg: PublishConfig) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString("token", cfg.githubToken.trim())
            .putString("repoName", cfg.repoName.trim())
            .putString("templateOwner", cfg.templateOwner.trim())
            .putString("templateRepo", cfg.templateRepo.trim())
            .putString("appName", cfg.appName.trim())
            .putString("packageName", cfg.packageName.trim())
            .putString("versionName", cfg.versionName.trim())
            .putInt("versionCode", cfg.versionCode)
            .putString("buildFormat", cfg.buildFormat)
            .apply()
    }

    fun loadConfigJson(): String {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("githubToken", sp.getString("token", ""))
            put("repoName", sp.getString("repoName", "ZeroGame-CloudBuilder"))
            put("templateOwner", sp.getString("templateOwner", DEFAULT_TEMPLATE_OWNER))
            put("templateRepo", sp.getString("templateRepo", DEFAULT_TEMPLATE_REPO))
            put("appName", sp.getString("appName", "MyZeroGame"))
            put("packageName", sp.getString("packageName", "com.developer.myzerogame"))
            put("versionName", sp.getString("versionName", "1.0.0"))
            put("versionCode", sp.getInt("versionCode", 1))
            put("buildFormat", sp.getString("buildFormat", "both"))
        }.toString()
    }

    fun startCloudBuild(projectDir: File, cfg: PublishConfig) {
        if (isRunning) {
            onFinished(false, "已有云端打包任务正在进行中，请稍候", "")
            return
        }
        if (cfg.githubToken.isBlank()) {
            onFinished(false, "请先填写您自己的 GitHub Token（需勾选 repo 与 workflow 权限）", "")
            return
        }
        if (!cfg.packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
            onFinished(false, "包名格式不合法（示例：com.studio.mygame）", "")
            return
        }

        saveConfig(cfg)
        isRunning = true

        executor.execute {
            try {
                // 1. 识别当前开发者自己的 GitHub 用户名
                onProgress(5, "正在验证开发者 GitHub 身份...")
                val owner = fetchAuthenticatedUser(cfg.githubToken)

                // 2. 检查或从公开模板自动克隆出属于该开发者的私有打包仓库
                onProgress(15, "检查私有打包仓库 $owner/${cfg.repoName}...")
                ensurePrivateBuilderRepo(cfg.githubToken, owner, cfg)

                // 3. 本地打包游戏资源（scenes/textures/audio/scripts/data）
                onProgress(28, "正在压缩当前游戏工程资源...")
                val bundleBytes = zipProjectResources(projectDir, cfg)
                if (bundleBytes.size > 45 * 1024 * 1024) {
                    throw IllegalStateException("工程资源压缩包超过 45MB，请压缩大图或音频后重试")
                }

                // 4. 静默上传 game_bundle.zip 到开发者私有仓库
                onProgress(42, "正在上传游戏包 (${bundleBytes.size / 1024} KB) 到私有云端...")
                val b64 = Base64.encodeToString(bundleBytes, Base64.NO_WRAP)
                uploadFileToRepo(cfg.githubToken, owner, cfg.repoName, "game_bundle.zip", b64, "ZeroEngine Auto Upload: ${cfg.appName} v${cfg.versionName}")

                // 5. 触发 GitHub Actions 云端打包流水线
                onProgress(55, "正在触发云端编译 (${cfg.buildFormat.uppercase()})...")
                val triggerStartMs = System.currentTimeMillis()
                triggerWorkflow(cfg.githubToken, owner, cfg)

                // 6. 轮询构建状态（最长等待 12 分钟）
                val runId = waitForWorkflowRun(cfg.githubToken, owner, cfg.repoName, triggerStartMs)

                // 7. 下载并解压 .apk / .aab 到本地 builds 目录
                onProgress(92, "编译完成！正在下载 APK / AAB 到手机...")
                val outDir = File(projectDir, "builds/${cfg.versionName}_${System.currentTimeMillis() / 1000}")
                outDir.mkdirs()
                val extractedFiles = downloadAndExtractArtifacts(cfg.githubToken, owner, cfg.repoName, runId, outDir)

                if (extractedFiles.isEmpty()) {
                    throw IllegalStateException("未在云端产物中找到 APK 或 AAB 文件")
                }
                onProgress(100, "打包完成！已保存至 ${outDir.absolutePath}")
                onFinished(true, "成功导出：${extractedFiles.joinToString(", ")}", outDir.absolutePath)
            } catch (e: Exception) {
                onFinished(false, "云端打包失败：${e.message}", "")
            } finally {
                isRunning = false
            }
        }
    }

    private fun fetchAuthenticatedUser(token: String): String {
        val (code, body) = apiRequest("GET", "https://api.github.com/user", token)
        if (code !in 200..299) throw IllegalStateException("GitHub Token 无效或网络异常 (HTTP $code)")
        val login = JSONObject(body).optString("login", "")
        if (login.isBlank()) throw IllegalStateException("无法获取 GitHub 用户名")
        return login
    }

    private fun ensurePrivateBuilderRepo(token: String, owner: String, cfg: PublishConfig) {
        val (checkCode, _) = apiRequest("GET", "https://api.github.com/repos/$owner/${cfg.repoName}", token)
        if (checkCode == 200) return

        onProgress(20, "首次使用：正在从模板仓库为您克隆专属私有打包库...")
        val payload = JSONObject().apply {
            put("owner", owner)
            put("name", cfg.repoName)
            put("description", "ZeroEngine Private Cloud Builder")
            put("private", true)
            put("include_all_branches", false)
        }.toString()

        val genUrl = "https://api.github.com/repos/${cfg.templateOwner}/${cfg.templateRepo}/generate"
        val (code, body) = apiRequest("POST", genUrl, token, payload)
        if (code !in 200..299) {
            throw IllegalStateException("克隆模板仓库失败 (HTTP $code)，请确认模板仓库已开启 Template 选项: $body")
        }
        // 等待 GitHub 后台完成模板初始化
        Thread.sleep(5000)
    }

    private fun zipProjectResources(projectDir: File, cfg: PublishConfig): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(BufferedOutputStream(baos)).use { zos ->
            // 写入游戏元数据
            val meta = JSONObject().apply {
                put("appName", cfg.appName)
                put("packageName", cfg.packageName)
                put("versionName", cfg.versionName)
                put("versionCode", cfg.versionCode)
            }.toString(2).toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry("game_meta.json"))
            zos.write(meta)
            zos.closeEntry()

            // 打包游戏核心目录
            val folders = listOf("scenes", "textures", "audio", "scripts", "data")
            for (folderName in folders) {
                val dir = File(projectDir, folderName)
                if (!dir.exists() || !dir.isDirectory) continue
                addFolderToZip(dir, folderName, zos)
            }
        }
        return baos.toByteArray()
    }

    private fun addFolderToZip(dir: File, basePath: String, zos: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val entryPath = "$basePath/${f.name}"
            if (f.isDirectory) {
                addFolderToZip(f, entryPath, zos)
            } else {
                zos.putNextEntry(ZipEntry(entryPath))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun uploadFileToRepo(token: String, owner: String, repo: String, path: String, base64Content: String, msg: String) {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
        val (getCode, getBody) = apiRequest("GET", url, token)
        val existingSha = if (getCode == 200) JSONObject(getBody).optString("sha", "") else ""

        val payload = JSONObject().apply {
            put("message", msg)
            put("content", base64Content)
            if (existingSha.isNotEmpty()) put("sha", existingSha)
        }.toString()

        val (putCode, putBody) = apiRequest("PUT", url, token, payload)
        if (putCode !in 200..299) {
            throw IllegalStateException("上传工程包失败 (HTTP $putCode): $putBody")
        }
    }

    private fun triggerWorkflow(token: String, owner: String, cfg: PublishConfig) {
        val url = "https://api.github.com/repos/$owner/${cfg.repoName}/actions/workflows/$WORKFLOW_FILE/dispatches"
        val inputs = JSONObject().apply {
            put("app_name", cfg.appName)
            put("package_name", cfg.packageName)
            put("version_name", cfg.versionName)
            put("version_code", cfg.versionCode.toString())
            put("build_format", cfg.buildFormat)
        }
        val payload = JSONObject().apply {
            put("ref", "main")
            put("inputs", inputs)
        }.toString()

        val (code, body) = apiRequest("POST", url, token, payload)
        if (code !in 200..299) {
            throw IllegalStateException("触发云端编译失败 (HTTP $code)，请确保 Token 勾选了 workflow 权限: $body")
        }
    }

    private fun waitForWorkflowRun(token: String, owner: String, repo: String, triggerStartMs: Long): Long {
        var runId = -1L
        val maxWaitMs = 12 * 60 * 1000L
        val runsUrl = "https://api.github.com/repos/$owner/$repo/actions/workflows/$WORKFLOW_FILE/runs?per_page=3"

        // 先找到刚触发的 Run ID
        for (attempt in 1..20) {
            Thread.sleep(3000)
            onProgress(56 + attempt / 2, "正在等待云端服务器分配编译机...")
            val (code, body) = apiRequest("GET", runsUrl, token)
            if (code == 200) {
                val runs = JSONObject(body).optJSONArray("workflow_runs")
                if (runs != null && runs.length() > 0) {
                    val latest = runs.getJSONObject(0)
                    val status = latest.optString("status", "")
                    if (status == "queued" || status == "in_progress" || status == "completed") {
                        runId = latest.optLong("id", -1L)
                        break
                    }
                }
            }
        }
        if (runId <= 0L) throw IllegalStateException("未检测到云端编译任务启动")

        val detailUrl = "https://api.github.com/repos/$owner/$repo/actions/runs/$runId"
        var pct = 65
        while (System.currentTimeMillis() - triggerStartMs < maxWaitMs) {
            Thread.sleep(5000)
            val (code, body) = apiRequest("GET", detailUrl, token)
            if (code != 200) continue
            val obj = JSONObject(body)
            val status = obj.optString("status", "")
            val conclusion = obj.optString("conclusion", "")

            if (status == "completed") {
                if (conclusion == "success") return runId
                throw IllegalStateException("云端编译未通过 (结论: $conclusion)，请到 GitHub Actions 查看构建日志")
            }
            pct = (pct + 2).coerceAtMost(90)
            val stateCn = if (status == "queued") "排队中" else "正在编译 APK/AAB 与签名"
            onProgress(pct, "云端$stateCn...")
        }
        throw IllegalStateException("云端编译超时（超过 12 分钟）")
    }

    private fun downloadAndExtractArtifacts(token: String, owner: String, repo: String, runId: Long, outDir: File): List<String> {
        val url = "https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts"
        val (code, body) = apiRequest("GET", url, token)
        if (code != 200) throw IllegalStateException("获取产物列表失败 (HTTP $code)")

        val artifacts = JSONObject(body).optJSONArray("artifacts") ?: return emptyList()
        val savedNames = mutableListOf<String>()

        for (i in 0 until artifacts.length()) {
            val art = artifacts.getJSONObject(i)
            val dlUrl = art.optString("archive_download_url", "")
            if (dlUrl.isBlank()) continue

            // GitHub 下载接口会 302 重定向到外部对象存储，必须手动处理重定向且不带 Authorization 头
            val conn1 = (URL(dlUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 20000
                readTimeout = 60000
            }
            val respCode = conn1.responseCode
            val stream = if (respCode in 301..303 || respCode == 307) {
                val redirectUrl = conn1.getHeaderField("Location")
                conn1.disconnect()
                val conn2 = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 120000
                }
                conn2.inputStream
            } else if (respCode == 200) {
                conn1.inputStream
            } else {
                conn1.disconnect()
                continue
            }

            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name.endsWith(".apk") || entry.name.endsWith(".aab"))) {
                        val outFile = File(outDir, File(entry.name).name)
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        savedNames.add(outFile.name)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return savedNames
    }

    private fun apiRequest(method: String, urlStr: String, token: String, jsonBody: String? = null): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 45000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "ZeroEngine-Mobile-IDE")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }
}

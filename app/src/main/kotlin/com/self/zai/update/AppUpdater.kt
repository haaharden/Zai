package com.self.zai.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.self.zai.BuildConfig
import com.self.zai.shell.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val piHotDownloadUrl: String,
    val piHotManifestDownloadUrl: String = "",
    val piHotSizeBytes: Long,
    val htmlUrl: String,
    val isNewer: Boolean,
)

/**
 * Dual-mode in-app updater backed by GitHub Releases:
 * 1. Full signed APK self-update (via Android Package Installer or rapid Shizuku install).
 * 2. Signed hot-update of the Pi JS agent kernel (`zai-pi-host.js` + `zai-pi-host.manifest.json`)
 *    verified against the embedded ECDSA P-256 publisher public key with automatic rollback on boot failure.
 */
object AppUpdater {

    const val PI_HOT_DIR_NAME = "pi_hot"
    private const val PI_HOT_PREV_DIR_NAME = "pi_hot_prev"
    const val PI_HOT_JS_NAME = "host.js"
    const val PI_HOT_HTML_NAME = "index.html"
    const val PI_HOT_VERSION_NAME = "version.txt"
    private const val PI_HOT_PENDING_BOOT_FLAG = "pending_boot.flag"
    private const val PI_HOT_BOOT_ATTEMPTS_NAME = "boot_attempts.txt"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkLatestRelease(repoSlug: String): Result<ReleaseUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSlug = repoSlug.trim().removePrefix("https://github.com/").trim('/')
            require(cleanSlug.contains("/")) { "仓库格式应为 owner/repo，例如 haaharden/Zai" }

            val req = Request.Builder()
                .url("https://api.github.com/repos/$cleanSlug/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Zai/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 404) error("暂未在 $cleanSlug 发现已发布的 Release")
                if (!resp.isSuccessful) error("检查更新失败 (HTTP ${resp.code})")

                val obj = json.parseToJsonElement(body).jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val version = tag.removePrefix("v").removePrefix("V")
                val title = obj["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { tag } ?: tag
                val notes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()

                val assets = obj["assets"]?.jsonArray.orEmpty()
                    .mapNotNull { runCatching { it.jsonObject }.getOrNull() }

                val apkAsset = assets.firstOrNull {
                    it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
                }
                val piHotAsset = assets.firstOrNull {
                    val n = it["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    n.equals("zai-pi-host.js", ignoreCase = true) || n.equals("host.js", ignoreCase = true)
                }
                val piManifestAsset = assets.firstOrNull {
                    val n = it["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    n.equals(PiHotUpdateVerifier.MANIFEST_ASSET_NAME, ignoreCase = true)
                }

                ReleaseUpdateInfo(
                    tagName = tag,
                    versionName = version,
                    title = title,
                    releaseNotes = notes,
                    apkDownloadUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    apkSizeBytes = apkAsset?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
                    piHotDownloadUrl = piHotAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    piHotManifestDownloadUrl = piManifestAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    piHotSizeBytes = piHotAsset?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
                    htmlUrl = htmlUrl,
                    isNewer = isRemoteVersionNewer(BuildConfig.VERSION_NAME, version),
                )
            }
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(downloadUrl.isNotBlank()) { "该 Release 未附带 APK 安装包" }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, "Zai-update.apk")
            if (outFile.exists()) outFile.delete()
            streamDownload(downloadUrl, outFile, onProgress)
            outFile
        }
    }

    fun installApk(context: Context, apkFile: File): Result<Boolean> = runCatching {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return@runCatching false
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    }

    suspend fun installApkViaShizuku(
        context: Context,
        shizuku: ShizukuBridge,
        apkFile: File,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val extDir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
            val extApk = File(extDir, "Zai-shizuku-update.apk")
            apkFile.copyTo(extApk, overwrite = true)
            val quoted = "'${extApk.absolutePath.replace("'", "'\"'\"'")}'"
            val out = shizuku.exec("pm install -r $quoted", timeoutMs = 30_000).getOrThrow()
            runCatching { extApk.delete() }
            if (!out.contains("Success", ignoreCase = true)) {
                error(out.ifBlank { "Shizuku 安装未返回 Success" })
            }
            out.trim()
        }
    }

    /**
     * Downloads, cryptographically verifies (ECDSA P-256 + SHA-256), and stages a hot-updated Pi JS bundle
     * with automatic rollback backup of the previously verified kernel.
     */
    suspend fun applyPiHotUpdate(
        context: Context,
        downloadUrl: String,
        manifestUrl: String,
        tagName: String,
        onProgress: (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(downloadUrl.isNotBlank()) { "该 Release 未附带 Pi 热更新包 (zai-pi-host.js)" }
            require(manifestUrl.isNotBlank()) {
                "该 Release 缺少签名清单 (${PiHotUpdateVerifier.MANIFEST_ASSET_NAME})，拒绝加载未签名内核"
            }

            val stageDir = File(context.cacheDir, "pi_hot_stage").apply {
                deleteRecursively()
                mkdirs()
            }
            val manifestFile = File(stageDir, PiHotUpdateVerifier.MANIFEST_ASSET_NAME)
            val tmpFile = File(stageDir, "$PI_HOT_JS_NAME.tmp")

            try {
                streamDownload(manifestUrl, manifestFile) { p -> onProgress(p * 0.1f) }
                streamDownload(downloadUrl, tmpFile) { p -> onProgress(0.1f + p * 0.85f) }

                val manifestText = manifestFile.readText(Charsets.UTF_8)
                val jsBytes = tmpFile.readBytes()
                val verified = PiHotUpdateVerifier.verifyBundle(
                    manifestJson = manifestText,
                    bundleBytes = jsBytes,
                    expectedTag = tagName,
                ).getOrThrow()

                val hotDir = File(context.filesDir, PI_HOT_DIR_NAME)
                val prevDir = File(context.filesDir, PI_HOT_PREV_DIR_NAME)

                // Preserve previous known-good hot bundle for automatic rollback if the new kernel fails to boot
                if (hotDir.exists() &&
                    File(hotDir, PI_HOT_JS_NAME).exists() &&
                    !File(hotDir, PI_HOT_PENDING_BOOT_FLAG).exists()
                ) {
                    prevDir.deleteRecursively()
                    hotDir.copyRecursively(prevDir, overwrite = true)
                }

                hotDir.mkdirs()
                val finalJs = File(hotDir, PI_HOT_JS_NAME)
                val swapTmp = File(hotDir, "$PI_HOT_JS_NAME.new")
                swapTmp.writeBytes(jsBytes)
                if (finalJs.exists()) finalJs.delete()
                require(swapTmp.renameTo(finalJs)) { "写入热更新内核失败" }

                File(hotDir, PI_HOT_HTML_NAME).writeText(
                    """<!doctype html><meta charset="utf-8"><title>zai-pi-host</title><script src="host.js?t=${System.currentTimeMillis()}"></script>""",
                    Charsets.UTF_8,
                )
                File(hotDir, PI_HOT_VERSION_NAME).writeText(verified.tag.ifBlank { tagName }, Charsets.UTF_8)
                // Mark as unverified boot until WebView successfully emits "ready"
                File(hotDir, PI_HOT_PENDING_BOOT_FLAG).writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
                File(hotDir, PI_HOT_BOOT_ATTEMPTS_NAME).writeText("0", Charsets.UTF_8)
                onProgress(1f)
                verified.tag
            } finally {
                runCatching { stageDir.deleteRecursively() }
            }
        }
    }

    /**
     * Tracks startup attempts for an unverified hot bundle. If a prior boot attempt died before emitting
     * `ready` (for example a hard process crash loop), rolls back automatically before loading WebView.
     */
    fun recordPiHotBootAttemptOrRollback(context: Context): Boolean {
        val hotDir = File(context.filesDir, PI_HOT_DIR_NAME)
        val pendingFlag = File(hotDir, PI_HOT_PENDING_BOOT_FLAG)
        if (!pendingFlag.exists()) return false
        val attemptsFile = File(hotDir, PI_HOT_BOOT_ATTEMPTS_NAME)
        val priorAttempts = runCatching {
            attemptsFile.readText(Charsets.UTF_8).trim().toIntOrNull() ?: 0
        }.getOrDefault(0)
        if (priorAttempts >= 1) {
            rollbackPiHotUpdate(context)
            return true
        }
        runCatching { attemptsFile.writeText((priorAttempts + 1).toString(), Charsets.UTF_8) }
        return false
    }

    /** Called once the loaded hot-updated Pi WebView successfully completes startup (`ready`). */
    fun confirmPiHotBootSuccess(context: Context) {
        runCatching {
            val hotDir = File(context.filesDir, PI_HOT_DIR_NAME)
            File(hotDir, PI_HOT_PENDING_BOOT_FLAG).delete()
            File(hotDir, PI_HOT_BOOT_ATTEMPTS_NAME).delete()
            File(context.filesDir, PI_HOT_PREV_DIR_NAME).deleteRecursively()
        }
    }

    fun isPiHotBootPending(context: Context): Boolean {
        val flag = File(File(context.filesDir, PI_HOT_DIR_NAME), PI_HOT_PENDING_BOOT_FLAG)
        return flag.exists()
    }

    /**
     * Rolls back a failed hot-updated kernel to the previously confirmed hot bundle if available,
     * otherwise clears `pi_hot` so the APK's bundled `assets/pi/host.js` takes over cleanly.
     */
    fun rollbackPiHotUpdate(context: Context): Boolean {
        return runCatching {
            val hotDir = File(context.filesDir, PI_HOT_DIR_NAME)
            val prevDir = File(context.filesDir, PI_HOT_PREV_DIR_NAME)
            hotDir.deleteRecursively()
            if (prevDir.exists() && File(prevDir, PI_HOT_JS_NAME).length() > 10_000L) {
                prevDir.copyRecursively(hotDir, overwrite = true)
                File(hotDir, PI_HOT_PENDING_BOOT_FLAG).delete()
                prevDir.deleteRecursively()
                true
            } else {
                prevDir.deleteRecursively()
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Returns the active Pi hot-update tag ONLY when it is strictly newer than the installed APK version.
     * Whenever a newer (or same-version) APK is installed, any older `pi_hot` override is automatically
     * evicted so the freshly bundled `assets/pi/host.js` takes effect immediately without manual reload.
     */
    fun getActivePiHotTag(context: Context): String? {
        val dir = File(context.filesDir, PI_HOT_DIR_NAME)
        val js = File(dir, PI_HOT_JS_NAME)
        val html = File(dir, PI_HOT_HTML_NAME)
        if (!js.exists() || !html.exists() || js.length() < 10_000L) return null
        val savedTag = runCatching {
            File(dir, PI_HOT_VERSION_NAME).readText(Charsets.UTF_8).trim()
        }.getOrDefault("")
        if (shouldDiscardHotUpdate(BuildConfig.VERSION_NAME, savedTag)) {
            clearPiHotUpdate(context)
            return null
        }
        return savedTag.ifBlank { "已热更" }
    }

    fun shouldDiscardHotUpdate(installedAppVersion: String, savedHotTag: String): Boolean {
        val clean = savedHotTag.trim()
        if (clean.isEmpty()) return true
        return !isRemoteVersionNewer(installedAppVersion, clean)
    }

    fun clearPiHotUpdate(context: Context) {
        runCatching {
            File(context.filesDir, PI_HOT_DIR_NAME).deleteRecursively()
            File(context.filesDir, PI_HOT_PREV_DIR_NAME).deleteRecursively()
        }
    }

    fun isRemoteVersionNewer(current: String, remote: String): Boolean {
        val cParts = current.removePrefix("v").removePrefix("V").split(".", "-").mapNotNull { it.toIntOrNull() }
        val rParts = remote.removePrefix("v").removePrefix("V").split(".", "-").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(cParts.size, rParts.size)
        for (i in 0 until maxLen) {
            val c = cParts.getOrElse(i) { 0 }
            val r = rParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun streamDownload(
        downloadUrl: String,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val req = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Zai/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败 (HTTP ${resp.code})")
            val body = resp.body ?: error("响应内容为空")
            val total = body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        onProgress(1f)
    }
}

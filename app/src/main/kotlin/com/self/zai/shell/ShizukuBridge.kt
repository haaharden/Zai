package com.self.zai.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.self.zai.BuildConfig
import com.self.zai.permission.DevicePermissionHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

data class ShizukuStatus(
    val installed: Boolean = false,
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val serviceBound: Boolean = false,
    val uid: Int = -1,
) {
    val isReady: Boolean get() = binderAlive && permissionGranted && serviceBound
    val identityLabel: String
        get() = when (uid) {
            0 -> "Root (UID 0)"
            2000 -> "ADB Shell (UID 2000)"
            else -> if (serviceBound) "UID $uid" else "未连接"
        }
}

/**
 * Manages Shizuku binder lifecycle, permission requests, and resilient auto-rebinding UserService.
 */
class ShizukuBridge(private val context: Context) {

    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val bindMutex = Mutex()

    @Volatile
    private var shellService: IZaiShellService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IZaiShellService?>? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ZaiShellService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("shell_service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stub = service?.let { IZaiShellService.Stub.asInterface(it) }
            shellService = stub
            val uid = runCatching { stub?.uid ?: -1 }.getOrDefault(-1)
            refreshStatus(boundOverride = stub != null, uidOverride = uid)
            pendingBind?.complete(stub)
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            refreshStatus(boundOverride = false, uidOverride = -1)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
        if (hasPermission()) {
            bindAsync()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        refreshStatus(boundOverride = false, uidOverride = -1)
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_CODE_SHIZUKU) {
            refreshStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindAsync()
            }
        }
    }

    fun register() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
        refreshStatus()
        if (isBinderAlive() && hasPermission()) {
            bindAsync()
        }
    }

    fun refreshStatus(boundOverride: Boolean? = null, uidOverride: Int? = null) {
        val installed = isShizukuAppInstalled()
        val binder = isBinderAlive()
        val granted = binder && hasPermission()
        val aliveService = shellService?.takeIf { it.asBinder().isBinderAlive }
        val bound = boundOverride ?: (aliveService != null)
        val uid = uidOverride ?: if (bound) runCatching { aliveService?.uid ?: -1 }.getOrDefault(-1) else -1
        _status.value = ShizukuStatus(
            installed = installed,
            binderAlive = binder,
            permissionGranted = granted,
            serviceBound = bound,
            uid = uid,
        )
    }

    fun requestPermission(): Boolean {
        if (!isBinderAlive()) {
            refreshStatus()
            return false
        }
        if (hasPermission()) {
            bindAsync()
            return true
        }
        return runCatching {
            Shizuku.requestPermission(REQ_CODE_SHIZUKU)
            true
        }.getOrDefault(false)
    }

    fun bindAsync() {
        if (!isBinderAlive() || !hasPermission()) return
        if (shellService?.asBinder()?.isBinderAlive == true) return
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }
    }

    suspend fun ensureService(): IZaiShellService? = withContext(Dispatchers.IO) {
        shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withContext it }
        if (!isBinderAlive() || !hasPermission()) {
            refreshStatus(boundOverride = false, uidOverride = -1)
            return@withContext null
        }
        bindMutex.withLock {
            shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            val deferred = CompletableDeferred<IZaiShellService?>()
            pendingBind = deferred
            val requested = runCatching {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                true
            }.getOrDefault(false)
            if (!requested) {
                pendingBind = null
                return@withLock null
            }
            withTimeoutOrNull(5_000L) { deferred.await() }
        }
    }

    suspend fun exec(command: String, timeoutMs: Int = 10_000): Result<String> = withContext(Dispatchers.IO) {
        val svc = ensureService()
            ?: return@withContext Result.failure(IllegalStateException("Shizuku 未连接或未授权"))
        val raw = runCatching { svc.exec(command, timeoutMs) }
            .getOrElse { e ->
                shellService = null
                refreshStatus(boundOverride = false, uidOverride = -1)
                return@withContext Result.failure(e)
            }
        if (raw.startsWith("ERR_")) {
            Result.failure(IllegalStateException(raw))
        } else {
            Result.success(raw)
        }
    }

    suspend fun tap(x: Int, y: Int): Result<String> = exec("input tap $x $y")

    suspend fun longPress(x: Int, y: Int, durationMs: Int = 700): Result<String> =
        exec("input swipe $x $y $x $y $durationMs")

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 350): Result<String> =
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun keyEvent(keyCode: Int): Result<String> = exec("input keyevent $keyCode")

    suspend fun inputTextAscii(text: String): Result<String> {
        val escaped = text
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("&", "\\&")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace(";", "\\;")
        return exec("input text \"$escaped\"")
    }

    suspend fun screencapToFile(file: File): Result<File> = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val path = shellQuote(file.absolutePath)
        val res = exec("screencap -p $path", timeoutMs = 8_000)
        if (res.isSuccess && file.exists() && file.length() > 100) {
            Result.success(file)
        } else {
            Result.failure(res.exceptionOrNull() ?: IllegalStateException("Shizuku 截屏写入失败"))
        }
    }

    suspend fun dumpUiXmlToFile(file: File): Result<String> = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val path = shellQuote(file.absolutePath)
        val res = exec("uiautomator dump $path", timeoutMs = 10_000)
        if (res.isSuccess && file.exists() && file.length() > 20) {
            Result.success(file.readText(Charsets.UTF_8))
        } else {
            Result.failure(res.exceptionOrNull() ?: IllegalStateException("uiautomator dump 失败"))
        }
    }

    suspend fun getTopPackageName(): String? {
        val out = exec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4", 4_000).getOrNull()
            ?: return null
        val match = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/").find(out)
        return match?.groupValues?.getOrNull(1)
    }

    suspend fun launchPackage(packageName: String): Result<String> =
        exec("monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1", 6_000)

    /**
     * Grants `WRITE_SECURE_SETTINGS` permanently to Zai via Shizuku so future accessibility
     * self-healing survives reboots without needing Shizuku running.
     */
    suspend fun grantWriteSecureSettings(packageName: String = context.packageName): Result<Unit> =
        exec("pm grant ${shellQuote(packageName)} android.permission.WRITE_SECURE_SETTINGS", 5_000)
            .map { Unit }

    /**
     * Restores Zai's AccessibilityService via secure settings while preserving all other enabled services.
     * When the service is already listed but unbound, toggles it off briefly before re-enabling so
     * Android's AccessibilityManagerService reliably triggers a fresh bind.
     */
    suspend fun restoreAccessibilityService(
        fullComponent: String,
        shortComponent: String = fullComponent,
        forceRebind: Boolean = true,
    ): Result<Unit> {
        val currentRaw = exec("settings get secure enabled_accessibility_services", 4_000)
            .getOrElse { return Result.failure(it) }
            .trim()
        val plan = DevicePermissionHelper.buildAccessibilityRestorePlan(
            rawEnabledServices = currentRaw,
            fullComponent = fullComponent,
            shortComponent = shortComponent,
        )
        if (plan.alreadyPresent && forceRebind) {
            val clearCmd = if (plan.withoutSelf.isEmpty()) {
                "settings delete secure enabled_accessibility_services"
            } else {
                "settings put secure enabled_accessibility_services ${shellQuote(plan.withoutSelf)}"
            }
            exec(clearCmd, 4_000).getOrElse { return Result.failure(it) }
            delay(180L)
        }
        val putRes = exec(
            "settings put secure enabled_accessibility_services ${shellQuote(plan.withSelf)} && settings put secure accessibility_enabled 1",
            5_000,
        )
        return putRes.map { Unit }
    }

    private fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun isShizukuAppInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    private fun shellQuote(s: String): String = "'${s.replace("'", "'\"'\"'")}'"

    companion object {
        const val REQ_CODE_SHIZUKU = 9101
    }
}

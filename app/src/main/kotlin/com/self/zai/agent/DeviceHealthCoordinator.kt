package com.self.zai.agent

import android.content.ComponentName
import android.content.Context
import com.self.zai.data.SessionRepository
import com.self.zai.model.ZaiComponentNames
import com.self.zai.permission.DevicePermissionHelper
import com.self.zai.shell.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single owner of Zai's self-healing lifecycle for AccessibilityService, VoiceInteractionService,
 * and Shizuku rebinding. Extracted from [com.self.zai.di.AppContainer] so the DI container stays
 * a pure composition root and Activity/Receiver entrypoints share one health pipeline.
 */
class DeviceHealthCoordinator(
    context: Context,
    private val sessionRepository: SessionRepository,
    private val shizukuBridge: ShizukuBridge,
    private val isAccessibilityConnected: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accessibilityRepairMutex = Mutex()
    private val accessibilityComponent = ComponentName(
        appContext.packageName,
        ZaiComponentNames.ACCESSIBILITY_SERVICE,
    )

    @Volatile
    private var lastFailedAutoRepairAtMs: Long = 0L

    fun startMonitoring() {
        autoRestoreVoiceInteractionIfPossible()
        autoRestoreAccessibilityIfPossible(force = true)
        scope.launch {
            shizukuBridge.status.collect { st ->
                if (st.isReady && !isAccessibilityConnected()) {
                    autoRestoreAccessibilityIfPossible()
                }
            }
        }
    }

    /** Unified foreground self-check called by MainActivity and ZaiAssistActivity. */
    fun ensureHealthyOnForeground(refreshShizuku: Boolean = false) {
        isAccessibilityConnected()
        if (refreshShizuku) {
            shizukuBridge.refreshStatus()
            if (shizukuBridge.status.value.binderAlive &&
                shizukuBridge.status.value.permissionGranted
            ) {
                shizukuBridge.bindAsync()
            }
        }
        autoRestoreVoiceInteractionIfPossible()
        autoRestoreAccessibilityIfPossible()
    }

    /** Unified post-upgrade / post-boot self-heal hook. */
    fun onBootOrUpgrade() {
        autoRestoreVoiceInteractionIfPossible()
        autoRestoreAccessibilityIfPossible(force = true)
    }

    suspend fun repairAccessibility(waitForBindTimeoutMs: Long = 1_500L): Result<Unit> =
        accessibilityRepairMutex.withLock {
            if (isAccessibilityConnected()) {
                return@withLock Result.success(Unit)
            }
            var lastFailure: Throwable? = null
            if (DevicePermissionHelper.hasWriteSecureSettings(appContext)) {
                val nativeRes = DevicePermissionHelper.restoreAccessibilityViaSecureSettings(
                    context = appContext,
                    isAccessibilityConnected = isAccessibilityConnected,
                    waitForBindTimeoutMs = waitForBindTimeoutMs,
                )
                if (nativeRes.isSuccess) return@withLock nativeRes
                lastFailure = nativeRes.exceptionOrNull()
            }
            val shizukuReady = shizukuBridge.status.value.isReady || shizukuBridge.ensureService() != null
            if (shizukuReady) {
                // Opportunistically grant permanent WRITE_SECURE_SETTINGS via Shizuku so future
                // self-healing survives reboots without requiring Shizuku to be active.
                if (!DevicePermissionHelper.hasWriteSecureSettings(appContext)) {
                    shizukuBridge.grantWriteSecureSettings()
                }
                val shizukuRes = shizukuBridge.restoreAccessibilityService(
                    fullComponent = accessibilityComponent.flattenToString(),
                    shortComponent = accessibilityComponent.flattenToShortString(),
                    forceRebind = true,
                )
                if (shizukuRes.isSuccess) {
                    val connected = DevicePermissionHelper.awaitAccessibilityConnected(
                        isAccessibilityConnected = isAccessibilityConnected,
                        timeoutMs = waitForBindTimeoutMs,
                    )
                    if (connected) return@withLock Result.success(Unit)
                    lastFailure = IllegalStateException(
                        "已通过 Shizuku 写入无障碍配置，但系统未完成绑定，请在无障碍页面手动确认（如开关变灰请先允许受限制的设置）",
                    )
                } else {
                    lastFailure = shizukuRes.exceptionOrNull()
                }
            }
            Result.failure(
                lastFailure ?: IllegalStateException("未授予 ADB 永久自愈权限且 Shizuku 未就绪"),
            )
        }

    fun autoRestoreAccessibilityIfPossible(force: Boolean = false) {
        if (!sessionRepository.settings.value.autoRestoreAccessibility) return
        if (isAccessibilityConnected()) return
        val status = shizukuBridge.status.value
        val canUseShizuku = status.isReady || (status.binderAlive && status.permissionGranted)
        if (!DevicePermissionHelper.hasWriteSecureSettings(appContext) && !canUseShizuku) return
        if (accessibilityRepairMutex.isLocked) return
        val now = System.currentTimeMillis()
        if (!force && now - lastFailedAutoRepairAtMs < AUTO_REPAIR_FAILURE_COOLDOWN_MS) return
        scope.launch {
            val res = repairAccessibility()
            if (res.isFailure) {
                lastFailedAutoRepairAtMs = System.currentTimeMillis()
            }
        }
    }

    fun autoRestoreVoiceInteractionIfPossible() {
        if (!DevicePermissionHelper.needsVoiceInteractionRepair(appContext)) return
        if (!DevicePermissionHelper.hasWriteSecureSettings(appContext)) return
        scope.launch {
            DevicePermissionHelper.restoreVoiceInteractionServiceIfNeeded(appContext)
        }
    }

    companion object {
        private const val AUTO_REPAIR_FAILURE_COOLDOWN_MS = 3_000L
    }
}

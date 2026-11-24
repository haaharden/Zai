package com.self.zai

import android.app.Application
import com.self.zai.agent.AgentCoordinator
import com.self.zai.data.SecureStore
import com.self.zai.data.SettingsStore
import com.self.zai.pi.PiEngine
import com.self.zai.shell.ShizukuBridge

class ZaiApplication : Application() {

    lateinit var secureStore: SecureStore
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var shizukuBridge: ShizukuBridge
        private set

    lateinit var piEngine: PiEngine
        private set

    lateinit var agentCoordinator: AgentCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        secureStore = SecureStore(this)
        settingsStore = SettingsStore(this)
        shizukuBridge = ShizukuBridge(this)
        piEngine = PiEngine(this, secureStore)
        agentCoordinator = AgentCoordinator(
            context = this,
            settingsStore = settingsStore,
            secureStore = secureStore,
            shizuku = shizukuBridge,
            piEngine = piEngine,
        )
        agentCoordinator.startRuntime()
    }
}

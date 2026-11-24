package com.self.zai

import android.app.Application
import com.self.zai.agent.AgentCoordinator
import com.self.zai.di.AppContainer

class ZaiApplication : Application() {

    lateinit var container: AppContainer
        private set

    val agentCoordinator: AgentCoordinator
        get() = container.agentCoordinator

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startRuntime()
    }
}

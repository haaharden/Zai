package com.self.zai

import android.app.Application
import com.self.zai.agent.AgentCoordinator
import com.self.zai.di.AppContainer
import com.self.zai.di.ZaiContainerProvider
import com.self.zai.model.AgentStopHandler

class ZaiApplication : Application(), ZaiContainerProvider, AgentStopHandler {

    override lateinit var container: AppContainer
        private set

    val agentCoordinator: AgentCoordinator
        get() = container.agentCoordinator

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startRuntime()
    }

    override fun stopActiveRun() {
        container.agentCoordinator.stopActiveRun()
    }
}

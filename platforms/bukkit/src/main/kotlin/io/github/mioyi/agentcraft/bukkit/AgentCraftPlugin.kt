package io.github.mioyi.agentcraft.bukkit

import io.github.mioyi.agentcraft.core.AgentManager
import io.github.mioyi.agentcraft.core.LlmConfig
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin

class AgentCraftPlugin : JavaPlugin() {
    private var manager: AgentManager? = null

    override fun onEnable() {
        saveDefaultConfig()
        val llmConfig = LlmConfig(
            apiKey = config.getString("llm.api-key").orEmpty(),
            baseUrl = config.getString("llm.base-url").orEmpty(),
            model = config.getString("llm.model").orEmpty(),
            maxIterations = config.getInt("agent.max-iterations", 100),
        )
        llmConfig.validate()?.let { logger.warning("LLM not configured: $it") }

        val platform = BukkitPlatform(this)
        val manager = AgentManager(platform, llmConfig)
        this.manager = manager
        server.pluginManager.registerEvents(BodyListener(this, platform, manager), this)
        platform.startTicker()
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) {
            it.registrar().register(AgentCommands(manager).build(), "Control LLM agents")
        }
        server.scheduler.runTask(this, Runnable { manager.restoreAll() })
    }

    override fun onDisable() {
        manager?.shutdown()
        manager = null
    }
}

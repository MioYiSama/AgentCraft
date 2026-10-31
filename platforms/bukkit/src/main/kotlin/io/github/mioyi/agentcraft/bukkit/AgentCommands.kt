package io.github.mioyi.agentcraft.bukkit

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.mioyi.agentcraft.core.AgentManager
import io.github.mioyi.agentcraft.core.SpawnPoint
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player

/** `/agent start|chat|list|view|stop|destroy`, operators only. */
class AgentCommands(private val manager: AgentManager) {

    fun build(): LiteralCommandNode<CommandSourceStack> = Commands.literal("agent")
        .requires { it.sender.isOp }
        .then(
            Commands.literal("start").then(
                Commands.argument("prompt", StringArgumentType.greedyString()).executes { ctx ->
                    val at = requesterLocation(ctx.source)
                    val spawn = at ?: Bukkit.getWorlds()[0].spawnLocation.toSpawnPoint()
                    reply(ctx, manager.start(StringArgumentType.getString(ctx, "prompt"), spawn, requester(ctx.source), at))
                },
            ),
        )
        .then(
            Commands.literal("chat").then(
                idArgument().then(
                    Commands.argument("prompt", StringArgumentType.greedyString()).executes { ctx ->
                        reply(
                            ctx,
                            manager.chat(
                                id(ctx),
                                StringArgumentType.getString(ctx, "prompt"),
                                requester(ctx.source),
                                requesterLocation(ctx.source),
                            ),
                        )
                    },
                ),
            ),
        )
        .then(Commands.literal("list").executes { ctx -> reply(ctx, manager.list()) })
        .then(Commands.literal("view").then(idArgument().executes { ctx -> reply(ctx, manager.view(id(ctx))) }))
        .then(Commands.literal("stop").then(idArgument().executes { ctx -> reply(ctx, manager.stop(id(ctx), requester(ctx.source))) }))
        .then(Commands.literal("destroy").then(idArgument().executes { ctx -> reply(ctx, manager.destroy(id(ctx), requester(ctx.source))) }))
        .build()

    private fun idArgument() = Commands.argument("id", IntegerArgumentType.integer(1000, 9999))
        .suggests { _, builder ->
            manager.ids().forEach { builder.suggest(it) }
            builder.buildFuture()
        }

    private fun id(ctx: CommandContext<CommandSourceStack>) = IntegerArgumentType.getInteger(ctx, "id")

    private fun requester(source: CommandSourceStack): String = source.sender.name

    private fun requesterLocation(source: CommandSourceStack): SpawnPoint? =
        ((source.executor as? Player) ?: (source.sender as? Player))?.location?.toSpawnPoint()

    private fun Location.toSpawnPoint() = SpawnPoint(world.key.asString(), x, y, z, yaw, pitch)

    private fun reply(ctx: CommandContext<CommandSourceStack>, line: String) = reply(ctx, listOf(line))

    private fun reply(ctx: CommandContext<CommandSourceStack>, lines: List<String>): Int {
        lines.forEach { ctx.source.sender.sendMessage(Component.text(it)) }
        return Command.SINGLE_SUCCESS
    }
}

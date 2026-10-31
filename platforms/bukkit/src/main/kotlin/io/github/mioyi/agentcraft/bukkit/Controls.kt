package io.github.mioyi.agentcraft.bukkit

import io.github.mioyi.agentcraft.bukkit.nms.AgentPacketListener
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.PlayerRideableJumping
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Abilities
import net.minecraft.world.entity.player.Input
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import java.lang.reflect.Method
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The keyboard of a fake player. Each tick the current action presses keys in [frame]; [apply] then does
 * what the vanilla client does with held keys: send input and sprint/flight/glide packets, scale movement
 * and steer vehicles. Main thread only.
 */
class Controls {
    /** Keys held during one tick; cleared before every tick. */
    class Frame {
        /** 1 forward, -1 backward. */
        var forward = 0

        /** 1 left, -1 right. */
        var strafe = 0
        var jump = false
        var sprint = false
        var sneak = false

        fun clear() {
            forward = 0
            strafe = 0
            jump = false
            sprint = false
            sneak = false
        }
    }

    val frame = Frame()

    /** Sneak toggled on by the agent; held across ticks until toggled off. */
    var sneakLocked = false

    private var sprinting = false
    private var wasJumping = false
    private var ridingJumpTicks = 0

    fun apply(h: ServerPlayer, listener: AgentPacketListener) {
        val f = frame
        val sneak = f.sneak || sneakLocked
        val input = Input(f.forward > 0, f.forward < 0, f.strafe > 0, f.strafe < 0, f.jump, sneak, f.sprint)
        if (input != h.lastClientInput) listener.handlePlayerInput(ServerboundPlayerInputPacket(input))

        // Like the client, ask once per change; the server or a plugin may refuse.
        val sprint = f.sprint && f.forward > 0 && !h.isShiftKeyDown && !h.isUsingItem && !h.isPassenger &&
            (h.foodData.foodLevel > 6 || h.abilities.mayfly) && !h.hasEffect(MobEffects.BLINDNESS)
        if (sprint != sprinting) {
            sprinting = sprint
            val action = if (sprint) ServerboundPlayerCommandPacket.Action.START_SPRINTING else ServerboundPlayerCommandPacket.Action.STOP_SPRINTING
            listener.handlePlayerCommand(ServerboundPlayerCommandPacket(h, action))
        }

        var forward = f.forward.toFloat()
        var strafe = f.strafe.toFloat()
        if (forward != 0f && strafe != 0f) {
            forward *= DIAGONAL
            strafe *= DIAGONAL
        }
        var scale = 1f
        if (h.isCrouching || h.isVisuallyCrawling) scale *= h.getAttributeValue(Attributes.SNEAKING_SPEED).toFloat()
        if (h.isUsingItem && !h.isPassenger) scale *= USING_ITEM_SPEED
        h.zza = forward * scale
        h.xxa = strafe * scale
        h.setJumping(f.jump)

        when (val vehicle = h.controlledVehicle) {
            is AbstractBoat -> {
                vehicle.setInput(f.strafe > 0, f.strafe < 0, f.forward > 0, f.forward < 0)
                CONTROL_BOAT.invoke(vehicle)
            }

            is PlayerRideableJumping -> rideJump(h, vehicle, listener, f.jump)
        }

        val abilities = h.abilities
        if (abilities.flying) {
            val vertical = (if (f.jump) 1 else 0) - (if (sneak) 1 else 0)
            if (vertical != 0) h.deltaMovement = h.deltaMovement.add(0.0, vertical * abilities.flyingSpeed * 3.0, 0.0)
            if (h.onGround() && !h.isSpectator) setFlying(listener, false)
        } else if (f.jump && !wasJumping && !h.onGround() && !h.isPassenger && !h.onClimbable() && !h.isInWater &&
            !h.isFallFlying
        ) {
            listener.handlePlayerCommand(ServerboundPlayerCommandPacket(h, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING))
        }
        wasJumping = f.jump
    }

    /** Holding jump charges a horse jump; releasing it sends the charge, like the client's jump bar. */
    private fun rideJump(h: ServerPlayer, vehicle: PlayerRideableJumping, listener: AgentPacketListener, jump: Boolean) {
        if (jump) {
            ridingJumpTicks++
            return
        }
        if (ridingJumpTicks > 0 && vehicle.canJump()) {
            val t = ridingJumpTicks
            val scale = if (t < 10) t * 0.1f else 0.8f + 2f / (t - 9) * 0.1f
            listener.handlePlayerCommand(
                ServerboundPlayerCommandPacket(h, ServerboundPlayerCommandPacket.Action.START_RIDING_JUMP, floor(scale * 100f).toInt()),
            )
        }
        ridingJumpTicks = 0
    }

    companion object {
        private val DIAGONAL = 1f / sqrt(2f)
        private const val USING_ITEM_SPEED = 0.2f

        /** Vanilla only steers boats on the client; the server must run that step itself for a fake player. */
        private val CONTROL_BOAT: Method = AbstractBoat::class.java.getDeclaredMethod("controlBoat").apply { isAccessible = true }

        fun setFlying(listener: AgentPacketListener, flying: Boolean) {
            listener.handlePlayerAbilities(ServerboundPlayerAbilitiesPacket(Abilities().also { it.flying = flying }))
        }
    }
}

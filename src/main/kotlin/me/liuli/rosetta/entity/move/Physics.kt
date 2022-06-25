/*
MIT License

Copyright (c) 2020 PrismarineJS

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
package me.liuli.rosetta.entity.move

import me.liuli.rosetta.bot.MinecraftBot
import me.liuli.rosetta.bot.event.FuncListener
import me.liuli.rosetta.bot.event.PreMotionEvent
import me.liuli.rosetta.util.vec.Vec3f
import me.liuli.rosetta.world.WorldIdentifier
import me.liuli.rosetta.world.block.AxisAlignedBB
import me.liuli.rosetta.world.block.Block
import me.liuli.rosetta.world.data.EnumBlockFacing
import kotlin.math.*

/**
 * some code copy from https://github.com/PrismarineJS/prismarine-physics/
 */
class Physics(val bot: MinecraftBot, val identifier: WorldIdentifier, val settings: PhysicsSetting = PhysicsSetting.INSTANCE) {

    // client player data
    var isInWater = false
        private set
    var isInLava = false
        private set
    var jumpTicks = 0
        private set

    /**
     * this method will set an event listener to bot and automatically simulate physics every tick
     */
    fun setupTickListener() {
        bot.registerListener(FuncListener(PreMotionEvent::class.java) {
            simulate()
        })
    }

    fun simulate() {
        if (!bot.isConnected || bot.world.getChunkAt(bot.player.position.x.toInt() shr 4, bot.player.position.z.toInt() shr 4) == null) {
            return
        }

        applyWaterFlow()
        isInLava = bot.world.getSurroundingBlocks(bot.player.axisAlignedBB) {
            identifier.isLava(it)
        }.isNotEmpty()

        val motion = bot.player.motion
        val position = bot.player.position

        // Reset velocity component if it falls under the threshold
        if (abs(motion.x) < settings.negligibleVelocity) motion.x = 0f
        if (abs(motion.y) < settings.negligibleVelocity) motion.y = 0f
        if (abs(motion.z) < settings.negligibleVelocity) motion.z = 0f

        if (bot.controller.jump) {
            if (jumpTicks > 0) jumpTicks--
            if (isInWater || isInLava) {
                motion.y += 0.04f
            } else if(bot.player.onGround && jumpTicks == 0) {
                val blockBelow = bot.world.getBlockAt(position.x.toInt(), position.y.toInt() - 1, position.z.toInt())
                motion.y = 0.42f * (if(blockBelow?.let { identifier.isHoneyBlock(it) } == true) settings.honeyJumpMultiplier else 1f)
                val jumpBoost = identifier.jumpBoostLevel(bot.player)
                if (jumpBoost > 0) {
                    motion.y += 0.1f * jumpBoost
                }
                if (bot.player.sprinting) {
                    val yaw = PI - bot.player.rotation.x
                    motion.x -= sin(yaw).toFloat() * 0.2f
                    motion.z += cos(yaw).toFloat() * 0.2f
                }
                jumpTicks = settings.autojumpCooldown
            }
        } else {
            jumpTicks = 0
        }

        var strafe = bot.controller.strafeValue * 0.98f
        var forward = bot.controller.forwardValue * 0.98f

        if (bot.player.sneaking) {
            strafe *= settings.sneakSpeedMultiplier
            forward *= settings.sneakSpeedMultiplier
        }

        if (isInWater || isInLava) {
            bot.player.sprinting = false
            moveInWater(strafe, forward)
        } else {
            moveInAir(strafe, forward)
        }
    }

    private fun moveInWater(strafe: Float, forward: Float) {
        val motion = bot.player.motion
        val position = bot.player.position

        val lastY = position.y
        var acceleration = settings.liquidAcceleration
        val inertia = if(isInWater) settings.waterInertia else settings.lavaInertia
        var horizontalInertia = inertia

        if (isInWater) {
            var strider = identifier.depthStriderEnchantLevel(bot.player).toFloat()
            if (!bot.player.onGround) {
                strider *= 0.5f
            }
            if (strider > 0) {
                horizontalInertia += (0.546f - horizontalInertia) * strider / 3
                acceleration *= (0.7f - acceleration) * strider / 3
                if (identifier.dolphinsGraceLevel(bot.player) > 0) horizontalInertia = 0.96f
            }
        }

        applyHeading(strafe, forward, acceleration)
        bot.player.applyMotionCollides()

        motion.y *= inertia
        motion.y -= (if(isInWater) settings.waterGravity else settings.lavaGravity) *
                (if(motion.y <= 0 && identifier.slowFallingLevel(bot.player) > 0) settings.slowFallingMultiplier else 1f)
        motion.x *= horizontalInertia
        motion.z *= horizontalInertia

        if (bot.player.isCollidedHorizontally) {
            val bb = AxisAlignedBB(position.x + motion.x, lastY + motion.y + 0.6, position.z + motion.z, bot.player.shape)
            if(!bot.world.getSurroundingBBs(bb).any { it.intersects(bb) }
                && bot.world.getSurroundingBlocks(bb){ identifier.getWaterDepth(it) != -1 }.isNotEmpty()) {
                motion.y = settings.outOfLiquidImpulse
            }
        }
    }

    private fun moveInAir(strafe: Float, forward: Float) {
        val motion = bot.player.motion
        val position = bot.player.position

        var acceleration = settings.airborneAcceleration
        var inertia = settings.airborneInertia
        val blockUnder = bot.world.getBlockAt(position.x.toInt(), position.y.toInt() - 1, position.z.toInt()) ?: Block.AIR

        if (bot.player.onGround) {
            inertia *= identifier.getSlipperiness(blockUnder)
            acceleration = (bot.player.walkSpeed * 0.1627714f / inertia.pow(3)).coerceAtLeast(0f) // acceleration should not be negative
        }

        applyHeading(strafe, forward, acceleration)

        if (identifier.isClimbable(bot.world.getBlockAt(position.x.toInt(), position.y.toInt(), position.z.toInt()) ?: Block.AIR)) {
            motion.x = motion.x.coerceIn(-settings.ladderMaxSpeed, settings.ladderMaxSpeed)
            motion.z = motion.z.coerceIn(-settings.ladderMaxSpeed, settings.ladderMaxSpeed)
            motion.y = motion.y.coerceAtMost(if (bot.player.sneaking) 0f else -settings.ladderMaxSpeed)
        }

        bot.player.applyMotionCollides()

        // refresh isOnClimbableBlock cuz position changed
        if (identifier.isClimbable(bot.world.getBlockAt(position.x.toInt(), position.y.toInt(), position.z.toInt()) ?: Block.AIR)
            && (bot.player.isCollidedHorizontally || (identifier.climbUsingJump && bot.controller.jump))) {
            motion.y = settings.ladderClimbSpeed
        }

        // apply friction and gravity
        val levitation = identifier.levitationLevel(bot.player)
        if (levitation > 0) {
            motion.y += (0.05f * levitation - motion.y) * 0.2f
        } else {
            motion.y -= settings.gravity *
                    (if(motion.y <= 0 && identifier.slowFallingLevel(bot.player) > 0) settings.slowFallingMultiplier else 1f)
        }
        motion.x *= inertia
        motion.z *= inertia
        motion.y *= settings.airDrag
    }

    private fun applyHeading(strafe: Float, forward: Float, multiplier: Float) {
        var speed = sqrt(strafe * strafe + forward * forward)
        if (speed < 0.01) return

        speed = multiplier / speed.coerceAtMost(1f)

        val strafe = strafe * speed
        val forward = forward * speed

        val yaw = PI / bot.player.rotation.x
        val sinYaw = sin(yaw).toFloat()
        val cosYaw = cos(yaw).toFloat()

        bot.player.motion.x *= strafe * cosYaw - forward * sinYaw
        bot.player.motion.z *= forward * cosYaw + strafe * sinYaw
    }

    private fun getFlow(x: Int, y: Int, z: Int): Vec3f {
        val flow = Vec3f()
        val curLevel = identifier.getWaterDepth(bot.world.getBlockAt(x, y, z) ?: return flow)
        if (curLevel == -1) {
            return flow
        }
        val directions = arrayOf(EnumBlockFacing.EAST, EnumBlockFacing.WEST, EnumBlockFacing.NORTH, EnumBlockFacing.SOUTH)
        for (direction in directions) {
            val dx = direction.offset.x
            val dz = direction.offset.z
            val adjBlock = bot.world.getBlockAt(x + dx, y, z + dz)
            val adjLevel = adjBlock?.let { identifier.getWaterDepth(it) } ?: -1
            if (adjLevel == -1) {
                if (adjBlock?.shape != null) {
                    val newLevel = bot.world.getBlockAt(x + dx, y - 1, z + dz)?.let { identifier.getWaterDepth(it) } ?: -1
                    if (newLevel >= 0) {
                        val f = newLevel - (curLevel - 8)
                        flow.x += dx * f
                        flow.z += dz * f
                    }
                }
            } else {
                val f = adjLevel - curLevel
                flow.x += dx * f
                flow.z += dz * f
            }
        }

        flow.normalize()

        return flow
    }

    private fun applyWaterFlow() {
        val waterCollides = bot.world.getSurroundingBlocks(bot.player.axisAlignedBB) {
            identifier.getWaterDepth(it) != -1
        }
        isInWater = waterCollides.isNotEmpty()
        if (!isInWater) {
            return
        }

        val acceleration = Vec3f()
        for (blockPos in waterCollides) {
            val flow = getFlow(blockPos.x, blockPos.y, blockPos.z)
            acceleration.x += flow.x
            acceleration.y += flow.y
            acceleration.z += flow.z
        }

        acceleration.normalize()
        bot.player.motion.x += acceleration.x * 0.014f
        bot.player.motion.y += acceleration.y * 0.014f
        bot.player.motion.z += acceleration.z * 0.014f
    }
}
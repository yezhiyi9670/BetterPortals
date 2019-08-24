package de.johni0702.minecraft.betterportals.common.entity

import de.johni0702.minecraft.betterportals.common.*
import de.johni0702.minecraft.betterportals.common.util.TickTimer
import net.minecraft.block.Block
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.client.renderer.culling.ICamera
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.network.datasync.DataParameter
import net.minecraft.network.datasync.DataSerializers
import net.minecraft.network.datasync.EntityDataManager
import net.minecraft.util.EnumFacing
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import kotlin.math.abs

open class OneWayPortalEntityPortalAgent(
        manager: PortalManager,
        portalConfig: PortalConfiguration
) : PortalEntityPortalAgent(manager, portalConfig) {
    lateinit var oneWayEntity: OneWayPortalEntity
        internal set

    override fun checkTeleportees() {
        if (oneWayEntity.isTailEnd) return // Cannot use portal from the tail end
        super.checkTeleportees()
    }

    override fun teleportPlayer(player: EntityPlayer, from: EnumFacing): Boolean {
        val remotePortal = entity.getRemotePortal() // FIXME for some reason this call fails after the teleport; might be fixed by now
        val success = super.teleportPlayer(player, from)
        if (success) {
            (remotePortal as OneWayPortalEntity).isTravelingInProgress = true
        }
        return success
    }

    override fun canBeSeen(camera: ICamera): Boolean = (!oneWayEntity.isTailEnd || oneWayEntity.isTravelingInProgress) && super.canBeSeen(camera)
}

/**
 * A portal which really only exists at one end.
 * At the other end, it'll seem to exist while traveling through it but cannot be used to go back and disappear when
 * moving sufficiently far away.
 */
abstract class OneWayPortalEntity(
        /**
         * Whether this portal instance is the tail/exit end of a pair of portals.
         * Not to be confused with the exit portal which spawns after the dragon fight; its tail end is in the overworld.
         * A pair of one-way portals cannot be entered from the tail end.
         */
        isTailEnd: Boolean,

        world: World,
        portal: FinitePortal,
        agent: OneWayPortalEntityPortalAgent
) : AbstractPortalEntity(world, portal, agent), PortalEntity.OneWay {
    constructor(isTailEnd: Boolean, world: World, portal: FinitePortal, portalConfig: PortalConfiguration)
            : this(isTailEnd, world, portal, OneWayPortalEntityPortalAgent(world.portalManager, portalConfig))

    companion object {
        private val IS_TAIL_END: DataParameter<Boolean> = EntityDataManager.createKey(OneWayPortalEntity::class.java, DataSerializers.BOOLEAN)
        private val ORIGINAL_TAIL_POS: DataParameter<BlockPos> = EntityDataManager.createKey(OneWayPortalEntity::class.java, DataSerializers.BLOCK_POS)
    }

    override var isTailEnd: Boolean
        get() = dataManager[IS_TAIL_END]
        set(value) { dataManager[IS_TAIL_END] = value }

    var originalTailPos: BlockPos
        get() = dataManager[ORIGINAL_TAIL_POS]
        set(value) { dataManager[ORIGINAL_TAIL_POS] = value }

    init {
        @Suppress("LeakingThis")
        agent.oneWayEntity = this
        dataManager[IS_TAIL_END] = isTailEnd
        dataManager[ORIGINAL_TAIL_POS] = if (isTailEnd) portal.localPosition else portal.remotePosition
    }

    override fun entityInit() {
        super.entityInit()
        dataManager.register(IS_TAIL_END, false)
        dataManager.register(ORIGINAL_TAIL_POS, BlockPos.ORIGIN)
    }

    override fun readEntityFromNBT(compound: NBTTagCompound) {
        super.readEntityFromNBT(compound)
        with(compound.getCompoundTag("BetterPortal")) {
            isTailEnd = getBoolean("IsTailEnd")
            originalTailPos = if (hasKey("OriginalTailPos")) {
                getCompoundTag("OriginalTailPos").getXYZ()
            } else {
                if (isTailEnd) portal.localPosition else portal.remotePosition
            }
        }
    }

    override fun writeEntityToNBT(compound: NBTTagCompound) {
        super.writeEntityToNBT(compound)
        with(compound.getCompoundTag("BetterPortal")) {
            setBoolean("IsTailEnd", isTailEnd)
            setTag("OriginalTailPos", NBTTagCompound().apply {
                setXYZ(originalTailPos)
            })
        }
    }

    override var portal: FinitePortal
        get() = super.portal
        set(value) {
            // We might have previously put fake blocks in the world and need to remove those in case the portal moves
            isTravelingInProgress = false

            super.portal = value
        }

    /**
     * When the player has just passed through the portal, the other end will still be rendered while the player
     * hasn't moved away from it.
     * This is to prevent the portal from disappearing off of half of the screen.
     */
    var isTravelingInProgress = false
        set(value) {
            if (field == value) return
            field = value
            val newState = (if (value) portalFrameBlock else Blocks.AIR).defaultState
            val oldState = (if (value) Blocks.AIR else portalFrameBlock).defaultState
            val portalBlocks = portal.localBlocks
            portalBlocks.forEach { pos ->
                EnumFacing.HORIZONTALS.forEach { facing ->
                    val neighbour = pos.offset(facing)
                    if (neighbour !in portalBlocks) {
                        if (world.getBlockState(neighbour) == oldState) {
                            world.setBlockState(neighbour, newState)
                        }
                    }
                }
            }
            if (value && travelingInProgressTimer == 0) {
                travelingInProgressTimer = 20
            }
        }
    override val isTailEndVisible: Boolean
        get() = isTravelingInProgress
    var travelingInProgressTimer = 0

    /**
     * The type of blocks which form the fake, client-side frame at the tail end of the portal.
     */
    abstract val portalFrameBlock: Block

    override fun onClientUpdate() {
        super.onClientUpdate()

        if (isTravelingInProgress && isTailEnd) {
            // Check whether the player has moved away from the tail end of the portal far enough so we can hide it
            val nearby = world.playerEntities.filterIsInstance<EntityPlayerSP>().any {
                // Traveling is still considered in progress if the distance to the portal center is less than 10 blocks
                portal.localBoundingBox.center.squareDistanceTo(it.pos) < 100.0
            }

            // or they're no longer inside of it and enough time has passed, e.g. if they're standing next to it
            val inside = world.playerEntities.filterIsInstance<EntityPlayerSP>().any { player ->
                val playerAABB = player.entityBoundingBox
                portal.localDetailedBounds.any { it.intersects(playerAABB) }
            }
            if (!inside && travelingInProgressTimer > 0) {
                travelingInProgressTimer--
            }

            if (!nearby || travelingInProgressTimer == 0) {
                isTravelingInProgress = false
            }
        }
    }

    /**
     * Check whether the tail end is obstructed by blocks when this timer reaches 0.
     */
    private val checkTailObstructionDelay = TickTimer(10 * 20, world)

    /**
     * Check whether there's a better (i.e. closer to [originalTailPos]) position for the tail portal when this timer
     * reaches 0.
     */
    private val checkTailPreferredPosDelay = TickTimer(60 * 20, world)

    override fun onUpdate() {
        super.onUpdate()

        if (!world.isRemote && isTailEnd) {
            checkTailObstructionDelay.tick("checkPortalObstruction") {
                if (isObstructed()) {
                    updatePortalPosition()
                }
            }

            checkTailPreferredPosDelay.tick("findImprovedPortalPosition") {
                val currPos = portal.localPosition
                val orgPos = originalTailPos
                if (currPos != orgPos) {
                    updatePortalPosition()
                }
            }
        }
    }

    open fun isObstructed(): Boolean {
        val growVec = portal.localFacing.directionVec.to3d() * 2.0
        if (world.getCollisionBoxes(null, portal.localBoundingBox.grow(growVec)).isEmpty()) {
            return false
        }
        return portal.localDetailedBounds.any {
            world.getCollisionBoxes(null, it.grow(growVec)).isNotEmpty()
        }
    }

    open fun updatePortalPosition() {
        val newPos = findBestUnobstructedSpace()
        if (newPos == portal.localPosition) return

        val remote = getRemotePortal() ?: return
        portal = with(portal) { FinitePortal(
                plane, blocks,
                localDimension, newPos, localRotation,
                remoteDimension, remotePosition, remoteRotation
        ) }
        remote.portal = portal.toRemote()
    }

    // This seems fairly difficult to implement well. good suggestions (and implementations) welcome.
    // Could probably do with some caching of collision boxes and better fallbacks.
    open fun findBestUnobstructedSpace(): BlockPos {
        val maxY = if (world.isCubicWorld) Int.MAX_VALUE else world.provider.actualHeight - 3
        val minY = if (world.isCubicWorld) Int.MIN_VALUE else 3
        val growVec = portal.localFacing.directionVec.to3d() * 2.0
        val orgPos = originalTailPos
        val orgBounds = portal.localDetailedBounds.map { it.offset((orgPos - portal.localPosition).to3d()) }

        // Check all positions close to the original position
        var bestDist = Int.MAX_VALUE
        var bestSpot: BlockPos? = null
        for (yOff in -10..10) {
            val y = orgPos.y + yOff
            if (y < minY || y > maxY) continue

            for (xOff in -10..10) {
                for (zOff in -10..10) {
                    val dist = abs(xOff) + abs(yOff) + abs(zOff)

                    if (dist >= bestDist) continue

                    val empty = orgBounds.all {
                        val bound = it.offset(xOff.toDouble(), yOff.toDouble(), zOff.toDouble())
                        world.getCollisionBoxes(null, bound.grow(growVec)).isEmpty()
                    }

                    if (empty) {
                        bestDist = dist
                        bestSpot = orgPos.add(xOff, yOff, zOff)
                    }
                }
            }
        }

        // Fallback to original position
        return bestSpot ?: orgPos
    }
}

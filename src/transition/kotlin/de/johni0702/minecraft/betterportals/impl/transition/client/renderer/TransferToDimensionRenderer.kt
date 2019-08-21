package de.johni0702.minecraft.betterportals.impl.transition.client.renderer

import de.johni0702.minecraft.betterportals.common.*
import de.johni0702.minecraft.view.client.render.*
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.client.shader.ShaderManager
import net.minecraftforge.client.event.RenderWorldLastEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.lwjgl.opengl.GL11
import java.time.Duration

internal class TransferToDimensionRenderer(
        private val whenDone: () -> Unit,
        private val duration: Duration = Duration.ofSeconds(10)
) {
    private val mc = Minecraft.getMinecraft()

    private val shader = ShaderManager(mc.resourceManager, "betterportals:dimension_transition")
    private val eventHandler = EventHandler()

    private val fromWorld = mc.world
    private val fromInitialPitch = mc.player.rotationPitch
    private val fromInitialYaw = mc.player.rotationYaw
    private val fromInitialPos = mc.player.pos
    private var toInitialized = false
    private var toWorld: WorldClient = fromWorld
    private var toInitialYaw = fromInitialYaw
    private var toInitialPos = fromInitialPos
    private val cameraYawOffset
        get() = toInitialYaw - fromInitialYaw
    private val cameraPosOffset
        get() = Mat4d.add(fromInitialPos.toJavaX()) * Mat4d.rotYaw(cameraYawOffset) * Mat4d.sub(toInitialPos.toJavaX())
    private var ticksPassed = 0

    private fun getProgress(partialTicks: Float) = ((ticksPassed + partialTicks) * 50 / duration.toMillis()).coerceIn(0f, 1f)

    init {
        eventHandler.registered = true
    }

    private fun addOldViewToTree(event: PopulateTreeEvent) {
        val root = event.root
        if (root.children.any { it.get<TransferToDimensionRenderer>() == this }) {
            return
        }
        val prevRoot = root.manager.previous
        val prevChild = prevRoot?.children?.find { it.get<TransferToDimensionRenderer>() == this }

        if (!toInitialized) {
            with(mc.player) {
                // Copy current pitch onto new camera to prevent sudden camera jumps when switching views
                rotationPitch = fromInitialPitch
                prevRotationPitch = fromInitialPitch

                // Initialize to-values
                toWorld = mc.world
                toInitialYaw = rotationYaw
                toInitialPos = pos
                toInitialized = true
            }
        }

        // TODO this isn't quite right once we use a portal in the new view while the transition is still active
        val camera = root.camera.transformed(cameraPosOffset)
        root.addChild(fromWorld, camera, prevChild).set(this)
        event.changed = true
    }

    private fun renderTransition(rootPass: RenderPass, partialTicks: Float) {
        val childPass = rootPass.children.find { it.get<TransferToDimensionRenderer>() == this } ?: return
        val framebuffer = childPass.framebuffer ?: return

        shader.addSamplerTexture("sampler", framebuffer)
        shader.getShaderUniformOrDefault("screenSize")
                .set(framebuffer.framebufferWidth.toFloat(), framebuffer.framebufferHeight.toFloat())
        shader.getShaderUniformOrDefault("progress").set(getProgress(partialTicks))
        shader.useShader()

        val tessellator = Tessellator.getInstance()
        tessellator.buffer.apply {
            begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION)
            pos( 1.0,  1.0,  1.0).endVertex()
            pos(-1.0,  1.0,  1.0).endVertex()
            pos(-1.0, -1.0,  1.0).endVertex()
            pos( 1.0, -1.0,  1.0).endVertex()
        }
        tessellator.draw()

        shader.endShader()
    }

    private inner class EventHandler {
        var registered by MinecraftForge.EVENT_BUS

        @SubscribeEvent
        fun preClientTick(event: TickEvent.ClientTickEvent) {
            if (event.phase != TickEvent.Phase.START) return
            ticksPassed++
        }

        @SubscribeEvent
        fun onPopulateTreeEvent(event: PopulateTreeEvent) {
            addOldViewToTree(event)
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        fun onRenderWorldLast(event: RenderWorldLastEvent) {
            val manager = Minecraft.getMinecraft().renderPassManager
            val rootPass = manager.root ?: return
            if (manager.current == rootPass) {
                renderTransition(rootPass, event.partialTicks)
            }

            if (getProgress(event.partialTicks) >= 1) {
                registered = false
                shader.deleteShader()
                whenDone()
            }
        }
    }
}
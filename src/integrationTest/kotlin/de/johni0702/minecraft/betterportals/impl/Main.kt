package de.johni0702.minecraft.betterportals.impl

import de.johni0702.minecraft.view.impl.net.Transaction
import io.kotlintest.*
import io.kotlintest.extensions.TestListener
import net.minecraft.client.Minecraft
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter
import java.time.Duration

//#if FABRIC>=1
//#else
import net.minecraftforge.fml.client.registry.RenderingRegistry
//#endif

//#if MC>=11400
//$$ import org.lwjgl.glfw.GLFW
//#else
import org.lwjgl.opengl.Display
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.registry.EntityRegistry
//#endif

lateinit var mc: Minecraft

fun preInitTests(mcIn: Minecraft) {
    mc = mcIn
}

fun initTests() {
    //#if MC<11400
    EntityRegistry.registerModEntity(
            ResourceLocation(MOD_ID, "test_entity"),
            TestEntity::class.java,
            "test_entity",
            256,
            MOD_ID,
            256,
            1,
            false
    )
    //#endif
}

fun runTests(): Boolean {
    mc.gameSettings.showDebugInfo = true
    mc.gameSettings.pauseOnLostFocus = false
    mc.gameSettings.renderDistanceChunks = 8 // some tests depend on this specific render distance
    Transaction.disableTransactions = true

    //#if FABRIC>=1
    //$$ // FIXME
    //#else
    mc.renderManager.entityRenderMap[TestEntity::class.java] = RenderTestEntity(mc.renderManager)
    RenderingRegistry.registerEntityRenderingHandler(TestEntity::class.java) { RenderTestEntity(it) }
    //#endif

    releaseMainThread()
    System.setProperty("kotlintest.project.config", ProjectConfig::class.java.name)

    val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClass(EntityCullingTests::class.java))
            .selectors(selectClass(EntityTraversalRenderTests::class.java))
            .selectors(selectClass(SinglePortalTraversalTests::class.java))
            .selectors(selectClass(SinglePortalWithSecondNearbyTraversalTest::class.java))
            .selectors(selectClass(DoublePortalTraversalTests::class.java))
            // Requires Mekanism
            //#if MC<11400
            .selectors(selectClass(NearTeleporterTraversalTests::class.java))
            //#endif
            .build()
    val launcher = LauncherFactory.create()
    val testPlan = launcher.discover(request)
    val summaryListener = SummaryGeneratingListener()
    launcher.registerTestExecutionListeners(summaryListener)
    launcher.execute(testPlan)

    val summary = summaryListener.summary
    summary.printTo(PrintWriter(System.err))
    summary.printFailuresTo(PrintWriter(System.err))
    return summary.totalFailureCount == 0L
}

interface IHasMainThread {
    fun setMainThread()
}

object ProjectConfig : AbstractProjectConfig() {
    override val timeout: Duration?
        get() = 30.seconds
}

fun acquireMainThread() {
    //#if MC>=11400
    //$$ GLFW.glfwMakeContextCurrent(mc.mainWindow.handle)
    //#else
    Display.getDrawable().makeCurrent()
    //#endif
    (mc as IHasMainThread).setMainThread()
    (mc.integratedServer as IHasMainThread?)?.setMainThread()
}

fun releaseMainThread() {
    //#if MC>=11400
    //$$ GLFW.glfwMakeContextCurrent(0)
    //#else
    Display.getDrawable().releaseContext()
    //#endif
}

private var inAsMainThread = false
fun asMainThread(block: () -> Unit) {
    if (inAsMainThread) {
        block()
    } else {
        inAsMainThread = true
        acquireMainThread()
        try {
            block()
        } finally {
            releaseMainThread()
            inAsMainThread = false
        }
    }
}

open class SetClientThreadListener : TestListener {
    override fun beforeTest(testCase: TestCase) {
        println("Begin ${testCase.description.fullName()}")
        acquireMainThread()
        super.beforeTest(testCase)
    }

    override fun afterTest(testCase: TestCase, result: TestResult) {
        when(result.status) {
            TestStatus.Error, TestStatus.Failure -> {
                println("Failed ${testCase.description.fullName()}, taking screenshot..")
                try {
                    // Previous render result (in case render tests fail)
                    screenshot(testCase.description.fullName() + ".previous.png")

                    // Initial screenshot
                    renderToScreenshot(testCase.description.fullName() + ".first.png")

                    // Extra render passes to get lazily computed things updated
                    repeat(5) { render() }
                    renderToScreenshot(testCase.description.fullName() + ".last.png")

                    // Debug view
                    BPConfig.debugView = true
                    renderToScreenshot(testCase.description.fullName() + ".debug.png")
                    BPConfig.debugView = false
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
            else -> {}
        }

        super.afterTest(testCase, result)
        releaseMainThread()
        println("After ${testCase.description.fullName()}")
    }
}

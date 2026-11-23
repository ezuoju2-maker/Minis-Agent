package com.openminis.app.ctx

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoboScenarioTest {

    private val EXPECTED_DIFFERENT: Map<String, String> = mapOf(
        "C24" to "Robolectric NATIVE graphics + 生产 codec 下超大 base64 图片重建耗尽 JVM 堆（Java heap space）",
    )

    @Test fun `every scenario under Robolectric native graphics with the production codec`() {
        val outcome = linkedMapOf<String, String>()
        for (sc in CtxScenarios.all) {
            outcome[sc.id] = try {
                CtxSession("robo-" + sc.id.lowercase(), sc.window).use { s ->
                    sc.build(s)
                    val ctx = s.request()
                    val live = CtxPhase("live", ctx, CtxProvider.entries.associateWith { CtxSession.wire(it, ctx.messages) }, s.history.toList())
                    val run0 = CtxRun(sc, s, live, null)
                    sc.beforeRestart(s, run0)
                    val restarted = sc.restart?.let {
                        it(s)
                        val c2 = s.request()
                        CtxPhase("restart", c2, CtxProvider.entries.associateWith { p -> CtxSession.wire(p, c2.messages) }, s.history.toList())
                    }
                    val run = CtxRun(sc, s, live, restarted).also { it.facts.putAll(run0.facts) }
                    sc.current(run)
                    "agrees"
                }
            } catch (e: Throwable) {
                "differs: " + (e.message ?: e.javaClass.simpleName).lines().first().take(200)
            }
            println("[ROBO-SCENARIO] ${sc.id} ${outcome[sc.id]}")
        }
        val differing = outcome.filterValues { it != "agrees" }.keys
        val unexpected = differing - EXPECTED_DIFFERENT.keys
        assertEquals(
            "scenarios differing from JVM characterization that are NOT known/accepted under Robolectric",
            emptySet<String>(),
            unexpected,
        )
    }

    @Test fun `C09 a transparent image re-encoded for size comes out black where it was transparent`() {
        val sc = CtxScenarios.all.single { it.id == "C09" }
        CtxSession("robo-c09bg", sc.window).use { s ->
            sc.build(s)
            val imgs = com.openminis.app.imagerepro.ImageRealFixtures.wireImages(CtxSession.wire(CtxProvider.ANTHROPIC, s.request().messages))
            val jpeg = imgs[2].bytes
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            val transparentSide = bmp.getPixel(bmp.width / 8, bmp.height / 2)
            val opaqueSideAlpha = android.graphics.Color.alpha(bmp.getPixel(bmp.width * 7 / 8, bmp.height / 2))
            println("[ROBO-C09] ${bmp.width}x${bmp.height} transparent side px=%08x opaque side alpha=$opaqueSideAlpha".format(transparentSide))
            assertEquals("image/jpeg", imgs[2].mime)
            assertEquals("the transparent half is black", 0xFF000000.toInt(), transparentSide)
            val png = imgs[0].bytes
            val p = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
            assertEquals("the PNG keeps transparency", 0, android.graphics.Color.alpha(p.getPixel(p.width / 8, p.height / 2)))
        }
    }
}

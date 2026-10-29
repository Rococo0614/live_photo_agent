package com.vivo.lpa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileToolRegistryTest {
    private fun ids(context: ToolRuntimeContext): Set<String> =
        MobileToolRegistry.loopCatalog(context).available.map { it.id }.toSet()

    @Test
    fun imageStyleHidesVideoAndLivePhotoImplementations() {
        val visible = ids(ToolRuntimeContext(
            family = ToolFamily.STYLE,
            mediaKind = RuntimeMediaKind.IMAGE,
            assetCount = 3,
            imageCount = 3
        ))

        assertTrue("color_tone" in visible)
        assertTrue("denoise" in visible)
        assertFalse("video_filter" in visible)
        assertFalse("live_photo_filter_effect" in visible)
        assertTrue(visible.size <= MobileToolRegistry.MAX_VISIBLE_TOOLS)
    }

    @Test
    fun verticalStackRequiresExactlyThreeVideoTracks() {
        val two = ids(ToolRuntimeContext(
            family = ToolFamily.COMPOSE,
            mediaKind = RuntimeMediaKind.VIDEO,
            assetCount = 2,
            videoCount = 2
        ))
        val three = ids(ToolRuntimeContext(
            family = ToolFamily.COMPOSE,
            mediaKind = RuntimeMediaKind.VIDEO,
            assetCount = 3,
            videoCount = 3
        ))
        val four = ids(ToolRuntimeContext(
            family = ToolFamily.COMPOSE,
            mediaKind = RuntimeMediaKind.VIDEO,
            assetCount = 4,
            videoCount = 4
        ))

        assertFalse("video_vertical_stack" in two)
        assertTrue("video_vertical_stack" in three)
        assertFalse("video_vertical_stack" in four)
    }

    @Test
    fun swapRequiresTwoSelectionsAndExistingLayout() {
        val noLayout = ids(ToolRuntimeContext(
            family = ToolFamily.LAYOUT,
            mediaKind = RuntimeMediaKind.LIVE_PHOTO,
            assetCount = 3,
            imageCount = 3,
            videoCount = 3,
            livePhotoCount = 3,
            selectedCount = 2,
            hasLayout = false
        ))
        val ready = ids(ToolRuntimeContext(
            family = ToolFamily.LAYOUT,
            mediaKind = RuntimeMediaKind.LIVE_PHOTO,
            assetCount = 3,
            imageCount = 3,
            videoCount = 3,
            livePhotoCount = 3,
            selectedCount = 2,
            hasLayout = true
        ))

        assertFalse("layout_swap" in noLayout)
        assertTrue("layout_swap" in ready)
    }

    @Test
    fun publishRequiresValidatedArtifactAndUsesOneLoopTool() {
        val missing = ids(ToolRuntimeContext(
            family = ToolFamily.PUBLISH,
            artifactKind = AgentArtifactKind.VIDEO,
            artifactValidated = false
        ))
        val ready = ids(ToolRuntimeContext(
            family = ToolFamily.PUBLISH,
            mediaKind = RuntimeMediaKind.VIDEO,
            artifactKind = AgentArtifactKind.VIDEO,
            artifactValidated = true
        ))

        assertTrue(missing.isEmpty())
        assertTrue(ready == setOf("publish_result"))
    }

    @Test
    fun singleVideoArtifactDoesNotExposeMultiInputStyleTools() {
        val visible = ids(ToolRuntimeContext(
            family = ToolFamily.STYLE,
            mediaKind = RuntimeMediaKind.VIDEO,
            assetCount = 1,
            videoCount = 1,
            artifactKind = AgentArtifactKind.VIDEO,
            artifactValidated = true
        ))

        assertTrue("video_filter" in visible)
        assertFalse("video_dynamic_sticker" in visible)
        assertFalse("live_photo_filter_effect" in visible)
    }

    @Test
    fun everyRepresentativeFamilyStaysWithinVisibleLimit() {
        val contexts = ToolFamily.entries.flatMap { family ->
            listOf(
                ToolRuntimeContext(family = family),
                ToolRuntimeContext(
                    family = family,
                    mediaKind = RuntimeMediaKind.IMAGE,
                    assetCount = 3,
                    imageCount = 3,
                    selectedCount = 1
                ),
                ToolRuntimeContext(
                    family = family,
                    mediaKind = RuntimeMediaKind.LIVE_PHOTO,
                    assetCount = 3,
                    imageCount = 3,
                    videoCount = 3,
                    livePhotoCount = 3,
                    selectedCount = 2,
                    hasLayout = true,
                    artifactKind = AgentArtifactKind.VIDEO,
                    artifactValidated = true
                )
            )
        }

        contexts.forEach { context ->
            assertTrue(
                "${context.family} exposed too many tools",
                MobileToolRegistry.loopCatalog(context).available.size <=
                    MobileToolRegistry.MAX_VISIBLE_TOOLS
            )
        }
    }

    @Test
    fun everyRegisteredToolHasTypedCompilerContract() {
        MobileToolRegistry.allSpecs().forEach { spec ->
            assertTrue("${spec.id} contract version", spec.contract.version > 0)
            assertTrue(
                "${spec.id} must declare an output",
                spec.contract.outputPorts.isNotEmpty()
            )
            assertTrue(
                "${spec.id} output ports must be unique",
                spec.contract.outputPorts.map { it.name }.distinct().size ==
                    spec.contract.outputPorts.size
            )
        }
    }
}

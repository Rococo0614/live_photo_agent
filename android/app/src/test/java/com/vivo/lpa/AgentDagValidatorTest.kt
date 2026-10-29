package com.vivo.lpa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDagValidatorTest {
    private fun port(name: String, vararg kinds: AgentDataKind) =
        AgentPortSpec(name, kinds.toSet())

    private fun node(
        id: String,
        phase: AgentActionPhase = AgentActionPhase.TRANSFORM,
        inputs: List<AgentPortSpec> = emptyList(),
        outputs: List<AgentPortSpec> = emptyList()
    ) = AgentDagNode(
        nodeId = id,
        operationId = "op-$id",
        actionId = "action-$id",
        family = ToolFamily.ANALYZE,
        phase = phase,
        contractVersion = 1,
        inputPorts = inputs,
        outputPorts = outputs
    )

    private fun graph(
        nodes: List<AgentDagNode>,
        edges: List<AgentDagEdge> = emptyList(),
        bindings: List<AgentDagExternalBinding> = emptyList()
    ) = AgentPlanGraph(
        graphId = "graph-test",
        graphVersion = 1,
        goalId = "goal-test",
        stateFingerprint = "state-test",
        toolSpecVersion = 1,
        nodes = nodes.associateBy { it.nodeId },
        edges = edges.associateBy { it.edgeId },
        externalBindings = bindings
    )

    @Test
    fun branchingAndFanInGraphIsValid() {
        val decode = node("decode", outputs = listOf(port("video", AgentDataKind.VIDEO)))
        val segment = node("segment", outputs = listOf(port("mask", AgentDataKind.MASK_TRACK)))
        val inpaint = node(
            "inpaint",
            inputs = listOf(
                port("video", AgentDataKind.VIDEO),
                port("mask", AgentDataKind.MASK_TRACK)
            ),
            outputs = listOf(port("video", AgentDataKind.VIDEO))
        )
        val edges = listOf(
            AgentDagEdge("video-edge", AgentDagEdgeKind.DATA, "decode", "inpaint", "video", "video"),
            AgentDagEdge("mask-edge", AgentDagEdgeKind.DATA, "segment", "inpaint", "mask", "mask")
        )

        val validation = AgentDagValidator.validate(graph(listOf(decode, segment, inpaint), edges))

        assertTrue(validation.diagnostics.toString(), validation.valid)
        assertTrue(validation.topologicalOrder.indexOf("decode") < validation.topologicalOrder.indexOf("inpaint"))
        assertTrue(validation.topologicalOrder.indexOf("segment") < validation.topologicalOrder.indexOf("inpaint"))
    }

    @Test
    fun cycleIsRejected() {
        val first = node("first")
        val second = node("second")
        val edges = listOf(
            AgentDagEdge("a", AgentDagEdgeKind.CONTROL, "first", "second"),
            AgentDagEdge("b", AgentDagEdgeKind.CONTROL, "second", "first")
        )

        val validation = AgentDagValidator.validate(graph(listOf(first, second), edges))

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "cycle" })
    }

    @Test
    fun incompatibleDataTypesAreRejected() {
        val image = node("image", outputs = listOf(port("out", AgentDataKind.IMAGE)))
        val tracker = node(
            "tracker",
            inputs = listOf(port("mask", AgentDataKind.MASK_TRACK))
        )
        val edge = AgentDagEdge(
            "wrong", AgentDagEdgeKind.DATA, "image", "tracker", "out", "mask"
        )

        val validation = AgentDagValidator.validate(graph(listOf(image, tracker), listOf(edge)))

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "incompatible_data_edge" })
    }

    @Test
    fun missingRequiredInputIsRejected() {
        val transform = node(
            "transform",
            inputs = listOf(port("media", AgentDataKind.VIDEO))
        )

        val validation = AgentDagValidator.validate(graph(listOf(transform)))

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "missing_required_input" })
    }

    @Test
    fun externalBindingSatisfiesRequiredInput() {
        val transform = node(
            "transform",
            inputs = listOf(port("media", AgentDataKind.VIDEO))
        )
        val binding = AgentDagExternalBinding(
            "transform",
            "media",
            AgentDagExternalValue.Artifact("video-1", AgentDataKind.VIDEO)
        )

        val validation = AgentDagValidator.validate(graph(listOf(transform), bindings = listOf(binding)))

        assertTrue(validation.diagnostics.toString(), validation.valid)
        assertEquals(listOf("transform"), validation.topologicalOrder)
    }

    @Test
    fun staleAssetScopeIsRejected() {
        val transform = node(
            "transform",
            inputs = listOf(port("media", AgentDataKind.ASSET_SET))
        )
        val binding = AgentDagExternalBinding(
            "transform",
            "media",
            AgentDagExternalValue.AssetSet(listOf("asset-1"), "old-state")
        )

        val validation = AgentDagValidator.validate(graph(listOf(transform), bindings = listOf(binding)))

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "stale_scope" })
    }

    @Test
    fun parameterSchemaRejectsMissingAndOutOfRangeValues() {
        val speed = node("speed").copy(
            parameterSpecs = mapOf(
                "speed" to AgentParameterSpec(
                    "speed", AgentParameterType.DECIMAL, required = true,
                    minimum = 0.1, maximum = 8.0
                )
            ),
            allowUnknownParameters = false,
            parametersJson = JSONObject().put("speed", 12.0).put("invented", true).toString()
        )

        val validation = AgentDagValidator.validate(graph(listOf(speed)))

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "parameter_maximum" })
        assertTrue(validation.diagnostics.any { it.code == "unknown_parameter" })

        val missing = speed.copy(parametersJson = "{}")
        val missingValidation = AgentDagValidator.validate(graph(listOf(missing)))
        assertTrue(missingValidation.diagnostics.any { it.code == "missing_parameter" })
    }
}

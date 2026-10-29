package com.vivo.lpa

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalIrCodecTest {
    private fun emptyState(query: String) = AgentRuntimeState(null, "turn-goal-codec", query)

    @Test
    fun explicitUserFactsOverrideModelCountAndMedia() {
        val query = "找一段猫的视频倒放后导出"
        val arguments = JSONObject().put("operations", JSONArray()
            .put(JSONObject()
                .put("op", "retrieve")
                .put("instruction", "猫")
                .put("count", 3)
                .put("media", "image")
                .put("scope", "library"))
            .put(JSONObject()
                .put("op", "temporal_edit")
                .put("instruction", "倒放")
                .put("scope", "previous"))
            .put(JSONObject()
                .put("op", "publish")
                .put("scope", "previous")))

        val result = AgentGoalIrCodec.parseArguments(arguments, query, emptyState(query))

        assertTrue(result.diagnostics.toString(), result.valid)
        val retrieve = result.goal!!.operations.first()
        assertEquals(1, retrieve.requestedCount)
        assertEquals(RuntimeMediaKind.VIDEO, retrieve.requiredMedia)
        assertTrue(result.goal.operations[1].scope is AgentGoalScope.PreviousOutput)
    }

    @Test
    fun contentTargetAndConstraintsRemainSemantic() {
        val query = "移除右后方的人，但保留声音"
        val arguments = JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "remove_content")
            .put("target", JSONObject()
                .put("description", "右后方的人")
                .put("source_text", "右后方的人"))
            .put("constraints", JSONArray().put(JSONObject()
                .put("kind", "preserve")
                .put("key", "audio")
                .put("value", "original")
                .put("source_text", "保留声音")))))

        val result = AgentGoalIrCodec.parseArguments(arguments, query, emptyState(query))

        assertTrue(result.diagnostics.toString(), result.valid)
        val operation = result.goal!!.operations.single()
        assertEquals(AgentGoalOperationKind.REMOVE_CONTENT, operation.kind)
        assertEquals(
            AgentGoalTarget.Semantic("右后方的人", "右后方的人"),
            operation.target
        )
        assertEquals(AgentValueProvenance.USER_EXPLICIT, operation.constraints.single().provenance)
    }

    @Test
    fun missingContentTargetBecomesUnresolvedSlot() {
        val query = "移除一个东西"
        val arguments = JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "remove_content")))

        val result = AgentGoalIrCodec.parseArguments(arguments, query, emptyState(query))

        assertTrue(result.diagnostics.toString(), result.valid)
        assertEquals("target", result.goal!!.unresolvedSlots.single().name)
    }

    @Test
    fun physicalToolAndRuntimeReferencesAreRejected() {
        val query = "倒放视频"
        val arguments = JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "temporal_edit")
            .put("tool", "video_reverse")
            .put("parameters", JSONObject().put("input", "/data/local/tmp/input.mp4"))))

        val result = AgentGoalIrCodec.parseArguments(arguments, query, emptyState(query))

        assertFalse(result.valid)
        assertTrue(result.diagnostics.any { it.code == "physical_field" })
        assertTrue(result.diagnostics.any { it.code == "runtime_reference" })
    }

    @Test
    fun parsesFunctionCallWithStringArguments() {
        val query = "分析当前素材"
        val call = JSONObject()
            .put("name", AgentGoalIrCodec.FUNCTION_NAME)
            .put("arguments", JSONObject().put(
                "operations",
                JSONArray().put(JSONObject().put("op", "analyze"))
            ).toString())

        val result = AgentGoalIrCodec.parseFunctionCall(call, query, emptyState(query))

        assertTrue(result.diagnostics.toString(), result.valid)
        assertNotNull(result.goal)
        assertEquals(AgentGoalOperationKind.ANALYZE, result.goal!!.operations.single().kind)
    }
}

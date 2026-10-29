package com.vivo.lpa

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalCompilerTest {
    private fun parse(query: String, arguments: JSONObject, state: AgentRuntimeState): AgentGoalIr =
        requireNotNull(AgentGoalIrCodec.parseArguments(arguments, query, state).goal)

    @Test
    fun retrieveGoalRequestsBindingFromStateFilteredCatalog() {
        val query = "找一段猫的视频"
        val state = AgentRuntimeState(null, "turn-binding", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "retrieve")
            .put("instruction", "猫"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedBinding)
        val request = (result as AgentCompileResult.NeedBinding).requests.single()
        assertEquals(ToolFamily.RETRIEVE, request.family)
        assertEquals(listOf("search_assets"), request.candidateActionIds)
        assertTrue(request.candidateActionIds.size <= MobileToolRegistry.MAX_VISIBLE_TOOLS)
    }

    @Test
    fun missingTargetSuspendsForUser() {
        val query = "移除一个东西"
        val state = AgentRuntimeState(null, "turn-question", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "remove_content"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedUser)
        val question = (result as AgentCompileResult.NeedUser).question
        assertEquals("target", question.missingSlot)
        assertTrue(question.continuationToken.isNotBlank())
    }

    @Test
    fun semanticContentTargetRequestsGroundingObservation() {
        val query = "移除右后方的人"
        val state = AgentRuntimeState(null, "turn-observe", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "remove_content")
            .put("target", "右后方的人"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedObservation)
        val requirement = (result as AgentCompileResult.NeedObservation).requirements.single()
        assertEquals(AgentDataKind.OBJECT_TRACK, requirement.kind)
    }

    @Test
    fun retrievalBindsBeforeLaterContentGrounding() {
        val query = "找一段街景视频并移除右后方的人"
        val state = AgentRuntimeState(null, "turn-retrieve-before-grounding", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray()
            .put(JSONObject().put("op", "retrieve").put("instruction", "街景"))
            .put(JSONObject().put("op", "remove_content").put("target", "右后方的人"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedBinding)
        assertEquals(
            ToolFamily.RETRIEVE,
            (result as AgentCompileResult.NeedBinding).requests.single().family
        )
    }

    @Test
    fun publishWithoutArtifactRequestsObservationRatherThanInventingInput() {
        val query = "发布结果"
        val state = AgentRuntimeState(null, "turn-publish", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "publish"))), state)

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.NeedObservation)
        assertEquals(
            AgentDataKind.MEDIA_ARTIFACT,
            (result as AgentCompileResult.NeedObservation).requirements.single().kind
        )
    }

    @Test
    fun staleGoalIsRejectedBeforeBinding() {
        val query = "分析当前素材"
        val state = AgentRuntimeState(null, "turn-stale", query)
        val goal = parse(query, JSONObject().put("operations", JSONArray().put(JSONObject()
            .put("op", "analyze"))), state).copy(stateFingerprint = "stale")

        val result = AgentGoalCompiler.preflight(goal, state)

        assertTrue(result is AgentCompileResult.Unsupported)
        assertTrue((result as AgentCompileResult.Unsupported).reason.contains("stale state"))
    }
}

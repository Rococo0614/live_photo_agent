package com.vivo.lpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalJsonEnvelopeTest {
    @Test
    fun validEnvelopePassesWithoutRepair() {
        val parsed = AgentGoalJsonEnvelopeParser.parse(
            """{"name":"plan_live_edit","arguments":{"operations":[]}}"""
        )!!

        assertFalse(parsed.repaired)
        assertEquals("plan_live_edit", parsed.value.getString("name"))
    }

    @Test
    fun repairsOnlyMissingOperationsArrayTerminator() {
        val malformed = """
            {"name":"plan_live_edit","arguments":{"operations":[{"op":"change_content","scope":"current","target":{"description":"orange cat","source_text":"橘猫"},"parameters":{"effect_kind":"color_tint","color_hex":"#008000","intensity":1.0},"constraints":[]},"unresolved_slots":[]}}
        """.trimIndent()

        val parsed = AgentGoalJsonEnvelopeParser.parse(malformed)!!

        assertTrue(parsed.repaired)
        assertEquals(1,
            parsed.value.getJSONObject("arguments").getJSONArray("operations").length())
        assertEquals("inserted missing operations array terminator", parsed.repairDescription)
    }

    @Test
    fun movesTerminatorWrittenAfterArgumentsObject() {
        val malformed = """
            {"name":"plan_live_edit","arguments":{"operations":[{"op":"change_content","scope":"current","target":{"description":"cat","source_text":"猫"},"parameters":{"effect_kind":"color_tint","color_hex":"#008000","intensity":1.0},"constraints":[]},"unresolved_slots":[]}]}
        """.trimIndent()

        val parsed = AgentGoalJsonEnvelopeParser.parse(malformed)!!

        assertTrue(parsed.repaired)
        assertEquals(1,
            parsed.value.getJSONObject("arguments").getJSONArray("operations").length())
        assertEquals("moved misplaced operations array terminator", parsed.repairDescription)
    }

    @Test
    fun normalizesOnlyWhitelistedNumericParameterStrings() {
        val parsed = AgentGoalJsonEnvelopeParser.parse(
            """{"name":"plan_live_edit","arguments":{"operations":[{"op":"change_content","parameters":{"intensity":"1.0","color_hex":"123"}}]}}"""
        )!!
        val parameters = parsed.value.getJSONObject("arguments")
            .getJSONArray("operations").getJSONObject(0).getJSONObject("parameters")

        assertTrue(parsed.repaired)
        assertEquals(1.0, parameters.getDouble("intensity"), 0.0)
        assertEquals("123", parameters.getString("color_hex"))
    }

    @Test
    fun repairsMissingArrayBeforeBalancedTrailingBraces() {
        val malformed = """
            {"name":"plan_live_edit","arguments":{"operations":[{"op":"change_content","scope":"current","target":{"description":"orange cat","source_text":"橘猫"},"parameters":{"effect_kind":"color_tint","color_hex":"#008000","intensity":1.0},"constraints":[]},"unresolved_slots":[]}}}
        """.trimIndent()

        val parsed = AgentGoalJsonEnvelopeParser.parse(malformed)!!

        assertTrue(parsed.repaired)
        assertEquals(1,
            parsed.value.getJSONObject("arguments").getJSONArray("operations").length())
        assertEquals(
            "inserted missing operations array terminator",
            parsed.repairDescription
        )
    }

    @Test
    fun removesOneBraceWrittenBeforeOperationsTerminator() {
        val malformed = """
            {"name":"plan_live_edit","arguments":{"operations":[{"op":"change_content","scope":"current","target":{"description":"orange cat","source_text":"橘猫"},"parameters":{"effect_kind":"color_tint","color_hex":"#008000","intensity":1.0},"constraints":[]}}]}}
        """.trimIndent()

        val parsed = AgentGoalJsonEnvelopeParser.parse(malformed)!!

        assertTrue(parsed.repaired)
        assertEquals(1,
            parsed.value.getJSONObject("arguments").getJSONArray("operations").length())
        assertEquals(
            "removed extra brace before operations array terminator",
            parsed.repairDescription
        )
    }

    @Test
    fun removesOnlyByteEquivalentDuplicateOperations() {
        val operation = """{"op":"change_content","scope":"current","target":{"description":"orange cat","source_text":"橘猫"},"parameters":{"effect_kind":"color_tint","color_hex":"#008000","intensity":1.0},"constraints":[]}"""
        val parsed = AgentGoalJsonEnvelopeParser.parse(
            """{"name":"plan_live_edit","arguments":{"operations":[$operation,$operation],"unresolved_slots":[]}}"""
        )!!

        assertTrue(parsed.repaired)
        assertEquals(1,
            parsed.value.getJSONObject("arguments").getJSONArray("operations").length())
        assertEquals("removed exact duplicate operations", parsed.repairDescription)
    }

    @Test
    fun preservesNonEquivalentMultipleOperationsForStrictValidator() {
        val parsed = AgentGoalJsonEnvelopeParser.parse(
            """{"name":"plan_live_edit","arguments":{"operations":[{"op":"change_content","parameters":{"effect_kind":"blur","blur_radius_px":4}},{"op":"change_content","parameters":{"effect_kind":"blur","blur_radius_px":8}}]}}"""
        )!!

        assertFalse(parsed.repaired)
        assertEquals(2,
            parsed.value.getJSONObject("arguments").getJSONArray("operations").length())
    }

    @Test
    fun refusesUnbalancedTextButLeavesSemanticValidationToCodec() {
        assertNull(AgentGoalJsonEnvelopeParser.parse("{not json"))
        val semanticError = AgentGoalJsonEnvelopeParser.parse(
            """{"name":"plan_live_edit","arguments":{"operations":{"op":"change_content"}}}"""
        )!!
        assertFalse(semanticError.repaired)
    }
}

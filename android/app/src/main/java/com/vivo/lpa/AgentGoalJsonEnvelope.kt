package com.vivo.lpa

import org.json.JSONArray
import org.json.JSONObject

data class AgentGoalJsonEnvelope(
    val value: JSONObject,
    val repaired: Boolean,
    val repairDescription: String? = null
)

/** Narrow syntax/structure repair before the strict Goal IR codec performs semantic validation. */
object AgentGoalJsonEnvelopeParser {
    fun parse(modelText: String): AgentGoalJsonEnvelope? {
        val start = modelText.indexOf('{')
        val end = modelText.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val raw = modelText.substring(start, end + 1)
        runCatching { JSONObject(raw) }.getOrNull()?.let {
            return normalizeParameterTypes(it, repaired = false, description = null)
        }

        // Observed duplicate-row truncation: the first operation gets one extra closing brace,
        // while the outer envelope loses one, yielding a misleadingly balanced total:
        //   "constraints":[]}}, {"op":...],"unresolved_slots":[]}
        // Move that single brace back to the end; values and operation bytes are untouched.
        if (raw.count { it == '{' } == raw.count { it == '}' } &&
            raw.count { it == '[' } == raw.count { it == ']' } &&
            raw.contains(Regex("""["]operations["]\s*:\s*\["""))
        ) {
            val extraBetweenRows = Regex(
                """[}](\s*)[}](\s*),(\s*[{]\s*["]op["]\s*:)"""
            )
            val matches = extraBetweenRows.findAll(raw).toList()
            if (matches.size == 1) {
                val match = matches.single()
                val moved = raw.replaceRange(
                    match.range,
                    "}${match.groupValues[1]}${match.groupValues[2]},${match.groupValues[3]}"
                ) + "}"
                runCatching { JSONObject(moved) }.getOrNull()?.let {
                    return normalizeParameterTypes(
                        it,
                        repaired = true,
                        description = "moved one extra inter-operation brace to outer envelope"
                    )
                }
            }
        }

        // Combined observed variant: one extra brace closes the first operation and the array
        // terminator is omitted before a fixed top-level arguments field.
        if (raw.count { it == '}' } == raw.count { it == '{' } + 1 &&
            raw.count { it == '[' } == raw.count { it == ']' } + 1 &&
            raw.contains(Regex("""["]operations["]\s*:\s*\["""))
        ) {
            val combined = Regex(
                """[}](\s*)[}](\s*),(\s*["](?:constraints|unresolved_slots)["]\s*:)"""
            )
            val matches = combined.findAll(raw).toList()
            matches.asReversed().forEach { match ->
                val repaired = raw.replaceRange(
                    match.range,
                    "}${match.groupValues[1]}]${match.groupValues[2]},${match.groupValues[3]}"
                )
                runCatching { JSONObject(repaired) }.getOrNull()?.let {
                    return normalizeParameterTypes(
                        it,
                        repaired = true,
                        description = "removed extra operation brace and inserted array terminator"
                    )
                }
            }
        }

        // One extra operation-closing brace immediately before the operations array terminator,
        // followed by a valid fixed top-level arguments field.
        if (raw.count { it == '}' } == raw.count { it == '{' } + 1 &&
            raw.count { it == '[' } == raw.count { it == ']' } &&
            raw.contains(Regex("""["]operations["]\s*:\s*\["""))
        ) {
            val extraBeforeArray = Regex(
                """[}](\s*)[}](\s*)](\s*),(\s*["](?:constraints|unresolved_slots)["]\s*:)"""
            )
            val matches = extraBeforeArray.findAll(raw).toList()
            if (matches.size == 1) {
                val match = matches.single()
                val repaired = raw.replaceRange(
                    match.range,
                    "}${match.groupValues[1]}]${match.groupValues[3]},${match.groupValues[4]}"
                )
                runCatching { JSONObject(repaired) }.getOrNull()?.let {
                    return normalizeParameterTypes(
                        it,
                        repaired = true,
                        description = "removed extra brace before operations array terminator"
                    )
                }
            }
        }

        // Observed truncation where generation stops after closing `arguments` but omits exactly
        // the outer function-call brace. Append only one trailing brace, only for the fixed Goal
        // IR envelope with balanced arrays; semantic validation and duplicate removal still run.
        if (raw.count { it == '{' } == raw.count { it == '}' } + 1 &&
            raw.count { it == '[' } == raw.count { it == ']' } &&
            raw.contains("\"name\"") && raw.contains("\"plan_live_edit\"") &&
            raw.contains("\"arguments\"")
        ) {
            runCatching { JSONObject("$raw}") }.getOrNull()?.let {
                return normalizeParameterTypes(
                    it,
                    repaired = true,
                    description = "appended one missing outer envelope brace"
                )
            }
        }

        // Qwen occasionally closes the last operation object and immediately writes the next
        // top-level arguments key, omitting only the operations array bracket:
        //   ... "constraints":[]},"unresolved_slots":[] ...
        // Repair is allowed only when braces are already balanced, arrays differ by exactly one,
        // and the fixed schema key is present. No value, key or semantic field is changed.
        val openBraces = raw.count { it == '{' }
        val closeBraces = raw.count { it == '}' }
        if (closeBraces in openBraces..(openBraces + 1) &&
            raw.count { it == '[' } == raw.count { it == ']' } + 1 &&
            raw.contains(Regex("\"operations\"\\s*:\\s*\\["))
        ) {
            val boundary = Regex("\\}(\\s*),(\\s*\"(?:constraints|unresolved_slots)\"\\s*:)")
            val match = boundary.findAll(raw).lastOrNull()
            if (match != null) {
                val inserted = raw.replaceRange(
                    match.range,
                    "}]${match.groupValues[1]},${match.groupValues[2]}"
                )
                val candidates = buildList {
                    add(inserted)
                    if (closeBraces == openBraces + 1 && inserted.endsWith('}')) {
                        add(inserted.dropLast(1))
                    }
                }
                candidates.forEach { repaired ->
                    runCatching { JSONObject(repaired) }.getOrNull()?.let {
                        val description = if (repaired.length == inserted.length) {
                            "inserted missing operations array terminator"
                        } else {
                            "inserted operations array terminator and removed one extra trailing brace"
                        }
                        return normalizeParameterTypes(it, repaired = true, description = description)
                    }
                }
            }
        }
        // Another observed formatting-only variant moves the missing array terminator after the
        // arguments object: ...operation},"unresolved_slots":[]}]} . Move that single bracket
        // back before the fixed empty top-level field. This branch is intentionally limited to an
        // empty constraints/unresolved array so nested user/model values are never rewritten.
        if (raw.count { it == '{' } == raw.count { it == '}' } &&
            raw.count { it == '[' } == raw.count { it == ']' }
        ) {
            val misplaced = Regex(
                "\\}(\\s*),(\\s*\"(?:constraints|unresolved_slots)\"\\s*:\\s*\\[\\s*\\])" +
                    "\\s*\\}\\s*]\\s*\\}"
            )
            val match = misplaced.find(raw)
            if (match != null) {
                val repaired = raw.replaceRange(
                    match.range,
                    "}]${match.groupValues[1]},${match.groupValues[2]}}}"
                )
                runCatching { JSONObject(repaired) }.getOrNull()?.let {
                    return normalizeParameterTypes(
                        it,
                        repaired = true,
                        description = "moved misplaced operations array terminator"
                    )
                }
            }
        }
        // Observed local-goal variant with one arguments-closing brace written before the
        // operations array terminator: ..."constraints":[]}}]}}. Remove only that one brace;
        // all keys and values remain untouched and the strict codec still validates the result.
        if (closeBraces == openBraces + 1 &&
            raw.count { it == '[' } == raw.count { it == ']' } &&
            raw.contains(Regex("\"operations\"\\s*:\\s*\\["))
        ) {
            val misplacedBrace = Regex("\\}(\\s*)\\}(\\s*)\\](\\s*)\\}(\\s*)\\}$")
            val match = misplacedBrace.find(raw)
            if (match != null) {
                val repaired = raw.replaceRange(
                    match.range,
                    "}${match.groupValues[1]}]${match.groupValues[3]}}${match.groupValues[4]}}"
                )
                runCatching { JSONObject(repaired) }.getOrNull()?.let {
                    return normalizeParameterTypes(
                        it,
                        repaired = true,
                        description = "removed extra brace before operations array terminator"
                    )
                }
            }
        }
        return null
    }

    private fun normalizeParameterTypes(
        value: JSONObject,
        repaired: Boolean,
        description: String?
    ): AgentGoalJsonEnvelope {
        val copy = JSONObject(value.toString())
        val operations = copy.optJSONObject("arguments")?.optJSONArray("operations")
        var normalized = false
        var deduplicated = false
        if (operations != null) {
            for (index in 0 until operations.length()) {
                val parameters = operations.optJSONObject(index)?.optJSONObject("parameters") ?: continue
                for (name in listOf("intensity", "blur_radius_px")) {
                    val raw = parameters.opt(name) as? String ?: continue
                    val number = raw.toDoubleOrNull() ?: continue
                    if (number.isFinite() && Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)").matches(raw)) {
                        parameters.put(name, number)
                        normalized = true
                    }
                }
            }
            val uniqueRows = mutableListOf<JSONObject>()
            val seenRows = linkedSetOf<String>()
            for (index in 0 until operations.length()) {
                val row = operations.optJSONObject(index) ?: continue
                val canonical = row.toString()
                if (seenRows.add(canonical)) uniqueRows += row else deduplicated = true
            }
            if (deduplicated) {
                copy.getJSONObject("arguments").put("operations", JSONArray(uniqueRows))
            }
        }
        val descriptions = listOfNotNull(
            description,
            "normalized numeric parameter strings".takeIf { normalized },
            "removed exact duplicate operations".takeIf { deduplicated }
        )
        return AgentGoalJsonEnvelope(
            copy,
            repaired = repaired || normalized || deduplicated,
            repairDescription = descriptions.joinToString("; ").takeIf(String::isNotBlank)
        )
    }
}

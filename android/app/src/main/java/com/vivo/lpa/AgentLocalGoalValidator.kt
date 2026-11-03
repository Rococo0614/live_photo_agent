package com.vivo.lpa

object AgentLocalGoalValidator {
    fun appliesTo(goal: AgentGoalIr): Boolean = goal.operations.any {
        it.kind in setOf(
            AgentGoalOperationKind.ADD_CONTENT,
            AgentGoalOperationKind.CHANGE_CONTENT,
            AgentGoalOperationKind.REMOVE_CONTENT
        )
    }

    fun apply(result: AgentGoalCodecResult): AgentGoalCodecResult = result.goal?.let { goal ->
        result.copy(diagnostics = result.diagnostics + validate(goal))
    } ?: result

    fun validate(goal: AgentGoalIr): List<AgentGoalCodecDiagnostic> {
        val errors = mutableListOf<AgentGoalCodecDiagnostic>()
        if (goal.operations.size != 1) {
            errors += error("local_operation_count", "$.operations", "local preview requires exactly one operation")
            return errors
        }
        val operation = goal.operations.single()
        val unresolvedNames = goal.unresolvedSlots
            .filter { it.operationId == operation.operationId }
            .map { it.name }
            .toSet()
        if (operation.kind != AgentGoalOperationKind.CHANGE_CONTENT) {
            errors += error(
                "local_operation_kind",
                "$.operations[0].op",
                "local preview requires change_content"
            )
        }
        val target = operation.target as? AgentGoalTarget.Semantic
        if (target == null) {
            if ("target" !in unresolvedNames) {
                errors += error("local_target", "$.operations[0].target", "semantic target is required")
            }
        } else {
            if (target.description.isBlank() ||
                target.description.any { !it.isWhitespace() && it.code !in 0x20..0x7e }
            ) {
                errors += error(
                    "canonical_target_ascii",
                    "$.operations[0].target.description",
                    "canonical target must be a short ASCII English visual concept"
                )
            }
            if (target.description.length > 96) {
                errors += error(
                    "canonical_target_length",
                    "$.operations[0].target.description",
                    "canonical target is too long"
                )
            }
            if (target.sourceText.isNullOrBlank() || !goal.userGoal.contains(target.sourceText)) {
                errors += error(
                    "target_source_text",
                    "$.operations[0].target.source_text",
                    "target source_text must quote the user's target phrase"
                )
            } else {
                val canonicalWords = target.description.lowercase()
                    .split(Regex("[^a-z]+"))
                    .filter(String::isNotBlank)
                REQUIRED_VISUAL_QUALIFIERS.forEach { qualifier ->
                    if (qualifier.source.containsMatchIn(target.sourceText) &&
                        canonicalWords.none { word -> qualifier.acceptedPrefixes.any(word::startsWith) }
                    ) {
                        errors += error(
                            "target_qualifier_loss",
                            "$.operations[0].target.description",
                            "canonical target dropped ${qualifier.label}; include an equivalent " +
                                "English visual term (${qualifier.acceptedPrefixes.joinToString("/")})"
                        )
                    }
                }
            }
        }
        val change = LocalDeterministicChange.fromOperation(operation)
        if (change == null &&
            unresolvedNames.none { it in setOf("local_effect", "effect", "color", "intensity", "blur_radius") }
        ) {
            errors += error(
                "local_effect",
                "$.operations[0].parameters",
                "effect must be a bounded color_tint or blur"
            )
        }
        if (target != null && change != null) {
            val targetWords = target.description.lowercase()
                .split(Regex("[^a-z]+"))
                .filter(String::isNotBlank)
                .toSet()
            val leakedTerms = when (change) {
                is LocalDeterministicChange.ColorTint -> {
                    // A destination colour may legitimately describe the surrounding scene
                    // ("orange cat on green grass"). Only the subject phrase before a spatial
                    // relation is checked; "green cat" remains invalid.
                    val subjectWords = target.description.lowercase()
                        .split(Regex("[^a-z]+"))
                        .filter(String::isNotBlank)
                        .takeWhile { it !in SCENE_RELATIONS }
                        .toSet()
                    destinationColorTerms(change.colorHex).intersect(subjectWords)
                }
                is LocalDeterministicChange.Blur -> setOf("blur", "blurred", "blurry")
                    .intersect(targetWords)
            }
            if (leakedTerms.isNotEmpty()) {
                errors += error(
                    "target_effect_leakage",
                    "$.operations[0].target.description",
                    "canonical target must describe the pre-edit object and exclude effect terms: " +
                        leakedTerms.joinToString()
                )
            }
        }
        return errors
    }

    private fun destinationColorTerms(colorHex: String): Set<String> {
        val value = colorHex.removePrefix("#").toInt(16)
        val red = value ushr 16 and 0xff
        val green = value ushr 8 and 0xff
        val blue = value and 0xff
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        if (maximum < 48) return setOf("black")
        if (minimum > 208) return setOf("white")
        if (maximum - minimum < 28) return setOf("gray", "grey")
        return when {
            red > green * 1.25 && red > blue * 1.25 -> setOf("red")
            green > red * 1.25 && green > blue * 1.25 -> setOf("green")
            blue > red * 1.25 && blue > green * 1.25 -> setOf("blue")
            red > blue * 1.4 && green > blue * 1.4 -> setOf("yellow")
            green > red * 1.4 && blue > red * 1.4 -> setOf("cyan")
            red > green * 1.4 && blue > green * 1.4 -> setOf("purple", "magenta")
            else -> emptySet()
        }
    }

    private fun error(code: String, path: String, message: String) = AgentGoalCodecDiagnostic(
        AgentGoalCodecSeverity.ERROR,
        code,
        path,
        message
    )

    private val SCENE_RELATIONS = setOf(
        "on", "in", "at", "near", "beside", "by", "over", "under", "across", "through",
        "against", "inside", "outside", "around", "behind", "between", "with"
    )

    private data class RequiredQualifier(
        val source: Regex,
        val acceptedPrefixes: Set<String>,
        val label: String
    )

    private val REQUIRED_VISUAL_QUALIFIERS = listOf(
        RequiredQualifier(Regex("橘|橙"), setOf("orange"), "original orange colour"),
        RequiredQualifier(Regex("行走|走动|走"), setOf("walk"), "walking action"),
        RequiredQualifier(Regex("奔跑|跑"), setOf("run"), "running action"),
        RequiredQualifier(Regex("跳跃|跳"), setOf("jump"), "jumping action"),
        RequiredQualifier(Regex("草坪|草地"), setOf("grass", "lawn"), "grass scene")
    )
}

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
                if (targetSourceContainsEditAction(target.sourceText)) {
                    errors += error(
                        "target_source_action_leakage",
                        "$.operations[0].target.source_text",
                        "target source_text must quote only the pre-edit object phrase"
                    )
                }
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
        val generation = LocalGenerativeChange.fromOperation(operation)
        if (change == null && generation == null &&
            unresolvedNames.none {
                it in setOf(
                    "local_effect", "effect", "color", "intensity", "blur_radius",
                    "generation_prompt", "replacement"
                )
            }
        ) {
            errors += error(
                "local_effect",
                "$.operations[0].parameters",
                "effect must be a bounded color_tint, blur or masked_replace"
            )
        }
        if (generation != null && !goal.userGoal.contains(generation.promptSourceText)) {
            errors += error(
                "generation_prompt_source_text",
                "$.operations[0].parameters.generation_prompt.source_text",
                "generation prompt source_text must quote the requested replacement"
            )
        }
        if (target != null && change != null) {
            val targetWords = target.description.lowercase()
                .split(Regex("[^a-z]+"))
                .filter(String::isNotBlank)
                .toSet()
            val leakedTerms = when (change) {
                is LocalDeterministicChange.ColorTint -> {
                    destinationColorLeakage(
                        target.description,
                        target.sourceText.orEmpty(),
                        change.colorHex
                    )
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

    private fun destinationColorLeakage(
        canonicalTarget: String,
        sourceText: String,
        colorHex: String
    ): Set<String> {
        val destinationTerms = destinationColorTerms(colorHex)
        if (destinationTerms.isEmpty() || (!targetSourceContainsEditAction(sourceText) &&
            sourceMentionsDestinationColor(
                sourceText,
                destinationTerms
            ))
        ) {
            return emptySet()
        }
        var remaining = canonicalTarget.lowercase()
        NATURAL_SCENE_COLORS.forEach { scene ->
            if (scene.colorTerm in destinationTerms && scene.source.containsMatchIn(sourceText)) {
                remaining = scene.canonical.replace(remaining, " ")
            }
        }
        val remainingWords = remaining.split(Regex("[^a-z]+"))
            .filter(String::isNotBlank)
            .toSet()
        return destinationTerms.intersect(remainingWords)
    }

    private fun sourceMentionsDestinationColor(
        sourceText: String,
        destinationTerms: Set<String>
    ): Boolean = SOURCE_COLOR_TERMS.any { (term, source) ->
        term in destinationTerms && source.containsMatchIn(sourceText)
    }

    private fun targetSourceContainsEditAction(sourceText: String): Boolean =
        TARGET_SOURCE_EDIT_ACTION.containsMatchIn(sourceText)

    private fun error(code: String, path: String, message: String) = AgentGoalCodecDiagnostic(
        AgentGoalCodecSeverity.ERROR,
        code,
        path,
        message
    )

    private data class NaturalSceneColor(
        val colorTerm: String,
        val source: Regex,
        val canonical: Regex
    )

    private val NATURAL_SCENE_COLORS = listOf(
        NaturalSceneColor("green", Regex("草地|草坪"), Regex("\\bgreen\\s+(grass|lawn)\\b")),
        NaturalSceneColor("blue", Regex("水面|水中|海面|海里|天空"),
            Regex("\\bblue\\s+(water|sea|ocean|sky)\\b")),
        NaturalSceneColor("white", Regex("雪地|雪中|雪上"), Regex("\\bwhite\\s+snow\\b"))
    )

    private val SOURCE_COLOR_TERMS = mapOf(
        "red" to Regex("红"),
        "green" to Regex("绿"),
        "blue" to Regex("蓝"),
        "yellow" to Regex("黄"),
        "cyan" to Regex("青"),
        "purple" to Regex("紫|品红|洋红"),
        "magenta" to Regex("紫|品红|洋红"),
        "black" to Regex("黑"),
        "white" to Regex("白"),
        "gray" to Regex("灰"),
        "grey" to Regex("灰")
    )

    private val TARGET_SOURCE_EDIT_ACTION = Regex(
        "调成|变成|改成|染成|换成|换色|上色|着色|调色|模糊|虚化|" +
            "\\b(?:tint|color|colour|blur)\\b",
        RegexOption.IGNORE_CASE
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
        RequiredQualifier(
            Regex("滑板"),
            setOf("skateboard", "skating", "skater"),
            "skateboarding action"
        ),
        RequiredQualifier(Regex("草坪|草地"), setOf("grass", "lawn"), "grass scene")
    )
}

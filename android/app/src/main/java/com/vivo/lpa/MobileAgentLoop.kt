package com.vivo.lpa

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Android-native bounded Plan-Execute-Replan loop; no LangChain runtime is required. */
class MobileAgentLoop(private val context: Context) {
    companion object {
        private const val MAX_EXECUTED_STEPS = 8
        private const val MAX_REPLANS = 2
    }

    private val preprocessor = LivePhotoPreprocessor(context)

    fun run(
        query: String,
        uris: List<Uri>,
        layoutContext: JSONObject?,
        cancelled: AtomicBoolean,
        listener: MobileAgentRunner.Listener,
        preparedAssets: List<PreparedAsset>? = null,
        sessionId: String? = null,
        selectedAssetIds: List<String> = emptyList()
    ) {
        val turnId = "turn-${System.currentTimeMillis()}"
        val jobDir = File(context.cacheDir, "agent-loop/$turnId").apply { mkdirs() }
        var state = AgentRuntimeState(
            sessionId = sessionId,
            turnId = turnId,
            originalQuery = query,
            selectedAssetIds = selectedAssetIds,
            layout = layoutContext?.let { JSONObject(it.toString()) }
        )
        val executedCalls = mutableListOf<MobileToolCall>()
        val completedRoute = mutableListOf<SemanticRouteStep>()
        var finalTrace: BrainTrace? = null

        try {
            if (uris.isNotEmpty() || preparedAssets != null) {
                val assets = preparedAssets ?: preprocessor.prepare(
                    uris,
                    cancelled = { cancelled.get() },
                    progress = listener::onProgress
                )
                state = AgentStateReducer.apply(state, StateDelta.AddAssets(assets))
                listener.onAssetsPrepared(uris.ifEmpty { assets.map { it.sourceUri } }, assets)
            }

            listener.onProgress("端侧大脑正在规划完整任务路线…")
            val planner = GenieTextPlanner(context, sessionId)
            var routeResult = planner.tryRoute(query, state)
            planner.lastTrace?.let { listener.onRuntimeBrainTrace("route", it) }
            if (routeResult == null) {
                routeResult = planner.tryRoute(
                    query,
                    state,
                    "只输出完整 ROUTE 行；编辑请求不能同时输出 REPLY/QUESTION，也不能漏掉实际编辑能力"
                )
                planner.lastTrace?.let { listener.onRuntimeBrainTrace("route_retry", it) }
            }
            var parsedRoute = routeResult
                ?: throw IllegalStateException("端侧大脑未能生成可解析的语义路线")
            parsedRoute = repairRetrievalInstruction(
                planner, query, parsedRoute, listener
            )
            finalTrace = parsedRoute.trace
            listener.onRuntimeRoute(parsedRoute.route, parsedRoute.trace)

            parsedRoute.route.reply?.let { reply ->
                state = AgentStateReducer.apply(state, StateDelta.SetStatus(AgentRunStatus.FINISHED))
                listener.onRuntimeTerminal(state, reply)
                listener.onPlan(MobilePlan(query, "chat", emptyList(), emptyList(), reply,
                    parsedRoute.trace))
                listener.onFinished(null, reply)
                return
            }
            parsedRoute.route.clarification?.let { question ->
                state = AgentStateReducer.apply(state, StateDelta.SetStatus(AgentRunStatus.WAITING_USER))
                listener.onRuntimeTerminal(state, question)
                listener.onPlan(MobilePlan(query, "clarify", emptyList(), emptyList(), question,
                    parsedRoute.trace))
                listener.onFinished(null, question)
                return
            }

            val planId = "plan-$turnId"
            var route = parsedRoute.route
            var plan = RuntimePlan(planId, version = 1, route = route)
            state = state.copy(route = route, plan = plan)
            listener.onRuntimePlan(plan)
            checkpoint(state)

            val pending = ArrayDeque<SemanticRouteStep>().apply { addAll(route.steps) }
            val failedTools = mutableMapOf<String, MutableSet<String>>()
            var lastObservation = ""

            while (pending.isNotEmpty()) {
                if (cancelled.get()) throw InterruptedException("cancelled")
                if (state.executedCount >= MAX_EXECUTED_STEPS) {
                    throw IllegalStateException("工具步骤超过上限 $MAX_EXECUTED_STEPS，已停止避免循环")
                }

                val routeStep = pending.first()
                val baseCatalog = MobileToolRegistry.loopCatalog(state.toolContext(routeStep.family))
                val excludedByFailure = failedTools[routeStep.stepId].orEmpty()
                val catalog = baseCatalog.copy(
                    available = baseCatalog.available.filterNot { it.id in excludedByFailure },
                    excluded = baseCatalog.excluded + excludedByFailure.associateWith { "failed_in_this_step" }
                )
                listener.onToolCatalog(routeStep, catalog)

                if (catalog.available.isEmpty()) {
                    val reason = "${routeStep.family} 在当前素材/状态下没有可执行工具"
                    val revised = revise(
                        planner, query, state, completedRoute, pending.toList(), reason, listener
                    ) ?: throw IllegalStateException(reason)
                    if (revised.route.reply != null || revised.route.clarification != null) {
                        val message = revised.route.clarification ?: revised.route.reply!!
                        state = AgentStateReducer.apply(
                            state,
                            StateDelta.SetStatus(
                                if (revised.route.clarification != null) AgentRunStatus.WAITING_USER
                                else AgentRunStatus.FAILED,
                                message
                            )
                        ).copy(replanCount = state.replanCount + 1)
                        checkpoint(state)
                        listener.onRuntimeTerminal(state, message)
                        listener.onPlan(MobilePlan(
                            query,
                            if (revised.route.clarification != null) "clarify" else "chat",
                            state.selectedAssetIds,
                            emptyList(),
                            message,
                            revised.trace
                        ))
                        listener.onFinished(null, message)
                        return
                    }
                    route = SemanticRoute(query, completedRoute + revised.route.steps)
                    pending.clear(); pending.addAll(revised.route.steps)
                    plan = plan.copy(
                        version = plan.version + 1,
                        parentVersion = plan.version,
                        revisionReason = reason,
                        route = route
                    )
                    state = state.copy(
                        route = route,
                        plan = plan,
                        replanCount = state.replanCount + 1
                    )
                    finalTrace = revised.trace
                    listener.onRuntimePlan(plan)
                    checkpoint(state)
                    continue
                }

                val call = bindCall(planner, query, routeStep, state, catalog, lastObservation)
                    ?: throw IllegalStateException("无法为 ${routeStep.family} 绑定可执行工具")
                if (catalog.available.size > 1) {
                    planner.lastTrace?.let { listener.onRuntimeBrainTrace("bind", it) }
                }
                val runtimeStep = RuntimeStep(
                    stepId = "step-${plan.version}-${plan.steps.size + 1}-${routeStep.family.name.lowercase()}",
                    family = routeStep.family,
                    tool = call.tool,
                    arguments = JSONObject(call.arguments.toString()),
                    inputs = inputRefs(routeStep.family, state),
                    idempotencyKey = "$turnId:${routeStep.stepId}:${call.tool}"
                )
                val spec = MobileToolRegistry.spec(call.tool)
                    ?: throw IllegalStateException("未注册工具：${call.tool}")
                if (spec.singleton && executedCalls.any { it.tool == call.tool }) {
                    throw IllegalStateException("singleton 工具 ${call.tool} 已执行，拒绝重复副作用")
                }
                plan = plan.copy(steps = plan.steps + runtimeStep)
                state = state.copy(
                    plan = plan,
                    availableToolIds = catalog.available.map { it.id }
                )
                listener.onRuntimePlan(plan)
                if (BuildConfig.PLANNER_EVAL_ONLY && routeStep.family != ToolFamily.RETRIEVE) {
                    executedCalls += call
                    listener.onPlan(MobilePlan(
                        userGoal = query,
                        intent = "runtime_loop_eval",
                        selectedAssetIds = state.selectedAssetIds,
                        toolCalls = executedCalls,
                        brainTrace = finalTrace
                    ))
                    listener.onFinished(null, "规划评测完成（未执行媒体工具）")
                    return
                }
                listener.onProgress("执行 ${state.executedCount + 1}/$MAX_EXECUTED_STEPS：${call.tool}")
                listener.onToolCall(state.executedCount, MAX_EXECUTED_STEPS, call)

                val executor = MobileToolExecutor(
                    context, query, sessionId, jobDir, cancelled, listener::onProgress
                )
                try {
                    val execution = executor.execute(runtimeStep, state)
                    state = AgentStateReducer.apply(state, execution.delta)
                    state = AgentStateReducer.apply(state, StateDelta.MarkStepCompleted(runtimeStep.stepId))
                    plan = state.plan ?: plan
                    listener.onResult(execution.result)
                    listener.onStateDelta(runtimeStep.stepId, execution.delta, state)
                    executedCalls += call
                    propagateAssetCallbacks(execution.delta, state, listener)
                    checkpoint(state)
                    completedRoute += routeStep
                    pending.removeFirst()
                    failedTools.remove(routeStep.stepId)
                    lastObservation = compactResult(execution.result)

                    if (execution.invalidatesPendingPlan) {
                        val reason = "${call.tool} 的实际结果改变了后续前置条件：$lastObservation"
                        val revised = revise(
                            planner, query, state, completedRoute, pending.toList(), reason, listener
                        ) ?: throw IllegalStateException("$reason；端侧大脑未给出可执行修订")
                        if (revised.route.reply != null || revised.route.clarification != null) {
                            val message = revised.route.clarification ?: revised.route.reply!!
                            state = AgentStateReducer.apply(
                                state,
                                StateDelta.SetStatus(
                                    if (revised.route.clarification != null) AgentRunStatus.WAITING_USER
                                    else AgentRunStatus.FAILED,
                                    message
                                )
                            ).copy(replanCount = state.replanCount + 1)
                            checkpoint(state)
                            listener.onRuntimeTerminal(state, message)
                            listener.onPlan(MobilePlan(
                                query,
                                if (revised.route.clarification != null) "clarify" else "chat",
                                state.selectedAssetIds,
                                emptyList(),
                                message,
                                revised.trace
                            ))
                            listener.onFinished(state.publishedUris.lastOrNull(), message)
                            return
                        }
                        route = SemanticRoute(query, completedRoute + revised.route.steps)
                        pending.clear(); pending.addAll(revised.route.steps)
                        plan = plan.copy(
                            version = plan.version + 1,
                            parentVersion = plan.version,
                            revisionReason = reason,
                            route = route
                        )
                        state = state.copy(
                            route = route,
                            plan = plan,
                            replanCount = state.replanCount + 1
                        )
                        finalTrace = revised.trace
                        listener.onRuntimePlan(plan)
                        checkpoint(state)
                    }
                } catch (error: Throwable) {
                    if (error is InterruptedException) throw error
                    val message = error.message ?: error::class.java.simpleName
                    state = AgentStateReducer.apply(
                        state, StateDelta.MarkStepFailed(runtimeStep.stepId, message)
                    )
                    plan = state.plan ?: plan
                    val failedResult = MobileToolResult(call.tool, false, JSONObject()
                        .put("message", message)
                        .put("step_id", runtimeStep.stepId))
                    listener.onResult(failedResult)
                    listener.onStateDelta(
                        runtimeStep.stepId,
                        StateDelta.MarkStepFailed(runtimeStep.stepId, message),
                        state
                    )
                    checkpoint(state)
                    failedTools.getOrPut(routeStep.stepId) { linkedSetOf() } += call.tool
                    if (state.replanCount >= MAX_REPLANS) {
                        throw IllegalStateException("$message；已达到重规划上限 $MAX_REPLANS")
                    }
                    state = state.copy(replanCount = state.replanCount + 1)
                    lastObservation = "工具 ${call.tool} 失败：$message"
                    // Retry the same semantic step with the failed implementation removed. If no
                    // implementation remains, the next loop iteration asks the route reviser.
                }
            }

            val mediaWork = completedRoute.any {
                it.family in setOf(
                    ToolFamily.COMPOSE, ToolFamily.STYLE, ToolFamily.TEMPORAL,
                    ToolFamily.LAYOUT, ToolFamily.REPLACE
                )
            }
            if (mediaWork && state.publishedUris.isEmpty()) {
                throw IllegalStateException("编辑步骤已完成但没有经过显式 PUBLISH，拒绝报告成功")
            }
            state = AgentStateReducer.apply(state, StateDelta.SetStatus(AgentRunStatus.FINISHED))
            checkpoint(state)
            listener.onPlan(MobilePlan(
                userGoal = query,
                intent = "runtime_loop",
                selectedAssetIds = state.selectedAssetIds,
                toolCalls = executedCalls,
                brainTrace = finalTrace
            ))
            val uri = state.publishedUris.lastOrNull()
            listener.onRuntimeTerminal(state, if (uri == null) "任务已完成" else "已写入系统相册")
            listener.onFinished(uri, if (uri == null) "任务已完成" else "已写入系统相册")
            if (uri != null) jobDir.deleteRecursively()
        } catch (cancelledError: InterruptedException) {
            state = AgentStateReducer.apply(state, StateDelta.SetStatus(AgentRunStatus.CANCELLED))
            checkpoint(state)
            listener.onRuntimeTerminal(state, "用户已取消")
            listener.onFinished(null, "用户已取消")
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            state = AgentStateReducer.apply(state, StateDelta.SetStatus(AgentRunStatus.FAILED, message))
            checkpoint(state)
            listener.onRuntimeTerminal(state, message)
            listener.onError(message)
        }
    }

    private fun bindCall(
        planner: GenieTextPlanner,
        query: String,
        routeStep: SemanticRouteStep,
        state: AgentRuntimeState,
        catalog: ToolCatalogDecision,
        observation: String
    ): MobileToolCall? {
        if (catalog.available.size == 1) {
            val tool = catalog.available.single().id
            val args = JSONObject()
            if (routeStep.instruction.isNotBlank()) args.put("effect", routeStep.instruction)
            when (tool) {
                "search_assets" -> {
                    args.put("query", routeStep.instruction.ifBlank { query })
                    args.put("k", routeStep.requestedCount ?: 3)
                    routeStep.requiredMedia?.let { args.put("required_media", it.name.lowercase()) }
                }
                "asset_replace" -> args.put("query", routeStep.instruction)
                "template_collage" -> {
                    args.put("layout", "vertical")
                    args.put("canvas", "1080x1440")
                }
            }
            val ids = if (tool == "template_collage") state.sourceAssets.map { it.assetId }
                else state.scopedAssets().map { it.assetId }
            if (ids.isNotEmpty() && tool != "publish_result") args.put("asset_ids", JSONArray(ids))
            return MobileToolCall(tool, args, routeStep.instruction)
        }
        var result = planner.tryBindStep(query, routeStep, state, catalog, observation)
        planner.lastTrace?.let { /* audited by caller through route/plan listeners when parsed */ }
        if (result == null) {
            result = planner.tryBindStep(
                query, routeStep, state, catalog,
                "$observation\n上一输出无法解析；只输出一行 STEP=目录中的工具ID|效果"
            )
        }
        return result?.call
    }

    private fun inputRefs(family: ToolFamily, state: AgentRuntimeState): List<AgentInputRef> {
        val latest = state.latestValidatedArtifact()
        return when {
            family == ToolFamily.PUBLISH && latest != null ->
                listOf(AgentInputRef.Artifact(latest.artifactId))
            family in setOf(ToolFamily.STYLE, ToolFamily.TEMPORAL) && latest != null ->
                listOf(AgentInputRef.Artifact(latest.artifactId))
            family == ToolFamily.LAYOUT -> listOf(
                AgentInputRef.AssetSet(state.scopedAssets().map { it.assetId }),
                AgentInputRef.Layout(state.layoutVersion)
            )
            family == ToolFamily.COMPOSE ->
                listOf(AgentInputRef.AssetSet(state.sourceAssets.map { it.assetId }))
            family in setOf(ToolFamily.REPLACE, ToolFamily.ANALYZE, ToolFamily.STYLE, ToolFamily.TEMPORAL) ->
                listOf(AgentInputRef.AssetSet(state.scopedAssets().map { it.assetId }))
            else -> emptyList()
        }
    }

    private fun revise(
        planner: GenieTextPlanner,
        query: String,
        state: AgentRuntimeState,
        completed: List<SemanticRouteStep>,
        pending: List<SemanticRouteStep>,
        reason: String,
        listener: MobileAgentRunner.Listener
    ): RoutePlanningResult? {
        if (state.replanCount >= MAX_REPLANS) return null
        listener.onProgress("执行条件发生变化，端侧大脑正在修订剩余步骤…")
        var result = planner.tryReviseRoute(query, state, completed, pending, reason)
        planner.lastTrace?.let { listener.onRuntimeBrainTrace("revise", it) }
        if (result == null) {
            result = planner.tryReviseRoute(
                query, state, completed, pending,
                "$reason；上一输出无法解析，只输出未完成的 ROUTE 后缀"
            )
            planner.lastTrace?.let { listener.onRuntimeBrainTrace("revise_retry", it) }
        }
        result = result?.let { repairRetrievalInstruction(planner, query, it, listener) }
        result?.let { listener.onRuntimeRoute(it.route, it.trace) }
        return result
    }

    private fun repairRetrievalInstruction(
        planner: GenieTextPlanner,
        originalQuery: String,
        result: RoutePlanningResult,
        listener: MobileAgentRunner.Listener
    ): RoutePlanningResult {
        val retrieval = result.route.steps.firstOrNull { it.family == ToolFamily.RETRIEVE }
            ?: return result
        RetrievalQueryGuard.canonicalize(originalQuery, retrieval.instruction)?.let { canonical ->
            if (canonical != retrieval.instruction) {
                listener.onProgress("素材检索语义已规范化：$canonical")
                return result.copy(route = result.route.copy(steps = result.route.steps.map { step ->
                    if (step.stepId == retrieval.stepId) step.copy(instruction = canonical) else step
                }))
            }
            return result
        }

        listener.onProgress("检索内容与编辑动作混淆，端侧大脑正在重新提取素材语义…")
        var repaired = planner.tryRetrieveSpec(
            originalQuery,
            retrieval.requestedCount ?: 3,
            retrieval.requiredMedia
        )
        planner.lastTrace?.let { listener.onRuntimeBrainTrace("retrieve_repair", it) }
        if (repaired == null || RetrievalQueryGuard.canonicalize(originalQuery, repaired.spec.query) == null) {
            repaired = planner.tryRetrieveSpec(
                originalQuery,
                retrieval.requestedCount ?: 3,
                retrieval.requiredMedia
            )
            planner.lastTrace?.let { listener.onRuntimeBrainTrace("retrieve_repair_retry", it) }
        }
        val spec = repaired?.spec
            ?.let { candidate ->
                RetrievalQueryGuard.canonicalize(originalQuery, candidate.query)
                    ?.let { candidate.copy(query = it) }
            }
            ?: throw IllegalStateException("无法从用户原话中提取可靠的素材检索内容，已停止避免选错素材")
        val route = result.route.copy(steps = result.route.steps.map { step ->
            if (step.stepId == retrieval.stepId) step.copy(
                instruction = spec.query,
                requestedCount = spec.requestedCount,
                requiredMedia = spec.requiredMedia
            ) else step
        })
        return result.copy(route = route)
    }


    private fun propagateAssetCallbacks(
        delta: StateDelta,
        state: AgentRuntimeState,
        listener: MobileAgentRunner.Listener
    ) {
        when (delta) {
            is StateDelta.AddAssets -> listener.onAssetsPrepared(
                state.sourceAssets.map { it.sourceUri }, state.sourceAssets
            )
            is StateDelta.ReplaceAsset -> listener.onAssetsUpdated(
                state.sourceAssets, mapOf(delta.oldAssetId to delta.asset.assetId)
            )
            is StateDelta.Batch -> delta.deltas.forEach { propagateAssetCallbacks(it, state, listener) }
            else -> Unit
        }
    }

    private fun compactResult(result: MobileToolResult): String = JSONObject()
        .put("tool", result.tool)
        .put("success", result.success)
        .put("payload", result.payload)
        .toString()
        .take(1200)

    private fun checkpoint(state: AgentRuntimeState) {
        val id = state.sessionId?.takeIf { it.isNotBlank() } ?: return
        val database = LibraryDatabase(context)
        try {
            val session = database.sessionState(id)
            session.put("runtime_state_version", 2)
            session.put("runtime_checkpoint", runtimeCheckpoint(state))
            database.saveSessionState(id, session)
        } finally {
            database.close()
        }
    }

    private fun runtimeCheckpoint(state: AgentRuntimeState): JSONObject = JSONObject()
        .put("turn_id", state.turnId)
        .put("status", state.status.name.lowercase())
        .put("query", state.originalQuery)
        .put("asset_ids", JSONArray(state.sourceAssets.map { it.assetId }))
        .put("selected_asset_ids", JSONArray(state.selectedAssetIds))
        .put("layout_version", state.layoutVersion)
        .put("plan_id", state.plan?.planId)
        .put("plan_version", state.plan?.version ?: 0)
        .put("steps", JSONArray(state.plan?.steps.orEmpty().map { step ->
            JSONObject()
                .put("step_id", step.stepId)
                .put("family", step.family.name)
                .put("tool", step.tool)
                .put("status", step.status.name.lowercase())
                .put("idempotency_key", step.idempotencyKey)
                .put("arguments", JSONObject(step.arguments.toString()))
        }))
        .put("executed_count", state.executedCount)
        .put("replan_count", state.replanCount)
        .put("available_tools", JSONArray(state.availableToolIds))
        .put("artifacts", JSONArray(state.artifacts.values.map { artifact ->
            JSONObject()
                .put("artifact_id", artifact.artifactId)
                .put("kind", artifact.kind.name.lowercase())
                .put("producer_step_id", artifact.producerStepId)
                .put("file", artifact.file?.absolutePath)
                .put("image_file", artifact.imageFile?.absolutePath)
                .put("video_file", artifact.videoFile?.absolutePath)
                .put("uri", artifact.contentUri?.toString())
                .put("validated", artifact.validated)
                .put("published", artifact.published)
        }))
        .put("last_error", state.lastError)

}

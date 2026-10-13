package com.vivo.lpa

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Phone-facing shell for the migrated Live Photo Agent. */
class AgentActivity : Activity() {
    private val pickMediaRequest = 401
    private val canvasRequest = 702
    private val selectedUris = mutableListOf<Uri>()
    private val cancelled = AtomicBoolean(false)

    private lateinit var state: TextView
    private lateinit var chatLog: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var mediaSummary: TextView
    private lateinit var assetStrip: LinearLayout
    private lateinit var assetStripScroll: HorizontalScrollView
    private lateinit var templatePanel: LinearLayout
    private lateinit var templateScroll: HorizontalScrollView
    private lateinit var input: EditText
    private lateinit var selectButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var libraryButton: Button
    private lateinit var sessionButton: Button
    private lateinit var importButton: Button
    private lateinit var templateButton: Button
    private lateinit var canvasButton: Button
    private lateinit var generateButton: Button
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    private var lastResult: Uri? = null
    private var worker: Thread? = null
    private var preparedAssets: List<PreparedAsset> = emptyList()
    private var selectedTemplate: JSONObject? = null
    private var sessionId: String = ""
    private var restoringSession = false
    private var lastResultMessage: String = ""
    private var lastQuery: String = ""
    private var lastPlan: JSONObject? = null
    private val lastToolResults = mutableListOf<JSONObject>()
    private var taskStatus: String = "idle"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val database = LibraryDatabase(this)
        cleanupIntermediateVideos()
        scanSeedMedia()
        startLibrarySync()
        val requestedSession = intent.getStringExtra("session_id")
        val sessions = database.sessions()
        when {
            requestedSession != null && sessions.any { it.id == requestedSession } -> {
                openSession(database, requestedSession)
            }
            sessions.isEmpty() -> {
                openSession(database, database.createSession(), announce = true)
            }
            else -> showInitialSessionChoice(database, sessions.first().id)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(247, 247, 245))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@AgentActivity).apply {
                text = "Live Photo Agent"
                textSize = 21f
                setTextColor(Color.rgb(26, 61, 52))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@AgentActivity).apply {
                text = "端侧 VLM · 本地素材库 · vivo SDK"
                textSize = 12f
                setTextColor(Color.rgb(111, 72, 38))
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sessionButton = button("会话") { showSessions() }
        header.addView(sessionButton, LinearLayout.LayoutParams(dp(76), dp(42)))
        libraryButton = button("素材库") {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        header.addView(libraryButton, LinearLayout.LayoutParams(dp(82), dp(42)).apply { leftMargin = dp(6) })
        root.addView(header)
        state = TextView(this).apply {
            text = "就绪"
            textSize = 12f
            setTextColor(Color.rgb(71, 91, 84))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(state)

        chatScroll = ScrollView(this).apply { isFillViewport = true }
        chatLog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        chatScroll.addView(chatLog)
        root.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        mediaSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(26, 61, 52))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        root.addView(mediaSummary)
        assetStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        assetStripScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(assetStrip)
        }
        root.addView(assetStripScroll, LinearLayout.LayoutParams(-1, dp(118)))
        templatePanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        templateScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(templatePanel)
        }
        root.addView(templateScroll, LinearLayout.LayoutParams(-1, dp(108)))

        input = EditText(this).apply {
            hint = "告诉我你想怎么处理：调色、裁剪、拼接或增强"
            textSize = 15f
            minLines = 2
            maxLines = 3
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(Color.WHITE, Color.rgb(218, 224, 216), dp(18))
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(74)).apply { topMargin = dp(8) })

        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        selectButton = button("+ 素材") { pickMedia() }
        composer.addView(selectButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        analyzeButton = button("分析") { preprocessAssets() }
        analyzeButton.isEnabled = false
        composer.addView(analyzeButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        sendButton = button("发送") { submitQuery() }
        sendButton.setTextColor(Color.WHITE)
        sendButton.background = roundedBackground(Color.rgb(31, 105, 85), Color.rgb(31, 105, 85), dp(18))
        composer.addView(sendButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        cancelButton = button("中止") { cancelQuery() }
        cancelButton.isEnabled = false
        composer.addView(cancelButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        root.addView(composer)

        val secondary = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        templateButton = button("模板") { chooseTemplate() }
        templateButton.isEnabled = false
        secondary.addView(templateButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        canvasButton = button("画布") { openCanvas() }
        canvasButton.isEnabled = false
        secondary.addView(canvasButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(6) })
        generateButton = button("生成") { generateTemplate() }
        generateButton.isEnabled = false
        secondary.addView(generateButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(6) })
        val open = button("查看结果") { openResult() }
        secondary.addView(open, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(6) })
        root.addView(secondary, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4) })
        importButton = button("导入库") { importLivePhotoLibrary() }
        secondary.addView(importButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(6) })
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            } else {
                insets.systemWindowInsetBottom
            }
            view.setPadding(dp(14), dp(12), dp(14), bottom + dp(12))
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            insets
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun pickMedia() {
        if (worker != null) return
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 3)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }
        startActivityForResult(intent, pickMediaRequest)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == canvasRequest && resultCode == RESULT_OK && data != null) {
            layoutContext = data.getStringExtra("layout_context")?.let(::JSONObject)
            templateButton.text = "当前画布布局"
            generateButton.isEnabled = layoutContext != null && preparedAssets.isNotEmpty()
            input.setText("当前模板拼接")
            taskStatus = "ready_for_edit"
            persistSessionState()
            append("已接收画布布局，可直接点击‘按模板生成拼接’")
            return
        }
        if (requestCode != pickMediaRequest || resultCode != RESULT_OK || data == null) return
        val picked = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) picked += clip.getItemAt(index).uri
        } ?: data.data?.let { picked += it }
        selectedUris.clear()
        selectedUris += picked.take(3)
        layoutContext = null
        selectedTemplate = null
        preparedAssets = emptyList()
        lastResult = null
        lastResultMessage = ""
        lastPlan = null
        lastToolResults.clear()
        taskStatus = if (selectedUris.isEmpty()) "idle" else "assets_selected"
        selectedUris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        mediaSummary.visibility = if (selectedUris.isEmpty()) View.GONE else View.VISIBLE
        analyzeButton.isEnabled = selectedUris.isNotEmpty()
        templateButton.isEnabled = false
        canvasButton.isEnabled = false
        generateButton.isEnabled = false
        mediaSummary.text = "已选择 ${selectedUris.size} 个素材：${selectedUris.joinToString() }"
        renderUriPreviews(selectedUris)
        persistSessionState()
        append("已载入 ${selectedUris.size} 个素材")
    }

    private var layoutContext: JSONObject? = null

    private fun preprocessAssets() {
        if (selectedUris.isEmpty() || worker != null) return
        cancelled.set(false)
        setBusy(true)
        state.text = "前处理 | 正在读取素材和运行 VLM"
        worker = Thread({
            runCatching {
                val prepared = LivePhotoPreprocessor(this).prepare(
                    selectedUris.toList(),
                    cancelled = { cancelled.get() },
                    progress = { message -> ui { state.text = message; append("· $message") } }
                )
                preparedAssets = prepared
                ui {
                    val ready = prepared.count { it.semantic.optBoolean("ready", false) }
                    LibraryDatabase(this).apply { prepared.forEach(::upsert) }
                    taskStatus = "ready_for_edit"
                    persistSessionState()
                    mediaSummary.text = "已前处理 ${prepared.size} 个素材 · VLM 就绪 ${ready} 个\n" +
                        prepared.joinToString("\n") { asset ->
                            "${asset.sourceFile.name}: ${asset.semantic.optString("summary", "无语义摘要")}".take(180)
                        }
                    renderPreparedPreviews(prepared)
                    templateButton.isEnabled = prepared.isNotEmpty()
                    canvasButton.isEnabled = prepared.isNotEmpty()
                    append("图像前处理完成：${prepared.size} 个素材，VLM 就绪 ${ready} 个")
                    state.text = "前处理完成 | 可选择模板或打开交互画布"
                    finishWorker()
                }
            }.onFailure { error ->
                ui {
                    state.text = "前处理失败"
                    taskStatus = "failed"
                    persistSessionState(error.message ?: "前处理失败")
                    append("前处理失败：${error.message}")
                    finishWorker()
                }
            }
        }, "live-photo-preprocess")
        worker!!.start()
    }

    private fun startLibrarySync() {
        startForegroundService(Intent(this, AgentKeepAliveService::class.java))
        Thread({
            runCatching {
                LibraryIndexer(this).sync({ cancelled.get() }) { message ->
                    ui { state.text = "素材库索引 | $message" }
                }
            }.onSuccess { report ->
                ui {
                    if (!report.skipped) {
                        append("素材库同步完成：新增/更新 ${report.changed} 条，删除 ${report.removed} 条")
                        state.text = "就绪 | 素材库已增量同步"
                    }
                    stopService(Intent(this, AgentKeepAliveService::class.java))
                }
            }.onFailure { error ->
                ui {
                    append("素材库索引暂不可用：${error.message}")
                    stopService(Intent(this, AgentKeepAliveService::class.java))
                }
            }
        }, "library-indexer").start()
    }

    private fun chooseTemplate() {
        if (preparedAssets.isEmpty() || worker != null) return
        val templates = MobileTemplateCatalog(this).list()
        if (templates.isEmpty()) {
            append("模板库为空")
            return
        }
        templatePanel.removeAllViews()
        templates.forEach { template ->
            val card = TextView(this).apply {
                text = "${template.optString("id")}\n${template.optString("name")}\n${template.optJSONArray("slots")?.length() ?: 0} 格 · ${template.optString("layout_type", "grid")}"
                textSize = 13f
                setTextColor(Color.rgb(26, 61, 52))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(if (template.optString("id") == selectedTemplate?.optString("id")) {
                    Color.rgb(205, 232, 218)
                } else {
                    Color.WHITE
                })
                setOnClickListener { selectTemplate(template) }
            }
            templatePanel.addView(card, LinearLayout.LayoutParams(dp(170), dp(92)).apply {
                rightMargin = dp(8)
            })
        }
        templatePanel.visibility = View.VISIBLE
        templateScroll.visibility = View.VISIBLE
        state.text = "模板库 | 共 ${templates.size} 个，可横向滑动选择"
    }

    private fun renderUriPreviews(uris: List<Uri>) {
        assetStrip.removeAllViews()
        uris.forEachIndexed { index, uri ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(218, 224, 216))
                val bitmap = runCatching {
                    if (Build.VERSION.SDK_INT >= 29) {
                        contentResolver.loadThumbnail(uri, Size(dp(132), dp(88)), null)
                    } else {
                        BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                    }
                }.getOrNull()
                if (bitmap != null) setImageBitmap(bitmap)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(image, LinearLayout.LayoutParams(dp(132), dp(88)))
                addView(TextView(this@AgentActivity).apply {
                    text = "素材 ${index + 1}"
                    textSize = 11f
                    setTextColor(Color.rgb(26, 61, 52))
                })
            }
            assetStrip.addView(card, LinearLayout.LayoutParams(dp(132), dp(108)).apply {
                rightMargin = dp(8)
            })
        }
        assetStripScroll.visibility = if (uris.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderPreparedPreviews(assets: List<PreparedAsset>) {
        assetStrip.removeAllViews()
        assets.forEachIndexed { index, asset ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(218, 224, 216))
                asset.imageFile?.let { file ->
                    BitmapFactory.decodeFile(file.absolutePath)?.let(::setImageBitmap)
                }
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(image, LinearLayout.LayoutParams(dp(132), dp(88)))
                addView(TextView(this@AgentActivity).apply {
                    text = "${index + 1} · ${if (asset.semantic.optBoolean("ready")) "VLM" else "待处理"}"
                    textSize = 11f
                    setTextColor(Color.rgb(26, 61, 52))
                })
            }
            assetStrip.addView(card, LinearLayout.LayoutParams(dp(132), dp(108)).apply {
                rightMargin = dp(8)
            })
        }
        assetStripScroll.visibility = if (assets.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun selectTemplate(template: JSONObject) {
        selectedTemplate = template
        layoutContext = MobileTemplateParser.parse(template, preparedAssets)
        templateButton.text = "模板 ${template.optString("id")}"
        generateButton.isEnabled = true
        input.setText("当前模板拼接")
        templatePanel.visibility = View.GONE
        templateScroll.visibility = View.GONE
        taskStatus = "ready_for_edit"
        persistSessionState()
        state.text = "${template.optString("id")} ${template.optString("name")} | 可微调画布后生成"
        append("已选择模板：${template.optString("id")} ${template.optString("name")}")
    }

    private fun generateTemplate() {
        if (layoutContext == null) {
            append("请先分析素材并选择模板，或打开画布完成布局")
            return
        }
        input.setText("当前模板拼接")
        submitQuery()
    }

    private fun importLivePhotoLibrary() {
        if (worker != null) return
        val imageRoot = File(
            getExternalFilesDir("livephoto-source") ?: filesDir,
            "jpg"
        )
        val originalVideoRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ".LivePhotoAgent/original"
        )
        val activeVideoRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ".LivePhotoAgent/active"
        )
        if (!imageRoot.isDirectory) {
            append("没有找到导入源：${imageRoot.absolutePath}")
            return
        }
        cancelled.set(false)
        setBusy(true)
        state.text = "正在用 vivo LivePhotoSDK 重建动态照片"
        worker = Thread({
            runCatching {
                LivePhotoLibraryImporter(this).importDirectory(
                    imageRoot, originalVideoRoot, activeVideoRoot
                ) { message -> ui { state.text = message } }
            }.onSuccess { report ->
                ui {
                    append("动态照片导入完成：成功 ${report.imported}，跳过 ${report.skipped}，错误 ${report.errors.size}")
                    report.errors.take(5).forEach { append(it) }
                    state.text = "动态照片已写入系统相册 | 成功 ${report.imported} 张"
                    finishWorker()
                }
            }.onFailure { error ->
                ui {
                    append("动态照片导入失败：${error.message}")
                    state.text = "动态照片导入失败"
                    finishWorker()
                }
            }
        }, "livephoto-library-import")
        worker!!.start()
    }

    private fun openCanvas() {
        if (selectedUris.isEmpty() || worker != null) return
        startActivityForResult(Intent(this, CanvasEditorActivity::class.java).apply {
            putStringArrayListExtra("uris", ArrayList(selectedUris.map(Uri::toString)))
            layoutContext?.let { putExtra("layout_context", it.toString()) }
        }, canvasRequest)
    }

    private fun submitQuery() {
        if (worker != null) return
        val query = input.text.toString().trim()
        if (query.isEmpty()) {
            append("请输入处理要求")
            return
        }
        cancelled.set(false)
        lastQuery = query
        taskStatus = "running"
        persistSessionState()
        append("你\n$query")
        input.text.clear()
        setBusy(true)
        state.text = "运行中 | 前处理 -> 规划 -> vivo SDK 执行"
        val runner = MobileAgentRunner(this)
        worker = Thread({
            runner.run(query, selectedUris.toList(), layoutContext, cancelled, object : MobileAgentRunner.Listener {
                override fun onProgress(message: String) = ui { state.text = message; append("· $message") }
                override fun onAssetsPrepared(uris: List<Uri>, assets: List<PreparedAsset>) = ui {
                    selectedUris.clear()
                    selectedUris += uris
                    preparedAssets = assets
                    taskStatus = "assets_prepared"
                    mediaSummary.text = "已准备 ${assets.size} 个素材，可继续沿用本轮素材"
                    renderPreparedPreviews(assets)
                    persistSessionState()
                }
                override fun onPlan(plan: MobilePlan) = ui {
                    lastPlan = plan.toJson()
                    lastToolResults.clear()
                    persistSessionState()
                    val steps = plan.toolCalls.joinToString("、") { it.tool }
                    append(if (steps.isEmpty()) "我暂时没有需要执行的编辑动作" else "我会：$steps")
                }
                override fun onResult(result: MobileToolResult) = ui {
                    if (result.tool == "template_collage" || result.tool == "smart_collage") {
                        result.payload.optJSONObject("layout_context")?.let { layoutContext = it }
                        result.payload.optString("template_id").takeIf { it.isNotBlank() }?.let {
                            selectedTemplate = JSONObject().put("id", it)
                        }
                    }
                    lastToolResults += JSONObject()
                        .put("tool", result.tool)
                        .put("success", result.success)
                        .put("payload", result.payload)
                    persistSessionState()
                    append("${result.tool} 已完成")
                }
                override fun onFinished(uri: Uri?, message: String) = ui {
                    lastResult = uri
                    lastResultMessage = message
                    taskStatus = if (uri == null && message == "用户已取消") "cancelled" else
                        if (uri == null) "completed_without_output" else "completed"
                    persistSessionState()
                    state.text = if (uri == null) message else "完成 | 已写入系统相册"
                    if (uri == null) append(message) else appendResult(uri, message)
                    finishWorker()
                }
                override fun onError(message: String) = ui {
                    Log.e("LivePhotoAgent", message)
                    taskStatus = "failed"
                    persistSessionState(message)
                    state.text = "失败"
                    append("失败：$message")
                    finishWorker()
                }
            }, preparedAssets.ifEmpty { null }, sessionId)
        }, "live-photo-agent")
        worker!!.start()
    }

    private fun cancelQuery() {
        cancelled.set(true)
        append("正在请求中止当前任务")
        state.text = "正在中止"
        worker?.interrupt()
    }

    private fun finishWorker() {
        worker = null
        setBusy(false)
    }

    private fun setBusy(busy: Boolean) {
        if (busy) {
            startForegroundService(Intent(this, AgentKeepAliveService::class.java))
        } else {
            stopService(Intent(this, AgentKeepAliveService::class.java))
        }
        updateActionState(busy)
    }

    private fun updateActionState(busy: Boolean = worker != null) {
        if (!::sendButton.isInitialized) return
        sendButton.isEnabled = !busy
        selectButton.isEnabled = !busy
        analyzeButton.isEnabled = !busy && selectedUris.isNotEmpty()
        sessionButton.isEnabled = !busy
        libraryButton.isEnabled = !busy
        importButton.isEnabled = !busy
        templateButton.isEnabled = !busy && preparedAssets.isNotEmpty()
        canvasButton.isEnabled = !busy && preparedAssets.isNotEmpty()
        generateButton.isEnabled = !busy && layoutContext != null && preparedAssets.isNotEmpty()
        cancelButton.isEnabled = busy
    }

    private fun openResult() {
        val uri = lastResult ?: return
        openUri(uri)
    }

    private fun openUri(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: if (uri.toString().contains(".jpg")) {
            "image/jpeg"
        } else {
            "video/mp4"
        }
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun modelState(): String {
        val vlm = when {
            ModelPack.vlmReady(this) -> "VLM runner 已发现"
            ModelPack.vlmConfig(this).isFile && ModelPack.vlmScript(this).isFile ->
                "VLM 资源已部署，但缺少 genie-app runner"
            else -> "VLM 未部署"
        }
        val planner = if (ModelPack.qwenReady(this)) "QNN Planner 已发现" else "Planner 使用确定性迁移骨架"
        val catalog = runCatching { LibraryDatabase(this).list() }.getOrDefault(emptyList())
        val indexed = catalog.count {
            it.semantic.optBoolean("ready", false) || it.semantic.optString("status") == "ready"
        }
        return "就绪 | $planner | $vlm | 素材库 ${catalog.size} 条 / 已解析 ${indexed} 条 | vivo VideoEditorSDK"
    }

    private fun append(message: String) {
        if (!::chatLog.isInitialized) return
        val userMessage = message.startsWith("你\n")
        if (!restoringSession) {
            runCatching {
                LibraryDatabase(this).logEvent(if (userMessage) "USER" else "AGENT", message)
                LibraryDatabase(this).appendMessage(sessionId, if (userMessage) "user" else "agent", message)
            }
        }
        val bubble = TextView(this).apply {
            text = if (userMessage) message.removePrefix("你\n") else message
            textSize = if (userMessage) 15f else 14f
            setTextColor(Color.rgb(38, 43, 40))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            background = roundedBackground(
                if (userMessage) Color.rgb(211, 239, 224) else Color.WHITE,
                if (userMessage) Color.rgb(183, 220, 198) else Color.rgb(225, 228, 224),
                dp(18)
            )
        }
        chatLog.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (userMessage) Gravity.END else Gravity.START
            bottomMargin = dp(8)
        })
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openSession(database: LibraryDatabase, id: String, announce: Boolean = false) {
        if (worker != null) return
        resetSessionUi()
        sessionId = id
        restoreSession(database)
        if (chatLog.childCount == 0) {
            append(if (announce) {
                "Agent 已启动。请选择照片、Live Photo 或视频，然后输入处理要求。"
            } else {
                "已恢复对话，可继续处理上次会话中的素材。"
            })
        }
        state.text = modelState()
        updateActionState()
    }

    private fun showInitialSessionChoice(database: LibraryDatabase, recentSessionId: String) {
        state.text = "请选择新建对话，或继续最近一次对话"
        AlertDialog.Builder(this)
            .setTitle("开始对话")
            .setMessage("继续对话会恢复上次的素材、布局和结果；新建对话会从空白状态开始。")
            .setPositiveButton("继续最近对话") { _, _ ->
                openSession(database, recentSessionId)
            }
            .setNegativeButton("新建对话") { _, _ ->
                openSession(database, database.createSession(), announce = true)
            }
            .setNeutralButton("选择其他") { _, _ -> showSessions(recentSessionId) }
            .setCancelable(false)
            .show()
    }

    private fun restoreSession(database: LibraryDatabase) {
        restoringSession = true
        database.recentMessages(sessionId).forEach { raw ->
            val split = raw.indexOf(": ")
            val role = if (split > 0) raw.substring(0, split) else "agent"
            val content = if (split > 0) raw.substring(split + 2) else raw
            append(if (role == "user") "你\n$content" else content)
        }
        restoreSessionState(database)
        lastResult?.let { uri ->
            appendResult(uri, lastResultMessage.ifBlank { "上次任务结果" }, persistEvent = false)
        }
        restoringSession = false
    }

    private fun restoreSessionState(database: LibraryDatabase) {
        val saved = database.sessionState(sessionId)
        selectedUris.clear()
        saved.optJSONArray("selected_uris")?.let { values ->
            for (index in 0 until values.length()) {
                values.optString(index).takeIf { it.isNotBlank() }?.let { selectedUris += Uri.parse(it) }
            }
        }
        preparedAssets = decodePreparedAssets(saved.optJSONArray("prepared_assets"))
        layoutContext = saved.optJSONObject("layout_context")
        selectedTemplate = saved.optJSONObject("selected_template")
        lastResult = saved.optString("last_result_uri", "")
            .takeIf { it.isNotBlank() }?.let(Uri::parse)
        lastResultMessage = saved.optString("last_result_message", "")
        lastQuery = saved.optString("last_query", "")
        lastPlan = saved.optJSONObject("last_plan")
        lastToolResults.clear()
        saved.optJSONArray("last_tool_results")?.let { values ->
            for (index in 0 until values.length()) {
                values.optJSONObject(index)?.let(lastToolResults::add)
            }
        }
        taskStatus = saved.optString("task_status", "idle")

        when {
            preparedAssets.isNotEmpty() -> {
                mediaSummary.visibility = View.VISIBLE
                mediaSummary.text = "已恢复 ${preparedAssets.size} 个素材和上次前处理结果"
                renderPreparedPreviews(preparedAssets)
            }
            selectedUris.isNotEmpty() -> {
                mediaSummary.visibility = View.VISIBLE
                mediaSummary.text = "已恢复 ${selectedUris.size} 个素材；前处理缓存已失效，请重新分析"
                renderUriPreviews(selectedUris)
                append("上次素材仍在，但前处理缓存不可用，请点击‘分析’重新准备")
            }
            else -> {
                mediaSummary.visibility = View.GONE
                assetStrip.removeAllViews()
                assetStripScroll.visibility = View.GONE
            }
        }
        selectedTemplate?.let { templateButton.text = "模板 ${it.optString("id")}" }
        updateActionState(false)
    }

    private fun resetSessionUi() {
        selectedUris.clear()
        preparedAssets = emptyList()
        selectedTemplate = null
        layoutContext = null
        lastResult = null
        lastResultMessage = ""
        lastQuery = ""
        lastPlan = null
        lastToolResults.clear()
        taskStatus = "idle"
        input.setText("")
        mediaSummary.text = ""
        mediaSummary.visibility = View.GONE
        assetStrip.removeAllViews()
        assetStripScroll.visibility = View.GONE
        templatePanel.removeAllViews()
        templatePanel.visibility = View.GONE
        templateScroll.visibility = View.GONE
        templateButton.text = "模板"
        chatLog.removeAllViews()
        updateActionState(false)
    }

    private fun showSessions(fallbackSessionId: String? = null) {
        val database = LibraryDatabase(this)
        val sessions = database.sessions()
        val labels = sessions.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择对话")
            .setItems(labels) { _, index ->
                startActivity(Intent(this, AgentActivity::class.java).putExtra("session_id", sessions[index].id))
                finish()
            }
            .setPositiveButton("新建对话") { _, _ -> newSession() }
            .setNegativeButton("取消") { _, _ ->
                fallbackSessionId?.let { openSession(database, it) }
            }
            .show()
    }

    private fun newSession() {
        sessionId = LibraryDatabase(this).createSession()
        resetSessionUi()
        append("Agent 已启动。新对话已创建。")
        state.text = modelState()
        persistSessionState()
    }

    private fun appendResult(uri: Uri, message: String, persistEvent: Boolean = true) {
        if (!::chatLog.isInitialized) return
        if (persistEvent) {
            runCatching { LibraryDatabase(this).logEvent("RESULT", "$message|$uri") }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(Color.rgb(225, 244, 232), Color.rgb(153, 207, 174), dp(18))
            setOnClickListener { openUri(uri) }
            addView(TextView(this@AgentActivity).apply {
                text = "$message\n点击打开结果文件"
                textSize = 15f
                setTextColor(Color.rgb(26, 61, 52))
                setPadding(dp(4), dp(2), dp(4), dp(8))
            })
            val preview = ImageView(this@AgentActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(211, 224, 216))
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) {
                        setImageBitmap(contentResolver.loadThumbnail(uri, Size(dp(260), dp(180)), null))
                    }
                }
            }
            addView(preview, LinearLayout.LayoutParams(dp(260), dp(180)))
        }
        chatLog.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.START
            bottomMargin = dp(8)
        })
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun persistSessionState(error: String? = null) {
        if (sessionId.isBlank()) return
        val prepared = JSONArray()
        preparedAssets.forEach { asset ->
            prepared.put(JSONObject()
                .put("asset_id", asset.assetId)
                .put("source_uri", asset.sourceUri.toString())
                .put("source_file", asset.sourceFile.absolutePath)
                .put("image_file", asset.imageFile?.absolutePath ?: JSONObject.NULL)
                .put("video_file", asset.videoFile?.absolutePath ?: JSONObject.NULL)
                .put("metadata", asset.metadata)
                .put("semantic", asset.semantic))
        }
        val toolResults = JSONArray()
        lastToolResults.forEach(toolResults::put)
        val state = JSONObject()
            .put("schema_version", 1)
            .put("task_status", taskStatus)
            .put("last_query", lastQuery)
            .put("selected_uris", JSONArray(selectedUris.map(Uri::toString)))
            .put("prepared_assets", prepared)
            .put("layout_context", layoutContext ?: JSONObject.NULL)
            .put("selected_template", selectedTemplate ?: JSONObject.NULL)
            .put("last_plan", lastPlan ?: JSONObject.NULL)
            .put("last_tool_results", toolResults)
            .put("last_result_uri", lastResult?.toString() ?: "")
            .put("last_result_message", lastResultMessage)
        if (!error.isNullOrBlank()) state.put("last_error", error)
        runCatching { LibraryDatabase(this).saveSessionState(sessionId, state) }
    }

    private fun decodePreparedAssets(values: JSONArray?): List<PreparedAsset> = buildList {
        if (values == null) return@buildList
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val sourceUri = item.optString("source_uri", "").takeIf { it.isNotBlank() }?.let(Uri::parse)
                ?: continue
            val sourceFile = File(item.optString("source_file", ""))
            if (!sourceFile.isFile) continue
            val imageFile = item.optString("image_file", "")
                .takeIf { it.isNotBlank() && it != "null" }?.let(::File)?.takeIf { it.isFile }
            val videoFile = item.optString("video_file", "")
                .takeIf { it.isNotBlank() && it != "null" }?.let(::File)?.takeIf { it.isFile }
            add(PreparedAsset(
                assetId = item.optString("asset_id", sourceFile.name),
                sourceUri = sourceUri,
                sourceFile = sourceFile,
                imageFile = imageFile,
                videoFile = videoFile,
                metadata = item.optJSONObject("metadata") ?: JSONObject(),
                semantic = item.optJSONObject("semantic") ?: JSONObject()
            ))
        }
    }

    private fun scanSeedMedia() {
        val roots = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LivePhotoAgent"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "LivePhotoAgent")
        )
        val files = roots.flatMap { root -> root.listFiles()?.filter { it.isFile } ?: emptyList() }
        if (files.isEmpty()) return
        val signature = files.fold(0L) { value, file -> value xor file.length() xor file.lastModified() }
        val preferences = getPreferences(MODE_PRIVATE)
        if (preferences.getLong("seed_media_signature", 0L) == signature) return
        MediaScannerConnection.scanFile(
            this,
            files.map { it.absolutePath }.toTypedArray(),
            files.map { if (it.extension.equals("mp4", true)) "video/mp4" else "image/jpeg" }.toTypedArray(),
            null
        )
        preferences.edit().putLong("seed_media_signature", signature).apply()
    }

    /** Remove MP4s emitted by older collage runs; explicit exports use a different name. */
    private fun cleanupIntermediateVideos() {
        if (Build.VERSION.SDK_INT < 29) return
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} = ?",
            arrayOf("Movies/LivePhotoAgent/"),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                ).lowercase()
                if (!name.startsWith("template_collage") && !name.startsWith("smart_collage")) continue
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                contentResolver.delete(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    null,
                    null
                )
            }
        }
    }

    private fun persistBeforeLeaving() {
        if (sessionId.isNotBlank()) persistSessionState()
    }

    private fun ui(block: () -> Unit) {
        if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return
        runOnUiThread(block)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
        background = roundedBackground(Color.WHITE, Color.rgb(218, 224, 216), dp(18))
        setTextColor(Color.rgb(26, 61, 52))
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = radius.toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        persistBeforeLeaving()
        super.onStop()
    }

    override fun onDestroy() {
        if (worker != null) {
            cancelled.set(true)
            worker?.interrupt()
            worker = null
            stopService(Intent(this, AgentKeepAliveService::class.java))
        }
        persistBeforeLeaving()
        super.onDestroy()
    }
}

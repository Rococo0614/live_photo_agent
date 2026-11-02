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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

    /**
     * Bumped whenever the open session changes so callbacks from a cancelled worker cannot
     * write into the session that replaced the one they were launched for.
     */
    private var sessionGeneration = 0

    private lateinit var state: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var chatLog: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var localPreviewPanel: LinearLayout
    private lateinit var mediaSummary: TextView
    private lateinit var assetStrip: LinearLayout
    private lateinit var libraryButton: Button
    private lateinit var assetStripScroll: HorizontalScrollView
    private lateinit var templatePanel: LinearLayout
    private lateinit var templateScroll: HorizontalScrollView
    private lateinit var input: EditText
    private lateinit var selectButton: Button
    private lateinit var sessionButton: Button
    private lateinit var actionButton: Button
    private var actionBusy = false
    private var lastResult: Uri? = null
    private var worker: Thread? = null
    private var librarySyncing = false
    private var libraryProgressText: String = ""
    private var libraryTotal: Int = 0
    private var libraryReady: Int = 0
    private var libraryPending: Int = 0
    private var libraryIndexDialog: AlertDialog? = null
    /** User chose “后台运行” for this sync; later per-asset callbacks must not reopen it. */
    private var libraryIndexDialogDismissed = false
    private var preparedAssets: List<PreparedAsset> = emptyList()
    /**
     * Assets the user tapped in the strip.
     *
     * Empty means "the whole set", which is the behaviour every existing flow relies on, so
     * leaving nothing selected is a no-op rather than a new mode.
     */
    private val selectedAssetIds = linkedSetOf<String>()
    private var selectedTemplate: JSONObject? = null
    private var sessionId: String = ""
    private var restoringSession = false
    private var lastResultMessage: String = ""
    private var lastQuery: String = ""
    private var lastPlan: JSONObject? = null
    private val lastToolResults = mutableListOf<JSONObject>()
    private var taskStatus: String = "idle"
    private var activeLocalPreview: AndroidLocalPreviewInteraction? = null

    /**
     * Transient "thinking" bubble: a spinner plus a fixed-height box that is rewritten in place
     * as planning and tool execution proceed.
     *
     * It is deliberately not an [append] bubble. Its content goes only to interaction_events as
     * TRACE rows (for later diagnosis) and never to chat_messages, so restoring a session — or
     * simply looking at the finished conversation — shows the result, not the working trace.
     * The view is removed from the flow entirely once a result arrives.
     */
    private var thinkingBubble: LinearLayout? = null
    private var thinkingText: TextView? = null
    private val thinkingLines = ArrayDeque<String>()

    /** Visible trace lines before the oldest are dropped; keeps the box a constant height. */
    private val THINKING_MAX_LINES = 4

    /** Collapsed height of the brain-output bubble; tap to expand the full text. */
    private val BRAIN_COLLAPSED_LINES = 2

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

        progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progress = 0
            visibility = View.GONE
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        root.addView(progressView)

        chatScroll = ScrollView(this).apply { isFillViewport = true }
        chatLog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        chatScroll.addView(chatLog)
        root.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        localPreviewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(Color.WHITE, Color.rgb(183, 220, 198), dp(18))
            visibility = View.GONE
        }
        root.addView(localPreviewPanel, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

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
        actionButton = Button(this).apply {
            text = "发送"
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(31, 105, 85), Color.rgb(31, 105, 85), dp(18))
            setOnClickListener { if (actionBusy) cancelQuery() else submitQuery() }
        }
        composer.addView(actionButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        root.addView(composer)
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
        if (activeLocalPreview != null) {
            append("请先取消或撤销当前临时预览，再更换素材")
            return
        }
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
            input.setText("当前模板拼接")
            taskStatus = "ready_for_edit"
            persistSessionState()
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
        mediaSummary.text = "已选择 ${selectedUris.size} 个素材：${selectedUris.joinToString() }"
        renderUriPreviews(selectedUris)
        persistSessionState()
        append("已载入 ${selectedUris.size} 个素材")
    }

    private var layoutContext: JSONObject? = null

    private fun _preprocessAssets_UNUSED() {
        if (selectedUris.isEmpty() || worker != null) return
        cancelled.set(false)
        setBusy(true)
        state.text = "前处理 | 正在读取素材和运行 VLM"
        worker = Thread({
            runCatching {
                val prepared = LivePhotoPreprocessor(this).prepare(
                    selectedUris.toList(),
                    cancelled = { cancelled.get() },
                    progress = { message -> ui { updateProgressBar(message) } }
                )
preparedAssets = prepared
                    resetProgressBar()
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
        librarySyncing = true
        libraryIndexDialogDismissed = false
        val db = LibraryDatabase(this)
        libraryTotal = 0
        libraryReady = 0
        runCatching {
            val countCursor = db.readableDatabase.rawQuery("SELECT COUNT(*), SUM(CASE WHEN semantic_json LIKE '%\"ready\"%' THEN 1 ELSE 0 END) FROM assets", null)
            if (countCursor.moveToFirst()) {
                libraryTotal = countCursor.getInt(0)
                libraryReady = countCursor.getInt(1)
            }
            countCursor.close()
        }
        if (libraryTotal > libraryReady && libraryTotal > 0) showIndexingProgress()
        startForegroundService(Intent(this, AgentKeepAliveService::class.java))
        Thread({
            runCatching {
                LibraryIndexer(this).sync({ cancelled.get() }) { message ->
                    libraryProgressText = message
                    ui {
                        state.text = "素材库索引 | $message"
                        // The pre-sync counters only describe rows already in SQLite. A newly
                        // added MediaStore Live Photo is absent from those counters, so the old
                        // `libraryTotal > libraryReady` check showed no dialog even while QNN VLM
                        // was genuinely analysing it in the background. The first real indexer
                        // callback is the authoritative signal that work exists.
                        if (libraryIndexDialog == null && !libraryIndexDialogDismissed) {
                            showIndexingProgress()
                        }
                        updateIndexingProgress(message)
                    }
                }
            }.onSuccess { report ->
                librarySyncing = false
                libraryPending = report.pending
                ui {
                    dismissIndexingProgress()
                    if (!report.skipped) {
                        append("素材库同步完成：新增/更新 ${report.changed} 条，删除 ${report.removed} 条")
                    }
                    if (report.pending > 0) {
                        append("注意：${report.pending}/${report.total} 个素材未能完成解析，" +
                            "这些素材不会进入检索结果")
                        state.text = "就绪 | 素材库 ${report.total - report.pending}/${report.total} 已解析"
                    } else {
                        state.text = "就绪 | 素材库 ${report.total} 个素材已全部解析"
                    }
                    stopService(Intent(this, AgentKeepAliveService::class.java))
                }
            }.onFailure { error ->
                librarySyncing = false
                ui {
                    dismissIndexingProgress()
                    append("素材库索引暂不可用：${error.message}")
                    state.text = "就绪"
                    stopService(Intent(this, AgentKeepAliveService::class.java))
                }
            }
        }, "library-indexer").start()
    }

    private fun showIndexingProgress() {
        if (libraryIndexDialog != null && libraryIndexDialog!!.isShowing) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("VLM 素材分析进度")
        builder.setMessage("正在分析素材 ${libraryReady}/${libraryTotal}...")
        builder.setCancelable(false)
        builder.setPositiveButton("后台运行") { dialog, _ ->
            libraryIndexDialogDismissed = true
            dialog.dismiss()
            libraryIndexDialog = null
        }
        libraryIndexDialog = builder.show()
    }

    private fun updateIndexingProgress(message: String) {
        runCatching {
            val match = Regex("""(\d+)/(\d+)""").find(message)
            if (match != null) {
                libraryTotal = match.groupValues[2].toIntOrNull() ?: libraryTotal
                libraryReady = match.groupValues[1].toIntOrNull() ?: libraryReady
            }
        }
        val dialog = libraryIndexDialog ?: return
        dialog.setMessage("正在分析素材 ${libraryReady}/${libraryTotal}\n\n$message")
    }

    private fun dismissIndexingProgress() {
        libraryIndexDialog?.dismiss()
        libraryIndexDialog = null
    }

    private fun _chooseTemplate_UNUSED() {
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
        // Drop selections that no longer exist (e.g. replaced away) so a stale id can never be
        // passed to the planner.
        selectedAssetIds.retainAll(assets.map { it.assetId }.toSet())
        assets.forEachIndexed { index, asset ->
            val checked = asset.assetId in selectedAssetIds
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(218, 224, 216))
                asset.imageFile?.let { file ->
                    BitmapFactory.decodeFile(file.absolutePath)?.let(::setImageBitmap)
                }
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(
                    if (checked) Color.rgb(206, 232, 214) else Color.rgb(243, 247, 243),
                    if (checked) Color.rgb(74, 158, 106) else Color.rgb(206, 220, 211),
                    dp(16)
                )
                setPadding(dp(5), dp(5), dp(5), dp(5))
                addView(image, LinearLayout.LayoutParams(dp(122), dp(80)))
                addView(TextView(this@AgentActivity).apply {
                    text = buildString {
                        append("${index + 1} · ")
                        append(if (checked) "已选中" else if (asset.semantic.optBoolean("ready")) "VLM" else "待处理")
                    }
                    textSize = 11f
                    setTextColor(if (checked) Color.rgb(23, 92, 52) else Color.rgb(26, 61, 52))
                    setTypeface(typeface, if (checked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                })
                setOnClickListener { toggleAssetSelection(asset.assetId) }
            }
            assetStrip.addView(card, LinearLayout.LayoutParams(dp(132), dp(112)).apply {
                rightMargin = dp(8)
            })
        }
        assetStripScroll.visibility = if (assets.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Taps toggle membership; the strip redraws so the highlight stays in sync.
     *
     * Selection is intentionally transient within a turn's UI but persisted with the session,
     * so rotating or re-entering the activity does not silently drop what the user pointed at.
     */
    private fun toggleAssetSelection(assetId: String) {
        if (actionBusy) {
            append("任务执行中，暂时不能更改选中素材")
            return
        }
        if (!selectedAssetIds.remove(assetId)) selectedAssetIds += assetId
        renderPreparedPreviews(preparedAssets)
        persistSessionState()
        state.text = if (selectedAssetIds.isEmpty()) {
            modelState()
        } else {
            "已选中 ${selectedAssetIds.size} 个素材 | 接下来的指令只作用于它们"
        }
    }

    private fun selectTemplate(template: JSONObject) {
        selectedTemplate = template
        layoutContext = MobileTemplateParser.parse(template, preparedAssets)
        input.setText("当前模板拼接")
        templatePanel.visibility = View.GONE
        templateScroll.visibility = View.GONE
        taskStatus = "ready_for_edit"
        persistSessionState()
        state.text = "${template.optString("id")} ${template.optString("name")} | 可微调画布后生成"
        append("已选择模板：${template.optString("id")} ${template.optString("name")}")
    }

    private fun _generateTemplate_UNUSED() {
        if (layoutContext == null) {
            append("请先分析素材并选择模板，或打开画布完成布局")
            return
        }
        input.setText("当前模板拼接")
        submitQuery()
    }

    private fun _importLivePhotoLibrary_UNUSED() {
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

    private fun _openCanvas_UNUSED() {
        if (selectedUris.isEmpty() || worker != null) return
        startActivityForResult(Intent(this, CanvasEditorActivity::class.java).apply {
            putStringArrayListExtra("uris", ArrayList(selectedUris.map(Uri::toString)))
            layoutContext?.let { putExtra("layout_context", it.toString()) }
        }, canvasRequest)
    }

    private fun submitQuery() {
        if (worker != null) return
        if (activeLocalPreview != null) {
            append("请先完成当前临时预览：未确认时可取消，确认后可撤销；预览不会写回源媒体")
            return
        }
        val query = input.text.toString().trim()
        if (query.isEmpty()) {
            append("请输入处理要求")
            return
        }
        // Gated on the indexer, not on a row count: a query is only meaningful once every
        // gallery live photo carries a current-version parse. Newly added photos therefore
        // have to finish analysis before the agent will act on them.
        if (selectedUris.isEmpty() && librarySyncing) {
            val detail = if (libraryTotal > 0) "（$libraryReady/$libraryTotal）" else ""
            append("素材库正在解析动作语义$detail，完成后即可交互")
            return
        }
        if (selectedUris.isEmpty() && LibraryDatabase(this).count() == 0) {
            append("素材库为空，请先从系统相册选择素材或等待索引完成")
            return
        }
        cancelled.set(false)
        lastQuery = query
        taskStatus = "running"
        persistSessionState()
        append("你\n$query")
        input.text.clear()
        setBusy(true)
        resetProgressBar()
        // Show the spinning bubble the instant the message is sent. The first onProgress can be
        // 5-12s away while the on-device brain reads the sentence, and users read silence as a
        // dead app -- the circle must turn from the trigger, not from the first callback.
        thinking("正在理解你的请求…")
        state.text = "运行中 | 前处理 -> 规划 -> vivo SDK 执行"
        val runner = MobileAgentRunner(this)
        val generation = sessionGeneration
        worker = Thread({
            runner.run(query, selectedUris.toList(), layoutContext, cancelled, object : MobileAgentRunner.Listener {
                override fun onProgress(message: String) = uiFor(generation) { updateProgressBar(message); thinking(message) }
                override fun onAssetsPrepared(uris: List<Uri>, assets: List<PreparedAsset>) = uiFor(generation) {
                    // Union, not overwrite. asset_replace repoints the cast mid-plan but cannot
                    // reach this Activity's selectedUris, so a plain assignment would stamp the
                    // pre-replacement URIs back on top every turn -- and the replaced asset would
                    // resurrect on session restore or re-analysis.
                    val merged = LinkedHashSet(selectedUris)
                    merged += uris
                    merged += assets.map { it.sourceUri }
                    selectedUris.clear()
                    selectedUris += merged
                    preparedAssets = assets
                    taskStatus = "assets_prepared"
                    mediaSummary.text = "已准备 ${assets.size} 个素材，可继续沿用本轮素材"
                    renderPreparedPreviews(assets)
                    persistSessionState()
                }
                override fun onAssetsUpdated(
                    assets: List<PreparedAsset>,
                    replacedIds: Map<String, String>
                ) = uiFor(generation) {
                    preparedAssets = assets
                    // Move the tap-selection onto the replacement: the user pointed at a slot, and
                    // the next instruction should keep addressing that slot.
                    if (replacedIds.isNotEmpty()) {
                        val moved = linkedSetOf<String>()
                        selectedAssetIds.forEach { id -> moved += (replacedIds[id] ?: id) }
                        selectedAssetIds.clear()
                        selectedAssetIds += moved
                    }
                    renderPreparedPreviews(assets)
                    persistSessionState()
                }
                override fun onIntentTrace(trace: BrainTrace) = uiFor(generation) {
                    // Intent runs on its own throwaway planner and its plan is consumed for search
                    // params then discarded, so this is the only place its raw output is captured.
                    // Logged under INTENT_BRAIN (not PLAN_BRAIN) so the two brain stages never
                    // overwrite each other in the audit -- the survivorship that made
                    // intent_returned_tools unmeasurable (planner_reliability_plan.md §3.4).
                    logAudit("INTENT_BRAIN", JSONObject()
                        .put("query", query)
                        .put("source", trace.source)
                        .put("parsed", trace.parsed)
                        .put("prompt_chars", trace.promptChars)
                        .put("elapsed_ms", trace.elapsedMs)
                        .put("strategy", trace.strategy)
                        .put("reasoning_output", trace.reasoningOutput)
                        .put("reasoning_elapsed_ms", trace.reasoningElapsedMs)
                        .put("format_elapsed_ms", trace.formatElapsedMs)
                        .put("raw_output", trace.rawOutput))
                }
                override fun onRuntimeRoute(route: SemanticRoute, trace: BrainTrace) {
                    // Serialize on the worker thread before later state transitions can replace
                    // the route/trace objects queued for UI audit.
                    val payload = JSONObject()
                        .put("query", query)
                        .put("source", trace.source)
                        .put("parsed", trace.parsed)
                        .put("elapsed_ms", trace.elapsedMs)
                        .put("raw_output", trace.rawOutput)
                        .put("route", semanticRouteJson(route))
                    uiFor(generation) {
                        logAudit("ROUTE_BRAIN", payload)
                        val visible = route.steps.joinToString(" → ") { it.family.name }
                        if (visible.isNotBlank()) thinking("任务路线：$visible")
                    }
                }
                override fun onRuntimeBrainTrace(stage: String, trace: BrainTrace) {
                    val payload = JSONObject()
                        .put("query", query)
                        .put("stage", stage)
                        .put("source", trace.source)
                        .put("parsed", trace.parsed)
                        .put("prompt_chars", trace.promptChars)
                        .put("elapsed_ms", trace.elapsedMs)
                        .put("strategy", trace.strategy)
                        .put("reasoning_output", trace.reasoningOutput)
                        .put("reasoning_elapsed_ms", trace.reasoningElapsedMs)
                        .put("format_elapsed_ms", trace.formatElapsedMs)
                        .put("raw_output", trace.rawOutput)
                    uiFor(generation) { logAudit("RUNTIME_BRAIN", payload) }
                }
                override fun onToolCatalog(
                    routeStep: SemanticRouteStep,
                    decision: ToolCatalogDecision
                ) {
                    val payload = JSONObject()
                        .put("query", query)
                        .put("route_step_id", routeStep.stepId)
                        .put("family", routeStep.family.name)
                        .put("available_count", decision.available.size)
                        .put("available", JSONArray(decision.available.map { spec ->
                            JSONObject().put("id", spec.id).put("description", spec.description)
                        }))
                        .put("excluded", JSONObject(decision.excluded))
                    uiFor(generation) { logAudit("TOOL_CATALOG", payload) }
                }
                override fun onRuntimePlan(plan: RuntimePlan) {
                    val payload = runtimePlanJson(plan).put("query", query)
                    uiFor(generation) { logAudit("PLAN_VERSION", payload) }
                }
                override fun onRuntimeGraph(compilation: AgentDagCompilation) {
                    val payload = AgentDagJson.toJson(compilation).put("query", query)
                    uiFor(generation) { logAudit("PLAN_GRAPH", payload) }
                }
                override fun onRuntimeContentGraph(snapshot: AgentContentGraphSnapshot) {
                    val payload = AgentContentGraphJson.toJson(snapshot).put("query", query)
                    uiFor(generation) { logAudit("CONTENT_GRAPH", payload) }
                }
                override fun onRuntimeGoal(goal: AgentGoalIr, result: AgentCompileResult) {
                    val payload = AgentCompileResultJson.toJson(result)
                        .put("query", query)
                        .put("goal_id", goal.goalId)
                    uiFor(generation) { logAudit("GOAL_IR", payload) }
                }
                override fun onLocalPreview(interaction: AndroidLocalPreviewInteraction) {
                    if (generation != sessionGeneration) {
                        releaseLocalPreviewInteraction(interaction)
                        return
                    }
                    ui {
                        if (generation == sessionGeneration && !isFinishing && !isDestroyed) {
                            showLocalPreview(interaction)
                        } else {
                            releaseLocalPreviewInteraction(interaction)
                        }
                    }
                }
                override fun onStateDelta(
                    stepId: String,
                    delta: StateDelta,
                    runtimeState: AgentRuntimeState
                ) {
                    val payload = JSONObject()
                        .put("query", query)
                        .put("step_id", stepId)
                        .put("delta", stateDeltaJson(delta))
                        .put("state", JSONObject()
                            .put("turn_id", runtimeState.turnId)
                            .put("plan_version", runtimeState.plan?.version ?: 0)
                            .put("asset_ids", JSONArray(runtimeState.sourceAssets.map { it.assetId }))
                            .put("selected_asset_ids", JSONArray(runtimeState.selectedAssetIds))
                            .put("layout_version", runtimeState.layoutVersion)
                            .put("artifact_ids", JSONArray(runtimeState.artifacts.keys.toList()))
                            .put("executed_count", runtimeState.executedCount)
                            .put("replan_count", runtimeState.replanCount)
                            .put("status", runtimeState.status.name.lowercase()))
                    uiFor(generation) { logAudit("STATE_DELTA", payload) }
                }
                override fun onRuntimeTerminal(runtimeState: AgentRuntimeState, message: String) {
                    val payload = JSONObject()
                        .put("query", query)
                        .put("turn_id", runtimeState.turnId)
                        .put("plan_version", runtimeState.plan?.version ?: 0)
                        .put("status", runtimeState.status.name.lowercase())
                        .put("message", message)
                        .put("published_uris", JSONArray(runtimeState.publishedUris.map(Uri::toString)))
                        .put("executed_count", runtimeState.executedCount)
                        .put("replan_count", runtimeState.replanCount)
                    uiFor(generation) { logAudit("TERMINAL", payload) }
                }
                override fun onPlan(plan: MobilePlan) = uiFor(generation) {
                    lastPlan = plan.toJson()
                    lastToolResults.clear()
                    persistSessionState()
                    val trace = plan.brainTrace
                    // A trace can accompany a fallback plan when the brain ran but failed to parse;
                    // the plan is still the tables', so attribution follows parsed, not presence.
                    val brainProduced = trace?.parsed == true
                    val source = if (brainProduced) trace!!.source else "fallback"
                    logAudit("PLAN_BRAIN", JSONObject()
                        .put("query", query)
                        .put("source", trace?.source ?: "none")
                        .put("parsed", brainProduced)
                        .put("prompt_chars", trace?.promptChars ?: 0)
                        .put("elapsed_ms", trace?.elapsedMs ?: 0)
                        .put("strategy", trace?.strategy ?: BuildConfig.PLANNER_EXPERIMENT)
                        .put("reasoning_output", trace?.reasoningOutput ?: "")
                        .put("reasoning_elapsed_ms", trace?.reasoningElapsedMs ?: 0)
                        .put("format_elapsed_ms", trace?.formatElapsedMs ?: 0)
                        .put("raw_output", trace?.rawOutput ?: "")
                        .put("selected_asset_ids", JSONArray(selectedAssetIds.toList())))
                    logAudit("PLAN_FINAL", JSONObject()
                        .put("query", query)
                        .put("intent", plan.intent)
                        .put("source", source)
                        .put("tool_calls", JSONArray(plan.toolCalls.map { it.toJson() }))
                        .put("clarification", plan.clarification ?: JSONObject.NULL))
                    val steps = plan.toolCalls.joinToString("、") { it.tool }
                    if (steps.isEmpty() && plan.clarification == null) {
                        append("我暂时没有需要执行的编辑动作")
                    } else if (steps.isEmpty()) {
                        // The plan carries a spoken answer (capability / chat); onFinished renders
                        // it as the reply bubble, so nothing is appended here.
                    } else {
                        thinking("规划意图：${plan.intent}" +
                            (if (brainProduced) "（${trace!!.source} ${trace.elapsedMs}ms）" else "（关键词兜底）"))
                        // Show the brain's own words, collapsed. This is the difference between an
                        // agent whose reasoning can be inspected and one that is a black box.
                        trace?.let { brain ->
                            val visible = listOf(
                                brain.reasoningOutput.takeIf { it.isNotBlank() }
                                    ?.let { "自由规划：\n${it.trim()}" },
                                brain.rawOutput.takeIf { it.isNotBlank() }
                                    ?.let { "最终结构：\n${it.trim()}" }
                            ).filterNotNull().joinToString("\n\n")
                            visible.takeIf { it.isNotBlank() }?.let { thinkingBrain(it) }
                        }
                        plan.toolCalls.forEachIndexed { index, call ->
                            thinking("步骤 ${index + 1}/${plan.toolCalls.size} ${call.tool}" +
                                (call.reason.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""))
                        }
                    }
                }
                override fun onToolCall(index: Int, total: Int, call: MobileToolCall) = uiFor(generation) {
                    logAudit("TOOL_CALL", JSONObject()
                        .put("query", query)
                        .put("index", index + 1)
                        .put("total", total)
                        .put("tool", call.tool)
                        .put("arguments", call.arguments)
                        .put("reason", call.reason))
                }
                override fun onResult(result: MobileToolResult) = uiFor(generation) {
                    logAudit("TOOL_RESULT", JSONObject()
                        .put("query", query)
                        .put("tool", result.tool)
                        .put("success", result.success)
                        .put("payload", JSONObject(result.payload.toString())))
                    when (result.tool) {
                        "template_collage", "layout_swap", "asset_replace" -> {
                            // Only adopt a layout that actually carries slots; asset_replace emits
                            // an empty object when there was no prior collage, which must not
                            // clobber a good layout.
                            result.payload.optJSONObject("layout_context")
                                ?.takeIf { it.optJSONArray("items") != null }
                                ?.let { layoutContext = it }
                            result.payload.optString("template_id").takeIf { it.isNotBlank() }?.let {
                                selectedTemplate = JSONObject().put("id", it)
                            }
                        }
                    }
                    if (result.tool == "asset_replace" && result.success) {
                        logAudit("ASSET_SWAP", JSONObject()
                            .put("query", query)
                            .put("search_query", result.payload.optString("search_query"))
                            .put("old_asset_id", result.payload.optString("replaced_asset_id"))
                            .put("new_asset_id", result.payload.optString("new_asset_id"))
                            .put("display_name", result.payload.optString("display_name"))
                            .put("match_score", result.payload.optDouble("match_score"))
                            .put("candidates", result.payload.optJSONArray("candidates") ?: JSONArray()))
                    }
                    lastToolResults += JSONObject()
                        .put("tool", result.tool)
                        .put("success", result.success)
                        .put("payload", result.payload)
                    persistSessionState()
                    thinking(if (result.success) "${result.tool} 已完成" else
                        "${result.tool} 失败：${result.payload.optString("message")}")
                }
                override fun onFinished(uri: Uri?, message: String) = uiFor(generation) {
                    resetProgressBar()
                    lastResult = uri
                    lastResultMessage = message
                    taskStatus = if (uri == null && message == "用户已取消") "cancelled" else
                        if (uri == null) "completed_without_output" else "completed"
                    persistSessionState()
                    state.text = if (uri == null) message else "完成 | 已写入系统相册"
                    if (uri == null) append(message) else appendResult(uri, message)
                    finishWorker()
                }
                override fun onError(message: String) = uiFor(generation) {
                    resetProgressBar()
                    Log.e("LivePhotoAgent", message)
                    taskStatus = "failed"
                    persistSessionState(message)
                    state.text = "失败"
                    append("失败：$message")
                    finishWorker()
                }
            }, preparedAssets.ifEmpty { null }, sessionId, selectedAssetIds.toList())
        }, "live-photo-agent")
        worker!!.start()
    }

    private fun cancelQuery() {
        cancelled.set(true)
        resetProgressBar()
        append("正在请求中止当前任务")
        state.text = "正在中止"
        worker?.interrupt()
    }

    private fun showLocalPreview(interaction: AndroidLocalPreviewInteraction) {
        clearActiveLocalPreview()
        val artifact = runCatching { interaction.snapshot() }.getOrElse { error ->
            append("临时预览不可用：${error.message}")
            return
        }
        activeLocalPreview = interaction
        localPreviewPanel.removeAllViews()
        localPreviewPanel.visibility = View.VISIBLE
        val status = TextView(this).apply {
            text = "局部${if (artifact.effectKind == "blur") "模糊" else "调色"} · " +
                "仅临时预览，尚未写回"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(26, 61, 52))
        }
        localPreviewPanel.addView(status)
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.rgb(239, 242, 238))
            setImageBitmap(artifact.previewBitmap)
        }
        localPreviewPanel.addView(preview, LinearLayout.LayoutParams(-1, dp(230)).apply {
            topMargin = dp(6)
        })
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        var showingPreview = true
        val toggle = button("查看原图") {}
        toggle.setOnClickListener {
            showingPreview = !showingPreview
            preview.setImageBitmap(if (showingPreview) artifact.previewBitmap else artifact.sourceBitmap)
            toggle.text = if (showingPreview) "查看原图" else "查看预览"
        }
        val confirm = button("确认预览") {}
        val cancelPreview = button("取消") {}
        val undo = button("撤销预览") {}
        undo.visibility = View.GONE
        controls.addView(toggle, LinearLayout.LayoutParams(0, dp(42), 1f))
        controls.addView(confirm, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(5)
        })
        controls.addView(cancelPreview, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(5)
        })
        controls.addView(undo, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(5)
        })
        localPreviewPanel.addView(controls, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })

        confirm.setOnClickListener {
            runCatching { interaction.confirm() }.onSuccess { accepted ->
                status.text = "已确认临时预览 · 写回仍未授权，可随时撤销"
                confirm.visibility = View.GONE
                cancelPreview.visibility = View.GONE
                undo.visibility = View.VISIBLE
                logLocalPreviewReview(accepted)
            }.onFailure { append("确认预览失败：${it.message}") }
        }
        cancelPreview.setOnClickListener {
            preview.setImageBitmap(artifact.sourceBitmap)
            runCatching {
                val cancelledPreview = interaction.cancel()
                logLocalPreviewReview(cancelledPreview)
                interaction.release()
            }.onFailure {
                releaseLocalPreviewInteraction(interaction)
                append("取消预览失败：${it.message}")
            }
            activeLocalPreview = null
            status.text = "已取消临时预览 · 源媒体未改变"
            toggle.visibility = View.GONE
            confirm.visibility = View.GONE
            cancelPreview.visibility = View.GONE
        }
        undo.setOnClickListener {
            preview.setImageBitmap(artifact.sourceBitmap)
            runCatching {
                val undone = interaction.undo()
                logLocalPreviewReview(undone)
                interaction.release()
            }.onFailure {
                releaseLocalPreviewInteraction(interaction)
                append("撤销预览失败：${it.message}")
            }
            activeLocalPreview = null
            status.text = "已撤销临时预览 · 源媒体未改变"
            toggle.visibility = View.GONE
            undo.visibility = View.GONE
        }
        logAudit("LOCAL_PREVIEW_READY", JSONObject()
            .put("artifact_id", artifact.artifactId)
            .put("effect_kind", artifact.effectKind)
            .put("renderer_backend", artifact.rendererBackendId)
            .put("frame_index", artifact.frameIndex)
            .put("review_state", artifact.lifecycle.state.name)
            .put("writeback", false))
    }

    private fun logLocalPreviewReview(artifact: AndroidTemporaryLocalPreviewArtifact) {
        logAudit("LOCAL_PREVIEW_REVIEW", JSONObject()
            .put("artifact_id", artifact.artifactId)
            .put("review_state", artifact.lifecycle.state.name)
            .put("revision", artifact.lifecycle.revision)
            .put("writeback_authorized", artifact.lifecycle.writebackAuthorized)
            .put("source_media_modified", false))
    }

    private fun clearActiveLocalPreview() {
        val interaction = activeLocalPreview ?: run {
            if (::localPreviewPanel.isInitialized) {
                localPreviewPanel.removeAllViews()
                localPreviewPanel.visibility = View.GONE
            }
            return
        }
        releaseLocalPreviewInteraction(interaction)
        activeLocalPreview = null
        if (::localPreviewPanel.isInitialized) {
            localPreviewPanel.removeAllViews()
            localPreviewPanel.visibility = View.GONE
        }
    }

    private fun releaseLocalPreviewInteraction(interaction: AndroidLocalPreviewInteraction) {
        runCatching {
            when (interaction.snapshot().lifecycle.state) {
                LocalPreviewReviewState.PENDING_REVIEW -> interaction.cancel()
                LocalPreviewReviewState.ACCEPTED_FOR_REVIEW -> interaction.undo()
                LocalPreviewReviewState.CANCELLED,
                LocalPreviewReviewState.UNDONE -> Unit
            }
            interaction.release()
        }
    }

    private fun finishWorker() {
        worker = null
        // The trace has served its purpose: the result bubble replaces it and the full text is
        // already in interaction_events for later diagnosis.
        endThinking()
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
        actionBusy = busy
        if (!::actionButton.isInitialized) return
        actionButton.text = if (busy) "中止" else "发送"
        actionButton.background = roundedBackground(
            if (busy) Color.rgb(180, 60, 60) else Color.rgb(31, 105, 85),
            if (busy) Color.rgb(180, 60, 60) else Color.rgb(31, 105, 85),
            dp(18)
        )
        selectButton.isEnabled = !busy
        sessionButton.isEnabled = !busy
    }

    private fun openUri(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: if (uri.toString().contains(".jpg")) {
            "image/jpeg"
        } else {
            "video/mp4"
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { append("无法打开结果文件：${it.message ?: it::class.java.simpleName}") }
    }

    private fun updateProgressBar(message: String) {
        if (!::progressView.isInitialized) return
        val percent = extractProgressPercent(message)
        if (percent >= 0) {
            state.text = message
            progressView.isIndeterminate = false
            progressView.progress = percent
            progressView.visibility = View.VISIBLE
        } else {
            state.text = message
            progressView.isIndeterminate = true
            progressView.visibility = View.VISIBLE
        }
    }

    private fun extractProgressPercent(message: String): Int {
        val match = Regex("""(\d{1,3})%""").find(message) ?: Regex("""导出中(\d{1,3})""").find(message) ?: return -1
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: -1
    }

    private fun resetProgressBar() {
        if (::progressView.isInitialized) {
            progressView.visibility = View.GONE
            progressView.progress = 0
            progressView.isIndeterminate = false
        }
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

    /**
     * Persists a structured audit record.
     *
     * Separate from [thinking]: that one is user-facing progress text and gets capped/dropped,
     * while these are the machine-readable trail -- what the brain actually said, which arguments
     * a tool received, what got swapped for what. Without them a wrong plan can only be guessed at
     * from the step list.
     */
    private fun logAudit(kind: String, payload: JSONObject) {
        runCatching { LibraryDatabase(this).logEvent(kind, payload.toString()) }
    }

    private fun semanticRouteJson(route: SemanticRoute): JSONObject = JSONObject()
        .put("user_goal", route.userGoal)
        .put("reply", route.reply ?: JSONObject.NULL)
        .put("clarification", route.clarification ?: JSONObject.NULL)
        .put("steps", JSONArray(route.steps.map { step ->
            JSONObject()
                .put("step_id", step.stepId)
                .put("family", step.family.name)
                .put("instruction", step.instruction)
                .put("requested_count", step.requestedCount ?: JSONObject.NULL)
                .put("required_media", step.requiredMedia?.name ?: JSONObject.NULL)
        }))

    private fun runtimePlanJson(plan: RuntimePlan): JSONObject = JSONObject()
        .put("plan_id", plan.planId)
        .put("version", plan.version)
        .put("parent_version", plan.parentVersion ?: JSONObject.NULL)
        .put("revision_reason", plan.revisionReason)
        .put("route", semanticRouteJson(plan.route))
        .put("steps", JSONArray(plan.steps.map { step ->
            JSONObject()
                .put("step_id", step.stepId)
                .put("family", step.family.name)
                .put("tool", step.tool)
                .put("arguments", JSONObject(step.arguments.toString()))
                .put("inputs", JSONArray(step.inputs.map { input ->
                    when (input) {
                        is AgentInputRef.AssetSet -> JSONObject()
                            .put("type", "asset_set")
                            .put("asset_ids", JSONArray(input.assetIds))
                        is AgentInputRef.Artifact -> JSONObject()
                            .put("type", "artifact")
                            .put("artifact_id", input.artifactId)
                        is AgentInputRef.Layout -> JSONObject()
                            .put("type", "layout")
                            .put("version", input.version)
                    }
                }))
                .put("status", step.status.name.lowercase())
                .put("idempotency_key", step.idempotencyKey)
        }))

    private fun stateDeltaJson(delta: StateDelta): JSONObject = when (delta) {
        is StateDelta.AddAssets -> JSONObject()
            .put("type", "add_assets")
            .put("asset_ids", JSONArray(delta.assets.map { it.assetId }))
        is StateDelta.ReplaceAsset -> JSONObject()
            .put("type", "replace_asset")
            .put("old_asset_id", delta.oldAssetId)
            .put("new_asset_id", delta.asset.assetId)
        is StateDelta.RemapSelection -> JSONObject()
            .put("type", "remap_selection")
            .put("replacements", JSONObject(delta.replacements))
        is StateDelta.AddArtifact -> JSONObject()
            .put("type", "add_artifact")
            .put("artifact_id", delta.artifact.artifactId)
            .put("kind", delta.artifact.kind.name)
            .put("validated", delta.artifact.validated)
        is StateDelta.ValidateArtifact -> JSONObject()
            .put("type", "validate_artifact").put("artifact_id", delta.artifactId)
        is StateDelta.UpdateLayout -> JSONObject()
            .put("type", "update_layout")
            .put("template_id", delta.layout.optString("template_id"))
            .put("slot_count", delta.layout.optJSONArray("items")?.length() ?: 0)
        is StateDelta.MarkStepCompleted -> JSONObject()
            .put("type", "step_completed").put("step_id", delta.stepId)
        is StateDelta.MarkStepFailed -> JSONObject()
            .put("type", "step_failed").put("step_id", delta.stepId).put("message", delta.message)
        is StateDelta.PublishArtifact -> JSONObject()
            .put("type", "publish_artifact")
            .put("artifact_id", delta.artifactId)
            .put("uri", delta.uri.toString())
        is StateDelta.SetStatus -> JSONObject()
            .put("type", "set_status")
            .put("status", delta.status.name)
            .put("message", delta.message ?: JSONObject.NULL)
        is StateDelta.Batch -> JSONObject()
            .put("type", "batch")
            .put("deltas", JSONArray(delta.deltas.map(::stateDeltaJson)))
    }

    /**
     * Creates the thinking bubble on first use and appends one line to it.
     *
     * Lines are capped and the oldest are dropped, so the box keeps a constant height while a
     * long multi-step plan streams through it.
     */
    private fun thinking(message: String) {
        if (!::chatLog.isInitialized || message.isBlank()) return
        runCatching { LibraryDatabase(this).logEvent("TRACE", message) }

        val bubble = thinkingBubble ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(Color.rgb(246, 248, 246), Color.rgb(216, 224, 218), dp(16))
            addView(ProgressBar(this@AgentActivity, null, android.R.attr.progressBarStyleSmall),
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(9) })
            addView(TextView(this@AgentActivity).apply {
                textSize = 11.5f
                setTextColor(Color.rgb(126, 138, 130))
                setLineSpacing(dp(2).toFloat(), 1f)
                maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
                maxLines = THINKING_MAX_LINES
                ellipsize = android.text.TextUtils.TruncateAt.START
            }, LinearLayout.LayoutParams(-2, -2))
            thinkingBubble = this
            chatLog.addView(this, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.START
                bottomMargin = dp(8)
            })
        }

        thinkingLines.addLast(message)
        while (thinkingLines.size > THINKING_MAX_LINES) thinkingLines.removeFirst()
        thinkingText = bubble.getChildAt(1) as? TextView
        thinkingText?.text = thinkingLines.joinToString("\n") { "· $it" }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Removes the thinking bubble; the trace survives only in interaction_events. */
    private fun endThinking() {
        thinkingBubble?.let { chatLog.removeView(it) }
        thinkingBubble = null
        thinkingText = null
        thinkingLines.clear()
    }

    /**
     * Collapsible bubble carrying the planner model's verbatim output.
     *
     * Deliberately separate from [thinking] so it survives [endThinking]: the reasoning is the
     * thing worth re-reading after a wrong result, and it is already persisted as PLAN_BRAIN.
     * Collapsed to a couple of lines by default -- raw model output is noisy and would otherwise
     * bury the actual plan.
     */
    private fun thinkingBrain(raw: String) {
        if (!::chatLog.isInitialized || raw.isBlank()) return
        val label = TextView(this).apply {
            text = "大脑原文（点击展开）"
            textSize = 11f
            setTextColor(Color.rgb(126, 138, 130))
            setPadding(dp(12), dp(7), dp(12), dp(2))
        }
        val body = TextView(this).apply {
            text = raw.trim()
            textSize = 10.5f
            setTextColor(Color.rgb(110, 122, 114))
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(dp(12), dp(2), dp(12), dp(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            maxLines = BRAIN_COLLAPSED_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.rgb(243, 246, 243), Color.rgb(210, 220, 212), dp(14))
            addView(label)
            addView(body)
            setOnClickListener {
                val expanded = body.maxLines != BRAIN_COLLAPSED_LINES
                body.maxLines = if (expanded) BRAIN_COLLAPSED_LINES else Int.MAX_VALUE
                body.ellipsize = if (expanded) android.text.TextUtils.TruncateAt.END else null
                label.text = if (expanded) "大脑原文（点击展开）" else "大脑原文（点击收起）"
            }
        }
        chatLog.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.START
            bottomMargin = dp(8)
        })
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
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
        sessionGeneration++
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
        saved.optJSONObject("runtime_checkpoint")?.let { checkpoint ->
            if (checkpoint.optString("status") == "running") {
                // The first runtime-v2 release resumes only at a user decision, never by silently
                // repeating an SDK side effect after process death. Keep the checkpoint for audit
                // and report the interruption explicitly.
                taskStatus = "interrupted"
                append("上次端侧工具链在步骤边界外中断，系统未自动重复执行；可重新发送指令继续。")
            }
        }

        selectedAssetIds.clear()
        saved.optJSONArray("selected_asset_ids")?.let { values ->
            for (index in 0 until values.length()) {
                values.optString(index).takeIf { it.isNotBlank() }?.let(selectedAssetIds::add)
            }
        }

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
        updateActionState(false)
    }

    private fun resetSessionUi() {
        clearActiveLocalPreview()
        selectedUris.clear()
        preparedAssets = emptyList()
        selectedAssetIds.clear()
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
        chatLog.removeAllViews()
        updateActionState(false)
    }

    private fun showSessions(fallbackSessionId: String? = null) {
        showSessionPicker(fallbackSessionId, manageMode = false, checked = BooleanArray(0))
    }

    /**
     * Session chooser with an optional multi-select manage mode for deletion.
     *
     * Manage mode keeps its checked flags across the confirm/cancel round trips so an
     * accidental "取消" on the confirm dialog does not silently drop the selection.
     */
    private fun showSessionPicker(fallbackSessionId: String?, manageMode: Boolean, checked: BooleanArray) {
        val database = LibraryDatabase(this)
        val sessions = database.sessions()
        val flags = if (checked.size == sessions.size) checked else BooleanArray(sessions.size)
        val labels = sessions.map { it.title }.toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle(if (manageMode) "管理对话：勾选后删除" else "选择对话")
        if (manageMode) {
            builder.setMultiChoiceItems(labels, flags) { _, index, isChecked -> flags[index] = isChecked }
        } else {
            builder.setItems(labels) { _, index ->
                startActivity(Intent(this, AgentActivity::class.java).putExtra("session_id", sessions[index].id))
                finish()
            }
        }
        if (manageMode) {
            builder.setPositiveButton("删除所选") { _, _ ->
                confirmDeleteSessions(database, sessions, flags, fallbackSessionId)
            }
            builder.setNeutralButton("返回选择") { _, _ ->
                showSessionPicker(fallbackSessionId, false, BooleanArray(0))
            }
        } else {
            builder.setPositiveButton("新建对话") { _, _ -> newSession() }
            builder.setNeutralButton("管理") { _, _ ->
                showSessionPicker(fallbackSessionId, true, BooleanArray(sessions.size))
            }
        }
        builder.setNegativeButton("取消") { _, _ ->
            fallbackSessionId?.let { openSession(database, it) }
        }
        builder.show()
    }

    private fun confirmDeleteSessions(
        database: LibraryDatabase,
        sessions: List<ChatSession>,
        checked: BooleanArray,
        fallbackSessionId: String?
    ) {
        val targets = sessions.filterIndexed { index, _ -> checked.getOrElse(index) { false } }
        if (targets.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的对话", Toast.LENGTH_SHORT).show()
            showSessionPicker(fallbackSessionId, true, checked)
            return
        }
        val deletingCurrent = targets.any { it.id == sessionId }
        AlertDialog.Builder(this)
            .setTitle("删除 ${targets.size} 个对话？")
            .setMessage("将删除对话记录与本会话的前处理缓存；相册中的结果文件保留。" +
                if (deletingCurrent) "当前对话也在其中，删除后需要重新选择对话。" else "")
            .setPositiveButton("删除") { _, _ ->
                deleteSessions(database, targets, deletingCurrent)
            }
            .setNegativeButton("取消") { _, _ -> showSessionPicker(fallbackSessionId, true, checked) }
            .show()
    }

    /**
     * Removes sessions plus the preprocess copies their saved state points at.
     *
     * Only files under cacheDir/agent-inputs are session-private; agent-edits and album
     * outputs are shared or user-visible and stay on disk.
     */
    private fun deleteSessions(database: LibraryDatabase, targets: List<ChatSession>, deletingCurrent: Boolean) {
        if (deletingCurrent && worker != null) {
            cancelled.set(true)
            worker?.interrupt()
            worker = null
            resetProgressBar()
            endThinking()
            setBusy(false)
        }
        if (deletingCurrent) sessionGeneration++
        val inputsRoot = File(cacheDir, "agent-inputs").absolutePath
        targets.forEach { session ->
            database.sessionPreparedPaths(session.id).forEach { path ->
                val file = File(path)
                if (file.absolutePath.startsWith("$inputsRoot/")) {
                    (file.parentFile?.takeIf { it.name.startsWith("asset-") } ?: file).deleteRecursively()
                }
            }
            database.deleteSession(session.id)
        }
        if (deletingCurrent) {
            sessionId = ""
            resetSessionUi()
            val remaining = database.sessions()
            if (remaining.isEmpty()) {
                openSession(database, database.createSession(), announce = true)
            } else {
                showSessions(null)
            }
        }
    }

    private fun newSession() {
        sessionGeneration++
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
            isClickable = true
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
        val database = LibraryDatabase(this)
        val existing = runCatching { database.sessionState(sessionId) }.getOrDefault(JSONObject())
        val state = JSONObject()
            .put("schema_version", 1)
            .put("task_status", taskStatus)
            .put("last_query", lastQuery)
            .put("selected_uris", JSONArray(selectedUris.map(Uri::toString)))
            .put("selected_asset_ids", JSONArray(selectedAssetIds.toList()))
            .put("prepared_assets", prepared)
            .put("layout_context", layoutContext ?: JSONObject.NULL)
            .put("selected_template", selectedTemplate ?: JSONObject.NULL)
            .put("last_plan", lastPlan ?: JSONObject.NULL)
            .put("last_tool_results", toolResults)
            .put("last_result_uri", lastResult?.toString() ?: "")
            .put("last_result_message", lastResultMessage)
        // MobileAgentLoop checkpoints on the worker thread. UI persistence must carry those
        // fields forward instead of replacing the whole session JSON and erasing recovery data.
        if (existing.has("runtime_state_version")) {
            state.put("runtime_state_version", existing.optInt("runtime_state_version"))
        }
        existing.optJSONObject("runtime_checkpoint")?.let {
            state.put("runtime_checkpoint", JSONObject(it.toString()))
        }
        if (!error.isNullOrBlank()) state.put("last_error", error)
        runCatching { database.saveSessionState(sessionId, state) }
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

    /** Like [ui] but drops the block if the session changed since the worker was launched. */
    private fun uiFor(generation: Int, block: () -> Unit) {
        if (generation != sessionGeneration) return
        ui(block)
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
        clearActiveLocalPreview()
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

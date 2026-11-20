package com.iqonli.ezxlrc

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.iqonli.ezxlrc.data.LyricResult
import com.iqonli.ezxlrc.data.LyricSource
import com.iqonli.ezxlrc.engine.ScriptRepository
import com.iqonli.ezxlrc.engine.ScriptRuntime
import com.iqonli.ezxlrc.util.LyricProcessor
import com.iqonli.ezxlrc.util.LyricTextCodec
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var repository: ScriptRepository
    private var runtime: ScriptRuntime? = null
    private lateinit var contentFrame: FrameLayout
    private lateinit var tabs: TabLayout
    private var webView: WebView? = null
    private var searchEngineAvailable = false

    private lateinit var sourceSpinner: Spinner
    private lateinit var searchStatus: TextView
    private lateinit var searchProgress: ProgressBar
    private lateinit var resultsContainer: LinearLayout
    private lateinit var titleInput: TextInputEditText
    private lateinit var artistInput: TextInputEditText
    private lateinit var albumInput: TextInputEditText
    private lateinit var durationInput: TextInputEditText
    private lateinit var editorInput: TextInputEditText
    private lateinit var intervalInput: TextInputEditText
    private lateinit var segmentationInput: TextInputEditText
    private lateinit var toolStatus: TextView

    private var sources: List<LyricSource> = emptyList()
    private var searchInProgress = false
    private var searchIndex = 0
    private var searchErrorCount = 0
    private var stopSearchRequested = false
    private val collectedResults = mutableListOf<LyricResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashDiagnostics.markStage(this, "MainActivity.onCreate: entered")
        WindowCompat.setDecorFitsSystemWindows(window, true)
        CrashDiagnostics.markStage(this, "MainActivity.onCreate: window configured")
        repository = ScriptRepository(this)
        CrashDiagnostics.markStage(this, "MainActivity.onCreate: ScriptRepository created")
        buildApplicationUi()
        CrashDiagnostics.markStage(this, "MainActivity.onCreate: UI built")
        val cachedSources = repository.getSources()
        refreshSourceList(cachedSources)
        searchStatus.text = if (cachedSources.isEmpty()) {
            "尚无本地歌词源。点击“更新歌词源”下载。"
        } else {
            "已加载 ${cachedSources.size} 个本地歌词源。需要时可手动更新。"
        }
        CrashDiagnostics.markStage(this, "MainActivity.onCreate: completed without startup network sync")
    }

    private fun buildApplicationUi() {
        CrashDiagnostics.markStage(this, "buildApplicationUi: entered")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 249, 251))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(10))
        }
        val brand = TextView(this).apply {
            text = "ezXLRC"
            setTextColor(Color.rgb(28, 38, 52))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "  Lyrics · LRC · XLRC"
            setTextColor(Color.rgb(110, 120, 132))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        header.addView(brand)
        header.addView(subtitle)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        tabs = TabLayout(this).apply {
            setSelectedTabIndicatorColor(Color.rgb(33, 150, 243))
            setTabTextColors(Color.rgb(105, 115, 128), Color.rgb(25, 118, 210))
            addTab(newTab().setText("搜索"))
            addTab(newTab().setText("编辑与转换"))
            addTab(newTab().setText("修复工具"))
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        CrashDiagnostics.markStage(this, "buildApplicationUi: root attached")

        val searchPage = buildSearchPage()
        CrashDiagnostics.markStage(this, "buildApplicationUi: search page built")
        val editorPage = buildEditorPage()
        CrashDiagnostics.markStage(this, "buildApplicationUi: editor page built")
        val toolsPage = buildToolsPage()
        CrashDiagnostics.markStage(this, "buildApplicationUi: tools page built")
        val pages = listOf(searchPage, editorPage, toolsPage)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(pages[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        showPage(searchPage)
        CrashDiagnostics.markStage(this, "buildApplicationUi: first page shown")
    }

    private fun showPage(page: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView?.let { hiddenWebView ->
            (hiddenWebView.parent as? ViewGroup)?.removeView(hiddenWebView)
            contentFrame.addView(hiddenWebView, FrameLayout.LayoutParams(dp(1), dp(1), Gravity.BOTTOM or Gravity.END))
        }
    }

    private fun initializeSearchEngine() {
        try {
            val hiddenWebView = WebView(this).apply {
                alpha = 0f
                isFocusable = false
                isFocusableInTouchMode = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
            }
            val scriptRuntime = ScriptRuntime(this, hiddenWebView)
            webView = hiddenWebView
            runtime = scriptRuntime
            if (::contentFrame.isInitialized) {
                (hiddenWebView.parent as? ViewGroup)?.removeView(hiddenWebView)
                contentFrame.addView(hiddenWebView, FrameLayout.LayoutParams(dp(1), dp(1), Gravity.BOTTOM or Gravity.END))
            }
            scriptRuntime.initialize {
                searchEngineAvailable = true
                searchStatus.text = "搜索引擎已就绪。正在检查歌词源更新…"
            }
        } catch (error: Throwable) {
            searchEngineAvailable = false
            webView = null
            runtime = null
            android.util.Log.e("ezXLRC", "Search engine initialization failed", error)
            if (::searchStatus.isInitialized) {
                val reason = listOfNotNull(error.javaClass.simpleName, error.message?.take(120)).joinToString(": ")
                searchStatus.text = "搜索引擎初始化失败，已禁用在线搜索。\n$reason"
            }
        }
    }

    private fun buildSearchPage(): View {
        val column = verticalColumn()
        val intro = TextView(this).apply {
            text = "从 ESLyric/scripts 获取歌词源。可按单个来源搜索，也可逐个搜索全部来源。"
            setTextColor(Color.rgb(92, 103, 116))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(10))
        }
        column.addView(intro)
        titleInput = addField(column, "歌曲名")
        artistInput = addField(column, "歌手")
        albumInput = addField(column, "专辑（可选）")
        durationInput = addField(column, "时长（秒，可选）", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

        val sourceLabel = TextView(this).apply {
            text = "歌词源"
            setTextColor(Color.rgb(75, 86, 100))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(2), dp(6), 0, dp(4))
        }
        column.addView(sourceLabel)
        sourceSpinner = Spinner(this)
        column.addView(sourceSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val actionRow = horizontalRow()
        actionRow.addView(makeButton("搜索所选", primary = true) { startSelectedSearch() }, weightedButtonParams())
        actionRow.addView(makeButton("搜索全部", primary = false) { startAllSearch() }, weightedButtonParams())
        column.addView(actionRow)
        val updateRow = horizontalRow()
        updateRow.addView(makeButton("更新歌词源", primary = false) { updateSources(force = true) }, weightedButtonParams())
        updateRow.addView(makeButton("停止搜索", primary = false) { stopSearch() }, weightedButtonParams())
        searchProgress = ProgressBar(this).apply { visibility = View.GONE }
        updateRow.addView(searchProgress, LinearLayout.LayoutParams(dp(32), dp(36)).apply { gravity = Gravity.CENTER_VERTICAL })
        column.addView(updateRow)

        searchStatus = bodyText("等待歌词源更新。")
        column.addView(searchStatus)
        resultsContainer = verticalColumn()
        column.addView(resultsContainer)
        return scroll(column)
    }

    private fun buildEditorPage(): View {
        val column = verticalColumn()
        val intro = bodyText("可编辑搜索结果或导入本地歌词。支持 LRC、XLRC 与纯文本互转。")
        column.addView(intro)
        val fileRow = horizontalRow()
        fileRow.addView(makeButton("打开文件", false) { openLyricFile() }, weightedButtonParams())
        fileRow.addView(makeButton("保存文件", true) { saveLyricFile() }, weightedButtonParams())
        fileRow.addView(makeButton("复制", false) { copyEditorText() }, weightedButtonParams())
        column.addView(fileRow)

        val editorLayout = TextInputLayout(this).apply {
            hint = "歌词内容"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        }
        editorInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 16
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        editorLayout.addView(editorInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)))
        column.addView(editorLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val intervalRow = horizontalRow()
        intervalInput = addField(intervalRow, "纯歌词转 LRC 的间隔（秒）", InputType.TYPE_CLASS_NUMBER).apply { setText("5") }
        column.addView(intervalRow)
        addActionRow(column, "LRC 转纯歌词", "纯歌词转 LRC") {
            val text = editorText()
            val interval = intervalInput.text?.toString()?.toIntOrNull() ?: 5
            editorInput.setText(if (it == 0) LyricProcessor.toPlain(text) else LyricProcessor.plainToLrc(text, interval))
        }
        addActionRow(column, "XLRC 转普通 LRC", "LRC 转 XLRC") {
            editorInput.setText(if (it == 0) LyricProcessor.advancedToLrc(editorText()) else LyricProcessor.lrcToXlrc(editorText()))
        }
        addActionRow(column, "应用 offset", "XLRC 转递进 LRC") {
            editorInput.setText(if (it == 0) LyricProcessor.applyOffset(editorText()) else LyricProcessor.advancedToProgressive(editorText(), true))
        }
        addActionRow(column, "XLRC 转单词 LRC", "生成分词文本") {
            editorInput.setText(if (it == 0) LyricProcessor.advancedToWords(editorText(), false) else LyricProcessor.generateSplitText(editorText()))
        }

        val segmentLayout = TextInputLayout(this).apply {
            hint = "自定义分词（每行对应一条 XLRC 歌词，用下划线分隔；空格保留在词内）"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        segmentationInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        segmentLayout.addView(segmentationInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))
        column.addView(segmentLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val customRow = horizontalRow()
        customRow.addView(makeButton("自定义递进 LRC", false) {
            editorInput.setText(LyricProcessor.advancedToCustom(editorText(), segmentationInput.text?.toString().orEmpty(), true))
        }, weightedButtonParams())
        customRow.addView(makeButton("自定义单词 LRC", false) {
            editorInput.setText(LyricProcessor.advancedToCustom(editorText(), segmentationInput.text?.toString().orEmpty(), false))
        }, weightedButtonParams())
        customRow.addView(makeButton("导入分词文件", false) { openSegmentationFile() }, weightedButtonParams())
        column.addView(customRow)
        return scroll(column)
    }

    private fun buildToolsPage(): View {
        val column = verticalColumn()
        column.addView(bodyText("以下修复操作直接作用于“编辑与转换”页当前打开的歌词。"))
        column.addView(makeButton("转到编辑器", false) { tabs.getTabAt(1)?.select() })
        column.addView(makeButton("时间码行上移一行", true) {
            editorInput.setText(LyricProcessor.shiftTimestampRows(editorText(), -1))
            toolStatus.text = "已将独立时间码行相对歌词行上移一行。"
        })
        column.addView(makeButton("时间码行下移一行", false) {
            editorInput.setText(LyricProcessor.shiftTimestampRows(editorText(), 1))
            toolStatus.text = "已将独立时间码行相对歌词行下移一行。"
        })
        val nLayout = TextInputLayout(this).apply {
            hint = "删除相同时间码的第 n 行（从 1 开始）"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val nInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("2")
        }
        nLayout.addView(nInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(nLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        column.addView(makeButton("删除重复时间码的第 n 行", true) {
            val n = nInput.text?.toString()?.toIntOrNull() ?: 2
            if (n < 1) {
                toolStatus.text = "n 必须大于或等于 1。"
            } else {
                val (updated, removed) = LyricProcessor.deleteNthDuplicateTimestampLine(editorText(), n)
                editorInput.setText(updated)
                toolStatus.text = if (removed > 0) "已删除 $removed 行。" else "没有找到满足条件的重复时间码行。"
            }
        })
        toolStatus = bodyText("等待操作。")
        column.addView(toolStatus)
        return scroll(column)
    }

    private fun addActionRow(parent: LinearLayout, first: String, second: String, action: (Int) -> Unit) {
        val row = horizontalRow()
        row.addView(makeButton(first, false) { action(0) }, weightedButtonParams())
        row.addView(makeButton(second, false) { action(1) }, weightedButtonParams())
        parent.addView(row)
    }

    private fun addField(parent: LinearLayout, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): TextInputEditText {
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        }
        val edit = TextInputEditText(this).apply {
            this.inputType = inputType
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        layout.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        return edit
    }

    private fun refreshSourceList(list: List<LyricSource>) {
        sources = list
        if (!::sourceSpinner.isInitialized) return
        val names = mutableListOf("选择歌词源")
        names.addAll(list.map { it.toString() })
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun updateSources(force: Boolean) {
        if (::searchStatus.isInitialized) searchStatus.text = if (force) "正在更新 ESLyric 歌词源…" else "正在检查 ESLyric 歌词源…"
        CrashDiagnostics.markStage(this, "updateSources: sync requested")
        repository.sync(force) { report ->
            if (isFinishing || isDestroyed) return@sync
            CrashDiagnostics.markStage(this, "updateSources: sync callback")
            refreshSourceList(report.sources)
            val base = "歌词源：${report.sources.size} 个；新增下载 ${report.downloaded} 个，缓存复用 ${report.unchanged} 个。"
            searchStatus.text = if (report.errors.isEmpty()) base else base + "\n" + report.errors.take(4).joinToString("\n")
        }
    }

    private fun startSelectedSearch() {
        val index = sourceSpinner.selectedItemPosition - 1
        if (index !in sources.indices) {
            searchStatus.text = "先选择一个歌词源。"
            return
        }
        startSearch(listOf(sources[index]))
    }

    private fun startAllSearch() = startSearch(sources)

    private fun startSearch(targets: List<LyricSource>) {
        if (searchInProgress) return
        val activeRuntime = runtime
        if (activeRuntime == null) {
            initializeSearchEngine()
            searchStatus.text = "正在初始化搜索引擎，请稍后再试一次。"
            return
        }
        if (!searchEngineAvailable || !activeRuntime.isReady) {
            searchStatus.text = "搜索引擎尚未就绪，或当前设备上的 WebView 初始化失败。"
            return
        }
        if (targets.isEmpty()) {
            searchStatus.text = "没有可用歌词源。请先更新歌词源。"
            return
        }
        val title = titleInput.text?.toString()?.trim().orEmpty()
        val artist = artistInput.text?.toString()?.trim().orEmpty()
        if (title.isBlank() && artist.isBlank()) {
            searchStatus.text = "至少填写歌曲名或歌手。"
            return
        }
        val duration = durationInput.text?.toString()?.toDoubleOrNull() ?: 0.0
        val meta = JSONObject()
            .put("title", title)
            .put("rawTitle", title)
            .put("artist", artist)
            .put("rawArtist", artist)
            .put("album", albumInput.text?.toString()?.trim().orEmpty())
            .put("rawAlbum", albumInput.text?.toString()?.trim().orEmpty())
            .put("duration", duration)
        resultsContainer.removeAllViews()
        collectedResults.clear()
        searchErrorCount = 0
        stopSearchRequested = false
        searchInProgress = true
        searchIndex = 0
        searchProgress.visibility = View.VISIBLE
        runNextSearcher(targets, meta)
    }

    private fun stopSearch() {
        if (!searchInProgress) {
            searchStatus.text = "当前没有正在运行的搜索。"
            return
        }
        stopSearchRequested = true
        searchStatus.text = "正在停止；当前歌词源结束后将停止后续搜索。"
    }

    private fun runNextSearcher(targets: List<LyricSource>, meta: JSONObject) {
        if (stopSearchRequested) {
            searchInProgress = false
            searchProgress.visibility = View.GONE
            searchStatus.text = "搜索已停止：已检查 $searchIndex/${targets.size} 个来源，找到 ${collectedResults.size} 条歌词。"
            return
        }
        if (searchIndex >= targets.size) {
            searchInProgress = false
            searchProgress.visibility = View.GONE
            searchStatus.text = "搜索完成：${collectedResults.size} 条歌词；检查 ${targets.size} 个来源，失败 ${searchErrorCount} 个。"
            return
        }
        val source = targets[searchIndex]
        searchIndex++
        searchStatus.text = "正在搜索 ${source.name}（$searchIndex/${targets.size}）… 已找到 ${collectedResults.size} 条。"
        val script = repository.getScript(source)
        if (script.isNullOrBlank()) {
            searchErrorCount++
            runNextSearcher(targets, meta)
            return
        }
        runtime?.run(source, script, meta) { results, error ->
            results.forEach { result ->
                collectedResults += result
                addResultCard(result)
            }
            if (!error.isNullOrBlank()) {
                searchErrorCount++
                android.util.Log.w("ezXLRC", "${source.name}: $error")
            }
            runNextSearcher(targets, meta)
        }
    }

    private fun addResultCard(result: LyricResult) {
        val card = MaterialCardView(this).apply {
            radius = dp(10).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = Color.rgb(229, 234, 240)
            strokeWidth = dp(1)
        }
        val box = verticalColumn().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val title = TextView(this).apply {
            text = result.title.ifBlank { "未命名歌词" }
            setTextColor(Color.rgb(32, 43, 58))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        box.addView(title)
        val details = TextView(this).apply {
            text = listOf(result.artist, result.album, result.source, result.fileType.uppercase()).filter { it.isNotBlank() }.joinToString("  ·  ")
            setTextColor(Color.rgb(111, 121, 134))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(3), 0, dp(6))
        }
        box.addView(details)
        val preview = TextView(this).apply {
            text = result.lyricText.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n").take(260)
            setTextColor(Color.rgb(70, 80, 94))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
        }
        box.addView(preview)
        val open = makeButton("在编辑器中打开", false) {
            editorInput.setText(result.lyricText)
            tabs.getTabAt(1)?.select()
        }
        box.addView(open)
        card.addView(box)
        resultsContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun editorText(): String = if (::editorInput.isInitialized) editorInput.text?.toString().orEmpty() else ""

    private fun openLyricFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    private fun saveLyricFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            val text = editorText()
            val timed = Regex("""(?m)^\s*\[\d{1,3}:\d{2}""").containsMatchIn(text)
            val wordTimed = Regex("""<(?:\d{1,3}:)?\d{1,3}:\d{2}(?:[.,]\d{1,3})?>""").containsMatchIn(text)
            putExtra(Intent.EXTRA_TITLE, when {
                wordTimed -> "lyrics.xlrc"
                timed -> "lyrics.lrc"
                else -> "lyrics.txt"
            })
        }
        startActivityForResult(intent, REQUEST_SAVE)
    }

    private fun copyEditorText() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Lyrics", editorText()))
        android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Use activity result APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_OPEN -> readText(uri)
            REQUEST_SAVE -> writeText(uri)
            REQUEST_OPEN_SEGMENTATION -> readSegmentationText(uri)
        }
    }

    private fun openSegmentationFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(intent, REQUEST_OPEN_SEGMENTATION)
    }

    private fun readSegmentationText(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { LyricTextCodec.decode(it.readBytes()) }
                ?: throw IllegalStateException("无法读取分词文件")
        }.onSuccess { segmentationInput.setText(it) }
            .onFailure { android.widget.Toast.makeText(this, "导入分词失败：${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun readText(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { LyricTextCodec.decode(it.readBytes()) }
                ?: throw IllegalStateException("无法读取文件")
        }.onSuccess { editorInput.setText(it) }
            .onFailure { android.widget.Toast.makeText(this, "打开失败：${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun writeText(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(editorText()) }
                ?: throw IllegalStateException("无法写入文件")
        }.onSuccess { android.widget.Toast.makeText(this, "已保存", android.widget.Toast.LENGTH_SHORT).show() }
            .onFailure { android.widget.Toast.makeText(this, "保存失败：${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun verticalColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(20))
    }

    private fun horizontalRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun scroll(child: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun bodyText(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(Color.rgb(88, 99, 113))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(8), 0, dp(10))
    }

    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        cornerRadius = dp(8)
        insetTop = dp(2)
        insetBottom = dp(2)
        if (primary) {
            backgroundTintList = ColorStateList.valueOf(Color.rgb(33, 150, 243))
            setTextColor(Color.WHITE)
        } else {
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.button_surface)
            setTextColor(Color.rgb(35, 116, 184))
            strokeColor = ContextCompat.getColorStateList(this@MainActivity, R.color.button_stroke)
            strokeWidth = dp(1)
        }
        setOnClickListener { action() }
    }

    private fun weightedButtonParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
        marginEnd = dp(6)
        topMargin = dp(4)
        bottomMargin = dp(4)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        runtime?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_OPEN = 1001
        private const val REQUEST_SAVE = 1002
        private const val REQUEST_OPEN_SEGMENTATION = 1003
    }
}

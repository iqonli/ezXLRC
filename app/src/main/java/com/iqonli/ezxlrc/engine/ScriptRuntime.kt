package com.iqonli.ezxlrc.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.iqonli.ezxlrc.data.LyricResult
import com.iqonli.ezxlrc.data.LyricSource
import com.iqonli.ezxlrc.util.LyricDecoders
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class ScriptRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val servicePrefs = appContext.getSharedPreferences("searcher_service_data", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ezXLRC-JavaScript").apply { isDaemon = true }
    }
    private val bridgeCallbacks = CopyOnWriteArrayList<JSCallFunction>()

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile private var jsContext: QuickJSContext? = null
    @Volatile private var currentSource: LyricSource? = null
    @Volatile private var currentMeta = JSONObject()
    @Volatile private var currentResults = CopyOnWriteArrayList<LyricResult>()
    @Volatile private var currentCallback: ((List<LyricResult>, String?) -> Unit)? = null
    @Volatile private var completion = AtomicBoolean(true)

    fun initialize(onReady: (String?) -> Unit = {}) {
        if (isReady) {
            onReady(null)
            return
        }
        try {
            executor.execute {
                try {
                    QuickJSLoader.init()
                    val context = QuickJSContext.create()
                    bindBridge(context)
                    val runtimeSource = appContext.assets.open("js/runtime.js")
                        .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    context.evaluate(runtimeSource, "ezxlrc-runtime.js")
                    jsContext = context
                    isReady = true
                    mainHandler.post { onReady(null) }
                } catch (error: Throwable) {
                    isReady = false
                    runCatching { jsContext?.destroy() }
                    jsContext = null
                    bridgeCallbacks.clear()
                    val message = error.message ?: error.javaClass.simpleName
                    Log.e("ezXLRC.Runtime", "QuickJS initialization failed", error)
                    mainHandler.post { onReady(message) }
                }
            }
        } catch (error: RejectedExecutionException) {
            mainHandler.post { onReady(error.message ?: "JavaScript runtime is closed") }
        }
    }

    fun run(
        source: LyricSource,
        script: String,
        meta: JSONObject,
        callback: (List<LyricResult>, String?) -> Unit
    ) {
        if (!isReady) {
            mainHandler.post { callback(emptyList(), "JavaScript runtime is not ready") }
            return
        }
        try {
            executor.execute {
                val context = jsContext
                if (!isReady || context == null) {
                    mainHandler.post { callback(emptyList(), "JavaScript runtime is not ready") }
                    return@execute
                }
                currentSource = source
                currentMeta = meta
                currentResults = CopyOnWriteArrayList()
                currentCallback = callback
                completion = AtomicBoolean(false)
                try {
                    val scriptArg = JSONObject.quote(script)
                    context.evaluate("window.runSearcher($scriptArg, ${meta});", "ezxlrc-searcher-runner.js")
                } catch (error: Throwable) {
                    finish(error.message ?: error.javaClass.simpleName)
                }
            }
        } catch (error: RejectedExecutionException) {
            mainHandler.post { callback(emptyList(), error.message ?: "JavaScript runtime is closed") }
        }
    }

    fun destroy() {
        isReady = false
        if (executor.isShutdown) return
        runCatching {
            executor.execute {
                runCatching { jsContext?.destroy() }
                jsContext = null
                bridgeCallbacks.clear()
            }
            executor.shutdown()
        }.onFailure {
            Log.w("ezXLRC.Runtime", "Runtime shutdown failed", it)
            executor.shutdownNow()
        }
    }

    private fun bindBridge(context: QuickJSContext) {
        val bridge = context.createNewJSObject()

        fun bind(name: String, callback: (Array<out Any?>) -> Any?) {
            val function = JSCallFunction { args -> callback(args) }
            bridgeCallbacks.add(function)
            bridge.setProperty(name, function)
        }

        bind("requestSync") { args -> requestSync(args.getOrNull(0)?.toString().orEmpty()) }
        bind("addLyric") { args -> addLyric(args.getOrNull(0)?.toString().orEmpty()); null }
        bind("getSvcData") { args -> getSvcData(args.getOrNull(0)?.toString().orEmpty()) }
        bind("setSvcData") { args ->
            setSvcData(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString().orEmpty())
            null
        }
        bind("aesEcbEncrypt") { args ->
            aesEcbEncrypt(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString().orEmpty())
        }
        bind("md5Base64") { args -> md5Base64(args.getOrNull(0)?.toString().orEmpty()) }
        bind("parseHtml") { args -> parseHtml(args.getOrNull(0)?.toString().orEmpty()) }
        bind("parseXml") { args -> parseXml(args.getOrNull(0)?.toString().orEmpty()) }
        bind("onSearchDone") { finish(null); null }
        bind("onSearchError") { args -> finish(args.getOrNull(0)?.toString().orEmpty()); null }
        bind("log") { args ->
            Log.d("ezXLRC.Searcher", args.getOrNull(0)?.toString().orEmpty().take(2_000))
            null
        }

        context.getGlobalObject().setProperty("AndroidBridge", bridge)
        bridge.release()
    }

    private fun requestSync(requestJson: String): String = runCatching {
        val request = JSONObject(requestJson)
        val url = request.optString("url")
        require(url.startsWith("https://") || url.startsWith("http://")) { "Only HTTP(S) requests are allowed" }
        val method = request.optString("method", "GET").uppercase(Locale.ROOT)
        val timeout = request.optInt("timeout", 12_000).coerceIn(2_000, 30_000)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout
            readTimeout = timeout
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        }
        try {
            val headers = request.optJSONObject("headers")
            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.equals("Host", true) || key.equals("Content-Length", true) || key.equals("Connection", true)) continue
                    connection.setRequestProperty(key, headers.optString(key))
                }
            }
            connection.setRequestProperty("Accept-Encoding", "gzip")
            val bodyBase64 = request.optString("bodyBase64", "")
            val body = request.opt("body")
            if ((bodyBase64.isNotEmpty() || (body != null && body != JSONObject.NULL)) && method !in setOf("GET", "HEAD")) {
                connection.doOutput = true
                val bytes = if (bodyBase64.isNotEmpty()) {
                    Base64.decode(bodyBase64, Base64.DEFAULT)
                } else if (body is String) {
                    body.toByteArray(StandardCharsets.UTF_8)
                } else {
                    body.toString().toByteArray(StandardCharsets.UTF_8)
                }
                if (connection.getRequestProperty("Content-Type").isNullOrBlank()) {
                    val contentType = when {
                        bodyBase64.isNotEmpty() -> "application/octet-stream"
                        body is String && body.trimStart().let { it.startsWith("{") || it.startsWith("[") } -> "application/json; charset=UTF-8"
                        else -> "application/x-www-form-urlencoded; charset=UTF-8"
                    }
                    connection.setRequestProperty("Content-Type", contentType)
                }
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = if (stream == null) ByteArray(0) else {
                val decoded = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream
                decoded.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 12 * 1024 * 1024) { "Response exceeds 12 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            val response = JSONObject()
                .put("statusCode", status)
                .put("headers", JSONObject().apply {
                    connection.headerFields.forEach { (key, values) ->
                        if (key != null && !values.isNullOrEmpty()) put(key.lowercase(Locale.ROOT), values.joinToString(","))
                    }
                })
            if (request.optBoolean("raw", false)) {
                response.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } else {
                val charsetName = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                    .find(connection.contentType.orEmpty())?.groupValues?.getOrNull(1)?.trim(' ', '\'', '"')
                val charset = runCatching {
                    if (charsetName.isNullOrBlank()) StandardCharsets.UTF_8 else Charset.forName(charsetName)
                }.getOrDefault(StandardCharsets.UTF_8)
                response.put("body", String(bytes, charset))
            }
            if (status !in 200..299) response.put("error", "HTTP $status")
            response.toString()
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        JSONObject().put("statusCode", 0).put("body", "").put("error", error.message ?: error.javaClass.simpleName).toString()
    }

    private fun addLyric(lyricJson: String) {
        runCatching {
            val item = JSONObject(lyricJson)
            val sourceType = item.optString("fileType", "lrc").lowercase(Locale.ROOT)
            val dataBase64 = item.optString("lyricDataBase64", "")
            val bytes = if (dataBase64.isNotEmpty()) Base64.decode(dataBase64, Base64.DEFAULT) else null
            val text = item.optString("lyricText", "").ifBlank {
                if (bytes != null) LyricDecoders.decode(sourceType, bytes) else ""
            }
            if (text.isBlank()) return
            val normalizedType = if (
                sourceType in setOf("krc", "qrc", "yrc") ||
                Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>").containsMatchIn(text)
            ) "xlrc" else "lrc"
            currentResults.add(
                LyricResult(
                    title = item.optString("title", currentMeta.optString("title")),
                    artist = item.optString("artist", currentMeta.optString("artist")),
                    album = item.optString("album", currentMeta.optString("album")),
                    source = currentSource?.name.orEmpty(),
                    fileType = normalizedType,
                    lyricText = text,
                    location = item.optString("location", "")
                )
            )
        }.onFailure { error ->
            Log.w("ezXLRC.Searcher", "${currentSource?.name.orEmpty()} lyric decode failed", error)
        }
    }

    private fun serviceKey(key: String): String = "svc:${currentSource?.uri.orEmpty()}:$key"

    private fun getSvcData(key: String): String = servicePrefs.getString(serviceKey(key), "").orEmpty()

    private fun setSvcData(key: String, value: String) {
        servicePrefs.edit().putString(serviceKey(key), value).apply()
    }

    private fun aesEcbEncrypt(plainText: String, keyText: String): String = runCatching {
        val key = keyText.toByteArray(Charset.forName("ISO-8859-1"))
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)).toHex()
    }.getOrDefault("")

    private fun md5Base64(bytesBase64: String): String = runCatching {
        val bytes = Base64.decode(bytesBase64, Base64.DEFAULT)
        MessageDigest.getInstance("MD5").digest(bytes).toHex()
    }.getOrDefault("")

    private fun parseHtml(html: String): String = runCatching {
        val document = Jsoup.parse(html)
        val children = JSONArray()
        document.childNodes().forEach { node -> nodeToJson(node)?.let(children::put) }
        children.toString()
    }.getOrDefault("[]")

    private fun parseXml(xml: String): String = runCatching {
        val document = Parser.xmlParser().parseInput(xml, "")
        val children = JSONArray()
        document.childNodes().forEach { node -> nodeToJson(node)?.let(children::put) }
        JSONObject().put("children", children).toString()
    }.getOrElse { JSONObject().put("error", it.message ?: "XML parse failed").toString() }

    private fun nodeToJson(node: Node): JSONObject? = when (node) {
        is Element -> {
            val attributes = JSONArray()
            node.attributes().forEach { attribute ->
                attributes.put(JSONObject().put("key", attribute.key).put("value", attribute.value))
            }
            val children = JSONArray()
            node.childNodes().forEach { child -> nodeToJson(child)?.let(children::put) }
            JSONObject()
                .put("type", "element")
                .put("tagName", node.tagName())
                .put("attributes", attributes)
                .put("children", children)
        }
        is TextNode -> JSONObject().put("type", "text").put("content", node.wholeText)
        is DataNode -> JSONObject().put("type", "text").put("content", node.wholeData)
        else -> null
    }

    private fun finish(error: String?) {
        if (!completion.compareAndSet(false, true)) return
        val results = currentResults.toList()
        val callback = currentCallback
        currentCallback = null
        mainHandler.post { callback?.invoke(results, error) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

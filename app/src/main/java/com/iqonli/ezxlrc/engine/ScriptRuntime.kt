package com.iqonli.ezxlrc.engine

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.iqonli.ezxlrc.data.LyricResult
import com.iqonli.ezxlrc.data.LyricSource
import com.iqonli.ezxlrc.util.LyricDecoders
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class ScriptRuntime(context: Context, private val webView: WebView) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val servicePrefs = appContext.getSharedPreferences("searcher_service_data", Context.MODE_PRIVATE)
    private val bridge = Bridge()
    @Volatile var isReady: Boolean = false
        private set

    @Volatile private var currentSource: LyricSource? = null
    @Volatile private var currentMeta = JSONObject()
    @Volatile private var currentResults = CopyOnWriteArrayList<LyricResult>()
    @Volatile private var currentCallback: ((List<LyricResult>, String?) -> Unit)? = null

    fun initialize(onReady: (() -> Unit)? = null) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = false
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.settings.allowFileAccessFromFileURLs = false
        webView.settings.allowUniversalAccessFromFileURLs = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            override fun onPageFinished(view: WebView?, url: String?) {
                isReady = true
                onReady?.invoke()
            }
        }
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.loadUrl("file:///android_asset/engine.html")
    }

    fun run(source: LyricSource, script: String, meta: JSONObject, callback: (List<LyricResult>, String?) -> Unit) {
        if (!isReady) {
            callback(emptyList(), "JavaScript runtime is still starting")
            return
        }
        currentSource = source
        currentMeta = meta
        currentResults = CopyOnWriteArrayList()
        currentCallback = callback
        val scriptArg = JSONObject.quote(script)
        val metaArg = meta.toString()
        webView.evaluateJavascript("window.runSearcher($scriptArg,$metaArg);", null)
    }

    fun destroy() {
        isReady = false
        webView.stopLoading()
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun requestSync(requestJson: String): String {
            return runCatching {
                val request = JSONObject(requestJson)
                val url = request.optString("url")
                require(url.startsWith("https://") || url.startsWith("http://")) { "Only HTTP(S) requests are allowed" }
                val method = request.optString("method", "GET").uppercase()
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
                                if (key != null && !values.isNullOrEmpty()) put(key.lowercase(), values.joinToString(","))
                            }
                        })
                    if (request.optBoolean("raw", false)) {
                        response.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    } else {
                        val charsetName = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                            .find(connection.contentType.orEmpty())?.groupValues?.getOrNull(1)?.trim(' ', '\'', '"')
                        val charset = runCatching { if (charsetName.isNullOrBlank()) StandardCharsets.UTF_8 else Charset.forName(charsetName) }
                            .getOrDefault(StandardCharsets.UTF_8)
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
        }

        @JavascriptInterface
        fun addLyric(lyricJson: String) {
            runCatching {
                val item = JSONObject(lyricJson)
                val sourceType = item.optString("fileType", "lrc").lowercase()
                val dataBase64 = item.optString("lyricDataBase64", "")
                val bytes = if (dataBase64.isNotEmpty()) Base64.decode(dataBase64, Base64.DEFAULT) else null
                val text = item.optString("lyricText", "").ifBlank {
                    if (bytes != null) LyricDecoders.decode(sourceType, bytes) else ""
                }
                if (text.isBlank()) return
                val normalizedType = if (sourceType in setOf("krc", "qrc") || Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>").containsMatchIn(text)) "xlrc" else "lrc"
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
                android.util.Log.w("ezXLRC.Searcher", "${currentSource?.name.orEmpty()} lyric decode failed", error)
            }
        }

        private fun serviceKey(key: String): String = "svc:${currentSource?.uri.orEmpty()}:$key"

        @JavascriptInterface
        fun getSvcData(key: String): String = servicePrefs.getString(serviceKey(key), "").orEmpty()

        @JavascriptInterface
        fun setSvcData(key: String, value: String) {
            servicePrefs.edit().putString(serviceKey(key), value).apply()
        }

        @JavascriptInterface
        fun aesEcbEncrypt(plainText: String, keyText: String): String = runCatching {
            val key = keyText.toByteArray(Charset.forName("ISO-8859-1"))
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)).toHex()
        }.getOrDefault("")

        @JavascriptInterface
        fun md5Base64(bytesBase64: String): String = runCatching {
            val bytes = Base64.decode(bytesBase64, Base64.DEFAULT)
            MessageDigest.getInstance("MD5").digest(bytes).toHex()
        }.getOrDefault("")

        @JavascriptInterface
        fun onSearchDone() {
            finish(null)
        }

        @JavascriptInterface
        fun onSearchError(message: String) {
            finish(message)
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("ezXLRC.Searcher", message.take(2_000))
        }
    }

    private fun finish(error: String?) {
        val results = currentResults.toList()
        val callback = currentCallback
        currentCallback = null
        mainHandler.post { callback?.invoke(results, error) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

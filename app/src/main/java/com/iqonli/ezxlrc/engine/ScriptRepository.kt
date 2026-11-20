package com.iqonli.ezxlrc.engine

import android.content.Context
import com.iqonli.ezxlrc.data.LyricSource
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ScriptRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("script_cache", Context.MODE_PRIVATE)
    private val searcherDir = File(appContext.filesDir, "searchers").apply { mkdirs() }
    @Volatile private var sources: List<LyricSource> = loadCachedSources()

    data class SyncReport(
        val sources: List<LyricSource>,
        val downloaded: Int,
        val unchanged: Int,
        val errors: List<String>
    )

    fun getSources(): List<LyricSource> = sources

    fun getScript(source: LyricSource): String? {
        val file = File(searcherDir, safeName(source.uri))
        return if (file.isFile) normalizeSource(source.uri, file.readText(Charsets.UTF_8)) else null
    }

    fun sync(force: Boolean = false, callback: (SyncReport) -> Unit) {
        Thread {
            val errors = mutableListOf<String>()
            var downloaded = 0
            var unchanged = 0
            try {
                val manifestUrl = "$BASE/archive/version.json"
                val manifestFetch = fetch(manifestUrl, if (force) null else prefs.getString("etag:manifest", null))
                val manifestText = when {
                    manifestFetch.status == HttpURLConnection.HTTP_NOT_MODIFIED -> prefs.getString("manifest", null)
                    manifestFetch.status == HttpURLConnection.HTTP_OK -> manifestFetch.body.also {
                        prefs.edit().putString("manifest", it).putString("etag:manifest", manifestFetch.etag).apply()
                    }
                    else -> null
                } ?: throw IllegalStateException("ESLyric source manifest is unavailable")

                val json = JSONObject(manifestText)
                val sourceArray = json.optJSONArray("sources") ?: throw IllegalStateException("Manifest has no sources array")
                val parsed = buildList {
                    for (i in 0 until sourceArray.length()) {
                        val item = sourceArray.optJSONObject(i) ?: continue
                        val uri = item.optString("uri")
                        if (!SAFE_JS.matches(uri)) continue
                        add(
                            LyricSource(
                                name = item.optString("name", uri.removeSuffix(".js")),
                                version = item.optString("version", ""),
                                author = item.optString("author", ""),
                                uri = uri
                            )
                        )
                    }
                }
                if (parsed.isEmpty()) throw IllegalStateException("No usable searcher scripts in manifest")

                val executor = Executors.newFixedThreadPool(4)
                try {
                    val futures = parsed.map { source ->
                        executor.submit(Callable {
                            val file = File(searcherDir, safeName(source.uri))
                            try {
                                val url = "$BASE/searcher/${source.uri}"
                                val etagKey = "etag:${source.uri}"
                                val result = fetch(url, if (force) null else prefs.getString(etagKey, null))
                                when (result.status) {
                                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                                        if (!file.isFile) throw IllegalStateException("Server returned 304 but no cached script exists")
                                        "unchanged"
                                    }
                                    HttpURLConnection.HTTP_OK -> {
                                        val normalized = normalizeSource(source.uri, result.body)
                                        val temp = File(searcherDir, "${source.uri}.tmp")
                                        temp.writeText(normalized, Charsets.UTF_8)
                                        if (!temp.renameTo(file)) {
                                            file.writeText(normalized, Charsets.UTF_8)
                                            temp.delete()
                                        }
                                        prefs.edit().putString(etagKey, result.etag).apply()
                                        "downloaded"
                                    }
                                    else -> throw IllegalStateException("HTTP ${result.status}")
                                }
                            } catch (e: Exception) {
                                if (file.isFile) "cached:${e.message ?: "update failed"}"
                                else "error:${e.message ?: "download failed"}"
                            }
                        })
                    }
                    futures.forEachIndexed { index, future ->
                        when (val result = future.get()) {
                            "downloaded" -> downloaded++
                            "unchanged" -> unchanged++
                            else -> {
                                val source = parsed[index]
                                if (result.startsWith("cached:")) {
                                    unchanged++
                                    errors += "${source.name}: ${result.removePrefix("cached:")} (cached copy retained)"
                                } else {
                                    errors += "${source.name}: ${result.removePrefix("error:")}"
                                }
                            }
                        }
                    }
                } finally {
                    executor.shutdown()
                }

                sources = parsed.filter { File(searcherDir, safeName(it.uri)).isFile }
                if (sources.isEmpty()) throw IllegalStateException("No searcher scripts were downloaded or cached")
                prefs.edit().putString("source_list", JSONObject().put("sources", sourceArray).toString()).apply()
            } catch (e: Exception) {
                errors += (e.message ?: e.javaClass.simpleName)
                if (sources.isEmpty()) sources = loadCachedSources()
            }
            val report = SyncReport(sources, downloaded, unchanged, errors)
            android.os.Handler(appContext.mainLooper).post { callback(report) }
        }.start()
    }

    private fun loadCachedSources(): List<LyricSource> = runCatching {
        val raw = prefs.getString("source_list", null) ?: prefs.getString("manifest", null) ?: return emptyList()
        val array = JSONObject(raw).optJSONArray("sources") ?: return emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uri = item.optString("uri")
                if (!SAFE_JS.matches(uri) || !File(searcherDir, safeName(uri)).isFile) continue
                add(LyricSource(item.optString("name", uri), item.optString("version", ""), item.optString("author", ""), uri))
            }
        }
    }.getOrDefault(emptyList())

    private data class FetchResult(val status: Int, val body: String, val etag: String?)

    private fun fetch(url: String, etag: String?): FetchResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ezXLRC/1.0 (Android)")
            setRequestProperty("Accept", "application/json, text/javascript, */*")
            if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
        }
        return try {
            val status = connection.responseCode
            val responseEtag = connection.getHeaderField("ETag") ?: etag
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                FetchResult(status, "", responseEtag)
            } else if (status in 200..299) {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                FetchResult(status, body, responseEtag)
            } else {
                val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $status ${errorBody.take(180)}")
            }
        } finally {
            connection.disconnect()
        }
    }


    private fun normalizeSource(uri: String, source: String): String {
        if (uri != "qqmusic.js") return source
        var fixed = source
        fixed = fixed.replace(
            Regex("""(?m)(singerName\s*:\s*btoa\()\s*song\.album(\s*\))"""),
            "$1song.artist$2"
        )
        fixed = fixed.replace(
            Regex("""(?m)(songName\s*:\s*btoa\()\s*song\.artist(\s*\))"""),
            "$1song.title$2"
        )
        fixed = fixed.replace(
            Regex("""btoa\((song\.(?:album|artist|title))\)"""),
            "btoa(unescape(encodeURIComponent($1)))"
        )
        return fixed
    }

    private fun safeName(uri: String): String = uri.substringAfterLast('/').replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        private const val BASE = "https://raw.githubusercontent.com/ESLyric/scripts/main"
        private val SAFE_JS = Regex("^[A-Za-z0-9_.-]+\\.js$")
    }
}

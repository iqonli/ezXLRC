package com.iqonli.ezxlrc.engine

import android.content.Context
import android.util.Log
import com.iqonli.ezxlrc.data.LyricSource
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class ScriptRepository(context: Context) {
    private val appContext = context.applicationContext
    private val bundledLibraryScripts: Map<String, String> = loadLibraryScripts()
    private val sources: List<LyricSource> = loadSources()
    private val scriptCache = ConcurrentHashMap<String, String>()

    fun getSources(): List<LyricSource> = sources

    fun getScript(source: LyricSource): String? {
        return scriptCache[source.uri] ?: runCatching {
            val script = when {
                source.uri.startsWith("library/") ->
                    bundledLibraryScripts[source.uri.removePrefix("library/")]
                source.uri.startsWith("upstream/") ->
                    appContext.assets.open("eslyric/${source.uri}").bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                else -> null
            }
            if (script != null) scriptCache.putIfAbsent(source.uri, script)
            script
        }.onFailure {
            Log.e("ezXLRC.Scripts", "Unable to load bundled script ${source.uri}", it)
        }.getOrNull()
    }

    private fun loadSources(): List<LyricSource> = runCatching {
        val json = appContext.assets.open("eslyric/sources.json")
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val array = JSONObject(json).getJSONArray("sources")
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    LyricSource(
                        name = item.getString("name"),
                        version = item.optString("version", ""),
                        author = item.optString("author", ""),
                        uri = item.getString("uri")
                    )
                )
            }
        }
    }.onFailure {
        Log.e("ezXLRC.Scripts", "Unable to load bundled source index", it)
    }.getOrDefault(emptyList())

    private fun loadLibraryScripts(): Map<String, String> = runCatching {
        val scripts = LinkedHashMap<String, String>()
        ZipInputStream(appContext.assets.open("eslyric/library-searchers.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".js", ignoreCase = true)) {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)
                    scripts[entry.name] = String(output.toByteArray(), StandardCharsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        scripts
    }.onFailure {
        Log.e("ezXLRC.Scripts", "Unable to read bundled Library searchers", it)
    }.getOrDefault(emptyMap())
}

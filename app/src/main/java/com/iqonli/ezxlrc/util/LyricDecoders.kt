package com.iqonli.ezxlrc.util

import android.util.Xml
import android.util.Base64
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object LyricDecoders {
    private val krcKey = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
    )
    private val qrcKey = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charset.forName("US-ASCII"))

    fun decode(fileType: String?, data: ByteArray): String {
        return when (fileType?.lowercase()) {
            "krc" -> decodeKrc(data)
            "qrc" -> decodeQrc(data)
            else -> data.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        }
    }

    fun decodeKrc(data: ByteArray): String {
        require(data.size > 4 && data.copyOfRange(0, 4).contentEquals(byteArrayOf(0x6b, 0x72, 0x63, 0x31))) {
            "Invalid KRC header"
        }
        val decrypted = ByteArray(data.size - 4)
        for (i in 4 until data.size) decrypted[i - 4] = (data[i].toInt() xor (krcKey[(i - 4) % krcKey.size].toInt() and 0xff)).toByte()
        val plain = inflate(decrypted).toString(StandardCharsets.UTF_8)
        return krcToXlrc(plain)
    }

    private fun krcToXlrc(krc: String): String {
        val output = mutableListOf<String>()
        var translation = emptyList<String>()
        var translationIndex = 0
        val meta = Regex("""^\[(\S+):(\S+)]$""")
        val lyric = Regex("""^\[(\d+),(\d+)](.*)$""")
        val chunks = Regex("""<(\d+),(\d+),(\d+)>([^<]*)""")

        krc.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            val metaMatch = meta.matchEntire(line)
            if (metaMatch != null) {
                if (metaMatch.groupValues[1] == "language") {
                    translation = parseKrcTranslation(metaMatch.groupValues[2])
                } else {
                    output += line
                }
                return@forEach
            }
            val match = lyric.matchEntire(line) ?: return@forEach
            val start = match.groupValues[1].toLongOrNull() ?: return@forEach
            val duration = match.groupValues[2].toLongOrNull() ?: 0L
            val body = match.groupValues[3]
            val tokens = chunks.findAll(body).toList()
            val builder = StringBuilder("[").append(LyricProcessor.formatTime(start)).append(']')
            if (tokens.isEmpty()) {
                builder.append(body)
            } else {
                tokens.forEach { token ->
                    val offset = token.groupValues[1].toLongOrNull() ?: 0L
                    builder.append('<').append(LyricProcessor.formatTime(start + offset)).append('>')
                        .append(token.groupValues[4])
                }
                builder.append('<').append(LyricProcessor.formatTime(start + duration)).append('>')
            }
            output += builder.toString()
            if (translationIndex < translation.size) {
                output += "[${LyricProcessor.formatTime(start)}]${translation[translationIndex]}"
                translationIndex++
            }
        }
        return output.joinToString("\n")
    }

    private fun parseKrcTranslation(encoded: String): List<String> = runCatching {
        val jsonText = String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
        val root = JSONObject(jsonText)
        val content = root.optJSONArray("content") ?: return emptyList()
        val first = content.optJSONObject(0) ?: return emptyList()
        if (first.optInt("type") != 1) return emptyList()
        val lines = first.optJSONArray("lyricContent") ?: return emptyList()
        buildList { for (i in 0 until lines.length()) add(lines.optString(i)) }
    }.getOrDefault(emptyList())

    fun decodeQrc(data: ByteArray): String {
        val headerEnd = data.indexOf(0x0a.toByte())
        val encrypted = if (headerEnd >= 0) data.copyOfRange(headerEnd + 1, data.size) else data
        require(encrypted.isNotEmpty() && encrypted.size % 8 == 0) { "Invalid QRC payload" }
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(qrcKey, "DESede"))
        val decrypted = cipher.doFinal(encrypted)
        val xmlOrText = runCatching { inflate(decrypted).toString(StandardCharsets.UTF_8) }
            .getOrElse { decrypted.toString(StandardCharsets.UTF_8).trimEnd('\u0000') }
        val qrcText = extractQrcContent(xmlOrText) ?: xmlOrText
        return qrcToXlrc(qrcText)
    }

    private fun extractQrcContent(xml: String): String? = runCatching {
        val sanitized = xml.replace(Regex("&(?![a-zA-Z]{2,6};|#[0-9]{2,4};)"), "&amp;")
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(sanitized))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Lyric_1") {
                val type = parser.getAttributeValue(null, "LyricType")?.toIntOrNull()
                if (type != 1) return null
                return parser.getAttributeValue(null, "LyricContent")
            }
            event = parser.next()
        }
        null
    }.getOrNull()

    private fun qrcToXlrc(qrc: String): String {
        val output = mutableListOf<String>()
        val linePattern = Regex("""^\[(\d+),(\d+)](.*)$""")
        val wordPattern = Regex("""\((\d+),(\d+)\)""")
        qrc.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            val match = linePattern.matchEntire(line)
            if (match == null) {
                if (line.startsWith("[")) output += line
                return@forEach
            }
            val start = match.groupValues[1].toLongOrNull() ?: return@forEach
            val body = match.groupValues[3]
            val builder = StringBuilder("[").append(LyricProcessor.formatTime(start)).append("]<")
                .append(LyricProcessor.formatTime(start)).append('>')
            var cursor = 0
            wordPattern.findAll(body).forEach { word ->
                builder.append(body.substring(cursor, word.range.first))
                val offset = word.groupValues[1].toLongOrNull() ?: 0L
                val wordDuration = word.groupValues[2].toLongOrNull() ?: 0L
                builder.append('<').append(LyricProcessor.formatTime(start + offset + wordDuration)).append('>')
                cursor = word.range.last + 1
            }
            builder.append(body.substring(cursor))
            output += builder.toString()
        }
        return output.joinToString("\n")
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        InflaterInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            return output.toByteArray()
        }
    }
}

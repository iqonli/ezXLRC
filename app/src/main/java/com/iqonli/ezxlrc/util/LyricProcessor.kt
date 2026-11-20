package com.iqonli.ezxlrc.util

import java.util.Locale

/** LRC/XLRC transformations used by the editor and repair tools. */
object LyricProcessor {
    private const val TIME_PATTERN = """(?:\d{1,3}:)?\d{1,3}:\d{2}(?:[.,]\d{1,3})?"""
    private val bracketTime = Regex("""\[($TIME_PATTERN)\]""")
    private val angleTime = Regex("""<($TIME_PATTERN)>""")
    private val leadingTimes = Regex("""^\s*((?:\[$TIME_PATTERN\])+)(.*)$""")
    private val metadata = Regex("""^\s*\[(?:ar|ti|al|by|re|ve|offset|length|kana|la|tool|id|sign|language):.*]\s*$""", RegexOption.IGNORE_CASE)
    private val timestampOnly = Regex("""^\s*(?:\[$TIME_PATTERN]\s*)+$""")
    private val advancedChunk = Regex("""<($TIME_PATTERN)>([^<]*)""")

    fun toPlain(text: String): String = text.lineSequence()
        .filterNot { metadata.matches(it) }
        .map { line ->
            angleTime.replace(bracketTime.replace(line, ""), "").trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    fun advancedToLrc(text: String): String = text.lineSequence()
        .map { line -> angleTime.replace(line, "") }
        .joinToString("\n")

    fun plainToLrc(text: String, intervalSeconds: Int = 5): String {
        val interval = intervalSeconds.coerceIn(1, 600) * 1000L
        var nextTime = 0L
        val output = mutableListOf<String>()
        text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEach
            if (metadata.matches(line)) {
                output += line
            } else {
                output += "[${formatTime(nextTime)}]$line"
                nextTime += interval
            }
        }
        return output.joinToString("\n")
    }

    /** Generate word/character-level timestamps by distributing each line over the next line's start. */
    fun lrcToXlrc(text: String, fallbackDurationMs: Long = 4_000L): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val timed = lines.mapIndexedNotNull { index, line ->
            val match = leadingTimes.find(line) ?: return@mapIndexedNotNull null
            val times = bracketTime.findAll(match.groupValues[1]).map { parseTime(it.groupValues[1]) }.toList()
            if (times.isEmpty()) null else Triple(index, times, match.groupValues[2])
        }
        val nextStartByIndex = timed.associate { (index, times, _) ->
            index to (timed.firstOrNull { it.first > index }?.second?.firstOrNull() ?: (times.first() + fallbackDurationMs))
        }
        val output = lines.toMutableList()
        for ((index, times, rawContent) in timed) {
            if (angleTime.containsMatchIn(rawContent)) continue
            val content = splitCodePoints(rawContent)
            if (content.isEmpty()) continue
            val start = times.first()
            val end = maxOf(start + 1L, nextStartByIndex[index] ?: (start + fallbackDurationMs))
            val step = maxOf(1L, (end - start) / content.size)
            val builder = StringBuilder()
            for (time in times) builder.append('[').append(formatTime(time)).append(']')
            content.forEachIndexed { wordIndex, word ->
                builder.append('<').append(formatTime(start + step * wordIndex)).append('>').append(word)
            }
            builder.append('<').append(formatTime(end)).append('>')
            output[index] = builder.toString()
        }
        return output.joinToString("\n")
    }

    fun applyOffset(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val offset = lines.firstNotNullOfOrNull { line ->
            Regex("""^\s*\[offset:([+-]?\d+)]\s*$""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)?.toLongOrNull()
        } ?: return text
        if (offset == 0L) return lines.filterNot { Regex("""^\s*\[offset:.*]$""", RegexOption.IGNORE_CASE).matches(it) }.joinToString("\n")
        val adjusted = lines.filterNot { Regex("""^\s*\[offset:.*]$""", RegexOption.IGNORE_CASE).matches(it) }.map { line ->
            val withBrackets = bracketTime.replace(line) { match ->
                "[${formatTime((parseTime(match.groupValues[1]) + offset).coerceAtLeast(0L))}]"
            }
            angleTime.replace(withBrackets) { match ->
                "<${formatTime((parseTime(match.groupValues[1]) + offset).coerceAtLeast(0L))}>"
            }
        }
        return adjusted.joinToString("\n")
    }

    fun advancedToProgressive(text: String, progressive: Boolean = true): String = advancedToTimedLines(text, progressive, null)

    fun advancedToWords(text: String, progressive: Boolean = false): String = advancedToTimedLines(text, progressive, null)

    private fun advancedToTimedLines(text: String, progressive: Boolean, customSegments: List<String>?): String {
        val output = mutableListOf<String>()
        var segmentIndex = 0
        text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            val lead = leadingTimes.find(line)
            if (lead == null || !line.contains('<')) {
                if (line.isNotEmpty()) output += line
                return@forEach
            }
            val chunks = advancedChunk.findAll(lead.groupValues[2]).mapNotNull { match ->
                val value = match.groupValues[2]
                if (value.isEmpty()) null else parseTime(match.groupValues[1]) to value
            }.toList()
            if (chunks.isEmpty()) {
                output += line
                return@forEach
            }
            if (customSegments == null) {
                var accumulated = StringBuilder()
                chunks.forEach { (time, content) ->
                    accumulated.append(content)
                    output += "[${formatTime(time)}]${if (progressive) accumulated else content}"
                }
                return@forEach
            }

            val segmentation = customSegments.getOrNull(segmentIndex++).orEmpty()
            val segments = segmentation.split('_').filter { it.isNotEmpty() }
            if (segments.isEmpty()) {
                output += line
                return@forEach
            }

            val timedCharacters = ArrayList<Pair<String, Long>>()
            chunks.forEach { (time, content) ->
                var offset = 0
                while (offset < content.length) {
                    val codePoint = content.codePointAt(offset)
                    timedCharacters += String(Character.toChars(codePoint)) to time
                    offset += Character.charCount(codePoint)
                }
            }
            val fullText = timedCharacters.joinToString("") { it.first }
            var cursor = 0
            val accumulated = StringBuilder()
            val converted = ArrayList<String>()
            var valid = true
            for (segment in segments) {
                val segmentCharacters = ArrayList<String>()
                var offset = 0
                while (offset < segment.length) {
                    val codePoint = segment.codePointAt(offset)
                    segmentCharacters += String(Character.toChars(codePoint))
                    offset += Character.charCount(codePoint)
                }
                if (cursor + segmentCharacters.size > timedCharacters.size) {
                    valid = false
                    break
                }
                val actual = timedCharacters.subList(cursor, cursor + segmentCharacters.size).map { it.first }
                if (actual != segmentCharacters) {
                    valid = false
                    break
                }
                val time = timedCharacters[cursor].second
                accumulated.append(segment)
                converted += "[${formatTime(time)}]${if (progressive) accumulated else segment}"
                cursor += segmentCharacters.size
            }
            if (valid && cursor == timedCharacters.size && fullText == segments.joinToString("")) {
                output += converted
            } else {
                // A segmentation line must cover the XLRC text exactly; keep the source line on mismatch.
                output += line
            }
        }
        return output.joinToString("\n")
    }

    /** Export one line per XLRC lyric line for a user-supplied underscore-separated segmentation file. */
    fun generateSplitText(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').lineSequence()
        .mapNotNull { line ->
            val lead = leadingTimes.find(line) ?: return@mapNotNull null
            if (!line.contains('<')) return@mapNotNull null
            advancedChunk.findAll(lead.groupValues[2]).joinToString("") { it.groupValues[2] }.takeIf { it.isNotEmpty() }
        }.joinToString("\n")

    fun advancedToCustom(text: String, segmentationText: String, progressive: Boolean = false): String {
        val segments = segmentationText.replace("\r\n", "\n").replace('\r', '\n').lines()
        return advancedToTimedLines(text, progressive, segments)
    }

    /** Move each consecutive block of standalone timestamp rows by one lyric row. Blank and metadata rows stay fixed. */
    fun shiftTimestampRows(text: String, direction: Int): String {
        require(direction == -1 || direction == 1) { "direction must be -1 (up) or 1 (down)" }
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val trailingNewline = text.endsWith('\n')
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        if (trailingNewline && lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)

        // Keep metadata and blank separators at their original positions. The remaining rows are
        // compared in sequence, so a timestamp block moves across one lyric row even when blank
        // lines separate the visible rows in the source file.
        val positions = lines.indices.filter { index ->
            lines[index].isNotBlank() && !metadata.matches(lines[index])
        }
        val movable = positions.map { lines[it] }.toMutableList()
        fun isTimestampRow(value: String) = timestampOnly.matches(value)

        if (direction < 0) {
            var index = 1
            while (index < movable.size) {
                if (isTimestampRow(movable[index]) && !isTimestampRow(movable[index - 1])) {
                    var end = index
                    while (end + 1 < movable.size && isTimestampRow(movable[end + 1])) end++
                    val previousLyric = movable.removeAt(index - 1)
                    movable.add(end, previousLyric)
                    index = end + 1
                } else {
                    index++
                }
            }
        } else {
            var index = movable.lastIndex - 1
            while (index >= 0) {
                if (isTimestampRow(movable[index]) && !isTimestampRow(movable[index + 1])) {
                    var start = index
                    while (start > 0 && isTimestampRow(movable[start - 1])) start--
                    val nextLyric = movable.removeAt(index + 1)
                    movable.add(start, nextLyric)
                    index = start - 1
                } else {
                    index--
                }
            }
        }

        positions.forEachIndexed { index, originalPosition -> lines[originalPosition] = movable[index] }
        return lines.joinToString(newline) + if (trailingNewline) newline else ""
    }

    /** Remove the n-th occurrence from every duplicated leading-timestamp group. n is one-based. */
    fun deleteNthDuplicateTimestampLine(text: String, n: Int): Pair<String, Int> {
        require(n >= 1) { "n must be at least 1" }
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val trailingNewline = text.endsWith('\n')
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        if (trailingNewline && lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        val groups = linkedMapOf<Long, MutableList<Int>>()
        lines.forEachIndexed { index, line ->
            if (metadata.matches(line)) return@forEachIndexed
            val leading = leadingTimes.find(line.trimStart()) ?: return@forEachIndexed
            val timestamp = bracketTime.find(leading.groupValues[1])?.groupValues?.get(1) ?: return@forEachIndexed
            groups.getOrPut(parseTime(timestamp)) { mutableListOf() }.add(index)
        }
        val remove = groups.values.filter { it.size > 1 && it.size >= n }.map { it[n - 1] }.toSet()
        val output = lines.filterIndexed { index, _ -> index !in remove }.joinToString(newline)
        return (output + if (trailingNewline) newline else "") to remove.size
    }

    private fun splitCodePoints(value: String): List<String> {
        val result = ArrayList<String>()
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            result += String(Character.toChars(codePoint))
            index += Character.charCount(codePoint)
        }
        return result
    }

    fun parseTime(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        if (parts.size < 2 || parts.size > 3) return 0L
        val hours: Long
        val minutes: Long
        val secondsPart: String
        if (parts.size == 3) {
            hours = parts[0].toLongOrNull() ?: 0L
            minutes = parts[1].toLongOrNull() ?: 0L
            secondsPart = parts[2]
        } else {
            hours = 0L
            minutes = parts[0].toLongOrNull() ?: 0L
            secondsPart = parts[1]
        }
        val secParts = secondsPart.split('.')
        val seconds = secParts[0].toLongOrNull() ?: 0L
        val fraction = secParts.getOrNull(1).orEmpty().take(3).padEnd(3, '0').toLongOrNull() ?: 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fraction
    }

    fun formatTime(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val totalSeconds = safe / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        val centiseconds = (safe % 1000) / 10
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%02d", hours, minutes, seconds, centiseconds)
        } else {
            String.format(Locale.US, "%02d:%02d.%02d", totalSeconds / 60, seconds, centiseconds)
        }
    }
}

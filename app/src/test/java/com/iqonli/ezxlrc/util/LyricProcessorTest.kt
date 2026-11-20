package com.iqonli.ezxlrc.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricProcessorTest {
    @Test
    fun supportsHourBasedTimestampTags() {
        assertEquals(
            "[01:02:03.00]<01:02:03.00>w<01:02:04.00>",
            LyricProcessor.lrcToXlrc("[01:02:03.00]w", 1_000L)
        )
        assertEquals("word", LyricProcessor.toPlain("[01:02:03.00]<01:02:03.00>word<01:02:04.00>"))
    }

    @Test
    fun plainTextToLrcUsesConfiguredInterval() {
        assertEquals(
            "[00:00.00]第一行\n[00:05.00]第二行",
            LyricProcessor.plainToLrc("第一行\n第二行", 5)
        )
    }

    @Test
    fun lrcToPlainRemovesMetadataAndBothTimestampKinds() {
        val input = "[ti:Title]\n[00:01.00]<00:01.00>hello<00:01.50> world<00:02.00>\n[00:03.00]again"
        assertEquals("hello world\nagain", LyricProcessor.toPlain(input))
    }

    @Test
    fun advancedToSimpleKeepsLineTimestampAndText() {
        assertEquals(
            "[00:01.00]hello world",
            LyricProcessor.advancedToLrc("[00:01.00]<00:01.00>hello<00:01.50> world<00:02.00>")
        )
    }

    @Test
    fun advancedToProgressiveAndWordModesUseSubTimestamp() {
        val input = "[00:01.00]<00:01.00>hel<00:01.50>lo<00:02.00>"
        assertEquals(
            "[00:01.00]hel\n[00:01.50]hello",
            LyricProcessor.advancedToProgressive(input, true)
        )
        assertEquals(
            "[00:01.00]hel\n[00:01.50]lo",
            LyricProcessor.advancedToWords(input, false)
        )
    }

    @Test
    fun offsetAppliesToLineAndWordTimestampsAndRemovesMetadata() {
        assertEquals(
            "[00:02.00]<00:02.50>word<00:03.00>",
            LyricProcessor.applyOffset("[offset:1000]\n[00:01.00]<00:01.50>word<00:02.00>")
        )
    }

    @Test
    fun lrcToXlrcDistributesWordTimesBetweenLineStarts() {
        assertEquals(
            "[00:00.00]<00:00.00>a<00:02.00>b<00:04.00>\n[00:04.00]<00:04.00>c<00:08.00>",
            LyricProcessor.lrcToXlrc("[00:00.00]ab\n[00:04.00]c")
        )
    }

    @Test
    fun timestampRowsCanMoveUpAndDownRelativeToLyricRows() {
        val upInput = "[ar:artist]\n歌词一\n[00:01.00]\n歌词二\n[00:02.00]"
        assertEquals(
            "[ar:artist]\n[00:01.00]\n歌词一\n[00:02.00]\n歌词二",
            LyricProcessor.shiftTimestampRows(upInput, -1)
        )
        val downInput = "[ar:artist]\n[00:01.00]\n歌词一\n[00:02.00]\n歌词二"
        assertEquals(
            "[ar:artist]\n歌词一\n[00:01.00]\n歌词二\n[00:02.00]",
            LyricProcessor.shiftTimestampRows(downInput, 1)
        )
    }

    @Test
    fun shiftsConsecutiveTimestampRowsAsOneBlock() {
        val up = "[ar:artist]\n歌词一\n[00:01.00]\n[00:02.00]\n歌词二"
        assertEquals(
            "[ar:artist]\n[00:01.00]\n[00:02.00]\n歌词一\n歌词二",
            LyricProcessor.shiftTimestampRows(up, -1)
        )
        val down = "[ar:artist]\n[00:01.00]\n[00:02.00]\n歌词一\n歌词二"
        assertEquals(
            "[ar:artist]\n歌词一\n[00:01.00]\n[00:02.00]\n歌词二",
            LyricProcessor.shiftTimestampRows(down, 1)
        )
    }

    @Test
    fun customSegmentationUsesTheTimestampOfEachWordStart() {
        val input = "[00:01.00]<00:01.00>New<00:01.50> York<00:02.00>"
        assertEquals(
            "[00:01.00]New\n[00:01.50] York",
            LyricProcessor.advancedToCustom(input, "New_ York", false)
        )
        assertEquals(input, LyricProcessor.advancedToCustom(input, "New_ York!", false))
    }

    @Test
    fun preservesWindowsLineEndingsWhenRepairing() {
        val input = "[00:01.00]\r\n歌词一\r\n[00:02.00]"
        assertEquals(
            "歌词一\r\n[00:01.00]\r\n[00:02.00]",
            LyricProcessor.shiftTimestampRows(input, 1)
        )
    }


    @Test
    fun acceptsLeadingWhitespaceAndCommaDecimalTimestamps() {
        assertEquals(1_500L, LyricProcessor.parseTime("00:01,5"))
        assertEquals(
            "[00:01.50]<00:01.50>a<00:03.00>\n[00:03.00]<00:03.00>b<00:07.00>",
            LyricProcessor.lrcToXlrc(" [00:01,50]a\n[00:03.00]b")
        )
    }

    @Test
    fun timestampRepairDoesNotCountBlankOrMetadataRows() {
        val input = "[ar:artist]\n歌词一\n\n[00:01.00]\n\n歌词二"
        assertEquals(
            "[ar:artist]\n[00:01.00]\n\n歌词一\n\n歌词二",
            LyricProcessor.shiftTimestampRows(input, -1)
        )
    }

    @Test
    fun duplicateTimestampComparisonUsesActualTimeNotFormatting() {
        val input = "[00:01.0]first\n[00:01.00]second\n[00:02.00]other"
        val (output, removed) = LyricProcessor.deleteNthDuplicateTimestampLine(input, 2)
        assertEquals(1, removed)
        assertEquals("[00:01.0]first\n[00:02.00]other", output)
    }

    @Test
    fun duplicateTimestampDeletionKeepsUniqueGroupsAndSupportsFirstOccurrence() {
        val input = "[00:01.00]one\n[00:02.00]unique\n[00:01.00]two\n[00:01.00]three"
        val (output, removed) = LyricProcessor.deleteNthDuplicateTimestampLine(input, 1)
        assertEquals(1, removed)
        assertEquals("[00:02.00]unique\n[00:01.00]two\n[00:01.00]three", output)
    }

    @Test
    fun timestampRepairDoesNotWrapAtFileBoundaries() {
        val first = "[00:01.00]\n歌词一"
        val last = "歌词一\n[00:02.00]"
        assertEquals(first, LyricProcessor.shiftTimestampRows(first, -1))
        assertEquals(last, LyricProcessor.shiftTimestampRows(last, 1))
    }

    @Test
    fun removesNthOccurrenceFromEachDuplicateTimestampGroup() {
        val input = "[00:01.00]first\n[00:01.00]second\n[00:02.00]other\n[00:01.00]third"
        val (output, removed) = LyricProcessor.deleteNthDuplicateTimestampLine(input, 2)
        assertEquals(1, removed)
        assertEquals("[00:01.00]first\n[00:02.00]other\n[00:01.00]third", output)
    }
}

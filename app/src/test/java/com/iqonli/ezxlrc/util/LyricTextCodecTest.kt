package com.iqonli.ezxlrc.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

class LyricTextCodecTest {
    @Test
    fun decodesUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "歌词".toByteArray(StandardCharsets.UTF_8)
        assertEquals("歌词", LyricTextCodec.decode(bytes))
    }

    @Test
    fun decodesUtf16LittleEndianBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "歌词".toByteArray(StandardCharsets.UTF_16LE)
        assertEquals("歌词", LyricTextCodec.decode(bytes))
    }

    @Test
    fun fallsBackToGb18030ForLegacyLrcFiles() {
        val bytes = "歌词".toByteArray(Charset.forName("GB18030"))
        assertEquals("歌词", LyricTextCodec.decode(bytes))
    }
}

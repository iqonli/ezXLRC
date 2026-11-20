package com.iqonli.ezxlrc.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class LyricDecodersTest {
    @Test
    fun decodesKrcAndPreservesWordTiming() {
        val plain = "[0,1000]<0,500,0>hello<500,500,0>world".toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(plain) }
        }.toByteArray()
        val key = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
        )
        val encrypted = compressed.mapIndexed { index, byte ->
            (byte.toInt() xor (key[index % key.size].toInt() and 0xff)).toByte()
        }.toByteArray()
        val payload = byteArrayOf(0x6b, 0x72, 0x63, 0x31) + encrypted

        assertEquals(
            "[00:00.00]<00:00.00>hello<00:00.50>world<00:01.00>",
            LyricDecoders.decodeKrc(payload)
        )
    }
}

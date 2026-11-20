package com.iqonli.ezxlrc.data

data class LyricSource(
    val name: String,
    val version: String,
    val author: String,
    val uri: String
) {
    override fun toString(): String = "$name  ·  $version"
}

data class LyricResult(
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val fileType: String,
    val lyricText: String,
    val location: String = ""
)

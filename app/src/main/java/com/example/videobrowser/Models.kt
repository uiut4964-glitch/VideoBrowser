package com.example.videobrowser

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MediaItem(val url: String, val type: String)

class Format(
    val label: String,
    val details: String,
    val url: String,
    val kind: String,
    val ext: String,
    val fromMaster: Boolean = false,
    val sizeText: String = ""
)

object St {
    const val QUEUED = 0
    const val DOWNLOADING = 1
    const val PAUSED = 2
    const val MERGING = 3
    const val DONE = 4
    const val FAILED = 5
}

class Task(
    val id: String,
    val url: String,
    var name: String,
    val kind: String,
    val headers: Map<String, String>
) {
    var total = -1L
    var parts = 0
    @Volatile var status = St.QUEUED
    @Volatile var error = ""
    var savedUri = ""
    var created = System.currentTimeMillis()
    var mime = "video/mp4"
    var segCount = 0
    var rangeOk = false
    var thumbUrl = ""
    val downloaded = AtomicLong(0)
    val segDone = AtomicInteger(0)
    @Volatile var speed = 0L
    @Volatile var stop = false
    @Volatile var gen = 0
    var lastBytes = 0L
    val lock = Any()

    fun permille(): Int =
        if (kind == "hls") {
            if (segCount > 0) (segDone.get() * 1000L / segCount).toInt() else 0
        } else {
            if (total > 0) (downloaded.get() * 1000L / total).toInt() else 0
        }
}

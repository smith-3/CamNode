package com.fragmind.camnode.recorder

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

/** Deletes recordings older than [days]. Returns how many files were removed. */
fun pruneRecordings(root: Path, days: Int, now: Instant = Instant.now()): Int {
    if (!Files.isDirectory(root)) return 0
    val cutoff = now.minus(Duration.ofDays(days.toLong()))
    return Files.walk(root).use { paths ->
        paths.filter { it.isRegularFile() && it.extension == "mp4" && it.getLastModifiedTime().toInstant() < cutoff }
            .toList()
    }.count { Files.deleteIfExists(it) }
}

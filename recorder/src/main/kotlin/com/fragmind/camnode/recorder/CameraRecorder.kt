package com.fragmind.camnode.recorder

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * One ffmpeg process per camera. It copies the H.264/AAC stream without re-encoding into
 * fragmented MP4 segments, so a segment stays playable even if the computer loses power.
 */
class CameraRecorder(private val cameraDir: Path, private val segmentSeconds: Int) {
    private var process: Process? = null
    private var url: String? = null

    val recording: Boolean get() = process?.isAlive == true

    /** Starts recording [rtspUrl], or restarts when the route changed or ffmpeg exited. */
    fun ensure(rtspUrl: String) {
        if (recording && url == rtspUrl) return
        stop()
        Files.createDirectories(cameraDir)
        url = rtspUrl
        process = ProcessBuilder(
            "ffmpeg", "-nostdin", "-loglevel", "error",
            "-rtsp_transport", "tcp", "-i", rtspUrl,
            "-map", "0", "-c", "copy",
            "-f", "segment", "-segment_time", segmentSeconds.toString(), "-reset_timestamps", "1", "-strftime", "1",
            "-segment_format", "mp4", "-segment_format_options", "movflags=+frag_keyframe+empty_moov+default_base_moof",
            cameraDir.resolve("%Y-%m-%d_%H-%M-%S.mp4").toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(cameraDir.resolve("ffmpeg.log").toFile())
            .start()
    }

    fun stop() {
        process?.let { running ->
            running.destroy()
            if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
        }
        process = null
        url = null
    }
}

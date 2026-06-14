package cz.skybit.mrsd

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** A downloaded audio file plus the metadata needed to build an M4B chapter. */
data class DownloadedEpisode(
    val title: String,
    val number: Int,
    val duration: Int,
    val audioFile: File,
)

/**
 * Wraps ffmpeg + plain HTTP downloads. Produces M4A part files (AAC stream copy)
 * and combines them into a single M4B audiobook with chapters and cover art.
 */
class Downloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkFfmpeg() {
        try {
            val process = ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readLine()
            if (process.waitFor() != 0) throw RuntimeException("ffmpeg returned non-zero exit code")
        } catch (e: Exception) {
            throw RuntimeException("ffmpeg not found in PATH. Please install ffmpeg.", e)
        }
    }

    /** Download an HLS stream into an M4A container (AAC stream copy, no transcoding). */
    fun downloadHlsToM4a(hlsUrl: String, outputFile: File) {
        runFfmpeg("-i", hlsUrl, "-c", "copy", "-movflags", "+faststart", outputFile.absolutePath)
    }

    /** Download a plain file (e.g. MP3 audio or a cover image) over HTTP. */
    fun downloadFile(url: String, outputFile: File) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Download failed: ${response.code} for $url")
            response.body.byteStream().use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** Download a cover image into [targetDir]/cover.jpg, or return null on failure. */
    fun downloadCover(imageUrl: String?, targetDir: File): File? {
        if (imageUrl == null) return null
        val file = File(targetDir, "cover.jpg")
        return try {
            downloadFile(imageUrl, file)
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Combine M4A/MP3 episode files into a single M4B audiobook with chapter
     * markers and an optional embedded cover image.
     */
    fun combineToM4b(
        episodes: List<DownloadedEpisode>,
        bookTitle: String,
        coverFile: File?,
        outputFile: File,
    ) {
        val sorted = episodes.sortedBy { it.number }
        val parentDir = outputFile.parentFile

        val concatFile = File(parentDir, ".concat.txt")
        concatFile.writeText(sorted.joinToString("\n") { ep ->
            "file '${ep.audioFile.absolutePath.replace("'", "'\\''")}'"
        })

        val metadataFile = File(parentDir, ".metadata.txt")
        val md = StringBuilder()
        md.appendLine(";FFMETADATA1")
        md.appendLine("title=${escapeMetadata(bookTitle)}")
        var startMs = 0L
        for (ep in sorted) {
            val endMs = startMs + ep.duration * 1000L
            md.appendLine()
            md.appendLine("[CHAPTER]")
            md.appendLine("TIMEBASE=1/1000")
            md.appendLine("START=$startMs")
            md.appendLine("END=$endMs")
            md.appendLine("title=${escapeMetadata(ep.title)}")
            startMs = endMs
        }
        metadataFile.writeText(md.toString())

        try {
            val args = mutableListOf(
                "-f", "concat", "-safe", "0", "-i", concatFile.absolutePath,
                "-f", "ffmetadata", "-i", metadataFile.absolutePath,
            )
            // Stream-copy AAC sources; transcode only if a source isn't already M4A.
            val needsTranscode = sorted.any { !it.audioFile.name.endsWith(".m4a") }
            val audioCodec = if (needsTranscode) listOf("-c:a", "aac", "-b:a", "128k") else listOf("-c:a", "copy")

            if (coverFile != null && coverFile.exists()) {
                args.addAll(listOf("-i", coverFile.absolutePath))
                args.addAll(listOf("-map", "0:a", "-map", "2:v"))
                args.addAll(audioCodec)
                args.addAll(listOf("-c:v", "mjpeg", "-disposition:v:0", "attached_pic"))
            } else {
                args.addAll(listOf("-map", "0:a"))
                args.addAll(audioCodec)
            }
            args.addAll(listOf("-map_metadata", "1", outputFile.absolutePath))

            runFfmpeg(*args.toTypedArray())
        } finally {
            concatFile.delete()
            metadataFile.delete()
        }
    }

    private fun runFfmpeg(vararg args: String) {
        val command = listOf("ffmpeg", "-y", "-loglevel", "error") + args.toList()
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val stderr = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            args.lastOrNull()?.let { File(it).delete() }
            throw RuntimeException("ffmpeg failed: $stderr")
        }
    }

    companion object {
        fun sanitizeFilename(name: String): String =
            name.replace(Regex("[/\\\\<>:\"|?*]"), "_").trim()

        /** Number of digits needed to represent [n] (minimum 2). */
        fun digitCount(n: Int): Int = maxOf(2, n.toString().length)

        fun isPlayable(link: AudioLink): Boolean {
            val till = link.playableTill ?: return true
            return try {
                OffsetDateTime.now().isBefore(
                    OffsetDateTime.parse(till, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                )
            } catch (_: Exception) {
                true
            }
        }

        private fun escapeMetadata(value: String): String =
            value.replace("\\", "\\\\").replace("=", "\\=")
                .replace(";", "\\;").replace("#", "\\#").replace("\n", " ")
    }
}

package cz.skybit.mrsd

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * Orchestrates everything after a URL is resolved: persists the item, downloads
 * the available parts one at a time, builds the final M4B when a serial is
 * complete, retries waiting serials once a day, and soft-deletes on request.
 */
class DownloadService(
    private val api: Api,
    private val resolver: UrlResolver,
    private val downloader: Downloader,
    private val outputDir: File,
    private val deletedDir: File,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(DownloadService::class.java)
    private val channel = Channel<String>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var scheduler: Job? = null

    data class AddResult(val itemId: String, val isNew: Boolean, val title: String)

    // --- Lifecycle ---

    fun start(retryIntervalHours: Long = 24) {
        outputDir.mkdirs()
        deletedDir.mkdirs()
        recover()

        worker = scope.launch {
            for (itemId in channel) {
                try {
                    processItem(itemId)
                } catch (e: Exception) {
                    log.error("Unexpected error processing item $itemId: ${e.message}", e)
                    markError(itemId, e.message ?: "Unknown error")
                }
            }
        }

        scheduler = scope.launch {
            while (isActive) {
                delay(retryIntervalHours * 3600 * 1000)
                retryWaiting()
            }
        }
    }

    suspend fun shutdown() {
        channel.close()
        scheduler?.cancelAndJoin()
        worker?.join()
    }

    /** Re-enqueue items that were mid-download when the process stopped. */
    private fun recover() {
        val stuck = transaction {
            Items.selectAll().where { Items.status eq ItemStatus.DOWNLOADING }.map { it[Items.id] }
        }
        for (id in stuck) channel.trySend(id)
        if (stuck.isNotEmpty()) log.info("Re-enqueued ${stuck.size} interrupted item(s)")
    }

    // --- Public actions ---

    /** Resolve a URL, persist the item (if new), and enqueue it for download. */
    fun addFromUrl(url: String): AddResult {
        val resolution = resolver.resolve(url.trim())
        val result = when (resolution) {
            is UrlResolver.Resolution.Single -> persistSingle(url.trim(), resolution)
            is UrlResolver.Resolution.Series -> persistSeries(url.trim(), resolution)
        }
        if (result.isNew) channel.trySend(result.itemId)
        return result
    }

    /** Re-fetch fresh metadata/audio for an item, then enqueue it. Used by "fetch now" and "retry". */
    fun refreshAndEnqueue(itemId: String) {
        try {
            refreshMetadata(itemId)
        } catch (e: Exception) {
            log.warn("Refresh of $itemId failed: ${e.message}")
        }
        channel.trySend(itemId)
    }

    /** Remove an item from the DB and move its folder to the deleted directory. */
    fun deleteItem(itemId: String) {
        val dirName = transaction {
            Items.selectAll().where { Items.id eq itemId }.firstOrNull()?.get(Items.dirName)
        } ?: return

        transaction {
            Parts.deleteWhere { Parts.itemId eq itemId }
            Items.deleteWhere { Items.id eq itemId }
        }

        val src = File(outputDir, dirName)
        if (src.exists()) {
            var dest = File(deletedDir, dirName)
            var n = 2
            while (dest.exists()) dest = File(deletedDir, "$dirName ($n)").also { n++ }
            if (!src.renameTo(dest)) {
                src.copyRecursively(dest, overwrite = true)
                src.deleteRecursively()
            }
            log.info("Soft-deleted '$dirName' -> ${dest.absolutePath}")
        }
    }

    // --- Persistence of new items ---

    private fun persistSingle(url: String, res: UrlResolver.Resolution.Single): AddResult {
        val ep = res.episode
        if (existing(ep.uuid)) return AddResult(ep.uuid, false, ep.title)

        val dir = uniqueDirName(ep.title)
        val best = ep.audioLinks.bestAudioLink()
        transaction {
            insertItem(ep.uuid, ItemType.SINGLE, ep.title, url, ep.imageUrl ?: res.show?.imageUrl, 1, dir)
            insertPart(ep.uuid, ep.uuid, ep.orderNumber, ep.title, best)
        }
        return AddResult(ep.uuid, true, ep.title)
    }

    private fun persistSeries(url: String, res: UrlResolver.Resolution.Series): AddResult {
        val serial = res.serial
        if (existing(serial.uuid)) return AddResult(serial.uuid, false, serial.title)

        val dir = uniqueDirName(serial.title)
        transaction {
            insertItem(serial.uuid, ItemType.SERIES, serial.title, url, serial.imageUrl, serial.totalParts, dir)
            for (ep in res.episodes) {
                insertPart(ep.uuid, serial.uuid, ep.orderNumber, ep.title, ep.audioLinks.bestAudioLink())
            }
        }
        return AddResult(serial.uuid, true, serial.title)
    }

    private fun existing(id: String): Boolean = transaction {
        Items.selectAll().where { Items.id eq id }.count() > 0
    }

    private fun insertItem(
        id: String, type: ItemType, title: String, url: String,
        imageUrl: String?, totalParts: Int, dirName: String,
    ) {
        val now = Instant.now()
        Items.insert {
            it[Items.id] = id
            it[Items.type] = type
            it[Items.title] = title
            it[originUrl] = url
            it[Items.imageUrl] = imageUrl
            it[status] = ItemStatus.DOWNLOADING
            it[Items.totalParts] = totalParts
            it[Items.dirName] = dirName
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private fun insertPart(id: String, itemId: String, number: Int, title: String, link: AudioLink?) {
        Parts.insert {
            it[Parts.id] = id
            it[Parts.itemId] = itemId
            it[Parts.number] = number
            it[Parts.title] = title
            it[duration] = link?.duration ?: 0
            it[audioUrl] = link?.url
            it[audioVariant] = link?.variant ?: "hls"
            it[playableTill] = link?.playableTill
        }
    }

    // --- Refresh (pick up newly published / now-available parts) ---

    private fun refreshMetadata(itemId: String) {
        val item = transaction { Items.selectAll().where { Items.id eq itemId }.firstOrNull() } ?: return
        when (item[Items.type]) {
            ItemType.SERIES -> {
                val serial = api.getSerial(itemId)
                val episodes = api.getSerialEpisodes(itemId)
                transaction {
                    Items.update({ Items.id eq itemId }) {
                        it[totalParts] = serial.totalParts
                        it[updatedAt] = Instant.now()
                    }
                    val knownIds = Parts.selectAll().where { Parts.itemId eq itemId }
                        .map { it[Parts.id] }.toSet()
                    for (ep in episodes) {
                        val best = ep.audioLinks.bestAudioLink()
                        if (ep.uuid !in knownIds) {
                            insertPart(ep.uuid, itemId, ep.orderNumber, ep.title, best)
                        } else if (best != null) {
                            // Refresh audio for not-yet-downloaded parts (links expire / appear late).
                            Parts.update({ (Parts.id eq ep.uuid) and (Parts.downloaded eq false) }) {
                                it[duration] = best.duration
                                it[audioUrl] = best.url
                                it[audioVariant] = best.variant
                                it[playableTill] = best.playableTill
                            }
                        }
                    }
                }
            }
            ItemType.SINGLE -> {
                val ep = api.getEpisode(itemId)
                val best = ep.audioLinks.bestAudioLink() ?: return
                transaction {
                    Parts.update({ (Parts.id eq itemId) and (Parts.downloaded eq false) }) {
                        it[duration] = best.duration
                        it[audioUrl] = best.url
                        it[audioVariant] = best.variant
                        it[playableTill] = best.playableTill
                    }
                }
            }
        }
    }

    private fun retryWaiting() {
        val waiting = transaction {
            Items.selectAll().where { Items.status eq ItemStatus.WAITING }.map { it[Items.id] }
        }
        if (waiting.isEmpty()) return
        log.info("Daily retry: ${waiting.size} waiting item(s)")
        for (id in waiting) refreshAndEnqueue(id)
    }

    // --- Core processing ---

    private suspend fun processItem(itemId: String) {
        val item = transaction { Items.selectAll().where { Items.id eq itemId }.firstOrNull() } ?: return
        val type = item[Items.type]
        val title = item[Items.title]
        val totalParts = item[Items.totalParts]
        val dir = File(outputDir, item[Items.dirName]).also { it.mkdirs() }

        transaction {
            Items.update({ Items.id eq itemId }) {
                it[status] = ItemStatus.DOWNLOADING
                it[errorMessage] = null
                it[updatedAt] = Instant.now()
            }
        }

        val pending = transaction {
            Parts.selectAll()
                .where { (Parts.itemId eq itemId) and (Parts.downloaded eq false) }
                .orderBy(Parts.number)
                .map { PartRec(it[Parts.id], it[Parts.number], it[Parts.title], it[Parts.duration], it[Parts.audioUrl], it[Parts.audioVariant], it[Parts.playableTill]) }
        }

        val padWidth = Downloader.digitCount(maxOf(totalParts, pendingPlusDone(itemId)))
        var hadError = false
        for (p in pending) {
            // Stop if the item was deleted (e.g. from the dashboard) mid-download.
            if (!existing(itemId)) {
                log.info("Item $itemId deleted during download, stopping")
                return
            }
            if (p.audioUrl == null) continue // not published yet
            if (p.playableTill != null && !Downloader.isPlayable(AudioLink(p.audioUrl, p.audioVariant, p.duration, 0, p.playableTill))) {
                continue // expired window; treat as not currently available
            }
            val baseName = if (type == ItemType.SERIES && p.number > 0) {
                "%0${padWidth}d - %s".format(p.number, Downloader.sanitizeFilename(title))
            } else {
                Downloader.sanitizeFilename(p.title)
            }
            val ext = if (p.audioVariant == "mp3") "mp3" else "m4a"
            val outFile = File(dir, "$baseName.$ext")
            try {
                withContext(Dispatchers.IO) {
                    log.info("Downloading: ${p.title} -> ${outFile.name}")
                    if (p.audioVariant == "mp3") downloader.downloadFile(p.audioUrl, outFile)
                    else downloader.downloadHlsToM4a(p.audioUrl, outFile)
                }
                transaction {
                    Parts.update({ Parts.id eq p.id }) {
                        it[downloaded] = true
                        it[filePath] = outFile.absolutePath
                        it[errorMessage] = null
                    }
                }
            } catch (e: Exception) {
                hadError = true
                log.error("Download failed for ${p.title}: ${e.message}")
                transaction {
                    Parts.update({ Parts.id eq p.id }) { it[errorMessage] = e.message?.take(2000) }
                }
            }
        }

        val downloadedCount = transaction {
            Parts.selectAll().where { (Parts.itemId eq itemId) and (Parts.downloaded eq true) }.count().toInt()
        }
        val knownCount = pendingPlusDone(itemId)

        if (hadError) {
            markError(itemId, "Některé díly se nepodařilo stáhnout.")
            return
        }

        val complete = when (type) {
            ItemType.SINGLE -> downloadedCount >= 1
            ItemType.SERIES ->
                if (totalParts > 0) downloadedCount >= totalParts
                else knownCount > 0 && downloadedCount >= knownCount
        }

        if (!complete) {
            if (downloadedCount == 0 && type == ItemType.SINGLE) {
                markError(itemId, "Audio není k dispozici.")
            } else {
                setStatus(itemId, ItemStatus.WAITING)
                log.info("'$title' waiting: $downloadedCount/$totalParts parts downloaded")
            }
            return
        }

        try {
            buildM4b(itemId, title, dir, item[Items.imageUrl])
            setStatus(itemId, ItemStatus.COMPLETED)
            log.info("'$title' completed")
        } catch (e: Exception) {
            log.error("M4B build failed for '$title': ${e.message}", e)
            markError(itemId, "M4B se nepodařilo vytvořit: ${e.message}")
        }
    }

    private fun buildM4b(itemId: String, title: String, dir: File, imageUrl: String?) {
        val episodes = transaction {
            Parts.selectAll()
                .where { (Parts.itemId eq itemId) and (Parts.downloaded eq true) }
                .orderBy(Parts.number)
                .mapNotNull { row ->
                    val path = row[Parts.filePath] ?: return@mapNotNull null
                    DownloadedEpisode(row[Parts.title], row[Parts.number], row[Parts.duration], File(path))
                }
        }
        require(episodes.isNotEmpty()) { "no downloaded parts" }
        val missing = episodes.filter { !it.audioFile.exists() }
        require(missing.isEmpty()) { "missing audio files: ${missing.joinToString { it.audioFile.name }}" }

        val cover = downloader.downloadCover(imageUrl, dir)
        val m4b = File(dir, "${Downloader.sanitizeFilename(title)}.m4b")
        downloader.combineToM4b(episodes, title, cover, m4b)
        transaction {
            Items.update({ Items.id eq itemId }) {
                it[m4bPath] = m4b.absolutePath
                it[updatedAt] = Instant.now()
            }
        }
    }

    // --- Small helpers ---

    private fun pendingPlusDone(itemId: String): Int = transaction {
        Parts.selectAll().where { Parts.itemId eq itemId }.count().toInt()
    }

    private fun setStatus(itemId: String, status: ItemStatus) = transaction {
        Items.update({ Items.id eq itemId }) {
            it[Items.status] = status
            it[updatedAt] = Instant.now()
        }
    }

    private fun markError(itemId: String, message: String) = transaction {
        Items.update({ Items.id eq itemId }) {
            it[status] = ItemStatus.ERROR
            it[errorMessage] = message
            it[updatedAt] = Instant.now()
        }
    }

    private fun uniqueDirName(title: String): String {
        val base = Downloader.sanitizeFilename(title).ifBlank { "audio" }
        val used = transaction { Items.selectAll().map { it[Items.dirName] }.toSet() }
        if (base !in used && !File(outputDir, base).exists()) return base
        var n = 2
        while (true) {
            val candidate = "$base ($n)"
            if (candidate !in used && !File(outputDir, candidate).exists()) return candidate
            n++
        }
    }

    private data class PartRec(
        val id: String, val number: Int, val title: String, val duration: Int,
        val audioUrl: String?, val audioVariant: String, val playableTill: String?,
    )
}

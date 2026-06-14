package cz.skybit.mrsd

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.timestamp
import java.io.File

/** SINGLE = one standalone audio, SERIES = a multi-part serial. */
enum class ItemType { SINGLE, SERIES }

/**
 * DOWNLOADING — work in progress.
 * WAITING     — a serial whose remaining parts haven't been published yet.
 * COMPLETED   — fully downloaded and the final M4B exists.
 * ERROR       — something failed; retry manually.
 */
enum class ItemStatus { DOWNLOADING, WAITING, COMPLETED, ERROR }

object Items : Table("items") {
    val id = varchar("id", 64)            // serial uuid (SERIES) or episode uuid (SINGLE)
    val type = enumerationByName<ItemType>("type", 16)
    val title = varchar("title", 512)
    val originUrl = varchar("origin_url", 2048)
    val imageUrl = varchar("image_url", 2048).nullable()
    val status = enumerationByName<ItemStatus>("status", 16).default(ItemStatus.DOWNLOADING)
    val totalParts = integer("total_parts").default(0)
    val dirName = varchar("dir_name", 512)
    val m4bPath = varchar("m4b_path", 1024).nullable()
    val errorMessage = varchar("error_message", 2048).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Parts : Table("parts") {
    val id = varchar("id", 64)            // episode uuid
    val itemId = varchar("item_id", 64).references(Items.id).index()
    val number = integer("number").default(0)
    val title = varchar("title", 512)
    val duration = integer("duration").default(0)
    val audioUrl = varchar("audio_url", 2048).nullable()
    val audioVariant = varchar("audio_variant", 16).default("hls")
    val playableTill = varchar("playable_till", 64).nullable()
    val downloaded = bool("downloaded").default(false)
    val filePath = varchar("file_path", 1024).nullable()
    val errorMessage = varchar("error_message", 2048).nullable()

    override val primaryKey = PrimaryKey(id)
}

fun initDatabase(dbPath: String) {
    File(dbPath).parentFile?.mkdirs()
    Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.createMissingTablesAndColumns(Items, Parts)
    }
}

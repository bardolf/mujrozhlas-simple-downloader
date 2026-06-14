package cz.skybit.mrsd

import java.io.File

/**
 * Single entry point: a small web server. Configuration via environment variables:
 *   PORT          (default 8080)
 *   DOWNLOAD_DIR  (default downloads)
 *   DELETED_DIR   (default deleted)
 *   DB_PATH       (default data/mrsd.db)
 *   AUTH_USER / AUTH_PASS  (optional basic auth; if unset, the app is open)
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val downloadDir = File(System.getenv("DOWNLOAD_DIR") ?: "downloads")
    val deletedDir = File(System.getenv("DELETED_DIR") ?: "deleted")
    val dbPath = System.getenv("DB_PATH") ?: "data/mrsd.db"

    println("mujrozhlas-simple-downloader")
    println("  port:      $port")
    println("  downloads: ${downloadDir.absolutePath}")
    println("  deleted:   ${deletedDir.absolutePath}")
    println("  database:  ${File(dbPath).absolutePath}")

    startServer(port, downloadDir, deletedDir, dbPath)
}

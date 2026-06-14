package cz.skybit.mrsd

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File

private val serverLog = LoggerFactory.getLogger("cz.skybit.mrsd.Server")

fun startServer(port: Int, outputDir: File, deletedDir: File, dbPath: String) {
    initDatabase(dbPath)

    val api = Api()
    val downloader = Downloader()
    val resolver = UrlResolver(api)
    val scope = CoroutineScope(SupervisorJob())
    val service = DownloadService(api, resolver, downloader, outputDir, deletedDir, scope)

    val authUser = System.getenv("AUTH_USER")
    val authPass = System.getenv("AUTH_PASS")

    downloader.checkFfmpeg()
    service.start()

    val server = embeddedServer(Netty, port = port) {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                serverLog.error("Unhandled error", cause)
                call.respondText("Chyba: ${cause.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        if (authUser != null && authPass != null) {
            install(createApplicationPlugin("BasicAuth") {
                onCall { call ->
                    val header = call.request.headers["Authorization"]
                    if (header != null && header.startsWith("Basic ")) {
                        val decoded = java.util.Base64.getDecoder()
                            .decode(header.removePrefix("Basic ")).toString(Charsets.UTF_8)
                        val parts = decoded.split(":", limit = 2)
                        if (parts.size == 2 && parts[0] == authUser && parts[1] == authPass) return@onCall
                    }
                    call.response.header("WWW-Authenticate", "Basic realm=\"mrsd\"")
                    call.respond(HttpStatusCode.Unauthorized)
                }
            })
        }

        routing {
            get("/") {
                val items = loadSummaries()
                call.respondHtml { layout("Stahování – mujrozhlas") { dashboard(items) } }
            }

            post("/add") {
                val url = call.receiveParameters()["url"]?.trim()
                if (url.isNullOrBlank()) {
                    return@post respondAddError(call, "Zadej prosím URL.")
                }
                try {
                    val result = withContext(Dispatchers.IO) { service.addFromUrl(url) }
                    val summary = loadSummary(result.itemId)
                    if (summary == null) {
                        respondAddError(call, "Položku se nepodařilo načíst.")
                    } else {
                        if (!result.isNew) {
                            call.response.header("HX-Retarget", "#add-error")
                            call.response.header("HX-Reswap", "innerHTML")
                            call.respondText(
                                createHTML().p { +"${result.title} – tohle už máš přidané." },
                                ContentType.Text.Html,
                            )
                        } else {
                            call.respondText(
                                createHTML().div { itemCard(summary) }.removeWrapperDiv(),
                                ContentType.Text.Html,
                            )
                        }
                    }
                } catch (e: ResolveException) {
                    respondAddError(call, e.message ?: "URL se nepodařilo zpracovat.")
                } catch (e: Exception) {
                    serverLog.error("Add failed for $url", e)
                    respondAddError(call, "Chyba: ${e.message}")
                }
            }

            get("/items/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val detail = loadDetail(id) ?: return@get call.respond(HttpStatusCode.NotFound, "Nenalezeno")
                call.respondHtml { layout("${detail.title} – mujrozhlas") { itemDetailPage(detail) } }
            }

            get("/items/{id}/card") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val summary = loadSummary(id) ?: return@get call.respondText("", ContentType.Text.Html)
                call.respondText(createHTML().div { itemCard(summary) }.removeWrapperDiv(), ContentType.Text.Html)
            }

            get("/items/{id}/status") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val detail = loadDetail(id) ?: return@get call.respondText("", ContentType.Text.Html)
                call.respondText(createHTML().div { itemStatusBlock(detail) }.removeWrapperDiv(), ContentType.Text.Html)
            }

            post("/items/{id}/refresh") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                withContext(Dispatchers.IO) { service.refreshAndEnqueue(id) }
                val detail = loadDetail(id) ?: return@post call.respondText("", ContentType.Text.Html)
                call.respondText(createHTML().div { itemStatusBlock(detail) }.removeWrapperDiv(), ContentType.Text.Html)
            }

            post("/items/{id}/delete") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                withContext(Dispatchers.IO) { service.deleteItem(id) }
                call.respondText("", ContentType.Text.Html)
            }

            get("/dl/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val path = transaction {
                    Items.selectAll().where { Items.id eq id }.firstOrNull()?.get(Items.m4bPath)
                }
                val file = path?.let { File(it) }
                if (file == null || !file.exists()) {
                    return@get call.respond(HttpStatusCode.NotFound, "Soubor není k dispozici")
                }
                call.response.header("Content-Disposition", "attachment; filename=\"${file.name}\"")
                call.respondFile(file)
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        serverLog.info("Shutting down…")
        runBlocking { service.shutdown() }
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

private suspend fun respondAddError(call: ApplicationCall, message: String) {
    call.response.header("HX-Retarget", "#add-error")
    call.response.header("HX-Reswap", "innerHTML")
    call.respondText(createHTML().p { +message }, ContentType.Text.Html)
}

/** createHTML().div { ... } wraps fragments in an extra <div>; strip it for outerHTML swaps. */
private fun String.removeWrapperDiv(): String =
    removePrefix("<div>").removeSuffix("</div>")

// --- DB → view models ---

private fun loadSummaries(): List<ItemSummary> = transaction {
    Items.selectAll().orderBy(Items.createdAt, SortOrder.DESC).map { it.toSummary() }
}

private fun loadSummary(id: String): ItemSummary? = transaction {
    Items.selectAll().where { Items.id eq id }.firstOrNull()?.toSummary()
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toSummary(): ItemSummary {
    val id = this[Items.id]
    val known = Parts.selectAll().where { Parts.itemId eq id }.count().toInt()
    val done = Parts.selectAll().where { (Parts.itemId eq id) and (Parts.downloaded eq true) }.count().toInt()
    val m4bPath = this[Items.m4bPath]
    return ItemSummary(
        id = id,
        type = this[Items.type],
        title = this[Items.title],
        status = this[Items.status],
        imageUrl = this[Items.imageUrl],
        downloadedParts = done,
        totalParts = this[Items.totalParts],
        knownParts = known,
        m4bDownloadUrl = if (m4bPath != null && File(m4bPath).exists()) "/dl/$id" else null,
    )
}

private fun loadDetail(id: String): ItemDetail? = transaction {
    val row = Items.selectAll().where { Items.id eq id }.firstOrNull() ?: return@transaction null
    val parts = Parts.selectAll().where { Parts.itemId eq id }.orderBy(Parts.number).map {
        PartView(
            number = it[Parts.number],
            title = it[Parts.title],
            duration = it[Parts.duration],
            downloaded = it[Parts.downloaded],
            errorMessage = it[Parts.errorMessage],
        )
    }
    val done = parts.count { it.downloaded }
    val m4bPath = row[Items.m4bPath]
    ItemDetail(
        id = id,
        type = row[Items.type],
        title = row[Items.title],
        originUrl = row[Items.originUrl],
        imageUrl = row[Items.imageUrl],
        status = row[Items.status],
        downloadedParts = done,
        totalParts = row[Items.totalParts],
        knownParts = parts.size,
        errorMessage = row[Items.errorMessage],
        m4bDownloadUrl = if (m4bPath != null && File(m4bPath).exists()) "/dl/$id" else null,
        parts = parts,
    )
}

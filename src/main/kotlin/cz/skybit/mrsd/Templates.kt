package cz.skybit.mrsd

import kotlinx.html.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// --- View models ---

data class ItemSummary(
    val id: String,
    val type: ItemType,
    val title: String,
    val status: ItemStatus,
    val imageUrl: String?,
    val downloadedParts: Int,
    val totalParts: Int,
    val knownParts: Int,
)

data class PartView(
    val number: Int,
    val title: String,
    val duration: Int,
    val downloaded: Boolean,
    val errorMessage: String?,
)

data class ItemDetail(
    val id: String,
    val type: ItemType,
    val title: String,
    val originUrl: String,
    val imageUrl: String?,
    val status: ItemStatus,
    val downloadedParts: Int,
    val totalParts: Int,
    val knownParts: Int,
    val errorMessage: String?,
    val m4bDownloadUrl: String?,
    val parts: List<PartView>,
)

// --- Formatting ---

private val dateFormatter = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm").withZone(ZoneId.systemDefault())
private fun formatInstant(instant: Instant?): String = if (instant != null) dateFormatter.format(instant) else ""

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "–"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun statusLabel(status: ItemStatus): String = when (status) {
    ItemStatus.DOWNLOADING -> "Stahuje se"
    ItemStatus.WAITING -> "Čeká na další díly"
    ItemStatus.COMPLETED -> "Hotovo"
    ItemStatus.ERROR -> "Chyba"
}

private fun statusClass(status: ItemStatus): String = when (status) {
    ItemStatus.DOWNLOADING -> "badge-downloading"
    ItemStatus.WAITING -> "badge-waiting"
    ItemStatus.COMPLETED -> "badge-completed"
    ItemStatus.ERROR -> "badge-error"
}

private fun progressText(item: ItemSummary): String? {
    if (item.type == ItemType.SINGLE) return null
    val total = if (item.totalParts > 0) item.totalParts else item.knownParts
    return "${item.downloadedParts}/$total dílů"
}

// --- Layout ---

fun HTML.layout(pageTitle: String, content: MAIN.() -> Unit) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; this.content = "width=device-width, initial-scale=1" }
        title { +pageTitle }
        link { rel = "stylesheet"; href = "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css" }
        script { src = "https://unpkg.com/htmx.org@2.0.4" }
        style {
            unsafe {
                +"""
                .badge { display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 0.8em; font-weight: bold; white-space: nowrap; }
                .badge-downloading { background: #0d6efd; color: #fff; }
                .badge-waiting { background: #ffc107; color: #000; }
                .badge-completed { background: #198754; color: #fff; }
                .badge-error { background: #dc3545; color: #fff; }
                .item-card { display: flex; gap: 1rem; align-items: center; margin-bottom: 0.75rem; padding: 0.75rem 1rem; }
                .item-card img { width: 64px; height: 64px; object-fit: cover; border-radius: 6px; flex: 0 0 auto; }
                .item-card .grow { flex: 1 1 auto; min-width: 0; }
                .item-card .title { font-weight: 600; }
                .muted { color: var(--pico-muted-color); }
                .cover-lg { max-width: 220px; border-radius: 8px; }
                .row { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
                .btn-sm { padding: 4px 12px; font-size: 0.85em; margin: 0; }
                """.trimIndent()
            }
        }
    }
    body {
        nav {
            attributes["class"] = "container"
            ul { li { a(href = "/") { strong { +"mujrozhlas – stahovač" } } } }
        }
        main {
            attributes["class"] = "container"
            content()
        }
    }
}

// --- Dashboard ---

fun MAIN.dashboard(items: List<ItemSummary>) {
    h1 { +"Stahování" }

    form {
        attributes["hx-post"] = "/add"
        attributes["hx-target"] = "#item-list"
        attributes["hx-swap"] = "afterbegin"
        attributes["hx-on::after-request"] = "if(event.detail.successful) this.reset()"
        role = "group"
        input {
            type = InputType.url
            name = "url"
            placeholder = "Vlož URL z mujrozhlas.cz…"
            required = true
        }
        button { type = ButtonType.submit; +"Stáhnout" }
    }

    div { id = "add-error" }

    div {
        id = "item-list"
        if (items.isEmpty()) {
            p { attributes["class"] = "muted"; +"Zatím nic. Vlož URL výše." }
        } else {
            for (item in items) itemCard(item)
        }
    }
}

fun FlowContent.itemCard(item: ItemSummary) {
    article {
        id = "item-${item.id}"
        attributes["class"] = "item-card"
        // Live-update the card while it is actively downloading.
        if (item.status == ItemStatus.DOWNLOADING) {
            attributes["hx-get"] = "/items/${item.id}/card"
            attributes["hx-trigger"] = "every 4s"
            attributes["hx-swap"] = "outerHTML"
        }
        if (item.imageUrl != null) {
            img { src = item.imageUrl; alt = "" }
        }
        div {
            attributes["class"] = "grow"
            div {
                attributes["class"] = "title"
                a(href = "/items/${item.id}") { +item.title }
            }
            div {
                attributes["class"] = "muted"
                +if (item.type == ItemType.SERIES) "Seriál" else "Jednotlivé audio"
                progressText(item)?.let { +" · $it" }
            }
        }
        div {
            attributes["class"] = "row"
            span("badge ${statusClass(item.status)}") { +statusLabel(item.status) }
            button {
                attributes["class"] = "outline secondary btn-sm"
                attributes["hx-post"] = "/items/${item.id}/delete"
                attributes["hx-target"] = "#item-${item.id}"
                attributes["hx-swap"] = "outerHTML"
                attributes["hx-confirm"] = "Smazat '${item.title}'? Soubory se přesunou do složky deleted."
                +"Smazat"
            }
        }
    }
}

// --- Item detail ---

fun MAIN.itemDetailPage(item: ItemDetail) {
    p { a(href = "/") { +"← Zpět" } }
    h1 { +item.title }

    div {
        attributes["class"] = "row"
        style = "align-items: flex-start; gap: 1.5rem;"
        if (item.imageUrl != null) {
            img { attributes["class"] = "cover-lg"; src = item.imageUrl; alt = "" }
        }
        div {
            attributes["class"] = "grow"
            itemStatusBlock(item)

            p {
                +(if (item.type == ItemType.SERIES) "Seriál" else "Jednotlivé audio")
                br
                a(href = item.originUrl) { attributes["target"] = "_blank"; +"Otevřít na mujrozhlas.cz ↗" }
            }
        }
    }

    h2 { +"Díly" }
    table {
        thead { tr { th { +"#" }; th { +"Název" }; th { +"Délka" }; th { +"Stav" } } }
        tbody {
            for (p in item.parts) {
                tr {
                    td { +(if (p.number > 0) p.number.toString() else "") }
                    td {
                        +p.title
                        p.errorMessage?.let { br; small { style = "color: var(--pico-del-color);"; +it } }
                    }
                    td { +formatDuration(p.duration) }
                    td {
                        if (p.downloaded) span("badge badge-completed") { +"Staženo" }
                        else span("badge badge-waiting") { +"Čeká" }
                    }
                }
            }
        }
    }
}

/** Status + progress + action buttons; polled on its own while downloading. */
fun FlowContent.itemStatusBlock(item: ItemDetail) {
    div {
        id = "detail-status"
        if (item.status == ItemStatus.DOWNLOADING) {
            attributes["hx-get"] = "/items/${item.id}/status"
            attributes["hx-trigger"] = "every 4s"
            attributes["hx-swap"] = "outerHTML"
        }
        div {
            attributes["class"] = "row"
            span("badge ${statusClass(item.status)}") { +statusLabel(item.status) }
            val total = if (item.totalParts > 0) item.totalParts else item.knownParts
            if (item.type == ItemType.SERIES) {
                span("muted") { +"${item.downloadedParts}/$total dílů staženo" }
            }
        }
        item.errorMessage?.let { p { small { style = "color: var(--pico-del-color);"; +it } } }

        div {
            attributes["class"] = "row"
            style = "margin-top: 0.75rem;"
            if (item.m4bDownloadUrl != null) {
                a(href = item.m4bDownloadUrl) {
                    attributes["class"] = "btn-sm"
                    attributes["role"] = "button"
                    attributes["download"] = ""
                    +"Stáhnout M4B"
                }
            }
            if (item.status == ItemStatus.WAITING) {
                button {
                    attributes["class"] = "outline btn-sm"
                    attributes["hx-post"] = "/items/${item.id}/refresh"
                    attributes["hx-target"] = "#detail-status"
                    attributes["hx-swap"] = "outerHTML"
                    +"Zkusit dotáhnout teď"
                }
            }
            if (item.status == ItemStatus.ERROR) {
                button {
                    attributes["class"] = "outline btn-sm"
                    attributes["hx-post"] = "/items/${item.id}/refresh"
                    attributes["hx-target"] = "#detail-status"
                    attributes["hx-swap"] = "outerHTML"
                    +"Zkusit znovu"
                }
            }
        }
    }
}

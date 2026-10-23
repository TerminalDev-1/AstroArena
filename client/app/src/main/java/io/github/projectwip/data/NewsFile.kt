package io.github.projectwip.data

/** One item on the News tab. */
data class NewsItem(val title: String, val date: String, val tag: String, val text: String)

/**
 * Reads `news.cfg`, the News tab's file, the way the server does (`astro/config.py`): each `[section]` is an item and
 * its name the headline, with `date`, `tag` and `text` under it; a line that starts with whitespace carries the one
 * before it on, and `#` or `;` starts a comment line. Items stay in the order written, newest first.
 *
 * The game carries a copy of the file from when it was built (an asset), so the News can be read offline.
 */
object NewsFile {
    fun parse(text: String): List<NewsItem> {
        val sections = ArrayList<Pair<String, LinkedHashMap<String, String>>>()
        var key: String? = null
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { key = null; continue }
            if (trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sections += trimmed.substring(1, trimmed.length - 1) to LinkedHashMap()
                key = null
                continue
            }
            val entries = sections.lastOrNull()?.second ?: continue
            if (line.first().isWhitespace() && key != null) {
                entries[key] = entries[key] + " " + trimmed
                continue
            }
            val eq = line.indexOfFirst { it == '=' || it == ':' }
            if (eq <= 0) continue
            key = line.substring(0, eq).trim().lowercase()
            entries[key] = line.substring(eq + 1).trim()
        }
        return sections.take(30).map { (title, e) ->
            NewsItem(
                title = title.take(80),
                date = e["date"].orEmpty().take(20),
                tag = (e["tag"]?.trim()?.uppercase()?.ifEmpty { null } ?: "NEWS").take(12),
                text = e["text"].orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(1200),
            )
        }
    }

    /** Of two copies of the News, the newer: the one whose newest item is dated later (dates are written 2026-10-23). */
    fun newer(a: List<NewsItem>, b: List<NewsItem>): List<NewsItem> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return if (b.first().date > a.first().date || (b.first().date == a.first().date && b.size > a.size)) b else a
    }
}

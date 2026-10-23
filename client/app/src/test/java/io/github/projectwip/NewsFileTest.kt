package io.github.projectwip

import io.github.projectwip.data.NewsFile
import io.github.projectwip.data.NewsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NewsFileTest {
    @Test fun readsTheNewsFileTheWayTheServerDoes() {
        val items = NewsFile.parse(
            """
            # A comment.
            [Big news]
            date = 2026-10-23
            tag = new
            text = One line,
                and the next carries it on.

            [Older news]
            date = 2026-10-01
            text = Plain.
            """.trimIndent()
        )
        assertEquals(listOf(
            NewsItem("Big news", "2026-10-23", "NEW", "One line, and the next carries it on."),
            NewsItem("Older news", "2026-10-01", "NEWS", "Plain."),
        ), items)
    }

    @Test fun theRealNewsFileReadsNewestFirst() {
        val file = listOf(File("../../server/news.cfg"), File("../server/news.cfg")).first { it.exists() }
        val items = NewsFile.parse(file.readText())
        assertTrue(items.isNotEmpty())
        assertTrue("every item has a date and some text", items.all { it.date.isNotEmpty() && it.text.isNotEmpty() })
        assertEquals("newest first", items.map { it.date }.sortedDescending(), items.map { it.date })
    }

    @Test fun theNewerCopyWins() {
        val old = listOf(NewsItem("a", "2026-10-01", "NEWS", "x"))
        val new = listOf(NewsItem("b", "2026-10-23", "NEWS", "y"), NewsItem("a", "2026-10-01", "NEWS", "x"))
        assertEquals(new, NewsFile.newer(old, new))
        assertEquals(new, NewsFile.newer(new, old))
        assertEquals(old, NewsFile.newer(old, emptyList()))
    }
}

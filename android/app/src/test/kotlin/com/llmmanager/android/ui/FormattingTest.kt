package com.llmmanager.android.ui

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattingTest {
    @Test
    fun `axis maximum rounds up to a clean number`() {
        assertEquals(1, niceMax(0))
        assertEquals(3, niceMax(3))
        assertEquals(5, niceMax(5))
        assertEquals(10, niceMax(7))
        assertEquals(20, niceMax(11))
        assertEquals(50, niceMax(21))
        assertEquals(100, niceMax(100))
        assertEquals(200, niceMax(101))
    }

    @Test
    fun `taps map to the day band under the finger`() {
        // 30 days across 300px of plot, starting 26px in.
        assertNull(dayAt(10f, 326f, 26f, 30))
        assertEquals(0, dayAt(26f, 326f, 26f, 30))
        assertEquals(0, dayAt(35.9f, 326f, 26f, 30))
        assertEquals(1, dayAt(36f, 326f, 26f, 30))
        assertEquals(29, dayAt(325.9f, 326f, 26f, 30))
        assertEquals(29, dayAt(400f, 326f, 26f, 30))
        assertNull(dayAt(50f, 326f, 26f, 0))
    }

    @Test
    fun `relative times`() {
        val now = OffsetDateTime.parse("2026-11-09T12:00:00Z")
        assertEquals("no commits", relativeTime(null, now))
        assertEquals("just now", relativeTime("2026-11-09T11:59:30Z", now))
        assertEquals("5m ago", relativeTime("2026-11-09T11:55:00Z", now))
        assertEquals("3h ago", relativeTime("2026-11-09T10:00:00+01:00", now))
        assertEquals("2d ago", relativeTime("2026-11-07T12:00:00Z", now))
        assertEquals("garbage", relativeTime("garbage", now))
        assertEquals("1 commit", plural(1, "commit", "commits"))
        assertEquals("0 commits", plural(0, "commit", "commits"))
    }
}

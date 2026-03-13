package com.idopen.idopen.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadWindowSupportTest {
    @Test
    fun `normalize request converts line ranges into offset and limit`() {
        val request = ReadWindowSupport.normalizeRequest(startLine = 20, endLine = 35)

        assertEquals(20, request.offset)
        assertEquals(16, request.limit)
    }

    @Test
    fun `slice lines reports continuation when file is longer than window`() {
        val lines = (1..6).map { "line-$it" }
        val slice = ReadWindowSupport.sliceLines(
            lines = lines,
            request = ReadWindowSupport.WindowRequest(offset = 3, limit = 2),
        )

        assertEquals(3, slice.offset)
        assertEquals(listOf("line-3", "line-4"), slice.lines)
        assertEquals(6, slice.totalLines)
        assertTrue(slice.hasMore)
    }

    @Test
    fun `format selection includes range metadata`() {
        val output = ReadWindowSupport.formatSelection(
            path = "src/App.kt",
            startLine = 8,
            endLine = 9,
            content = "fun a()\nfun b()",
        )

        assertTrue(output.contains("<type>selection</type>"))
        assertTrue(output.contains("<range>8-9</range>"))
        assertTrue(output.contains("8: fun a()"))
        assertTrue(output.contains("9: fun b()"))
    }
}

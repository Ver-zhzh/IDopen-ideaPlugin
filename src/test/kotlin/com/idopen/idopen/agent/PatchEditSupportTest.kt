package com.idopen.idopen.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PatchEditSupportTest {
    @Test
    fun `search replace edits only patch a unique match`() {
        val result = PatchEditSupport.apply(
            beforeText = "alpha\nbeta\ngamma\n",
            edits = listOf(
                PatchEdit(search = "beta", replace = "BETA"),
            ),
        )

        assertEquals("alpha\nBETA\ngamma\n", result)
    }

    @Test
    fun `line edits replace an inclusive range`() {
        val result = PatchEditSupport.apply(
            beforeText = "one\ntwo\nthree\nfour\n",
            edits = listOf(
                PatchEdit(startLine = 2, endLine = 3, newText = "TWO\nTHREE"),
            ),
        )

        assertEquals("one\nTWO\nTHREE\nfour\n", result)
    }

    @Test
    fun `ambiguous search raises an error`() {
        assertFailsWith<IllegalArgumentException> {
            PatchEditSupport.apply(
                beforeText = "same\nsame\n",
                edits = listOf(PatchEdit(search = "same", replace = "other")),
            )
        }
    }

    @Test
    fun `occurrence disambiguates repeated matches`() {
        val result = PatchEditSupport.apply(
            beforeText = "same\nsame\nsame\n",
            edits = listOf(
                PatchEdit(search = "same", replace = "SECOND", occurrence = 2),
            ),
        )

        assertEquals("same\nSECOND\nsame\n", result)
    }

    @Test
    fun `anchor insert can place new content before a unique marker`() {
        val result = PatchEditSupport.apply(
            beforeText = "alpha\nbeta\ngamma\n",
            edits = listOf(
                PatchEdit(before = "beta", newText = "INSERT\n"),
            ),
        )

        assertEquals("alpha\nINSERT\nbeta\ngamma\n", result)
    }

    @Test
    fun `anchor insert can place new content after a unique marker`() {
        val result = PatchEditSupport.apply(
            beforeText = "alpha\nbeta\ngamma\n",
            edits = listOf(
                PatchEdit(after = "beta", newText = "\nINSERT"),
            ),
        )

        assertEquals("alpha\nbeta\nINSERT\ngamma\n", result)
    }

    @Test
    fun `search replace tolerates newline differences against crlf files`() {
        val result = PatchEditSupport.apply(
            beforeText = "alpha\r\nbeta\r\ngamma\r\n",
            edits = listOf(
                PatchEdit(search = "beta\ngamma", replace = "BETA\nGAMMA"),
            ),
        )

        assertEquals("alpha\r\nBETA\r\nGAMMA\r\n", result)
    }
}

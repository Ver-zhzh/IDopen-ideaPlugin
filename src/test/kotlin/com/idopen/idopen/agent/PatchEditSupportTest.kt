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
}

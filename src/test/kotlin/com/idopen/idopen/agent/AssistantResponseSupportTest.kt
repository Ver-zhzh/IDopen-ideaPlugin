package com.idopen.idopen.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AssistantResponseSupportTest {
    @Test
    fun `partition splits markdown into text list and code parts`() {
        val parts = AssistantResponseSupport.partition(
            """
            Intro paragraph

            - one
            - two

            ```kotlin
            println("hello")
            ```
            """.trimIndent(),
        )

        assertEquals(3, parts.size)
        assertIs<AssistantOutputPart.Text>(parts[0])
        val list = assertIs<AssistantOutputPart.ListBlock>(parts[1])
        assertEquals(listOf("one", "two"), list.items)
        val code = assertIs<AssistantOutputPart.CodeBlock>(parts[2])
        assertEquals("kotlin", code.language)
        assertEquals("""println("hello")""", code.code)
    }
}

package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTodoSupportTest {
    private val mapper = ObjectMapper()

    @Test
    fun `parse todos normalizes status and trims blank items`() {
        val node = mapper.readTree(
            """
            [
              {"content":" inspect project ", "status":"in_progress"},
              {"content":"", "status":"pending"},
              {"content":"write summary", "status":"done"}
            ]
            """.trimIndent(),
        )

        val todos = SessionTodoSupport.parseTodos(node)

        assertEquals(2, todos.size)
        assertEquals(SessionTodoStatus.IN_PROGRESS, todos.first().status)
        assertEquals(SessionTodoStatus.COMPLETED, todos.last().status)
        assertEquals("inspect project", todos.first().content)
    }

    @Test
    fun `validate todos rejects multiple in progress items`() {
        val error = SessionTodoSupport.validateTodos(
            listOf(
                SessionTodoItem("a", SessionTodoStatus.IN_PROGRESS),
                SessionTodoItem("b", SessionTodoStatus.IN_PROGRESS),
            ),
        )

        assertTrue(error?.contains("in_progress") == true)
    }

    @Test
    fun `format and summary handle empty and populated lists`() {
        assertEquals("No todo items recorded.", SessionTodoSupport.formatForTool(emptyList()))
        assertNull(SessionTodoSupport.validateTodos(listOf(SessionTodoItem("a"))))
        assertTrue(
            SessionTodoSupport.summary(
                listOf(
                    SessionTodoItem("a", SessionTodoStatus.PENDING),
                    SessionTodoItem("b", SessionTodoStatus.COMPLETED),
                ),
            ).contains("2 items"),
        )
    }
}

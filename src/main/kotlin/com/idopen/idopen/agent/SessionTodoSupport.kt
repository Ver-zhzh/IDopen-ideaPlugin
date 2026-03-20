package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode

object SessionTodoSupport {
    private const val MAX_TODO_ITEMS = 24

    fun parseTodos(node: JsonNode): List<SessionTodoItem> {
        if (!node.isArray) return emptyList()
        return node.mapNotNull { item ->
            val content = item.path("content").asText("").trim()
            if (content.isBlank()) return@mapNotNull null
            SessionTodoItem(
                content = content,
                status = SessionTodoStatus.fromStored(item.path("status").asText()) ?: SessionTodoStatus.PENDING,
            )
        }
    }

    fun validateTodos(items: List<SessionTodoItem>): String? {
        if (items.size > MAX_TODO_ITEMS) {
            return "Too many todo items. Keep at most $MAX_TODO_ITEMS items in the list."
        }
        val inProgressCount = items.count { it.status == SessionTodoStatus.IN_PROGRESS }
        if (inProgressCount > 1) {
            return "Keep at most one todo item as in_progress."
        }
        return null
    }

    fun formatForTool(items: List<SessionTodoItem>): String {
        if (items.isEmpty()) return "No todo items recorded."
        return buildString {
            appendLine("Current todo list:")
            items.forEachIndexed { index, item ->
                append(index + 1)
                append(". [")
                append(item.status.wireValue())
                append("] ")
                appendLine(item.content)
            }
        }.trimEnd()
    }

    fun summary(items: List<SessionTodoItem>): String {
        if (items.isEmpty()) return "Todo list cleared."
        val pending = items.count { it.status == SessionTodoStatus.PENDING }
        val active = items.count { it.status == SessionTodoStatus.IN_PROGRESS }
        val completed = items.count { it.status == SessionTodoStatus.COMPLETED }
        return "Todo list updated: ${items.size} items (${active} in progress, ${pending} pending, ${completed} completed)."
    }
}

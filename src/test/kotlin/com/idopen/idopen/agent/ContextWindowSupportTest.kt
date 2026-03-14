package com.idopen.idopen.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextWindowSupportTest {
    @Test
    fun `compact keeps system prompt and recent history`() {
        val messages = buildList {
            add(ConversationMessage.System("base system"))
            repeat(24) { index ->
                add(ConversationMessage.User("user message $index " + "x".repeat(220)))
                add(ConversationMessage.Assistant("assistant reply $index " + "y".repeat(220)))
            }
        }

        val compacted = ContextWindowSupport.compact(messages)

        assertIs<ConversationMessage.System>(compacted.first())
        assertTrue(compacted.size < messages.size)
        val summary = assertIs<ConversationMessage.System>(compacted[1])
        assertTrue(summary.content.contains("Conversation summary of earlier context"))
        val lastUser = compacted.filterIsInstance<ConversationMessage.User>().last()
        assertEquals("user message 23 " + "x".repeat(220), lastUser.content)
    }

    @Test
    fun `compact leaves short history unchanged`() {
        val messages = listOf(
            ConversationMessage.System("base"),
            ConversationMessage.User("你好"),
            ConversationMessage.Assistant("收到"),
        )

        val compacted = ContextWindowSupport.compact(messages)

        assertEquals(messages, compacted)
    }

    @Test
    fun `summary includes tool activity`() {
        val messages = buildList {
            add(ConversationMessage.System("base"))
            repeat(20) { index ->
                add(ConversationMessage.User("用户问题 $index " + "a".repeat(260)))
                add(
                    ConversationMessage.Assistant(
                        content = "助手回复 $index",
                        toolCalls = listOf(ToolCall("call-$index", "read_file", """{"path":"README.md"}""")),
                    ),
                )
                add(ConversationMessage.Tool("call-$index", "read_file", "tool output $index " + "b".repeat(260)))
            }
        }

        val compacted = ContextWindowSupport.compact(messages)
        val summary = assertIs<ConversationMessage.System>(compacted[1])

        assertTrue(summary.content.contains("Tool read_file"))
        assertTrue(summary.content.contains("tool calls: read_file"))
    }
}

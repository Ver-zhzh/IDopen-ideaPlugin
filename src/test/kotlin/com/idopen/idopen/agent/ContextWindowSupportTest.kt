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
                add(ConversationMessage.User("user message $index " + "x".repeat(220), "round-$index"))
                add(ConversationMessage.Assistant("assistant reply $index " + "y".repeat(220), roundId = "round-$index"))
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
            ConversationMessage.User("你好", "round-1"),
            ConversationMessage.Assistant("收到", roundId = "round-1"),
        )

        val compacted = ContextWindowSupport.compact(messages)

        assertEquals(messages, compacted)
    }

    @Test
    fun `summary includes tool activity`() {
        val messages = buildList {
            add(ConversationMessage.System("base"))
            repeat(20) { index ->
                add(ConversationMessage.User("用户问题 $index " + "a".repeat(260), "round-$index"))
                add(
                    ConversationMessage.Assistant(
                        content = "助手回复 $index",
                        toolCalls = listOf(ToolCall("call-$index", "read_file", """{"path":"README.md"}""")),
                        roundId = "round-$index",
                    ),
                )
                add(ConversationMessage.Tool("call-$index", "read_file", "tool output $index " + "b".repeat(260), "round-$index"))
            }
        }

        val compacted = ContextWindowSupport.compact(messages)
        val summary = assertIs<ConversationMessage.System>(compacted[1])

        assertTrue(summary.content.contains("Tool read_file"))
        assertTrue(summary.content.contains("tool calls: read_file"))
    }

    @Test
    fun `step group summary replaces older raw messages when rounds are available`() {
        val messages = buildList {
            add(ConversationMessage.System("base"))
            repeat(10) { index ->
                add(ConversationMessage.User("request $index", "round-$index"))
                add(
                    ConversationMessage.Assistant(
                        content = "reply $index",
                        toolCalls = listOf(ToolCall("call-$index", "read_file", """{"path":"file$index.txt"}""")),
                        roundId = "round-$index",
                    ),
                )
                add(ConversationMessage.Tool("call-$index", "read_file", "output $index", "round-$index"))
            }
        }
        val stepGroups = (0 until 10).map { index ->
            SessionStepGroup(
                roundId = "round-$index",
                stepIndex = index + 1,
                entries = listOf(
                    TranscriptEntry.User("user-$index", "request $index", roundId = "round-$index"),
                    TranscriptEntry.StepStart("start-$index", index + 1, roundId = "round-$index"),
                    TranscriptEntry.Assistant("assistant-$index", "reply $index", roundId = "round-$index"),
                    TranscriptEntry.ToolInvocation(
                        id = "tool-$index",
                        callId = "call-$index",
                        toolName = "read_file",
                        argumentsJson = """{"path":"file$index.txt"}""",
                        state = ToolInvocationState.COMPLETED,
                        output = "output $index",
                        success = true,
                        recoveryHint = if (index == 0) "Re-read the file window before retrying the patch." else null,
                        roundId = "round-$index",
                    ),
                    TranscriptEntry.StepFinish("finish-$index", index + 1, "tool-loop", 1, true, roundId = "round-$index"),
                ),
            )
        }

        val compacted = ContextWindowSupport.compact(messages, SessionStepSupport.buildSteps(stepGroups))
        val summary = assertIs<ConversationMessage.System>(compacted[1])

        assertTrue(summary.content.contains("Step 1"))
        assertTrue(summary.content.contains("tools: read_file"))
        assertTrue(summary.content.contains("recovery:"))
        assertEquals("request 9", compacted.filterIsInstance<ConversationMessage.User>().last().content)
    }
}

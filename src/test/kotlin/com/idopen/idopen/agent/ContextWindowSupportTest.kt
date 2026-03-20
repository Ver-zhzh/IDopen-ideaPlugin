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

    @Test
    fun `prepare request messages merges all system messages at the beginning`() {
        val prepared = ContextWindowSupport.prepareRequestMessages(
            messages = listOf(
                ConversationMessage.System("base prompt"),
                ConversationMessage.User("first question", "round-1"),
                ConversationMessage.Assistant("first answer", roundId = "round-1"),
                ConversationMessage.System("Recovery hint: narrow the scope", "round-1"),
                ConversationMessage.User("second question", "round-2"),
            ),
            prefixedSystemMessages = listOf(
                ConversationMessage.System("Execution plan:\n1. inspect", "round-2"),
                ConversationMessage.System("Planning notes:\n- read exact files", "round-2"),
            ),
        )

        assertEquals(4, prepared.size)
        val mergedSystem = assertIs<ConversationMessage.System>(prepared.first())
        assertTrue(mergedSystem.content.contains("base prompt"))
        assertTrue(mergedSystem.content.contains("Execution plan:"))
        assertTrue(mergedSystem.content.contains("Recovery hint: narrow the scope"))
        assertTrue(prepared.drop(1).none { it is ConversationMessage.System })
        assertEquals(
            listOf("first question", "first answer", "second question"),
            prepared.drop(1).map {
                when (it) {
                    is ConversationMessage.User -> it.content
                    is ConversationMessage.Assistant -> it.content
                    is ConversationMessage.Tool -> it.content
                    is ConversationMessage.System -> error("unexpected system message")
                }
            },
        )
    }

    @Test
    fun `compact keeps the full latest round for tool continuation`() {
        val messages = buildList {
            add(ConversationMessage.System("base"))
            repeat(18) { index ->
                add(ConversationMessage.User("older user $index " + "x".repeat(220), "round-$index"))
                add(ConversationMessage.Assistant("older assistant $index " + "y".repeat(220), roundId = "round-$index"))
            }
            repeat(6) { index ->
                add(ConversationMessage.User("active user $index", "round-active"))
                add(
                    ConversationMessage.Assistant(
                        content = "active assistant $index",
                        toolCalls = listOf(ToolCall("call-$index", "search_text", """{"query":"$index"}""")),
                        roundId = "round-active",
                    ),
                )
                add(ConversationMessage.Tool("call-$index", "search_text", "active output $index", "round-active"))
            }
        }

        val compacted = ContextWindowSupport.compact(messages)
        val activeRoundMessages = compacted.filter { it.roundId == "round-active" }

        assertEquals(18, activeRoundMessages.size)
        assertTrue(activeRoundMessages.none { it is ConversationMessage.System })
        assertEquals("active user 0", (activeRoundMessages.first() as ConversationMessage.User).content)
        assertEquals("active output 5", (activeRoundMessages.last() as ConversationMessage.Tool).content)
    }

    @Test
    fun `compact expands partially selected rounds to keep tool call pairs intact`() {
        val messages = buildList {
            add(ConversationMessage.System("base"))
            repeat(12) { index ->
                add(ConversationMessage.User("user $index " + "x".repeat(180), "round-$index"))
                add(
                    ConversationMessage.Assistant(
                        content = "assistant $index",
                        toolCalls = listOf(ToolCall("call-$index", "read_file", """{"path":"file$index.txt"}""")),
                        roundId = "round-$index",
                    ),
                )
                add(ConversationMessage.Tool("call-$index", "read_file", "output $index " + "y".repeat(180), "round-$index"))
            }
        }

        val compacted = ContextWindowSupport.compact(messages)
        val rounds = compacted.mapNotNull { it.roundId }.toSet()

        rounds.forEach { roundId ->
            val toolMessages = compacted.filterIsInstance<ConversationMessage.Tool>().filter { it.roundId == roundId }
            toolMessages.forEach { tool ->
                val assistant = compacted
                    .filterIsInstance<ConversationMessage.Assistant>()
                    .firstOrNull { it.roundId == roundId && it.toolCalls.any { call -> call.id == tool.toolCallId } }
                assertTrue(assistant != null, "Missing tool call context for $roundId/${tool.toolCallId}")
            }
        }
    }
}

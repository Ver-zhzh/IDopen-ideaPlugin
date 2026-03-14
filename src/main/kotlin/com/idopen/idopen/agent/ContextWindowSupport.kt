package com.idopen.idopen.agent

object ContextWindowSupport {
    private const val MAX_CONTEXT_CHARS = 24_000
    private const val MIN_RECENT_MESSAGES = 8
    private const val MAX_RECENT_MESSAGES = 18
    private const val SUMMARY_LINE_LIMIT = 18
    private const val SUMMARY_GROUP_LIMIT = 8
    private const val SUMMARY_VALUE_LIMIT = 240

    fun compact(
        messages: List<ConversationMessage>,
        steps: List<SessionStep> = emptyList(),
    ): List<ConversationMessage> {
        if (messages.isEmpty()) return messages

        val leadingSystem = messages.firstOrNull() as? ConversationMessage.System
        val body = if (leadingSystem != null) messages.drop(1) else messages
        if (body.isEmpty()) return messages

        val recent = mutableListOf<ConversationMessage>()
        var estimatedChars = 0
        for (message in body.asReversed()) {
            val estimate = estimateSize(message)
            val wouldOverflow = estimatedChars + estimate > MAX_CONTEXT_CHARS
            if (recent.size >= MIN_RECENT_MESSAGES && (wouldOverflow || recent.size >= MAX_RECENT_MESSAGES)) {
                break
            }
            recent += message
            estimatedChars += estimate
        }

        val recentMessages = recent.asReversed()
        if (recentMessages.size == body.size) {
            return messages
        }

        val olderMessages = body.dropLast(recentMessages.size)
        val summary = summarize(olderMessages, steps, recentMessages)
        return buildList {
            if (leadingSystem != null) add(leadingSystem)
            if (summary.isNotBlank()) add(ConversationMessage.System(summary))
            addAll(recentMessages)
        }
    }

    private fun summarize(
        messages: List<ConversationMessage>,
        steps: List<SessionStep>,
        recentMessages: List<ConversationMessage>,
    ): String {
        if (messages.isEmpty()) return ""
        val recentRoundIds = recentMessages.mapNotNull { it.roundId }.toSet()
        val groupedLines = summarizeSteps(steps, recentRoundIds)
        val fallbackLines = messages
            .filter { it.roundId == null || it.roundId !in recentRoundIds }
            .takeLast(SUMMARY_LINE_LIMIT)
            .mapNotNull(::summarizeMessage)
            .filter { it.isNotBlank() }
        val lines = (groupedLines + fallbackLines).takeLast(SUMMARY_LINE_LIMIT)
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine("Conversation summary of earlier context:")
            lines.forEach { line ->
                append("- ")
                appendLine(line)
            }
        }.trim()
    }

    private fun summarizeSteps(
        steps: List<SessionStep>,
        recentRoundIds: Set<String>,
    ): List<String> {
        if (steps.isEmpty()) return emptyList()
        return steps
            .filter { it.roundId !in recentRoundIds }
            .takeLast(SUMMARY_GROUP_LIMIT)
            .mapNotNull { it.summary }
    }

    private fun summarizeMessage(message: ConversationMessage): String? {
        return when (message) {
            is ConversationMessage.System -> "System: ${truncate(flatten(message.content))}"
            is ConversationMessage.User -> "User: ${truncate(flatten(message.content))}"
            is ConversationMessage.Assistant -> {
                val text = truncate(flatten(message.content))
                val toolSuffix = if (message.toolCalls.isEmpty()) {
                    ""
                } else {
                    " | tool calls: " + message.toolCalls.joinToString(", ") { it.name }
                }
                "Assistant: $text$toolSuffix"
            }
            is ConversationMessage.Tool -> "Tool ${message.toolName}: ${truncate(flatten(message.content))}"
        }
    }

    private fun estimateSize(message: ConversationMessage): Int {
        return when (message) {
            is ConversationMessage.System -> message.content.length
            is ConversationMessage.User -> message.content.length
            is ConversationMessage.Assistant -> {
                message.content.length + message.toolCalls.sumOf { it.name.length + it.argumentsJson.length }
            }
            is ConversationMessage.Tool -> message.toolName.length + message.content.length
        }
    }

    private fun flatten(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun truncate(value: String): String {
        return if (value.length <= SUMMARY_VALUE_LIMIT) value else value.take(SUMMARY_VALUE_LIMIT - 3) + "..."
    }
}

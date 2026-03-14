package com.idopen.idopen.agent

enum class SessionStepStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

data class SessionStepGroup(
    val roundId: String,
    val stepIndex: Int?,
    val entries: List<TranscriptEntry>,
) {
    val userEntries: List<TranscriptEntry.User> = entries.filterIsInstance<TranscriptEntry.User>()
    val contextEntries: List<TranscriptEntry.Context> = entries.filterIsInstance<TranscriptEntry.Context>()
    val assistantEntries: List<TranscriptEntry.Assistant> = entries.filterIsInstance<TranscriptEntry.Assistant>()
    val toolEntries: List<TranscriptEntry.ToolInvocation> = entries.filterIsInstance<TranscriptEntry.ToolInvocation>()
    val approvalEntries: List<TranscriptEntry.Approval> = entries.filterIsInstance<TranscriptEntry.Approval>()
    val errorEntries: List<TranscriptEntry.Error> = entries.filterIsInstance<TranscriptEntry.Error>()
    val started: TranscriptEntry.StepStart? = entries.filterIsInstance<TranscriptEntry.StepStart>().firstOrNull()
    val finished: TranscriptEntry.StepFinish? = entries.filterIsInstance<TranscriptEntry.StepFinish>().lastOrNull()
    val toolNames: List<String> = toolEntries.map { it.toolName }.distinct()
    val approvalKinds: List<ApprovalRequest.Type> = approvalEntries.map { it.request.type }.distinct()
    val status: SessionStepStatus = when {
        errorEntries.isNotEmpty() -> SessionStepStatus.FAILED
        finished?.success == false -> SessionStepStatus.FAILED
        finished != null -> SessionStepStatus.COMPLETED
        else -> SessionStepStatus.RUNNING
    }
    val summary: String? get() = SessionStepSupport.summarize(this)
}

object SessionStepSupport {
    private const val SUMMARY_VALUE_LIMIT = 240

    fun group(transcript: List<TranscriptEntry>): List<SessionStepGroup> {
        return transcript.fold(emptyList(), ::append)
    }

    fun append(groups: List<SessionStepGroup>, entry: TranscriptEntry): List<SessionStepGroup> {
        val roundId = entry.roundId ?: return groups
        val updated = groups.toMutableList()

        if (entry is TranscriptEntry.StepStart) {
            val pendingIndex = updated.indexOfLast { it.roundId == roundId && it.stepIndex == null && it.finished == null }
            if (pendingIndex >= 0) {
                val group = updated[pendingIndex]
                updated[pendingIndex] = group.copy(
                    stepIndex = entry.stepIndex,
                    entries = group.entries + entry,
                )
            } else {
                updated += SessionStepGroup(
                    roundId = roundId,
                    stepIndex = entry.stepIndex,
                    entries = listOf(entry),
                )
            }
            return updated
        }

        val openIndex = updated.indexOfLast { it.roundId == roundId && it.finished == null }
        if (openIndex >= 0) {
            val group = updated[openIndex]
            updated[openIndex] = group.copy(entries = group.entries + entry)
        } else {
            updated += SessionStepGroup(
                roundId = roundId,
                stepIndex = null,
                entries = listOf(entry),
            )
        }
        return updated
    }

    fun summarize(group: SessionStepGroup): String? {
        val userText = group.userEntries.lastOrNull()?.text?.let(::flatten)?.let(::truncate)
        val assistantText = group.assistantEntries.lastOrNull()?.text?.let(::flatten)?.let(::truncate)
        val approvals = group.approvalKinds.map { type ->
            when (type) {
                ApprovalRequest.Type.COMMAND -> "command"
                ApprovalRequest.Type.PATCH -> "patch"
            }
        }
        if (userText == null && assistantText == null && group.toolNames.isEmpty() && approvals.isEmpty()) return null

        return buildString {
            append("Step ")
            append(group.stepIndex ?: "?")
            append(" [")
            append(group.status.name.lowercase())
            append("]")
            userText?.let {
                append(" user: ")
                append(it)
            }
            assistantText?.let {
                append(" | assistant: ")
                append(it)
            }
            if (group.toolNames.isNotEmpty()) {
                append(" | tools: ")
                append(group.toolNames.joinToString(", "))
            }
            if (approvals.isNotEmpty()) {
                append(" | approvals: ")
                append(approvals.joinToString(", "))
            }
        }
    }

    private fun flatten(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun truncate(value: String): String {
        return if (value.length <= SUMMARY_VALUE_LIMIT) value else value.take(SUMMARY_VALUE_LIMIT - 3) + "..."
    }
}

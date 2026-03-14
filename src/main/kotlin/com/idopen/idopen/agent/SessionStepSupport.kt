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
    private const val TITLE_VALUE_LIMIT = 72

    fun group(transcript: List<TranscriptEntry>): List<SessionStepGroup> {
        return transcript.fold(emptyList(), ::append)
    }

    fun buildSteps(groups: List<SessionStepGroup>): List<SessionStep> {
        return groups.map(::toStep)
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

    fun toStep(group: SessionStepGroup): SessionStep {
        return SessionStep(
            roundId = group.roundId,
            stepIndex = group.stepIndex,
            status = group.status,
            title = title(group),
            summary = summarize(group),
            startedAt = group.started?.createdAt ?: group.entries.firstOrNull()?.createdAtOrNull(),
            finishedAt = group.finished?.createdAt,
            reason = group.finished?.reason,
            toolCalls = group.finished?.toolCalls ?: group.toolEntries.size,
            parts = group.entries.mapNotNull(::toPart),
        )
    }

    private fun title(group: SessionStepGroup): String {
        val candidate = sequenceOf(
            group.userEntries.lastOrNull()?.text,
            group.assistantEntries.lastOrNull()?.text,
            group.toolEntries.firstOrNull()?.title,
            group.toolEntries.firstOrNull()?.toolName,
            group.approvalEntries.firstOrNull()?.request?.title,
        )
            .filterNotNull()
            .map(::flatten)
            .map { truncate(it, TITLE_VALUE_LIMIT) }
            .firstOrNull { it.isNotBlank() }

        return candidate ?: "Step ${group.stepIndex ?: "?"}"
    }

    private fun toPart(entry: TranscriptEntry): SessionStepPart? {
        return when (entry) {
            is TranscriptEntry.Context -> SessionStepPart.Context(
                summary = entry.summary,
                createdAt = entry.createdAt,
            )
            is TranscriptEntry.User -> SessionStepPart.User(
                text = entry.text,
                createdAt = entry.createdAt,
            )
            is TranscriptEntry.Assistant -> SessionStepPart.Assistant(
                text = entry.text,
                createdAt = entry.createdAt,
            )
            is TranscriptEntry.ToolInvocation -> SessionStepPart.Tool(
                callId = entry.callId,
                toolName = entry.toolName,
                argumentsJson = entry.argumentsJson,
                state = entry.state,
                title = entry.title,
                metadata = entry.metadata,
                output = entry.output,
                success = entry.success,
                createdAt = entry.createdAt,
                startedAt = entry.startedAt,
                finishedAt = entry.finishedAt,
            )
            is TranscriptEntry.Approval -> SessionStepPart.Approval(
                title = entry.request.title,
                type = entry.request.type,
                status = entry.request.status,
                summary = approvalSummary(entry.request.payload),
                createdAt = entry.createdAt,
            )
            is TranscriptEntry.Error -> SessionStepPart.Error(
                message = entry.message,
                createdAt = entry.createdAt,
            )
            is TranscriptEntry.System -> SessionStepPart.System(
                message = entry.message,
                createdAt = entry.createdAt,
            )
            is TranscriptEntry.StepStart -> null
            is TranscriptEntry.StepFinish -> null
            is TranscriptEntry.ToolCall -> null
            is TranscriptEntry.ToolResult -> null
        }
    }

    private fun approvalSummary(payload: ApprovalPayload): String {
        return when (payload) {
            is ApprovalPayload.Command -> "command: ${payload.command}"
            is ApprovalPayload.Patch -> "patch: ${payload.filePath}"
        }
    }

    private fun flatten(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun truncate(value: String, maxLength: Int = SUMMARY_VALUE_LIMIT): String {
        return if (value.length <= maxLength) value else value.take(maxLength - 3) + "..."
    }

    private fun TranscriptEntry.createdAtOrNull() = when (this) {
        is TranscriptEntry.User -> createdAt
        is TranscriptEntry.Assistant -> createdAt
        is TranscriptEntry.ToolCall -> createdAt
        is TranscriptEntry.ToolResult -> createdAt
        is TranscriptEntry.ToolInvocation -> createdAt
        is TranscriptEntry.Approval -> createdAt
        is TranscriptEntry.StepStart -> createdAt
        is TranscriptEntry.StepFinish -> createdAt
        is TranscriptEntry.Error -> createdAt
        is TranscriptEntry.Context -> createdAt
        is TranscriptEntry.System -> createdAt
    }
}

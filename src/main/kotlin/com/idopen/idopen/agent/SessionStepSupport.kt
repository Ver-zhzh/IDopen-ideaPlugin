package com.idopen.idopen.agent

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
}

object SessionStepSupport {
    fun group(transcript: List<TranscriptEntry>): List<SessionStepGroup> {
        if (transcript.isEmpty()) return emptyList()

        val result = mutableListOf<MutableGroup>()
        val activeGroups = linkedMapOf<String, MutableGroup>()
        val pendingRoundEntries = linkedMapOf<String, MutableList<TranscriptEntry>>()

        transcript.forEach { entry ->
            val roundId = entry.roundId ?: return@forEach
            when (entry) {
                is TranscriptEntry.StepStart -> {
                    val group = MutableGroup(
                        roundId = roundId,
                        stepIndex = entry.stepIndex,
                        entries = pendingRoundEntries.remove(roundId)?.toMutableList() ?: mutableListOf(),
                    )
                    group.entries += entry
                    result += group
                    activeGroups[roundId] = group
                }
                else -> {
                    val active = activeGroups[roundId]
                    if (active != null) {
                        active.entries += entry
                        if (entry is TranscriptEntry.StepFinish) {
                            activeGroups.remove(roundId)
                        }
                    } else {
                        pendingRoundEntries.getOrPut(roundId) { mutableListOf() } += entry
                    }
                }
            }
        }

        pendingRoundEntries.forEach { (roundId, entries) ->
            result += MutableGroup(roundId, null, entries.toMutableList())
        }

        return result.map { SessionStepGroup(it.roundId, it.stepIndex, it.entries.toList()) }
    }

    private data class MutableGroup(
        val roundId: String,
        val stepIndex: Int?,
        val entries: MutableList<TranscriptEntry>,
    )
}

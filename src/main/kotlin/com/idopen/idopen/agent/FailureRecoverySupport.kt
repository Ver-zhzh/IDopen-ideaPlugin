package com.idopen.idopen.agent

object FailureRecoverySupport {
    fun toolHint(
        toolName: String,
        failureText: String,
        argumentsJson: String,
    ): String? {
        val compactFailure = failureText.replace(Regex("\\s+"), " ").trim().take(220)
        return when (toolName) {
            "read_file" -> "The previous read_file call failed. Re-check the path, then use read_project_tree or search_text before trying the same read again. Failure: $compactFailure"
            "search_text" -> "The previous search_text call failed. Narrow the query or read the current file first before searching again. Failure: $compactFailure"
            "run_command" -> "The previous run_command call failed. Prefer read-only commands, verify the working directory, and avoid repeating the same command unchanged. Failure: $compactFailure"
            "apply_patch_preview" -> "The previous apply_patch_preview call failed. Read the latest file window again and prefer occurrence, before/after anchors, or line edits instead of repeating the same patch. Failure: $compactFailure"
            else -> {
                if (compactFailure.isBlank() && argumentsJson.isBlank()) null
                else "The previous $toolName call failed. Adjust the arguments before retrying. Failure: $compactFailure"
            }
        }
    }

    fun approvalHint(request: ApprovalRequest): String {
        return when (request.type) {
            ApprovalRequest.Type.COMMAND -> "The user rejected the command request. Explain why the command is needed or find a read-only alternative."
            ApprovalRequest.Type.PATCH -> "The user rejected the file edit. Re-read the relevant file window and propose a narrower change with clearer justification."
        }
    }

    fun patchHint(
        filePath: String,
        edits: List<PatchEdit>,
        message: String,
    ): String {
        val strategy = when {
            edits.any { !it.search.isNullOrBlank() } -> "If the search text is ambiguous, add occurrence or replaceAll. If it was not found, re-read the file window and match the exact current text."
            edits.any { !it.before.isNullOrBlank() || !it.after.isNullOrBlank() } -> "If the anchor is ambiguous, add occurrence. If it was not found, re-read the surrounding lines and choose a more stable anchor."
            edits.any { it.startLine != null } -> "If the target lines shifted, re-read the file window and update the line range before retrying."
            else -> "Re-read the latest file content before retrying the patch."
        }
        val compactMessage = message.replace(Regex("\\s+"), " ").trim().take(220)
        return "Patch application for $filePath failed. $strategy Failure: $compactMessage"
    }

    fun runtimeHint(message: String): String? {
        val compact = message.replace(Regex("\\s+"), " ").trim()
        if (compact.isBlank()) return null
        return "The last turn failed with: ${compact.take(220)}. Avoid repeating the same failing action without narrowing the scope or changing the approach."
    }
}

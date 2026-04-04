package com.idopen.idopen.agent

object FailureRecoverySupport {
    fun toolHint(
        toolName: String,
        failureText: String,
        argumentsJson: String,
    ): String? {
        val compactFailure = failureText.replace(Regex("\\s+"), " ").trim().take(220)
        return when (toolName) {
            "mcp_list_servers" -> "The previous mcp_list_servers call failed. Re-check whether the project has .idopen/mcp.json configuration, or a legacy .mcp.json file, before retrying. Failure: $compactFailure"
            "mcp_describe_server" -> "The previous mcp_describe_server call failed. Use mcp_list_servers first, then retry with one exact server name. Failure: $compactFailure"
            "mcp_list_tools" -> "The previous mcp_list_tools call failed. Confirm the server uses a supported transport, then retry with one exact server name from mcp_list_servers. Failure: $compactFailure"
            "mcp_call_tool" -> "The previous mcp_call_tool call failed. Re-run mcp_list_tools, verify the exact tool name and schema, and narrow the arguments before retrying. Failure: $compactFailure"
            "mcp_list_resources" -> "The previous mcp_list_resources call failed. Confirm the server uses a supported transport, then retry with one exact server name from mcp_list_servers. Failure: $compactFailure"
            "mcp_read_resource" -> "The previous mcp_read_resource call failed. Re-run mcp_list_resources, verify the exact resource uri, and retry with one listed resource only. Failure: $compactFailure"
            "mcp_list_resource_templates" -> "The previous mcp_list_resource_templates call failed. Confirm the server uses a supported transport, then retry with one exact server name from mcp_list_servers. Failure: $compactFailure"
            "mcp_list_prompts" -> "The previous mcp_list_prompts call failed. Confirm the server uses a supported transport, then retry with one exact server name from mcp_list_servers. Failure: $compactFailure"
            "mcp_get_prompt" -> "The previous mcp_get_prompt call failed. Re-run mcp_list_prompts, verify the exact prompt name and required arguments, and narrow the arguments before retrying. Failure: $compactFailure"
            "skill" -> "The previous skill call failed. Retry with an exact skill name from the available skills list instead of guessing. Failure: $compactFailure"
            "todo_read" -> "The previous todo_read call failed. Retry without extra arguments and keep reading the current session todo list only. Failure: $compactFailure"
            "todo_write" -> "The previous todo_write call failed. Rewrite the full ordered todo list, keep items short, and leave at most one item in_progress. Failure: $compactFailure"
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

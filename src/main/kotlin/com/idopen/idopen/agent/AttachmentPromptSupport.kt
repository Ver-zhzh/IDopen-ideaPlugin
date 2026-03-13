package com.idopen.idopen.agent

object AttachmentPromptSupport {
    data class Prepared(
        val transcriptSummary: String,
        val injectedPrompt: String,
    )

    fun prepare(
        attachments: List<AttachmentContext>,
        toolCallingEnabled: Boolean,
    ): Prepared {
        return if (toolCallingEnabled) {
            Prepared(
                transcriptSummary = buildSummary(attachments),
                injectedPrompt = buildToolFirstPrompt(attachments),
            )
        } else {
            Prepared(
                transcriptSummary = buildSummary(attachments),
                injectedPrompt = buildResolvedPrompt(attachments),
            )
        }
    }

    private fun buildSummary(attachments: List<AttachmentContext>): String {
        return buildString {
            appendLine("已附带 IDE 上下文引用：")
            attachments.forEach { attachment ->
                append("- ")
                appendLine(summaryLine(attachment))
            }
        }.trim()
    }

    private fun buildToolFirstPrompt(attachments: List<AttachmentContext>): String {
        return buildString {
            appendLine("The plugin attached IDE context references for this turn.")
            appendLine("Treat those references as hints only, not as the source of truth.")
            appendLine("Before answering code-specific questions, inspect the exact code with IDE tools.")
            appendLine("Prefer get_current_selection, get_current_file, read_file(path, offset, limit), and search_text.")
            appendLine()
            attachments.forEach { attachment ->
                appendLine("Attachment: ${summaryLine(attachment)}")
                appendLine("Required read step: ${requiredReadStep(attachment)}")
                appendLine()
            }
            appendLine("Do not rely on attachment summaries when exact code can be inspected with tools.")
        }.trim()
    }

    private fun buildResolvedPrompt(attachments: List<AttachmentContext>): String {
        return buildString {
            appendLine("Attached IDE context resolved by the plugin because tool calling is disabled:")
            attachments.forEach { attachment ->
                appendLine("### ${attachment.label}")
                appendLine(summaryLine(attachment))
                val content = (attachment.resolvedContent ?: attachment.content).orEmpty().trim()
                if (content.isNotBlank()) {
                    appendLine(content)
                }
                appendLine()
            }
        }.trim()
    }

    private fun requiredReadStep(attachment: AttachmentContext): String {
        return when (attachment.kind) {
            AttachmentKind.CURRENT_SELECTION -> {
                val nearbyRead = attachment.path?.let {
                    " Then inspect nearby code with read_file(path=\"$it\", offset=${nearbyOffset(attachment)}, limit=120)."
                }.orEmpty()
                "Call get_current_selection first.$nearbyRead"
            }

            AttachmentKind.CURRENT_FILE -> {
                val fallback = attachment.path?.let {
                    " If the focused editor is different, call read_file(path=\"$it\", offset=1, limit=${ReadWindowSupport.DEFAULT_LIMIT})."
                }.orEmpty()
                "Call get_current_file first.$fallback"
            }

            AttachmentKind.GENERIC -> "Use the most relevant IDE read tool before answering."
        }
    }

    private fun summaryLine(attachment: AttachmentContext): String {
        return when (attachment.kind) {
            AttachmentKind.CURRENT_FILE -> {
                val path = attachment.path ?: attachment.label
                "当前文件：$path。先用 get_current_file 或 read_file 读取准确内容。"
            }

            AttachmentKind.CURRENT_SELECTION -> {
                val path = attachment.path ?: "<current editor>"
                val range = if (attachment.startLine != null && attachment.endLine != null) {
                    " 第 ${attachment.startLine}-${attachment.endLine} 行"
                } else {
                    ""
                }
                "当前选区：$path$range。先用 get_current_selection 读取准确内容。"
            }

            AttachmentKind.GENERIC -> attachment.reference
        }
    }

    private fun nearbyOffset(attachment: AttachmentContext): Int {
        val startLine = attachment.startLine ?: 1
        return (startLine - 40).coerceAtLeast(1)
    }
}

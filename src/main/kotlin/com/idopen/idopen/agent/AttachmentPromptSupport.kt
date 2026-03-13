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
            appendLine("已附带 IDE 上下文：")
            attachments.forEach { attachment ->
                append("- ")
                appendLine(attachment.reference)
            }
        }.trim()
    }

    private fun buildToolFirstPrompt(attachments: List<AttachmentContext>): String {
        return buildString {
            appendLine("You have IDE context references attached by the plugin.")
            appendLine("Treat these as references only, not as the source of truth.")
            appendLine("Before answering any code-specific request, inspect the exact code using IDE tools.")
            appendLine()
            attachments.forEach { attachment ->
                appendLine("Attachment: ${attachment.reference}")
                appendLine("Required read step: ${requiredReadStep(attachment)}")
                appendLine()
            }
            appendLine("Do not rely on attachment summaries when exact code can be inspected with tools.")
        }.trim()
    }

    private fun buildResolvedPrompt(attachments: List<AttachmentContext>): String {
        return buildString {
            appendLine("Attached IDE context resolved by the plugin:")
            attachments.forEach { attachment ->
                appendLine("### ${attachment.label}")
                appendLine(attachment.reference)
                val content = (attachment.resolvedContent ?: attachment.content).orEmpty().trim()
                if (content.isNotBlank()) {
                    appendLine(content)
                }
                appendLine()
            }
        }.trim()
    }

    private fun requiredReadStep(attachment: AttachmentContext): String {
        val text = "${attachment.label} ${attachment.reference}".lowercase()
        return when {
            "selection" in text || "选区" in text ->
                "Call get_current_selection first. If you need broader context, then call get_current_file or read_file."

            "file" in text || "文件" in text ->
                "Call get_current_file if the editor is focused on this file. Otherwise call read_file with the referenced path."

            else ->
                "Use the most relevant IDE read tool before answering."
        }
    }
}

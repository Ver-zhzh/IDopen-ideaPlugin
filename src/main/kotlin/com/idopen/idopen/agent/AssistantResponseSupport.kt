package com.idopen.idopen.agent

object AssistantResponseSupport {
    fun partition(markdown: String): List<AssistantOutputPart> {
        if (markdown.isBlank()) return emptyList()

        val parts = mutableListOf<AssistantOutputPart>()
        val paragraph = mutableListOf<String>()
        val listItems = mutableListOf<String>()
        var orderedList = false
        var inCodeBlock = false
        var codeLanguage: String? = null
        val codeLines = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            parts += AssistantOutputPart.Text(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }

        fun flushList() {
            if (listItems.isEmpty()) return
            parts += AssistantOutputPart.ListBlock(listItems.toList(), orderedList)
            listItems.clear()
        }

        fun flushCode() {
            if (codeLines.isEmpty()) return
            parts += AssistantOutputPart.CodeBlock(
                code = codeLines.joinToString("\n"),
                language = codeLanguage?.takeIf { it.isNotBlank() },
            )
            codeLines.clear()
            codeLanguage = null
        }

        markdown.lines().forEach { rawLine ->
            val trimmed = rawLine.trimEnd()
            if (trimmed.startsWith("```")) {
                flushParagraph()
                flushList()
                if (inCodeBlock) {
                    flushCode()
                } else {
                    codeLanguage = trimmed.removePrefix("```").trim().ifBlank { null }
                }
                inCodeBlock = !inCodeBlock
                return@forEach
            }

            if (inCodeBlock) {
                codeLines += rawLine
                return@forEach
            }

            if (trimmed.isBlank()) {
                flushParagraph()
                flushList()
                return@forEach
            }

            val ordered = Regex("^\\d+\\.\\s+(.+)$").matchEntire(trimmed)
            if (ordered != null) {
                flushParagraph()
                if (listItems.isNotEmpty() && !orderedList) flushList()
                orderedList = true
                listItems += ordered.groupValues[1]
                return@forEach
            }

            val unordered = Regex("^[-*]\\s+(.+)$").matchEntire(trimmed)
            if (unordered != null) {
                flushParagraph()
                if (listItems.isNotEmpty() && orderedList) flushList()
                orderedList = false
                listItems += unordered.groupValues[1]
                return@forEach
            }

            flushList()
            paragraph += trimmed
        }

        flushParagraph()
        flushList()
        flushCode()
        return parts.filterNot {
            it is AssistantOutputPart.Text && it.text.isBlank()
        }
    }
}

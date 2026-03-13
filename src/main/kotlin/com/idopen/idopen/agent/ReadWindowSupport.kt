package com.idopen.idopen.agent

object ReadWindowSupport {
    const val DEFAULT_LIMIT = 200
    private const val MAX_LIMIT = 800
    private const val MAX_LINE_LENGTH = 2000
    private const val TRUNCATED_SUFFIX = "... (line truncated)"

    data class WindowRequest(
        val offset: Int,
        val limit: Int,
    )

    data class WindowSlice(
        val offset: Int,
        val lines: List<String>,
        val totalLines: Int,
        val hasMore: Boolean,
    )

    fun normalizeRequest(
        offset: Int? = null,
        limit: Int? = null,
        startLine: Int? = null,
        endLine: Int? = null,
    ): WindowRequest {
        val normalizedOffset = (startLine ?: offset ?: 1).coerceAtLeast(1)
        val normalizedLimit = when {
            limit != null -> limit
            startLine != null && endLine != null && endLine >= startLine -> endLine - startLine + 1
            else -> DEFAULT_LIMIT
        }.coerceIn(1, MAX_LIMIT)

        return WindowRequest(
            offset = normalizedOffset,
            limit = normalizedLimit,
        )
    }

    fun sliceLines(lines: List<String>, request: WindowRequest): WindowSlice {
        if (lines.isEmpty()) {
            return WindowSlice(offset = 1, lines = emptyList(), totalLines = 0, hasMore = false)
        }

        val startIndex = (request.offset - 1).coerceAtMost(lines.size)
        val endExclusive = (startIndex + request.limit).coerceAtMost(lines.size)
        return WindowSlice(
            offset = request.offset,
            lines = lines.subList(startIndex, endExclusive).map(::truncateLine),
            totalLines = lines.size,
            hasMore = endExclusive < lines.size,
        )
    }

    fun formatFile(path: String, slice: WindowSlice): String {
        val body = slice.lines.mapIndexed { index, line ->
            "${slice.offset + index}: $line"
        }.joinToString("\n")

        val footer = when {
            slice.totalLines == 0 -> "(Empty file)"
            slice.hasMore -> {
                val lastReadLine = slice.offset + slice.lines.size - 1
                "(Showing lines ${slice.offset}-$lastReadLine of ${slice.totalLines}. Use offset=${lastReadLine + 1} to continue.)"
            }
            else -> "(End of file - total ${slice.totalLines} lines)"
        }

        return buildString {
            appendLine("<path>$path</path>")
            appendLine("<type>file</type>")
            appendLine("<content>")
            if (body.isNotBlank()) {
                appendLine(body)
                appendLine()
            }
            append(footer)
            appendLine()
            append("</content>")
        }
    }

    fun formatDirectory(path: String, entries: List<String>, request: WindowRequest): String {
        val startIndex = (request.offset - 1).coerceAtMost(entries.size)
        val endExclusive = (startIndex + request.limit).coerceAtMost(entries.size)
        val slice = entries.subList(startIndex, endExclusive)
        val footer = if (endExclusive < entries.size) {
            "(Showing entries ${request.offset}-${request.offset + slice.size - 1} of ${entries.size}. Use offset=${request.offset + slice.size} to continue.)"
        } else {
            "(${entries.size} entries)"
        }

        return buildString {
            appendLine("<path>$path</path>")
            appendLine("<type>directory</type>")
            appendLine("<entries>")
            if (slice.isNotEmpty()) {
                appendLine(slice.joinToString("\n"))
                appendLine()
            }
            append(footer)
            appendLine()
            append("</entries>")
        }
    }

    fun formatSelection(path: String?, startLine: Int, endLine: Int, content: String): String {
        val lines = content.lines().map(::truncateLine)
        val numbered = lines.mapIndexed { index, line -> "${startLine + index}: $line" }.joinToString("\n")
        return buildString {
            appendLine("<path>${path ?: "<current editor>"}</path>")
            appendLine("<type>selection</type>")
            appendLine("<range>$startLine-$endLine</range>")
            appendLine("<content>")
            appendLine(numbered)
            append("</content>")
        }
    }

    private fun truncateLine(text: String): String {
        return if (text.length <= MAX_LINE_LENGTH) text else text.take(MAX_LINE_LENGTH) + TRUNCATED_SUFFIX
    }
}

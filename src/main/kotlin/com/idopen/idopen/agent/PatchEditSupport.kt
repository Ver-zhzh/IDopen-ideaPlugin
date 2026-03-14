package com.idopen.idopen.agent

object PatchEditSupport {
    fun apply(beforeText: String, edits: List<PatchEdit>): String {
        require(edits.isNotEmpty()) { "At least one edit is required." }
        return edits.fold(beforeText) { current, edit ->
            when {
                !edit.search.isNullOrBlank() -> applySearchReplace(current, edit)
                !edit.before.isNullOrBlank() -> insertRelative(current, edit, beforeAnchor = true)
                !edit.after.isNullOrBlank() -> insertRelative(current, edit, beforeAnchor = false)
                edit.startLine != null -> applyLineEdit(current, edit)
                else -> error("Unsupported edit. Provide search/replace, before/after, or line-based fields.")
            }
        }
    }

    private fun applySearchReplace(current: String, edit: PatchEdit): String {
        val search = edit.search ?: error("search must not be blank.")
        val replace = edit.newText ?: edit.replace.orEmpty()
        val matches = findMatches(current, search)
        require(matches.isNotEmpty()) { "Could not find the target search text." }

        if (edit.replaceAll) {
            return current.replace(search, replace)
        }

        val selected = when {
            edit.occurrence != null -> {
                val occurrence = requireNotNull(edit.occurrence)
                require(occurrence in 1..matches.size) {
                    "occurrence must be between 1 and ${matches.size}."
                }
                matches[occurrence - 1]
            }
            matches.size == 1 -> matches.single()
            else -> throw IllegalArgumentException("Search text matched ${matches.size} times. Use occurrence or replaceAll.")
        }

        return current.replaceRange(selected.first, selected.last + 1, replace)
    }

    private fun insertRelative(current: String, edit: PatchEdit, beforeAnchor: Boolean): String {
        val anchor = if (beforeAnchor) edit.before else edit.after
        require(!anchor.isNullOrBlank()) { "An anchor string is required." }
        val insertion = edit.newText ?: edit.replace.orEmpty()
        val matches = findMatches(current, anchor)
        require(matches.isNotEmpty()) { "Could not find the requested anchor." }

        val selected = when {
            edit.occurrence != null -> {
                val occurrence = requireNotNull(edit.occurrence)
                require(occurrence in 1..matches.size) {
                    "occurrence must be between 1 and ${matches.size}."
                }
                matches[occurrence - 1]
            }
            matches.size == 1 -> matches.single()
            else -> throw IllegalArgumentException("Anchor matched ${matches.size} times. Use occurrence to disambiguate.")
        }

        val insertAt = if (beforeAnchor) selected.first else selected.last
        return current.substring(0, insertAt) + insertion + current.substring(insertAt)
    }

    private fun applyLineEdit(current: String, edit: PatchEdit): String {
        val startLine = requireNotNull(edit.startLine) { "startLine must not be blank." }
        val endLine = edit.endLine ?: startLine
        val newText = edit.newText ?: edit.replace.orEmpty()
        require(startLine >= 1) { "startLine must be >= 1." }
        require(endLine >= startLine - 1) { "endLine must be >= startLine - 1." }

        val lineEnding = detectLineEnding(current)
        val (lines, hasTrailingNewline) = splitLines(current)
        require(startLine <= lines.size + 1) { "startLine is outside the file line range." }
        require(endLine <= lines.size) { "endLine is outside the file line range." }

        val replacement = splitLines(newText).first
        val fromIndex = (startLine - 1).coerceAtMost(lines.size)
        val toIndexExclusive = if (endLine < startLine) fromIndex else endLine

        val merged = buildList {
            addAll(lines.subList(0, fromIndex))
            addAll(replacement)
            addAll(lines.subList(toIndexExclusive, lines.size))
        }

        return joinLines(
            lines = merged,
            lineEnding = lineEnding,
            trailingNewline = hasTrailingNewline || newText.endsWith("\n") || newText.endsWith("\r\n"),
        )
    }

    private fun detectLineEnding(text: String): String {
        return if (text.contains("\r\n")) "\r\n" else "\n"
    }

    private fun splitLines(text: String): Pair<List<String>, Boolean> {
        if (text.isEmpty()) return emptyList<String>() to false
        val normalized = text.replace("\r\n", "\n")
        val trailing = normalized.endsWith('\n')
        val body = if (trailing) normalized.dropLast(1) else normalized
        return if (body.isEmpty()) emptyList<String>() to trailing else body.split('\n') to trailing
    }

    private fun joinLines(lines: List<String>, lineEnding: String, trailingNewline: Boolean): String {
        if (lines.isEmpty()) return if (trailingNewline) lineEnding else ""
        val joined = lines.joinToString(lineEnding)
        return if (trailingNewline) joined + lineEnding else joined
    }

    private fun findMatches(current: String, needle: String): List<IntRange> {
        val matches = mutableListOf<IntRange>()
        var index = current.indexOf(needle)
        while (index >= 0) {
            matches += index until (index + needle.length)
            index = current.indexOf(needle, index + needle.length)
        }
        return matches
    }
}

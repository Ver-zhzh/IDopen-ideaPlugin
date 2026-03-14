package com.idopen.idopen.agent

object PatchEditSupport {
    fun apply(beforeText: String, edits: List<PatchEdit>): String {
        require(edits.isNotEmpty()) { "至少需要一个 edit。" }
        return edits.fold(beforeText) { current, edit ->
            when {
                !edit.search.isNullOrBlank() -> applySearchReplace(current, edit)
                edit.startLine != null -> applyLineEdit(current, edit)
                else -> error("不支持的 edit：必须提供 search/replace，或 startLine/endLine/newText。")
            }
        }
    }

    private fun applySearchReplace(current: String, edit: PatchEdit): String {
        val search = edit.search ?: error("search 不能为空。")
        val replace = edit.replace.orEmpty()
        val firstIndex = current.indexOf(search)
        require(firstIndex >= 0) { "未找到要替换的文本片段。" }
        require(current.indexOf(search, firstIndex + search.length) < 0) {
            "要替换的文本片段出现了多次，请改用更精确的 search 或按行范围编辑。"
        }
        return current.replaceFirst(search, replace)
    }

    private fun applyLineEdit(current: String, edit: PatchEdit): String {
        val startLine = requireNotNull(edit.startLine) { "startLine 不能为空。" }
        val endLine = edit.endLine ?: startLine
        val newText = edit.newText ?: edit.replace.orEmpty()
        require(startLine >= 1) { "startLine 必须从 1 开始。" }
        require(endLine >= startLine - 1) { "endLine 必须大于等于 startLine - 1。" }

        val lineEnding = detectLineEnding(current)
        val (lines, hasTrailingNewline) = splitLines(current)
        require(startLine <= lines.size + 1) { "startLine 超出了文件总行数范围。" }
        require(endLine <= lines.size) { "endLine 超出了文件总行数范围。" }

        val replacement = splitLines(newText).first
        val fromIndex = (startLine - 1).coerceAtMost(lines.size)
        val toIndexExclusive = if (endLine < startLine) fromIndex else endLine

        val merged = buildList {
            addAll(lines.subList(0, fromIndex))
            addAll(replacement)
            addAll(lines.subList(toIndexExclusive, lines.size))
        }

        return joinLines(merged, lineEnding, hasTrailingNewline || newText.endsWith("\n") || newText.endsWith("\r\n"))
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
}

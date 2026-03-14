package com.idopen.idopen.agent

object SessionTitleSupport {
    private const val MAX_TITLE_LENGTH = 24

    private val genericTitles = setOf(
        "新对话",
        "继续",
        "继续处理",
        "继续看",
        "继续靠",
        "看下",
        "看一下",
        "看看",
        "帮我看下",
        "帮我看看",
        "帮忙看下",
        "处理一下",
    )

    fun pickTitle(currentTitle: String, defaultTitle: String, userText: String): String? {
        val candidate = summarize(userText) ?: return null
        val refreshable = shouldRefresh(currentTitle, defaultTitle)
        return when {
            refreshable -> candidate
            currentTitle == candidate -> null
            else -> null
        }
    }

    fun summarize(userText: String): String? {
        val cleaned = userText
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .replace(Regex("[#>*`]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return null

        val normalized = cleaned
            .removePrefix("请帮我")
            .removePrefix("帮我")
            .removePrefix("请")
            .removePrefix("麻烦")
            .trim()
            .ifBlank { cleaned }

        if (normalized in genericTitles) return null
        if (normalized.length <= 2) return null
        return if (normalized.length <= MAX_TITLE_LENGTH) {
            normalized
        } else {
            normalized.take(MAX_TITLE_LENGTH - 3) + "..."
        }
    }

    fun shouldRefresh(currentTitle: String, defaultTitle: String): Boolean {
        val normalized = currentTitle.trim()
        return normalized.isBlank() || normalized == defaultTitle || normalized in genericTitles
    }
}

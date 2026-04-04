package com.idopen.idopen.settings

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.idopen.idopen.agent.ProviderConfig
import com.idopen.idopen.agent.ProviderConfigSupport
import com.idopen.idopen.agent.ProviderType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object ChatGptQuotaSupport {
    const val USAGE_PAGE_URL = "https://chatgpt.com/codex/settings/usage"

    private const val USAGE_ENDPOINT = "https://chatgpt.com/backend-api/wham/usage"
    private const val REQUEST_TIMEOUT_SECONDS = 30L

    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        .build()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    data class QuotaWindow(
        val source: String,
        val labelOverride: String?,
        val usedPercent: Int?,
        val remainingPercent: Int?,
        val resetAt: Long?,
        val windowSeconds: Long?,
    ) {
        fun summaryLine(language: DisplayLanguage = DisplayLanguage.EN_US): String {
            val label = labelOverride ?: ChatGptQuotaSupport.defaultWindowLabel(source, windowSeconds, language)
            val remainingText = when {
                remainingPercent != null -> {
                    if (language == DisplayLanguage.ZH_CN) "$remainingPercent% 剩余" else "$remainingPercent% remaining"
                }
                usedPercent != null -> {
                    val value = (100 - usedPercent).coerceIn(0, 100)
                    if (language == DisplayLanguage.ZH_CN) "$value% 剩余" else "$value% remaining"
                }
                else -> if (language == DisplayLanguage.ZH_CN) "剩余额度不可用" else "remaining quota unavailable"
            }
            val resetText = resetAt?.let {
                val formatted = ChatGptQuotaSupport.timeFormatter.format(Instant.ofEpochMilli(it))
                if (language == DisplayLanguage.ZH_CN) " | 重置于 $formatted" else " | resets $formatted"
            }.orEmpty()
            return "$label: $remainingText$resetText"
        }
    }

    data class CreditsStatus(
        val hasCredits: Boolean?,
        val unlimited: Boolean?,
        val balance: String?,
    ) {
        fun summaryLine(language: DisplayLanguage = DisplayLanguage.EN_US): String {
            return when {
                unlimited == true -> if (language == DisplayLanguage.ZH_CN) "Credits：无限制" else "Credits: unlimited"
                !balance.isNullOrBlank() -> {
                    if (language == DisplayLanguage.ZH_CN) "Credits 余额：$balance" else "Credits balance: $balance"
                }
                hasCredits == true -> if (language == DisplayLanguage.ZH_CN) "Credits 可用" else "Credits are available"
                hasCredits == false -> if (language == DisplayLanguage.ZH_CN) "Credits：无" else "Credits: none"
                else -> if (language == DisplayLanguage.ZH_CN) "Credits：不可用" else "Credits: unavailable"
            }
        }
    }

    data class ChatGptQuotaStatus(
        val planType: String?,
        val windows: List<QuotaWindow>,
        val credits: CreditsStatus?,
    ) {
        fun summary(language: DisplayLanguage = DisplayLanguage.EN_US): String {
            val primary = windows.firstOrNull()?.summaryLine(language)
            return buildString {
                append(if (language == DisplayLanguage.ZH_CN) "额度" else "Quota")
                planType?.takeIf { it.isNotBlank() }?.let {
                    append(if (language == DisplayLanguage.ZH_CN) "（$it）" else " ($it)")
                }
                if (!primary.isNullOrBlank()) {
                    append(": ")
                    append(primary)
                } else if (credits != null) {
                    append(": ")
                    append(credits.summaryLine(language))
                } else {
                    append(": ")
                    append(if (language == DisplayLanguage.ZH_CN) "服务端未返回额度窗口" else "no window data returned")
                }
            }
        }

        fun details(language: DisplayLanguage = DisplayLanguage.EN_US): String {
            return buildString {
                append(if (language == DisplayLanguage.ZH_CN) "ChatGPT Codex 额度" else "ChatGPT Codex quota")
                planType?.takeIf { it.isNotBlank() }?.let {
                    append("\n")
                    append(if (language == DisplayLanguage.ZH_CN) "套餐：" else "Plan: ")
                    append(it)
                }
                if (windows.isEmpty()) {
                    append("\n")
                    append(if (language == DisplayLanguage.ZH_CN) "服务端未返回额度窗口。" else "No quota windows were returned by the server.")
                } else {
                    windows.forEach { window ->
                        append("\n")
                        append(window.summaryLine(language))
                    }
                }
                credits?.let {
                    append("\n")
                    append(it.summaryLine(language))
                }
                append("\n\n")
                append(
                    if (language == DisplayLanguage.ZH_CN) {
                        "该结果基于 ChatGPT 内部 usage 接口，字段可能随时变化。"
                    } else {
                        "This is based on ChatGPT internal usage endpoints and may change without notice."
                    },
                )
            }
        }

        fun safeSummary(language: DisplayLanguage = DisplayLanguage.EN_US): String {
            return sanitizedText(summary(language), ::summary, language)
        }

        fun safeDetails(language: DisplayLanguage = DisplayLanguage.EN_US): String {
            return sanitizedText(details(language), ::details, language)
        }
    }

    fun fetchQuotaStatus(settings: IDopenSettingsState = IDopenSettingsState.getInstance()): ChatGptQuotaStatus {
        val baseConfig = ProviderConfigSupport.fromChatGptAuth(
            accessToken = settings.chatGptAccessToken,
            refreshToken = settings.chatGptRefreshToken,
            accessTokenExpiresAt = settings.chatGptAccessTokenExpiresAt,
            accountId = settings.chatGptAccountId,
            model = settings.defaultModel.ifBlank { ChatGptAuthSupport.defaultModel() },
            headersText = "",
            requireModel = false,
        ).config ?: error(
            if (DisplayLanguage.fromStored(settings.displayLanguage) == DisplayLanguage.ZH_CN) {
                "请先登录 ChatGPT。"
            } else {
                "Please sign in with ChatGPT first."
            },
        )

        return fetchQuotaStatus(baseConfig, settings, retryAfterRefresh = true)
    }

    fun openUsagePage() {
        ChatGptAuthSupport.openVerificationUrl(USAGE_PAGE_URL)
    }

    internal fun parseQuotaStatus(payload: String): ChatGptQuotaStatus {
        return parseQuotaStatus(mapper.readTree(payload))
    }

    internal fun parseQuotaStatus(root: JsonNode): ChatGptQuotaStatus {
        val windows = mutableListOf<QuotaWindow>()
        collectWindows(root, "", windows)

        return ChatGptQuotaStatus(
            planType = readPlanType(root),
            windows = windows.sortedBy { it.windowSeconds ?: Long.MAX_VALUE },
            credits = parseCredits(root.path("credits")),
        )
    }

    private fun fetchQuotaStatus(
        initialConfig: ProviderConfig,
        settings: IDopenSettingsState,
        retryAfterRefresh: Boolean,
    ): ChatGptQuotaStatus {
        val config = ChatGptAuthSupport.ensureActiveSession(initialConfig, settings)
        val response = httpClient.send(
            buildRequest(config),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() in 200..299) {
            return parseQuotaStatus(response.body())
        }

        if (retryAfterRefresh && response.statusCode() in setOf(401, 403) && !config.refreshToken.isNullOrBlank()) {
            val forcedRefresh = ChatGptAuthSupport.ensureActiveSession(
                config.copy(apiKey = "", accessTokenExpiresAt = 0L),
                settings,
            )
            return fetchQuotaStatus(forcedRefresh, settings, retryAfterRefresh = false)
        }

        error("Failed to fetch ChatGPT quota: HTTP ${response.statusCode()} ${response.body().take(500)}")
    }

    private fun buildRequest(config: ProviderConfig): HttpRequest {
        check(config.type == ProviderType.CHATGPT_AUTH) { "Quota lookup requires ChatGPT auth." }
        return HttpRequest.newBuilder()
            .uri(URI.create(USAGE_ENDPOINT))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .header("User-Agent", "opencode/idopen-ideaPlugin")
            .header("originator", ChatGptAuthSupport.AUTH_ORIGINATOR)
            .apply {
                config.accountId?.takeIf { it.isNotBlank() }?.let {
                    header("ChatGPT-Account-Id", it)
                }
            }
            .GET()
            .build()
    }

    private fun collectWindows(
        node: JsonNode,
        source: String,
        target: MutableList<QuotaWindow>,
        depth: Int = 0,
    ) {
        if (depth > 4 || node.isMissingNode || node.isNull) return
        if (node.isArray) {
            node.forEachIndexed { index, item ->
                collectWindows(item, appendSource(source, "item_$index"), target, depth + 1)
            }
            return
        }
        if (!node.isObject) return

        parseWindow(node, source.ifBlank { "root" })?.let { candidate ->
            val key = "${candidate.source}|${candidate.windowSeconds}|${candidate.usedPercent}|${candidate.remainingPercent}|${candidate.resetAt}"
            if (target.none { existing ->
                    "${existing.source}|${existing.windowSeconds}|${existing.usedPercent}|${existing.remainingPercent}|${existing.resetAt}" == key
                }
            ) {
                target += candidate
            }
        }

        node.fields().forEachRemaining { (name, child) ->
            if (!child.isObject && !child.isArray) return@forEachRemaining
            if (depth == 0 || looksLikeQuotaContainer(name, child)) {
                collectWindows(child, appendSource(source, name), target, depth + 1)
            }
        }
    }

    private fun looksLikeQuotaContainer(name: String, node: JsonNode): Boolean {
        if (node.isArray) return true
        if (!node.isObject) return false
        val lowered = name.lowercase()
        if (
            lowered.contains("window") ||
            lowered.contains("limit") ||
            lowered.contains("quota") ||
            lowered.contains("credit")
        ) {
            return true
        }
        return listOf("used_percent", "remaining_percent", "reset_at", "reset_after_seconds", "limit_window_seconds")
            .any(node::has)
    }

    private fun appendSource(prefix: String, segment: String): String {
        return if (prefix.isBlank()) segment else "$prefix.$segment"
    }

    private fun parseArrayWindows(node: JsonNode): List<QuotaWindow> {
        if (!node.isArray) return emptyList()
        return node.mapIndexedNotNull { index, item ->
            parseWindow(item, "window_$index")
        }
    }

    private fun parseWindow(node: JsonNode, source: String): QuotaWindow? {
        if (node.isMissingNode || node.isNull || !node.isObject) return null

        val usedPercent = readPercent(node, "used_percent", "used_percentage", "usage_percent")
        val remainingPercent = readPercent(node, "remaining_percent", "remaining_percentage")
            ?: usedPercent?.let { (100 - it).coerceIn(0, 100) }
        val resetAt = parseTimestamp(node.path("resets_at"))
            ?: parseTimestamp(node.path("reset_at"))
            ?: parseRelativeSeconds(node.path("reset_after_seconds"))
            ?: parseRelativeSeconds(node.path("resets_in_seconds"))
            ?: parseRelativeSeconds(node.path("reset_in_seconds"))
        val windowSeconds = readLong(node, "limit_window_seconds", "window_seconds", "rolling_window_seconds")
        val label = readText(node, "label", "name", "display_name")

        if (usedPercent == null && remainingPercent == null && resetAt == null && windowSeconds == null) {
            return null
        }

        return QuotaWindow(
            source = source,
            labelOverride = label,
            usedPercent = usedPercent,
            remainingPercent = remainingPercent,
            resetAt = resetAt,
            windowSeconds = windowSeconds,
        )
    }

    private fun defaultWindowLabel(
        source: String,
        windowSeconds: Long?,
        language: DisplayLanguage = DisplayLanguage.EN_US,
    ): String {
        val duration = formatDuration(windowSeconds)
        return when {
            source.endsWith("primary_window") -> duration?.let {
                if (language == DisplayLanguage.ZH_CN) "主窗口（$it）" else "Primary window ($it)"
            } ?: if (language == DisplayLanguage.ZH_CN) "主窗口" else "Primary window"
            source.endsWith("secondary_window") -> duration?.let {
                if (language == DisplayLanguage.ZH_CN) "次窗口（$it）" else "Secondary window ($it)"
            } ?: if (language == DisplayLanguage.ZH_CN) "次窗口" else "Secondary window"
            source.contains("code_review") -> duration?.let {
                if (language == DisplayLanguage.ZH_CN) "代码审查（$it）" else "Code review ($it)"
            } ?: if (language == DisplayLanguage.ZH_CN) "代码审查" else "Code review"
            source.endsWith("rate_limit") || source.contains("rate_limit") -> duration?.let {
                if (language == DisplayLanguage.ZH_CN) "速率窗口（$it）" else "Rate limit ($it)"
            } ?: if (language == DisplayLanguage.ZH_CN) "速率窗口" else "Rate limit"
            else -> duration?.let {
                if (language == DisplayLanguage.ZH_CN) "额度窗口（$it）" else "Quota window ($it)"
            } ?: if (language == DisplayLanguage.ZH_CN) "额度窗口" else "Quota window"
        }
    }

    private fun formatDuration(windowSeconds: Long?): String? {
        val seconds = windowSeconds ?: return null
        if (seconds <= 0L) return null
        return when {
            seconds % 86_400L == 0L -> "${seconds / 86_400L}d"
            seconds % 3_600L == 0L -> "${seconds / 3_600L}h"
            seconds % 60L == 0L -> "${seconds / 60L}m"
            else -> "${seconds}s"
        }
    }

    private fun readPlanType(root: JsonNode): String? {
        return readText(root, "plan_type", "subscription_plan", "plan")
            ?: readText(root.path("subscription"), "plan_type", "plan")
            ?: readText(root.path("account"), "plan_type", "plan")
    }

    private fun parseCredits(node: JsonNode): CreditsStatus? {
        if (node.isMissingNode || node.isNull || !node.isObject) return null
        val balance = when {
            node.path("balance").isMissingNode || node.path("balance").isNull -> null
            node.path("balance").isNumber -> node.path("balance").decimalValue().stripTrailingZeros().toPlainString()
            else -> node.path("balance").asText("").trim().ifBlank { null }
        }
        val hasCredits = node.path("has_credits").takeIf { !it.isMissingNode && !it.isNull }?.asBoolean()
        val unlimited = node.path("unlimited").takeIf { !it.isMissingNode && !it.isNull }?.asBoolean()
        if (balance == null && hasCredits == null && unlimited == null) return null
        return CreditsStatus(
            hasCredits = hasCredits,
            unlimited = unlimited,
            balance = balance,
        )
    }

    private fun readText(node: JsonNode, vararg names: String): String? {
        names.forEach { name ->
            val value = node.path(name).asText("").trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun readLong(node: JsonNode, vararg names: String): Long? {
        names.forEach { name ->
            val candidate = node.path(name)
            if (candidate.isMissingNode || candidate.isNull) return@forEach
            if (candidate.isNumber) return candidate.asLong()
            candidate.asText("").trim().toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun readPercent(node: JsonNode, vararg names: String): Int? {
        names.forEach { name ->
            val candidate = node.path(name)
            if (candidate.isMissingNode || candidate.isNull) return@forEach
            if (candidate.isNumber) return candidate.asDouble().roundToInt()
            candidate.asText("").trim().toDoubleOrNull()?.let { return it.roundToInt() }
        }
        return null
    }

    private fun parseRelativeSeconds(node: JsonNode): Long? {
        val seconds = when {
            node.isMissingNode || node.isNull -> null
            node.isNumber -> node.asLong()
            else -> node.asText("").trim().toLongOrNull()
        } ?: return null
        return System.currentTimeMillis() + seconds * 1_000L
    }

    private fun parseTimestamp(node: JsonNode): Long? {
        if (node.isMissingNode || node.isNull) return null
        if (node.isNumber) return normalizeEpoch(node.asLong())

        val raw = node.asText("").trim()
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { return normalizeEpoch(it) }
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private fun normalizeEpoch(value: Long): Long {
        return if (value >= 1_000_000_000_000L) value else value * 1_000L
    }

    private fun sanitizedText(
        text: String,
        fallback: (DisplayLanguage) -> String,
        language: DisplayLanguage,
    ): String {
        return LocalizedTextSupport.fallbackToEnglishIfCorrupted(language, text) {
            fallback(DisplayLanguage.EN_US)
        }
    }

    private fun containsCorruptedLocalizedText(text: String): Boolean {
        return listOf(
            "鍓",
            "棰",
            "鏈嶅姟",
            "璇",
            "濂楅",
            "涓荤獥",
            "娆＄獥",
            "閫熺巼",
            "浠ｇ爜瀹℃煡",
            "鍙敤",
            "浣欓",
            "鐧诲綍",
        ).any(text::contains)
    }
}

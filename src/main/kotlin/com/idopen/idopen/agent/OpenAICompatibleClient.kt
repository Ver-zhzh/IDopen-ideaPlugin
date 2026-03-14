package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.math.min

class OpenAICompatibleClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        private const val MAX_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 350L
        private val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
    }

    private val mapper = ObjectMapper()

    data class ConnectionCheckResult(
        val message: String,
        val models: List<String>,
    )

    data class ChatRequest(
        val providerConfig: ProviderConfig,
        val messages: List<ConversationMessage>,
        val tools: List<ToolDefinition>,
    )

    data class ChatResult(
        val text: String,
        val outputParts: List<AssistantOutputPart>,
        val toolCalls: List<ToolCall>,
    )

    data class ChatStreamDelta(
        val delta: String,
        val snapshot: String,
        val outputParts: List<AssistantOutputPart>,
    )

    fun testConnection(config: ProviderConfig): ConnectionCheckResult {
        val models = listModels(config)
        val message = if (models.isEmpty()) {
            "连接成功，但服务没有返回可用模型。"
        } else {
            "连接成功，发现 ${models.size} 个模型。"
        }
        return ConnectionCheckResult(message, models)
    }

    fun listModels(config: ProviderConfig): List<String> {
        val endpoint = URI.create("${config.baseUrl}/models")
        val response = sendWithRetry(
            buildRequest = {
                requestBuilder(config, endpoint)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            },
            operationName = "获取模型列表",
        )
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().use(::readBody)
            error("获取模型列表失败：HTTP ${response.statusCode()} ${errorBody.take(800)}")
        }

        response.body().use { stream ->
            val root = mapper.readTree(stream)
            val data = root.path("data")
            if (!data.isArray) return emptyList()
            return data.mapNotNull { item ->
                item.path("id")
                    .takeIf { !it.isMissingNode && !it.isNull }
                    ?.asText()
                    ?.takeIf(String::isNotBlank)
            }.distinct().sorted()
        }
    }

    fun detectToolCapability(config: ProviderConfig): ToolCapability {
        val endpoint = URI.create("${config.baseUrl}/chat/completions")
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to config.model,
                "stream" to false,
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to "Tool capability probe. Reply briefly.",
                    ),
                ),
                "tools" to listOf(
                    mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "capability_probe",
                            "description" to "Simple capability probe",
                            "parameters" to mapOf(
                                "type" to "object",
                                "properties" to emptyMap<String, Any>(),
                            ),
                        ),
                    ),
                ),
                "tool_choice" to "auto",
                "max_tokens" to 1,
            ),
        )

        val response = sendWithRetry(
            buildRequest = {
                requestBuilder(config, endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build()
            },
            operationName = "探测工具调用能力",
        )

        return response.body().use { stream ->
            val responseBody = readBody(stream)
            when (response.statusCode()) {
                in 200..299 -> ToolCapability(
                    supportsToolCalling = true,
                    detail = "模型通过了工具调用探测。",
                )

                400, 404, 405, 422 -> ToolCapability(
                    supportsToolCalling = false,
                    detail = summarizeProbeFailure(response.statusCode(), responseBody),
                )

                else -> error(
                    "工具能力探测失败：HTTP ${response.statusCode()} ${responseBody.take(800)}",
                )
            }
        }
    }

    fun streamChat(
        request: ChatRequest,
        listener: (ChatStreamDelta) -> Unit = {},
    ): ChatResult {
        val endpoint = URI.create("${request.providerConfig.baseUrl}/chat/completions")
        val body = mapper.writeValueAsString(
            buildMap<String, Any> {
                put("model", request.providerConfig.model)
                put("stream", true)
                put("messages", request.messages.map(::serializeMessage))
                if (request.tools.isNotEmpty()) {
                    put("tools", request.tools.map(::serializeTool))
                    put("tool_choice", "auto")
                }
            },
        )

        val response = sendWithRetry(
            buildRequest = {
                requestBuilder(request.providerConfig, endpoint)
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream, application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build()
            },
            operationName = "发起聊天请求",
        )
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().use(::readBody)
            error("OpenAI-compatible 请求失败：HTTP ${response.statusCode()} ${errorBody.take(800)}")
        }

        val contentType = response.headers().firstValue("content-type").orElse("")
        return if (contentType.contains("text/event-stream")) {
            parseSseResponse(response.body(), listener)
        } else {
            parseJsonResponse(response.body(), listener)
        }
    }

    private fun parseSseResponse(
        inputStream: InputStream,
        listener: (ChatStreamDelta) -> Unit,
    ): ChatResult {
        val text = StringBuilder()
        val toolCalls = linkedMapOf<Int, ToolCallBuilder>()

        inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    if (Thread.currentThread().isInterrupted) return@forEach
                    if (!line.startsWith("data:")) return@forEach

                    val payload = line.removePrefix("data:").trim()
                    if (payload.isBlank() || payload == "[DONE]") return@forEach

                    val root = mapper.readTree(payload)
                    val choices = root.path("choices")
                    if (!choices.isArray || choices.isEmpty) return@forEach

                    val choice = choices[0]
                    val delta = choice.path("delta")
                    val content = delta.path("content").takeIf { !it.isMissingNode && !it.isNull }?.asText()
                    if (!content.isNullOrEmpty()) {
                        text.append(content)
                        val snapshot = text.toString()
                        listener(
                            ChatStreamDelta(
                                delta = content,
                                snapshot = snapshot,
                                outputParts = AssistantResponseSupport.partition(snapshot),
                            ),
                        )
                    }

                    val chunkToolCalls = delta.path("tool_calls")
                    if (chunkToolCalls.isArray) {
                        chunkToolCalls.forEach { item ->
                            val index = item.path("index").asInt()
                            val builder = toolCalls.getOrPut(index) { ToolCallBuilder() }
                            if (!item.path("id").isMissingNode && !item.path("id").isNull) {
                                builder.id = item.path("id").asText()
                            }
                            val function = item.path("function")
                            if (!function.path("name").isMissingNode && !function.path("name").isNull) {
                                builder.name.append(function.path("name").asText())
                            }
                            if (!function.path("arguments").isMissingNode && !function.path("arguments").isNull) {
                                builder.arguments.append(function.path("arguments").asText())
                            }
                        }
                    }
                }
            }
        }

        val finalText = text.toString()
        return ChatResult(
            text = finalText,
            outputParts = AssistantResponseSupport.partition(finalText),
            toolCalls = toolCalls.values.mapNotNull { it.build() },
        )
    }

    private fun parseJsonResponse(
        inputStream: InputStream,
        listener: (ChatStreamDelta) -> Unit,
    ): ChatResult {
        inputStream.use { stream ->
            val root = mapper.readTree(stream)
            val choices = root.path("choices")
            if (!choices.isArray || choices.isEmpty) {
                return ChatResult("", emptyList(), emptyList())
            }
            val message = choices[0].path("message")
            val content = message.path("content").takeIf { !it.isMissingNode && !it.isNull }?.asText().orEmpty()
            val outputParts = AssistantResponseSupport.partition(content)
            if (content.isNotEmpty()) {
                listener(ChatStreamDelta(content, content, outputParts))
            }
            return ChatResult(content, outputParts, parseToolCalls(message.path("tool_calls")))
        }
    }

    private fun sendWithRetry(
        buildRequest: () -> HttpRequest,
        operationName: String,
    ): HttpResponse<InputStream> {
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                val response = httpClient.send(
                    buildRequest(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
                if (!isRetryableStatus(response.statusCode()) || attempt == MAX_RETRIES) {
                    return response
                }
                response.body().close()
                sleepBeforeRetry(attempt, operationName, "HTTP ${response.statusCode()}")
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                error("$operationName 被中断。")
            } catch (exception: IOException) {
                lastException = exception
                if (attempt == MAX_RETRIES) break
                sleepBeforeRetry(attempt, operationName, exception.message ?: exception.javaClass.simpleName)
            }
        }
        error("$operationName 失败：${lastException?.message ?: "请求未成功完成"}")
    }

    private fun sleepBeforeRetry(attempt: Int, operationName: String, reason: String) {
        val delayMs = retryDelayMillis(attempt)
        runCatching { sleeper(delayMs) }.getOrElse { exception ->
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
                error("$operationName 被中断。")
            }
            throw exception
        }
    }

    private fun retryDelayMillis(attempt: Int): Long {
        val scaled = INITIAL_RETRY_DELAY_MS * (1L shl attempt.coerceAtMost(4))
        return min(scaled, 2_500L)
    }

    private fun isRetryableStatus(statusCode: Int): Boolean = statusCode in RETRYABLE_STATUS_CODES

    private fun readBody(stream: InputStream): String {
        return stream.readBytes().toString(StandardCharsets.UTF_8)
    }

    private fun serializeMessage(message: ConversationMessage): Map<String, Any?> {
        return when (message) {
            is ConversationMessage.System -> mapOf("role" to "system", "content" to message.content)
            is ConversationMessage.User -> mapOf("role" to "user", "content" to message.content)
            is ConversationMessage.Assistant -> buildMap {
                put("role", "assistant")
                put("content", message.content.ifBlank { null })
                if (message.toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        message.toolCalls.map { tool ->
                            mapOf(
                                "id" to tool.id,
                                "type" to "function",
                                "function" to mapOf(
                                    "name" to tool.name,
                                    "arguments" to tool.argumentsJson,
                                ),
                            )
                        },
                    )
                }
            }

            is ConversationMessage.Tool -> mapOf(
                "role" to "tool",
                "tool_call_id" to message.toolCallId,
                "content" to message.content,
            )
        }
    }

    private fun requestBuilder(config: ProviderConfig, endpoint: URI): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${config.apiKey}")
        config.headers.forEach { (key, value) ->
            builder.header(key, value)
        }
        return builder
    }

    private fun serializeTool(definition: ToolDefinition): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to definition.id,
                "description" to definition.description,
                "parameters" to definition.inputSchema,
            ),
        )
    }

    private fun parseToolCalls(node: JsonNode): List<ToolCall> {
        if (!node.isArray) return emptyList()
        return node.mapNotNull { tool ->
            val id = tool.path("id").asText("")
            val name = tool.path("function").path("name").asText("")
            val arguments = tool.path("function").path("arguments").asText("")
            if (id.isBlank() || name.isBlank()) null else ToolCall(id, name, arguments)
        }
    }

    private fun summarizeProbeFailure(statusCode: Int, responseBody: String): String {
        val compact = responseBody
            .replace(Regex("\\s+"), " ")
            .take(240)
            .ifBlank { "服务端拒绝了工具调用探测请求。" }
        return "HTTP $statusCode: $compact"
    }

    private class ToolCallBuilder {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()

        fun build(): ToolCall? {
            if (id.isBlank() || name.isEmpty()) return null
            return ToolCall(
                id = id,
                name = name.toString(),
                argumentsJson = arguments.toString().ifBlank { "{}" },
            )
        }
    }
}

package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class OpenAICompatibleClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) {
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
        val toolCalls: List<ToolCall>,
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
        val response = httpClient.send(
            requestBuilder(config, endpoint)
                .header("Accept", "application/json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().use { it.readBytes().toString(StandardCharsets.UTF_8) }
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

    fun streamChat(
        request: ChatRequest,
        listener: (String) -> Unit,
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

        val response = httpClient.send(
            requestBuilder(request.providerConfig, endpoint)
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().use { it.readBytes().toString(StandardCharsets.UTF_8) }
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
        listener: (String) -> Unit,
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
                        listener(content)
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

        return ChatResult(text.toString(), toolCalls.values.mapNotNull { it.build() })
    }

    private fun parseJsonResponse(
        inputStream: InputStream,
        listener: (String) -> Unit,
    ): ChatResult {
        inputStream.use { stream ->
            val root = mapper.readTree(stream)
            val choices = root.path("choices")
            if (!choices.isArray || choices.isEmpty) {
                return ChatResult("", emptyList())
            }
            val message = choices[0].path("message")
            val content = message.path("content").takeIf { !it.isMissingNode && !it.isNull }?.asText().orEmpty()
            if (content.isNotEmpty()) {
                listener(content)
            }
            return ChatResult(content, parseToolCalls(message.path("tool_calls")))
        }
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

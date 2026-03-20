package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.idopen.idopen.settings.ChatGptAuthSupport
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
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
        private const val RESPONSE_PEEK_BYTES = 1_024
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
        val sessionId: String? = null,
    )

    data class ChatResult(
        val text: String,
        val outputParts: List<AssistantOutputPart>,
        val toolCalls: List<ToolCall>,
        val responseItems: List<String> = emptyList(),
    )

    data class ChatStreamDelta(
        val delta: String,
        val snapshot: String,
        val outputParts: List<AssistantOutputPart>,
    )

    fun testConnection(config: ProviderConfig): ConnectionCheckResult {
        if (config.type == ProviderType.CHATGPT_AUTH) {
            ChatGptAuthSupport.ensureActiveSession(config)
            val models = ChatGptAuthSupport.supportedModels()
            return ConnectionCheckResult(
                message = "ChatGPT login is valid and Codex models are available.",
                models = models,
            )
        }

        val models = listModels(config)
        val message = if (models.isEmpty()) {
            "Connected, but no models were returned."
        } else {
            "Connected successfully. Found ${models.size} models."
        }
        return ConnectionCheckResult(message, models)
    }

    fun listModels(config: ProviderConfig): List<String> {
        if (config.type == ProviderType.CHATGPT_AUTH) {
            return ChatGptAuthSupport.supportedModels()
        }

        val endpoint = URI.create("${config.baseUrl}/models")
        val response = sendWithRetry(
            buildRequest = {
                requestBuilder(config, endpoint)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            },
            operationName = "Fetch model list",
        )
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().use(::readBody)
            error("Failed to fetch model list: HTTP ${response.statusCode()} ${errorBody.take(800)}")
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
        if (config.type == ProviderType.CHATGPT_AUTH) {
            return ToolCapability(
                supportsToolCalling = true,
                detail = "ChatGPT account provider uses the Codex responses endpoint.",
            )
        }

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
            operationName = "Detect tool capability",
        )

        return response.body().use { stream ->
            val responseBody = readBody(stream)
            when (response.statusCode()) {
                in 200..299 -> ToolCapability(
                    supportsToolCalling = true,
                    detail = "Model accepted the tool capability probe.",
                )

                400, 404, 405, 422 -> ToolCapability(
                    supportsToolCalling = false,
                    detail = summarizeProbeFailure(response.statusCode(), responseBody),
                )

                else -> error(
                    "Tool capability probe failed: HTTP ${response.statusCode()} ${responseBody.take(800)}",
                )
            }
        }
    }

    fun streamChat(
        request: ChatRequest,
        listener: (ChatStreamDelta) -> Unit = {},
    ): ChatResult {
        return when (request.providerConfig.type) {
            ProviderType.OPENAI_COMPATIBLE -> streamChatCompletions(request, listener)
            ProviderType.CHATGPT_AUTH -> streamResponsesChat(request, listener)
        }
    }

    private fun streamChatCompletions(
        request: ChatRequest,
        listener: (ChatStreamDelta) -> Unit,
    ): ChatResult {
        val endpoint = URI.create("${request.providerConfig.baseUrl}/chat/completions")
        val body = mapper.writeValueAsString(
            buildMap<String, Any> {
                put("model", request.providerConfig.model)
                put("stream", true)
                put("messages", request.messages.map(::serializeChatMessage))
                if (request.tools.isNotEmpty()) {
                    put("tools", request.tools.map(::serializeChatTool))
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
            operationName = "Start chat request",
        )
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().use(::readBody)
            error("OpenAI-compatible request failed: HTTP ${response.statusCode()} ${errorBody.take(800)}")
        }

        return parseStructuredResponse(
            inputStream = response.body(),
            contentType = response.headers().firstValue("content-type").orElse(""),
            listener = listener,
            sseParser = ::parseChatCompletionsSseResponse,
        )
    }

    private fun streamResponsesChat(
        request: ChatRequest,
        listener: (ChatStreamDelta) -> Unit,
    ): ChatResult {
        val endpoint = URI.create("${request.providerConfig.baseUrl}/responses")
        val instructions = extractResponsesInstructions(request.messages)
        val body = mapper.writeValueAsString(
            buildMap<String, Any> {
                put("model", request.providerConfig.model)
                put("stream", true)
                put("instructions", instructions)
                put("store", false)
                put("input", serializeResponsesInput(request.messages))
                if (request.tools.isNotEmpty()) {
                    put("tools", request.tools.map(::serializeResponsesTool))
                    put("tool_choice", "auto")
                }
            },
        )

        val response = sendWithRetry(
            buildRequest = {
                requestBuilder(request.providerConfig, endpoint, request.sessionId)
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream, application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build()
            },
            operationName = "Start ChatGPT Codex request",
        )
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().use(::readBody)
            error(
                formatProviderError(
                    prefix = "ChatGPT Codex request failed",
                    statusCode = response.statusCode(),
                    errorBody = errorBody,
                    headers = response.headers(),
                    providerConfig = request.providerConfig,
                    instructions = instructions,
                ),
            )
        }

        return parseStructuredResponse(
            inputStream = response.body(),
            contentType = response.headers().firstValue("content-type").orElse(""),
            listener = listener,
            sseParser = ::parseResponsesSseResponse,
        )
    }

    private fun parseStructuredResponse(
        inputStream: InputStream,
        contentType: String,
        listener: (ChatStreamDelta) -> Unit,
        sseParser: (InputStream, (ChatStreamDelta) -> Unit) -> ChatResult,
    ): ChatResult {
        val buffered = BufferedInputStream(inputStream)
        buffered.mark(RESPONSE_PEEK_BYTES)
        val previewBuffer = ByteArray(RESPONSE_PEEK_BYTES)
        val previewLength = buffered.read(previewBuffer)
        buffered.reset()

        val preview = if (previewLength > 0) {
            String(previewBuffer, 0, previewLength, StandardCharsets.UTF_8)
        } else {
            ""
        }

        return if (looksLikeSse(contentType, preview)) {
            sseParser(buffered, listener)
        } else {
            parseJsonResponse(buffered, listener)
        }
    }

    private fun looksLikeSse(contentType: String, preview: String): Boolean {
        if (contentType.contains("event-stream", ignoreCase = true)) {
            return true
        }

        val normalized = preview.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (normalized.startsWith("data:") || normalized.startsWith("event:")) {
            return true
        }

        return preview.lineSequence()
            .take(6)
            .map(String::trimStart)
            .any { it.startsWith("data:") || it.startsWith("event:") }
    }

    private fun parseChatCompletionsSseResponse(
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
                            builder.id = item.path("id").asText(builder.id)
                            val function = item.path("function")
                            builder.setName(function.path("name").asText(""))
                            builder.appendArguments(function.path("arguments").asText(""))
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
            responseItems = emptyList(),
        )
    }

    private fun parseResponsesSseResponse(
        inputStream: InputStream,
        listener: (ChatStreamDelta) -> Unit,
    ): ChatResult {
        val text = StringBuilder()
        val toolCalls = linkedMapOf<String, ToolCallBuilder>()
        val itemIdToCallId = mutableMapOf<String, String>()
        val outputIndexToCallId = mutableMapOf<Int, String>()
        val responseItems = mutableListOf<String>()

        inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    if (Thread.currentThread().isInterrupted) return@forEach
                    if (!line.startsWith("data:")) return@forEach

                    val payload = line.removePrefix("data:").trim()
                    if (payload.isBlank() || payload == "[DONE]") return@forEach

                    val root = mapper.readTree(payload)
                    when (root.path("type").asText("")) {
                        "response.output_text.delta" -> {
                            val delta = root.path("delta").asText("")
                            if (delta.isBlank()) return@forEach
                            text.append(delta)
                            val snapshot = text.toString()
                            listener(
                                ChatStreamDelta(
                                    delta = delta,
                                    snapshot = snapshot,
                                    outputParts = AssistantResponseSupport.partition(snapshot),
                                ),
                            )
                        }

                        "response.output_item.added" -> {
                            val item = root.path("item")
                            if (item.path("type").asText("") != "function_call") return@forEach
                            val callId = item.path("call_id").asText("").ifBlank { item.path("id").asText("") }
                            if (callId.isBlank()) return@forEach
                            val builder = toolCalls.getOrPut(callId) { ToolCallBuilder() }
                            builder.id = callId
                            builder.setName(item.path("name").asText(""))
                            val itemId = item.path("id").asText("")
                            if (itemId.isNotBlank()) {
                                itemIdToCallId[itemId] = callId
                            }
                            if (!root.path("output_index").isMissingNode && !root.path("output_index").isNull) {
                                outputIndexToCallId[root.path("output_index").asInt()] = callId
                            }
                        }

                        "response.function_call_arguments.delta" -> {
                            val callId = itemIdToCallId[root.path("item_id").asText("")]
                                ?: outputIndexToCallId[root.path("output_index").asInt()]
                                ?: return@forEach
                            toolCalls.getOrPut(callId) { ToolCallBuilder().also { it.id = callId } }
                                .appendArguments(root.path("delta").asText(""))
                        }

                        "response.output_item.done" -> {
                            val item = root.path("item")
                            if (item.isObject) {
                                responseItems += item.toString()
                            }
                            if (item.path("type").asText("") != "function_call") return@forEach
                            val callId = item.path("call_id").asText("").ifBlank { item.path("id").asText("") }
                            if (callId.isBlank()) return@forEach
                            val builder = toolCalls.getOrPut(callId) { ToolCallBuilder() }
                            builder.id = callId
                            builder.setName(item.path("name").asText(""))
                            if (builder.arguments.isEmpty()) {
                                builder.appendArguments(item.path("arguments").asText(""))
                            }
                        }

                        "response.completed", "response.done" -> {
                            if (responseItems.isNotEmpty()) return@forEach
                            val output = root.path("response").path("output")
                            if (output.isArray) {
                                output.forEach { item ->
                                    if (item.isObject) {
                                        responseItems += item.toString()
                                    }
                                }
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
            responseItems = responseItems,
        )
    }

    private fun parseJsonResponse(
        inputStream: InputStream,
        listener: (ChatStreamDelta) -> Unit,
    ): ChatResult {
        inputStream.use { stream ->
            val root = mapper.readTree(stream)
            return when {
                root.path("choices").isArray -> parseChatCompletionsJson(root, listener)
                root.path("output").isArray -> parseResponsesJson(root, listener)
                else -> ChatResult("", emptyList(), emptyList())
            }
        }
    }

    private fun parseChatCompletionsJson(
        root: JsonNode,
        listener: (ChatStreamDelta) -> Unit,
    ): ChatResult {
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

    private fun parseResponsesJson(
        root: JsonNode,
        listener: (ChatStreamDelta) -> Unit,
    ): ChatResult {
        val output = root.path("output")
        if (!output.isArray || output.isEmpty) {
            return ChatResult("", emptyList(), emptyList())
        }

        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()

        output.forEach outputLoop@{ item ->
            when (item.path("type").asText("")) {
                "message" -> {
                    item.path("content").forEach partLoop@{ part ->
                        if (part.path("type").asText("") != "output_text") return@partLoop
                        text.append(part.path("text").asText(""))
                    }
                }

                "function_call" -> {
                    val callId = item.path("call_id").asText("")
                    val name = item.path("name").asText("")
                    if (callId.isBlank() || name.isBlank()) return@outputLoop
                    toolCalls += ToolCall(
                        id = callId,
                        name = name,
                        argumentsJson = item.path("arguments").asText("{}").ifBlank { "{}" },
                    )
                }
            }
        }

        val finalText = text.toString()
        val outputParts = AssistantResponseSupport.partition(finalText)
        if (finalText.isNotEmpty()) {
            listener(ChatStreamDelta(finalText, finalText, outputParts))
        }
        return ChatResult(
            text = finalText,
            outputParts = outputParts,
            toolCalls = toolCalls,
            responseItems = output.map { it.toString() },
        )
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
                sleepBeforeRetry(attempt, operationName)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                error("$operationName was interrupted.")
            } catch (exception: IOException) {
                lastException = exception
                if (attempt == MAX_RETRIES) break
                sleepBeforeRetry(attempt, operationName)
            }
        }
        error("$operationName failed: ${lastException?.message ?: "request did not complete"}")
    }

    private fun sleepBeforeRetry(attempt: Int, operationName: String) {
        val delayMs = retryDelayMillis(attempt)
        runCatching { sleeper(delayMs) }.getOrElse { exception ->
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
                error("$operationName was interrupted.")
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

    private fun requestBuilder(config: ProviderConfig, endpoint: URI, sessionId: String? = null): HttpRequest.Builder {
        val resolvedConfig = resolveRequestConfig(config)
        val builder = HttpRequest.newBuilder()
            .uri(resolveEndpoint(resolvedConfig, endpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${resolvedConfig.apiKey}")

        if (resolvedConfig.type == ProviderType.CHATGPT_AUTH) {
            builder.header("originator", ChatGptAuthSupport.AUTH_ORIGINATOR)
            builder.header("User-Agent", "opencode/idopen-ideaPlugin")
            resolvedConfig.accountId?.takeIf { it.isNotBlank() }?.let {
                builder.header("ChatGPT-Account-Id", it)
            }
            sessionId?.takeIf { it.isNotBlank() }?.let {
                builder.header("session_id", it)
            }
        }

        resolvedConfig.headers.forEach { (key, value) ->
            builder.header(key, value)
        }
        return builder
    }

    private fun resolveRequestConfig(config: ProviderConfig): ProviderConfig {
        return if (config.type == ProviderType.CHATGPT_AUTH) {
            ChatGptAuthSupport.ensureActiveSession(config)
        } else {
            config
        }
    }

    private fun resolveEndpoint(config: ProviderConfig, endpoint: URI): URI {
        if (config.type != ProviderType.CHATGPT_AUTH) return endpoint
        val path = endpoint.path.orEmpty()
        return if (path.contains("/responses") || path.contains("/chat/completions")) {
            URI.create(ChatGptAuthSupport.CODEX_API_ENDPOINT)
        } else {
            endpoint
        }
    }

    private fun serializeChatMessage(message: ConversationMessage): Map<String, Any?> {
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

    private fun serializeResponsesInput(messages: List<ConversationMessage>): List<Map<String, Any?>> {
        val input = mutableListOf<Map<String, Any?>>()
        messages.forEach { message ->
            when (message) {
                is ConversationMessage.System -> {
                    return@forEach
                }

                is ConversationMessage.User -> {
                    input += mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf(
                                "type" to "input_text",
                                "text" to message.content,
                            ),
                        ),
                    )
                }

                is ConversationMessage.Assistant -> {
                    if (message.responseItems.isNotEmpty()) {
                        message.responseItems.forEach { raw ->
                            decodeStoredResponseItem(raw)?.let { input += it }
                        }
                    } else {
                        if (message.content.isNotBlank()) {
                            input += mapOf(
                                "role" to "assistant",
                                "content" to listOf(
                                    mapOf(
                                        "type" to "output_text",
                                        "text" to message.content,
                                    ),
                                ),
                            )
                        }
                        message.toolCalls.forEach { toolCall ->
                            input += mapOf(
                                "type" to "function_call",
                                "call_id" to toolCall.id,
                                "name" to toolCall.name,
                                "arguments" to toolCall.argumentsJson,
                            )
                        }
                    }
                }

                is ConversationMessage.Tool -> {
                    input += mapOf(
                        "type" to "function_call_output",
                        "call_id" to message.toolCallId,
                        "output" to message.content,
                    )
                }
            }
        }
        return input
    }

    private fun decodeStoredResponseItem(raw: String): Map<String, Any?>? {
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return jsonNodeToValue(node) as? Map<String, Any?>
    }

    private fun jsonNodeToValue(node: JsonNode): Any? {
        return when {
            node.isObject -> node.fields().asSequence().associate { it.key to jsonNodeToValue(it.value) }
            node.isArray -> node.map(::jsonNodeToValue)
            node.isIntegralNumber -> node.longValue()
            node.isFloatingPointNumber -> node.doubleValue()
            node.isBoolean -> node.booleanValue()
            node.isNull || node.isMissingNode -> null
            else -> node.asText()
        }
    }

    private fun serializeChatTool(definition: ToolDefinition): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to definition.id,
                "description" to definition.description,
                "parameters" to definition.inputSchema,
            ),
        )
    }

    private fun serializeResponsesTool(definition: ToolDefinition): Map<String, Any> {
        return mapOf(
            "type" to "function",
            "name" to definition.id,
            "description" to definition.description,
            "parameters" to definition.inputSchema,
            "strict" to false,
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
            .ifBlank { "Provider rejected the tool capability probe." }
        return "HTTP $statusCode: $compact"
    }

    private fun formatProviderError(
        prefix: String,
        statusCode: Int,
        errorBody: String,
        headers: HttpHeaders,
        providerConfig: ProviderConfig,
        instructions: String,
    ): String {
        val compactBody = errorBody
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(800)
        val requestId = headers.firstValue("x-request-id").orElse("").ifBlank {
            headers.firstValue("request-id").orElse("")
        }
        val requestIdSuffix = requestId.takeIf { it.isNotBlank() }?.let { " | request_id=$it" }.orEmpty()
        val modelHint = if (providerConfig.type == ProviderType.CHATGPT_AUTH) {
            " | current_model=${providerConfig.model}"
        } else {
            ""
        }
        val instructionsHint = if (providerConfig.type == ProviderType.CHATGPT_AUTH) {
            " | instructions_length=${instructions.length}"
        } else {
            ""
        }
        val detail = compactBody.ifBlank { "Forbidden or unavailable request." }
        return "$prefix: HTTP $statusCode$requestIdSuffix$modelHint$instructionsHint $detail"
    }

    private fun extractResponsesInstructions(messages: List<ConversationMessage>): String {
        val combined = messages
            .filterIsInstance<ConversationMessage.System>()
            .joinToString("\n\n") { it.content.trim() }
            .trim()
        return combined.ifBlank {
            "You are IDopen, a coding agent running inside IntelliJ IDEA."
        }
    }

    private class ToolCallBuilder {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()

        fun setName(value: String) {
            if (value.isBlank() || name.isNotEmpty()) return
            name.append(value)
        }

        fun appendArguments(value: String) {
            if (value.isBlank()) return
            arguments.append(value)
        }

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

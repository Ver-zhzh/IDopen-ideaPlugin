package com.idopen.idopen.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAICompatibleClientRetryTest {
    @Test
    fun `list models retries retryable status and succeeds`() {
        val httpClient = FakeHttpClient(
            responses = ArrayDeque(
                listOf(
                    fakeResponse(429, """{"error":"rate limit"}"""),
                    fakeResponse(200, """{"data":[{"id":"qwen-1"},{"id":"qwen-2"}]}"""),
                ),
            ),
        )
        val client = OpenAICompatibleClient(httpClient, sleeper = {})

        val models = client.listModels(providerConfig())

        assertEquals(listOf("qwen-1", "qwen-2"), models)
        assertEquals(2, httpClient.sendCount)
    }

    @Test
    fun `detect tool capability does not retry unsupported request`() {
        val httpClient = FakeHttpClient(
            responses = ArrayDeque(
                listOf(
                    fakeResponse(400, """{"error":"tools not supported"}"""),
                ),
            ),
        )
        val client = OpenAICompatibleClient(httpClient, sleeper = {})

        val capability = client.detectToolCapability(providerConfig())

        assertFalse(capability.supportsToolCalling)
        assertEquals(1, httpClient.sendCount)
    }

    @Test
    fun `stream chat retries transient server error`() {
        val httpClient = FakeHttpClient(
            responses = ArrayDeque(
                listOf(
                    fakeResponse(503, """{"error":"temporary unavailable"}"""),
                    fakeResponse(
                        200,
                        """{"choices":[{"message":{"content":"你好","tool_calls":[]}}]}""",
                        contentType = "application/json",
                    ),
                ),
            ),
        )
        val client = OpenAICompatibleClient(httpClient, sleeper = {})
        var streamed = ""

        val result = client.streamChat(
            OpenAICompatibleClient.ChatRequest(
                providerConfig = providerConfig(),
                messages = listOf(ConversationMessage.User("你好", "round-1")),
                tools = emptyList(),
                sessionId = "session-1",
            ),
        ) { delta ->
            streamed = delta.snapshot
        }

        assertEquals("你好", result.text)
        assertEquals("你好", streamed)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(1, result.outputParts.size)
        assertTrue(result.outputParts.first() is AssistantOutputPart.Text)
        assertEquals(2, httpClient.sendCount)
    }

    @Test
    fun `chatgpt auth uses codex responses endpoint and response parsing`() {
        val httpClient = FakeHttpClient(
            responses = ArrayDeque(
                listOf(
                    fakeResponse(
                        200,
                        """
                        {
                          "output": [
                            {
                              "type": "message",
                              "role": "assistant",
                              "id": "msg_1",
                              "content": [
                                {
                                  "type": "output_text",
                                  "text": "来自 GPT auth 的回复",
                                  "annotations": []
                                }
                              ]
                            },
                            {
                              "type": "function_call",
                              "id": "fc_1",
                              "call_id": "call_1",
                              "name": "read_file",
                              "arguments": "{\"path\":\"README.md\"}"
                            }
                          ]
                        }
                        """.trimIndent(),
                        contentType = "application/json",
                    ),
                ),
            ),
        )
        val client = OpenAICompatibleClient(httpClient, sleeper = {})

        val result = client.streamChat(
            OpenAICompatibleClient.ChatRequest(
                providerConfig = ProviderConfig(
                    type = ProviderType.CHATGPT_AUTH,
                    baseUrl = "https://chatgpt.com/backend-api/codex",
                    apiKey = "access-token",
                    model = "gpt-5.4",
                    headers = mapOf("X-Test" to "1"),
                    refreshToken = "refresh-token",
                    accessTokenExpiresAt = Long.MAX_VALUE,
                    accountId = "org_123",
                ),
                messages = listOf(
                    ConversationMessage.System("You are helpful.", "round-1"),
                    ConversationMessage.User("Read the file", "round-1"),
                ),
                tools = listOf(
                    ToolDefinition(
                        id = "read_file",
                        description = "Read a file",
                        inputSchema = mapOf("type" to "object"),
                    ),
                ),
                sessionId = "session-123",
            ),
        )

        val request = httpClient.requests.single()
        assertEquals("https://chatgpt.com/backend-api/codex/responses", request.uri().toString())
        assertEquals("Bearer access-token", request.headers().firstValue("Authorization").orElse(""))
        assertEquals("opencode", request.headers().firstValue("originator").orElse(""))
        assertEquals("org_123", request.headers().firstValue("ChatGPT-Account-Id").orElse(""))
        assertEquals("session-123", request.headers().firstValue("session_id").orElse(""))
        assertEquals("1", request.headers().firstValue("X-Test").orElse(""))
        assertEquals("来自 GPT auth 的回复", result.text)
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls.first().id)
        assertEquals(2, result.responseItems.size)
        val requestBody = readBodyPublisher(request)
        assertTrue(requestBody.contains("\"input\""))
        assertTrue(requestBody.contains("\"tools\""))
        assertTrue(requestBody.contains("\"instructions\":\"You are helpful.\""))
        assertTrue(requestBody.contains("\"store\":false"))
        assertTrue(requestBody.contains("\"strict\":false"))
        assertFalse(requestBody.contains("\"role\":\"system\""))
    }

    @Test
    fun `chatgpt auth error surfaces instructions context and request id`() {
        val httpClient = FakeHttpClient(
            responses = ArrayDeque(
                listOf(
                    fakeResponse(
                        400,
                        """{"detail":"Instructions are required"}""",
                        contentType = "application/json",
                        headers = mapOf("x-request-id" to listOf("req_400")),
                    ),
                ),
            ),
        )
        val client = OpenAICompatibleClient(httpClient, sleeper = {})

        val error = runCatching {
            client.streamChat(
                OpenAICompatibleClient.ChatRequest(
                    providerConfig = ProviderConfig(
                        type = ProviderType.CHATGPT_AUTH,
                        baseUrl = "https://chatgpt.com/backend-api/codex",
                        apiKey = "access-token",
                        model = "gpt-5.4",
                        headers = emptyMap(),
                        refreshToken = "refresh-token",
                        accessTokenExpiresAt = Long.MAX_VALUE,
                    ),
                    messages = listOf(ConversationMessage.System("System instruction", "round-1")),
                    tools = emptyList(),
                    sessionId = "session-2",
                ),
            )
        }.exceptionOrNull()

        assertTrue(error != null)
        val message = error.message.orEmpty()
        assertTrue(message.contains("HTTP 400"))
        assertTrue(message.contains("request_id=req_400"))
        assertTrue(message.contains("instructions_length="))
    }

    @Test
    fun `chatgpt auth parses sse body even when content type is not event stream`() {
        val httpClient = FakeHttpClient(
            responses = ArrayDeque(
                listOf(
                    fakeResponse(
                        200,
                        """
                        event: response.created
                        data: {"type":"response.created","response":{"id":"resp_1"}}

                        event: response.output_text.delta
                        data: {"type":"response.output_text.delta","delta":"Hello"}

                        event: response.output_text.delta
                        data: {"type":"response.output_text.delta","delta":" world"}

                        data: [DONE]
                        """.trimIndent(),
                        contentType = "application/json",
                    ),
                ),
            ),
        )
        val client = OpenAICompatibleClient(httpClient, sleeper = {})

        val result = client.streamChat(
            OpenAICompatibleClient.ChatRequest(
                providerConfig = ProviderConfig(
                    type = ProviderType.CHATGPT_AUTH,
                    baseUrl = "https://chatgpt.com/backend-api/codex",
                    apiKey = "access-token",
                    model = "gpt-5.4",
                    headers = emptyMap(),
                    refreshToken = "refresh-token",
                    accessTokenExpiresAt = Long.MAX_VALUE,
                ),
                messages = listOf(
                    ConversationMessage.System("You are helpful.", "round-1"),
                    ConversationMessage.User("Say hello", "round-1"),
                ),
                tools = emptyList(),
                sessionId = "session-3",
            ),
        )

        assertEquals("Hello world", result.text)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `chatgpt auth captures reasoning items from sse tool stream`() {
        val httpClient = FakeHttpClient(
            responses = ArrayDeque(
                listOf(
                    fakeResponse(
                        200,
                        """
                        event: response.output_item.done
                        data: {"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1","summary":[]}}

                        event: response.output_item.added
                        data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search_text","arguments":""}}

                        event: response.function_call_arguments.delta
                        data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"{\"query\":\"IDopen\"}"}

                        event: response.output_item.done
                        data: {"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search_text","arguments":"{\"query\":\"IDopen\"}"}}

                        data: [DONE]
                        """.trimIndent(),
                        contentType = "text/event-stream",
                    ),
                ),
            ),
        )
        val client = OpenAICompatibleClient(httpClient, sleeper = {})

        val result = client.streamChat(
            OpenAICompatibleClient.ChatRequest(
                providerConfig = ProviderConfig(
                    type = ProviderType.CHATGPT_AUTH,
                    baseUrl = "https://chatgpt.com/backend-api/codex",
                    apiKey = "access-token",
                    model = "gpt-5.4",
                    headers = emptyMap(),
                    refreshToken = "refresh-token",
                    accessTokenExpiresAt = Long.MAX_VALUE,
                ),
                messages = listOf(
                    ConversationMessage.System("You are helpful.", "round-1"),
                    ConversationMessage.User("Inspect the project", "round-1"),
                ),
                tools = listOf(
                    ToolDefinition(
                        id = "search_text",
                        description = "Search text",
                        inputSchema = mapOf("type" to "object"),
                    ),
                ),
                sessionId = "session-sse",
            ),
        )

        assertEquals(1, result.toolCalls.size)
        assertEquals(2, result.responseItems.size)
        assertTrue(result.responseItems.first().contains("\"type\":\"reasoning\""))
        assertTrue(result.responseItems.last().contains("\"call_id\":\"call_1\""))
    }

    @Test
    fun `chatgpt auth replays stored response items before tool outputs`() {
        val httpClient = FakeHttpClient(
            responses = ArrayDeque(
                listOf(
                    fakeResponse(
                        200,
                        """{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}]}""",
                        contentType = "application/json",
                    ),
                ),
            ),
        )
        val client = OpenAICompatibleClient(httpClient, sleeper = {})

        client.streamChat(
            OpenAICompatibleClient.ChatRequest(
                providerConfig = ProviderConfig(
                    type = ProviderType.CHATGPT_AUTH,
                    baseUrl = "https://chatgpt.com/backend-api/codex",
                    apiKey = "access-token",
                    model = "gpt-5.4",
                    headers = emptyMap(),
                    refreshToken = "refresh-token",
                    accessTokenExpiresAt = Long.MAX_VALUE,
                ),
                messages = listOf(
                    ConversationMessage.System("You are helpful.", "round-1"),
                    ConversationMessage.User("Inspect the project", "round-1"),
                    ConversationMessage.Assistant(
                        content = "",
                        roundId = "round-1",
                        responseItems = listOf(
                            """{"type":"reasoning","id":"rs_1","summary":[]}""",
                            """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search_text","arguments":"{\"query\":\"IDopen\"}"}""",
                        ),
                    ),
                    ConversationMessage.Tool(
                        toolCallId = "call_1",
                        toolName = "search_text",
                        content = """{"matches":["README.md"]}""",
                        roundId = "round-1",
                    ),
                ),
                tools = listOf(
                    ToolDefinition(
                        id = "search_text",
                        description = "Search text",
                        inputSchema = mapOf("type" to "object"),
                    ),
                ),
                sessionId = "session-replay",
            ),
        )

        val requestBody = readBodyPublisher(httpClient.requests.single())
        val reasoningIndex = requestBody.indexOf("\"type\":\"reasoning\"")
        val toolCallIndex = requestBody.indexOf("\"type\":\"function_call\"")
        val toolOutputIndex = requestBody.indexOf("\"type\":\"function_call_output\"")
        assertTrue(reasoningIndex >= 0)
        assertTrue(toolCallIndex > reasoningIndex)
        assertTrue(toolOutputIndex > toolCallIndex)
        assertTrue(requestBody.contains("\"call_id\":\"call_1\""))
    }

    @Test
    fun `chatgpt auth model list is static and does not hit network`() {
        val httpClient = FakeHttpClient(responses = ArrayDeque())
        val client = OpenAICompatibleClient(httpClient, sleeper = {})

        val models = client.listModels(
            ProviderConfig(
                type = ProviderType.CHATGPT_AUTH,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                apiKey = "access-token",
                model = "gpt-5.4",
                headers = emptyMap(),
                refreshToken = "refresh-token",
                accessTokenExpiresAt = Long.MAX_VALUE,
            ),
        )

        assertTrue(models.contains("gpt-5.4"))
        assertEquals(0, httpClient.sendCount)
    }

    private fun providerConfig(): ProviderConfig {
        return ProviderConfig(
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://example.com/v1",
            apiKey = "test-key",
            model = "qwen",
            headers = emptyMap(),
        )
    }

    private fun fakeResponse(
        statusCode: Int,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, List<String>> = emptyMap(),
    ): HttpResponse<InputStream> {
        return object : HttpResponse<InputStream> {
            override fun statusCode(): Int = statusCode
            override fun request(): HttpRequest = HttpRequest.newBuilder(URI.create("https://example.com")).build()
            override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
            override fun headers(): HttpHeaders = HttpHeaders.of(
                headers + ("content-type" to listOf(contentType)),
            ) { _, _ -> true }
            override fun body(): InputStream = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))
            override fun sslSession(): Optional<SSLSession> = Optional.empty()
            override fun uri(): URI = URI.create("https://example.com")
            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }
    }

    private fun readBodyPublisher(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElseThrow()
        val output = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        publisher.subscribe(
            object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) {
                    subscription.request(Long.MAX_VALUE)
                }

                override fun onNext(item: ByteBuffer) {
                    val bytes = ByteArray(item.remaining())
                    item.get(bytes)
                    output.write(bytes)
                }

                override fun onError(throwable: Throwable) {
                    latch.countDown()
                    throw RuntimeException(throwable)
                }

                override fun onComplete() {
                    latch.countDown()
                }
            },
        )
        latch.await()
        return output.toString(StandardCharsets.UTF_8)
    }

    private class FakeHttpClient(
        private val responses: ArrayDeque<HttpResponse<InputStream>>,
    ) : HttpClient() {
        var sendCount: Int = 0
            private set
        val requests = mutableListOf<HttpRequest>()

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            sendCount += 1
            requests += request
            @Suppress("UNCHECKED_CAST")
            return responses.removeFirst() as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            throw UnsupportedOperationException()
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> {
            throw UnsupportedOperationException()
        }

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(30))
        override fun followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.of(
            ProxySelector.of(InetSocketAddress.createUnresolved("localhost", 8080)),
        )
        override fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun executor(): Optional<Executor> = Optional.empty()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
    }
}

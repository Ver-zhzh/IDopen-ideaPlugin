package com.idopen.idopen.agent

import java.io.ByteArrayInputStream
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
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
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
            ),
        ) { delta ->
            streamed += delta
        }

        assertEquals("你好", result.text)
        assertEquals("你好", streamed)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(2, httpClient.sendCount)
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
    ): HttpResponse<InputStream> {
        return object : HttpResponse<InputStream> {
            override fun statusCode(): Int = statusCode
            override fun request(): HttpRequest = HttpRequest.newBuilder(URI.create("https://example.com")).build()
            override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
            override fun headers(): HttpHeaders = HttpHeaders.of(mapOf("content-type" to listOf(contentType))) { _, _ -> true }
            override fun body(): InputStream = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))
            override fun sslSession(): Optional<SSLSession> = Optional.empty()
            override fun uri(): URI = URI.create("https://example.com")
            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }
    }

    private class FakeHttpClient(
        private val responses: ArrayDeque<HttpResponse<InputStream>>,
    ) : HttpClient() {
        var sendCount: Int = 0
            private set

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            sendCount += 1
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

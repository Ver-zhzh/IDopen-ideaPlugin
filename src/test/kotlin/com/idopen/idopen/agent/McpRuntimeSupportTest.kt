package com.idopen.idopen.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRuntimeSupportTest {
    private val mapper = ObjectMapper()

    @Test
    fun `list tools returns remote tool descriptions for stdio servers`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-list")
        try {
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "playwright": {
                      "command": "npx",
                      "args": ["@playwright/mcp@latest"]
                    }
                  }
                }
                """.trimIndent(),
            )

            val runtime = McpRuntimeSupport { _, _ ->
                FakeMcpSession(
                    tools = listOf(
                        McpRemoteTool("browser_navigate", "Open a page", mapper.createObjectNode().put("type", "object")),
                        McpRemoteTool("browser_snapshot", "Take an accessibility snapshot", mapper.createObjectNode().put("type", "object")),
                    ),
                )
            }

            val result = runtime.listTools(projectRoot, "playwright")

            assertTrue(result.success)
            assertTrue(result.content.contains("browser_navigate"))
            assertTrue(result.content.contains("browser_snapshot"))
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `call tool returns server error as failed execution result`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-call")
        try {
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "sqlite": {
                      "command": "uvx",
                      "args": ["mcp-server-sqlite"]
                    }
                  }
                }
                """.trimIndent(),
            )

            val runtime = McpRuntimeSupport { _, _ ->
                FakeMcpSession(
                    tools = listOf(
                        McpRemoteTool("query", "Run a SQL query", mapper.createObjectNode().put("type", "object")),
                    ),
                    callResult = McpCallResult(
                        content = "Query failed: missing database path",
                        isError = true,
                    ),
                )
            }

            val result = runtime.callTool(
                projectRoot = projectRoot,
                serverName = "sqlite",
                toolName = "query",
                arguments = mapper.createObjectNode().put("sql", "select 1"),
            )

            assertFalse(result.success)
            assertTrue(result.content.contains("missing database path"))
            assertTrue(result.recoveryHint?.contains("mcp_list_tools") == true)
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `list resources returns remote resources for stdio servers`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-resources")
        try {
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "docs": {
                      "command": "node",
                      "args": ["server.js"]
                    }
                  }
                }
                """.trimIndent(),
            )

            val runtime = McpRuntimeSupport { _, _ ->
                FakeMcpSession(
                    resources = listOf(
                        McpRemoteResource("resource://docs/readme", "README", "Project overview", "text/markdown", 128),
                    ),
                )
            }

            val result = runtime.listResources(projectRoot, "docs")

            assertTrue(result.success)
            assertTrue(result.content.contains("README"))
            assertTrue(result.content.contains("resource://docs/readme"))
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `read resource returns text and blob summaries`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-read-resource")
        try {
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "docs": {
                      "command": "node",
                      "args": ["server.js"]
                    }
                  }
                }
                """.trimIndent(),
            )

            val runtime = McpRuntimeSupport { _, _ ->
                FakeMcpSession(
                    resources = listOf(
                        McpRemoteResource("resource://docs/readme", "README", null, "text/markdown", null),
                    ),
                    resourceContents = listOf(
                        McpResourceContent("resource://docs/readme", "text/markdown", "# Hello", null),
                        McpResourceContent("resource://docs/logo", "image/png", null, "YWJj"),
                    ),
                )
            }

            val result = runtime.readResource(projectRoot, "docs", "resource://docs/readme")

            assertTrue(result.success)
            assertTrue(result.content.contains("# Hello"))
            assertTrue(result.content.contains("blob:image/png"))
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `list prompts and get prompt format returned messages`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-prompts")
        try {
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "prompts": {
                      "command": "node",
                      "args": ["server.js"]
                    }
                  }
                }
                """.trimIndent(),
            )

            val capturedArguments = mutableListOf<ObjectNode>()
            val runtime = McpRuntimeSupport { _, _ ->
                FakeMcpSession(
                    prompts = listOf(
                        McpRemotePrompt(
                            name = "code_review",
                            description = "Review code quality",
                            arguments = listOf(McpPromptArgument("code", "The code to review", required = true)),
                        ),
                    ),
                    promptResult = McpPromptResult(
                        description = "Code review prompt",
                        messages = listOf(
                            McpPromptMessage(
                                role = "user",
                                content = mapper.createObjectNode()
                                    .put("type", "text")
                                    .put("text", "Please review this code."),
                            ),
                        ),
                    ),
                    onGetPrompt = { _, arguments ->
                        capturedArguments += arguments.deepCopy()
                    },
                )
            }

            val listResult = runtime.listPrompts(projectRoot, "prompts")
            val getResult = runtime.getPrompt(
                projectRoot,
                "prompts",
                "code_review",
                mapper.createObjectNode().put("code", "fun main() = Unit"),
            )

            assertTrue(listResult.success)
            assertTrue(listResult.content.contains("code_review"))
            assertTrue(getResult.success)
            assertTrue(getResult.content.contains("Please review this code."))
            assertEquals("fun main() = Unit", capturedArguments.single().path("code").asText())
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `http transport supports json responses and session reuse`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-http")
        val initializeCalls = AtomicInteger(0)
        val seenSessionHeader = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/mcp") { exchange ->
                val payload = readJson(exchange)
                when (payload.path("method").asText()) {
                    "initialize" -> {
                        initializeCalls.incrementAndGet()
                        writeJsonResponse(
                            exchange,
                            """
                            {
                              "jsonrpc": "2.0",
                              "id": "${payload.path("id").asText()}",
                              "result": {
                                "protocolVersion": "2025-03-26",
                                "capabilities": {}
                              }
                            }
                            """.trimIndent(),
                            headers = mapOf("Mcp-Session-Id" to "session-1"),
                        )
                    }
                    "notifications/initialized" -> writeNoContent(exchange)
                    "tools/list" -> {
                        seenSessionHeader.set(exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                        writeJsonResponse(
                            exchange,
                            """
                            {
                              "jsonrpc": "2.0",
                              "id": "${payload.path("id").asText()}",
                              "result": {
                                "tools": [
                                  { "name": "fetch_docs", "description": "Fetch docs", "inputSchema": { "type": "object" } }
                                ]
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                    else -> error("Unexpected method: ${payload.path("method").asText()}")
                }
            }
            server.start()
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "docs": {
                      "type": "http",
                      "url": "http://127.0.0.1:${server.address.port}/mcp"
                    }
                  }
                }
                """.trimIndent(),
            )

            val runtime = McpRuntimeSupport()
            val result = runtime.listTools(projectRoot, "docs")

            assertTrue(result.success)
            assertTrue(result.content.contains("fetch_docs"))
            assertEquals("session-1", seenSessionHeader.get())
            assertEquals(1, initializeCalls.get())
        } finally {
            server.stop(0)
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `http transport parses sse responses`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-http-sse")
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/mcp") { exchange ->
                val payload = readJson(exchange)
                when (payload.path("method").asText()) {
                    "initialize" -> {
                        writeJsonResponse(
                            exchange,
                            """
                            {
                              "jsonrpc": "2.0",
                              "id": "${payload.path("id").asText()}",
                              "result": {
                                "protocolVersion": "2025-03-26",
                                "capabilities": {}
                              }
                            }
                            """.trimIndent(),
                            headers = mapOf("Mcp-Session-Id" to "session-sse"),
                        )
                    }
                    "notifications/initialized" -> writeNoContent(exchange)
                    "prompts/list" -> {
                        writeSseResponse(
                            exchange,
                            """
                            event: message
                            data: {"jsonrpc":"2.0","id":"${payload.path("id").asText()}","result":{"prompts":[{"name":"review","description":"Review prompt"}]}}

                            """.trimIndent(),
                        )
                    }
                    else -> error("Unexpected method: ${payload.path("method").asText()}")
                }
            }
            server.start()
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "prompts": {
                      "type": "http",
                      "url": "http://127.0.0.1:${server.address.port}/mcp"
                    }
                  }
                }
                """.trimIndent(),
            )

            val runtime = McpRuntimeSupport()
            val result = runtime.listPrompts(projectRoot, "prompts")

            assertTrue(result.success)
            assertTrue(result.content.contains("review"))
        } finally {
            server.stop(0)
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `sse transport remains inspect only`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-sse")
        try {
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "legacy": {
                      "type": "sse",
                      "url": "https://docs.example.com/sse"
                    }
                  }
                }
                """.trimIndent(),
            )

            val runtime = McpRuntimeSupport()
            val result = runtime.listTools(projectRoot, "legacy")

            assertFalse(result.success)
            assertTrue(result.content.contains("streamable HTTP"))
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `call tool forwards arguments to session`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-args")
        try {
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "playwright": {
                      "command": "npx",
                      "args": ["@playwright/mcp@latest"]
                    }
                  }
                }
                """.trimIndent(),
            )

            val captured = mutableListOf<Pair<String, ObjectNode>>()
            val runtime = McpRuntimeSupport { _, _ ->
                FakeMcpSession(
                    tools = listOf(
                        McpRemoteTool("browser_navigate", "Navigate to a page", mapper.createObjectNode().put("type", "object")),
                    ),
                    onCall = { name, arguments ->
                        captured += name to arguments.deepCopy()
                    },
                    callResult = McpCallResult(
                        content = "Navigation complete",
                        isError = false,
                    ),
                )
            }

            val arguments = mapper.createObjectNode().apply {
                put("url", "https://example.com")
                put("waitUntil", "load")
            }
            val result = runtime.callTool(projectRoot, "playwright", "browser_navigate", arguments)

            assertTrue(result.success)
            assertEquals("browser_navigate", captured.single().first)
            assertEquals("https://example.com", captured.single().second.path("url").asText())
        } finally {
            deleteTree(projectRoot)
        }
    }

    @Test
    fun `list catalogs reuse cached session and results`() {
        val projectRoot = Files.createTempDirectory("idopen-mcp-runtime-cache")
        try {
            writeConfig(
                projectRoot,
                """
                {
                  "mcpServers": {
                    "playwright": {
                      "command": "npx",
                      "args": ["@playwright/mcp@latest"]
                    }
                  }
                }
                """.trimIndent(),
            )

            val sessionCreations = AtomicInteger(0)
            val toolListCalls = AtomicInteger(0)
            val resourceListCalls = AtomicInteger(0)
            val promptListCalls = AtomicInteger(0)
            val runtime = McpRuntimeSupport { _, _ ->
                sessionCreations.incrementAndGet()
                FakeMcpSession(
                    tools = listOf(McpRemoteTool("browser_snapshot", "snapshot", mapper.createObjectNode().put("type", "object"))),
                    resources = listOf(McpRemoteResource("resource://docs/readme", "README", null, "text/markdown", null)),
                    prompts = listOf(McpRemotePrompt("review", null, emptyList())),
                    onList = { toolListCalls.incrementAndGet() },
                    onListResources = { resourceListCalls.incrementAndGet() },
                    onListPrompts = { promptListCalls.incrementAndGet() },
                )
            }

            runtime.listTools(projectRoot, "playwright")
            runtime.listTools(projectRoot, "playwright")
            runtime.listResources(projectRoot, "playwright")
            runtime.listResources(projectRoot, "playwright")
            runtime.listPrompts(projectRoot, "playwright")
            runtime.listPrompts(projectRoot, "playwright")

            assertEquals(1, sessionCreations.get())
            assertEquals(1, toolListCalls.get())
            assertEquals(1, resourceListCalls.get())
            assertEquals(1, promptListCalls.get())
        } finally {
            deleteTree(projectRoot)
        }
    }

    private fun writeConfig(projectRoot: java.nio.file.Path, content: String) {
        Files.writeString(projectRoot.resolve(".mcp.json"), content)
    }

    private fun readJson(exchange: HttpExchange): JsonNode {
        return exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            mapper.readTree(reader.readText())
        }
    }

    private fun writeJsonResponse(exchange: HttpExchange, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun writeSseResponse(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun writeNoContent(exchange: HttpExchange) {
        exchange.sendResponseHeaders(202, -1)
        exchange.close()
    }

    private fun deleteTree(root: java.nio.file.Path) {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private class FakeMcpSession(
        private val tools: List<McpRemoteTool> = emptyList(),
        private val resources: List<McpRemoteResource> = emptyList(),
        private val resourceContents: List<McpResourceContent> = emptyList(),
        private val prompts: List<McpRemotePrompt> = emptyList(),
        private val callResult: McpCallResult = McpCallResult("ok", isError = false),
        private val promptResult: McpPromptResult = McpPromptResult(description = null, messages = emptyList()),
        private val onList: () -> Unit = {},
        private val onListResources: () -> Unit = {},
        private val onListPrompts: () -> Unit = {},
        private val onCall: (String, ObjectNode) -> Unit = { _, _ -> },
        private val onGetPrompt: (String, ObjectNode) -> Unit = { _, _ -> },
    ) : McpSession {
        override fun listTools(): List<McpRemoteTool> {
            onList()
            return tools
        }

        override fun callTool(name: String, arguments: JsonNode): McpCallResult {
            onCall(name, arguments as ObjectNode)
            return callResult
        }

        override fun listResources(): List<McpRemoteResource> {
            onListResources()
            return resources
        }

        override fun readResource(uri: String): List<McpResourceContent> = resourceContents

        override fun listResourceTemplates(): List<McpRemoteResourceTemplate> = emptyList()

        override fun listPrompts(): List<McpRemotePrompt> {
            onListPrompts()
            return prompts
        }

        override fun getPrompt(name: String, arguments: JsonNode): McpPromptResult {
            onGetPrompt(name, arguments as? ObjectNode ?: ObjectMapper().createObjectNode())
            return promptResult
        }

        override fun close() = Unit
    }
}

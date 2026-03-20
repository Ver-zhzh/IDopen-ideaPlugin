package com.idopen.idopen.settings

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.idopen.idopen.agent.ProviderConfig
import com.idopen.idopen.agent.ProviderType
import com.intellij.ide.BrowserUtil
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ChatGptAuthSupport {
    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val ISSUER = "https://auth.openai.com"
    internal const val AUTH_LOOPBACK_HOST = "localhost"
    internal const val AUTH_ORIGINATOR = "opencode"
    private const val OAUTH_PORT = 1455
    private const val CALLBACK_PATH = "/auth/callback"
    private const val CALLBACK_TIMEOUT_MINUTES = 5L
    private const val TOKEN_REFRESH_MARGIN_MS = 5_000L
    private const val DEVICE_AUTH_POLLING_SAFETY_MARGIN_MS = 3_000L
    private const val IDOPEN_USER_AGENT = "opencode/idopen-ideaPlugin"

    const val CODEX_API_BASE = "https://chatgpt.com/backend-api/codex"
    const val CODEX_API_ENDPOINT = "$CODEX_API_BASE/responses"

    private val secureRandom = SecureRandom()
    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    data class OAuthTokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val idToken: String? = null,
    )

    data class ChatGptAuthStatus(
        val loggedIn: Boolean,
        val email: String?,
        val accountId: String?,
        val expiresAt: Long?,
    ) {
        fun summary(language: DisplayLanguage = DisplayLanguage.EN_US): String {
            if (!loggedIn) return if (language == DisplayLanguage.ZH_CN) "未登录" else "Not logged in"
            val identity = email?.takeIf { it.isNotBlank() }
                ?: accountId?.takeIf { it.isNotBlank() }
                ?: if (language == DisplayLanguage.ZH_CN) "ChatGPT 账号" else "ChatGPT account"
            return if (language == DisplayLanguage.ZH_CN) {
                "已登录：$identity"
            } else {
                "Logged in as $identity"
            }
        }
    }

    private data class PkceCodes(
        val verifier: String,
        val challenge: String,
    )

    data class DeviceAuthorizationSession(
        val deviceAuthId: String,
        val userCode: String,
        val intervalMs: Long,
        val verificationUrl: String = "$ISSUER/codex/device",
    )

    fun supportedModels(): List<String> {
        return listOf(
            "gpt-5.4",
            "gpt-5.3-codex",
            "gpt-5.2-codex",
            "gpt-5.2",
            "gpt-5.1-codex-max",
            "gpt-5.1-codex-mini",
        )
    }

    fun defaultModel(): String = supportedModels().first()

    fun getStatus(settings: IDopenSettingsState = IDopenSettingsState.getInstance()): ChatGptAuthStatus {
        val loggedIn = settings.chatGptAccessToken.isNotBlank() || settings.chatGptRefreshToken.isNotBlank()
        return ChatGptAuthStatus(
            loggedIn = loggedIn,
            email = settings.chatGptEmail.ifBlank { null },
            accountId = settings.chatGptAccountId.ifBlank { null },
            expiresAt = settings.chatGptAccessTokenExpiresAt.takeIf { it > 0L },
        )
    }

    fun logout(settings: IDopenSettingsState = IDopenSettingsState.getInstance()) {
        settings.chatGptAccessToken = ""
        settings.chatGptRefreshToken = ""
        settings.chatGptAccessTokenExpiresAt = 0L
        settings.chatGptAccountId = ""
        settings.chatGptEmail = ""
    }

    fun loginWithBrowser(settings: IDopenSettingsState = IDopenSettingsState.getInstance()): ChatGptAuthStatus {
        val pkce = generatePkce()
        val state = generateState()
        val callback = CompletableFuture<String>()
        val redirectUri = "http://$AUTH_LOOPBACK_HOST:$OAUTH_PORT$CALLBACK_PATH"
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress(AUTH_LOOPBACK_HOST, OAUTH_PORT), 0)
        server.executor = executor
        server.createContext(CALLBACK_PATH) { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            val error = params["error_description"] ?: params["error"]
            val response = when {
                error != null -> {
                    callback.completeExceptionally(IllegalStateException(error))
                    htmlResponse("Authorization failed", error)
                }

                params["code"].isNullOrBlank() -> {
                    val message = "Missing authorization code"
                    callback.completeExceptionally(IllegalStateException(message))
                    htmlResponse("Authorization failed", message)
                }

                params["state"] != state -> {
                    val message = "State validation failed"
                    callback.completeExceptionally(IllegalStateException(message))
                    htmlResponse("Authorization failed", message)
                }

                else -> {
                    callback.complete(params.getValue("code"))
                    htmlResponse("Authorization successful", "You can close this window and return to IDopen.")
                }
            }
            exchange.sendHtml(response)
        }
        server.start()

        try {
            openBrowser(buildAuthorizeUrl(redirectUri, pkce.challenge, state))
            val code = callback.get(CALLBACK_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            val tokens = exchangeCodeForTokens(code, redirectUri, pkce)
            return persistTokens(settings, tokens)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    fun startDeviceAuthorization(): DeviceAuthorizationSession {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$ISSUER/api/accounts/deviceauth/usercode"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("User-Agent", IDOPEN_USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString("""{"client_id":"$CLIENT_ID"}""", StandardCharsets.UTF_8))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            error("Failed to start ChatGPT device authorization: HTTP ${response.statusCode()} ${response.body().take(400)}")
        }
        val root = mapper.readTree(response.body())
        val deviceAuthId = root.path("device_auth_id").asText("").ifBlank {
            error("Device authorization response is missing device_auth_id.")
        }
        val userCode = root.path("user_code").asText("").ifBlank {
            error("Device authorization response is missing user_code.")
        }
        val intervalSeconds = root.path("interval").asText("").toLongOrNull()
            ?: root.path("interval").asLong(5L)
        return DeviceAuthorizationSession(
            deviceAuthId = deviceAuthId,
            userCode = userCode,
            intervalMs = maxOf(intervalSeconds, 1L) * 1_000L,
        )
    }

    fun loginWithDeviceCode(
        session: DeviceAuthorizationSession,
        settings: IDopenSettingsState = IDopenSettingsState.getInstance(),
    ): ChatGptAuthStatus {
        while (true) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$ISSUER/api/accounts/deviceauth/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("User-Agent", IDOPEN_USER_AGENT)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {
                          "device_auth_id":"${session.deviceAuthId}",
                          "user_code":"${session.userCode}"
                        }
                        """.trimIndent(),
                        StandardCharsets.UTF_8,
                    ),
                )
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() == 200) {
                val root = mapper.readTree(response.body())
                val authorizationCode = root.path("authorization_code").asText("").ifBlank {
                    error("Device authorization completed without authorization_code.")
                }
                val codeVerifier = root.path("code_verifier").asText("").ifBlank {
                    error("Device authorization completed without code_verifier.")
                }
                val tokens = exchangeTokens(
                    body = buildFormBody(
                        "grant_type" to "authorization_code",
                        "code" to authorizationCode,
                        "redirect_uri" to "$ISSUER/deviceauth/callback",
                        "client_id" to CLIENT_ID,
                        "code_verifier" to codeVerifier,
                    ),
                    errorPrefix = "Device token exchange failed",
                )
                return persistTokens(settings, tokens)
            }

            if (response.statusCode() != 403 && response.statusCode() != 404) {
                error("Device authorization failed: HTTP ${response.statusCode()} ${response.body().take(400)}")
            }

            Thread.sleep(session.intervalMs + DEVICE_AUTH_POLLING_SAFETY_MARGIN_MS)
        }
    }

    fun openVerificationUrl(url: String) {
        openBrowser(url)
    }

    @Synchronized
    fun ensureActiveSession(
        config: ProviderConfig,
        settings: IDopenSettingsState = IDopenSettingsState.getInstance(),
    ): ProviderConfig {
        if (config.type != ProviderType.CHATGPT_AUTH) return config
        val expiresAt = config.accessTokenExpiresAt ?: 0L
        val accessToken = config.apiKey.trim().ifBlank { settings.chatGptAccessToken.trim() }
        if (accessToken.isNotBlank() && expiresAt > System.currentTimeMillis() + TOKEN_REFRESH_MARGIN_MS) {
            return config.copy(
                apiKey = accessToken,
                refreshToken = config.refreshToken ?: settings.chatGptRefreshToken.ifBlank { null },
                accessTokenExpiresAt = expiresAt,
                accountId = config.accountId ?: settings.chatGptAccountId.ifBlank { null },
            )
        }

        val refreshToken = config.refreshToken?.trim().orEmpty()
            .ifBlank { settings.chatGptRefreshToken.trim() }
        if (refreshToken.isBlank()) {
            error("ChatGPT login is missing a refresh token. Please sign in again.")
        }

        val refreshed = refreshAccessToken(refreshToken)
        return persistTokens(
            settings = settings,
            tokens = refreshed,
            fallbackRefreshToken = refreshToken,
            fallbackAccountId = config.accountId ?: settings.chatGptAccountId.ifBlank { null },
            fallbackEmail = settings.chatGptEmail.ifBlank { null },
        ).let {
            config.copy(
                apiKey = settings.chatGptAccessToken,
                refreshToken = settings.chatGptRefreshToken.ifBlank { null },
                accessTokenExpiresAt = settings.chatGptAccessTokenExpiresAt,
                accountId = settings.chatGptAccountId.ifBlank { null },
            )
        }
    }

    fun parseJwtClaims(token: String): JsonNode? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(parts[1])
            mapper.readTree(decoded)
        }.getOrNull()
    }

    fun extractAccountIdFromClaims(claims: JsonNode): String? {
        val direct = claims.path("chatgpt_account_id").asText("").ifBlank { null }
        if (direct != null) return direct

        val nested = claims.path("https://api.openai.com/auth").path("chatgpt_account_id").asText("").ifBlank { null }
        if (nested != null) return nested

        val organizations = claims.path("organizations")
        if (!organizations.isArray || organizations.isEmpty) return null
        return organizations.first().path("id").asText("").ifBlank { null }
    }

    fun extractEmailFromClaims(claims: JsonNode): String? {
        return claims.path("email").asText("").ifBlank { null }
    }

    internal fun buildAuthorizeUrl(
        redirectUri: String,
        challenge: String,
        state: String,
    ): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to redirectUri,
            "scope" to "openid profile email offline_access",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
            "originator" to AUTH_ORIGINATOR,
        )
        return buildString {
            append(ISSUER)
            append("/oauth/authorize?")
            append(
                params.entries.joinToString("&") { entry ->
                    encode(entry.key) + "=" + encode(entry.value)
                },
            )
        }
    }

    private fun exchangeCodeForTokens(
        code: String,
        redirectUri: String,
        pkce: PkceCodes,
    ): OAuthTokens {
        val body = buildFormBody(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to CLIENT_ID,
            "code_verifier" to pkce.verifier,
        )
        return exchangeTokens(body, "Token exchange failed")
    }

    private fun refreshAccessToken(refreshToken: String): OAuthTokens {
        val body = buildFormBody(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to CLIENT_ID,
        )
        return exchangeTokens(body, "Token refresh failed")
    }

    private fun exchangeTokens(body: String, errorPrefix: String): OAuthTokens {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$ISSUER/oauth/token"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            error("$errorPrefix: HTTP ${response.statusCode()} ${response.body().take(400)}")
        }
        val root = mapper.readTree(response.body())
        val accessToken = root.path("access_token").asText("")
        if (accessToken.isBlank()) {
            error("$errorPrefix: access token missing")
        }
        val refreshToken = root.path("refresh_token").asText("")
        val expiresIn = root.path("expires_in").asLong(3600L)
        return OAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
            idToken = root.path("id_token").asText("").ifBlank { null },
        )
    }

    private fun persistTokens(
        settings: IDopenSettingsState,
        tokens: OAuthTokens,
        fallbackRefreshToken: String? = null,
        fallbackAccountId: String? = null,
        fallbackEmail: String? = null,
    ): ChatGptAuthStatus {
        val claims = tokens.idToken?.let(::parseJwtClaims) ?: parseJwtClaims(tokens.accessToken)
        val accountId = claims?.let(::extractAccountIdFromClaims) ?: fallbackAccountId
        val email = claims?.let(::extractEmailFromClaims) ?: fallbackEmail
        settings.chatGptAccessToken = tokens.accessToken
        settings.chatGptRefreshToken = tokens.refreshToken.ifBlank { fallbackRefreshToken.orEmpty() }
        settings.chatGptAccessTokenExpiresAt = tokens.expiresAt
        settings.chatGptAccountId = accountId.orEmpty()
        settings.chatGptEmail = email.orEmpty()
        if (settings.defaultModel.isBlank() || settings.defaultModel !in supportedModels()) {
            settings.defaultModel = defaultModel()
        }
        return getStatus(settings)
    }

    private fun generatePkce(): PkceCodes {
        val verifier = randomString(43)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.UTF_8))
        return PkceCodes(verifier, base64UrlEncode(digest))
    }

    private fun generateState(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    private fun randomString(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val buffer = StringBuilder(length)
        repeat(length) {
            buffer.append(alphabet[secureRandom.nextInt(alphabet.length)])
        }
        return buffer.toString()
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun buildFormBody(vararg params: Pair<String, String>): String {
        return params.joinToString("&") { encode(it.first) + "=" + encode(it.second) }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
                key to value
            }
            .toMap()
    }

    private fun openBrowser(url: String) {
        runCatching { BrowserUtil.browse(url) }
            .recoverCatching {
                if (!Desktop.isDesktopSupported()) throw it
                Desktop.getDesktop().browse(URI.create(url))
            }
            .getOrElse { error("Unable to open the browser for ChatGPT login: ${it.message}") }
    }

    private fun htmlResponse(title: String, message: String): ByteArray {
        return """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <title>IDopen ChatGPT Login</title>
            <style>
              body {
                font-family: system-ui, sans-serif;
                margin: 0;
                min-height: 100vh;
                display: flex;
                align-items: center;
                justify-content: center;
                background: #111827;
                color: #f9fafb;
              }
              .card {
                max-width: 520px;
                padding: 32px;
                border-radius: 16px;
                background: #1f2937;
                box-shadow: 0 20px 40px rgba(0, 0, 0, 0.35);
              }
              h1 {
                margin: 0 0 12px;
                font-size: 24px;
              }
              p {
                margin: 0;
                color: #d1d5db;
                line-height: 1.5;
              }
            </style>
          </head>
          <body>
            <div class="card">
              <h1>${escapeHtml(title)}</h1>
              <p>${escapeHtml(message)}</p>
            </div>
            <script>
              setTimeout(() => window.close(), 2000);
            </script>
          </body>
        </html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun com.sun.net.httpserver.HttpExchange.sendHtml(response: ByteArray) {
        responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        sendResponseHeaders(200, response.size.toLong())
        responseBody.use { stream ->
            stream.write(response)
            stream.flush()
        }
    }
}

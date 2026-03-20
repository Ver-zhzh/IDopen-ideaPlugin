package com.idopen.idopen.settings

import java.nio.charset.StandardCharsets
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatGptAuthSupportTest {
    @Test
    fun `parse jwt claims returns null for invalid token`() {
        assertNull(ChatGptAuthSupport.parseJwtClaims("not-a-jwt"))
    }

    @Test
    fun `extract account id prefers direct claim`() {
        val claims = assertNotNull(
            ChatGptAuthSupport.parseJwtClaims(
                fakeJwt("""{"chatgpt_account_id":"org_direct","email":"user@example.com"}"""),
            ),
        )

        assertEquals("org_direct", ChatGptAuthSupport.extractAccountIdFromClaims(claims))
        assertEquals("user@example.com", ChatGptAuthSupport.extractEmailFromClaims(claims))
    }

    @Test
    fun `extract account id falls back to namespaced claim and organizations`() {
        val namespaced = assertNotNull(
            ChatGptAuthSupport.parseJwtClaims(
                fakeJwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"org_nested"}}"""),
            ),
        )
        val organizations = assertNotNull(
            ChatGptAuthSupport.parseJwtClaims(
                fakeJwt("""{"organizations":[{"id":"org_first"}]}"""),
            ),
        )

        assertEquals("org_nested", ChatGptAuthSupport.extractAccountIdFromClaims(namespaced))
        assertEquals("org_first", ChatGptAuthSupport.extractAccountIdFromClaims(organizations))
    }

    @Test
    fun `browser authorize url keeps opencode-compatible originator and localhost redirect`() {
        val redirectUri = "http://${ChatGptAuthSupport.AUTH_LOOPBACK_HOST}:1455/auth/callback"
        val url = ChatGptAuthSupport.buildAuthorizeUrl(
            redirectUri = redirectUri,
            challenge = "test-challenge",
            state = "test-state",
        )

        assertTrue(url.startsWith("https://auth.openai.com/oauth/authorize?"))
        val query = url.substringAfter('?').split('&').associate { part ->
            val pieces = part.split('=', limit = 2)
            URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }
        assertEquals(redirectUri, query["redirect_uri"])
        assertEquals(ChatGptAuthSupport.AUTH_ORIGINATOR, query["originator"])
        assertEquals("test-challenge", query["code_challenge"])
    }

    private fun fakeJwt(payloadJson: String): String {
        return listOf(
            base64Url("""{"alg":"none","typ":"JWT"}"""),
            base64Url(payloadJson),
            "signature",
        ).joinToString(".")
    }

    private fun base64Url(value: String): String {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}

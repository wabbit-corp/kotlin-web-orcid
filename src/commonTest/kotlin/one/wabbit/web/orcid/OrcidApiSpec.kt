@file:OptIn(ExperimentalTime::class)

package one.wabbit.web.orcid

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.time.ExperimentalTime

class OrcidApiSpec {
    @Test
    fun `authorization url encodes scopes and extra parameters`() {
        val api = KtorOrcidApi(testClient { error("request should not be made") })

        val url =
            api.buildAuthorizationUrl(
                OrcidApi.AuthorizationRequest(
                    clientId = "client-id",
                    redirectUri = "https://example.com/callback",
                    scopes = listOf(OrcidApi.Scope.OPENID, OrcidApi.Scope.AUTHENTICATE),
                    state = "opaque-state",
                    additionalParameters = mapOf("lang" to "en"),
                ),
            )

        assertTrue(url.startsWith("https://orcid.org/oauth/authorize?"))
        assertTrue(url.contains("client_id=client-id"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("scope=%2Fopenid+%2Fauthenticate"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"))
        assertTrue(url.contains("state=opaque-state"))
        assertTrue(url.contains("lang=en"))
    }

    @Test
    fun `client credentials token posts form and decodes response`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = testClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/oauth/token", request.url.encodedPath)
            respondJson(
                """
                {
                  "access_token": "token-123",
                  "token_type": "bearer",
                  "refresh_token": "refresh-456",
                  "expires_in": 631138518,
                  "scope": "/read-public"
                }
                """.trimIndent(),
            )
        }

        val api = KtorOrcidApi(client)
        val token =
            api.fetchClientCredentialsToken(
                OrcidApi.ClientCredentials(
                    clientId = "client-id",
                    clientSecret = "client-secret",
                ),
            )

        assertEquals("token-123", token.accessToken)
        assertEquals("bearer", token.tokenType)
        assertEquals("/read-public", token.scope)

        val body = seenRequest?.bodyText() ?: error("request body missing")
        assertTrue(body.contains("client_id=client-id"))
        assertTrue(body.contains("client_secret=client-secret"))
        assertTrue(body.contains("grant_type=client_credentials"))
        assertTrue(body.contains("scope=%2Fread-public"))
    }

    @Test
    fun `search omits authorization for public API and decodes results`() = runTest {
        val client = testClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v3.0/search", request.url.encodedPath)
            assertEquals("Ada Lovelace", request.url.parameters["q"])
            assertEquals("10", request.url.parameters["start"])
            assertEquals("5", request.url.parameters["rows"])
            assertEquals("lucene", request.url.parameters["defType"])
            assertNull(request.headers[HttpHeaders.Authorization])
            respondJson(
                """
                {
                  "num-found": 1,
                  "result": [
                    {
                      "orcid-identifier": {
                        "uri": "https://orcid.org/0000-0001-2345-6789",
                        "path": "0000-0001-2345-6789",
                        "host": "orcid.org"
                      }
                    }
                  ]
                }
                """.trimIndent(),
                contentType = "application/vnd.orcid+json",
            )
        }

        val api = KtorOrcidApi(client)
        val response =
            api.search(
                OrcidApi.SearchRequest(
                    query = "Ada Lovelace",
                    start = 10,
                    rows = 5,
                    queryParser = "lucene",
                ),
            )

        assertEquals(1, response.numFound)
        assertEquals(1, response.results.size)
        assertEquals("0000-0001-2345-6789", response.results.single().orcidIdentifier.path)
    }

    @Test
    fun `read path includes bearer token and decodes json object`() = runTest {
        val client = testClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v3.0/0000-0002-1825-0097/works/12345", request.url.encodedPath)
            assertEquals("Bearer secret-token", request.headers[HttpHeaders.Authorization])
            respondJson(
                """
                {
                  "path": "0000-0002-1825-0097",
                  "last-modified-date": {"value": 1494016313820}
                }
                """.trimIndent(),
                contentType = "application/vnd.orcid+json",
            )
        }

        val api =
            KtorOrcidApi(
                client,
                config = OrcidApi.Config(apiTier = OrcidApi.ApiTier.MEMBER),
            )

        val json = api.readPath("0000-0002-1825-0097", "works/12345", accessToken = "secret-token")
        assertEquals("0000-0002-1825-0097", json["path"]?.toString()?.trim('"'))
    }

    private fun testClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(HttpTimeout)
        }

    private fun MockRequestHandleScope.respondJson(body: String, contentType: String = ContentType.Application.Json.toString()) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, contentType),
        )
}

private suspend fun HttpRequestData.bodyBytes(): ByteArray =
    when (val value = body) {
        is OutgoingContent.ByteArrayContent -> value.bytes()
        is OutgoingContent.ReadChannelContent -> readAll(value.readFrom())
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel()
            value.writeTo(channel)
            channel.close()
            readAll(channel)
        }
        else -> value.toString().encodeToByteArray()
    }

private suspend fun HttpRequestData.bodyText(): String =
    when (val value = body) {
        is OutgoingContent.ByteArrayContent -> value.bytes().decodeToString()
        else -> bodyBytes().decodeToString()
    }

private suspend fun readAll(channel: ByteReadChannel): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(4096)
    var total = 0

    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) {
            break
        }
        if (read == 0) {
            continue
        }
        chunks += buffer.copyOf(read)
        total += read
    }

    val out = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(out, destinationOffset = offset)
        offset += chunk.size
    }
    return out
}

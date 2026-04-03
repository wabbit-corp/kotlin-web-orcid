@file:OptIn(ExperimentalTime::class)

package one.wabbit.web.orcid

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.accept
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.RetryAction
import one.wabbit.web.common.RetryPolicy
import one.wabbit.web.common.Schedule
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyEtiquette
import one.wabbit.web.common.applyTimeouts
import one.wabbit.web.common.runWithRetry
import one.wabbit.web.common.safeBodyPrefix
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

sealed class OrcidError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(val url: String, val status: Int, val bodySample: String?) :
        OrcidError(
            buildString {
                append("HTTP ")
                append(status)
                append(" from ")
                append(url)
                if (!bodySample.isNullOrBlank()) {
                    append(", body sample: ")
                    append(bodySample.take(256))
                }
            },
        )

    class Network(val url: String, cause: Throwable) :
        OrcidError(
            "Network failure talking to $url: ${cause::class.simpleName ?: "Throwable"}: ${cause.message}",
            cause,
        )

    class Parse(val url: String, cause: Throwable, val bodySample: String) :
        OrcidError("Failed to parse ORCID response from $url: ${cause.message}", cause)
}

typealias OrcidRetryPolicy = RetryPolicy<OrcidError>

interface OrcidApi {
    data class Config(
        val environment: Environment = Environment.PRODUCTION,
        val apiTier: ApiTier = ApiTier.PUBLIC,
        val apiVersion: String = "v3.0",
        val etiquette: Etiquette = Etiquette("one.wabbit.web.orcid/1.0"),
        val timeouts: Timeouts = Timeouts(),
        val retryPolicy: OrcidRetryPolicy? = defaultOrcidRetryPolicy(),
    ) {
        init {
            require(apiVersion.matches(Regex("""v\d+\.\d+"""))) {
                "apiVersion must look like v3.0"
            }
        }

        val oauthBaseUrl: String
            get() = environment.oauthBaseUrl

        val resourceBaseUrl: String
            get() =
                when (apiTier) {
                    ApiTier.PUBLIC -> environment.publicApiBaseUrl
                    ApiTier.MEMBER -> environment.memberApiBaseUrl
                }

        fun authorizationEndpoint(): String = "${oauthBaseUrl.trimEnd('/')}/oauth/authorize"

        fun tokenEndpoint(): String = "${oauthBaseUrl.trimEnd('/')}/oauth/token"
    }

    enum class Environment(
        val oauthBaseUrl: String,
        val publicApiBaseUrl: String,
        val memberApiBaseUrl: String,
    ) {
        PRODUCTION(
            oauthBaseUrl = "https://orcid.org",
            publicApiBaseUrl = "https://pub.orcid.org",
            memberApiBaseUrl = "https://api.orcid.org",
        ),
        SANDBOX(
            oauthBaseUrl = "https://sandbox.orcid.org",
            publicApiBaseUrl = "https://pub.sandbox.orcid.org",
            memberApiBaseUrl = "https://api.sandbox.orcid.org",
        ),
    }

    enum class ApiTier {
        PUBLIC,
        MEMBER,
    }

    data class ClientCredentials(
        val clientId: String,
        val clientSecret: String,
    ) {
        init {
            require(clientId.isNotBlank()) { "clientId must not be blank" }
            require(clientSecret.isNotBlank()) { "clientSecret must not be blank" }
        }
    }

    data class Scope(
        val value: String,
    ) {
        init {
            require(value.startsWith("/")) { "scope must start with '/'" }
        }

        override fun toString(): String = value

        companion object {
            val READ_PUBLIC = Scope("/read-public")
            val READ_LIMITED = Scope("/read-limited")
            val AUTHENTICATE = Scope("/authenticate")
            val OPENID = Scope("/openid")

            fun custom(value: String): Scope = Scope(value)
        }
    }

    data class AuthorizationRequest(
        val clientId: String,
        val redirectUri: String,
        val scopes: List<Scope> = listOf(Scope.READ_PUBLIC),
        val state: String? = null,
        val additionalParameters: Map<String, String> = emptyMap(),
    ) {
        init {
            require(clientId.isNotBlank()) { "clientId must not be blank" }
            require(redirectUri.isNotBlank()) { "redirectUri must not be blank" }
            require(scopes.isNotEmpty()) { "at least one scope is required" }
            require(additionalParameters.keys.none { it in RESERVED_AUTHORIZATION_PARAMETERS }) {
                "additionalParameters must not override reserved OAuth parameters"
            }
        }
    }

    @Serializable
    data class AccessToken(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        val scope: String? = null,
        val name: String? = null,
        val orcid: String? = null,
    )

    data class SearchRequest(
        val query: String,
        val start: Int? = null,
        val rows: Int? = null,
        val queryParser: String? = null,
    ) {
        init {
            require(query.isNotBlank()) { "query must not be blank" }
            require(start == null || start >= 0) { "start must be non-negative" }
            require(rows == null || rows > 0) { "rows must be positive" }
            require(queryParser == null || queryParser.isNotBlank()) {
                "queryParser must not be blank"
            }
        }
    }

    @Serializable
    data class OrcidIdentifier(
        val uri: String? = null,
        val path: String,
        val host: String? = null,
    )

    @Serializable
    data class SearchResultItem(
        @SerialName("orcid-identifier") val orcidIdentifier: OrcidIdentifier,
    )

    @Serializable
    data class SearchResponse(
        @SerialName("num-found") val numFound: Int,
        @SerialName("result") val results: List<SearchResultItem> = emptyList(),
    )

    @Serializable
    data class ExpandedSearchResult(
        @SerialName("orcid-id") val orcidId: String,
        @SerialName("given-names") val givenNames: String? = null,
        @SerialName("family-names") val familyNames: String? = null,
        @SerialName("credit-name") val creditName: String? = null,
        @SerialName("other-name") val otherNames: List<String> = emptyList(),
        @SerialName("email") val emails: List<String> = emptyList(),
        @SerialName("institution-name") val institutionNames: List<String> = emptyList(),
    )

    @Serializable
    data class ExpandedSearchResponse(
        @SerialName("num-found") val numFound: Int,
        @SerialName("expanded-result") val results: List<ExpandedSearchResult> = emptyList(),
    )

    enum class RecordEndpoint(val path: String) {
        RECORD("record"),
        PERSON("person"),
        ACTIVITIES("activities"),
        BIOGRAPHY("biography"),
        EDUCATIONS("educations"),
        EMPLOYMENTS("employments"),
        FUNDINGS("fundings"),
        INVITED_POSITIONS("invited-positions"),
        KEYWORDS("keywords"),
        MEMBERSHIPS("memberships"),
        OTHER_NAMES("other-names"),
        PEER_REVIEWS("peer-reviews"),
        QUALIFICATIONS("qualifications"),
        RESEARCH_RESOURCES("research-resources"),
        RESEARCHER_URLS("researcher-urls"),
        SERVICES("services"),
        WORKS("works"),
    }

    val config: Config

    fun buildAuthorizationUrl(request: AuthorizationRequest): String

    suspend fun fetchClientCredentialsToken(
        credentials: ClientCredentials,
        scope: Scope = Scope.READ_PUBLIC,
    ): AccessToken

    suspend fun exchangeAuthorizationCode(
        credentials: ClientCredentials,
        authorizationCode: String,
        redirectUri: String,
    ): AccessToken

    suspend fun search(
        request: SearchRequest,
        accessToken: String? = null,
    ): SearchResponse

    suspend fun expandedSearch(
        request: SearchRequest,
        accessToken: String? = null,
    ): ExpandedSearchResponse

    suspend fun readPath(
        orcidId: String,
        path: String = RecordEndpoint.RECORD.path,
        accessToken: String? = null,
    ): JsonObject

    suspend fun readRecord(orcidId: String, accessToken: String? = null): JsonObject =
        readPath(orcidId, RecordEndpoint.RECORD.path, accessToken)

    suspend fun readPerson(orcidId: String, accessToken: String? = null): JsonObject =
        readPath(orcidId, RecordEndpoint.PERSON.path, accessToken)

    companion object {
        fun defaultOrcidRetryPolicy(): OrcidRetryPolicy {
            val schedule =
                Schedule.retries(
                    maxRetries = 4,
                    baseDelay = 200.milliseconds,
                    maxDelay = 5.seconds,
                    jitterFactor = 0.2,
                )

            return RetryPolicy(schedule) { error, _ ->
                when (error) {
                    is OrcidError.Network -> RetryAction.Retry()
                    is OrcidError.Http ->
                        when (error.status) {
                            429 -> RetryAction.Retry()
                            in 500..599 -> RetryAction.Retry()
                            else -> RetryAction.Stop
                        }
                    is OrcidError.Parse -> RetryAction.Stop
                }
            }
        }
    }
}

class KtorOrcidApi(
    private val httpClient: HttpClient,
    override val config: OrcidApi.Config = OrcidApi.Config(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : OrcidApi {
    init {
        check(runCatching { httpClient.pluginOrNull(HttpTimeout) }.getOrNull() != null) {
            "HttpTimeout plugin must be installed on the provided HttpClient for per-request timeouts to work."
        }
    }

    override fun buildAuthorizationUrl(request: OrcidApi.AuthorizationRequest): String {
        val builder = URLBuilder(config.authorizationEndpoint())
        builder.parameters.append("client_id", request.clientId)
        builder.parameters.append("response_type", "code")
        builder.parameters.append("scope", request.scopes.joinToString(" ") { it.value })
        builder.parameters.append("redirect_uri", request.redirectUri)
        request.state?.let { builder.parameters.append("state", it) }
        request.additionalParameters.forEach { (name, value) ->
            builder.parameters.append(name, value)
        }
        return builder.buildString()
    }

    override suspend fun fetchClientCredentialsToken(
        credentials: OrcidApi.ClientCredentials,
        scope: OrcidApi.Scope,
    ): OrcidApi.AccessToken =
        withRetry {
            val url = config.tokenEndpoint()
            val body =
                postForm(url) {
                    append("client_id", credentials.clientId)
                    append("client_secret", credentials.clientSecret)
                    append("grant_type", "client_credentials")
                    append("scope", scope.value)
                }
            decode(url, body)
        }

    override suspend fun exchangeAuthorizationCode(
        credentials: OrcidApi.ClientCredentials,
        authorizationCode: String,
        redirectUri: String,
    ): OrcidApi.AccessToken {
        require(authorizationCode.isNotBlank()) { "authorizationCode must not be blank" }
        require(redirectUri.isNotBlank()) { "redirectUri must not be blank" }

        return withRetry {
            val url = config.tokenEndpoint()
            val body =
                postForm(url) {
                    append("client_id", credentials.clientId)
                    append("client_secret", credentials.clientSecret)
                    append("grant_type", "authorization_code")
                    append("code", authorizationCode)
                    append("redirect_uri", redirectUri)
                }
            decode(url, body)
        }
    }

    override suspend fun search(
        request: OrcidApi.SearchRequest,
        accessToken: String?,
    ): OrcidApi.SearchResponse =
        withRetry {
            ensureAccessTokenIfRequired(accessToken)
            val url = buildResourceUrl("search")
            val body =
                getText(url, accessToken) {
                    accept(ORCID_JSON)
                    parameter("q", request.query)
                    request.start?.let { parameter("start", it) }
                    request.rows?.let { parameter("rows", it) }
                    request.queryParser?.let { parameter("defType", it) }
                }
            decode(url, body)
        }

    override suspend fun expandedSearch(
        request: OrcidApi.SearchRequest,
        accessToken: String?,
    ): OrcidApi.ExpandedSearchResponse =
        withRetry {
            ensureAccessTokenIfRequired(accessToken)
            val url = buildResourceUrl("expanded-search")
            val body =
                getText(url, accessToken) {
                    accept(ORCID_JSON)
                    parameter("q", request.query)
                    request.start?.let { parameter("start", it) }
                    request.rows?.let { parameter("rows", it) }
                    request.queryParser?.let { parameter("defType", it) }
                }
            decode(url, body)
        }

    override suspend fun readPath(
        orcidId: String,
        path: String,
        accessToken: String?,
    ): JsonObject =
        withRetry {
            ensureAccessTokenIfRequired(accessToken)
            val canonicalOrcidId = normalizeOrcidId(orcidId)
            val url = buildResourceUrl(canonicalOrcidId, path)
            val body =
                getText(url, accessToken) {
                    accept(ORCID_JSON)
                }
            decode(url, body)
        }

    private suspend fun postForm(
        url: String,
        buildParameters: io.ktor.http.ParametersBuilder.() -> Unit,
    ): String {
        val response =
            try {
                httpClient.post(url) {
                    expectSuccess = false
                    applyEtiquette(config.etiquette)
                    applyTimeouts(config.timeouts)
                    accept(ContentType.Application.Json)
                    setBody(FormDataContent(io.ktor.http.Parameters.build(buildParameters)))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw OrcidError.Network(url, t)
            }

        return responseTextOrThrow(url, response)
    }

    private suspend fun getText(
        url: String,
        accessToken: String?,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): String {
        val response =
            try {
                httpClient.get(url) {
                    expectSuccess = false
                    applyEtiquette(config.etiquette)
                    applyTimeouts(config.timeouts)
                    accessToken?.takeIf { it.isNotBlank() }?.let {
                        header(HttpHeaders.Authorization, "Bearer $it")
                    }
                    block()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw OrcidError.Network(url, t)
            }

        return responseTextOrThrow(url, response)
    }

    private suspend fun responseTextOrThrow(
        url: String,
        response: io.ktor.client.statement.HttpResponse,
    ): String =
        if (response.status.isSuccess()) {
            response.bodyAsText()
        } else {
            val sample =
                try {
                    response.safeBodyPrefix(2048)
                } catch (t: Throwable) {
                    "<failed to read body prefix: ${t::class.simpleName}: ${t.message}>"
                }
            throw OrcidError.Http(url, response.status.value, sample)
        }

    private fun ensureAccessTokenIfRequired(accessToken: String?) {
        require(config.apiTier != OrcidApi.ApiTier.MEMBER || !accessToken.isNullOrBlank()) {
            "Member API requests require an access token."
        }
    }

    private inline fun <reified T> decode(url: String, body: String): T =
        try {
            json.decodeFromString(body)
        } catch (t: Throwable) {
            throw OrcidError.Parse(url, t, body.take(512))
        }

    private suspend fun <T> withRetry(block: suspend () -> T): T =
        if (config.retryPolicy == null) {
            block()
        } else {
            runWithRetry(config.retryPolicy) { block() }
        }

    private fun buildResourceUrl(vararg rawSegments: String): String {
        val segments =
            buildList {
                add(config.apiVersion.trim('/'))
                rawSegments.forEach { segment ->
                    addAll(normalizePathSegments(segment))
                }
            }

        return buildString {
            append(config.resourceBaseUrl.trimEnd('/'))
            segments.forEach { segment ->
                append('/')
                append(segment.encodeURLPathPart())
            }
        }
    }
}

private fun normalizeOrcidId(orcidId: String): String {
    val trimmed = orcidId.trim()
    require(ORCID_ID_REGEX.matches(trimmed)) {
        "orcidId must match the canonical ORCID iD format 0000-0000-0000-0000"
    }
    return trimmed
}

private fun normalizePathSegments(path: String): List<String> {
    val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    require(segments.isNotEmpty()) { "path must not be blank" }
    require(segments.none { it == "." || it == ".." }) { "path must not contain relative segments" }
    return segments
}

private val ORCID_JSON = ContentType.parse("application/vnd.orcid+json")
private val ORCID_ID_REGEX = Regex("""\d{4}-\d{4}-\d{4}-\d{3}[\dX]""")
private val RESERVED_AUTHORIZATION_PARAMETERS =
    setOf("client_id", "response_type", "scope", "redirect_uri", "state")

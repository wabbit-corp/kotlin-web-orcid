//package web.orcid
//
//import io.ktor.client.*
//import io.ktor.client.call.*
//import io.ktor.client.engine.cio.*
//import io.ktor.client.request.*
//import io.ktor.client.request.forms.*
//import io.ktor.http.*
//import io.ktor.http.headers
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.JsonObject
//import kotlinx.serialization.json.jsonPrimitive
//
//// https://github.com/ropensci-archive/rorcid/blob/master/README-not.md
//// https://github.com/ORCID/python-orcid
//// https://github.com/ORCID/orcid-model
//// https://github.com/scholrly/orcid-python
//// https://github.com/vdmitriyev/orcidpyclient
//// https://github.com/lizkrznarich/orcid-search
//// https://github.com/sri0606/PyOrcid
//
//const val SEARCH_VERSION = "/v2.0"
//const val VERSION = "/v2.0"
//
//@Serializable
//data class PublicAPI(
//    val institutionKey: String,
//    val institutionSecret: String,
//    val sandbox: Boolean = false,
//    val timeout: Double? = null,
//    val doStoreRawResponse: Boolean = false
//) {
//    private val host = if (sandbox) "sandbox.orcid.org" else "orcid.org"
//    private val loginOrRegisterEndpoint = if (sandbox) "https://sandbox.orcid.org/oauth/authorize" else "https://orcid.org/oauth/authorize"
//    private val loginUrl = if (sandbox) "https://sandbox.orcid.org/oauth/custom/login.json" else "https://orcid.org/oauth/custom/login.json"
//    private val tokenUrl = if (sandbox) "https://api.sandbox.orcid.org/oauth/token" else "https://api.orcid.org/oauth/token"
//    private val endpoint = if (sandbox) "https://pub.sandbox.orcid.org" else "https://pub.orcid.org"
//
//    private val client = HttpClient(CIO)
//
//    fun getLoginUrl(
//        scope: String,
//        redirectUri: String,
//        state: String? = null,
//        familyNames: String? = null,
//        givenNames: String? = null,
//        email: String? = null,
//        lang: String? = null,
//        showLogin: Boolean? = null
//    ): String {
//        // Implementation goes here
//    }
//
//    suspend fun search(
//        query: String,
//        method: String = "lucene",
//        start: Int? = null,
//        rows: Int? = null,
//        accessToken: String? = null
//    ): JsonObject {
//        val token = accessToken ?: getSearchTokenFromOrcid()
//        val headers = headersOf("Accept" to "application/orcid+json", "Authorization" to "Bearer $token")
//        val response = client.get("$endpoint$SEARCH_VERSION/search/?defType=$method&q=$query${start?.let { "&start=$it" } ?: ""}${rows?.let { "&rows=$it" } ?: ""}") {
//            headers(headers)
//        }
//        return response.body()
//    }
//
//    private suspend fun getSearchTokenFromOrcid(scope: String = "/read-public"): String {
//        val payload = FormDataContent(
//            Parameters.build {
//                append("client_id", institutionKey)
//                append("client_secret", institutionSecret)
//                append("scope", scope)
//                append("grant_type", "client_credentials")
//            }
//        )
//        val response = client.post("$endpoint/oauth/token") {
//            setBody(payload)
//            headers {
//                append(HttpHeaders.Accept, "application/json")
//            }
//        }
//        return response.body<JsonObject>().getValue("access_token").jsonPrimitive.content
//    }
//
//    suspend fun getToken(
//        userId: String,
//        password: String,
//        redirectUri: String,
//        scope: String = "/read-limited"
//    ): String {
//        val response = authenticate(userId, password, redirectUri, scope)
//        return response.getValue("access_token").jsonPrimitive.content
//    }
//
//    suspend fun getTokenFromAuthorizationCode(
//        authorizationCode: String,
//        redirectUri: String
//    ): JsonObject {
//        val tokenDict = Parameters.build {
//            append("client_id", institutionKey)
//            append("client_secret", institutionSecret)
//            append("grant_type", "authorization_code")
//            append("code", authorizationCode)
//            append("redirect_uri", redirectUri)
//        }
//
//        val response = client.post(tokenUrl) {
//            setBody(FormDataContent(tokenDict))
//            headers {
//                append(HttpHeaders.Accept, "application/json")
//            }
//        }
//
//        return response.body()
//    }
//
//    suspend fun readRecordPublic(
//        orcidId: String,
//        requestType: String,
//        token: String,
//        putCode: String? = null,
//        acceptType: String = "application/orcid+json"
//    ): JsonObject {
//        return getInfo(orcidId, ::getPublicInfo, requestType, token, putCode, acceptType)
//    }
//
//    private suspend fun getPublicInfo(
//        orcidId: String,
//        requestType: String,
//        accessToken: String,
//        putCode: String?,
//        acceptType: String
//    ): ResponseData {
//        val requestUrl = "$endpoint$VERSION/$orcidId/$requestType${putCode?.let { "/$it" } ?: ""}"
//        val headers = headersOf("Accept" to acceptType, "Authorization" to "Bearer $accessToken")
//
//        return client.get(requestUrl) {
//            headers(headers)
//        }
//    }
//}

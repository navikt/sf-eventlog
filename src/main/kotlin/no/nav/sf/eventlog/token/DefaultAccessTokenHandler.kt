package no.nav.sf.eventlog.token

import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.sf.eventlog.config_SF_TOKENHOST
import no.nav.sf.eventlog.env
import no.nav.sf.eventlog.secret_KEYSTORE_JKS_B64
import no.nav.sf.eventlog.secret_KEYSTORE_PASSWORD
import no.nav.sf.eventlog.secret_PRIVATE_KEY_ALIAS
import no.nav.sf.eventlog.secret_PRIVATE_KEY_PASSWORD
import no.nav.sf.eventlog.secret_SF_CLIENT_ID
import no.nav.sf.eventlog.secret_SF_USERNAME
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.body.toBody
import java.security.KeyStore
import java.security.PrivateKey

/**
 * A handler for oauth2 access flow to salesforce.
 * @see [sf.remoteaccess_oauth_jwt_flow](https://help.salesforce.com/s/articleView?id=sf.remoteaccess_oauth_jwt_flow.htm&type=5)
 *
 * Fetches and caches access token, also retrieves instance url
 */
class DefaultAccessTokenHandler : AccessTokenHandler {
    override val accessToken get() = fetchAccessTokenAndInstanceUrl().first
    override val instanceUrl get() = fetchAccessTokenAndInstanceUrl().second
    override val tenantId get() = fetchAccessTokenAndInstanceUrl().third

    private val log = KotlinLogging.logger { }

    private val sfTokenHost = env(config_SF_TOKENHOST)
    private val sfClientID = env(secret_SF_CLIENT_ID)
    private val sfUsername = env(secret_SF_USERNAME)
    private val keystoreB64 = env(secret_KEYSTORE_JKS_B64)
    private val keystorePassword = env(secret_KEYSTORE_PASSWORD)
    private val privateKeyAlias = env(secret_PRIVATE_KEY_ALIAS)
    private val privateKeyPassword = env(secret_PRIVATE_KEY_PASSWORD)

    private val client: HttpHandler = OkHttp()

    private val gson = Gson()

    private val expTimeSecondsClaim = 3600 // 60 min - expire time for the access token we ask salesforce for

    private var lastTokenTriplet = Triple("", "", "")

    private var expireTime = System.currentTimeMillis()

    private fun fetchAccessTokenAndInstanceUrl(): Triple<String, String, String> {
        if (System.currentTimeMillis() < expireTime) {
            log.debug { "Using cached access token (${(expireTime - System.currentTimeMillis()) / 60000} min left)" }
            return lastTokenTriplet
        }

        val expireMomentSinceEpochInSeconds = (System.currentTimeMillis() / 1000) + expTimeSecondsClaim
        val claim = JWTClaim(
            iss = sfClientID,
            aud = sfTokenHost,
            sub = sfUsername,
            exp = expireMomentSinceEpochInSeconds.toString()
        )
        val privateKey = privateKeyFromBase64Store(
            ksB64 = keystoreB64,
            ksPwd = keystorePassword,
            pkAlias = privateKeyAlias,
            pkPwd = privateKeyPassword
        )
        val claimWithHeaderJsonUrlSafe = "${
        gson.toJson(JWTClaimHeader("RS256")).encodeB64UrlSafe()
        }.${gson.toJson(claim).encodeB64UrlSafe()}"
        val fullClaimSignature = privateKey.sign(claimWithHeaderJsonUrlSafe.toByteArray())

        val accessTokenRequest = Request(Method.POST, sfTokenHost)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to "$claimWithHeaderJsonUrlSafe.$fullClaimSignature"
                ).toBody()
            )

        for (retry in 1..4) {
            try {
                val response: Response = client(accessTokenRequest)
                if (response.status.code == 200) {
                    val accessTokenResponse = gson.fromJson(response.bodyString(), AccessTokenResponse::class.java)
                    lastTokenTriplet = Triple(accessTokenResponse.access_token, accessTokenResponse.instance_url, accessTokenResponse.id.split("/")[4])
                    expireTime = System.currentTimeMillis() + 600000 // (expireMomentSinceEpochInSeconds - 10) * 1000
                    // println("$accessTokenResponse")
                    // println("UPDATE triple $lastTokenTriplet")
                    return lastTokenTriplet
                }
            } catch (e: Exception) {
                log.error("Attempt to fetch access token $retry of 3 failed by ${e.message}")
                runBlocking { delay(retry * 1000L) }
            }
        }
        log.error("Attempt to fetch access token given up")
        return Triple("", "", "")
    }

    private fun privateKeyFromBase64Store(ksB64: String, ksPwd: String, pkAlias: String, pkPwd: String): PrivateKey {
        return KeyStore.getInstance("JKS").apply { load(ksB64.decodeB64().inputStream(), ksPwd.toCharArray()) }.run {
            getKey(pkAlias, pkPwd.toCharArray()) as PrivateKey
        }
    }

    private fun PrivateKey.sign(data: ByteArray): String {
        return this.let {
            java.security.Signature.getInstance("SHA256withRSA").apply {
                initSign(it)
                update(data)
            }.run {
                sign().encodeB64()
            }
        }
    }

    private fun ByteArray.encodeB64(): String = String(java.util.Base64.getUrlEncoder().withoutPadding().encode(this))
    private fun String.decodeB64(): ByteArray = java.util.Base64.getMimeDecoder().decode(this)
    private fun String.encodeB64UrlSafe(): String = this.toByteArray(Charsets.UTF_8).encodeB64()

    private data class JWTClaim(
        val iss: String,
        val aud: String,
        val sub: String,
        val exp: String
    )

    private data class JWTClaimHeader(val alg: String)

    private data class AccessTokenResponse(
        val access_token: String,
        val scope: String,
        val instance_url: String,
        val id: String,
        val token_type: String
    )
}

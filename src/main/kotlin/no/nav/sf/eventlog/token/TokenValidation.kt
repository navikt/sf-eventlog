package no.nav.sf.eventlog.token

import no.nav.security.token.support.core.configuration.IssuerProperties
import no.nav.security.token.support.core.configuration.MultiIssuerConfiguration
import no.nav.security.token.support.core.http.HttpRequest
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.security.token.support.core.validation.JwtTokenValidationHandler
import no.nav.sf.eventlog.env
import no.nav.sf.eventlog.env_AZURE_APP_CLIENT_ID
import no.nav.sf.eventlog.env_AZURE_APP_WELL_KNOWN_URL
import org.http4k.core.Request
import java.net.URL

object TokenValidation {
    private val jwtTokenValidationHandler = JwtTokenValidationHandler(
        MultiIssuerConfiguration(
            mapOf(
                "azure" to IssuerProperties(
                    URL(env(env_AZURE_APP_WELL_KNOWN_URL)),
                    listOf(env(env_AZURE_APP_CLIENT_ID))
                )
            )
        )
    )

    fun firstValidToken(request: Request): JwtToken? =
        jwtTokenValidationHandler.getValidatedTokens(request.toNavRequest()).firstValidToken

    private fun Request.toNavRequest(): HttpRequest {
        val req = this
        return object : HttpRequest {
            override fun getHeader(headerName: String): String {
                return req.header(headerName) ?: ""
            }
        }
    }
}

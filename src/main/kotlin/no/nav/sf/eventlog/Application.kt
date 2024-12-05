package no.nav.sf.eventlog

import mu.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.ApacheServer
import org.http4k.server.Http4kServer
import org.http4k.server.asServer

object Application {
    private val log = KotlinLogging.logger { }

    val cluster = env(env_NAIS_CLUSTER_NAME)

    val salesforceClient = SalesforceClient()

    fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    fun api(): HttpHandler = routes(
        "/internal/isAlive" bind Method.GET to { Response(OK) },
        "/internal/isReady" bind Method.GET to { Response(OK) },
        "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
    )

    fun start() {
        log.info { "Starting in cluster $cluster" }
        apiServer(8080).start()
        salesforceClient.fetchEventLog(EventType.ApexUnexpectedException)
        Thread.sleep(5 * 60000)
        log.info { "Slept 5 min" }
    }
}

package no.nav.sf.eventlog

import mu.KotlinLogging
import no.nav.sf.eventlog.db.PostgresDatabase
import no.nav.sf.eventlog.db.getMetaData
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.ApacheServer
import org.http4k.server.Http4kServer
import org.http4k.server.asServer
import java.time.LocalDate

class Application {
    private val log = KotlinLogging.logger { }

    val salesforceClient = SalesforceClient()

    private val database = PostgresDatabase()

    val gson = configureGson()

    val cluster = env(env_NAIS_CLUSTER_NAME)

    private fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    private fun api(): HttpHandler = routes(
        "/internal/isAlive" bind Method.GET to { Response(OK) },
        "/internal/isReady" bind Method.GET to { Response(OK) },
        "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
        "/internal/fetchAndLog" bind Method.GET to fetchAndLogHandler,
        "/internal/test" bind Method.GET to { if (cluster != "local") database.create() ; log.info { "Test path is called" }; Response(OK) },
        "/internal/gui" bind Method.GET to static(ResourceLoader.Classpath("gui")),
        "/internal/metadata" bind Method.GET to metaDataHandler
    )

    fun start() {
        log.info { "Starting in cluster $cluster" }
        apiServer(8080).start()
        // salesforceClient.fetchLogFiles(EventType.ApexUnexpectedException)
    }

    private val fetchAndLogHandler: HttpHandler = {
        val date = LocalDate.parse(it.query("date")!!)
        val eventTypeArg = it.query("eventType")!!
        if (eventTypeArg == "ALL") {
            log.info { "Will fetch event logs for ALL event types (${EventType.values().joinToString(",") { e -> e.name }}) for $date" }
            val result = EventType.values().map { eventType ->
                salesforceClient.fetchAndLogEventLogs(eventType, date)
            }
            Response(OK).body(gson.toJson(result))
        } else {
            val eventType = EventType.valueOf(eventTypeArg)
            val result = salesforceClient.fetchAndLogEventLogs(eventType, date)
            Response(OK).body(gson.toJson(result))
        }
    }

    private val metaDataHandler: HttpHandler = {
        Response(OK).body(getMetaData())
    }
}

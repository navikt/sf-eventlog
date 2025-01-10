package no.nav.sf.eventlog

import mu.KotlinLogging
import no.nav.sf.eventlog.db.LogSyncStatus
import no.nav.sf.eventlog.db.PostgresDatabase
import no.nav.sf.eventlog.db.createNoLogfileStatus
import no.nav.sf.eventlog.db.getMetaData
import no.nav.sf.eventlog.salesforce.SalesforceClient
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

    val gson = configureGson()

    val cluster = env(env_NAIS_CLUSTER_NAME)

    private fun apiServer(port: Int = 8080): Http4kServer = api().asServer(ApacheServer(port))

    private fun api(): HttpHandler = routes(
        "/internal/isAlive" bind Method.GET to { Response(OK) },
        "/internal/isReady" bind Method.GET to { Response(OK) },
        "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
        "/internal/fetchAndLog" bind Method.GET to fetchAndLogHandler,
        "/internal/fetchAndLogYesterday" bind Method.GET to fetchAndLogYesterdayHandler,
        "/internal/test" bind Method.GET to { request -> log.info { "Test path is called with URL: ${request.uri}" }; Response(OK) },
        "/internal/gui" bind Method.GET to static(ResourceLoader.Classpath("gui")),
        "/internal/metadata" bind Method.GET to metaDataHandler,
        "/internal/clearCaches" bind Method.GET to {
            salesforceClient.clearCache()
            PostgresDatabase.clearCache()
            Response(OK).body("Caches cleared")
        }
    )

    fun start() {
        log.info { "Starting in cluster $cluster" }
        apiServer().start()
        // if (cluster == "prod-gcp") PostgresDatabase.create()
        // salesforceClient.fetchLogFiles(EventType.ApexUnexpectedException)
    }

    private val fetchAndLogHandler: HttpHandler = {
        val date = LocalDate.parse(it.query("date")!!)
        val eventTypeArg = it.query("eventType")!!
        fetchAndLogHandlerCommon(date, eventTypeArg)
    }

    private val fetchAndLogYesterdayHandler: HttpHandler = {
        val date = LocalDate.now().minusDays(1)
        log.info { "fetchAndLogHandlerYesterday triggered and will attempt fetch ALL for date $date" }
        fetchAndLogHandlerCommon(date, "ALL")
    }

    private fun fetchAndLogHandlerCommon(date: LocalDate, eventTypeArg: String): Response {
        fun handleEventLogs(eventType: EventType): LogSyncStatus {
            salesforceClient.logFileDataMap
            val eventLogsForDate = PostgresDatabase.logSyncStatusMap[eventType]?.get(date)
            return if (!salesforceClient.isLogFileToFetch(date, eventType)) {
                log.info { "Skipping performing fetch and log on $eventType for $date since there is no such log file Salesforce" }
                createNoLogfileStatus(date, eventType)
            } else if (!local && eventLogsForDate?.status == "SUCCESS") {
                log.info { "Skipping performing fetch and log on $eventType for $date since there is a sync registered as performed successfully already" }
                eventLogsForDate
            } else {
                salesforceClient.fetchAndLogEventLogs(eventType, date)
            }
        }
        return if (eventTypeArg == "ALL") {
            log.info { "Will fetch event logs for ALL event types (${EventType.values().joinToString(",") { it.name }}) for $date" }
            val result = EventType.values().map { handleEventLogs(it) }
            Response(OK).body(gson.toJson(result))
        } else {
            val result = handleEventLogs(EventType.valueOf(eventTypeArg))
            Response(OK).body(gson.toJson(result))
        }
    }

    private val metaDataHandler: HttpHandler = {
        Response(OK).body(getMetaData())
    }
}

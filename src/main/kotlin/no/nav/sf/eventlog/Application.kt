package no.nav.sf.eventlog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import no.nav.sf.eventlog.db.LogSyncStatus
import no.nav.sf.eventlog.db.PostgresDatabase
import no.nav.sf.eventlog.db.createNoLogfileStatus
import no.nav.sf.eventlog.db.createProcessingStatus
import no.nav.sf.eventlog.db.createUnprocessedStatus
import no.nav.sf.eventlog.db.getMetaData
import no.nav.sf.eventlog.salesforce.SalesforceClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.ApacheServer
import org.http4k.server.Http4kServer
import org.http4k.server.asServer
import java.io.File
import java.lang.IllegalStateException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class Application {
    private val log = KotlinLogging.logger { }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val context = env(config_CONTEXT)

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
        "/internal/examine" bind Method.GET to examineHandler,
        "/internal/applogfetch" bind Method.GET to appLogFetchHandler,
        "/internal/gui" bind Method.GET to static(ResourceLoader.Classpath("gui")),
        "/internal/guiLabel" bind Method.GET to { Response(OK).body(context) },
        "/internal/metadata" bind Method.GET to metaDataHandler,
        "/internal/clearCaches" bind Method.GET to {
            salesforceClient.clearCache()
            PostgresDatabase.clearCache()
            Response(OK).body("Caches cleared")
        },
        "/internal/clearStatus" bind Method.GET to clearStatusHandler,
        "/internal/transferStatus" bind Method.GET to TransferJob.statusHandler,
        "/internal/limits" bind Method.GET to limitHandler
    )

    fun start() {
        log.info { "Starting in cluster $cluster" }
        apiServer().start()
        if (!local) {
            val progressMap = PostgresDatabase.retrieveLogSyncProgressesAsMap()
            log.info { "Result of progress map lookup: $progressMap" }
            progressMap.forEach {
                it.value.filter { it.value.progress != it.value.goal }.map { it.value }.forEach {
                    if (TransferJob.active) {
                        log.info { "Will put off pickup job of ${it.eventType} for ${it.syncDate}, from ${it.progress} to ${it.goal} since already busy" }
                    } else {
                        log.info { "Starting job pickup on ${it.eventType} for ${it.syncDate}, from ${it.progress} to ${it.goal}" }
                        TransferJob.activateTransferJob(it.syncDate, EventType.valueOf(it.eventType), it.progress)
                    }
                }
                it.value.filter { it.value.progress == it.value.goal }.map { it.value }.forEach {
                    log.info { "Should remove completed job from progress table ${it.eventType} ${it.syncDate}, ${it.goal} rows" }
                    PostgresDatabase.deleteLogSyncProgressRow(it)
                }
            }
        }
        scheduleLimitCalls()

        Runtime.getRuntime().addShutdownHook(
            Thread {
                log.info { "Shutting down gracefully..." }
                scope.cancel()
            }
        )

        // PostgresDatabase.createProgressTable()
        if (local) {
            // salesforceClient.fetchLogFiles(EventType.ApexCallout)
            // Normally run via the async TransferJob:
            // salesforceClient.fetchAndProcessEventLogsStreaming(EventType.ApexCallout, LocalDate.parse("2025-03-16"), 0)
            println(salesforceClient.doLimitCall())
            // fetchAndLogHandlerCommon(LocalDate.now().minusDays(1), "ALL")
        }
        File("/tmp/limitCallResponse").writeText(salesforceClient.doLimitCall())
        // if (cluster == "prod-gcp") PostgresDatabase.create()
        // salesforceClient.fetchLogFiles(EventType.ApexUnexpectedException)
    }

    private fun scheduleLimitCalls() {
        scope.launch {
            while (isActive) {
                try {
                    salesforceClient.doLimitCall()
                } catch (e: Exception) {
                    log.error(e) { "Error during salesforce limit fetch" }
                }
                delay(TimeUnit.MINUTES.toMillis(30)) // 30 minutes delay
            }
        }
    }

    var debugValue: Int = 0

    private val examineHandler: HttpHandler = {
        val date = LocalDate.parse(it.query("date")!!)
        val eventTypeArg = it.query("eventType")!!
        val eventType = EventType.valueOf(eventTypeArg)
        val logFilesForDate = application.salesforceClient.logFileDataMap[eventType]?.filter { it.date == date } ?: listOf()
        if (logFilesForDate.size > 1) throw IllegalStateException("Should never be more then one log file per log date")
        if (logFilesForDate.isEmpty()) {
            Response(OK).body("No log rows found of $eventTypeArg for $date")
        } else {
            logFilesForDate.first().let {
                val responseAndCount = application.salesforceClient.countCsvRows(application.salesforceClient.logFileContentRequest(it.file))
                // val capturedEvents = application.salesforceClient.fetchLogFileContentAsJson(it.file)
                Response(OK).body("${responseAndCount.first} log rows found of $eventTypeArg for $date. DEBUG $debugValue")
            }
        }
    }

    private val appLogFetchHandler: HttpHandler = {
        val date = LocalDate.parse(it.query("date")!!)
        Response(OK).body("Application log stats for $date (error, critical): ${application.salesforceClient.fetchApplicationLogsForDateFromRest(date)}")
    }

    private val clearStatusHandler: HttpHandler = {
        val date = LocalDate.parse(it.query("date")!!)
        val eventTypeArg = it.query("eventType")!!
        val eventType = EventType.valueOf(eventTypeArg)
        PostgresDatabase.deleteLogSyncStatus(date, eventType)
        Response(OK).body("Done")
    }

    private val limitHandler: HttpHandler = {
        Response(OK).body("Results: ${salesforceClient.doLimitCall()}")
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
            // salesforceClient.logFileDataMap
            val eventLogsForDate = if (local) {
                null
            } else {
                PostgresDatabase.logSyncStatusMap[eventType]?.get(date)
            }
            return if (!salesforceClient.isLogFileToFetch(date, eventType)) {
                log.info { "Skipping performing fetch and log on $eventType for $date - No log file in Salesforce" }
                createNoLogfileStatus(date, eventType)
            } else if (!local && eventLogsForDate?.status == "SUCCESS") {
                log.info { "Skipping performing fetch and log on $eventType for $date - Already processed successfully" }
                eventLogsForDate
            } else if (TransferJob.active) {
                log.info { "Skipping performing fetch and log on $eventType for $date - Job in progress" }
                createUnprocessedStatus(date, eventType)
            } else {
                Metrics.clearEventLogCounter(eventType)
                TransferJob.activateTransferJob(date, eventType)
                createProcessingStatus(date, eventType)
            }
        }
        return if (eventTypeArg == "ALL") {
            log.info { "Will fetch event logs for ALL event types (${EventType.values().joinToString(",") { it.name }}) for $date" }
            val result = EventType.values().map {
                log.info { "In $it, start handling" }
                var status = handleEventLogs(it)
                if (status.status == "PROCESSING") {
                    // Use the pollStatus (same as gui) do determine if current eventType job has finished
                    // status == null is when async tread is not yet launched so the job has not started yet
                    while (TransferJob.pollStatus(date, it).status == Status.ACCEPTED || TransferJob.status == null) {
                        log.info { "In $it, Transfer ALL in progress, sleep 10 s" }
                        Thread.sleep(10000)
                    }
                    log.info { "In $it, Done waiting will use job status ${TransferJob.status ?: "Null state!!"}" }
                    status = TransferJob.status!!
                }
                log.info { "In $it, returning status $status" }
                status
            }
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

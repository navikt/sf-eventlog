package no.nav.sf.eventlog

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.sf.eventlog.db.LogSyncStatus
import no.nav.sf.eventlog.db.createFailureStatus
import no.nav.sf.eventlog.db.createNoLogfileStatus
import no.nav.sf.eventlog.db.createSuccessStatus
import no.nav.sf.eventlog.token.AccessTokenHandler
import no.nav.sf.eventlog.token.DefaultAccessTokenHandler
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.http4k.client.ApacheClient
import org.http4k.core.Method
import org.http4k.core.Request
import java.io.File
import java.io.StringReader
import java.lang.Exception
import java.lang.IllegalStateException
import java.net.URLEncoder
import java.time.LocalDate

class SalesforceClient(private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler()) {
    private val log = KotlinLogging.logger { }

    private val apiVersion = env(config_SALESFORCE_API_VERSION)

    private val client = ApacheClient()

    private var logFileCacheLastUpdated: LocalDate = LocalDate.MIN

    private var logFileDataCache: Map<EventType, List<LogFileData>> = mapOf()

    val logFileDataMap: Map<EventType, List<LogFileData>> get() {
        if (logFileCacheLastUpdated == LocalDate.now()) {
            log.info { "Using log file dates cache" }
        } else {
            log.info { "Cache invalid : Fetching log file dates" }
            logFileDataCache = EventType.values().associateWith { eventType ->
                fetchLogFiles(eventType).map { it }
            }
            logFileCacheLastUpdated = LocalDate.now()
        }
        return logFileDataCache
    }

    fun getLogFileDatesMock(): Map<EventType, List<LogFileData>> {
        val eventTypes = listOf(EventType.ApexUnexpectedException, EventType.FlowExecution)

        return eventTypes.associateWith { eventType ->
            List(10) {
                LogFileData(date = LocalDate.now().minusDays((1..30).random().toLong()).toString(), file = "") // Simulate 10 random dates for each event type
                // Random date in the last 30 days
            }
        }
    }

    fun fetchAndLogEventLogs(eventType: EventType, date: LocalDate): LogSyncStatus {
        log.info { "Will fetch event logs for ${eventType.name} $date" }
        try {
            val logFilesForDate = logFileDataMap[eventType]?.filter { LocalDate.parse(it.date) == date } ?: listOf()
            if (logFilesForDate.size > 1) throw IllegalStateException("Should never be more then one log file per log date")
            if (logFilesForDate.isEmpty()) {
                return createNoLogfileStatus(date, eventType)
            }
            logFilesForDate.first().let {
                // TODO make sure there is not a successful run stored in db.
                val capturedEvents = fetchLogFileContentAsJson(it.file)

                log.info { "Would have log ${capturedEvents.size} events" }
                capturedEvents.forEach { event ->
                    val logMessage = if (eventType.messageField.isNotBlank()) {
                        event[eventType.messageField]?.asString ?: "N/A"
                    } else {
                        // Locally - if no message field defined we want to see full event object to examine model
                        if (application.cluster == "local") event.toString() else "N/A"
                    }

                    val nonSensitiveContext =
                        eventType.generateLoggingContext(eventData = event, excludeSensitive = true)
                    val fullContext = eventType.generateLoggingContext(eventData = event, excludeSensitive = false)

                    withLoggingContext(nonSensitiveContext) {
                        //log.error(logMessage)
                    }
                    withLoggingContext(fullContext) {
                        //log.error(SECURE, logMessage)
                    }
                }
                val successState = createSuccessStatus(date, eventType, "Would have Logged ${capturedEvents.size} events of type ${eventType.name} for $date")
                application.database.upsertLogSyncStatus(successState)
                application.database.updateCache(successState)
                return successState
            }
        } catch (e: Exception) {
            val failureState = createFailureStatus(date, eventType, e.javaClass.name + ":" + e.message)
            application.database.upsertLogSyncStatus(failureState)
            application.database.updateCache(failureState)
            return failureState
        }
    }

    /***
     * fetchLogFiles - fetches FileLogs from Salesforce that each contains the event logs for evenType for one day
     *  - logDate - which date to fetch logfile for, if null that means fetch all (typically last 30 days)
     *  - fieldOfInterest - which field is i
     */
    fun fetchLogFiles(eventType: EventType, logDate: LocalDate? = null): List<LogFileData> {
        val soqlQuery = "SELECT Id, EventType, LogFile, LogDate FROM EventLogFile WHERE EventType='${eventType.name}'" +
            (logDate?.let { dateRestrictionExtention(it) } ?: "")
        val encodedQuery = URLEncoder.encode(soqlQuery, "UTF-8")

        var done = false
        var nextRecordsUrl = "/services/data/$apiVersion/query?q=$encodedQuery"

        val result: MutableList<LogFileData> = mutableListOf()

        while (!done) {
            val request = Request(Method.GET, accessTokenHandler.instanceUrl + nextRecordsUrl)
                .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
                .header("Accept", "application/json")
            val response = client(request)
            if (response.status.successful) {
                val obj = JsonParser.parseString(response.bodyString()).asJsonObject
                val recordEntries = obj["records"].asJsonArray
                result.addAll(
                    recordEntries.map {
                        log.debug {
                            "Fetched logfile for ${eventType.name} from date " + LocalDate.parse(
                                it.asJsonObject["LogDate"].asString.substring(0, 10)
                            )
                        }
                        LogFileData(file = it.asJsonObject["LogFile"].asString, date = it.asJsonObject["LogDate"].asString.substring(0, 10))
                    }
                )
                val totalSize = obj["totalSize"].asInt
                log.info { "Completed fetch ${result.count()} of $totalSize log files for ${eventType.name}" }
                done = obj["done"].asBoolean
                if (!done) nextRecordsUrl = obj["nextRecordsUrl"].asString
            } else {
                log.error { "Failed to fetch EventLogFiles of type ${eventType.name} - response ${response.status.code}:${response.bodyString()}" }
                done = true
            }
        }

        return result
    }

    private fun dateRestrictionExtention(date: LocalDate) =
        " AND LogDate >= ${date}T00:00:00Z AND LogDate < ${date.plusDays(1)}T00:00:00Z"

    private fun fetchLogFileContentAsJson(logFileUrl: String): List<JsonObject> {
        val fullLogFileUrl = "${accessTokenHandler.instanceUrl}$logFileUrl"
        val request = Request(Method.GET, fullLogFileUrl)
            .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
            .header("Accept", "text/csv")

        val response = client(request)

        return if (response.status.successful) {
            parseCSVToJsonObjects(response.bodyString())
        } else {
            log.error { "Error fetching log file $logFileUrl: ${response.status.code}:${response.bodyString()}" }
            listOf()
        }
    }

    private fun parseCSVToJsonObjects(csvData: String): List<JsonObject> {
        File("/tmp/latestCsvData").writeText(csvData)
        val result: MutableList<JsonObject> = mutableListOf()

        val reader = StringReader(csvData)
        val csvParser = CSVParser(
            reader,
            CSVFormat.DEFAULT.builder().setSkipHeaderRecord(true).setHeader().build()
        )

        // Iterate through the records (skipping the header row)
        for (csvRecord in csvParser) {
            val jsonObject = JsonObject()

            // For each column in the record, add the key-value pair to the JsonObject
            csvRecord.toMap().forEach { (key, value) ->
                jsonObject.addProperty(key, if (value.isNullOrBlank()) null else value.trim('"'))
            }

            result.add(jsonObject)
        }
        csvParser.close()
        reader.close()

        return result
    }
}

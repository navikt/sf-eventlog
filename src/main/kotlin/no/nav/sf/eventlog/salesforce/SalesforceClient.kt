package no.nav.sf.eventlog.salesforce

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.sf.eventlog.EventType
import no.nav.sf.eventlog.Metrics
import no.nav.sf.eventlog.SECURE
import no.nav.sf.eventlog.TransferJob
import no.nav.sf.eventlog.config_SALESFORCE_API_VERSION
import no.nav.sf.eventlog.db.LogSyncStatus
import no.nav.sf.eventlog.db.PostgresDatabase
import no.nav.sf.eventlog.db.createFailureStatus
import no.nav.sf.eventlog.db.createNoLogfileStatus
import no.nav.sf.eventlog.db.createSuccessStatus
import no.nav.sf.eventlog.env
import no.nav.sf.eventlog.generateLoggingContext
import no.nav.sf.eventlog.local
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class SalesforceClient(private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler()) {
    private val log = KotlinLogging.logger { }

    private val apiVersion = env(config_SALESFORCE_API_VERSION)

    private val client = ApacheClient()

    private val useCache = false

    private var logFileCacheLastUpdated: LocalDateTime = LocalDateTime.MIN

    private var logFileDataCache: Map<EventType, List<LogFileData>> = mapOf()

    fun clearCache() {
        logFileDataCache = mutableMapOf()
        logFileCacheLastUpdated = LocalDateTime.MIN
    }

    val logFileDataMap: Map<EventType, List<LogFileData>> get() {
        if (useCache && logFileCacheLastUpdated.toLocalDate() == LocalDate.now() &&
            isTimeInAllowedRange(logFileCacheLastUpdated.toLocalTime(), LocalTime.now())
        ) {
            log.info { "Using log file dates cache" }
        } else {
            log.info { "${if (useCache) "Cache invalid : " else ""}Fetching log file dates" }
            logFileDataCache = EventType.values().associateWith { eventType ->
                fetchLogFiles(eventType)
            }
            logFileCacheLastUpdated = LocalDateTime.now()
        }
        return logFileDataCache
    }

    private fun isTimeInAllowedRange(cacheTime: LocalTime, currentTime: LocalTime): Boolean {
        val morningCutoff = LocalTime.of(12, 30)
        val afternoonStart = LocalTime.of(13, 30)

        val bothMorning = cacheTime.isBefore(morningCutoff) && currentTime.isBefore(morningCutoff)
        val bothAfternoon = cacheTime.isAfter(afternoonStart) && currentTime.isAfter(afternoonStart)

        return bothMorning || bothAfternoon
    }

    fun isLogFileToFetch(date: LocalDate, eventType: EventType): Boolean =
        logFileDataMap[eventType]?.any { it.date == date } == true

    fun getLogFileDatesMock(): Map<EventType, List<LogFileData>> {
        val eventTypes = listOf(EventType.ApexUnexpectedException/*, EventType.FlowExecution*/)

        return eventTypes.associateWith {
            List(10) {
                LogFileData(date = LocalDate.now().minusDays((1..30).random().toLong()), file = "") // Simulate 10 random dates for each event type
                // Random date in the last 30 days
            }
        }
    }

    fun fetchAndLogEventLogs(eventType: EventType, date: LocalDate, skipToRow: Int): LogSyncStatus {
        log.info { "Will fetch event logs for ${eventType.name} $date" + (if (skipToRow > 1) " but skip to row $skipToRow" else "") }
        try {
            val logFilesForDate = logFileDataMap[eventType]?.filter { it.date == date } ?: listOf()
            if (logFilesForDate.size > 1) throw IllegalStateException("Should never be more then one log file per log date")
            if (logFilesForDate.isEmpty()) {
                return createNoLogfileStatus(date, eventType)
            }
            logFilesForDate.first().let {
                val capturedEvents = fetchLogFileContentAsJson(it.file)

                TransferJob.goal = capturedEvents.size
                if (skipToRow > 1) {
                    log.info { "Will continue log ${capturedEvents.size} events from position $skipToRow of type $eventType for $date" }
                } else {
                    log.info { "Will log ${capturedEvents.size} events of type $eventType for $date" }
                }

                var logCounter = 0 // To pause every 100th record
                val capturedEventsSize = capturedEvents.size
                if (!local) {
                    PostgresDatabase.upsertLogSyncProgress(date, eventType.name, 0, capturedEventsSize)
                }
                capturedEvents.forEach { event ->
                    // File("/tmp/latestEvent").writeText(event.toString())
                    val logMessage = if (eventType.messageField.isNotBlank()) {
                        event[eventType.messageField]?.asString ?: "N/A"
                    } else {
                        // Locally - if no message field defined we want to see full event object to examine model
                        if (local) event.toString() else "N/A"
                    }

                    logCounter++

                    if (logCounter >= skipToRow) {
                        val nonSensitiveContext =
                            eventType.generateLoggingContext(
                                eventData = event,
                                excludeSensitive = true,
                                logCounter,
                                capturedEventsSize,
                                eventType.fieldToUseAsEventTime
                            )
                        val fullContext = eventType.generateLoggingContext(
                            eventData = event,
                            excludeSensitive = false,
                            logCounter,
                            capturedEventsSize,
                            eventType.fieldToUseAsEventTime
                        )

                        withLoggingContext(nonSensitiveContext) {
                            log.error(logMessage)
                        }

                        withLoggingContext(fullContext) {
                            log.error(SECURE, logMessage)
                        }

                        try {
                            Metrics.eventLogCounters[eventType]!!
                                .labels(
                                    *eventType.fieldsToUseAsMetricLabels.map { nonSensitiveContext[it] }
                                        .toTypedArray()
                                ).inc()
                        } catch (e: Exception) {
                            log.warn { "Failed to populate and increment a metric of eventType $eventType: ${e.message}" }
                        }

                        if (!local) {
                            PostgresDatabase.upsertLogSyncProgress(date, eventType.name, logCounter, capturedEventsSize)
                        }
                    }

                    TransferJob.progress = logCounter
                    if (logCounter % 100 == 0) {
                        if (logCounter >= skipToRow) {
                            log.info { "Logged $logCounter of $capturedEventsSize events" + (if (skipToRow > 1) " of which skipped first $skipToRow in current run" else "") }
                        } else {
                            log.info { "Skipped $logCounter of $capturedEventsSize events" }
                        }
                        Thread.sleep(2000) // Pause for 2 seconds
                    }
                }
                log.info { "Finally logged $logCounter of $capturedEventsSize events" + (if (skipToRow > 1) " of which skipped first $skipToRow in current run" else "") }
                val successState = createSuccessStatus(date, eventType, "Logged $capturedEventsSize events of type ${eventType.name} for $date " + (if (skipToRow > 1) " (pickup from $skipToRow in current run)" else ""))
                if (!local) {
                    PostgresDatabase.upsertLogSyncStatus(successState)
                    PostgresDatabase.updateCache(successState)
                }
                return successState
            }
        } catch (e: Exception) {
            log.warn { "Process interrupted " + e.javaClass.name + ":" + e.message }
            val failureState = createFailureStatus(date, eventType, e.javaClass.name + ":" + e.message)
            if (!local) {
                PostgresDatabase.upsertLogSyncStatus(failureState)
                PostgresDatabase.updateCache(failureState)
            }
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
                        LogFileData(file = it.asJsonObject["LogFile"].asString, date = LocalDate.parse(it.asJsonObject["LogDate"].asString.substring(0, 10)))
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

        Metrics.fetchedLogs.labels(eventType.name).inc(result.count().toDouble())

        File("/tmp/latestFileLogs-${eventType.name}").writeText(result.toString())

        return result
    }

    private fun dateRestrictionExtention(date: LocalDate) =
        " AND LogDate >= ${date}T00:00:00Z AND LogDate < ${date.plusDays(1)}T00:00:00Z"

    // private fun createdDateRestrictionExtention(date: LocalDate) =
    //    " AND CreatedDate >= ${date}T00:00:00Z AND CreatedDate < ${date.plusDays(1)}T00:00:00Z"

    private fun createdDateRestrictionExtention(date: LocalDate): String {
        val osloZone = ZoneId.of("Europe/Oslo")

        // Convert start of the LocalDate in Oslo time to UTC
        val startOfDayUtc = date.atStartOfDay(osloZone).withZoneSameInstant(ZoneOffset.UTC)
        val endOfDayUtc = date.plusDays(1).atStartOfDay(osloZone).withZoneSameInstant(ZoneOffset.UTC)

        return " AND CreatedDate >= ${startOfDayUtc.toLocalDate()}T${startOfDayUtc.toLocalTime()}Z" +
            " AND CreatedDate < ${endOfDayUtc.toLocalDate()}T${endOfDayUtc.toLocalTime()}Z"
    }

    fun fetchLogFileContentAsJson(logFileUrl: String): List<JsonObject> {
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
        // File("/tmp/latestCsvData").writeText(csvData)
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
        // File("/tmp/latestJson").writeText(Gson().toJson(result))

        return result
    }

    fun fetchApplicationLogsForDateFromRest(logDate: LocalDate): Pair<Int, Int> {
        val soqlQuery = "SELECT CreatedDate, Log_Level__c, Log_Messages__c, Application_Domain__c, Source_Class__c, UUID__c FROM Application_Log__c WHERE Log_Level__c IN ('Critical', 'Error')" +
            createdDateRestrictionExtention(logDate)
        val encodedQuery = URLEncoder.encode(soqlQuery, "UTF-8")
        var done = false
        var nextRecordsUrl = "/services/data/$apiVersion/query?q=$encodedQuery"

        // Variables to count Critical and Error logs
        var criticalCount = 0
        var errorCount = 0

        while (!done) {
            val request = Request(Method.GET, accessTokenHandler.instanceUrl + nextRecordsUrl)
                .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
                .header("Accept", "application/json")
            val response = client(request)

            File("/tmp/applogresp-$logDate").writeText(response.toMessage())

            if (response.status.successful) {
                val obj = JsonParser.parseString(response.bodyString()).asJsonObject

                val records = obj["records"].asJsonArray

                // Count Critical and Error logs
                for (record in records) {
                    val logLevel = record.asJsonObject["Log_Level__c"].asString
                    when (logLevel) {
                        "Critical" -> criticalCount++
                        "Error" -> errorCount++
                    }
                }

                done = obj["done"].asBoolean
                if (!done) nextRecordsUrl = obj["nextRecordsUrl"].asString
            } else {
                log.warn { "Failed to fetch application logs - response ${response.status.code}:${response.bodyString()}" }
                done = true
            }
        }
        return Pair(errorCount, criticalCount)
    }

    fun queryForAllEventTypes(): String {
        val soqlQuery = "SELECT EventType FROM EventLogFile GROUP BY EventType"
        val encodedQuery = URLEncoder.encode(soqlQuery, "UTF-8")

        var nextRecordsUrl = "/services/data/$apiVersion/query?q=$encodedQuery"

        val request = Request(Method.GET, accessTokenHandler.instanceUrl + nextRecordsUrl)
            .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
            .header("Accept", "application/json")
        val response = client(request)
        File("/tmp/responseEventTypeQuery").writeText(response.toMessage())
        return response.bodyString()
    }
}

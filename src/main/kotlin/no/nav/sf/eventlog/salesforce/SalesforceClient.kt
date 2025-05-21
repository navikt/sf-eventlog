package no.nav.sf.eventlog.salesforce

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.sf.eventlog.EventType
import no.nav.sf.eventlog.Metrics
import no.nav.sf.eventlog.Metrics.toSizeLabel
import no.nav.sf.eventlog.Metrics.toTimeLabel
import no.nav.sf.eventlog.SECURE
import no.nav.sf.eventlog.TransferJob
import no.nav.sf.eventlog.config_SALESFORCE_API_VERSION
import no.nav.sf.eventlog.db.LogSyncStatus
import no.nav.sf.eventlog.db.PostgresDatabase
import no.nav.sf.eventlog.db.createFailureStatus
import no.nav.sf.eventlog.db.createNoLogfileStatus
import no.nav.sf.eventlog.db.createSuccessStatus
import no.nav.sf.eventlog.env
import no.nav.sf.eventlog.fieldAsString
import no.nav.sf.eventlog.generateLoggingContext
import no.nav.sf.eventlog.local
import no.nav.sf.eventlog.token.AccessTokenHandler
import no.nav.sf.eventlog.token.DefaultAccessTokenHandler
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.NoConnectionReuseStrategy
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.http4k.client.ApacheClient
import org.http4k.core.BodyMode
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import java.io.File
import java.lang.Exception
import java.lang.IllegalStateException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class SalesforceClient(private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler()) {
    private val log = KotlinLogging.logger { }

    private val apiVersion = env(config_SALESFORCE_API_VERSION)

    private val client = ApacheClient()

    // Tuning for long-lasting streaming:
    val longLivedHttpClient = HttpClients.custom()
        .setConnectionManager(PoolingHttpClientConnectionManager())
        .setConnectionTimeToLive(1, TimeUnit.HOURS)
        .setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE) // always use fresh connections
        .disableContentCompression() // avoid gzip chunking issues
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setSocketTimeout(60 * 60 * 1000) // 1h read timeout
                .setConnectTimeout(30_000)
                .setConnectionRequestTimeout(30_000)
                .build()
        )
        .build()

    private val clientStreamingMode = ApacheClient(longLivedHttpClient, responseBodyMode = BodyMode.Stream)

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

    fun fetchAndProcessEventLogsStreaming(eventType: EventType, date: LocalDate, skipToRow: Int): LogSyncStatus {
        log.info { "Will fetch event logs for ${eventType.name} $date" + (if (skipToRow > 1) " but skip to row $skipToRow" else "") }
        try {
            val logFilesForDate = logFileDataMap[eventType]?.filter { it.date == date } ?: listOf()
            if (logFilesForDate.size > 1) throw IllegalStateException("Should never be more then one log file per log date")
            if (logFilesForDate.isEmpty()) {
                return createNoLogfileStatus(date, eventType)
            }
            logFilesForDate.first().let {
                val countAndResponse = countCsvRows(logFileContentRequest(it.file))
                return processCsvRows(countAndResponse.second, countAndResponse.first, date, eventType, skipToRow)
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
    fun fetchLogFiles(eventType: EventType, logDate: LocalDate? = null, verbose: Boolean = false): List<LogFileData> {
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
            val response = clientStreamingMode(request)
            if (response.status.successful) {
                val obj = JsonParser.parseString(response.bodyString()).asJsonObject
                val recordEntries = obj["records"].asJsonArray
                result.addAll(
                    recordEntries.map {
                        if (verbose) {
                            log.debug {
                                "Fetched logfile for ${eventType.name} from date " + LocalDate.parse(
                                    it.asJsonObject["LogDate"].asString.substring(0, 10)
                                )
                            }
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

    fun createdDateRestrictionExtension(date: LocalDate): String {
        val osloZone = ZoneId.of("Europe/Oslo")

        // Convert LocalDate (Oslo time) to UTC at start and end of the day
        val startOfDayUtc = date.atStartOfDay(osloZone).withZoneSameInstant(ZoneOffset.UTC)
        val endOfDayUtc = date.plusDays(1).atStartOfDay(osloZone).withZoneSameInstant(ZoneOffset.UTC)

        // Format in strict SOQL required format: YYYY-MM-DDTHH:MM:SSZ
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

        val startOfDayStr = startOfDayUtc.format(formatter)
        val endOfDayStr = endOfDayUtc.format(formatter)

        return " AND CreatedDate >= $startOfDayStr AND CreatedDate < $endOfDayStr"
    }

    fun logFileContentRequest(logFileUrl: String): Request =
        Request(Method.GET, "${accessTokenHandler.instanceUrl}$logFileUrl")
            .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
            .header("Accept", "text/csv")

    // Used for health check of Application log
    fun fetchApplicationLogsForDateFromRest(logDate: LocalDate): Pair<Int, Int> {
        val soqlQuery = "SELECT CreatedDate, Log_Level__c, Application_Domain__c, Source_Class__c, Source_Function__c, UUID__c FROM Application_Log__c WHERE Log_Level__c IN ('Critical', 'Error')" +
            createdDateRestrictionExtension(logDate)
        val encodedQuery = URLEncoder.encode(soqlQuery, "UTF-8")
        var done = false
        var nextRecordsUrl = "/services/data/$apiVersion/query?q=$encodedQuery"

        val result: MutableList<AppRecordPartial> = mutableListOf()

        // Variables to count Critical and Error logs
        var criticalCount = 0
        var errorCount = 0

        while (!done) {
            val request = Request(Method.GET, accessTokenHandler.instanceUrl + nextRecordsUrl)
                .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
                .header("Accept", "application/json")
            val response = clientStreamingMode(request)

            File("/tmp/soqlQ").writeText(soqlQuery)
            File("/tmp/applogresp-$logDate").writeText(response.toMessage())

            if (response.status.successful) {
                val obj = JsonParser.parseString(response.bodyString()).asJsonObject

                val records = obj["records"].asJsonArray

                // Count Critical and Error logs
                for (record in records) {
                    val logLevel = record.asJsonObject["Log_Level__c"].asString
                    val appRecordPartial = Gson().fromJson(record, AppRecordPartial::class.java)
                    result.add(appRecordPartial)
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
        File("/tmp/appRecords-$logDate").writeText(Gson().toJson(result))
        return Pair(errorCount, criticalCount)
    }

    fun queryForAllEventTypes(): String {
        val soqlQuery = "SELECT EventType FROM EventLogFile GROUP BY EventType"
        val encodedQuery = URLEncoder.encode(soqlQuery, "UTF-8")

        val nextRecordsUrl = "/services/data/$apiVersion/query?q=$encodedQuery"

        val request = Request(Method.GET, accessTokenHandler.instanceUrl + nextRecordsUrl)
            .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
            .header("Accept", "application/json")
        val response = clientStreamingMode(request)
        File("/tmp/responseEventTypeQuery").writeText(response.toMessage())
        return response.bodyString()
    }

    fun countCsvRows(logFileRequest: Request): Pair<Int, Response> {
        return try {
            log.info { "Trigger countCsvRows" }
            val response = clientStreamingMode(logFileRequest)
            if (response.status.successful) {
                log.info { "Successful response countCsvRows" }
                val reader = response.body.stream.reader()
                val csvParser = CSVParser(reader, CSVFormat.DEFAULT.builder().setSkipHeaderRecord(true).setHeader().build())
                log.info { "Before count of countCsvRows" }
                var rowCount = 0
                for (record in csvParser) {
                    rowCount++
                }
                // val rowCount = csvParser.records.size // This counts the rows
                log.info { "countCsvRows result $rowCount" }
                csvParser.close()
                reader.close()
                Pair(rowCount, clientStreamingMode(logFileRequest)) // Renew fetch of response since count has used it up
            } else {
                log.error("Failed to fetch CSV data for counting rows: ${response.status}")
                throw IllegalStateException("Error counting CSV rows: ${response.status}")
            }
        } catch (e: Exception) {
            log.error("Error counting CSV rows: ${e.message}")
            throw IllegalStateException("Error counting CSV rows: ${e.message}")
        }
    }

    fun processCsvRows(response: Response, count: Int, date: LocalDate, eventType: EventType, skipToRow: Int): LogSyncStatus {
        try {
            TransferJob.goal = count
            if (response.status.successful) {
                val reader = response.body.stream.reader()
                val csvParser = CSVParser(reader, CSVFormat.DEFAULT.builder().setSkipHeaderRecord(true).setHeader().build())

                // Process each row as it’s read
                var logCounter = 0
                if (!local) {
                    PostgresDatabase.upsertLogSyncProgress(date, eventType.name, 0, count)
                }

                if (skipToRow > 1) {
                    log.info { "Will continue process $count events from position $skipToRow of type $eventType for $date" }
                } else {
                    log.info { "Will process $count events of type $eventType for $date" }
                }

                for (csvRecord in csvParser) {
                    logCounter++ // will start from 1

                    // Convert each record to a JSON object TODO not necessary - can skip transform later, only keep value cleaning
                    val event = JsonObject()
                    csvRecord.toMap().forEach { (key, value) ->
                        event.addProperty(key, if (value.isNullOrBlank()) null else value.trim('"'))
                    }

                    val logMessage = if (eventType.messageField.isNotBlank()) {
                        event[eventType.messageField]?.asString ?: "N/A"
                    } else {
                        // Locally - if no message field defined nor any metrics labels
                        // we want to see full event object to examine model of new event type
                        if (local && eventType.fieldsToUseAsMetricLabels.isEmpty()) event.toString() else "N/A"
                    }

                    if (logCounter >= skipToRow) {
                        if (eventType.messageField.isNotEmpty() || (local && eventType.fieldsToUseAsMetricLabels.isEmpty())) {
                            val nonSensitiveContext =
                                eventType.generateLoggingContext(
                                    eventData = event,
                                    excludeSensitive = true,
                                    logCounter,
                                    count
                                )
                            val fullContext = eventType.generateLoggingContext(
                                eventData = event,
                                excludeSensitive = false,
                                logCounter,
                                count
                            )

                            withLoggingContext(nonSensitiveContext) {
                                log.error(logMessage)
                            }

                            withLoggingContext(fullContext) {
                                log.error(SECURE, logMessage)
                            }
                        }

                        if (eventType.fieldsToUseAsMetricLabels.isNotEmpty()) {
                            try {
                                Metrics.eventLogCounters[eventType]!!
                                    .labels(
                                        *eventType.fieldsToUseAsMetricLabels.map {
                                            val strValue = event.fieldAsString(it)
                                            if (eventType.metricsFieldsToNormalizeURL.contains(it)) {
                                                Metrics.normalizeUrl(strValue)
                                            } else if (eventType.metricsFieldsToTimeBucket.contains(it)) {
                                                try {
                                                    strValue.toLong().toTimeLabel()
                                                } catch (e: Exception) {
                                                    "Not applicable"
                                                }
                                            } else if (eventType.metricsFieldsToSizeBucket.contains(it)) {
                                                try {
                                                    strValue.toLong().toSizeLabel()
                                                } catch (e: Exception) {
                                                    "Not applicable"
                                                }
                                            } else {
                                                event.fieldAsString(it)
                                            }
                                        }.toTypedArray() + event.fieldAsString(eventType.fieldToUseAsMetricDateLabel).substring(0, 10)
                                    ).inc()
                            } catch (e: Exception) {
                                log.warn { "Failed to populate and increment a metric of eventType $eventType: ${e.message}" }
                            }
                        }

                        if (!local) {
                            PostgresDatabase.upsertLogSyncProgress(date, eventType.name, logCounter, count)
                        }
                    }

                    TransferJob.progress = logCounter
                    if (eventType.messageField.isNotEmpty()) {
                        if (logCounter % 100 == 0) {
                            if (logCounter >= skipToRow) {
                                log.info { "Processed $logCounter of $count events" + (if (skipToRow > 1) " of which skipped first $skipToRow in current run" else "") }
                            } else {
                                log.info { "Skipped $logCounter of $count events" }
                            }
                            if (local || eventType.messageField.isEmpty()) Thread.sleep(20) else Thread.sleep(2000) // Pause for 2 seconds
                        }
                    }
                }
                csvParser.close()
                reader.close()
                log.info { "Finally processed $logCounter of $count events" + (if (skipToRow > 1) " of which skipped first $skipToRow in current run" else "") }
                val successState = createSuccessStatus(date, eventType, "Processed $count events of type ${eventType.name} for $date " + (if (skipToRow > 1) " (pickup from $skipToRow in current run)" else ""))
                if (!local) {
                    PostgresDatabase.upsertLogSyncStatus(successState)
                    PostgresDatabase.updateCache(successState)
                }
                return successState
            } else {
                log.error("Failed to fetch and process CSV data: ${response.status}")
                throw IllegalStateException("Failed to fetch and process CSV data: ${response.status}")
            }
        } catch (e: Exception) {
            log.error("Exception when fetching and process CSV data counting CSV rows: ${e.message}")
            throw IllegalStateException("Exception when fetching and process CSV data counting CSV rows: ${e.message}")
        }
    }

    fun limitRequest(): Request =
        Request(Method.GET, "${accessTokenHandler.instanceUrl}/services/data/v58.0/limits") // v59.0/limits
            .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")

    fun doLimitCall(): String {
        log.info { "Limit call triggered" }
        val request = limitRequest()
        val result = client.invoke(request)

        val jsonObject = JsonParser.parseString(result.bodyString()).asJsonObject
        for ((key, value) in jsonObject.entrySet()) {
            val type = key // e.g. "ActiveScratchOrgs"
            val obj = value.asJsonObject

            val max = obj["Max"].asDouble
            val remaining = obj["Remaining"].asDouble

            Metrics.limitGauge.labels(type).set(max)
            Metrics.limitRemainingGauge.labels(type).set(remaining)
        }
        return result.toMessage()
    }
}

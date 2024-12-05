package no.nav.sf.eventlog

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KotlinLogging
import no.nav.sf.eventlog.token.AccessTokenHandler
import no.nav.sf.eventlog.token.DefaultAccessTokenHandler
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.http4k.client.ApacheClient
import org.http4k.core.Method
import org.http4k.core.Request
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import java.io.File
import java.io.StringReader

class SalesforceClient(private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler()) {
    private val log = KotlinLogging.logger { }

    private val SECURE: Marker = MarkerFactory.getMarker("SECURE_LOG")

    private val apiVersion = env(config_SALESFORCE_API_VERSION)

    private val client = ApacheClient()

    fun fetchEventLog(eventType: EventType) {
        val soqlQuery = "SELECT Id, EventType, LogFile, LogDate FROM EventLogFile WHERE EventType='${eventType.name}'"
        val encodedQuery = soqlQuery.replace(" ", "+") // URL-encode the query

        var done = false
        var nextRecordsUrl = "/services/data/$apiVersion/query?q=$encodedQuery"

        val logFiles: MutableList<String> = mutableListOf()

        while (!done) {
            val request = Request(Method.GET, accessTokenHandler.instanceUrl + nextRecordsUrl)
                .header("Authorization", "Bearer ${accessTokenHandler.accessToken}")
                .header("Accept", "application/json")
            val response = client(request)
            if (response.status.successful) {
                val obj = JsonParser.parseString(response.bodyString()).asJsonObject
                val recordEntries = obj["records"].asJsonArray
                logFiles.addAll(recordEntries.map { it.asJsonObject["LogFile"].asString })
                val totalSize = obj["totalSize"].asInt
                log.info { "Fetched ${logFiles.count()} of $totalSize log files for ${eventType.name}" }
                done = obj["done"].asBoolean
                if (!done) nextRecordsUrl = obj["nextRecordsUrl"].asString
            } else {
                log.error { "Failed to fetch EventLogFiles of type ${eventType.name} - response ${response.status.code}:${response.bodyString()}" }
                done = true
            }
        }
        val first = logFiles.first()
        val capturedEvents = fetchLogFileContentAsJson(first)
        log.info { "Will log ${capturedEvents.size} events to secure logs" }
        capturedEvents.forEach {
            log.error(
                SECURE,
                "Example forwarded message from first file: " +
                    it["EXCEPTION_TYPE"].asString +
                    ":" +
                    it["EXCEPTION_MESSAGE"].asString
            )
        }
    }

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
                jsonObject.addProperty(key, if (value.isNullOrBlank()) null else value)
            }

            result.add(jsonObject)
        }
        csvParser.close()
        reader.close()

        return result
    }
}

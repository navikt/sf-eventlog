package no.nav.sf.eventlog

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.opencsv.CSVReader
import java.io.StringReader
import java.lang.Math.abs
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * CompareAppLogsUtilityTest
 *
 * How to perform manual healthcheck:
 * Place response body from SOQL (from pod file /tmp/appRecords-<logDate> when visiting "/internal/applogfetch?date=<logDate>")
 * in a file in resource folder that you point to with JSON_ARRAY_FROM_SOQL below
 *
 * Export the logs from adeo secure logs for the same day to a csv, point to it with CSV_FROM_ADEO
 * Use for instance
 * https://logs.adeo.no/app/discover#/?_g=(filters:!(),refreshInterval:(pause:!t,value:60000),time:(from:'2025-03-16T23:00:00.000Z',to:'2025-03-17T23:00:00.000Z'))&_a=(columns:!(message,envclass,x_Log_Level__c,x_TIMESTAMP_DERIVED,x_Application_Domain__c,x_Source_Class__c,x_Source_Function__c,x_UUID__c),dataSource:(dataViewId:'tjenestekall-*',type:dataView),filters:!(),hideChart:!f,interval:auto,query:(language:kuery,query:'application:%20sf-pubsub-application-event%20AND%20envclass:%20p'),sort:!(!('@timestamp',desc)))
 * and adjust date
 *
 * Uncomment @Test below and run, disable after use
 */
class CompareAppLogsUtilityTest {

    val CSV_FROM_ADEO = "/Scratch_logadeo17mars.csv"
    val JSON_ARRAY_FROM_SOQL = "/Scratch_records17mars.json"

    // @Test
    fun `add test cases`() {

        val gson = GsonBuilder()
            .registerTypeAdapter(LocalDateTime::class.java, LocalDateTimeFromSFStampDeserializer())
            .registerTypeAdapter(LocalDateTime::class.java, LocalDateTimeSerializer())
            .create()

        val csvLogAdeo = readResourceFile(CSV_FROM_ADEO)

        val jsonRecords = readResourceFile(JSON_ARRAY_FROM_SOQL)

        val logAdeoListFromCSV: List<AppRecordPartialLocalDate> = parseCsvLogs(csvLogAdeo)
        val secondJsonArray = JsonParser.parseString(jsonRecords).asJsonArray
        val recordsList: List<AppRecordPartialLocalDate> = gson.fromJson(secondJsonArray, Array<AppRecordPartialLocalDate>::class.java).toList()

        // Match function
        fun recordsMatch(r1: AppRecordPartialLocalDate, r2: AppRecordPartialLocalDate): Boolean {
            // Helper function to treat null and "-" as an empty string
            fun compareNullAsEmpty(value: String?): String {
                return value.let { if (it == "-") "" else value } ?: ""
            }

            // Compare fields, treating null as empty string
            val timeDiff = abs(r1.CreatedDate.toEpochSecond(ZoneOffset.UTC) - r2.CreatedDate.toEpochSecond(ZoneOffset.UTC))
            return compareNullAsEmpty(r1.Log_Level__c) == compareNullAsEmpty(r2.Log_Level__c) &&
                compareNullAsEmpty(r1.Application_Domain__c) == compareNullAsEmpty(r2.Application_Domain__c) &&
                compareNullAsEmpty(r1.Source_Class__c) == compareNullAsEmpty(r2.Source_Class__c) &&
                compareNullAsEmpty(r1.UUID__c) == compareNullAsEmpty(r2.UUID__c) &&
                timeDiff <= 120 // Within 2 minutes
        }

        // Compare records
        var matches = 0
        val mismatchesList = mutableListOf<AppRecordPartialLocalDate>()

        logAdeoListFromCSV.forEach { record1 ->
            val matchFound = recordsList.any { record2 -> recordsMatch(record1, record2) }
            if (matchFound) {
                matches++
            } else {
                mismatchesList.add(record1)
            }
        }

        // Print statistics
        println("Logadeo array size: ${logAdeoListFromCSV.size}")
        println("Records array size: ${recordsList.size}")
        println("Matches: $matches")
        println("Mismatches: ${mismatchesList.size} (should be 0)")

        // Print mismatched records
        if (mismatchesList.isNotEmpty()) {
            println("\nMismatched Records:")
            mismatchesList.forEach { println(it) }
        }

        // Compare records
        var matches2 = 0
        val mismatchesList2 = mutableListOf<AppRecordPartialLocalDate>()

        recordsList.forEach { record1 ->
            val matchFound = logAdeoListFromCSV.any { record2 -> recordsMatch(record1, record2) }
            if (matchFound) {
                matches2++
            } else {
                mismatchesList2.add(record1)
            }
        }

        // Print statistics
        println("Logadeo array size: ${logAdeoListFromCSV.size}")
        println("Records array size: ${recordsList.size}")
        println("Matches: $matches2")
        println("Mismatches: ${mismatchesList2.size}")

        // Print mismatched records
        if (mismatchesList2.isNotEmpty()) {
            println("\nMismatched Records:")
            mismatchesList2.forEach { println(gson.toJson(it)) }
        }

        val groupedRecords = mismatchesList2.groupBy {
            Triple(it.Application_Domain__c, it.Source_Class__c, it.Source_Function__c)
        }

        println("Reference value: ${mismatchesList.size} (should be 0)")

        println("Total logs not reported via event: " + mismatchesList2.size + " of total " + recordsList.size)
        // Print statistics for each group
        groupedRecords.forEach { (key, list) ->
            val (domain, sourceClass, sourceFunction) = key
            println("Application_Domain__c: $domain, Source_Class__c: $sourceClass, Source_Function__c: $sourceFunction - Count: ${list.size}")
        }
    }
}

fun parseCsvLogs(csvContent: String): List<AppRecordPartialLocalDate> {
    val reader = CSVReader(StringReader(csvContent))
    val lines = reader.readAll()

    val header = lines[0] // First row is the header
    val columnIndexMap = header.withIndex().associate { it.value to it.index } // Map column names to indices

    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy @ HH:mm:ss.SSS") // Adjust if necessary
    val records = mutableListOf<AppRecordPartialLocalDate>()

    // Skip the header row
    for (i in 1 until lines.size) {
        val row = lines[i]

        val createdDateStr = row[columnIndexMap["x_TIMESTAMP_DERIVED"] ?: continue]
        val uuid = row[columnIndexMap["x_UUID__c"] ?: continue]
        val sourceClass = row[columnIndexMap["x_Source_Class__c"] ?: continue]
        val sourceFunction = row[columnIndexMap["x_Source_Function__c"] ?: continue]
        val applicationDomain = row[columnIndexMap["x_Application_Domain__c"] ?: continue]
        val logLevel = row[columnIndexMap["x_Log_Level__c"] ?: continue]

        val createdDate = LocalDateTime.parse(createdDateStr, formatter)
        records.add(AppRecordPartialLocalDate(createdDate, logLevel, applicationDomain, sourceClass, sourceFunction, uuid))
    }
    return records
}

fun readResourceFile(path: String) = CompareAppLogsUtilityTest::class.java.getResource(path).readText()

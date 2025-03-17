package no.nav.sf.eventlog

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.opencsv.CSVReader
import java.io.StringReader
import java.lang.Math.abs
import java.lang.reflect.Type
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class TemplateTest {

    class LocalDateTimeDeserializer : JsonDeserializer<LocalDateTime> {
        val formatterZ = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX") // Handles "Z"
        val formatterOffset = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX") // Handles "+0000"

        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): LocalDateTime {
            val dateStr = json.asString
            return (
                if (dateStr.endsWith("+0000")) {
                    LocalDateTime.parse(dateStr, formatterOffset)
                } else {
                    LocalDateTime.parse(dateStr, formatterZ)
                }
                )
        }
    }

    class LocalDateTimeSerializer : JsonSerializer<LocalDateTime> {
        override fun serialize(src: LocalDateTime, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            // Just call toString() to get the default ISO-8601 format
            return JsonPrimitive(src.toString())
        }
    }

    // Define a record structure
    data class Record(
        val CreatedDate: LocalDateTime,
        val Log_Level__c: String,
        val Application_Domain__c: String,
        val Source_Class__c: String,
        val Source_Function__c: String,
        val UUID__c: String?
    )

    fun readResourceFile(path: String) = TemplateTest::class.java.getResource(path).readText()

    fun parseCsvLogs(csvContent: String): List<Record> {
        val reader = CSVReader(StringReader(csvContent))
        val lines = reader.readAll()
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy @ HH:mm:ss.SSS") // CSV date format
        val records = mutableListOf<Record>()

        // Skip the header row
        for (i in 1 until lines.size) {
            val row = lines[i]

            val createdDateStr = row[7] // "x_TIMESTAMP_DERIVED"
            val logLevel = row[11] // "x_Log_Level__c"
            val applicationDomain = row[10] // "x_Application_Domain__c"
            val sourceClass = row[8] // "x_Source_Class__c"
            val sourceFunction = row[9]
            val uuid = row[6] // "x_UUID__c"

            val createdDate = LocalDateTime.parse(createdDateStr, formatter).minusHours(1)
            records.add(Record(createdDate, logLevel, applicationDomain, sourceClass, sourceFunction, uuid))
        }
        return records
    }

    // @Test
    fun `add test cases`() {

        val gson = GsonBuilder()
            // Register custom deserializer for LocalDateTime
            .registerTypeAdapter(LocalDateTime::class.java, LocalDateTimeDeserializer())
            // Register custom serializer for LocalDateTime
            .registerTypeAdapter(LocalDateTime::class.java, LocalDateTimeSerializer())
            .create()

        val jsonLogAdeo = readResourceFile("/Scratch_logadeo9mars.json")

        val csvLogAdeo = readResourceFile("/Scratch_logadeo9mars.csv")

        val jsonRecords = readResourceFile("/Scratch_records9mars.json")

        // Parse JSON array
        val jsonArray = JsonParser.parseString(jsonLogAdeo).asJsonArray
        val transformedList = jsonArray.mapNotNull { element ->
            val obj = element.asJsonObject
            val fields = obj.getAsJsonObject("fields")

            // Extract and flatten single-element arrays
            val createdDate = fields.getAsJsonArray("x_TIMESTAMP_DERIVED")?.firstOrNull()?.asString ?: ""
            val logLevel = fields.getAsJsonArray("x_Log_Level__c")?.firstOrNull()?.asString ?: ""
            val applicationDomain = fields.getAsJsonArray("x_Application_Domain__c")?.firstOrNull()?.asString ?: ""
            val sourceClass = fields.getAsJsonArray("x_Source_Class__c")?.firstOrNull()?.asString ?: ""
            val uuid = fields.getAsJsonArray("x_UUID__c")?.firstOrNull()?.asString ?: ""

            // Create transformed object
            JsonObject().apply {
                addProperty("CreatedDate", createdDate)
                addProperty("Log_Level__c", logLevel)
                addProperty("Application_Domain__c", applicationDomain)
                addProperty("Source_Class__c", sourceClass)
                addProperty("UUID__c", uuid)
            }
        }

        // Convert back to JSON
        val transformedJson = gson.toJson(transformedList) // GsonBuilder().setPrettyPrinting().create().toJson(transformedList)

        // Print or save the output
        println(transformedJson)

        // val gson = Gson()
        // Parse JSON arrays
        // val logAdeoList: List<Record> = gson.fromJson(transformedJson, Array<Record>::class.java).toList()
        val logAdeoListFromCSV: List<Record> = parseCsvLogs(csvLogAdeo)
        val secondJsonObj = JsonParser.parseString(jsonRecords).asJsonObject
        val recordsList: List<Record> = gson.fromJson(secondJsonObj.getAsJsonArray("records"), Array<Record>::class.java).toList()

        // Match function
        fun recordsMatch(r1: Record, r2: Record): Boolean {
            // Helper function to treat null as an empty string
            fun compareNullAsEmpty(value: String?): String {
                return value.let { if (it == "-") "" else value } ?: ""
            }

            // Compare fields, treating null as empty string
            val timeDiff = abs(r1.CreatedDate.toEpochSecond(ZoneOffset.UTC) - r2.CreatedDate.toEpochSecond(ZoneOffset.UTC))
            return compareNullAsEmpty(r1.Log_Level__c) == compareNullAsEmpty(r2.Log_Level__c) &&
                compareNullAsEmpty(r1.Application_Domain__c) == compareNullAsEmpty(r2.Application_Domain__c) &&
                compareNullAsEmpty(r1.Source_Class__c) == compareNullAsEmpty(r2.Source_Class__c) &&
                compareNullAsEmpty(r1.UUID__c) == compareNullAsEmpty(r2.UUID__c) &&
                timeDiff <= 60 // Within 1 minute
        }

        // Compare records
        var matches = 0
        val mismatchesList = mutableListOf<Record>()

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
        println("Mismatches: ${mismatchesList.size}")

        // Print mismatched records
        if (mismatchesList.isNotEmpty()) {
            println("\nMismatched Records:")
            mismatchesList.forEach { println(it) }
        }

        // Compare records
        var matches2 = 0
        val mismatchesList2 = mutableListOf<Record>()

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
        println("end ")
    }
}

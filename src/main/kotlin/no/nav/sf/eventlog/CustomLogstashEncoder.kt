package no.nav.sf.eventlog

import ch.qos.logback.classic.spi.ILoggingEvent
import com.google.gson.JsonParser
import net.logstash.logback.encoder.LogstashEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CustomLogstashEncoder : LogstashEncoder() {

    override fun encode(event: ILoggingEvent): ByteArray {
        // Call the original encode method to generate JSON
        val originalJsonBytes = super.encode(event)
        val originalJson = String(originalJsonBytes, StandardCharsets.UTF_8)

        // Parse the JSON and add/replace fields
        val jsonObject = JsonParser.parseString(originalJson).asJsonObject

        // Access TIMESTAMP_DERIVED from MDC and replace @timestamp
        val timestampDerived = event.mdcPropertyMap[FIELD_TO_REPLACE_TIMESTAMP]
        if (timestampDerived != null) {
            val parsedTimestamp = ZonedDateTime.parse(timestampDerived)
            val adjustedTimestamp = parsedTimestamp.withZoneSameInstant(ZoneOffset.ofHours(1)) // Adjust to +01:00
            jsonObject.addProperty(FIELD_TO_STORE_EVENT_LOG_TIMESTAMP_ON_REPLACEMENT, jsonObject["@timestamp"].asString)
            jsonObject.addProperty("@timestamp", adjustedTimestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }

        jsonObject.addProperty("message", "Mod.v02 " + jsonObject["message"].toString())

        // Return the modified JSON as a byte array
        return (jsonObject.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
    }
}

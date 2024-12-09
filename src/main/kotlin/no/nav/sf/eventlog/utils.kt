package no.nav.sf.eventlog

import com.google.gson.JsonObject
import mu.KotlinLogging
import org.slf4j.MDC
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val log = KotlinLogging.logger { }

val SECURE: Marker = MarkerFactory.getMarker("SECURE_LOG")

val currentDateTime: String get() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

inline fun <T> withDerivedTimestamp(eventData: JsonObject, body: () -> T): T {
    val timestampKey = "timestampDerivedTranslated"
    val derivedTimestamp = eventData["TIMESTAMP_DERIVED"]?.asString?.let {
        val parsedTimestamp = ZonedDateTime.parse(it)
        val adjustedTimestamp = parsedTimestamp.withZoneSameInstant(ZoneOffset.ofHours(1))
        adjustedTimestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
    try {
        // log.info { "Setting $timestampKey to $derivedTimestamp" }
        if (derivedTimestamp != null) MDC.put(timestampKey, derivedTimestamp)
        return body() // Execute the given function
    } finally {
        MDC.remove(timestampKey)
    }
}

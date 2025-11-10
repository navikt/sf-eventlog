package no.nav.sf.eventlog

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalDateTimeFromSFStampDeserializer : JsonDeserializer<LocalDateTime> {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX") // Handles "+0000"

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): LocalDateTime {
        val dateStr = json.asString
        val zonedDateTime =
            OffsetDateTime
                .parse(dateStr, formatter)
                .atZoneSameInstant(ZoneId.of("Europe/Oslo")) // Convert to Oslo time
        val localDateTime = zonedDateTime.toLocalDateTime()
        return localDateTime
    }
}

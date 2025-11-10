@file:Suppress("ktlint:standard:filename")

package no.nav.sf.eventlog

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import mu.KotlinLogging
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import java.lang.reflect.Type
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val log = KotlinLogging.logger { }

val SECURE: Marker = MarkerFactory.getMarker("SECURE_LOG")

// Custom serializer/deserializer for LocalDate
class LocalDateAdapter :
    JsonSerializer<LocalDate>,
    JsonDeserializer<LocalDate> {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

    override fun serialize(
        src: LocalDate,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonPrimitive(src.format(formatter))

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): LocalDate = LocalDate.parse(json.asString, formatter)
}

// Custom serializer/deserializer for LocalDateTime
class LocalDateTimeAdapter :
    JsonSerializer<LocalDateTime>,
    JsonDeserializer<LocalDateTime> {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss") // Adjusted formatter

    override fun serialize(
        src: LocalDateTime,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement = JsonPrimitive(src.format(formatter))

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): LocalDateTime = LocalDateTime.parse(json.asString, formatter)
}

// Configure Gson with custom adapters
fun configureGson(): Gson =
    GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateAdapter())
        .registerTypeAdapter(LocalDateTime::class.java, LocalDateTimeAdapter())
        .setPrettyPrinting()
        .create()

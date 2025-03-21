package no.nav.sf.eventlog

import com.google.gson.JsonObject

enum class EventType(
    // For logging
    val messageField: String = "", // Empty means do not log at all
    val insensitiveFields: List<String> = listOf(),
    val sensitiveFields: List<String> = listOf(),

    // For metrics
    val fieldsToUseAsMetricLabels: List<String> = listOf(),
    val metricsFieldsToNormalizeURL: List<String> = listOf(),
    val metricsFieldsToTimeBucket: List<String> = listOf(),
    val metricsFieldsToSizeBucket: List<String> = listOf(),

    // Note: locally when setting up new EventType, just leave fields empty to examine model
) {
    // ApexUnexpectedException,
    // ApexCallout
    ApexUnexpectedException(
        messageField = "EXCEPTION_MESSAGE",
        insensitiveFields = listOf(
            "EVENT_TYPE",
            "TIMESTAMP",
            "TIMESTAMP_DERIVED",
            "REQUEST_ID",
            "ORGANIZATION_ID",
            "EXCEPTION_TYPE",
            "EXCEPTION_CATEGORY"
        ),
        sensitiveFields = listOf(
            "STACK_TRACE",
            "USER_ID",
            "USER_ID_DERIVED"
        ),
        fieldsToUseAsMetricLabels = listOf(
            "EVENT_TYPE",
            "EXCEPTION_TYPE",
            "EXCEPTION_CATEGORY"
        )
    ),
    ApexCallout(
        fieldsToUseAsMetricLabels = listOf(
            "EVENT_TYPE",
            "RUN_TIME",
            "TIME",
            "CPU_TIME",
            "URL",
            "TYPE",
            "METHOD",
            "SUCCESS",
            "STATUS_CODE",
            "REQUEST_SIZE",
            "RESPONSE_SIZE"
        ),
        metricsFieldsToNormalizeURL = listOf("URL"),
        metricsFieldsToSizeBucket = listOf("REQUEST_SIZE", "RESPONSE_SIZE"),
        metricsFieldsToTimeBucket = listOf("RUN_TIME", "TIME", "CPU_TIME"),
    )
}

fun EventType.generateLoggingContext(eventData: JsonObject, excludeSensitive: Boolean, rowNumber: Int, batchSize: Int): Map<String, String> {
    return (this.insensitiveFields + if (excludeSensitive) listOf() else sensitiveFields)
        .associateWith { key ->
            eventData.fieldAsString(key)
        } + ("ROW_NUMBER" to rowNumber.toString()) +
        ("BATCH_SIZE" to batchSize.toString())
}

fun JsonObject.fieldAsString(key: String): String {
    val value = this[key]
    return if (value.isJsonNull) "" else value.asString
}

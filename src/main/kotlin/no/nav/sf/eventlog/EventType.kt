package no.nav.sf.eventlog

import com.google.gson.JsonObject

enum class EventType(
    val messageField: String,
    val insensitiveFields: List<String>,
    val sensitiveFields: List<String>,
    val fieldsToUseAsMetricLabels: List<String>,
    val fieldToUseAsEventTime: String
) {
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
        ),
        fieldToUseAsEventTime = "TIMESTAMP_DERIVED"
    ),
//    FlowExecution(
//        messageField = "",
//        insensitiveFields = listOf(),
//        sensitiveFields = listOf()
//    )
}

fun EventType.generateLoggingContext(eventData: JsonObject, excludeSensitive: Boolean, rowNumber: Int, batchSize: Int, fieldToUseAsEventTime: String): Map<String, String> {
    return (this.insensitiveFields + if (excludeSensitive) listOf() else sensitiveFields)
        .associateWith { key ->
            val value = eventData[key]
            if (value.isJsonNull) "" else value.asString
        } + ("ROW_NUMBER" to rowNumber.toString()) +
        ("BATCH_SIZE" to batchSize.toString()) +
        ("event.start" to fieldToUseAsEventTime)
}

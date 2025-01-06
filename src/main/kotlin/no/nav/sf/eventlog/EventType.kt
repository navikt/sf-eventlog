package no.nav.sf.eventlog

import com.google.gson.JsonObject

enum class EventType(
    val messageField: String,
    val insensitiveFields: List<String>,
    val sensitiveFields: List<String>
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
        )
    ),
//
//    FlowExecution(
//        messageField = "",
//        insensitiveFields = listOf(),
//        sensitiveFields = listOf()
//    );
}

fun EventType.generateLoggingContext(eventData: JsonObject, excludeSensitive: Boolean): Map<String, String> {
    return (this.insensitiveFields + if (excludeSensitive) listOf() else sensitiveFields)
        .associateWith { eventData[it].asString ?: "N/A" }
}

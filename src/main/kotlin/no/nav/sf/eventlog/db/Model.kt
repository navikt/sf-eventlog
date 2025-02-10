package no.nav.sf.eventlog.db

import no.nav.sf.eventlog.EventType
import no.nav.sf.eventlog.application
import no.nav.sf.eventlog.db.LogSyncStatusTable.defaultExpression
import no.nav.sf.eventlog.local
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDate
import java.time.LocalDateTime

data class LogSyncStatus(
    val syncDate: LocalDate,
    val eventType: String,
    val status: String,
    val message: String,
    val lastModified: LocalDateTime
)

object LogSyncStatusTable : Table("log_sync_status") {
    val syncDate = date("sync_date")
    val eventType = varchar("event_type", 36)
    val status = varchar("status", 20)
    val message = text("message")
    val lastModified = datetime("last_modified").defaultExpression(CurrentDateTime) // TIMESTAMP NOT NULL DEFAULT NOW()

    init {
        uniqueIndex(syncDate, eventType) // Enforces unique combinations of syncDate and eventType
    }
}

fun ResultRow.toLogSyncStatus() = LogSyncStatus(
    syncDate = this[LogSyncStatusTable.syncDate],
    eventType = this[LogSyncStatusTable.eventType],
    status = this[LogSyncStatusTable.status],
    message = this[LogSyncStatusTable.message],
    lastModified = this[LogSyncStatusTable.lastModified]
)

data class LogSyncProgress(
    val syncDate: LocalDate,
    val eventType: String,
    val progress: Int,
    val goal: Int,
    val lastModified: LocalDateTime
)

object LogSyncProgressTable : Table("log_sync_progress") {
    val syncDate = date("sync_date")
    val eventType = varchar("event_type", 36)
    val progress = integer("progress")
    val goal = integer("goal")
    val lastModified = datetime("last_modified").defaultExpression(CurrentDateTime) // TIMESTAMP NOT NULL DEFAULT NOW()

    init {
        uniqueIndex(LogSyncStatusTable.syncDate, LogSyncStatusTable.eventType) // Enforces unique combinations of syncDate and eventType
    }
}

fun ResultRow.toLogSyncProgress() = LogSyncProgress(
    syncDate = this[LogSyncProgressTable.syncDate],
    eventType = this[LogSyncProgressTable.eventType],
    progress = this[LogSyncProgressTable.progress],
    goal = this[LogSyncProgressTable.goal],
    lastModified = this[LogSyncProgressTable.lastModified]
)

fun retrieveLogSyncStatusesAsMapMock(): MutableMap<EventType, MutableMap<LocalDate, LogSyncStatus>> {
    // Example hardcoded data
    val logSyncStatusList = listOf(
        LogSyncStatus(
            syncDate = LocalDate.of(2024, 12, 1),
            eventType = "ApexUnexpectedException",
            status = "SUCCESS",
            message = "Sync started successfully. A",
            lastModified = LocalDateTime.of(2024, 12, 1, 10, 0, 0, 0)
        ),
//        LogSyncStatus(
//            syncDate = LocalDate.of(2024, 12, 2),
//            eventType = "FlowExecution",
//            status = "FAILURE",
//            message = "Sync failed due to an error. B",
//            lastModified = LocalDateTime.of(2024, 12, 2, 12, 30, 0, 0)
//        ),
        LogSyncStatus(
            syncDate = LocalDate.of(2024, 12, 3),
            eventType = "ApexUnexpectedException",
            status = "SUCCESS",
            message = "Sync started successfully. C",
            lastModified = LocalDateTime.of(2024, 12, 3, 9, 0, 0, 0)
        ),
//        LogSyncStatus(
//            syncDate = LocalDate.of(2024, 12, 3),
//            eventType = "FlowExecution",
//            status = "SUCCESS",
//            message = "Sync completed successfully. D",
//            lastModified = LocalDateTime.of(2024, 12, 3, 12, 0, 0, 0)
//        ),
//        LogSyncStatus(
//            syncDate = LocalDate.of(2024, 9, 3),
//            eventType = "FlowExecution",
//            status = "SUCCESS",
//            message = "Old success E",
//            lastModified = LocalDateTime.of(2024, 12, 3, 12, 0, 0, 0)
//        )
    )

    // Group by eventType and convert to a map with EventType as the key
    return logSyncStatusList
        .groupBy { EventType.valueOf(it.eventType) } // Group by EventType
        .mapValues { entry ->
            // For each entry, convert the List<LogSyncStatus> into a MutableMap<LocalDate, LogSyncStatus>
            entry.value
                .sortedByDescending { it.syncDate } // Sort by syncDate
                .associateBy { it.syncDate } // Associate by syncDate to create the inner map
                .toMutableMap() // Convert to a mutable map
        }
        .toMutableMap()
}

fun getMetaData(): String {
    val logSyncStatuses = if (local) retrieveLogSyncStatusesAsMapMock() else PostgresDatabase.logSyncStatusMap
    val logFileDataMap = application.salesforceClient.logFileDataMap

    // Get today's date and calculate the last 30 days
    val now = LocalDateTime.now()
    val last30Days: List<LocalDate> = (0 until 30).map { now.minusDays(it.toLong()).toLocalDate() }

    // Prepare the map to hold the final structured data
    val result = mutableMapOf<EventType, List<LogSyncStatus>>()

    // For each eventType, process the data
    logFileDataMap.forEach { (eventType, logFileData) ->
        val eventLogSyncStatuses = logSyncStatuses[eventType] ?: mutableMapOf()

        val olderLogSyncStatuses = eventLogSyncStatuses.entries
            .filter { it.key !in last30Days }
            .map { it.value }
            .sortedByDescending { it.syncDate }

        // Combine existing LogSyncStatus with synthetic entries for gaps in the last 30 days
        val logSyncDataForEvent = last30Days.map { date ->
            eventLogSyncStatuses[date] // No change to existing Status
                ?: if (logFileData.any { it.date == date }) {
                    // If no log exists but the log file exists for this date, generate a synthetic log
                    createUnprocessedStatus(date, eventType)
                } else {
                    // If no log file exists for this date, generate a synthetic log
                    createNoLogfileStatus(date, eventType)
                }
        }

        // Add the generated data to the result map, making sure to keep logs that are older than 30 days
        result[eventType] = (olderLogSyncStatuses + logSyncDataForEvent)
            .sortedByDescending { it.syncDate } // Ensure the final list is sorted by syncDate
    }

    // Convert the result map to JSON using Gson
    return application.gson.toJson(result)
}

fun createNoLogfileStatus(date: LocalDate, eventType: EventType) =
    LogSyncStatus(
        syncDate = date,
        eventType = eventType.name,
        status = "NO_LOGFILE",
        message = if (date == LocalDate.now()) "No log file for today has been generated yet" else "No log file exists for date $date",
        lastModified = LocalDateTime.now()
    )

fun createUnprocessedStatus(date: LocalDate, eventType: EventType) =
    LogSyncStatus(
        syncDate = date,
        eventType = eventType.name,
        status = "UNPROCESSED",
        message = "Not yet processed",
        lastModified = LocalDateTime.now()
    )

fun createProcessingStatus(date: LocalDate, eventType: EventType) =
    LogSyncStatus(
        syncDate = date,
        eventType = eventType.name,
        status = "PROCESSING",
        message = "Processing",
        lastModified = LocalDateTime.now()
    )

fun createFailureStatus(date: LocalDate, eventType: EventType, message: String) =
    LogSyncStatus(
        syncDate = date,
        eventType = eventType.name,
        status = "FAILURE",
        message = message,
        lastModified = LocalDateTime.now()
    )

fun createSuccessStatus(date: LocalDate, eventType: EventType, message: String) =
    LogSyncStatus(
        syncDate = date,
        eventType = eventType.name,
        status = "SUCCESS",
        message = message,
        lastModified = LocalDateTime.now()
    )

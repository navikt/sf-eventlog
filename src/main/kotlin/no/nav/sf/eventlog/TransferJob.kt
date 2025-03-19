package no.nav.sf.eventlog

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.sf.eventlog.db.LogSyncStatus
import no.nav.sf.eventlog.db.createFailureStatus
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status
import java.lang.Exception
import java.lang.IllegalStateException
import java.time.LocalDate

object TransferJob {
    var active = false
    var transferDate = LocalDate.MIN
    var transferEventType = EventType.ApexUnexpectedException
    var status: LogSyncStatus? = null
    var progress: Int = 0
    var goal: Int = 0

    fun activateTransferJob(date: LocalDate, eventType: EventType, skipToRow: Int = 1) {
        if (active) throw IllegalStateException("Cannot activate new job transfer since one is already active")
        active = true
        status = null
        GlobalScope.launch {
            runTransferJob(date, eventType, skipToRow)
        }
    }

    fun runTransferJob(date: LocalDate, eventType: EventType, skipToRow: Int = 1) {
        transferDate = date
        transferEventType = eventType
        status = try {
            application.salesforceClient.fetchAndProcessEventLogs(eventType, date, skipToRow)
        } catch (e: Exception) {
            createFailureStatus(date, eventType, "Exception " + e.message + " : " + e.stackTraceToString())
        } finally {
            active = false
            goal = 0
            progress = 0
        }
    }

    val statusHandler: HttpHandler = {
        val eventType = EventType.valueOf(it.query("eventType")!!)
        val date = LocalDate.parse(it.query("date"))
        pollStatus(date, eventType)
    }

    fun pollStatus(date: LocalDate, eventType: EventType): Response {
        return if (eventType == transferEventType && date == transferDate) {
            if (!active && status != null) {
                Response(Status.OK).body(application.gson.toJson(status))
            } else if (active) {
                if (goal == 0) {
                    Response(Status.ACCEPTED).body("Preparing transfer")
                } else {
                    Response(Status.ACCEPTED).body("$progress of $goal")
                }
            } else {
                Response(Status.FORBIDDEN).body("Forbidden state - done without status")
            }
        } else {
            Response(Status.BAD_REQUEST).body("Not set to transfer mode for given eventType ${eventType.name} and date $date")
        }
    }
}

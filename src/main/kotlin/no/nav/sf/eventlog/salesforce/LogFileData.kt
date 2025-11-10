package no.nav.sf.eventlog.salesforce

import java.time.LocalDate

data class LogFileData(
    val date: LocalDate,
    val file: String,
)

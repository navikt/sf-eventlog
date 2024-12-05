package no.nav.sf.eventlog

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val currentDateTime: String get() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

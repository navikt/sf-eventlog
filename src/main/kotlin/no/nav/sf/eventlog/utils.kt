package no.nav.sf.eventlog

import org.slf4j.Marker
import org.slf4j.MarkerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val SECURE: Marker = MarkerFactory.getMarker("SECURE_LOG")

val currentDateTime: String get() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

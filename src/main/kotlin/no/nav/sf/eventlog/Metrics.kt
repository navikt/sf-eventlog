package no.nav.sf.eventlog

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import mu.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status
import java.io.StringWriter

object Metrics {
    private val log = KotlinLogging.logger { }

    val cRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry

    val fetchedLogs = registerLabelCounter("fetched_logs", "event_type")

    val eventLogCounters: Map<EventType, Counter>

    fun clearEventLogCounter(eventType: EventType) = eventLogCounters[eventType]?.clear()

    fun registerSummary(name: String) = Summary.build().name(name).help(name).register()

    fun registerGauge(name: String) =
        Gauge.build().name(name).help(name).register()

    fun registerLabelGauge(name: String, vararg labels: String) =
        Gauge.build().name(name).help(name).labelNames(*labels).register()

    fun registerLabelCounter(name: String, vararg labels: String) =
        Counter.build().name(name).help(name).labelNames(*labels).register()

    /**
     * Mask common path variables to avoid separate counts for paths with varying segments.
     */
    fun mask(path: String): String =
        path.replace(Regex("/\\d+"), "/{id}")
            .replace(Regex("/[A-Z]\\d{4,}"), "/{ident}")
            .replace(Regex("/[^/]+\\.(xml|pdf)$"), "/{filename}")
            .replace(Regex("/[A-Z]{3}(?=/|$)"), "/{code}")

    fun normalizeUrl(url: String): String {
        val urlWithoutQuery = url.substringBefore("?") // Remove query parameters

        return if (urlWithoutQuery.startsWith("http")) {
            val uri = java.net.URI(urlWithoutQuery)
            if (uri.host == "hooks.slack.com") {
                uri.host
            } else {
                "${uri.host}${mask(uri.path)}"
            }
        } else if (urlWithoutQuery.startsWith("callout:")) {
            "callout:" + mask(urlWithoutQuery.removePrefix("callout:"))
        } else {
            urlWithoutQuery
        }
    }

    fun Long.toTimeLabel(): String = when {
        this < 10 -> "< 10 ms"
        this < 50 -> "< 50 ms"
        this < 100 -> "< 100 ms"
        this < 500 -> "< 500 ms"
        this < 1000 -> "< 1s"
        this < 5000 -> "< 5s"
        this < 10000 -> "< 10s"
        else -> "> 10s"
    }

    fun Long.toSizeLabel(): String = when {
        this < 1024 -> "< 1 KB"
        this < 10 * 1024 -> "< 10 KB"
        this < 100 * 1024 -> "< 100 KB"
        this < 1024 * 1024 -> "< 1 MB"
        this < 10 * 1024 * 1024 -> "< 10 MB"
        else -> "> 10 MB"
    }

    init {
        DefaultExports.initialize()
        eventLogCounters = EventType.values().filter { it.fieldsToUseAsMetricLabels.isNotEmpty() }
            .associateWith { registerLabelCounter(it.name, *it.fieldsToUseAsMetricLabels.toTypedArray()) }
    }

    val metricsHttpHandler: HttpHandler = {
        try {
            val str = StringWriter()
            TextFormat.write004(str, CollectorRegistry.defaultRegistry.metricFamilySamples())
            val result = str.toString()
            if (result.isEmpty()) {
                Response(Status.NO_CONTENT)
            } else {
                Response(Status.OK).body(result)
            }
        } catch (e: Exception) {
            log.error { "/prometheus failed writing metrics - ${e.message}" }
            Response(Status.INTERNAL_SERVER_ERROR)
        }
    }
}

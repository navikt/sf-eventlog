package no.nav.sf.eventlog

val local = env(env_NAIS_CLUSTER_NAME) == "local"

val application = Application()

fun main() = application.start()

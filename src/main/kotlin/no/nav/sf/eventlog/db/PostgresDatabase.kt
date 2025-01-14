package no.nav.sf.eventlog.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import no.nav.sf.eventlog.EventType
import no.nav.sf.eventlog.config_CONTEXT
import no.nav.sf.eventlog.env
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDate
import java.time.LocalDateTime

const val NAIS_DB_PREFIX = "NAIS_DATABASE_SF_EVENTLOG_SF_EVENTLOG_"

object PostgresDatabase {
    private val log = KotlinLogging.logger { }

    private val context = env(config_CONTEXT)

    private var logSyncStatusCacheLastUpdated: LocalDate = LocalDate.MIN

    @Volatile
    private var logSyncStatusCache: MutableMap<EventType, MutableMap<LocalDate, LogSyncStatus>> = mutableMapOf()

    fun clearCache() {
        logSyncStatusCache = mutableMapOf()
        logSyncStatusCacheLastUpdated = LocalDate.MIN
    }

    val logSyncStatusMap: MutableMap<EventType, MutableMap<LocalDate, LogSyncStatus>> get() {
        if (logSyncStatusCacheLastUpdated == LocalDate.now()) {
            log.info { "Using log sync status cache" }
        } else {
            log.info { "Cache invalid : Fetching log sync statuses" }
            logSyncStatusCache = retrieveLogSyncStatusesAsMap()
            logSyncStatusCacheLastUpdated = LocalDate.now()
        }
        return logSyncStatusCache
    }

    fun updateCache(logSyncStatus: LogSyncStatus) {
        val eventType = EventType.valueOf(logSyncStatus.eventType)

        // Use getOrPut to initialize the inner map if it doesn't exist
        val eventTypeCache = logSyncStatusCache.getOrPut(eventType) { mutableMapOf() }

        // Update the entry for the specific date
        eventTypeCache[logSyncStatus.syncDate] = logSyncStatus
    }

    private val dbUrl = env("$NAIS_DB_PREFIX${context}_URL")
    private val dbHost = env("$NAIS_DB_PREFIX${context}_HOST")
    private val dbPort = env("$NAIS_DB_PREFIX${context}_PORT")
    private val dbName = env("$NAIS_DB_PREFIX${context}_DATABASE")
    private val dbUsername = env("$NAIS_DB_PREFIX${context}_USERNAME")
    private val dbPassword = env("$NAIS_DB_PREFIX${context}_PASSWORD")

    private val dbJdbcUrl = env("$NAIS_DB_PREFIX${context}_JDBC_URL")

    // Note: exposed Database connect prepares for connections but does not actually open connections
    // That is handled via transaction {} ensuring connections are opened and closed properly
    val database = Database.connect(HikariDataSource(hikariConfig()))

    private fun hikariConfig(): HikariConfig = HikariConfig().apply {
        jdbcUrl = dbJdbcUrl // "jdbc:postgresql://localhost:$dbPort/$dbName" // This is where the cloud db proxy is located in the pod
        driverClassName = "org.postgresql.Driver"
//        addDataSourceProperty("serverName", dbHost)
//        addDataSourceProperty("port", dbPort)
//        addDataSourceProperty("databaseName", dbName)
//        addDataSourceProperty("user", dbUsername)
//        addDataSourceProperty("password", dbPassword)
        minimumIdle = 1
        maxLifetime = 26000
        maximumPoolSize = 10
        connectionTimeout = 250
        idleTimeout = 10000
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ" // Isolation level that ensure the same snapshot of db during one transaction
    }

    fun create(dropFirst: Boolean = false) {
        transaction {
            if (dropFirst) {
                log.info { "Dropping table log_sync_status" }
                val dropStatement =
                    TransactionManager.current().connection.prepareStatement("DROP TABLE log_sync_status", false)
                dropStatement.executeUpdate()
                log.info { "Drop performed" }
            }

            log.info { "Creating table log_sync_status" }
            SchemaUtils.create(LogSyncStatusTable)
        }
    }

    fun upsertLogSyncStatus(logSyncStatus: LogSyncStatus): LogSyncStatus? {
        return upsertLogSyncStatus(logSyncStatus.syncDate, logSyncStatus.eventType, logSyncStatus.status, logSyncStatus.message)
    }
    // Upsert function for LogSyncStatus
    fun upsertLogSyncStatus(
        syncDate: LocalDate,
        eventType: String,
        status: String,
        message: String
    ): LogSyncStatus? {
        return transaction {
            LogSyncStatusTable.upsert(
                keys = arrayOf(LogSyncStatusTable.syncDate, LogSyncStatusTable.eventType) // Perform update if there is a conflict here
            ) {
                it[LogSyncStatusTable.syncDate] = syncDate
                it[LogSyncStatusTable.eventType] = eventType
                it[LogSyncStatusTable.status] = status
                it[LogSyncStatusTable.message] = message
                it[LogSyncStatusTable.lastModified] = LocalDateTime.now()
            }
        }.resultedValues?.firstOrNull()?.toLogSyncStatus()
    }

    // Function to delete rows with lastModified older than <thresholdDays> days
    fun deleteOldLogSyncStatuses(thresholdDays: Long = 100): Int {
        val thresholdDate = LocalDateTime.now().minusDays(thresholdDays)
        return transaction {
            LogSyncStatusTable.deleteWhere { LogSyncStatusTable.lastModified less thresholdDate }
        }
    }

    // Function to delete a row
    fun deleteLogSyncStatus(date: LocalDate, eventType: EventType) {
        transaction {
            LogSyncStatusTable.deleteWhere {
                (LogSyncStatusTable.syncDate eq date) and (LogSyncStatusTable.eventType eq eventType.name)
            }
        }
        clearCache()
    }

    private fun retrieveLogSyncStatusesAsMap(): MutableMap<EventType, MutableMap<LocalDate, LogSyncStatus>> {
        return transaction {
            LogSyncStatusTable.selectAll()
                .map { it.toLogSyncStatus() }
                .groupBy { EventType.valueOf(it.eventType) }
                .mapValues { entry ->
                    entry.value.associateBy { it.syncDate }.toMutableMap()
                }.toMutableMap()
        }
    }
}

package no.nav.sf.eventlog.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import no.nav.sf.eventlog.EventType
import no.nav.sf.eventlog.config_CONTEXT
import no.nav.sf.eventlog.env
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDate
import java.time.LocalDateTime

const val NAIS_DB_PREFIX = "NAIS_DATABASE_SF_EVENTLOG_SF_EVENTLOG_"

class PostgresDatabase {
    private val log = KotlinLogging.logger { }

    private val context = env(config_CONTEXT)

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

    // Function to retrieve all rows and structure as a Map<EventType, List<LogSyncStatus>>
    fun retrieveLogSyncStatusesAsMap(): Map<EventType, List<LogSyncStatus>> {
        return transaction {
            LogSyncStatusTable.selectAll()
                .map { it.toLogSyncStatus() }
                .groupBy { EventType.valueOf(it.eventType) }
                .mapValues { entry ->
                    entry.value.sortedByDescending { it.syncDate }
                }
        }
    }
}

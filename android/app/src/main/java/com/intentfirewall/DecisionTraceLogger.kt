package com.intentfirewall

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@Entity(tableName = "decision_trace")
data class DecisionTraceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "input_text") val inputText: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "tier1_score") val tier1Score: Int,
    @ColumnInfo(name = "tier1_category") val tier1Category: String?,
    @ColumnInfo(name = "tier1_matched") val tier1Matched: String,
    @ColumnInfo(name = "tier3_used") val tier3Used: Boolean,
    @ColumnInfo(name = "tier3_result") val tier3Result: String?,
    @ColumnInfo(name = "final_decision") val finalDecision: String,
    @ColumnInfo(name = "alert_shown") val alertShown: Boolean,
    @ColumnInfo(name = "user_action") val userAction: String?,
)

data class DecisionTrace(
    val timestamp: Long,
    val inputText: String,
    val packageName: String,
    val tier1Score: Int,
    val tier1Category: String?,
    val tier1Matched: String,
    val tier3Used: Boolean,
    val tier3Result: String?,
    val finalDecision: String,
    val alertShown: Boolean,
    val userAction: String?,
)

data class DetectionStats(
    val totalAlerts: Int,
    val totalRows: Int,
    val falsePositiveRate: Double,
)

@Dao
interface DecisionTraceDao {
    @Insert
    suspend fun insert(row: DecisionTraceEntity)

    @Query("SELECT * FROM decision_trace ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DecisionTraceEntity>

    @Query("SELECT * FROM decision_trace ORDER BY id DESC LIMIT 1")
    suspend fun latest(): DecisionTraceEntity?

    @Query("UPDATE decision_trace SET user_action = :action WHERE id = :id")
    suspend fun updateAction(id: Long, action: String)

    @Query("SELECT COUNT(*) FROM decision_trace")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM decision_trace WHERE alert_shown = 1")
    suspend fun countAlerts(): Int

    @Query("SELECT COUNT(*) FROM decision_trace WHERE alert_shown = 1 AND user_action = 'dismissed'")
    suspend fun countDismissedAlerts(): Int
}

@Database(entities = [DecisionTraceEntity::class], version = 1, exportSchema = false)
abstract class ScamDecisionTraceDb : RoomDatabase() {
    abstract fun dao(): DecisionTraceDao
}

/** Room-backed decision tracing for audit, debugging, and stats. */
object DecisionTraceLogger {
    private const val TAG = "SCAM_DecisionTraceLogger"
    private var db: ScamDecisionTraceDb? = null

    /** Initialize Room database for decision traces. */
    fun initialize(context: Context) {
        if (db != null) return
        db = Room.databaseBuilder(
            context.applicationContext,
            ScamDecisionTraceDb::class.java,
            "scam_decision_trace.db"
        ).fallbackToDestructiveMigration().build()
    }

    /** Persist a pipeline decision trace row. */
    fun log(result: PipelineResult, text: String, pkg: String) {
        val dao = db?.dao() ?: return
        val safeText = text.take(200)
        runBlocking(Dispatchers.IO) {
            dao.insert(
                DecisionTraceEntity(
                    timestamp = System.currentTimeMillis(),
                    inputText = safeText,
                    packageName = pkg,
                    tier1Score = if (result.tier == 1) result.confidenceScore else 0,
                    tier1Category = if (result.tier == 1) result.category else null,
                    tier1Matched = result.evidence.joinToString(", "),
                    tier3Used = result.usedTier3,
                    tier3Result = if (result.usedTier3) "${result.decision}:${result.category}" else null,
                    finalDecision = result.decision.name,
                    alertShown = result.decision == Decision.ALERT,
                    userAction = null,
                )
            )
        }
    }

    /** Update latest row with user action from alert UI. */
    fun recordUserAction(action: String) {
        val dao = db?.dao() ?: return
        runBlocking(Dispatchers.IO) {
            val latest = dao.latest() ?: return@runBlocking
            dao.updateAction(latest.id, action)
        }
    }

    /** Fetch most recent decision traces. */
    fun getRecentLogs(limit: Int = 50): List<DecisionTrace> {
        val dao = db?.dao() ?: return emptyList()
        return runBlocking(Dispatchers.IO) {
            dao.recent(limit).map {
                DecisionTrace(
                    timestamp = it.timestamp,
                    inputText = it.inputText,
                    packageName = it.packageName,
                    tier1Score = it.tier1Score,
                    tier1Category = it.tier1Category,
                    tier1Matched = it.tier1Matched,
                    tier3Used = it.tier3Used,
                    tier3Result = it.tier3Result,
                    finalDecision = it.finalDecision,
                    alertShown = it.alertShown,
                    userAction = it.userAction,
                )
            }
        }
    }

    /** Return aggregate stats including approximate false positive rate from dismissed alerts. */
    fun getStats(): DetectionStats {
        val dao = db?.dao() ?: return DetectionStats(0, 0, 0.0)
        return runBlocking(Dispatchers.IO) {
            val totalRows = dao.countAll()
            val totalAlerts = dao.countAlerts()
            val dismissed = dao.countDismissedAlerts()
            val fpRate = if (totalAlerts == 0) 0.0 else dismissed.toDouble() / totalAlerts.toDouble()
            DetectionStats(totalAlerts = totalAlerts, totalRows = totalRows, falsePositiveRate = fpRate)
        }
    }

    /** Persist a direct decision trace from standalone engines. */
    fun logDecisionTrace(
        packageName: String,
        currentMessage: String,
        classification: String,
        confidence: String,
        category: String,
        evidence: String,
        action: String,
    ) {
        val dao = db?.dao() ?: return
        val confScore = when (confidence.uppercase()) {
            "HIGH" -> 90
            "MEDIUM" -> 70
            else -> 50
        }
        runBlocking(Dispatchers.IO) {
            dao.insert(
                DecisionTraceEntity(
                    timestamp = System.currentTimeMillis(),
                    inputText = currentMessage.take(300),
                    packageName = packageName,
                    tier1Score = 0,
                    tier1Category = null,
                    tier1Matched = evidence.take(160),
                    tier3Used = true,
                    tier3Result = "$classification|$confidence|$category",
                    finalDecision = action,
                    alertShown = action == "ALERT_IMMEDIATE",
                    userAction = null,
                )
            )
        }
    }
}

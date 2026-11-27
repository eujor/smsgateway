package com.eujor.smsgateway.gateway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class QueuedGatewayJob(
    val jobId: String,
    val requestId: String,
    val type: String,
    val action: String,
    val sessionId: String,
    val payload: JSONObject
)

enum class QueueInsertResult { ADDED, DUPLICATE, FULL, INVALID }

class LocalJobQueue(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_JOBS (
                job_id TEXT NOT NULL UNIQUE,
                request_id TEXT NOT NULL UNIQUE,
                type TEXT NOT NULL,
                action TEXT NOT NULL,
                session_id TEXT NOT NULL DEFAULT '',
                payload_encrypted TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                finished_at INTEGER,
                last_error TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_gateway_jobs_status_created ON $TABLE_JOBS(status, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    @Synchronized
    fun enqueue(job: JSONObject): QueueInsertResult {
        val jobId = job.optString("job_id").trim()
        val requestId = job.optString("request_id").trim()
        if (jobId.isBlank() || requestId.isBlank()) return QueueInsertResult.INVALID

        val db = writableDatabase
        val existing = android.database.DatabaseUtils.longForQuery(
            db,
            "SELECT COUNT(*) FROM $TABLE_JOBS WHERE job_id=? OR request_id=?",
            arrayOf(jobId, requestId)
        )
        if (existing > 0L) return QueueInsertResult.DUPLICATE

        val pending = android.database.DatabaseUtils.longForQuery(
            db,
            "SELECT COUNT(*) FROM $TABLE_JOBS WHERE status IN ('queued','processing')",
            null
        )
        if (pending >= MAX_PENDING) return QueueInsertResult.FULL

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("job_id", jobId)
            put("request_id", requestId)
            put("type", job.optString("type").lowercase())
            put("action", job.optString("action").ifBlank {
                if (job.optString("type").equals("sms", true)) "send" else "execute"
            }.lowercase())
            put("session_id", job.optString("session_id"))
            put("payload_encrypted", encrypt(job.toString()))
            put("status", "queued")
            put("created_at", now)
            put("updated_at", now)
        }
        val inserted = db.insertWithOnConflict(TABLE_JOBS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return if (inserted == -1L) QueueInsertResult.DUPLICATE else QueueInsertResult.ADDED
    }

    @Synchronized
    fun nextQueued(activeSessionId: String? = null): QueuedGatewayJob? {
        val columns = arrayOf("job_id", "request_id", "type", "action", "session_id", "payload_encrypted")
        readableDatabase.query(
            TABLE_JOBS, columns, "status=?", arrayOf("queued"),
            null, null, "created_at ASC", "200"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val action = cursor.getString(3)
                val sessionId = cursor.getString(4)
                if (activeSessionId != null &&
                    (action !in setOf("continue", "cancel") || sessionId != activeSessionId)
                ) continue

                val payload = try {
                    JSONObject(decrypt(cursor.getString(5)))
                } catch (_: Exception) {
                    markFinished(cursor.getString(0), "failed", "Stored job could not be decrypted.")
                    continue
                }
                return QueuedGatewayJob(
                    jobId = cursor.getString(0),
                    requestId = cursor.getString(1),
                    type = cursor.getString(2),
                    action = action,
                    sessionId = sessionId,
                    payload = payload
                )
            }
        }
        return null
    }

    @Synchronized
    fun markProcessing(jobId: String): Boolean {
        val values = ContentValues().apply {
            put("status", "processing")
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update(
            TABLE_JOBS, values, "job_id=? AND status='queued'", arrayOf(jobId)
        ) == 1
    }

    @Synchronized
    fun markFinished(jobId: String, status: String, error: String = "") {
        require(status in setOf("completed", "failed", "timed_out", "cancelled", "expired"))
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("status", status)
            put("updated_at", now)
            put("finished_at", now)
            put("last_error", error.take(500))
        }
        writableDatabase.update(TABLE_JOBS, values, "job_id=?", arrayOf(jobId))
    }

    @Synchronized
    fun recoverInterrupted(): Int {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("status", "failed")
            put("updated_at", now)
            put("finished_at", now)
            put("last_error", "Interrupted by app or device restart; not retried automatically to prevent duplicate SMS or USSD actions.")
        }
        return writableDatabase.update(TABLE_JOBS, values, "status='processing'", null)
    }

    @Synchronized
    fun cleanupOlderThan(cutoffMillis: Long): Int =
        writableDatabase.delete(
            TABLE_JOBS,
            "status IN ('completed','failed','timed_out','cancelled','expired') AND updated_at < ?",
            arrayOf(cutoffMillis.toString())
        )

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val packed = cipher.iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.DEFAULT)
        require(packed.size > 12) { "Encrypted queue payload is incomplete." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val DATABASE_NAME = "eujor_gateway_jobs.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_JOBS = "gateway_jobs"
        private const val KEY_ALIAS = "eujor_smsgateway_queue_v1"
        private const val MAX_PENDING = 500
    }
}

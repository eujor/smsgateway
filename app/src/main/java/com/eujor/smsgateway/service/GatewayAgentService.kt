package com.eujor.smsgateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eujor.smsgateway.data.DailyMaintenance
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.data.SecureConfigStore
import com.eujor.smsgateway.gateway.GatewayApi
import com.eujor.smsgateway.gateway.GatewayJobProcessor
import com.eujor.smsgateway.gateway.JobProcessOutcome
import com.eujor.smsgateway.gateway.LocalJobQueue
import com.eujor.smsgateway.gateway.QueueInsertResult
import com.eujor.smsgateway.gateway.QueuedGatewayJob
import com.eujor.smsgateway.telephony.UssdChainState
import com.eujor.smsgateway.telephony.SmsOperationState

class GatewayAgentService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            worker?.interrupt()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        if (!running) {
            running = true
            worker = Thread({ agentLoop() }, "EujorGatewayAgent").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun agentLoop() {
        val queue = LocalJobQueue(this)
        queue.recoverInterrupted()
        var activeJob: QueuedGatewayJob? = null
        var lastHeartbeat = 0L
        var registeredDeviceId = ""
        var lastErrorLog = 0L

        try {
            while (running) {
                DailyMaintenance.runIfDue(this, queue)
                val config = SecureConfigStore.load(this)
                if (config.serverUrl.isBlank() || config.apiKey.isBlank()) {
                    if (System.currentTimeMillis() - lastErrorLog > 60_000) {
                        LogStore.append(this, "Agent paused: configure receiver URL and device API key.")
                        lastErrorLog = System.currentTimeMillis()
                    }
                    Thread.sleep(5_000)
                    continue
                }

                try {
                    if (registeredDeviceId != config.deviceId) {
                        GatewayApi.registerDevice(config, this)
                        registeredDeviceId = config.deviceId
                        lastHeartbeat = System.currentTimeMillis()
                    }
                    if (System.currentTimeMillis() - lastHeartbeat > 60_000) {
                        GatewayApi.heartbeat(config)
                        lastHeartbeat = System.currentTimeMillis()
                    }

                    if (activeJob != null && activeJob.type == "ussd" && !UssdChainState.isActive()) {
                        val reason = UssdChainState.endReason
                        val finalStatus = when (reason) {
                            "timeout" -> "timed_out"
                            "cancelled" -> "cancelled"
                            "failed" -> "failed"
                            else -> "completed"
                        }
                        queue.markFinished(
                            activeJob.jobId,
                            finalStatus,
                            if (reason == "timeout") "USSD session timed out." else ""
                        )
                        if (reason == "timeout") {
                            GatewayApi.postResult(config, activeJob.jobId, activeJob.requestId, "ussd", "timeout")
                            LogStore.append(this, "USSD session timed out; next queued request may proceed.")
                        }
                        activeJob = null
                    }

                    if (activeJob != null && activeJob.type == "sms") {
                        val smsStatus = SmsOperationState.consumeResult(activeJob.jobId)
                        if (smsStatus != null) {
                            val finalStatus = if (smsStatus == "sent") "completed" else "failed"
                            queue.markFinished(activeJob.jobId, finalStatus, if (finalStatus == "failed") smsStatus else "")
                            activeJob = null
                        }
                    }

                    if (activeJob != null) {
                        if (activeJob.type == "ussd") {
                            val controlJob = queue.nextQueued(activeJob.requestId)
                            if (controlJob != null) {
                                runQueuedJob(queue, config, controlJob)
                                continue
                            }
                        }
                        enqueueRemoteJobs(queue, config)
                        continue
                    }

                    // A manually triggered USSD session also holds the telephony slot.
                    if (UssdChainState.isActive()) {
                        val controlJob = queue.nextQueued(UssdChainState.sessionId)
                        if (controlJob != null) {
                            runQueuedJob(queue, config, controlJob)
                            continue
                        }
                        enqueueRemoteJobs(queue, config)
                        continue
                    }

                    // A manually triggered SMS also holds the telephony slot while Android resolves its send result.
                    if (SmsOperationState.hasActiveOperation()) {
                        enqueueRemoteJobs(queue, config)
                        continue
                    }

                    val queuedJob = queue.nextQueued()
                    if (queuedJob != null) {
                        runQueuedJob(queue, config, queuedJob) { activeJob = it }
                        continue
                    }

                    enqueueRemoteJobs(queue, config)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    registeredDeviceId = ""
                    if (System.currentTimeMillis() - lastErrorLog > 30_000) {
                        LogStore.append(this, "Gateway connection problem: " + (e.message ?: "network error"))
                        lastErrorLog = System.currentTimeMillis()
                    }
                    try { Thread.sleep(4_000) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        } finally {
            queue.close()
        }
    }

    private fun runQueuedJob(
        queue: LocalJobQueue,
        config: com.eujor.smsgateway.data.AppConfig,
        queued: QueuedGatewayJob,
        onWaitingForUssd: ((QueuedGatewayJob) -> Unit)? = null
    ) {
        if (!queue.markProcessing(queued.jobId)) return
        when (GatewayJobProcessor.process(this, config, queued.payload)) {
            JobProcessOutcome.WAITING_FOR_USSD, JobProcessOutcome.WAITING_FOR_SMS -> {
                if (onWaitingForUssd == null) {
                    queue.markFinished(queued.jobId, "failed", "Unexpected telephony session while another session was active.")
                    UssdChainState.finish("failed")
                } else {
                    onWaitingForUssd(queued)
                }
            }
            JobProcessOutcome.FINISHED -> queue.markFinished(queued.jobId, "completed")
            JobProcessOutcome.FAILED -> queue.markFinished(queued.jobId, "failed", "The Android agent rejected or failed this job.")
        }
    }

    private fun enqueueRemoteJobs(queue: LocalJobQueue, config: com.eujor.smsgateway.data.AppConfig) {
        GatewayApi.nextJobs(config).forEach { job ->
            when (queue.enqueue(job)) {
                QueueInsertResult.ADDED -> LogStore.append(this, "Remote job stored in local queue: " + job.optString("job_id"))
                QueueInsertResult.DUPLICATE -> LogStore.append(this, "Duplicate remote job ignored: " + job.optString("job_id"))
                QueueInsertResult.FULL -> {
                    LogStore.append(this, "Local request queue is full; remote job rejected: " + job.optString("job_id"))
                    val jobId = job.optString("job_id")
                    val requestId = job.optString("request_id")
                    if (jobId.isNotBlank() && requestId.isNotBlank()) {
                        GatewayApi.postResult(
                            config, jobId, requestId, job.optString("type", "unknown"), "failed",
                            org.json.JSONObject().put("error", "The phone's local request queue is full.")
                        )
                    }
                }
                QueueInsertResult.INVALID -> LogStore.append(this, "Remote job rejected because job_id or request_id is missing.")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gateway connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("EUJOR SMS Gateway")
            .setContentText("Connected agent is listening for SMS and USSD jobs")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        const val ACTION_STOP = "com.eujor.smsgateway.action.STOP_AGENT"
        private const val CHANNEL_ID = "eujor_gateway_agent"
        private const val NOTIFICATION_ID = 701
    }
}

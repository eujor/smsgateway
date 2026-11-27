package com.eujor.smsgateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.data.SecureConfigStore
import com.eujor.smsgateway.gateway.GatewayApi
import com.eujor.smsgateway.gateway.GatewayJobProcessor
import com.eujor.smsgateway.gateway.JobDeduper

class GatewayAgentService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
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
        var lastHeartbeat = 0L
        var registeredDeviceId = ""
        var lastErrorLog = 0L
        while (running) {
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
                GatewayApi.nextJob(config)?.let { job ->
                    val jobId = job.optString("job_id")
                    if (JobDeduper.claim(this, jobId)) {
                        GatewayJobProcessor.process(this, config, job)
                    } else {
                        LogStore.append(this, "Duplicate job ignored: " + jobId)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                registeredDeviceId = ""
                if (System.currentTimeMillis() - lastErrorLog > 30_000) {
                    LogStore.append(this, "Gateway connection problem: " + (e.message ?: "network error"))
                    lastErrorLog = System.currentTimeMillis()
                }
                try { Thread.sleep(4_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
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

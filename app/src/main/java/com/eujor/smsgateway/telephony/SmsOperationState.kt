package com.eujor.smsgateway.telephony

object SmsOperationState {
    private const val SEND_RESULT_TIMEOUT_MS = 120_000L

    @Volatile private var activeJobId: String = ""
    @Volatile private var startedAt: Long = 0L
    @Volatile private var completedJobId: String = ""
    @Volatile private var completedStatus: String = ""

    @Synchronized
    fun begin(jobId: String) {
        expireIfNeeded()
        require(jobId.isNotBlank()) { "SMS job ID is missing." }
        check(activeJobId.isBlank()) { "Another SMS send is still awaiting its Android send result." }
        activeJobId = jobId
        startedAt = System.currentTimeMillis()
        if (completedJobId == jobId) {
            completedJobId = ""
            completedStatus = ""
        }
    }

    @Synchronized
    fun finish(jobId: String, status: String) {
        expireIfNeeded()
        if (activeJobId != jobId) return
        activeJobId = ""
        startedAt = 0L
        completedJobId = jobId
        completedStatus = status
    }

    @Synchronized
    fun hasActiveOperation(): Boolean {
        expireIfNeeded()
        return activeJobId.isNotBlank()
    }

    @Synchronized
    fun consumeResult(jobId: String): String? {
        expireIfNeeded()
        if (completedJobId != jobId) return null
        val result = completedStatus
        completedJobId = ""
        completedStatus = ""
        return result
    }

    private fun expireIfNeeded() {
        if (activeJobId.isNotBlank() && System.currentTimeMillis() - startedAt > SEND_RESULT_TIMEOUT_MS) {
            completedJobId = activeJobId
            completedStatus = "send_timeout"
            activeJobId = ""
            startedAt = 0L
        }
    }
}

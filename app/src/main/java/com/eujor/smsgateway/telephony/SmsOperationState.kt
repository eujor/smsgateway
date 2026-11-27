package com.eujor.smsgateway.telephony

object SmsOperationState {
    @Volatile private var activeJobId: String = ""
    @Volatile private var completedJobId: String = ""
    @Volatile private var completedStatus: String = ""

    @Synchronized
    fun begin(jobId: String) {
        require(jobId.isNotBlank()) { "SMS job ID is missing." }
        check(activeJobId.isBlank()) { "Another SMS send is still awaiting its Android send result." }
        activeJobId = jobId
        if (completedJobId == jobId) {
            completedJobId = ""
            completedStatus = ""
        }
    }

    @Synchronized
    fun finish(jobId: String, status: String) {
        if (activeJobId != jobId) return
        activeJobId = ""
        completedJobId = jobId
        completedStatus = status
    }

    @Synchronized
    fun hasActiveOperation(): Boolean = activeJobId.isNotBlank()

    @Synchronized
    fun consumeResult(jobId: String): String? {
        if (completedJobId != jobId) return null
        val result = completedStatus
        completedJobId = ""
        completedStatus = ""
        return result
    }
}

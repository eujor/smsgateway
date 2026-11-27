package com.eujor.smsgateway.telephony

import java.util.ArrayDeque

object UssdChainState {
    private const val SESSION_TIMEOUT_MS = 120_000L

    @Volatile private var active = false
    @Volatile private var startedAt = 0L
    @Volatile var sessionId: String = ""
        private set
    @Volatile var jobId: String = ""
        private set
    @Volatile var simSlot: Int = 1
        private set
    @Volatile var endReason: String = ""
        private set
    private val pendingInputs = ArrayDeque<String>()

    @Synchronized
    fun begin(session: String, job: String, slot: Int, inputs: List<String>) {
        pendingInputs.clear()
        sessionId = session
        jobId = job
        simSlot = slot
        pendingInputs.addAll(inputs)
        startedAt = System.currentTimeMillis()
        endReason = ""
        active = true
    }

    @Synchronized
    fun enqueue(input: String) {
        expireIfNeeded()
        check(active) { "There is no active USSD session." }
        require(pendingInputs.size < 20) { "USSD input queue is full." }
        pendingInputs.addLast(input)
    }

    @Synchronized
    fun takeNext(): String? {
        expireIfNeeded()
        return if (!active || pendingInputs.isEmpty()) null else pendingInputs.removeFirst()
    }

    @Synchronized
    fun isActive(): Boolean {
        expireIfNeeded()
        return active
    }

    @Synchronized
    fun matches(session: String): Boolean {
        expireIfNeeded()
        return active && sessionId == session
    }

    @Synchronized
    fun finish(reason: String) {
        if (!active) return
        pendingInputs.clear()
        active = false
        startedAt = 0L
        endReason = reason
        UssdResultStore.finish(
            when (reason) {
                "timeout" -> "USSD session timed out."
                "failed" -> "USSD session failed."
                "cancelled" -> "USSD session cancelled."
                else -> ""
            }
        )
    }

    @Synchronized
    fun clear() = finish("cancelled")

    private fun expireIfNeeded() {
        if (active && System.currentTimeMillis() - startedAt > SESSION_TIMEOUT_MS) {
            finish("timeout")
        }
    }
}

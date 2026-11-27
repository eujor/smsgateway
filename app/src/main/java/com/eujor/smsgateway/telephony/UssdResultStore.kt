package com.eujor.smsgateway.telephony

object UssdResultStore {
    @Volatile
    var latest: String = ""
        private set

    @Volatile
    var sessionActive: Boolean = false
        private set

    fun update(response: String) {
        latest = response.take(3000)
        sessionActive = true
    }

    fun finish(response: String = "") {
        if (response.isNotBlank()) latest = response.take(3000)
        sessionActive = false
    }

    fun clear() {
        latest = ""
        sessionActive = false
    }
}

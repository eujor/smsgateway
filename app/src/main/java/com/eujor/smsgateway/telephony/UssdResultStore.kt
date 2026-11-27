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

    fun classifyCarrierOutcome(response: String): String {
        val text = response.lowercase()
        return when {
            Regex("\\bconfirm(?:ation)?\\b|\\baccept\\b|\\bdecline\\b|1\\s*[:.)-]\\s*accept|2\\s*[:.)-]\\s*decline").containsMatchIn(text) -> "confirmation_required"
            Regex("\\b(failed|failure|unsuccessful|insufficient|invalid|declined|error|unable to)\\b").containsMatchIn(text) -> "carrier_reported_failure"
            Regex("\\b(successful|successfully|transaction complete|purchase confirmed|activated successfully|you have received)\\b").containsMatchIn(text) -> "carrier_text_indicates_success"
            else -> "unclassified"
        }
    }

    fun clear() {
        latest = ""
        sessionActive = false
    }
}

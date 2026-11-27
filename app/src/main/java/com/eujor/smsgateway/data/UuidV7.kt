package com.eujor.smsgateway.data

import java.security.SecureRandom
import java.util.UUID

object UuidV7 {
    private val random = SecureRandom()

    @Synchronized
    fun generate(): String {
        val timestamp = System.currentTimeMillis() and 0x0000FFFFFFFFFFFFL
        val randomA = random.nextInt(1 shl 12).toLong()
        val most = (timestamp shl 16) or 0x7000L or randomA
        val randomB = random.nextLong() and 0x3FFFFFFFFFFFFFFFL
        val least = randomB or Long.MIN_VALUE or (1L shl 62)
        return UUID(most, least).toString()
    }
}

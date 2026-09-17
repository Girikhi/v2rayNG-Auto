package com.v2ray.ang.dto.entities

data class ServerHealthRecord(
    val delayMillis: Long = 0L,
    val testedAtMillis: Long = 0L,
    val useFineFragmentFallback: Boolean = false,
) {
    val isWorking: Boolean
        get() = delayMillis > 0L
}

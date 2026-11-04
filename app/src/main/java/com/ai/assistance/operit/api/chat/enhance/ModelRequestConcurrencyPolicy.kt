package com.ai.assistance.operit.api.chat.enhance

/** Independent children may overlap even when their model's regular chat requests are serialized. */
enum class ModelRequestConcurrencyPolicy {
    CONFIGURED,
    UNRESTRICTED;

    fun effectiveLimit(configuredLimit: Int): Int = when (this) {
        CONFIGURED -> configuredLimit.coerceAtLeast(0)
        UNRESTRICTED -> 0
    }
}

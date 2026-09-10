package com.lynko.app

/** Maps sessionId → the consent answer callback TransferConsentActivity invokes. */
object TransferConsentHub {
    val pending = mutableMapOf<String, (Boolean) -> Unit>()
}

package dev.nisarg.paisa.capture

/**
 * Which apps and senders we treat as payment sources.
 *
 * Deliberately short. Google Pay is the only one that matters today. Everything
 * else that posts a notification gets its PACKAGE NAME logged (never content)
 * to `unmatched_packages`, so the list below can be extended from evidence
 * rather than guesswork — including Jupiter, whose package name is not written
 * here because guessing it would be worse than discovering it.
 */
object PaymentSources {

    val PACKAGES: Set<String> = setOf(
        "com.google.android.apps.nbu.paisa.user",  // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",                      // BHIM
        "com.dreamplug.androidapp",                // CRED
    )

    fun isPaymentApp(packageName: String?): Boolean = packageName in PACKAGES

    /**
     * SMS senders are matched by content, not by sender id.
     *
     * Indian bank sender ids vary by circle and operator (AD-HDFCBK, VM-ICICIB,
     * JD-SBIINB…) and any allowlist would silently drop messages. Instead we keep
     * any SMS that looks monetary and let the parser sort it out — raw storage is
     * cheap, a missed transaction is not.
     */
    private val MONEY_HINT = Regex(
        "(?:₹|\\bRs\\.?|\\bINR)\\s*[0-9]",
        RegexOption.IGNORE_CASE
    )

    fun looksMonetary(body: String?): Boolean =
        body != null && MONEY_HINT.containsMatchIn(body)
}

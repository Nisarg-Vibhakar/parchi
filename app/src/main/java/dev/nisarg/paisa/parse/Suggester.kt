package dev.nisarg.paisa.parse

/**
 * "I went out with friends and paid for dinner — is that food, fun, or people?"
 *
 * That question has no correct answer, only a consistent one. What makes it
 * answerable is not intelligence, it is memory: you have already decided this,
 * probably several times, and the useful thing is to be reminded which way you
 * went and why.
 *
 * So this is not a model. A 2B model on-device is about 1.5GB, needs a download
 * and a warm-up, and would be guessing at something the database already knows —
 * there are hundreds of merchant-to-category answers in there, given by the only
 * person whose opinion counts. This ranks that evidence and, crucially, says
 * *why*, so a suggestion can be disagreed with rather than merely accepted.
 *
 * Deliberately pure: no Android, no database, no clock. Everything it needs is
 * passed in, which is what makes it unit-testable and what would let a model
 * slot in behind the same [Evidence] later without touching the callers.
 */
object Suggester {

    data class Suggestion(
        val category: Categoriser.Category,
        /** Shown to the user. A suggestion without a reason is just a guess. */
        val why: String,
        val score: Int,
    )

    /**
     * Everything known about a payment that has to be filed.
     *
     * @param learnedForMerchant what was chosen for this exact merchant before
     * @param ruleGuess what the keyword rules think
     * @param similarVotes category -> times chosen for merchants with a shared word
     * @param sameHourVotes category -> times chosen around this time of day
     * @param favourites most-used categories overall, most-used first
     */
    data class Evidence(
        val learnedForMerchant: String? = null,
        val ruleGuess: String? = null,
        val similarVotes: Map<String, Int> = emptyMap(),
        val sameHourVotes: Map<String, Int> = emptyMap(),
        val favourites: List<String> = emptyList(),
    )

    // Weights, in the order the evidence deserves to be trusted. An answer you
    // gave for this exact payee beats everything; a keyword rule is a decent
    // prior; what you usually pick at this hour is weak but real; and raw
    // popularity is a tie-breaker, never a reason on its own.
    private const val W_LEARNED = 100
    private const val W_RULE = 40
    private const val W_SIMILAR = 12
    private const val W_HOUR = 6
    private const val W_FAVOURITE = 2

    private fun parse(name: String?): Categoriser.Category? =
        name?.let { runCatching { Categoriser.Category.valueOf(it) }.getOrNull() }

    /**
     * Ranked suggestions, best first. Empty when there is genuinely nothing to
     * go on — which is an honest answer, and better than inventing one.
     */
    fun suggest(e: Evidence, top: Int = 3): List<Suggestion> {
        val scores = mutableMapOf<Categoriser.Category, Int>()
        val reasons = mutableMapOf<Categoriser.Category, String>()

        fun add(c: Categoriser.Category?, points: Int, why: String) {
            if (c == null || points <= 0) return
            scores[c] = (scores[c] ?: 0) + points
            // Keep the strongest reason, not the last one.
            if (points >= (scores[c]!! - points).coerceAtLeast(0) || c !in reasons) {
                reasons[c] = why
            }
        }

        parse(e.learnedForMerchant)?.let {
            add(it, W_LEARNED, "you filed this payee here before")
        }
        parse(e.ruleGuess)?.let {
            add(it, W_RULE, "the rules match this name")
        }
        e.similarVotes.forEach { (name, n) ->
            add(parse(name), W_SIMILAR * n,
                "you filed $n similar ${if (n == 1) "payee" else "payees"} here")
        }
        e.sameHourVotes.forEach { (name, n) ->
            add(parse(name), W_HOUR * n, "this is usually what you pick at this hour")
        }
        e.favourites.take(5).forEachIndexed { i, name ->
            add(parse(name), W_FAVOURITE * (5 - i), "one of your most-used")
        }

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Categoriser.Category, Int>> { it.value }
                .thenBy { it.key.name })
            .take(top)
            .map { Suggestion(it.key, reasons[it.key] ?: "a reasonable fit", it.value) }
    }

    /**
     * A one-line hint for the slip, or null when the evidence is too thin to be
     * worth the space. Silence beats a shrug.
     */
    fun hint(e: Evidence): String? {
        val best = suggest(e, 1).firstOrNull() ?: return null
        if (best.score < W_RULE) return null
        return "probably ${best.category.label.uppercase()} — ${best.why}"
    }

    /** Words worth comparing merchants by. Short and generic ones say nothing. */
    fun tokens(merchant: String?): Set<String> =
        merchant?.lowercase()
            ?.split(Regex("[^a-z0-9]+"))
            ?.filter { it.length >= 4 && it !in GENERIC }
            ?.toSet()
            ?: emptySet()

    private val GENERIC = setOf(
        "the", "and", "pvt", "ltd", "llp", "india", "private", "limited",
        "store", "shop", "services", "service", "enterprise", "enterprises",
        "solutions", "trading", "company", "retail",
    )
}

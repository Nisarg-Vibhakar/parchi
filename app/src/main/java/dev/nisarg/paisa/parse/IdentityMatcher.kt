package dev.nisarg.paisa.parse

/**
 * Finds payees that are probably the same person, and never acts on it.
 *
 * The same human appears under several identities: you paid "SHARMA RAHUL"
 * and were paid by "sameer.rao-1@okaxis" — debits carry names, credits carry
 * handles, and the app treats them as strangers. Merging them is what makes a
 * per-person total, and eventually a who-owes-whom ledger, possible at all.
 *
 * It is also the easiest place in this app to cause silent, invisible damage.
 * Merging two different people quietly corrupts every figure that follows and
 * there is no symptom to notice. So this only ever SUGGESTS, always with the
 * evidence attached, and the merge itself is stored separately and reversibly.
 *
 * Every rule below errs toward not suggesting. A missed merge costs nothing; a
 * wrong one costs trust in the whole ledger.
 */
object IdentityMatcher {

    data class Candidate(
        val a: String,
        val b: String,
        val reason: String,
        /** 0..1. Only for ordering the list — it is never a threshold to act on. */
        val strength: Double,
    )

    /**
     * The local part of a UPI handle, which is usually a mangled name:
     * "sameer.rao-1@okaxis" -> "sameerrao".
     *
     * Trailing digits are dropped because banks append them for uniqueness and
     * they carry no identity.
     */
    fun handleStem(value: String): String? {
        if (!value.contains("@")) return null
        val local = value.substringBefore("@").lowercase()
        val letters = local.filter { it.isLetterOrDigit() }.trimEnd { it.isDigit() }
        return letters.takeIf { it.length >= 5 }
    }

    /** "SHARMA RAHUL" -> "gaurangprajapati", so word order cannot hide a match. */
    fun nameKey(value: String): String? {
        if (value.contains("@") || value.any { it.isDigit() }) return null
        val words = value.lowercase().split(Regex("[^a-z]+")).filter { it.length > 1 }
        if (words.size !in 2..4) return null
        return words.sorted().joinToString("")
    }

    /** The words of a plain name, or null if this is not a plain name. */
    fun nameWords(value: String): List<String>? {
        if (value.contains("@") || value.any { it.isDigit() }) return null
        val words = value.lowercase().split(Regex("[^a-z]+")).filter { it.length > 1 }
        return words.takeIf { it.size in 2..4 }
    }

    fun suggest(payees: List<String>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val stems = payees.mapNotNull { p -> handleStem(p)?.let { it to p } }
        val names = payees.mapNotNull { p -> nameWords(p)?.let { it to p } }

        for ((stem, handle) in stems) {
            for ((words, name) in names) {
                // Whole words, not the joined key: handles keep the name's words
                // but not its order, and an alphabetically sorted key cannot be
                // found inside one. Handles also misspell — "rahulshrma"
                // against "prajapati" — so a partial word match is expected.
                val matched = words.filter { stem.contains(it) }
                if (matched.isEmpty()) continue
                val longEnough = matched.any { it.length >= 5 }
                if (!longEnough) continue

                // One matching word is only enough when the handle carries MORE
                // than that word. A handle that is exactly someone's first name
                // identifies a first name, not a person — there are many Sameers.
                val matchedLength = matched.sumOf { it.length }
                val carriesMore = matched.size >= 2 || stem.length > matchedLength + 2
                if (!carriesMore) continue

                val strength = (matchedLength.toDouble() / stem.length).coerceIn(0.0, 1.0)
                out += Candidate(handle, name,
                    "handle \"$stem\" contains ${matched.joinToString(", ")} from \"$name\"",
                    strength)
            }
        }

        // Two handles differing only by a trailing counter — a bank reissuing the
        // same payee — are the safest merge there is.
        for (i in stems.indices) for (j in i + 1 until stems.size) {
            val (s1, p1) = stems[i]; val (s2, p2) = stems[j]
            if (s1 == s2 && p1 != p2) {
                out += Candidate(p1, p2, "same handle, different suffix", 0.95)
            }
        }

        // The same name written two ways. Found in real data as "Umesh Rambhai
        // Patat" alongside "UMESH RAMBHAI PATAT" — two payees, one person, purely
        // from casing. The safest merge of all, because nothing is being inferred.
        val byKey = payees.filter { nameWords(it) != null }.groupBy { nameKey(it) }
        for ((_, group) in byKey) {
            if (group.size < 2) continue
            for (i in group.indices) for (j in i + 1 until group.size) {
                out += Candidate(group[i], group[j], "the same name, written differently", 1.0)
            }
        }

        return out.distinctBy { setOf(it.a, it.b) }.sortedByDescending { it.strength }
    }
}

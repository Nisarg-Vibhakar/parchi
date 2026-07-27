package dev.nisarg.paisa.parse

/**
 * Merchant -> category.
 *
 * Two layers, and the order is the whole design:
 *
 *   1. LEARNED  — what the user told us, from `merchant_categories`. Always wins.
 *   2. RULES    — keyword seeds below, derived from the first real corpus
 *                 (1,735 debits across 784 distinct merchants on this phone).
 *
 * Rules exist only so the app is useful on day one. Every correction the user
 * makes writes a learned row, so the rules matter less every week. This is the
 * mechanism that eventually makes the app stop asking.
 */
object Categoriser {

    const val VERSION = 1

    // Deliberately few. A category list you have to scroll is a category list
    // that gets ignored — the modal shows six tiles and one tap has to finish it.
    enum class Category {
        FOOD, GROCERIES, TRANSPORT, FUEL, VEHICLE, SHOPPING, BILLS, RENT,
        INSURANCE,
        FUN, SUBSCRIPTIONS, ENTERTAINMENT, HEALTH, SPORTS, GIFTS, HOME,
        EDUCATION, HELP, PEOPLE, INVESTMENT, TRANSFER, RENT_SHARE, OTHER;

        /**
         * Whether this leaves the user's net worth. Paying a credit-card bill
         * moves money the purchases already accounted for, and buying a mutual
         * fund converts cash into an asset — neither is consumption, and counting
         * them as spending is what made a ₹13k month read as ₹71k.
         */
        val isSpending: Boolean
            get() = this !in NON_SPENDING;

        val label: String
            get() = when (this) {
                RENT_SHARE -> "Rent (recovered)"
                SUBSCRIPTIONS -> "Subscriptions"
                else -> name.lowercase().replaceFirstChar { it.uppercase() }
            };

    }

    /**
     * Categories that leave the account without being consumption.
     *
     * TRANSFER    — card bills and settling up; the purchases were already counted
     * INVESTMENT  — cash converted into an asset
     * RENT_SHARE  — the flatmates' portion of a rent payment. The landlord is paid
     *               in full by one person, but only their own share is their cost;
     *               counting the whole payment overstates rent by however many
     *               people live there.
     */
    val NON_SPENDING = setOf(Category.TRANSFER, Category.INVESTMENT, Category.RENT_SHARE)

    /** Keyword -> category. Matched against a normalised merchant string. */
    private val RULES: List<Pair<List<String>, Category>> = listOf(
        listOf("swiggy", "zomato", "bundltechnologies", "foodcorner", "khaugalli",
            "linkcontinentfood", "restaurant", "cafe", "kathiyawadi", "dominos",
            "pizza", "biryani", "chai", "tea", "juice", "bakery", "hotel",
            "one20", "petpooja", "eatclub", "faasos", "behrouz") to Category.FOOD,

        listOf("blinkit", "zepto", "bigbasket", "dmart", "grocer", "kirana",
            "supermarket", "provision", "sales", "generalstore") to Category.GROCERIES,

        listOf("rapido", "uber", "ola", "gujaratstateroadtransport", "gsrtc",
            // Banks truncate: "GUJARAT STATE ROAD TRANSP" arrives cut short, so
            // the full name never matched and four alphabetic words then tripped
            // the person heuristic. Rs.4,120 of bus fares were filed as a person.
            "gujaratstateroad", "roadtransport",
            "brts", "citybus", "metro", "irctc", "railway", "redbus",
            "namma", "bmtc", "auto") to Category.TRANSPORT,

        listOf("petroleum", "petrol", "hpcl", "iocl", "bpcl", "indianoil",
            "bharatpetro", "hindustanpetro", "fuel", "filling") to Category.FUEL,

        // The running cost of owning a vehicle — service, parts, insurance.
        // Deliberately NOT the same bucket as fuel (too frequent to bury) and
        // deliberately not where a purchase lives (see the one-off flag).
        listOf("motors", "automobile", "autozone", "servicecentre", "servicecenter",
            "garage", "tyre", "tyres", "puncture", "acko", "policybazaar",
            "bikeservice", "carservice", "spares", "denting") to Category.VEHICLE,
        // "rto" is deliberately absent: matching is on a normalised substring, and
        // three letters collide — it fires inside "spoRTOpia", turning a sports
        // club into a vehicle expense. Keywords must be long enough to be rare.

        listOf("flipkart", "amazon", "myntra", "ajio", "lifestyle", "meesho",
            "nykaa", "reliancetrends", "decathlon", "croma") to Category.SHOPPING,

        // Card-bill aggregators must be matched BEFORE the bills rules: paying
        // one settles purchases that were each already captured, so treating it
        // as a bill double-counts the entire statement.
        listOf("cheq", "cred", "onecard", "paytmcc", "creditcardbill",
            "cardpayment") to Category.TRANSFER,

        listOf("jio", "airtel", "vodafone", "vi", "gtpl", "broadband",
            "electricity", "torrentpower", "gas", "recharge",
            "billdesk", "bbps") to Category.BILLS,

        // Protection premiums. Vehicle insurers are matched earlier by VEHICLE on
        // purpose, so the bike's insurance stays part of the bike's running cost
        // rather than being split across two views of the same object.
        //
        // "lic" is deliberately ABSENT. An LIC policy is usually endowment or
        // money-back — savings wearing a protection wrapper, which belongs in
        // INVESTMENT, not spending. A term plan is the opposite. Only the holder
        // knows which, so the app asks instead of guessing and being confidently
        // wrong about a recurring amount.
        listOf("insurance", "assurance", "mediclaim", "termplan", "hdfclife",
            "maxlife", "iciciprulife", "sbilife", "bajajallianzlife", "tataaia",
            "starhealth", "nivabupa", "carehealth", "adityabirlahealth",
            "religare") to Category.INSURANCE,

        listOf("hotstar", "netflix", "prime", "spotify", "youtube", "bookmyshow",
            "pvr", "inox", "cinema", "gaming", "bgmi") to Category.ENTERTAINMENT,

        listOf("medical", "pharma", "chemist", "hospital", "clinic", "apollo",
            "practo", "diagnostic", "pathology", "dental") to Category.HEALTH,

        listOf("pickleball", "sportomic", "bluejersey", "turf", "gym", "fitness",
            "cult", "sports", "badminton", "academy") to Category.SPORTS,

        listOf("nseclearin", "achdr", "zerodha", "groww", "upstox", "smallcase",
            "mutualfund", "bajajfinserv", "sip", "broking", "securities",
            "nsesms", "bsesms") to Category.INVESTMENT,

        listOf("rent", "landlord", "nobroker", "housing", "society",
            "maintenance") to Category.RENT,

        listOf("bookmyshow", "pvr", "inox", "cinema", "bar", "pub", "brewery",
            "lounge", "club", "resort", "trip", "travel", "makemytrip",
            "goibibo", "oyo", "airbnb", "district") to Category.FUN,

        listOf("netflix", "spotify", "hotstar", "primevideo", "youtube",
            "appleservices", "googleplay", "adobe", "openai", "chatgpt",
            "claude", "anthropic", "notion", "figma") to Category.SUBSCRIPTIONS,

        listOf("ikea", "urbanladder", "pepperfry", "homecentre", "furniture",
            "hardware", "plumber", "electrician", "carpenter") to Category.HOME,

        listOf("udemy", "coursera", "unacademy", "byjus", "school", "college",
            "tuition", "institute", "exam", "university") to Category.EDUCATION,

        // Household staff: maid, cook, driver, gardener, security, nanny.
        //
        // Kept apart from HOME, which is furniture and repairs — irregular stuff.
        // This is fixed recurring pay, and mixing a monthly salary with a one-off
        // sofa hides the committed monthly base entirely.
        //
        // Kept apart from PEOPLE, which is friends and settling up. That bucket is
        // noise to skim; this is a cost you owe every month whether you spend
        // anything else or not.
        //
        // The keywords rarely fire in practice — household staff are paid to a
        // personal UPI handle that names a human, so the rule engine will guess
        // PEOPLE. Filing it once teaches the merchant memory, and it stays right
        // from then on. That is the intended path, not a shortcoming.
        listOf("maid", "cook", "housekeeping", "domestic", "nanny", "babysitter",
            "gardener", "caretaker", "watchman", "driver", "chauffeur",
            "urbancompany", "urbanclap") to Category.HELP,
    )

    /**
     * Rule-based guess. Returns null rather than guessing OTHER, so the caller can
     * tell "no idea" from "confidently uncategorised" — the modal only needs to
     * ask about the former.
     */
    fun byRule(merchant: String?): Category? {
        if (merchant.isNullOrBlank()) return null
        val squashed = normalise(merchant)
        val words = merchant.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        for ((keywords, category) in RULES) {
            if (keywords.any { matches(it, squashed, words) }) return category
        }
        if (looksLikePersonName(merchant)) return Category.PEOPLE
        return null
    }

    /**
     * Peer-to-peer payments dominate UPI, and a human name is not a merchant.
     * Heuristic: two or more all-alphabetic words, no digits, no company suffix.
     */
    private fun looksLikePersonName(merchant: String): Boolean {
        if (merchant.any { it.isDigit() } || merchant.contains("@")) return false
        val corporate = listOf("private", "limited", "ltd", "pvt", "llp", "inc",
            "technologies", "solutions", "services", "corporation", "enterprise")
        // Trade words. "PHONE WALE" is a shop, not a person — it was booked as
        // People against a ₹25,000 card spend in the real corpus.
        val trade = listOf("wale", "wala", "store", "shop", "mart", "sales", "traders",
            "agency", "centre", "center", "point", "hub", "corner", "stores",
            "collection", "electronics", "mobile", "phone", "studio", "salon",
            "kirana", "provision", "medical", "hardware", "furniture", "opticals")
        val lower = merchant.lowercase()
        if (corporate.any { lower.contains(it) }) return false
        // Joining words belong to institution names, never to a person's:
        // "LIC OF INDIA" and "BANK OF BARODA" are three alphabetic words each and
        // would otherwise read as somebody's name.
        val joiners = setOf("of", "and", "the", "for", "at", "by", "co", "org")
        val words0 = merchant.trim().lowercase().split(Regex("\\s+"))
        if (words0.any { it in joiners }) return false
        if (trade.any { w -> lower.split(Regex("\\s+")).any { it == w } }) return false
        val words = merchant.trim().split(Regex("\\s+"))
        return words.size in 2..4 && words.all { w -> w.length > 1 && w.all { it.isLetter() } }
    }

    /**
     * A keyword matches as a whole word anywhere, or as a substring only if it is
     * long enough to be rare.
     *
     * Plain substring matching kept producing silent cross-category collisions:
     * "rto" fired inside "spoRTOpia" and made a sports club a vehicle expense;
     * "bar" fired inside "bankofBARoda". Both were found by tests rather than by
     * reading the rules, which is the point — short fragments appear inside
     * unrelated words constantly, and the failure is invisible in aggregate.
     */
    private const val SAFE_SUBSTRING_LENGTH = 5

    private fun matches(keyword: String, squashed: String, words: List<String>): Boolean =
        words.contains(keyword) ||
            (keyword.length >= SAFE_SUBSTRING_LENGTH && squashed.contains(keyword)) ||
            // Short brand names — uber, ola, jio, cred — cannot match by
            // substring without colliding, but they are safe as a PREFIX:
            // "uberindiasystem187204" starts with "uber", while "bankofbaroda"
            // does not start with "bar" and "sportomic" does not start with
            // "rto". Without this, a four-letter brand was unmatchable.
            squashed.startsWith(keyword)

    private fun normalise(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Stable key for the learned-category table, so casing and spacing don't fork rows. */
    fun merchantKey(merchant: String): String = normalise(merchant)
}

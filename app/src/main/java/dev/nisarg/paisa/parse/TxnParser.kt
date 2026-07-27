package dev.nisarg.paisa.parse

/**
 * Pure function: a captured raw event -> a best-effort transaction.
 *
 * Deliberately rule-based and deliberately unfinished. Phase 1 exists to collect
 * the real Google Pay and bank SMS strings; this v1 is a starting point written
 * against generic Indian payment phrasing, NOT against observed data.
 *
 * Contract that matters: this is a pure function over stored rows. It can be
 * re-run over the entire capture history at any time, and PARSER_VERSION lets
 * two versions be diffed over identical input.
 */
object TxnParser {

    const val PARSER_VERSION = 3

    // ---- inputs / outputs -------------------------------------------------

    data class Input(
        val source: String,          // "notification" | "sms"
        val packageName: String? = null,
        val sender: String? = null,
        val title: String? = null,
        val body: String? = null,
        /**
         * The user's own names, UPI IDs and account handles, supplied by the
         * caller from the `self_identities` table — never hardcoded here.
         * A payment whose counterparty is the user is money moving between their
         * own accounts, so it is SELF_TRANSFER rather than spending.
         */
        val selfIdentities: Set<String> = emptySet(),
    ) {
        /**
         * Title first, then body — title usually carries the amount.
         *
         * The separator is a pipe, and a pipe is in the merchant patterns' stop
         * set, so a merchant sitting at the end of the title cannot swallow the
         * body text after it.
         */
        val text: String
            get() = listOfNotNull(title, body).filter { it.isNotBlank() }.joinToString(" | ")
    }

    /**
     * SELF_TRANSFER exists because of a real finding in the first capture:
     * credit-card bill payments arrive as "Payment of Rs.18750 was credited to
     * your card". Counting those as CREDIT inflates income; counting them as
     * DEBIT double-counts spending, because the card purchases were already
     * captured individually when they happened. They are neither. Money moving
     * between the user's own accounts must be its own class or every total is
     * wrong.
     */
    enum class Direction { DEBIT, CREDIT, SELF_TRANSFER, UNKNOWN }
    enum class Instrument { UPI, CARD, WALLET, UNKNOWN }

    data class Result(
        val parserVersion: Int = PARSER_VERSION,
        val direction: Direction,
        val amountMinor: Long?,
        val merchantRaw: String?,
        val instrument: Instrument,
        val refId: String?,
        val confidence: Double,
        val matchedRules: List<String>,
        /** Non-null means "this is not a completed spend" — failures, requests, OTPs. */
        val rejectedReason: String?,
    )

    // ---- rule 0: reject things that are not completed transactions ---------

    private val REJECT_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("\\bfailed\\b", RegexOption.IGNORE_CASE) to "failed",
        Regex("\\b(cancelled|canceled)\\b", RegexOption.IGNORE_CASE) to "cancelled",
        Regex("\\bdeclined\\b", RegexOption.IGNORE_CASE) to "declined",
        Regex("\\bunsuccessful\\b", RegexOption.IGNORE_CASE) to "unsuccessful",
        Regex("\\breversed\\b", RegexOption.IGNORE_CASE) to "reversed",
        Regex("\\b(is )?requesting\\b", RegexOption.IGNORE_CASE) to "request",
        Regex("\\brequest(ed|s)?\\s+(you\\s+)?(for\\s+)?₹", RegexOption.IGNORE_CASE) to "request",
        Regex("\\bpayment request\\b", RegexOption.IGNORE_CASE) to "request",
        Regex("\\breminder\\b", RegexOption.IGNORE_CASE) to "reminder",
        Regex("\\bOTP\\b") to "otp",
        Regex("\\bone[- ]time password\\b", RegexOption.IGNORE_CASE) to "otp",
        Regex("\\bwill be (debited|deducted|charged)\\b", RegexOption.IGNORE_CASE) to "future",
        Regex("\\b(due|overdue)\\b", RegexOption.IGNORE_CASE) to "due",
        Regex("\\bpending\\b", RegexOption.IGNORE_CASE) to "pending",
        Regex("\\blow balance\\b", RegexOption.IGNORE_CASE) to "balance_alert",
        Regex("\\bclear the debit balance\\b", RegexOption.IGNORE_CASE) to "reminder",
        // A mutual fund confirming a SIP is not a second payment — the bank SMS
        // for the same rupees is the authoritative record of money leaving the
        // account. Counting both doubles every SIP.
        Regex("\\bSIP under folio\\b", RegexOption.IGNORE_CASE) to "third_party_confirmation",
        Regex("\\breported your Fund bal\\b", RegexOption.IGNORE_CASE) to "statement",
        Regex("\\btraded value for\\b", RegexOption.IGNORE_CASE) to "statement",
    )

    /**
     * Marketing markers. Only consulted when NO direction verb matched, so a real
     * transaction that happens to carry a link or a "TnC" footer — the HOTSTAR
     * AutoPay receipts do exactly that — is never discarded.
     */
    private val PROMO_PATTERNS = listOf(
        Regex("https?://", RegexOption.IGNORE_CASE),
        Regex("\\bT&C\\b", RegexOption.IGNORE_CASE),
        Regex("\\boffer\\b", RegexOption.IGNORE_CASE),
        Regex("\\blimit (?:is now )?increased\\b", RegexOption.IGNORE_CASE),
        Regex("\\bincrease limit\\b", RegexOption.IGNORE_CASE),
        Regex("\\brewards alert\\b", RegexOption.IGNORE_CASE),
        Regex("\\bgreat news\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcompete\\b", RegexOption.IGNORE_CASE),
        Regex("\\brecharge now\\b", RegexOption.IGNORE_CASE),
        Regex("\\bexpiring on\\b", RegexOption.IGNORE_CASE),
        // Every bank and biller in this corpus writes transaction alerts in
        // English. Gujarati script (U+0A80–U+0AFF) appears only in Jio marketing,
        // and this is consulted solely when no transaction verb was found.
        Regex("[\\u0A80-\\u0AFF]"),
    )

    /**
     * Marketing that cannot be mistaken for a receipt, rejected regardless of
     * direction.
     *
     * The weaker PROMO_PATTERNS above only apply when no transaction verb was
     * found, so a genuine receipt carrying a link survives. That gate leaks when
     * the advert itself uses transaction words: Paytm's "Rs.2,000 Cashback +
     * Gold ... enter to win big ... Code: CCBP2000. T&C Apply" matched
     * "cashback", resolved to CREDIT, and booked Rs.2,000 of income from an
     * advertisement.
     *
     * These phrases never appear in a real payment confirmation. A bank tells you
     * what moved; it does not invite you to enter anything or apply a code.
     */
    private val DEFINITELY_MARKETING = listOf(
        Regex("\\bT&?Cs?\\s+Apply\\b", RegexOption.IGNORE_CASE),
        Regex("\\benter to win\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwin big\\b", RegexOption.IGNORE_CASE),
        Regex("\\bCode:\\s*[A-Z0-9]{4,}", RegexOption.IGNORE_CASE),
        Regex("\\bavailable for you\\b", RegexOption.IGNORE_CASE),
        Regex("\\bloan offer\\b", RegexOption.IGNORE_CASE),
        Regex("\\bapply now\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpay now to avoid\\b", RegexOption.IGNORE_CASE),
        Regex("\\blowest interest\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpre[- ]?approved\\b", RegexOption.IGNORE_CASE),
    )

    // ---- rule 1: amount ---------------------------------------------------

    private val AMOUNT_PATTERNS: List<Pair<Regex, String>> = listOf(
        // ₹1,234.50  /  Rs. 1234  /  INR 1,234.50
        Regex("(?:₹|\\bRs\\.?|\\bINR)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
            to "amount:symbol_prefix",
        // 1,234.50 INR  /  1234 Rs
        Regex("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:₹|\\bRs\\.?|\\bINR)\\b", RegexOption.IGNORE_CASE)
            to "amount:symbol_suffix",
    )

    // ---- rule 2: direction ------------------------------------------------

    /**
     * Checked BEFORE debit/credit — these read as credits but are not income.
     * Observed in the first capture: 64 credit-card bill payments.
     */
    private val SELF_TRANSFER_PATTERNS = listOf(
        Regex("credited to your card", RegexOption.IGNORE_CASE),
        Regex("received towards your credit card", RegexOption.IGNORE_CASE),
        Regex("payment .{0,40}\\btowards\\b .{0,30}\\bcredit card\\b", RegexOption.IGNORE_CASE),
        // Loading a wallet is not spending; the purchase out of that wallet
        // arrives as its own message and is the one that counts.
        Regex("wallet is successfully loaded", RegexOption.IGNORE_CASE),
    )

    private val DEBIT_PATTERNS = listOf(
        // "Sent Rs.765.00 / From HDFC Bank A/C *9021 / To <merchant>" — the single
        // most common shape in the corpus (1089 of 2419 rows). Bare "Sent", not
        // "you sent", which is why v1 missed all of them.
        Regex("\\bSent\\s+(?:Rs\\.?|₹|INR)\\s*[0-9]", RegexOption.IGNORE_CASE),
        // Bank of Baroda: "Rs.55.00 Dr. from A/C XXXXXX9877 and Cr. to <vpa>"
        Regex("\\bDr\\.?\\s*from\\s+A/C", RegexOption.IGNORE_CASE),
        Regex("\\btransferred\\s+from\\s+A/c", RegexOption.IGNORE_CASE),
        // "Payment of Rs. 95 has been done successfully at <merchant>"
        Regex("\\bhas been done successfully\\b", RegexOption.IGNORE_CASE),
        // "AutoPay (E-mandate) Success! For HOTSTAR Txn Amt:INR499.00" —
        // subscription charges, invisible to v2.
        Regex("\\bAutoPay\\b.{0,40}\\bSuccess\\b", RegexOption.IGNORE_CASE),
        // A biller confirming receipt means the user paid: "Payment of Rs. 73.76
        // has been received ... Jio Number".
        Regex("\\bPayment of\\b.{0,40}\\bhas been received\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdebited\\b", RegexOption.IGNORE_CASE),
        Regex("\\byou (?:paid|sent)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(paid|sent) to\\b", RegexOption.IGNORE_CASE),
        Regex("\\bspent\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdeducted\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwithdrawn\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpurchase\\b", RegexOption.IGNORE_CASE),
    )

    private val CREDIT_PATTERNS = listOf(
        Regex("\\bCredit Alert\\b", RegexOption.IGNORE_CASE),
        // "Update! INR 68,450.00 deposited in HDFC Bank A/c XX9021 ... Salary"
        Regex("\\bdeposited in\\b", RegexOption.IGNORE_CASE),
        Regex("\\byou received\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcredited\\b", RegexOption.IGNORE_CASE),
        Regex("\\breceived from\\b", RegexOption.IGNORE_CASE),
        Regex("\\brefund(ed)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcashback\\b", RegexOption.IGNORE_CASE),
        Regex("\\badded to your\\b", RegexOption.IGNORE_CASE),
    )

    // ---- rule 3: merchant / counterparty ----------------------------------

    /** English function words never appear in a payee name, only in prose. */
    private val PROSE = Regex(
        "\\b(the|you|your|we|our|about|have|has|been|would|love|hear|please|will|" +
            "enjoy|click|register|setup|avoid|kindly)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Everything from the anti-fraud footer onwards is boilerplate, not a payee. */
    private val FOOTER = Regex(
        "\\bNot\\s*You\\??|\\bSMS\\s+BLOCK\\b|\\bCall\\s+1800",
        RegexOption.IGNORE_CASE
    )

    private val MERCHANT_PATTERNS: List<Pair<Regex, String>> = listOf(
        // UPI handles first, and captured whole.
        //
        // The generic rules stop at a full stop, which silently truncated every
        // handle containing one: "vyapar.900000000001@hdfcbank" became "vyapar",
        // collapsing four unrelated shops into a single payee, and
        // "paytm.d99887766554@pty" became "paytm". Merchant identity is the key
        // the learned categories hang off, so a truncated handle teaches the app
        // the wrong thing about every shop that shares the prefix.
        Regex("\\b(?:to|at|towards)\\s*:?\\s*(?:VPA\\s+)?([A-Za-z0-9._-]+@[A-Za-z0-9.-]+)", RegexOption.IGNORE_CASE)
            to "merchant:vpa",
        Regex("\\b(?:paid|sent)\\s+(?:₹|\\bRs\\.?|\\bINR)?\\s*[0-9][0-9,.]*\\s+to\\s+(.+?)(?=\\s+(?:on|for|via|using|from|at)\\b|[.,;!|]|$)", RegexOption.IGNORE_CASE)
            to "merchant:paid_amount_to",
        // The colon form covers Bank of Baroda's "transferred from A/c ...9123
        // to:ACHDR/NSEClearin".
        Regex("\\b(?:to|at|towards)\\s*:\\s*(?:VPA\\s+)?(.+?)(?=\\s+(?:on|for|via|using|from)\\b|[.,;!|]|$)", RegexOption.IGNORE_CASE)
            to "merchant:preposition_colon",
        Regex("\\b(?:to|at|towards)\\s+(?:VPA\\s+)?(.+?)(?=\\s+(?:on|for|via|using|from)\\b|[.,;!|]|$)", RegexOption.IGNORE_CASE)
            to "merchant:preposition",
        Regex("\\breceived\\s+(?:₹|\\bRs\\.?|\\bINR)?\\s*[0-9][0-9,.]*\\s+from\\s+(.+?)(?=\\s+(?:on|for|via|using)\\b|[.,;!|]|$)", RegexOption.IGNORE_CASE)
            to "merchant:received_from",

        // Last resort: the channel, when the bank names no recipient at all.
        //
        // "Amt Deducted! Rs.25000 ... for Money Transfer via HDFC Bank Online
        // Banking" carries no payee — HDFC omits it for net-banking transfers.
        // Left with a null merchant these payments were invisible to the filing
        // screen, which groups by payee, so the largest recurring payment in the
        // corpus (₹25,000/month, 84% of the unfiled value) could never be filed.
        // A channel label is not a payee, but it groups the payments so they can
        // be named once and filed together.
        Regex("\\bfor\\s+([A-Za-z][A-Za-z ]{2,30}?)\\s+via\\s+([A-Za-z][A-Za-z ]{2,40})", RegexOption.IGNORE_CASE)
            to "merchant:channel_fallback",
    )

    // ---- rule 4: reference id ---------------------------------------------

    private val REF_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("(?:UPI\\s*(?:Ref(?:erence)?|RRN)|Ref(?:erence)?|Txn|Transaction)\\s*(?:No\\.?|Number|ID|Id)?\\s*[:\\-]?\\s*([A-Za-z0-9]{6,})", RegexOption.IGNORE_CASE)
            to "ref:labelled",
        Regex("\\b(\\d{12})\\b")
            to "ref:bare_rrn",
    )

    // ---- rule 5: instrument ------------------------------------------------

    private val UPI_PACKAGES = setOf(
        "com.google.android.apps.nbu.paisa.user",  // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",                      // BHIM
        "com.dreamplug.androidapp",                // CRED
    )

    private fun normalise(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // ---- parse -------------------------------------------------------------

    fun parse(input: Input): Result {
        val text = input.text
        val rules = mutableListOf<String>()

        val reject = REJECT_PATTERNS.firstOrNull { it.first.containsMatchIn(text) }?.second
            ?: if (DEFINITELY_MARKETING.any { it.containsMatchIn(text) }) "promotional" else null

        var amount: Long? = null
        for ((re, rule) in AMOUNT_PATTERNS) {
            val m = re.find(text) ?: continue
            val v = Money.toMinor(m.groupValues[1]) ?: continue
            amount = v
            rules += rule
            break
        }

        // A refund landing on a card also reads as "credited to your card", but it
        // is genuine money coming back, not a bill payment.
        val looksLikeRefund = Regex("\\brefund(ed)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val isSelf = !looksLikeRefund && SELF_TRANSFER_PATTERNS.any { it.containsMatchIn(text) }
        val isDebit = DEBIT_PATTERNS.any { it.containsMatchIn(text) }
        val isCredit = CREDIT_PATTERNS.any { it.containsMatchIn(text) }
        val direction = when {
            // Own-account movement wins over both — it is neither spending nor income.
            isSelf -> Direction.SELF_TRANSFER
            isDebit && !isCredit -> Direction.DEBIT
            isCredit && !isDebit -> Direction.CREDIT
            // BOB writes both: "Rs.55 Dr. from A/C <mine> and Cr. to <payee>".
            // The debit clause describes the user's account, so debit wins here.
            isDebit && isCredit -> Direction.DEBIT
            else -> Direction.UNKNOWN
        }
        if (direction != Direction.UNKNOWN) {
            rules += "direction:" + direction.name.lowercase()
        }

        // Bank alerts end with an anti-fraud footer — "Not you? Call 18002586161/
        // SMS BLOCK OB to 7308080808". When a message names no merchant, the
        // extractor otherwise walks into that footer and books the spend against
        // the helpline number. That mis-attributed ₹3.6 lakh in the first corpus.
        val merchantText = text.split(FOOTER)[0]

        var merchant: String? = null
        for ((re, rule) in MERCHANT_PATTERNS) {
            val m = re.find(merchantText) ?: continue
            // The channel fallback captures two groups; join them so the label
            // reads as one thing.
            val candidate = (
                if (rule == "merchant:channel_fallback" && m.groupValues.size > 2)
                    "${m.groupValues[1].trim()} via ${m.groupValues[2].trim()}"
                else m.groupValues[1]
                ).trim().trim('.', ',', ';', '-')
            if (candidate.isEmpty() || candidate.length > 80) continue
            // "We would love to hear about the payment experience you had" —
            // the Jio receipt's closing line was being booked as a payee 13 times.
            // Merchants do not contain English function words.
            if (PROSE.containsMatchIn(candidate)) continue
            merchant = candidate
            rules += rule
            break
        }

        var refId: String? = null
        for ((re, rule) in REF_PATTERNS) {
            val m = re.find(text) ?: continue
            val candidate = m.groupValues[1]
            // "the txn reference" would otherwise yield refId = "reference".
            // A real reference is digit-heavy; a stray English word is not.
            if (candidate.count { it.isDigit() } < 4) continue
            refId = candidate
            rules += rule
            break
        }

        val instrument = when {
            Regex("\\b(credit card|debit card|card ending|card no|XX\\d{4})\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) -> Instrument.CARD
            Regex("\\bwallet\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Instrument.WALLET
            Regex("\\b(UPI|VPA)\\b").containsMatchIn(text) -> Instrument.UPI
            input.packageName in UPI_PACKAGES -> Instrument.UPI
            else -> Instrument.UNKNOWN
        }
        if (instrument != Instrument.UNKNOWN) {
            rules += "instrument:" + instrument.name.lowercase()
        }

        // A payment to the user's own name or UPI handle is not a spend. Compared
        // on alphanumerics only, so "MEHTA ARJUN" and "Mehta-Arjun" and
        // "arjun.mehta@okhdfcbank" all resolve alike.
        val selfMatch = merchant != null && input.selfIdentities.any { identity ->
            val a = normalise(merchant)
            val b = normalise(identity)
            b.isNotEmpty() && (a.contains(b) || b.contains(a))
        }
        if (selfMatch) rules += "self:identity"

        // Marketing SMS quote large rupee figures — loan offers, credit-limit
        // increases — and in the first capture they contributed ₹63 lakh of
        // phantom "unknown" volume, more than every real debit combined. They are
        // only ever discarded when no transaction verb was found.
        val finalReject = reject ?: if (
            direction == Direction.UNKNOWN && PROMO_PATTERNS.any { it.containsMatchIn(text) }
        ) "promotional" else null

        val confidence = if (finalReject != null) 0.0 else
            (if (amount != null) 0.50 else 0.0) +
            (if (direction != Direction.UNKNOWN) 0.25 else 0.0) +
            (if (merchant != null) 0.15 else 0.0) +
            (if (refId != null) 0.10 else 0.0)

        return Result(
            direction = if (selfMatch && direction != Direction.UNKNOWN)
                Direction.SELF_TRANSFER else direction,
            amountMinor = amount,
            merchantRaw = merchant,
            instrument = instrument,
            refId = refId,
            confidence = confidence,
            matchedRules = rules,
            rejectedReason = finalReject,
        )
    }
}

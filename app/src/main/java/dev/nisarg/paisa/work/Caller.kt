package dev.nisarg.paisa.work

/**
 * Who is calling about your spending today, and what mood they are in.
 *
 * A push notification about money is the most swipeable object on a phone. An
 * incoming call is the least. The joke is the delivery mechanism, not decoration
 * — but a joke that says the same thing every night stops being one, so the
 * caller is chosen by what you actually did today.
 *
 * Spend nothing and your savings account rings to say thank you. Blow through
 * the day's allowance and it escalates until the Enforcement Directorate is on
 * the line "purely as a formality, sir". The number on the screen is identical
 * either way; only the messenger changes, and that is what makes it read.
 *
 * Every line is absurd enough that it can never be mistaken for a real demand,
 * and the app's own name sits on the screen underneath.
 */
object Caller {

    enum class Mood { JOY, CALM, CONCERN, ALARM, DOOM }

    data class Persona(val name: String, val line: String, val mood: Mood)

    private val JOY = listOf(
        Persona("YOUR SAVINGS ACCOUNT", "just ringing to say thank you.", Mood.JOY),
        Persona("MUMMY", "beta, aaj toh kuch kharcha hi nahi kiya!", Mood.JOY),
        Persona("FUTURE YOU", "whatever you did today, keep doing it.", Mood.JOY),
        Persona("YOUR CA", "I have nothing to complain about. Unsettling.", Mood.JOY),
        Persona("YOUR EMERGENCY FUND", "I EXIST? since when?", Mood.JOY),
        Persona("YOUR MUTUAL FUND", "someone finally loves me.", Mood.JOY),
        Persona("GOD", "no notes. carry on.", Mood.JOY),
        Persona("THE SWIGGY ALGORITHM", "you have hurt me and I respect it.", Mood.JOY),
    )

    private val CALM = listOf(
        Persona("YOUR CA", "nothing to report. rare, but nice.", Mood.CALM),
        Persona("THE AUDITOR", "boringly fine. carry on.", Mood.CALM),
        Persona("YOUR BANK MANAGER", "no reason. genuinely just a chat.", Mood.CALM),
        Persona("FUTURE YOU", "on track. do not get cocky.", Mood.CALM),
        Persona("AN ANONYMOUS WELL-WISHER", "no reason. keep going.", Mood.CALM),
        Persona("NPCI SERVER ROOM", "routine ping. you are fine.", Mood.CALM),
        Persona("YOUR UPI ID", "we have been busy. not badly.", Mood.CALM),
        Persona("THE MIDDLE PATH", "sustainable. boring. correct.", Mood.CALM),
    )

    private val CONCERN = listOf(
        Persona("YOUR BANK MANAGER", "a friendly chat. mostly friendly.", Mood.CONCERN),
        Persona("THE LANDLORD", "just checking in. no reason at all.", Mood.CONCERN),
        Persona("MUMMY", "beta... kitna kharcha kiya aaj?", Mood.CONCERN),
        Persona("YOUR CA", "we should talk about today.", Mood.CONCERN),
        Persona("THE SWIGGY DELIVERY GUY", "we have grown close. I am worried.", Mood.CONCERN),
        Persona("YOUR FORMER SELF (2019)", "we had plans, remember?", Mood.CONCERN),
        Persona("THE PETROL PUMP GUY", "back so soon?", Mood.CONCERN),
        Persona("YOUR STEP COUNT", "unrelated. but also concerned.", Mood.CONCERN),
    )

    private val ALARM = listOf(
        Persona("INCOME TAX DEPT", "routine audit. nothing to worry about.", Mood.ALARM),
        Persona("GST COUNCIL", "you are not in trouble. yet.", Mood.ALARM),
        Persona("YOUR CREDIT CARD", "I think we should see other people.", Mood.ALARM),
        Persona("THE AUDITOR", "found something interesting today.", Mood.ALARM),
        Persona("FRAUD DEPARTMENT", "we assumed it was fraud. it was not.", Mood.ALARM),
        Persona("THE ZOMATO ALGORITHM", "I know what you did. I enabled it.", Mood.ALARM),
        Persona("YOUR EMERGENCY FUND", "there is no emergency fund.", Mood.ALARM),
        Persona("EVERY ATM IN AHMEDABAD", "we compared notes.", Mood.ALARM),
    )

    private val DOOM = listOf(
        Persona("ENFORCEMENT DIRECTORATE", "purely a formality, sir.", Mood.DOOM),
        Persona("YOUR WALLET", "calling from the ICU.", Mood.DOOM),
        Persona("FUTURE YOU", "calling from 2045. we need to discuss.", Mood.DOOM),
        Persona("RBI GOVERNOR", "took a personal interest in your case.", Mood.DOOM),
        Persona("YOUR FUTURE CHILDREN", "we heard about the bike.", Mood.DOOM),
        Persona("THE GHOST OF YOUR SIP", "you cancelled me. for THIS?", Mood.DOOM),
        Persona("GOD", "even I saw that one.", Mood.DOOM),
        Persona("CONFERENCE CALL: CA + MUMMY", "we have all been talking.", Mood.DOOM),
    )

    /**
     * @param spentTodayMinor what today cost.
     * @param allowancePerDayMinor what the cycle can afford per remaining day.
     * @param cycleOverspent already past this cycle's pay, whatever today looked like.
     * @param epochDay rotates the voice within a mood so the same one never lands
     *   twice running. Deterministic, because a summary posted twice in a day
     *   should not change its mind about who is calling.
     */
    fun forSpend(
        spentTodayMinor: Long,
        allowancePerDayMinor: Long,
        cycleOverspent: Boolean,
        epochDay: Long,
    ): Persona {
        val roster = when {
            cycleOverspent -> DOOM
            spentTodayMinor == 0L -> JOY
            // With no allowance to compare against there is nothing to judge, so
            // stay neutral rather than inventing alarm.
            allowancePerDayMinor <= 0L -> CALM
            else -> when (spentTodayMinor.toDouble() / allowancePerDayMinor) {
                in 0.0..0.5 -> JOY
                in 0.5..1.0 -> CALM
                in 1.0..2.0 -> CONCERN
                else -> ALARM
            }
        }
        return roster[(epochDay.mod(roster.size.toLong())).toInt()]
    }

    /** Every persona, for tests and for the roster preview. */
    val all: List<Persona> get() = JOY + CALM + CONCERN + ALARM + DOOM

    /**
     * The line that actually lands: your own biggest payment of the day, read
     * back to you by someone who is disappointed about it.
     *
     * A generic joke is funny once. "Rs.1,240 at Tandoor Junction. Again." is funny
     * every time, because it is specific, true, and you cannot argue with it.
     * This is the only place the app is allowed a personality, so it uses the
     * one thing no other app has — the actual receipt.
     */
    fun jibe(merchant: String?, amountMinor: Long, count: Int, mood: Mood): String? {
        if (merchant.isNullOrBlank() || amountMinor <= 0) {
            return when (mood) {
                Mood.JOY -> "Not one rupee. Who ARE you."
                Mood.DOOM -> "Nothing today. The damage is already done."
                else -> null
            }
        }
        val m = merchant.take(24).uppercase()
        val rs = "Rs.${amountMinor / 100}"
        return when (mood) {
            Mood.JOY -> listOf(
                "$rs at $m. Practically monastic.",
                "$rs at $m and that was IT? Proud.",
            ).random()
            Mood.CALM -> listOf(
                "$rs at $m. Reasonable. Suspiciously so.",
                "$rs at $m. Filed under 'fine'.",
            ).random()
            Mood.CONCERN -> listOf(
                "$rs at $m. Again.",
                "$rs at $m. Was that necessary?",
                "$rs at $m. I am not angry, just curious.",
            ).random()
            Mood.ALARM -> listOf(
                "$rs at $m. Explain.",
                "$rs at $m. In THIS economy?",
                "$rs at $m and $count payments total. Bold.",
            ).random()
            Mood.DOOM -> listOf(
                "$rs at $m. We are past discussing this.",
                "$rs at $m. Your money died doing what it loved.",
                "$rs at $m. I have forwarded this upstairs.",
            ).random()
        }
    }

    /** What the answer button says, because "ANSWER" is not a joke. */
    fun answerLabel(mood: Mood): String = when (mood) {
        Mood.JOY -> "ANSWER  —  TAKE THE COMPLIMENT"
        Mood.CALM -> "ANSWER  —  GET IT OVER WITH"
        Mood.CONCERN -> "ANSWER  —  EXPLAIN YOURSELF"
        Mood.ALARM -> "ANSWER  —  COOPERATE FULLY"
        Mood.DOOM -> "ANSWER  —  ACCEPT YOUR FATE"
    }

    fun declineLabel(mood: Mood): String = when (mood) {
        Mood.JOY -> "decline (rude)"
        Mood.CALM -> "decline"
        Mood.CONCERN -> "decline (cowardly)"
        Mood.ALARM -> "decline (they will call back)"
        Mood.DOOM -> "decline (it will not help)"
    }

    /** Caller-ID chrome, degrading exactly as a real one would not. */
    fun subtitle(mood: Mood, missed: Int = 0): String {
        val tail = if (missed > 0) " · $missed missed" else ""
        return when (mood) {
            Mood.JOY -> "mobile · india · spam risk: none$tail"
            Mood.CALM -> "mobile · india · verified caller$tail"
            Mood.CONCERN -> "mobile · india · calls frequently$tail"
            Mood.ALARM -> "unknown number · india · do not ignore$tail"
            Mood.DOOM -> "withheld number · calling from inside the house$tail"
        }
    }

    /**
     * The timer text, which loses its composure the longer you leave it ringing.
     * A counter that only counts is a counter you stop reading.
     */
    fun ringingLabel(elapsedMs: Long): String {
        val mmss = "%d:%02d".format(elapsedMs / 60000, (elapsedMs / 1000) % 60)
        return when {
            elapsedMs < 10_000 -> "ringing  $mmss"
            elapsedMs < 22_000 -> "still ringing  $mmss"
            elapsedMs < 40_000 -> "they are not hanging up  $mmss"
            elapsedMs < 70_000 -> "this is your life now  $mmss"
            else -> "we can do this all night  $mmss"
        }
    }

    /**
     * What they say the instant you pick up, before the app gets on with it.
     * Answering should cost something too.
     */
    fun greeting(mood: Mood): String = when (mood) {
        Mood.JOY -> "\"...that is genuinely all I wanted to say. Bye.\""
        Mood.CALM -> "\"Right. Shall we get this over with.\""
        Mood.CONCERN -> "\"Sit down. No, properly sit down.\""
        Mood.ALARM -> "\"Do not hang up. I have the statement open.\""
        Mood.DOOM -> "\"I have taken the liberty of opening a file.\""
    }
}

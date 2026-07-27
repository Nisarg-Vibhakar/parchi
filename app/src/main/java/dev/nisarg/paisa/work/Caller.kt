package dev.nisarg.paisa.work

/**
 * Who is calling about your spending today.
 *
 * A push notification about money is the most swipeable object on a phone. An
 * incoming call is the least. The joke is the delivery mechanism, not decoration
 * — you will not ignore a call from the Income Tax Department twice, and by the
 * time you have worked out it is Parchi, you have already read the number.
 *
 * The caller rotates by day so the gag does not wear out, and every line is
 * obviously absurd so it can never be mistaken for a real demand.
 */
object Caller {

    data class Persona(val name: String, val line: String)

    private val ROSTER = listOf(
        Persona("INCOME TAX DEPT", "routine audit. nothing to worry about."),
        Persona("YOUR CA", "we need to talk about this month."),
        Persona("MUMMY", "beta, kitna kharcha kiya aaj?"),
        Persona("FUTURE YOU", "calling from 2045. we need to discuss."),
        Persona("THE LANDLORD", "just checking in. no reason."),
        Persona("YOUR WALLET", "calling from the ICU."),
        Persona("RBI GOVERNOR", "took a personal interest in your case."),
        Persona("GST COUNCIL", "you are not in trouble. yet."),
        Persona("YOUR SAVINGS ACCOUNT", "long time no see."),
        Persona("ENFORCEMENT DIRECTORATE", "purely a formality, sir."),
        Persona("SWIGGY DELIVERY GUY", "concerned about you personally."),
        Persona("YOUR BANK MANAGER", "no reason. just a friendly chat."),
        Persona("THE AUDITOR", "found something interesting."),
        Persona("YOUR CREDIT CARD", "we should see other people."),
    )

    /**
     * Rotates by day so the same caller never lands twice running, and the whole
     * roster is seen before repeating. Deterministic, because a summary that
     * arrives twice on one day should not change its mind about who is calling.
     */
    fun forDay(epochDay: Long): Persona = ROSTER[(epochDay.mod(ROSTER.size.toLong())).toInt()]

    val size: Int get() = ROSTER.size
}

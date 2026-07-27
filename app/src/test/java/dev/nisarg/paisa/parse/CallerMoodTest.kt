package dev.nisarg.paisa.parse

import dev.nisarg.paisa.work.Caller
import dev.nisarg.paisa.work.Caller.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caller is chosen by what the day cost. A joke that says the same thing
 * every night stops being one; the escalation is what makes it read.
 */
class CallerMoodTest {

    private fun who(spent: Long, allowance: Long, over: Boolean = false, day: Long = 0) =
        Caller.forSpend(spent * 100, allowance * 100, over, day)

    @Test fun `spending nothing gets a happy caller`() =
        assertEquals(Mood.JOY, who(0, 1000).mood)

    @Test fun `well under the allowance is still joy`() =
        assertEquals(Mood.JOY, who(300, 1000).mood)

    @Test fun `near the allowance is calm`() =
        assertEquals(Mood.CALM, who(800, 1000).mood)

    @Test fun `over the allowance raises concern`() =
        assertEquals(Mood.CONCERN, who(1500, 1000).mood)

    @Test fun `far over the allowance is alarm`() =
        assertEquals(Mood.ALARM, who(4000, 1000).mood)

    /** Overspending the cycle outranks a quiet day — the hole is still there. */
    @Test fun `an overspent cycle is doom whatever today looked like`() =
        assertEquals(Mood.DOOM, who(0, 1000, over = true).mood)

    /** With nothing to compare against, inventing alarm would be dishonest. */
    @Test fun `no allowance means no judgement`() =
        assertEquals(Mood.CALM, who(5000, 0).mood)

    @Test fun `the voice rotates within a mood`() =
        assertNotEquals(who(1500, 1000, day = 1).name, who(1500, 1000, day = 2).name)

    @Test fun `the same day gives the same caller`() =
        assertEquals(who(1500, 1000, day = 9).name, who(1500, 1000, day = 9).name)

    @Test fun `dates before the epoch do not crash`() =
        assertTrue(who(1500, 1000, day = -5).name.isNotBlank())

    @Test fun `every persona has a name and a line`() {
        for (p in Caller.all) {
            assertTrue(p.name.isNotBlank())
            assertTrue(p.line.isNotBlank())
        }
    }

    @Test fun `every mood has someone to call`() {
        val moods = Caller.all.map { it.mood }.toSet()
        assertEquals(Mood.entries.toSet(), moods)
    }

    // ---- the jibe ----------------------------------------------------------

    /**
     * The line that actually lands is the user's own biggest payment read back to
     * them. A generic joke is funny once; "Rs.1240 at TANDOOR JUNCTION. Again." is funny
     * every time, because it is specific and true.
     */
    @Test fun `the jibe quotes the real payee and amount`() {
        val j = Caller.jibe("Tandoor Junction", 124000L, 5, Mood.CONCERN)!!
        assertTrue(j, j.contains("1240"))
        assertTrue(j, j.contains("TANDOOR JUNCTION"))
    }

    @Test fun `a spotless day still gets a line`() =
        assertTrue(Caller.jibe(null, 0, 0, Mood.JOY)!!.isNotBlank())

    @Test fun `an unremarkable day with no payee stays quiet`() =
        assertEquals(null, Caller.jibe(null, 0, 0, Mood.CALM))

    @Test fun `a very long payee name cannot break the layout`() {
        val j = Caller.jibe("A".repeat(90), 10000L, 1, Mood.ALARM)!!
        assertTrue("payee should be trimmed, was ${j.length}", j.length < 80)
    }

    @Test fun `every mood has a jibe, an answer label and a decline label`() {
        for (m in Mood.entries) {
            assertTrue(Caller.jibe("Shop", 5000L, 2, m)!!.isNotBlank())
            assertTrue(Caller.answerLabel(m).isNotBlank())
            assertTrue(Caller.declineLabel(m).isNotBlank())
            assertTrue(Caller.subtitle(m, 0).isNotBlank())
        }
    }

    /** The button must never just say "ANSWER" — that is not a joke. */
    @Test fun `answer labels differ by mood`() {
        val labels = Mood.entries.map { Caller.answerLabel(it) }.toSet()
        assertEquals(Mood.entries.size, labels.size)
    }

    // ---- losing composure --------------------------------------------------

    /** A counter that only counts is a counter you stop reading. */
    @Test fun `the ringing label escalates the longer it is ignored`() {
        val labels = listOf(1_000L, 15_000L, 30_000L, 50_000L, 90_000L)
            .map { Caller.ringingLabel(it).substringBefore("  ") }
        assertEquals("each stage should read differently", labels.size, labels.toSet().size)
    }

    @Test fun `the ringing label always carries the clock`() =
        assertTrue(Caller.ringingLabel(65_000L).contains("1:05"))

    @Test fun `missed calls appear in the caller id`() =
        assertTrue(Caller.subtitle(Mood.ALARM, 3).contains("3 missed"))

    @Test fun `no missed calls means no missed-call line`() =
        assertTrue(!Caller.subtitle(Mood.JOY, 0).contains("missed"))

    @Test fun `every mood has something to say when you pick up`() {
        val greetings = Mood.entries.map { Caller.greeting(it) }
        assertEquals(Mood.entries.size, greetings.toSet().size)
        for (g in greetings) assertTrue(g.isNotBlank())
    }

    @Test fun `the roster is big enough not to repeat within a week`() {
        for (m in Mood.entries) {
            val n = Caller.all.count { it.mood == m }
            assertTrue("$m has only $n voices", n >= 7)
        }
    }
}

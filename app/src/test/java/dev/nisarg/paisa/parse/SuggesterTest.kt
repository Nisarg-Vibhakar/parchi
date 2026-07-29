package dev.nisarg.paisa.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggester answers "food, fun or people?" from what has already been
 * decided, not from a model. These pin the order the evidence is trusted in,
 * which is the only real design decision in it.
 */
class SuggesterTest {


    @Test fun `an answer for this exact payee beats everything else`() {
        val s = Suggester.suggest(
            Suggester.Evidence(
                learnedForMerchant = "PEOPLE",
                ruleGuess = "FOOD",
                similarVotes = mapOf("FUN" to 5),
                favourites = listOf("SHOPPING"),
            )
        )
        assertEquals(Categoriser.Category.PEOPLE, s.first().category)
        assertTrue(s.first().why.contains("before"))
    }

    @Test fun `a rule beats the crowd when the payee is new`() {
        val s = Suggester.suggest(
            Suggester.Evidence(ruleGuess = "FOOD", favourites = listOf("SHOPPING", "BILLS"))
        )
        assertEquals(Categoriser.Category.FOOD, s.first().category)
    }

    @Test fun `enough similar payees can outweigh a rule`() {
        val s = Suggester.suggest(
            Suggester.Evidence(ruleGuess = "FOOD", similarVotes = mapOf("FUN" to 4))
        )
        assertEquals(Categoriser.Category.FUN, s.first().category)
    }

    @Test fun `every suggestion carries a reason`() {
        val s = Suggester.suggest(
            Suggester.Evidence(
                ruleGuess = "FOOD",
                similarVotes = mapOf("FUN" to 2),
                favourites = listOf("PEOPLE"),
            )
        )
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { it.why.isNotBlank() })
    }

    @Test fun `nothing to go on means no suggestion, not a guess`() =
        assertTrue(Suggester.suggest(Suggester.Evidence()).isEmpty())

    @Test fun `popularity alone is not worth a hint`() =
        assertNull(Suggester.hint(Suggester.Evidence(favourites = listOf("FOOD", "BILLS"))))

    @Test fun `a rule is worth a hint`() {
        val h = Suggester.hint(Suggester.Evidence(ruleGuess = "FOOD"))
        assertTrue(h != null && h.contains("FOOD"))
    }

    @Test fun `unknown category names are ignored rather than crashing`() =
        assertTrue(
            Suggester.suggest(Suggester.Evidence(learnedForMerchant = "NOT_A_CATEGORY")).isEmpty()
        )

    // ---- merchant similarity ----------------------------------------------

    @Test fun `generic words are not evidence of similarity`() =
        assertEquals(emptySet<String>(), Suggester.tokens("THE STORE PVT LTD INDIA"))

    @Test fun `distinctive words survive`() =
        assertTrue(Suggester.tokens("SHREE RASOI KATHIYAWADI").contains("kathiyawadi"))

    @Test fun `short fragments are not tokens`() =
        assertTrue(Suggester.tokens("A B CD XYZ").isEmpty())
}

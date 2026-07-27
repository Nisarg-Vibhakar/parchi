package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.Categoriser.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Merchants taken verbatim from the first real corpus. */
class CategoriserTest {

    private fun cat(m: String) = Categoriser.byRule(m)

    @Test fun `swiggy variants are food`() {
        assertEquals(Category.FOOD, cat("PYU*Swiggy Food"))
        assertEquals(Category.FOOD, cat("RAZ*Swiggy"))
        assertEquals(Category.FOOD, cat("BUNDL TECHNOLOGIES PRIVAT"))
    }

    @Test fun `local eateries are food`() {
        assertEquals(Category.FOOD, cat("Shree Rasoi Kathiyawadi"))
        assertEquals(Category.FOOD, cat("ASTODIA FOOD CORNER"))
        assertEquals(Category.FOOD, cat("KARANS KHAU GALLI"))
    }

    @Test fun `transport providers`() {
        assertEquals(Category.TRANSPORT, cat("Rapido"))
        assertEquals(Category.TRANSPORT, cat("UBER INDIA SYSTEMS PRIVAT"))
        assertEquals(Category.TRANSPORT, cat("GUJARAT STATE ROAD TRANSPORT CORPORATION"))
        assertEquals(Category.TRANSPORT, cat("paytm-citybus9@ptybl"))
    }

    @Test fun `fuel`() = assertEquals(Category.FUEL, cat("SUNRISE PETROLEUM"))

    @Test fun `shopping`() {
        assertEquals(Category.SHOPPING, cat("CAS*FLIPKART INTERNET"))
        assertEquals(Category.SHOPPING, cat("_LIFE STYLE INTERN"))
    }

    @Test fun `investment`() {
        assertEquals(Category.INVESTMENT, cat("ACHDR/NSEClearin"))
        assertEquals(Category.INVESTMENT, cat("ZERODHA BROKING LIMITED"))
    }

    @Test fun `health`() = assertEquals(Category.HEALTH, cat("VRAJ MEDICAL  GENER"))

    @Test fun `sports`() {
        assertEquals(Category.SPORTS, cat("DROPSHOT PICKLEBALL ACADEMY"))
        assertEquals(Category.SPORTS, cat("Sportomic"))
    }

    @Test fun `bills`() = assertEquals(Category.BILLS, cat("JIO PREPAID RECHARGE"))

    /**
     * Card-bill aggregators are transfers, not bills. Paying one settles
     * purchases that were each already captured — booking it as spending
     * double-counts the whole statement, which overstated one real cycle by
     * ₹31,840 against a ₹42k true figure.
     */
    @Test fun `card bill aggregators are transfers not bills`() {
        assertEquals(Category.TRANSFER, cat("cred"))
        assertEquals(Category.TRANSFER, cat("CheQ"))
        assertEquals(Category.TRANSFER, cat("CHEQ DIGITAL PRIVATE LIMI"))
    }

    @Test fun `transfers and investments are not spending`() {
        assertEquals(false, Category.TRANSFER.isSpending)
        assertEquals(false, Category.INVESTMENT.isSpending)
        assertEquals(true, Category.FOOD.isSpending)
        assertEquals(true, Category.PEOPLE.isSpending)
    }

    // ---- person detection --------------------------------------------------

    @Test fun `plain human names are people`() {
        assertEquals(Category.PEOPLE, cat("CHAMANSINGH KISHANSINGH KADECHA"))
        assertEquals(Category.PEOPLE, cat("RADHA CHAMAN SINGH"))
        assertEquals(Category.PEOPLE, cat("Dhiraj Sonkar"))
    }

    @Test fun `companies are not classified as people`() {
        assertNull(cat("SHANTINATH ELECTRONICS PA"))
    }

    /** A keyword rule outranks the person heuristic, which is the intended order. */
    @Test fun `a company matching a keyword rule keeps that category`() {
        assertEquals(Category.SPORTS, cat("BLUEJERSEY18 TECHNOLOGIES PRIVATE LIMITED"))
    }

    @Test fun `upi handles are not people`() = assertNull(cat("q11223344@ybl"))

    @Test fun `unknown merchant returns null rather than guessing`() =
        assertNull(cat("Sy Nos 8 1 to 55 6"))

    @Test fun `blank is null`() = assertNull(cat(""))

    // ---- key stability -----------------------------------------------------

    @Test fun `merchant key ignores case and punctuation`() =
        assertEquals(Categoriser.merchantKey("PYU*Swiggy Food"), Categoriser.merchantKey("pyu swiggyfood"))

    /** Real miss: a ₹25,000 card spend at a phone shop was booked as a person. */
    @Test fun `shops with trade words are not people`() {
        assertNull(cat("PHONE WALE"))
        assertNull(cat("SHARMA STORE"))
        assertNull(cat("GUPTA TRADERS"))
    }

    @Test fun `real person names still resolve after the trade guard`() {
        assertEquals(Category.PEOPLE, cat("Dhiraj Sonkar"))
        assertEquals(Category.PEOPLE, cat("RADHA CHAMAN SINGH"))
    }

    // ---- vehicle running costs --------------------------------------------

    @Test fun `vehicle service and insurance`() {
        assertEquals(Category.VEHICLE, cat("SHRADHA MOTORS"))
        assertEquals(Category.VEHICLE, cat("WWW ACKO COM"))
        assertEquals(Category.VEHICLE, cat("Sharma Tyre House"))
    }

    @Test fun `fuel stays separate from vehicle upkeep`() =
        assertEquals(Category.FUEL, cat("SUNRISE PETROLEUM"))

    /**
     * Guards a real collision: matching is on a normalised substring, so the
     * three-letter "rto" fired inside "spoRTOpia" and reclassified a sports club
     * as a vehicle expense.
     */
    @Test fun `short keywords do not collide across categories`() {
        assertEquals(Category.SPORTS, cat("Sportomic"))
        assertEquals(Category.SPORTS, cat("DROPSHOT PICKLEBALL ACADEMY"))
        assertNull(cat("BANK OF BARODA"))          // "bar" inside "baroda"
    }

    /** Short keywords still work when they are genuinely a word. */
    @Test fun `short keywords match as whole words`() {
        assertEquals(Category.FUN, cat("THE BAR HOUSE"))
        assertEquals(Category.TRANSPORT, cat("AUTO STAND"))
    }

    // ---- insurance ---------------------------------------------------------

    @Test fun `protection premiums are insurance`() {
        assertEquals(Category.INSURANCE, cat("HDFC LIFE INSURANCE"))
        assertEquals(Category.INSURANCE, cat("STAR HEALTH"))
        assertEquals(Category.INSURANCE, cat("NIVA BUPA"))
    }

    /**
     * Vehicle insurers resolve to VEHICLE, not INSURANCE, so the bike's premium
     * stays part of the bike's running cost instead of being split across two
     * views of the same object.
     */
    @Test fun `vehicle insurance counts toward the vehicle`() {
        assertEquals(Category.VEHICLE, cat("WWW ACKO COM"))
    }

    /**
     * LIC is genuinely ambiguous — endowment and money-back policies are savings,
     * a term plan is an expense. The app must ask rather than be confidently
     * wrong about a recurring amount.
     */
    @Test fun `LIC is left unclassified rather than guessed`() =
        assertNull(cat("LIC OF INDIA"))

    /**
     * Real miss: institution names carry joining words, personal names do not.
     * "LIC OF INDIA" is three alphabetic words and was read as a person.
     */
    @Test fun `institutions with joining words are not people`() {
        assertNull(cat("LIC OF INDIA"))
        assertNull(cat("BANK OF BARODA"))
    }

    // ---- household staff ---------------------------------------------------

    @Test fun `household staff services`() {
        assertEquals(Category.HELP, cat("URBAN COMPANY"))
        assertEquals(Category.HELP, cat("HOUSEKEEPING SERVICE"))
    }

    /**
     * The realistic case: staff are paid to a personal handle, so the rules guess
     * PEOPLE and the user corrects it once. This pins that the guess is at least
     * sane rather than random — the correction is what makes it right.
     */
    @Test fun `staff paid to a personal handle first read as people`() =
        assertEquals(Category.PEOPLE, cat("Sunita Devi"))

    @Test fun `help is real spending`() = assertEquals(true, Category.HELP.isSpending)
}

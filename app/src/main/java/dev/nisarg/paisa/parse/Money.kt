package dev.nisarg.paisa.parse

/**
 * Money is always an integer count of paise. Never a float, anywhere, ever.
 *
 * "1,234.50" -> 123450
 * "1,234.5"  -> 123450
 * "1234"     -> 123400
 * "1,00,000" -> 10000000   (Indian digit grouping)
 */
object Money {

    fun toMinor(raw: String): Long? {
        val cleaned = raw.replace(",", "").trim()
        if (cleaned.isEmpty()) return null

        val parts = cleaned.split(".")
        if (parts.size > 2) return null

        val whole = parts[0]
        if (whole.isEmpty() || !whole.all { it.isDigit() }) return null

        val fracRaw = if (parts.size == 2) parts[1] else ""
        if (!fracRaw.all { it.isDigit() }) return null
        if (fracRaw.length > 2) return null

        // "5" means 50 paise, "50" means 50 paise, "" means 0 paise
        val frac = fracRaw.padEnd(2, '0')

        return try {
            whole.toLong() * 100 + (if (frac.isEmpty()) 0L else frac.toLong())
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** For display and logs only. Never feed this back into arithmetic. */
    fun format(minor: Long): String {
        val sign = if (minor < 0) "-" else ""
        val abs = kotlin.math.abs(minor)
        return "%s₹%d.%02d".format(sign, abs / 100, abs % 100)
    }
}

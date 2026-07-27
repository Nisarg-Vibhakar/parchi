package dev.nisarg.paisa.ui

/**
 * What a launcher intent puts on screen.
 *
 * The back-tap gesture and the app icon fire the identical launcher intent —
 * Motorola's gesture list offers only "Open app" — so they cannot be told apart,
 * as intents or otherwise. An earlier attempt guessed, from lock state and
 * whether the screen was on, and got it wrong in the one case the app exists
 * for: you back-tap a phone you have just paid with, and that phone is unlocked
 * and awake, so every real gesture read as an icon tap and was sent to the full
 * receipt. The slip never appeared.
 *
 * There is no honest signal, so the slip wins the ambiguity — the gesture is the
 * product, and the receipt is one tap away from the slip. Nothing about the
 * device may influence this decision, which is why nothing about the device is
 * passed to it.
 */
object CaptureRouting {

    enum class Slip {
        /** An uncategorised spend arrived recently: one tap files it. */
        CONFIRM,

        /** Nothing to confirm: today's figure, and the keypad one tap away. */
        SUMMARY,
    }

    /**
     * The keypad is deliberately not reachable from here. Landing on a live
     * number field puts the cursor in front of the figure you opened the slip to
     * read, and invites an entry for a payment the bank SMS is about to bring in
     * anyway. It is a button on the summary instead.
     *
     * @param hasPending an uncategorised spend inside the window
     */
    fun decide(hasPending: Boolean): Slip = if (hasPending) Slip.CONFIRM else Slip.SUMMARY
}

# What the real data changed

Every rule in this codebase that looks arbitrary is a scar. This is the list, so
the next person to touch the parser knows which lines are load-bearing.

The corpus is 3,309 real payment messages from one Indian phone — Bank of
Baroda, HDFC, and a long tail of billers — captured verbatim and replayed
against each parser version.

## The parser was 27% right and looked fine

The first version was written against *plausible* Indian payment phrasing. It
extracted **100% of amounts correctly**, which made it look like it worked.

Direction was **27% correct**.

| | v1 | final |
|---|---|---|
| Amount extracted | 100% | 100% |
| Direction identified | 27% | 98% |
| Phantom "unknown" volume | ₹63,04,180 | ₹2,33,575 |

What the guesses got wrong:

- **HDFC writes a bare `Sent Rs.765.00`**, not "you sent". 1,089 messages — 45%
  of the corpus — and every single one was missed.
- **Bank of Baroda uses `Dr.` and `Cr.`** abbreviations, which no amount of
  reasoning would have produced.

The lesson is not "test your regexes". It is that **a metric can be 100% correct
and still be measuring the wrong thing** — amount accuracy told us nothing about
whether the app knew money was coming in or going out.

## Money that was never spent

Three separate ways the totals were wrong, all found by measurement:

**Credit-card bill payments** arrive as `Payment of Rs.18750 was credited to your
card`. Counting them as credit inflates income. Counting them as debit
double-counts, because every purchase on that card was already captured when it
happened. They are neither — hence `SELF_TRANSFER`.

One real cycle was overstated by **₹31,840 against a ₹38,000 true figure**. The
worst case was a UPI payment to a merchant literally named "CheQ", a card-bill
aggregator, which looked like an ordinary shop.

**Promotional SMS** quote large rupee figures — loan offers, credit-limit
increases. They contributed **₹63 lakh of phantom volume, more than every real
debit combined**. They are now rejected, but *only when no transaction verb is
present*, so a genuine receipt carrying a link or a `TnC` footer still survives.

**Investments** convert cash into an asset. Excluded from spending for the same
reason a card bill is.

## Payee extraction, four ways

- **Anti-fraud footers.** `Not you? Call 18002586161/SMS BLOCK OB to
  7308080808`. Messages naming no payee fell through and booked **₹3.6 lakh
  against a helpline number**.
- **Prose.** `We would love to hear about the payment experience you had` became
  a payee 13 times. Merchants do not contain English function words.
- **UPI handle truncation.** The generic rules stopped at a full stop, so
  `vyapar.900000000001@hdfcbank` became `vyapar` and **four unrelated shops
  merged into one payee**. Payee identity is the key learned categories hang
  off, so a truncated handle teaches the app the wrong thing about every shop
  sharing a prefix.
- **Unnamed payees.** HDFC names no recipient for net-banking transfers. Left
  null, those payments were invisible to a filing screen that groups by payee —
  and they were **84% of the unfiled value**: six ₹25,000 monthly transfers.

## Keyword matching was silently wrong

Rules matched by substring against a normalised merchant. Two collisions, both
found by tests rather than by reading the rules:

- `rto` fired inside `spoRTOpia`, making a sports club a vehicle expense.
- `bar` fired inside `bankofBARoda`.

Removing keywords one at a time was whack-a-mole; the *matching* was wrong. A
keyword now matches as a whole word anywhere, or as a substring only above five
characters. **Short fragments appear inside unrelated words constantly, and the
failure is invisible in aggregate** — nobody spots one mis-filed shop among 751.

Related: `LIC OF INDIA` and `BANK OF BARODA` were classified as *people*, being
three alphabetic words. Institution names carry joining words; personal names do
not.

## A state that could never happen

`Cycle.Pace` had four states. `BEHIND` was unreachable.

The overrun test was "runway shorter than days left", which reduces
algebraically to "spent > on pace" — the identical test as `BEHIND`, so
`OVERRUN` always fired first. Overrun is now judged on the projected total with
a tolerance band, and a test pins that `BEHIND` is reachable.

Found by writing a test that asserted the obvious.

## Things that are deliberately absent

- **No rule for `LIC`.** Endowment and money-back policies are savings; a term
  plan is an expense. Only the holder knows which. The app asks rather than
  being confidently wrong about a recurring amount every month.
- **No hardcoded identity.** The user's own names and UPI handles live in a
  table. Identity does not belong in a parser.
- **No SMS sender allowlist.** Indian bank sender ids vary by circle and
  operator; any list would silently drop messages.

## The design decision that paid for all of it

**Store raw first, parse second, never throw anything away.**

Parsing is a pure function over stored rows. Every fix above was found *after*
collection and replayed over the entire history in about 90 seconds. Four parser
versions were evaluated against the same 2,805 messages without collecting
anything twice.

Without it, each of these findings would have cost another week of waiting.

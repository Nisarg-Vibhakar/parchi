# Paisa — Phase 1: Silent Capture

**Date:** 2026-07-27
**Status:** Approved, ready for implementation plan
**Owner:** Nisarg

---

## Problem

Expenses are impossible to reconstruct after the fact. Recording them manually at
the moment of payment requires discipline that does not survive contact with real
life. The result is spending that is invisible until the money is gone.

## Insight

99% of the user's spending is UPI or card. Every one of those payments already
fires an Android notification, and most also arrive as a bank SMS. The data is
already on the phone, arriving in real time, for free.

Therefore the product is not data entry. It is **capture plus one question**:

> Capture everything silently. Ask the user *what it was for* — at the cheapest
> possible moment, and only when it cannot be guessed.

Phase 1 builds the capture half only. The question half is Phase 2, and will be
designed against real captured data rather than assumptions.

## Goals (in priority order, per user)

1. **Awareness** — know where the money went. This is the home screen in Phase 2.
2. **Control** — budgets and limits. Deferred until there is enough history to
   set them honestly.
3. **Goals** — saving toward something specific. Later.

Explicitly *not* a goal: business/personal separation for tax or reimbursement.

## Non-goals for Phase 1

- No modal, no category picker, no home screen
- No back-tap integration
- No LLM, no HALT, no categorization of any kind
- No budgets, no charts, no exports beyond raw data dump

---

## Constraints

| Constraint | Consequence |
|---|---|
| Sideload only, single user | `READ_SMS` is permitted. No Play Store review, no privacy policy, no accounts. |
| Motorola Edge 50 Pro | Back-tap exposes a fixed action list. Only `Open app` is usable — a **single** entry point, not three. Gentle/medium/hard is a sensitivity slider, not three assignments. |
| Payment apps | Google Pay today. Jupiter possibly later — its package name will be **discovered at runtime**, not hardcoded. |
| Device | Aggressive Motorola battery optimization will kill background listeners silently. |
| Build environment | No Android Studio. CLI-only: JDK 17 + Android cmdline-tools + Gradle wrapper, all under `$HOME`, no root. |

---

## Core principle

> **Store raw first. Parse second. Never throw anything away.**

The listener writes each notification and SMS **verbatim** — full text, full extras
bundle. Parsing is a separate, versioned, pure function that reads those rows and
writes to a second table. It never mutates raw data.

This is the decision that makes the phase worth doing. It means one capture
session buys unlimited parser iterations: a better rule written on day 9 can be
re-run retroactively over everything already collected, and the results diffed
against the previous version. Without it, every parser fix costs another round of
data collection.

---

## Architecture

| Component | Responsibility |
|---|---|
| `PaymentNotificationListener` | `NotificationListenerService`. Writes raw rows for allowlisted payment packages. Records package name only (never content) for non-matching notifications. |
| `PaymentSmsReceiver` | `BroadcastReceiver` on `SMS_RECEIVED`. Catches card/bank transactions that never produce a notification. |
| `SmsBackfillJob` | One-shot read of `content://sms/inbox` on first run. Imports existing message history. |
| `RawEventDao` | Room. Append-only. The source of truth. |
| `TxnParser` | Pure function `RawEvent -> ParsedTxn?`. Versioned. Re-runnable over history. |
| `HeartbeatWorker` | Writes a row every 6 hours so listener downtime is detectable. |
| `DebugActivity` | One screen: event count, last N events, permission status, Export button. |
| `Exporter` | Dumps `raw_events` + `parsed_txn` to JSON/CSV for off-device analysis. |

No Jetpack Compose in Phase 1. The debug UI is one list and one button; plain
views keep the dependency tree and build times small, which matters on a disk with
9 GB free. Compose can be reconsidered for the Phase 2 modal, where design
actually matters.

---

## Data model

```sql
raw_events                          -- append-only, verbatim, never edited
  id             INTEGER PK
  source         TEXT      -- 'notification' | 'sms'
  package_name   TEXT      -- e.g. com.google.android.apps.nbu.paisa.user
  sender         TEXT      -- SMS sender id (e.g. 'AD-HDFCBK'); null for notifications
  title          TEXT
  body           TEXT      -- verbatim
  extras_json    TEXT      -- full Bundle dump; useful fields often hide in subText
  posted_at      INTEGER   -- epoch ms, from the notification/SMS itself
  captured_at    INTEGER   -- when we observed it
  notif_key      TEXT      -- dedupe key; notifications update themselves in place
```

```sql
parsed_txn                          -- derived, disposable, regenerable
  id             INTEGER PK
  raw_event_id   INTEGER FK -> raw_events.id
  parser_version INTEGER   -- enables diffing v1 vs v2 over identical input
  direction      TEXT      -- 'debit' | 'credit' | 'unknown'
  amount_minor   INTEGER   -- PAISE, integer
  merchant_raw   TEXT
  instrument     TEXT      -- 'upi' | 'card' | 'wallet' | 'unknown'
  ref_id         TEXT      -- UPI reference / transaction id
  confidence     REAL
  matched_rule   TEXT      -- which rule fired, so failures are debuggable
  parsed_at      INTEGER
```

```sql
unmatched_packages                  -- discovery aid; package names only, no content
  package_name   TEXT PK
  first_seen     INTEGER
  count          INTEGER
```

```sql
heartbeat
  id             INTEGER PK
  at             INTEGER
```

### Load-bearing decisions

**`amount_minor` is an integer count of paise.** Money never touches a float.
`₹1,234.50` becomes `123450`. Free to do now, miserable to retrofit.

**`direction` is captured from day one.** Credits — salary, refunds, friends
repaying — arrive through the same channels. Without direction, the spending total
silently includes incoming money and is wrong in a way that is hard to notice.

**`ref_id` exists for deduplication.** A single payment frequently lands twice:
once as a Google Pay notification, once as a bank SMS, sharing a UPI reference.
Unhandled, this roughly doubles card spending totals. This is the most likely way
the app would quietly lie to the user.

**`unmatched_packages` records package names only, never content.** After a
capture session it reveals which payment apps were missed — including Jupiter's
package name, whenever it gets installed.

---

## Parsing

Five independent extractors, each recording which rule fired:

1. **Amount** — `₹` / `Rs.` / `INR` followed by digits with optional commas and
   decimals, normalised to paise
2. **Direction** — `paid`, `sent`, `debited`, `spent` → debit;
   `received`, `credited`, `refund` → credit
3. **Merchant** — capture following `to`, `at`, `towards`
4. **Ref ID** — UPI reference number, `Txn ID`, `Ref No`
5. **Confidence** — a function of how many of the above resolved

Rules and keyword sets only. No ML, no model, no HALT. `matched_rule` is stored on
every row so a mis-parse can be diagnosed rather than guessed at.

Google Pay's exact notification formats are **not** written from memory. They are
collected first (see Validation), and the parser is written against real strings.

---

## Staying alive

Motorola's battery optimiser will kill a background listener, and the failure mode
is invisible: no crash, no error, spending simply reads zero and resembles a frugal
week.

- Request battery-optimisation exemption on first run, plus a guided walkthrough to
  Motorola's **Optimized battery → No restrictions**
- `BOOT_COMPLETED` receiver and `requestRebind()` for recovery
- **Heartbeat row every 6 hours.** A gap in heartbeats proves the listener was
  dead, converting silent data loss into detectable data loss

---

## Validation protocol

Rather than waiting a week of passive collection, the user performs a **designed
set of ten transactions** immediately after install, then exports everything for
analysis. Each transaction targets a distinct parser risk.

| # | Transaction | What it tests |
|---|---|---|
| 1 | Pay a friend via phone number, ₹1 | Person-to-person format |
| 2 | Pay a friend via UPI ID, ₹1 | UPI ID format vs contact name |
| 3 | Scan a merchant QR, ₹1 | Merchant format — the most common real case |
| 4 | Pay with a note/message attached, ₹1 | Whether the note displaces the merchant |
| 5 | Non-round amount, ₹1.50 | Decimal handling |
| 6 | Amount ≥ ₹1,000 (self-transfer is fine) | **Comma parsing** — highest-risk regex case |
| 7 | Bill payment or mobile recharge | Biller format, differs from merchant |
| 8 | Friend sends ₹1 back | **Direction** — must not count as spending |
| 9 | A deliberately failed/cancelled payment | Must **not** be recorded as spend |
| 10 | Any transaction that also produces a bank SMS | **Deduplication** via `ref_id` |

Additionally, `SmsBackfillJob` imports existing SMS history on first run, providing
months of real bank messages to test against immediately — no waiting required.

Export is then pulled off-device and the parser is written and iterated against it.

---

## Testing

The parser is a pure function over stored rows, so it tests cleanly:

- **Golden-file tests** over a fixture corpus of real captured strings. Every
  export grows the corpus; every parser bug found becomes a permanent fixture.
- **Dedupe test** — one payment arriving as both notification and SMS with a
  matching `ref_id` yields one transaction, not two
- **Direction test** — credits are never counted as spending
- **Money test** — `₹1,234.50` → `123450`; no float appears anywhere in the path
- **Room DAO tests** on an in-memory database

The fixture corpus is the durable asset of this phase.

---

## Definition of done

- APK installs and runs on the Motorola Edge 50 Pro
- Notification access and SMS permissions granted, with in-app guidance
- Ten designed transactions captured, plus SMS history backfilled
- Export produces a file that can be pulled off-device
- Parser achieves a measured accuracy against that corpus — the actual number is
  the finding, and it determines the Phase 2 design

---

## Phase 2 (sketch — not in scope)

Back-tap → `Open app` → a translucent, dialog-themed Activity with
`setShowWhenLocked(true)`, appearing over the lock screen without unlocking.
Context-aware single entry point:

- Uncategorised payment in the last few minutes → confirm screen, six category
  tiles ranked by likelihood, one tap
- Nothing pending → manual quick-add for the rare cash case
- All caught up → today's total

Target: under three seconds from payment to recorded, phone never unlocked.
Later candidates, in rough order of value: learned merchant→category memory,
end-of-day swipe review, recurring-payment detection, anomaly nudges, geofence
pre-fill, split ledger.

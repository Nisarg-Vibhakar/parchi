# Parchi

*parchi* — the little paper slip a shopkeeper hands you.

Expense capture for Android that asks you nothing until it has to.

Payment notifications and bank SMS are read automatically, stored verbatim, and
parsed into transactions. The only thing you are ever asked is **what a payment
was for** — and only when no rule and no past answer can work it out.

**Status:** Phase 1 (capture) complete and validated against 2,805 real messages.
Phase 2 (the back-tap modal) built and working on device. GPay notification
capture is written but not yet exercised against real notifications.

See [the spec](docs/superpowers/specs/2026-07-27-expense-capture-phase1-design.md).

## The back-tap

Settings → Gestures → back-tap → **Open app** → Paisa.

The launcher activity *is* the modal. It is translucent, shows over the lock
screen, and decides what to show from what is waiting — never from what the
phone is doing, because the gesture and the app icon fire the identical intent
and cannot be told apart:

| Situation | What you get |
|---|---|
| Uncategorised spend in the last 30 min | Amount, payee, six category tiles. One tap. |
| Nothing pending | Today's total and this week's, then back out |

Nothing arrived yet? **+ ADD A PAYMENT** on that second slip opens the keypad —
type the amount, pick a category. It is a button rather than the landing screen
on purpose: a live number field in front of the figure you opened the slip to
read invites an entry for a payment the bank SMS is about to bring in anyway.

Manual entry exists because of a measured failure, not a hunch: **Google Pay
posts no notification for payments you make yourself**, and the bank SMS that
does carry them runs minutes late — or, below the bank's alert threshold, is
never sent at all. A ₹1 test payment stayed invisible indefinitely.

When the bank SMS does arrive for an amount you already entered, the manual row
is **superseded, not duplicated** — matched on amount within a 20-minute window,
keeping the bank's record because it carries the payee and reference.

Pay → double-tap the back → tap a tile → done. The phone never unlocks, and the
transaction was already saved before the screen opened, so dismissing loses
nothing.

Each tap teaches it: the answer is written to `merchant_categories` and applied
to every past *and* future payment to that merchant. It asks less every week.

## Build

No Android Studio required. Everything lives under `$HOME`, nothing needs root.

```bash
source env.sh
$GRADLE :app:testDebugUnitTest    # parser tests, JVM only, fast
$GRADLE :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
source env.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open **Parchi** and work through the three buttons at the top, in order:

1. **Grant notification access** — this is a Settings screen, not a normal
   permission prompt. Find Paisa in the list and enable it.
2. **Grant SMS permission** — normal runtime prompt.
3. **Disable battery optimisation** — then *also* set
   Settings → Apps → Paisa → Battery → **Unrestricted**. Motorola will kill the
   listener otherwise, silently, and your spending will read zero.

All three must show `OK` at the top of the screen.

## Collect

Press **Backfill SMS inbox** first — that imports months of existing bank
messages immediately, so the parser can be measured today rather than next week.

Then run the ten designed transactions from the spec's validation protocol. They
are chosen to cover distinct parser risks: comma parsing, decimals, direction,
merchant vs person, failed payments, and notification/SMS duplication. Ten random
payments would exercise one format ten times and teach us nothing.

## Get the data out

Press **EXPORT**, then:

```bash
adb pull /sdcard/Android/data/dev.nisarg.paisa/files/exports/
```

A copy also lands in the phone's **Downloads** folder if the cable isn't handy.

## Reports

```bash
python3 tools/report.py exports/exports/paisa-export-*.json -o report.html
```

A self-contained spending report — monthly trend, category split, biggest
destinations, most frequent habits. No network, nothing leaves the machine.

## Layout

```
parse/TxnParser.kt   pure function, no Android imports, unit tested
parse/Categoriser.kt merchant -> category; learned answers beat seeded rules
parse/Money.kt       paise as integers, never floats
data/PaisaDb.kt      append-only raw_events + regenerable parsed_txn
capture/             notification listener, SMS receiver, inbox backfill
work/Heartbeat.kt    6-hourly liveness rows so downtime is detectable
export/Exporter.kt   JSON dump
ui/CaptureActivity.kt  the back-tap modal — launcher activity
ui/DebugActivity.kt    capture diagnostics and permission status
tools/report.py        spending report generator
tools/analyze_export.py parser accuracy analysis
```

## The one rule

`raw_events` is append-only and never edited. `parsed_txn` is derived and
disposable — **Re-parse everything** rebuilds it from scratch with the current
parser. That is what lets a parser fix be replayed over the entire capture
history without collecting anything again.

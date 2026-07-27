# Parchi — state of play

Written 27 Jul 2026, at the end of the first build session. Read this before
touching anything; it records what is verified, what merely compiles, and what is
known broken.

**254 tests passing · 35 commits · APK 1.1 MB · installed on the Motorola Edge 50 Pro**

---

## Verified working on the real device

These were exercised against real data or a real phone, not just built.

| | evidence |
|---|---|
| SMS capture | 3,312 messages captured, 501 of them carrying balances |
| SMS inbox backfill | imported months of history on first run |
| Parser | direction accuracy 27% → 98% across four replays |
| Reparse over history | ~90 seconds for 3,312 rows, run dozens of times |
| Back-tap → modal | measured 137ms gesture to on-screen |
| Confirm → category → learned | filed a real ₹11 payment, remembered permanently |
| Manual entry + reconciliation | ₹1,111 manual entry superseded by a matching bank SMS |
| Rapid filing | settled 170 payments in one action |
| Bulk settle | ₹178,600 unfiled → ₹156,661 |
| Balance reconciliation | flagged ₹1,610 unexplained on account …9123 |
| Identity matching | 240 suggestions from 1,089 payees, top ones correct |
| Budgets | eight buckets set, grouping and 2-cycle periods working |
| Daily summary | fired on demand, correct figures |
| Call screen | took over the screen while unlocked, `topResumedActivity=CallActivity` |
| Launcher intent → slip | today's figure on an unlocked, awake phone, then + ADD A PAYMENT → keypad → CANCEL → back, all verified on device |

## Compiles and installs, but never confirmed by a human

- **Ringtone audio.** The five clips are bundled and the code path runs without
  error, but nobody has confirmed hearing them. See the open bug below.
- **The 9pm alarm actually firing.** Only ever triggered manually over adb. The
  alarm is scheduled and reschedules on boot, but a real 9pm has not happened yet.
- **Snooze callback after two hours.** Same — the path is written, never waited out.
- **Vibration.** `VIBRATE` was missing entirely until late in the session, so
  every buzz before that was a silent no-op. Since adding it, unconfirmed.

---

## Open bugs

### 1. Ringtone may still be muted — UNRESOLVED

Logcat showed:

```
AudioHardening background playback would be muted for dev.nisarg.paisa, level: full
new player ... attr: usage=USAGE_UNKNOWN content=CONTENT_TYPE_UNKNOWN
```

**Diagnosis:** `MediaPlayer.create()` prepares the player internally, so
`setAudioAttributes()` called afterwards is silently ignored and the stream stays
`USAGE_UNKNOWN`. Android then treats it as background media and mutes it.

**Fix applied:** build `MediaPlayer` by hand and set attributes *before*
`prepare()` (`Ringer.playClip`).

**Why it is still open:** after the fix a fresh run still logged
`usage=USAGE_UNKNOWN` — but that line is emitted when the player first registers
with AudioService, before attributes apply, so it does not prove failure. The
encouraging signal is that no new `AudioHardening` mute warnings appeared after
the fix. **Nobody has confirmed audio by ear.**

Next steps if it is still silent:
- Check `dumpsys audio` for the *active* player rather than the registration line
- Try `AudioManager.STREAM_RING` via the legacy `setAudioStreamType` path
- Consider a foreground service of type `phoneCall`, which is what real dialers
  use and what exempts them from hardening entirely
- Motorola may have its own background-audio restriction in Settings → Battery

### 2. The call screen does not always stay up

`am start` reports `Status: ok` and `Displayed … +282ms`, but seconds later
`topResumedActivity` is sometimes the launcher. It renders and then goes away.
Not diagnosed. Suspects: `excludeFromRecents` + `singleTask` interaction,
Motorola's background-activity policy, or the screen being dozed at launch.

`FLAG_KEEP_SCREEN_ON` and `FLAG_DISMISS_KEYGUARD` were added late and are
unverified.

### 3. Google Pay notifications are effectively unproven

`notif=3` after a full day, and **all three were marketing** — a loan offer, a
cashback advert, a bill reminder. Not one real payment notification.

Confirmed by measurement: **Google Pay posts no notification for payments you
make yourself.** The listener is provably alive (it logged WhatsApp ×62,
Termux ×17, Swiggy ×5 in the same period). So the notification path, which was
the original premise of the whole app, captures almost nothing. **Bank SMS is the
real channel.**

### 4. A ₹1 payment is invisible

Below the bank's SMS alert threshold, and GPay posts nothing. It is not
recoverable — this is what manual entry and balance reconciliation exist for.

---

## Things that were tried and abandoned

- **Full-screen intent alone.** Android downgrades it to a heads-up whenever the
  screen is unlocked, by design. Needed `SYSTEM_ALERT_WINDOW` plus a direct
  `startActivity` to take the screen.
- **Raising a notification channel's importance.** Impossible after creation —
  only the user can, in settings. Had to ship a new channel id (`incoming_call_v2`).
- **Synthesised ringtones.** Built, tested, and kept as a fallback, but they were
  clever rather than funny. Real recordings replaced them.
- **Guessing the gesture from device state.** `fd2649b` tried to tell the back-tap
  from the app icon by lock state and screen-on, so that the icon could land on
  the receipt. Both fire the identical launcher intent, and the guess was wrong in
  the only case that matters: you back-tap a phone you have just paid with, and
  that phone is unlocked and awake, so every real gesture read as an icon tap and
  was sent to Home. The slip never appeared unless something was already pending.
  There is no honest signal, so the slip now wins the ambiguity unconditionally
  (`CaptureRouting`) and the receipt is reached from the slip.
- **Auto-merging identities.** Deliberately never built. Merging two different
  people corrupts every figure afterwards with no symptom; it only ever suggests.
- **A rule for LIC.** Deliberately absent — endowment policies are savings, term
  plans are expenses, and only the holder knows which.

---

## What is left, in order of value

1. **Split the six ₹25,000 rent payments.** Ten minutes. Fixes ₹149,250 and makes
   the largest number in the app true. Until then the Rent bucket reads
   ₹25,000 / ₹9,000 and drowns everything.
2. **File the top payees.** 652 unfiled. Five payees hold 87% of the value.
3. **Review the 240 merge suggestions.**
4. **A budget edit screen** — the last thing that still needs a developer.
5. Confirm the ringtone actually rings.

Deliberately *not* on this list: goals (meaningless until buckets have run a
cycle), charts (the receipt already answers "am I fine" in one line), and HALT
(a separate project).

---

## Resuming the build

```bash
cd ~/Desktop/paisa
source env.sh                       # JDK 17 + SDK + Gradle, all under $HOME
$GRADLE :app:testDebugUnitTest      # 254 tests, JVM only, ~5s warm
$GRADLE :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**No Android Studio.** The toolchain lives in `~/android-tools` and `~/Android/Sdk`.

`/usr/bin/adb` is version 28 from 2022 and **cannot see this phone**. Always use
the SDK one — `env.sh` puts it first on `PATH`.

### Permissions that must be re-granted after a fresh install

```bash
adb shell cmd notification allow_listener \
  dev.nisarg.paisa/dev.nisarg.paisa.capture.PaymentNotificationListener
adb shell pm grant dev.nisarg.paisa android.permission.POST_NOTIFICATIONS
adb shell appops set dev.nisarg.paisa SYSTEM_ALERT_WINDOW allow
adb shell appops set dev.nisarg.paisa USE_FULL_SCREEN_INTENT allow
```

### Driving the app without touching the phone

```bash
B="adb shell am broadcast -n dev.nisarg.paisa/.ui.DebugReceiver -a dev.nisarg.paisa"
$B.STATUS          # row counts
$B.BACKFILL        # re-read the SMS inbox
$B.REPARSE         # rebuild parsed_txn with the current parser
$B.EXPORT          # write JSON to /sdcard/Android/data/.../files/exports/
$B.CALL_NOW                      # the real 9pm summary
$B.CALL_NOW --es mood DOOM       # audition a mood: JOY CALM CONCERN ALARM DOOM
$B.SETTLE_SMALL                  # file small pre-cycle payments as Other
$B.SELF_ADD --es value "'NAME'"  # add a self identity
$B.SET_BUCKET --es label "'Food'" --es categories "'FOOD'" --el rupees 5500 --ei period 1
$B.SIMULATE_SMS --es body "'Sent Rs.100.00 From HDFC Bank A/C *9021 To X'"
$B.DELETE_RAW --el id 1234       # remove a row and its parse

adb pull /sdcard/Android/data/dev.nisarg.paisa/files/exports/ ./exports/
python3 tools/analyze_export.py exports/exports/<latest>.json   # parser accuracy
python3 tools/report.py         exports/exports/<latest>.json -o report.html
```

Quoting matters: `--es` values need inner single quotes or the device shell
splits them on spaces. A whole test message once arrived as the single word
"Sent".

---

## Things worth not forgetting

- `raw_events` is append-only. `parsed_txn` is disposable and regenerates. That
  one rule is why every parser fix could be replayed over the full history.
- The tables holding what the user taught the app — learned categories, aliases,
  splits, self identities, custom categories, one-offs, merges, buckets — cannot
  be regenerated. They are all in the export. Keep it that way.
- Money is integer paise everywhere. Never a float.
- `exports/` and `report.html` are gitignored: they contain real bank messages.
  Test fixtures are anonymised — an early commit leaked a real name, UPI ID and
  three card tails, and the history was rebuilt to remove them.
- Every categorisation keyword under five characters must match as a whole word
  or a prefix. `rto` matched inside `spoRTOpia`; `bar` inside `bankofBARoda`.
- `docs/FINDINGS.md` is the interesting document. It records what real data
  changed, and it is the best thing in this repository to show someone.

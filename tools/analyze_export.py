#!/usr/bin/env python3
"""
Analyse a Paisa export and tell us how good the parser actually is.

    python3 tools/analyze_export.py paisa-export-*.json

This is the instrument that turns a capture session into parser work. It answers,
in order: what did we fail to parse, what did we parse wrongly, and what did we
count twice.
"""

import json
import re
import sys
from collections import Counter, defaultdict


def load(path):
    with open(path) as f:
        d = json.load(f)
    raw = {r["id"]: r for r in d["raw_events"]}
    parsed = d["parsed_txn"]
    return d, raw, parsed


def rupees(minor):
    if minor is None:
        return "—"
    return f"₹{minor // 100}.{abs(minor) % 100:02d}"


def text_of(r):
    return " — ".join(x for x in (r.get("title"), r.get("body")) if x)


def section(title):
    print(f"\n{'=' * 72}\n{title}\n{'=' * 72}")


def main(path):
    d, raw, parsed = load(path)

    print(f"export      {path}")
    print(f"device      {d.get('device')}")
    print(f"parser v{d.get('parser_version')}")
    print(f"raw events  {len(raw)}   parsed rows {len(parsed)}")

    # ---- coverage ------------------------------------------------------
    section("COVERAGE")

    rejected = [p for p in parsed if p.get("rejected_reason")]
    live = [p for p in parsed if not p.get("rejected_reason")]
    no_amount = [p for p in live if p.get("amount_minor") is None]
    no_direction = [p for p in live if p.get("direction") == "UNKNOWN"]
    no_merchant = [p for p in live if not p.get("merchant_raw")]
    confident = [p for p in live if (p.get("confidence") or 0) >= 0.75]

    n = len(live) or 1
    print(f"live rows        {len(live)}")
    print(f"direction split  {dict(Counter(p.get('direction') for p in live))}")
    print(f"rejected         {len(rejected)}  {dict(Counter(p['rejected_reason'] for p in rejected))}")
    print(f"missing amount   {len(no_amount):4d}  ({100*len(no_amount)/n:.0f}%)   <-- parser gaps")
    print(f"missing direction{len(no_direction):4d}  ({100*len(no_direction)/n:.0f}%)   <-- DANGEROUS, may miscount credits")
    print(f"missing merchant {len(no_merchant):4d}  ({100*len(no_merchant)/n:.0f}%)")
    print(f"confident >=0.75 {len(confident):4d}  ({100*len(confident)/n:.0f}%)")

    # ---- what failed ---------------------------------------------------
    section("FAILED TO EXTRACT AN AMOUNT  (write new rules for these)")
    for p in no_amount[:40]:
        r = raw.get(p["raw_event_id"], {})
        print(f"\n  #{r.get('id')} [{r.get('source')}] {r.get('package_name') or r.get('sender')}")
        print(f"    {text_of(r)[:220]}")
    if len(no_amount) > 40:
        print(f"\n  ... and {len(no_amount) - 40} more")

    section("AMOUNT FOUND BUT DIRECTION UNKNOWN  (highest-risk bucket)")
    for p in no_direction[:30]:
        r = raw.get(p["raw_event_id"], {})
        print(f"\n  #{r.get('id')} {rupees(p.get('amount_minor'))}")
        print(f"    {text_of(r)[:220]}")

    # ---- which rules are carrying the load -----------------------------
    section("RULE HIT COUNTS")
    hits = Counter()
    for p in live:
        for rule in (p.get("matched_rule") or "").split(","):
            if rule:
                hits[rule] += 1
    for rule, c in hits.most_common():
        print(f"  {c:5d}  {rule}")

    # ---- duplication ---------------------------------------------------
    section("SUSPECTED DOUBLE-COUNTING")

    by_ref = defaultdict(list)
    for p in live:
        if p.get("ref_id"):
            by_ref[p["ref_id"]].append(p)
    dupe_refs = {k: v for k, v in by_ref.items() if len(v) > 1}
    print(f"same ref_id seen more than once: {len(dupe_refs)}")
    for ref, ps in list(dupe_refs.items())[:10]:
        srcs = [raw.get(p["raw_event_id"], {}).get("source") for p in ps]
        print(f"  {ref}  x{len(ps)}  sources={srcs}  {rupees(ps[0].get('amount_minor'))}")

    # amount+minute collisions catch duplicates that carry no ref id
    by_amt_min = defaultdict(list)
    for p in live:
        if p.get("amount_minor") is None:
            continue
        r = raw.get(p["raw_event_id"], {})
        minute = (r.get("posted_at") or 0) // 60000
        by_amt_min[(p["amount_minor"], minute)].append(p)
    near = {k: v for k, v in by_amt_min.items() if len(v) > 1}
    print(f"\nsame amount within the same minute: {len(near)}")
    for (amt, _), ps in list(near.items())[:10]:
        srcs = [raw.get(p["raw_event_id"], {}).get("source") for p in ps]
        print(f"  {rupees(amt)}  x{len(ps)}  sources={srcs}")

    # ---- merchant sanity ------------------------------------------------
    section("MERCHANTS EXTRACTED  (look for junk — dates, 'UPI', truncation)")
    merchants = Counter(p["merchant_raw"] for p in live if p.get("merchant_raw"))
    for m, c in merchants.most_common(40):
        flag = ""
        if re.search(r"\d{2}[-/]\d{2}", m) or len(m) > 40 or m.lower() in ("upi", "vpa", "a/c"):
            flag = "   <-- looks wrong"
        print(f"  {c:4d}  {m!r}{flag}")

    # ---- totals ---------------------------------------------------------
    section("TOTALS  (sanity check against what you actually spent)")
    def total(direction):
        return sum(p["amount_minor"] for p in live
                   if p.get("direction") == direction and p.get("amount_minor"))

    print(f"  debits         {rupees(total('DEBIT'))}")
    print(f"  credits        {rupees(total('CREDIT'))}")
    print(f"  self-transfers {rupees(total('SELF_TRANSFER'))}   (card bills — NOT spending, NOT income)")
    print(f"  unknown        {rupees(total('UNKNOWN'))}   <-- unclassified, distorts everything above")

    # ---- listener liveness ----------------------------------------------
    section("LISTENER LIVENESS")
    beats = sorted(b["at"] for b in d.get("heartbeat", []))
    print(f"  heartbeats: {len(beats)}")
    gaps = [(b - a) / 3600000 for a, b in zip(beats, beats[1:]) if b - a > 8 * 3600000]
    if gaps:
        print(f"  !! {len(gaps)} gap(s) over 8h — listener was probably killed:")
        for g in gaps[:10]:
            print(f"     {g:.1f}h")
    else:
        print("  no suspicious gaps")

    section("UNMATCHED PACKAGES  (payment apps we are not listening to)")
    for u in sorted(d.get("unmatched_packages", []), key=lambda x: -x["count"])[:30]:
        print(f"  {u['count']:5d}  {u['package_name']}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1])

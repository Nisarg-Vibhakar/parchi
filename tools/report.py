#!/usr/bin/env python3
"""
Turn a Paisa export into a spending report you can actually read.

    python3 tools/report.py exports/exports/paisa-export-*.json -o report.html

Answers goal A — awareness — from real captured data. Self-contained HTML, no
network, no external assets, light and dark aware. Nothing leaves the machine.
"""

import argparse
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone

IST = timezone.utc  # timestamps are epoch ms; render in local civil time below

CAT_LABEL = {
    "FOOD": "Food", "GROCERIES": "Groceries", "TRANSPORT": "Transport",
    "FUEL": "Fuel", "SHOPPING": "Shopping", "BILLS": "Bills",
    "ENTERTAINMENT": "Entertainment", "HEALTH": "Health", "SPORTS": "Sports",
    "INVESTMENT": "Investment", "PEOPLE": "People", "OTHER": "Other",
    None: "Uncategorised",
}


def rupees(minor, decimals=False):
    v = (minor or 0) / 100
    return f"₹{v:,.2f}" if decimals else f"₹{v:,.0f}"


def month_key(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m")


def month_label(key):
    return datetime.strptime(key, "%Y-%m").strftime("%b %Y")


def load(path):
    with open(path) as f:
        d = json.load(f)
    raw = {r["id"]: r for r in d["raw_events"]}
    rows = []
    for p in d["parsed_txn"]:
        if p.get("rejected_reason"):
            continue
        r = raw.get(p["raw_event_id"])
        if not r or not p.get("amount_minor"):
            continue
        rows.append({
            "amount": p["amount_minor"],
            "direction": p["direction"],
            "category": p.get("category"),
            "merchant": p.get("merchant_raw"),
            "at": r.get("posted_at") or r.get("captured_at"),
            "confidence": p.get("confidence") or 0,
        })
    return d, rows


def bar_rows(pairs, total, unit=rupees):
    """Horizontal bars: 4px rounded data-end, baseline-anchored, thin marks."""
    out = []
    biggest = max((v for _, v in pairs), default=1) or 1
    for name, value in pairs:
        pct = 100 * value / biggest
        share = 100 * value / total if total else 0
        # "Uncategorised" is an absence of information, not a category. Drawing
        # it in the series colour makes the loudest bar on the chart a non-answer.
        muted = " muted" if name == "Uncategorised" else ""
        out.append(f"""
        <div class="row" role="listitem">
          <div class="row-label" title="{name}">{name}</div>
          <div class="track"><div class="bar{muted}" style="width:{pct:.2f}%"></div></div>
          <div class="row-value">{unit(value)}<span class="share">{share:.0f}%</span></div>
        </div>""")
    return "".join(out)


def build(path, out_path):
    meta, rows = load(path)
    debits = [r for r in rows if r["direction"] == "DEBIT"]
    credits = [r for r in rows if r["direction"] == "CREDIT"]
    selfs = [r for r in rows if r["direction"] == "SELF_TRANSFER"]

    total_spend = sum(r["amount"] for r in debits)
    total_in = sum(r["amount"] for r in credits)
    total_self = sum(r["amount"] for r in selfs)

    by_month = defaultdict(int)
    for r in debits:
        by_month[month_key(r["at"])] += r["amount"]
    months = sorted(by_month)
    # Drop the first and last months from the average — they are partial windows
    # and would drag a "typical month" figure toward nonsense.
    full = months[1:-1] if len(months) > 2 else months
    avg_month = sum(by_month[m] for m in full) // len(full) if full else 0

    by_cat = Counter()
    for r in debits:
        by_cat[CAT_LABEL.get(r["category"], r["category"])] += r["amount"]

    by_merchant = Counter()
    merchant_n = Counter()
    for r in debits:
        if r["merchant"]:
            by_merchant[r["merchant"]] += r["amount"]
            merchant_n[r["merchant"]] += 1

    # Frequency matters as much as value for a habit you want to notice.
    freq = merchant_n.most_common(12)

    max_month = max(by_month.values()) if by_month else 1
    month_bars = "".join(f"""
        <div class="mcol" title="{month_label(m)} — {rupees(by_month[m])}">
          <div class="mbar-wrap"><div class="mbar" style="height:{100*by_month[m]/max_month:.1f}%"></div></div>
          <div class="mlab">{month_label(m).split()[0]}</div>
        </div>""" for m in months)

    biggest = sorted(debits, key=lambda r: -r["amount"])[:8]
    biggest_rows = "".join(f"""
        <tr><td>{datetime.fromtimestamp(r['at']/1000):%d %b %Y}</td>
            <td>{(r['merchant'] or '—')[:38]}</td>
            <td class="c">{CAT_LABEL.get(r['category'], r['category'])}</td>
            <td class="n">{rupees(r['amount'])}</td></tr>""" for r in biggest)

    cat_table = "".join(f"""
        <tr><td>{k}</td><td class="n">{rupees(v)}</td>
            <td class="n">{100*v/total_spend:.1f}%</td></tr>"""
        for k, v in by_cat.most_common())

    span = f"{month_label(months[0])} – {month_label(months[-1])}" if months else "—"

    html = f"""<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Paisa — where the money went</title>
<style>
  :root {{
    color-scheme: light;
    --surface-0: #f4f4f2;
    --surface-1: #fcfcfb;
    --border:    #e2e1dc;
    --text-1:    #0b0b0b;
    --text-2:    #52514e;
    --text-3:    #86847d;
    --series-1:  #2a78d6;
    --spend:     #e34948;
    --income:    #1baf7a;
    --neutral:   #86847d;
  }}
  @media (prefers-color-scheme: dark) {{
    :root:where(:not([data-theme="light"])) {{
      color-scheme: dark;
      --surface-0: #121211; --surface-1: #1a1a19; --border: #33322e;
      --text-1: #ffffff; --text-2: #c3c2b7; --text-3: #8e8d84;
      --series-1: #3987e5; --spend: #e66767; --income: #199e70;
    }}
  }}
  :root[data-theme="dark"] {{
    color-scheme: dark;
    --surface-0: #121211; --surface-1: #1a1a19; --border: #33322e;
    --text-1: #ffffff; --text-2: #c3c2b7; --text-3: #8e8d84;
    --series-1: #3987e5; --spend: #e66767; --income: #199e70;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 32px 20px 80px;
    background: var(--surface-0); color: var(--text-1);
    font: 15px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
    -webkit-font-smoothing: antialiased;
  }}
  .wrap {{ max-width: 940px; margin: 0 auto; }}
  h1 {{ font-size: 26px; letter-spacing: -0.02em; margin: 0 0 4px; }}
  .sub {{ color: var(--text-2); margin: 0 0 28px; font-size: 14px; }}
  h2 {{ font-size: 15px; letter-spacing: -0.01em; margin: 0 0 2px; }}
  .hint {{ color: var(--text-3); font-size: 13px; margin: 0 0 16px; }}
  .card {{
    background: var(--surface-1); border: 1px solid var(--border);
    border-radius: 12px; padding: 20px; margin-bottom: 16px;
  }}
  .tiles {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-bottom: 16px; }}
  .tile {{ background: var(--surface-1); border: 1px solid var(--border); border-radius: 12px; padding: 18px 20px; }}
  .tile .k {{ color: var(--text-2); font-size: 12.5px; text-transform: uppercase; letter-spacing: .06em; }}
  .tile .v {{ font-size: 27px; font-weight: 600; letter-spacing: -0.02em; margin-top: 6px; font-variant-numeric: tabular-nums; }}
  .tile .n {{ color: var(--text-3); font-size: 12.5px; margin-top: 3px; }}
  .v.spend {{ color: var(--spend); }} .v.income {{ color: var(--income); }}

  .row {{ display: grid; grid-template-columns: 148px 1fr 132px; gap: 12px; align-items: center; padding: 5px 0; }}
  .row-label {{ color: var(--text-2); font-size: 13.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }}
  .track {{ background: var(--surface-0); border-radius: 4px; height: 16px; overflow: hidden; }}
  .bar {{ height: 100%; background: var(--series-1); border-radius: 0 4px 4px 0; min-width: 2px; }}
  .bar.muted {{ background: var(--neutral); opacity: .45; }}
  .row-value {{ text-align: right; font-variant-numeric: tabular-nums; font-size: 13.5px; }}
  .share {{ color: var(--text-3); margin-left: 8px; font-size: 12px; }}

  .months {{ display: flex; gap: 3px; align-items: flex-end; height: 190px; overflow-x: auto; padding-top: 8px; }}
  .mcol {{ flex: 1 1 0; min-width: 26px; display: flex; flex-direction: column; height: 100%; }}
  .mbar-wrap {{ flex: 1; display: flex; align-items: flex-end; }}
  .mbar {{ width: 100%; background: var(--series-1); border-radius: 4px 4px 0 0; min-height: 2px; }}
  .mlab {{ text-align: center; color: var(--text-3); font-size: 11px; padding-top: 6px; }}

  table {{ width: 100%; border-collapse: collapse; font-size: 13.5px; }}
  th, td {{ text-align: left; padding: 7px 8px; border-bottom: 1px solid var(--border); }}
  th {{ color: var(--text-3); font-weight: 500; font-size: 12px; text-transform: uppercase; letter-spacing: .05em; }}
  td.n {{ text-align: right; font-variant-numeric: tabular-nums; }}
  td.c {{ color: var(--text-2); }}
  tr:last-child td {{ border-bottom: none; }}
  .scroll {{ overflow-x: auto; }}
  .note {{ color: var(--text-3); font-size: 12.5px; margin-top: 12px; line-height: 1.55; }}
</style>

<div class="wrap">
  <h1>Where the money went</h1>
  <p class="sub">{span} · {len(debits):,} transactions · captured automatically from bank SMS</p>

  <div class="tiles">
    <div class="tile"><div class="k">Total spent</div><div class="v spend">{rupees(total_spend)}</div>
      <div class="n">excludes transfers to yourself</div></div>
    <div class="tile"><div class="k">Typical month</div><div class="v">{rupees(avg_month)}</div>
      <div class="n">mean of {len(full)} complete months</div></div>
    <div class="tile"><div class="k">Money in</div><div class="v income">{rupees(total_in)}</div>
      <div class="n">{len(credits):,} credits</div></div>
    <div class="tile"><div class="k">Own accounts</div><div class="v">{rupees(total_self)}</div>
      <div class="n">card bills, wallets, self UPI</div></div>
  </div>

  <div class="card">
    <h2>Spending by month</h2>
    <p class="hint">First and last bars are partial months.</p>
    <div class="months">{month_bars}</div>
  </div>

  <div class="card">
    <h2>Where it goes</h2>
    <p class="hint">Share of {rupees(total_spend)} total.</p>
    <div role="list">{bar_rows(by_cat.most_common(), total_spend)}</div>
  </div>

  <div class="card">
    <h2>Biggest destinations</h2>
    <p class="hint">By total value.</p>
    <div role="list">{bar_rows(by_merchant.most_common(12), total_spend)}</div>
  </div>

  <div class="card">
    <h2>Most frequent</h2>
    <p class="hint">By number of payments — these are the habits, not the big-ticket items.</p>
    <div role="list">{bar_rows(freq, sum(n for _, n in freq), unit=lambda n: f"{n}×")}</div>
  </div>

  <div class="card">
    <h2>Single biggest payments</h2>
    <div class="scroll"><table>
      <thead><tr><th>Date</th><th>To</th><th>Category</th><th class="n">Amount</th></tr></thead>
      <tbody>{biggest_rows}</tbody>
    </table></div>
  </div>

  <div class="card">
    <h2>Category totals</h2>
    <div class="scroll"><table>
      <thead><tr><th>Category</th><th class="n">Total</th><th class="n">Share</th></tr></thead>
      <tbody>{cat_table}</tbody>
    </table></div>
    <p class="note">
      Parser v{meta.get('parser_version')}. Credit-card bill payments, wallet top-ups and
      transfers to your own accounts are excluded from spending — counting them would
      double-count purchases already recorded individually.
      Uncategorised rows are merchants no rule matched yet; each one you tag in the app
      is remembered permanently and applied retroactively.
    </p>
  </div>
</div>
"""
    with open(out_path, "w") as f:
        f.write(html)
    print(f"wrote {out_path}")
    print(f"  span         {span}")
    print(f"  spend        {rupees(total_spend)} over {len(debits)} txns")
    print(f"  typical mth  {rupees(avg_month)}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("export")
    ap.add_argument("-o", "--out", default="report.html")
    a = ap.parse_args()
    build(a.export, a.out)

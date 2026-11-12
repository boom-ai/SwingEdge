# SwingEdge — contract addendum v4: live track record of the score's calls (FIXED for this round)

Purpose: the BUY CANDIDATE label can only become a plain BUY on real, out-of-sample evidence collected on the phone.

## Engine behaviour
- Every trading day, from the scan table built on that day's COMPLETED bar (first full scan after 15:30, or the first scan of the next trading day using the previous completed bar — whichever comes first; once per bar day, idempotent), record a cohort for that signal day D:
  - `BUY`: every BUY entry in the table (not just the top 15).
  - `CONTROL`: the same number of stocks drawn at random (seeded by D, deterministic) from rated stocks with close > SMA50 that are not BUY. Minimum 4 if there is at least 1 BUY.
  - `AVOID`: up to 30 AVOID entries (lowest scores first) and `AVOID_CTRL`: the same number of random rated non-AVOID stocks.
- Each recorded pick is evaluated from the daily cache bars as they arrive (pure function of bars, no live quotes), with plan **P1** (REPORT4 X10: fill = open of D+1 if ≤ C[D] + 0.5·ATR14 else skipped; stop fill − 3·ATR14 with gap rule; exit next open after a close < SMA50; time exit close of session 60) for BUY and CONTROL, and plan **P2** (fill = open of D+1; plain 60-session hold with a disaster stop at fill − 5·ATR14, gap rule) for BUY and CONTROL too. AVOID and AVOID_CTRL use P2 without the stop (pure 60-session hold). Costs: `costPct` from settings (default 0.25) — results are reported net of costs.
- Persist picks compactly (sym, D, group, fill, exit, status per plan) in their own file (e.g. "forward.json"), max ~6,000 picks (drop the oldest closed ones first). Tolerant loading.
- **Replay**: on first run after install/upgrade (and when the user taps "Replay"), compute the same cohorts and outcomes for every bar day in the cached history that has ≥ 60 sessions after it (≈ the last 12–18 months), using the scores computed for that day's cross-section from the cache. Must not block the UI (runs inside tick with `busy` progress; must finish in < 60 s on a phone for 574 × 520 bars — precompute per-stock component arrays once; percentile ranking per day is O(n log n)). Replay results are kept separately and NEVER count toward the upgrade.
- **Upgrade rule (forward only, closed P1 trades):** BUY group ≥ 150 closed trades AND control ≥ 150 AND (mean BUY − mean CONTROL) > 0 with a t-stat ≥ 2 computed on per-signal-day mean differences AND BUY mean > 0. When met: `call.proven` becomes true for BUY, the pill reads "BUY", and `evidence` quotes the live record. Otherwise unchanged.

## State addition
```json
"track": {
  "startedDay": 20261112, "lastDay": 20261112, "replayDone": true, "busyReplay": false,
  "live":   {"P1": {"buy":G, "control":G}, "P2": {"buy":G, "control":G}, "avoid": G, "avoidCtrl": G,
             "edgePct": 0, "edgeT": 0, "upgraded": false, "needText": "Needs 150 closed trades in each group (now 12 and 12) and an edge with t ≥ 2."},
  "replay": {"P1": {"buy":G, "control":G}, "P2": {"buy":G, "control":G}, "avoid": G, "avoidCtrl": G,
             "edgePct": 0, "edgeT": 0, "fromDay": 20250501, "toDay": 20260815},
  "recent": [{"sym":"","group":"BUY","day":20261112,"plan":"P1","status":"OPEN","fill":0,"exit":0,"retPct":0,"sessions":0}]
}
```
where `G = {"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0}` (closed trades only for the stats; worstPct = 5th percentile). `recent`: latest 40 picks across groups, newest first.
Action `replayTrack` {} → re-runs the replay (long; uses `busy`). Action `resetTrack` {} → clears live records (confirm in UI).

## UI
- A "Track record" screen (reachable from the verdict card via "See the live record", from the Alerts tab's Strategies area, and from Setups) showing live and replay side by side for P1 and P2: BUY vs CONTROL win %, average, median, worst, sessions; the edge and its t; progress toward the upgrade rule (`needText`, two progress bars to 150); AVOID vs AVOID_CTRL; the recent picks list; a clear note that replay overlaps the research period and does not count.
- On the verdict card for BUY: one line under the tested record: "Live record since ‹startedDay›: N closed, BUY avg x% vs random y%" with a link to the screen.

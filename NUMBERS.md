# Numbers

Every figure AdServe states, with the file it came from. The full tables, one row per run, are in
`NUMBERS_LEDGER.md` (generated from `results/*.jsonl` by `scripts/ledger.py`).

**Machine and load, for every latency figure.** One Apple M3 Pro laptop (12 cores, 18 GB,
macOS 26.5, running in macOS Low Power Mode, which could not be switched off during the runs),
JDK 21.0.12, server heap 2 GB with generational ZGC, Kafka, Redis and Postgres in Docker Desktop
on the same machine, and the load generator in a separate JVM on the same machine. The laptop was
shared that night with another project's benchmarks; the two took turns through a lock, so no
two load tests overlapped, but builds and idle services did. Load average before each run is in
the ledger. None of this is a production or large-scale claim.

**Traffic.** Ad breaks are the impression rows of the public iPinYou RTB log, season 2,
2013-06-11 (1,745,722 rows; 2013-06-10 is the forecast day). Campaigns are that day's 55 real
creatives from 5 advertisers. Viewers are simulated: nobody watched anything.

## Milestone 1: the path works end to end

- **10,000** real-log ad breaks decided over gRPC, and every one of the **9,947** distinct request
  ids (the log repeats 53 bid ids) found on `ad.responses`. `results/m1_replay.jsonl`, last row.

## Experiment 1: a live break, with and without a database write on the path

`results/exp1_burst.jsonl`. N requests within 2 s on a gamma-shaped arrival curve (peak at
0.4 s), all LIVE, three repeats, after a 30 s warm-up at 3,000/s.

| Requests in 2 s (offered peak /s) | AdServe p99 over 3 repeats | AdServe errors | Sync-write baseline p99 | Baseline errors |
|---|---|---|---|---|
| 4,000 (3,834) | 7.7, 11.2, 14.3 ms | 0 | 104, 151, 368 ms | 0 |
| 8,000 (7,668) | 10.3, 16.7, 20.7 ms | 0 | 1,324, 1,443, 1,986 ms | 0 to 0.5% |
| 12,000 (11,501) | 22.3, 83.4, 103.9 ms | 0 | 2,025 to 2,041 ms | 1.8 to 2.6% |
| 16,000 (15,335) | 60.6, 71.6, 184.6 ms | 0 | 2,080 to 2,114 ms | 20 to 24% |

The quotable line: **8,000 ad breaks arriving within 2 seconds, p99 at most 20.7 ms over three
repeats with 0 errors, where writing each decision to Postgres first took p99 to 1.3 to 2.0 s.**
Above about 8,000 per break the AdServe p99 varies widely between repeats on this machine; do
not quote a capacity above that.

## Experiment 2: pacing over one real day

`results/exp2_pacing.jsonl`. The whole replay day through the decision engine on a simulated
fleet of 8 nodes syncing spend every 10 s.

- **Smart Pacing** (Xu et al. 2015), with the per-node budget allowance: **54 of 55** campaigns
  landed within 5% of their budget, **0** overspent, **none** ran out before 23:00, mean RMSE
  of cumulative spend against plan **0.089** (share of budget).
- **Unpaced**, same fleet: **50 of 55** ran out before 23:00, on average at **07:51** UTC, RMSE
  **0.420**.
- **Without the allowance** (every node may spend the whole remaining budget between syncs),
  unpaced delivery overspent **3.1%** of all budgets in aggregate, **all 55** campaigns went
  over, and the worst spent **7.99x** its budget. Smart Pacing without the allowance still
  overspent 40 campaigns, by up to 49%. The allowance, not the pacer, is what stops overspend.
- Probabilistic throttling (LinkedIn 2014): 55 of 55 within 5%, RMSE 0.180, but 36 ran out
  early (mean 19:23). The ported PID delivered only **84.8%** of budgets (24 of 55 within 5%).
  The perfect-forecast baseline did not beat the real forecast (RMSE 0.149, see DESIGN.md).

## Experiment 3: frequency caps under duplicated beacons

`results/exp3_caps.jsonl`. 2,000 simulated viewers (real contexts), 12 breaks each, real Redis
and the real Lua scripts; beacons duplicated at 5, 10 and 20%, lost 1%, late 3%.

- **Idempotent counters (AdServe): 0 cap violations, 0 ad-load violations, 0 wrongly refused
  serves and 0.00% counter drift across 30,870 duplicated beacon deliveries.**
- Naive INCR from the same two writers: counters **104% to 124%** too high, **42,073 to 42,895**
  serves wrongly refused, and a third fewer ads served.
- Naive INCR from beacons only: **153 to 288** cap violations and 121 to 603 ad-load violations
  (lost and late beacons), counters 4% to 24% high.
- Idempotent from beacons only: still **274 to 317** cap violations. The decision-time write is
  what closes the lost-beacon gap; idempotency is what makes writing twice safe.

## Experiment 4: pod assembly against the exact optimum

`results/exp4_pods.jsonl`. The first 5,000 real ad breaks, every targeted campaign a candidate
(43 on average), no two same-category ads adjacent; the exact branch and bound proved every one.

- **DP: 0.00% below the exact optimum on all 5,000 breaks**, solve p99 **23 us**. Greedy by
  value per second: **8.35%** below on average (max 27.5%), greedy with 1-swap 4.78%.
- On 5,000 synthetic breaks of 120 candidates from 30 advertisers: DP 0.02% below on average,
  p99 143 us, against the exact solver's p99 1,333 us.
- **0 invalid pods** from any solver, and property tests over thousands of random catalogues
  prove it (`core/src/test/.../PodInvariantProperties.java`).

## Experiment 8: the campaign snapshot over Hollow

`results/exp8_hollow.jsonl`. For a 5,000-campaign catalogue, the full snapshot blob is
**1,177,292 bytes** and a delta after one campaign's budget changes is about **1,075 bytes**;
a watching serving node had rebuilt its snapshot **1.0 s** after publish (p50 of 20 changes;
the file-based announcement watcher polls once a second).

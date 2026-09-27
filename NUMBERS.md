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

`results/exp1_burst.jsonl`, plus the same bursts inside `results/exp9_runtime.jsonl`. N requests
within 2 s on a gamma-shaped arrival curve (peak at 0.4 s), all LIVE, three repeats per run,
after a 30 s warm-up at 3,000/s. The comparison was run twice, once under generational ZGC
(labels `adserve`, `legacy_sync_write`) and once under G1 (`*_g1`), with both sides on the same
runtime each time.

| Requests in 2 s (offered peak /s) | AdServe p99, every run | AdServe errors | Sync-write baseline p99, every run | Baseline errors |
|---|---|---|---|---|
| 4,000 (3,834) | ZGC 7.7, 11.2, 14.3 · G1 3.0, 14.3, 4.3 ms | 0 | ZGC 104, 151, 368 · G1 60, 550, 8 ms | 0 |
| 8,000 (7,668) | ZGC 20.7, 10.3, 16.7 · G1 122.9, 54.5, 56.5 ms | 0 | ZGC 1,324, 1,443, 1,986 · G1 1,036, 1,855, 1,108 ms | 0 to 0.5% |
| 12,000 (11,501) | ZGC 103.9, 22.3, 83.4 · G1 78.8, 85.6, 84.4 ms | 0 | 1,875 to 2,041 ms | 0.9 to 2.6% |
| 16,000 (15,335), ZGC only | 60.6, 184.6, 71.6 ms | 0 | 2,080 to 2,114 ms | 20 to 24% |

The quotable line: **across 12 AdServe runs of 8,000 requests arriving within 2 seconds
(this table's six, plus the six virtual-thread runs of experiment 9), the p99 had a median of
21.1 ms and a worst of 122.9 ms, with 0 errors; writing each decision to Postgres first put the
p99 above 1 second in all 6 of its runs.** The best configuration (G1, experiment 9) held
7.3 to 10.2 ms with every frequency check answered.

Two caveats that travel with every burst figure:

1. **Missed counter deadlines.** A decision whose Redis read misses its 20 ms deadline is served
   in the unknown_allow cap mode. At 8,000 per break, the G1 runs here missed 976, 2 and 3,344
   of 8,000; experiment 9's G1 runs missed 0 (BUG_LOG bug 11, experiment 10). The count is in
   the ledger for every run that recorded it (the first ZGC runs predate the counter).
2. **Run-to-run spread.** The same cell varied up to 10 times between runs as the shared
   laptop's load average moved between 4 and 17. Quote ranges, never a single best run.

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

## Experiment 5: priority shedding

`results/exp5_shedding.jsonl`. Sustained open-loop load for 15 s, 40% LIVE, two repeats, with
the shedder configured for a capacity of 4,000 decisions/s. That figure was set conservatively:
the Docker Desktop VM on this laptop had hung under another project's overload runs that night,
and the unshed server in fact sustained 8,000/s without errors. So this shows the priority
mechanism, not a server at its true limit.

- **At 2x the configured capacity (8,000/s): with shedding, every one of 40,000 LIVE requests
  was served with p99 7.8 and 17.0 ms, while 74% of VOD requests were refused with
  RESOURCE_EXHAUSTED and a retry hint. Without shedding, LIVE p99 was 40.9 and 60.2 ms.**
- At 1.5x, 49% of VOD was refused and LIVE p99 was 24.5 and 70.0 ms (with shedding) against
  15.1 and 20.5 ms (without): below the real limit, shedding bought nothing but refusals. At 1x,
  nothing was shed.

## Experiment 6: dependencies stopped

`results/exp6_dependency.jsonl`. The same 8,000-request burst, three repeats per leg (24,000
requests each).

| Condition | Served | p99 per repeat | Ads per pod |
|---|---|---|---|
| Everything up | 24,000 of 24,000 | 28.0, 119.3, 14.5 ms | 1.71 |
| **Postgres stopped** | **24,000 of 24,000** | 55.0, 22.7, 35.3 ms | 1.70 |
| Kafka stopped | 24,000 of 24,000 | 23.1, 230.7, 28.7 ms | 1.71 |
| Redis stopped, unknown_allow | 24,000 of 24,000 | 5.9, 74.6, 16.2 ms | 1.71 |
| Redis stopped, unknown_deny (server started while Redis was down) | 24,000 of 24,000 | 1.7, 3.0, 9.5 ms | 0.53 |
| Redis 50 ms slow (Toxiproxy), 20 ms deadline | 24,000 of 24,000 | 92.5, 24.5, 25.7 ms | 1.72 |
| Sync-write baseline, everything up | 24,000 of 24,000 | 1,283, 1,119, 1,454 ms | 1.71 |
| **Sync-write baseline, Postgres stopped** | **0 of 24,000** | every request failed after 2 s | 0 |

- With Kafka down the decision log's buffer absorbed every record (0 dropped in 24,000; the
  buffer holds 200,000), so a longer outage than this would start dropping.
- `unknown_deny` keeps capped campaigns out while caps cannot be read, and pods shrank from 1.71
  to 0.53 ads: that is the revenue the viewer-first choice costs.
- These legs ran generational ZGC and are noisy between repeats (see experiment 9). **During
  bursts the 20 ms counter deadline was missed for 0 to 57% of decisions even with Redis up**
  (BUG_LOG bug 11), and those decisions served in the unknown_allow mode. The per-run count is in
  the ledger (`server_cap_unknown`).

## Experiment 9: collector and thread model

`results/exp9_runtime.jsonl`. The 8,000 and 12,000-request bursts, three repeats each.

| Server runtime | 8,000 in 2 s, p99 | Counter deadline missed | 12,000 in 2 s, p99 | Missed |
|---|---|---|---|---|
| G1, a virtual thread per request | **7.3, 10.2, 7.3 ms** | **0, 0, 0** | 42.7, 44.5, 21.0 ms | 10,800, 4,258, 3,698 |
| Generational ZGC, virtual threads | 26.2, 54.6, 21.5 ms | 51, 1,572, 342 | 135.4, 61.8, 63.2 ms | 10,395, 7,390, 5,230 |
| Generational ZGC, 64 platform threads | 89.2, 49.0, 22.1 ms | 60, 16, 0 | 38.3, 7.7, 193.3 ms | 0, 0, 46 |

**G1 with virtual threads served every 8,000-request break with p99 at most 10.2 ms and every
frequency check answered.** The server has used G1 since. Platform threads bound how many counter
fetches are in flight, so they miss the deadline less but queue in the pool, and their p99 swings
widely. At 12,000 every configuration skipped thousands of checks: that is past this laptop's
capacity with caps enforced.

## JMH: the decision path's CPU cost

`results/jmh-hotpath.json`, average time per call, one fork, 5 measured iterations.

| What | Time |
|---|---|
| One whole decision (55 campaigns, DP pod, tokens, decision record; counters stubbed) | **16.5 us** |
| All 55 compiled targeting predicates against one request | 182 ns |
| DP pod solve (real break, 43 candidates on average) | 7.3 us |
| Exact branch and bound, same breaks | 7.0 us |
| Greedy | 1.7 us |
| Sign one impression token (HMAC-SHA256) | 280 ns |

## Experiment 10: the counter deadline, and where the server sits

`results/exp10_deadline.jsonl`. The 8,000-request burst, G1, three repeats per cell, with the cap
check's deadline at 20, 50 and 100 ms, first with the server on the host (Redis through Docker
Desktop's port forwarder) and then with the server in a container on the Redis network. Load
average before the runs ranged from 5 to 38: the laptop was doing more than this experiment, and
tail latency varied 5 to 20 times between repeats of the same cell. Read the ranges, not a cell.

| Server placement, deadline | p99 per repeat | Decisions that missed the deadline (of 8,000) |
|---|---|---|
| Host, 20 ms | 58.7, 5.6, 50.1 ms | 370, 0, 2,015 |
| Host, 50 ms | 74.5, 11.0, 186.4 ms | 876, 0, 5,532 |
| Host, 100 ms | 31.8, 400.1, 18.4 ms | 0, 5,764, 0 |
| Container, 20 ms | 32.9, 47.1, 19.3 ms | 5, 125, 9 |
| Container, 50 ms | 59.8, 8.4, 50.8 ms | 2,947, 0, 0 |
| **Container, 100 ms** | **87.3, 15.5, 33.1 ms** | **0, 0, 0** |

What this supports: the Redis path through the Mac's port forwarder, not the decision code, is
what makes checks miss under a burst (next to Redis, misses at 20 ms fell from hundreds or
thousands to single digits in two of three repeats), and a longer deadline trades tail latency
for enforcement. What it does not support: any single p99 from this table as a capacity figure.

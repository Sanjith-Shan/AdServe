# Bug log

Every bug that reached a commit or a measurement, the tool that found it, and the fix. Newest
last.

## 1. Proto sources picked up generated copies of themselves

- **Found by:** the Gradle build (`protoc` failed with "HttpRule is already defined").
- **What happened:** the proto source set pointed at `contract/` itself so the contract could
  live at `contract/ads.proto`. The protobuf plugin also extracts dependency protos under the
  module's `build/`, which was inside that source directory, so every Google proto was compiled
  twice.
- **Fix:** the contract stays in `contract/`, and a separate `api` module compiles it from
  `../contract`.

## 2. Two logging backends in the server

- **Found by:** the Testcontainers integration test (Spring refused to start: "LoggerFactory is
  not a Logback LoggerContext but Logback is on the classpath").
- **What happened:** the beacon consumer declares `slf4j-simple` for its standalone `main`, and
  a `java-library` module's runtime dependencies flow to every consumer, including the server.
- **Fix:** the server excludes `slf4j-simple`.

## 3. The M1 replay seemed to lose 53 decisions

- **Found by:** `sim replay-check` (10,000 requests sent, 9,947 decision records found).
- **What happened:** not a lost decision. The iPinYou log repeats 53 bid ids in its first 10,000
  rows, so 10,000 requests carried 9,947 distinct request ids, and the check counted records by
  key.
- **Fix:** the check now reports distinct request ids next to records found. Every distinct id
  was on `ad.responses`.

## 4. A fleet of serving nodes overspent small budgets by up to 7x

- **Found by:** experiment 2, the pacing simulator. In the first run, before the fix, all 55
  campaigns overspent when unpaced and 21 to 51 overspent under the pacers. The worst spent
  7.99 times its budget, which is (nodes - 1) = 7 budgets too many.
- **What happened:** each of the eight simulated nodes compared spend with the budget using the
  global total as of the last sync (every 10 s) plus its own spend since. Between syncs every
  node could spend the whole remaining budget, so the fleet could spend it eight times. Small
  budgets facing heavy traffic went over within one sync window.
- **Fix:** `FleetBudgetLedger` gives each node only its share of what remained at the last sync
  (remaining / nodes). Experiment 2 now runs every pacer with and without the split, so the
  size of this effect is its own measured row.

## 5. Pacers started too hot and burned small budgets in the first minute

- **Found by:** experiment 2 (first run: Smart Pacing exhausted 12 campaigns before 02:00 on
  average).
- **What happened:** every pacer started at a pass-through rate of 0.5 and only adjusted at the
  first one-minute boundary. The replay day's first hour is its busiest (191,511 of 1,745,722
  requests), so a campaign whose whole budget was worth a few thousand impressions spent it
  before the pacer had seen a single slot.
- **Fix:** a forecast warm start (`Pacers.warmStart`): the initial rate is the one that would
  spend exactly the budget if the campaign won every request the forecast expects. It applies to
  every pacer alike.

## 6. The ported PID starved its first hour

- **Found by:** reading the port against its unit test (`PacerTest`).
- **What happened:** AdRankBench's PID warm-starts at the plan's first-slot share. With 1,440
  one-minute slots that is about 0.0007, so the throttle begins near zero and takes most of an
  hour to climb.
- **Fix:** the port takes the same initial rate as the other pacers. The gains are unchanged.

## 7. Frequency-cap round trips dominated burst latency

- **Found by:** the experiment 1 calibration burst plus `sim redis-probe`. An 8,000-request
  live break had p99 82 ms. A probe that did nothing but the same 80-key MGET at 8,000/s over one
  connection already had p99 72 ms, so the cost was the Redis traffic, not the decision logic
  (a JFR profile of the warm server showed it mostly idle).
- **What happened:** every decision sent one MGET with two keys per surviving campaign, whether
  or not the campaign had a cap, plus one EVALSHA per served impression, all over a single
  connection into Docker Desktop's port forwarder.
- **Fix:** fetch counters only for campaigns that have a cap, count a whole pod with one script
  call (`COUNT_POD_ONCE`, the same event keys as the single-impression script, so it dedupes
  against the beacon consumer), and spread commands over four connections. The same burst's p99
  fell to 17.6 ms (calibration run, not a ledger figure).

## 8. The bench lock was removed by a run that did not own it

- **Found by:** the other project's harness, which found its lock replaced by ours.
- **What happened:** this laptop is shared with another project's benchmarks, and the two take
  turns through a lock directory. A shell line chained `rm -rf` after a `mkdir` that had failed,
  and the scripts' exit trap removed the directory without checking the owner.
- **Fix:** `scripts/lib.sh` only removes the lock when its owner line names this process. One
  calibration run overlapped the other project's load test; no ledger number comes from it.

## 9. A serving node could not start while Redis was down

- **Found by:** experiment 6. The Redis-down leg with the default cap mode served 8,000 of 8,000
  requests, but the next leg restarts the server in `unknown_deny` mode, and the restart failed:
  the counter client connected in its constructor, and Lettuce throws when the host refuses.
- **What happened:** a node that was already running survived the outage (Lettuce reconnects
  and rejects commands meanwhile), but a node started during it could not come up at all, which
  is exactly when a fleet is scaling out or restarting.
- **Fix:** `RedisCounters` opens its connections lazily and retries at most once a second; until
  one is open, reads fail fast into the cap mode and writes are counted as errors
  (`RedisDownTest`). The leg was rerun (`scripts/exp6-rest.sh`).

## 10. The billing job never ran, and the audit said everything agreed

- **Found by:** experiment 7 (billing rows 0 for 5,000 served impressions) and then the job's
  log (`NoClassDefFoundError: org/apache/flink/connector/base/source/reader/RecordEmitter`).
- **What happened:** the Flink Kafka connector expects `flink-connector-base` from the Flink
  distribution, which a standalone run does not have. The topology test passed because it feeds
  the join from in-memory sources and never loads the Kafka source. Worse, the audit computed
  its agreement as 1.0 when it had compared zero campaigns.
- **Fix:** the dependency is declared; the audit reports null agreement when nothing was
  compared and exits non-zero on an empty billing table, so a dead job fails the experiment
  instead of passing it. The empty run is archived in `results/archive/`.

## 11. Under a burst, many decisions skipped the frequency check

- **Found by:** the `adserve_cap_unknown_total` counter, added for experiment 6 and read by the
  burst driver before and after every run. With every dependency up, an 8,000-request burst had
  0 to 4,583 decisions (up to 57%) whose counter fetch missed its 20 ms deadline.
- **What happened:** during the wall of a live break, Redis round trips through Docker Desktop's
  port forwarder queue past 20 ms, and the engine does what it was told: serve in the
  `unknown_allow` cap mode. The latency figures of experiment 1 were partly bought by skipping
  slow cap checks, and the burst driver did not record how many until this counter existed.
- **Status:** a measurement and a trade-off rather than a code fix. Experiment 10 sweeps the
  deadline (20, 50, 100 ms) with the server on the host and in a container on the Redis network,
  and records the miss rate next to the latency, so every burst figure can be quoted with its
  enforcement rate. The zero-violation result of experiment 3 is unaffected (no burst, every
  fetch answered), but a burst figure is never quoted without its miss rate.

## 12. Half the billed impressions looked rerouted

- **Found by:** experiment 7's billing table: about 2,700 of 5,000 rows had `rerouted = true`
  when only 2% of beacons were deliberately sent to the wrong region.
- **What happened:** the simulated players sent each beacon to the region field of the log row
  (a hash of the iPinYou region code, half US_EAST and half US_WEST), while the one serving node
  in the experiment serves as US_EAST. So every beacon for a "US_WEST" row arrived in the wrong
  region by construction. The deduplication counts were unaffected; the reroute column was noise.
- **Fix:** a player sends beacons to the region in the response (`serving_region`), and a
  misroute sends them to the other one. The run was repeated; the first rows are archived.

## 13. Every campaign file write reordered the targeting lists

- **Found by:** the auction's `sim derive-bids`, whose first run rewrote the committed sample
  catalogue and changed every `geos` list in the diff when only bids should have moved.
- **What happened:** `TargetingSpec` copied its sets with `Set.copyOf`, whose iteration order is
  randomised per JVM run, so `CampaignFiles.write` put the same campaign's regions in a different
  order every time. Nothing served differently (targeting compiles sets to bitsets), but no
  catalogue written by the tools was reproducible byte for byte, and a real change was hidden in
  a noisy diff.
- **Fix:** the spec's sets are sorted (`TreeSet`, `EnumSet`), and `CampaignFilesTest` writes the
  sample twice and requires identical bytes. `derive-bids` edits only the bid field in the JSON.

## 14. The pricing experiment ran for an hour without finishing a day

- **Found by:** a heap histogram (`jmap -histo`) of the first full-day experiment 11 run. It had
  run for an hour; its main thread had used 254 s of CPU in 3,583 s, and the heap held 22.9
  million `AtomicLong`s and 33.8 million map nodes keyed by strings.
- **What happened:** the auction experiments ran each engine with the in-memory frequency-cap
  store, which keeps three string counter keys and an event-id string per impression (about 1 KB).
  Two engines over a whole replay day are about 10 million impressions, which filled the 6 GB
  heap, and the collector spent most of the run reclaiming nothing.
- **Fix:** `AuctionSim.CompactCapStore` packs the same counters (per campaign per day and week,
  ads per hour, on the same windows) into a few longs per viewer, without the event-id set: in
  process each impression is recorded once with a fresh id, so the idempotent store never dropped
  one and the counts are identical. The stalled run was killed and rerun.

## 15. Tuning on the forecast day served nothing

- **Found by:** the first experiment 15 tuning run on 2013-06-10, which delivered no impressions.
- **What happened:** every campaign's flight is the replay day, 2013-06-11 only, so on the
  forecast day the flight check refused every campaign.
- **Fix:** `PacingExperiment` shifts flights to the day of the requests file and records the shift
  (`flights_shifted_days`) on every row.

## 16. Result rows from the second machine went missing, twice

- **Found by:** result files that never appeared on the laptop although the job's log on the
  Windows box said it had finished.
- **What happened:** three separate faults in `scripts/minipc-sim.sh`. `minipc job run` stops
  following a job after 30 minutes, and a run queued behind the box's lock for longer, so the
  wrapper gave up before copying rows back. Windows PowerShell under `ErrorActionPreference =
  'Stop'` turns a native command's first stderr line into a terminating error. And a chain of runs
  launched from zsh passed `$args` unquoted, which zsh does not split, so the simulator was asked
  for the command "exp11-pricing --reps 1000 --seed 20130611".
- **Fix:** the wrapper starts the job without waiting and follows its log until that run's exit
  line, stderr no longer ends the job, and chains run under bash. No figure was affected: the
  missing rows were copied from the box, and the misquoted run failed at once.

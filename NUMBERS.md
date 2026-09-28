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

## The auction, M0: bids and reserve from the replay

`results/m0_bids.md` (table) and `results/m0_bids.jsonl` (one row per campaign). Replay day
2013-06-11. Money is micros of the log's currency (CNY) per impression, never dollars.

- **All 55 campaigns bid from their own median winning price** on the replay day (none needed the
  log-normal fallback, fitted to every positive paying price: mu 6.4049, sigma 0.8166, median 605
  micros). A 30 s spot at a campaign's own click rate bids exactly that median.
- **Reserve: 50 micros per impression per slot**, the day's median slot floor price. 32.4% of
  slots had no floor, 0.37% of impressions paid below 50, and 0 of 55 campaigns bid below it.
- Paying prices: p10 200, p50 700, p90 1,660, p99 2,600 micros per impression.

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

## Experiment 7: billing, end to end, with an audit

`results/exp7_billing.jsonl`. AdServe, the beacon consumer and the Flink billing job running
together; simulated players on the first 5,000 real ad breaks fire VAST beacons, duplicated at
5, 10 and 20%, lost at 1%, sent to the wrong region at 2%.

- **15,111 billed impressions across the three runs, each counted exactly once: 0 duplicate
  rows, 0 impressions missing, 0 billed without a beacon, across 11,370 duplicated beacons.**
  The billing table held exactly one row for every impression whose IMPRESSION beacon was sent
  at least once (5,053, 4,978 and 5,080).
- **Parallel-run audit:** every campaign's billed total equalled the beacon consumer's
  independently counted confirmed spend in Redis (53 of 53, 55 of 55, 55 of 55 campaigns): two
  pipelines, one answer.
- 110 or 111 rows per run were flagged rerouted (beacon arrived in the other region), about 2%
  of billed impressions, as injected.

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

## Experiment 11: what the auction clears, second price against first price

`results/exp11_pricing.jsonl`. The whole replay day 2013-06-11 (1,745,722 ad breaks, 55
campaigns) through the real decision engine (targeting, brand safety, caps, DP pod assembly),
unlimited budgets and unpaced so the pricing rule cannot change which pods win, once per rule in
lockstep: **all 1,745,722 pods were identical under both rules**, 5,549,515 impressions. Reserve
50 micros. 95% intervals are a paired bootstrap over the day's 1,541,127 viewers (1,000
resamples). Money is the log's currency, yuan; this is a simulation over replayed traffic, not
revenue anyone earned. Run on the Windows mini PC.

| Pricing | Cleared over the day | Mean price per impression | Price over bid |
|---|---|---|---|
| **Second price, per pod slot (AdServe)** | **2,759.89 yuan** (2,756.94 to 2,762.59) | 497 micros | 36.3% |
| First price (pay the bid) | 7,598.96 yuan (7,593.03 to 7,604.34) | 1,369 micros | 100% |
| Exact critical value (one extra solve per winner) | 3,241.75 yuan (3,238.66 to 3,244.56) | | |

- **Second price cleared 36.3% of first price at identical bids** (interval 36.29% to 36.34%).
  That is not a forecast of what first price would earn: under first price advertisers shade
  their bids, and experiment 12 measures that they gain by it.
- 81.2% of slots were priced by a rival and 18.8% by the reserve; 176 slots (0.003%) were capped
  at the winner's own bid; no price was above a bid or below the reserve.
- **The per-slot swap price against the exact critical value** (DESIGN.md, The known
  approximation): swap pricing collected **85.1%** of the critical-value total. It matched the
  critical value on 72.6% of slots, was below it on 27.4%, and above it on 12 slots of 5.5
  million. Per slot the error was 0 at the median, 50% at p90 and 93% at p99: the swap misses the
  cases where removing a winner lets a combination of shorter spots or a different rival take the
  room, and in those it undercharges.
- By advertiser, second-price revenue ranged from 24.9% of bid value (adv3386) to 53.2%
  (adv3476): the advertisers facing the closest rivals pay the most of their bids.

## Experiment 12: shading a bid, under second price and first price

`results/exp12_shading.jsonl`; chart `docs/img/shading.svg`. One advertiser at a time multiplies
every bid by 1 - shade (0 to 50% in 5-point steps) while the other four bid as before, over the
whole replay day 2013-06-11 (1,745,722 ad breaks, every viewer), unlimited budgets, unpaced,
frequency caps on, reserve 50 micros. Pods are identical under both rules at every shade (the
rule changes only what a slot is charged). "Value" is the advertiser's own unshaded bid value of
each impression it wins, so surplus = value - cost, and truthful bidding under first price has
zero surplus by construction. Run on the Windows mini PC (AMD Ryzen 3 4300U), CPU only.

- **Under second price, bidding its full value was the best of the 11 shades for all 5
  advertisers.** For the one with the most impressions (adv3358), a 25% shade gave up **17.5%**
  of its impressions and **93.26 yuan** of surplus (1,669.62 to 1,576.36), and every shade from
  5% to 50% left it worse off.
- **Under first price, shading paid for all 5.** adv3358's surplus peaked at a **40%** shade,
  **802.12 yuan** above bidding its value; the others peaked at 10% to 50%.
- The shade lowers what the advertiser pays per impression under both rules (it stops winning
  the slots where its margin was thinnest), which is why a naive reading of "cost per
  impression" would recommend shading under second price too; the surplus column is what shows
  it does not pay.
- GSP with several slots is not truthful in theory (Edelman, Ostrovsky and Schwarz 2007). On
  this replay, over this grid of uniform shades, no advertiser found a profitable deviation;
  that is a measurement of this market, not a proof.

## Experiment 13: does the auction cost latency?

`results/exp13_latency.jsonl`. The experiment 1 burst (8,000 LIVE requests within 2 s, 30 s
warm-up at 3,000/s, G1, virtual threads) against three servers, interleaved in two rounds of
three repeats each so the laptop's load drifted over all of them alike: this server with
second-price pricing, the same server with first-price pricing, and the server as of commit
`d208783`, before the auction existed. All three seed the same campaigns (bids from the replay).
**The laptop was busy with another project's model training throughout: the 1-minute load
average before the bursts ranged from 35 to 73.** Read this table as "no difference the noise
can show", not as capacity.

| Server | p99 per burst, sorted | Median p99 | Errors | Counter deadline missed (of 8,000) | Ads per pod |
|---|---|---|---|---|---|
| Auction, second price | 63.2, 81.6, 100.7, 119.6, 144.5, 185.9 ms | 110.2 ms | 0 of 48,000 | 282 to 2,518 | 1.67 to 1.69 |
| Auction, first price | 42.8, 69.6, 79.3, 109.1, 119.7, 174.7 ms | 94.2 ms | 0 of 48,000 | 364 to 3,313 | 1.46 to 1.48 |
| Before the auction | 97.5, 103.9, 107.8, 121.0, 199.4, 923.6 ms | 114.4 ms | 0 of 48,000 | 125 to 3,429 | 1.44 to 1.48 |

- **Every leg served all 48,000 requests with 0 errors, and the three p99 ranges overlap
  almost completely.** Under this load the auction and its pricing step add nothing the burst
  can resolve; the CPU cost itself is in the JMH section below.
- Second-price pods held more ads (1.67 to 1.69 against 1.44 to 1.48). Winners are charged the
  cleared price instead of the bid, so budgets and the pacers' spend drain slower through the
  warm-up and burst and more campaigns stay eligible. Pod assembly itself is identical under
  both rules.
- These p99s are several times experiment 9's 7.3 to 10.2 ms for the same burst on a quieter
  laptop, and the missed-deadline counts are high in every leg (BUG_LOG bug 11): the load, not
  the code, sets them.

## Experiment 14: pacing when winners pay the cleared price

`results/exp14_pacing_pricing.jsonl` (per campaign in `exp14_pacing_pricing_campaigns.jsonl`).
Experiment 2's setup (8 nodes, 10 s sync, every pacer, with and without the per-node allowance,
the replay day 2013-06-11) on the bids derived from the replay, once under first price (charged
the bid, as in experiment 2) and once under second price (charged the cleared price), reserve 50
micros. Budgets are unchanged: each campaign's real spend that day. Run on the Windows mini PC.

| Pacer, allowance on | First price: within 5% / overspent / out before 23:00 / RMSE | Second price: within 5% / overspent / out before 23:00 / RMSE | Second price: budget delivered |
|---|---|---|---|
| **Smart Pacing** | **55 / 0 / 1 / 0.082** | **20 / 0 / 0 / 0.215** | 72.5% |
| Throttling | 55 / 0 / 33 / 0.185 | 28 / 0 / 10 / 0.299 | 74.5% |
| PID | 27 / 0 / 8 / 0.169 | 10 / 0 / 3 / 0.262 | 65.7% |
| Perfect forecast | 55 / 0 / 46 / 0.151 | 31 / 0 / 12 / 0.278 | 77.0% |
| Unpaced | 55 / 0 / 49 / 0.432 | 34 / 0 / 27 / 0.405 | 83.2% |

- **Under first price, experiment 2's result holds on the new bids and slightly improves: Smart
  Pacing put all 55 campaigns within 5% of budget, overspent none, and RMSE fell to 0.082.**
- **Under second price the safety half holds (0 overspent with the allowance, 0 out of budget
  early under Smart Pacing), but budgets stop being spendable.** Winners paid 25.5 to 29.3% of
  their bids on average, so the same budgets bought 2.4 times the impressions under Smart Pacing
  (5.66 million against 2.35 million) and 2.6 times unpaced, and many campaigns ran out of traffic before they ran out of
  money.
- The mechanism is the one-ad-per-advertiser rule. Unpaced under second price, **11 campaigns
  spent under 5% of their budgets** while sibling creatives of the same advertiser spent in full:
  the advertiser's strongest creative held its one slot per pod all day. Under first price the
  same leaders spent out within the first hours and the siblings took over (0 starved).
- Smart Pacing's own shortfall: of the 35 campaigns it left more than 5% short, 21 were short
  even unpaced; the other 14 were spendable (unpaced spent them) and Smart landed 10 to 51%
  short, most likely because throttling a leader changes its siblings' traffic in ways the
  per-campaign forecast does not model.
- Without the allowance, second-price unpaced delivery overspent 34 campaigns, the worst by 552%; Smart Pacing 11 (worst 15%). The allowance still does the work it did in experiment 2.

## Experiment 15: pacing by bid multiplier instead of throttling

`results/exp15_bid_pacing.jsonl` (per campaign in `exp15_bid_pacing_campaigns.jsonl`), tuning
on the forecast day in `results/exp15_tuning*.jsonl`. The exp2 fleet (8 nodes, 10 s sync), second
price with cleared prices charged, the replay day 2013-06-11. The bid-scaling pacer (DESIGN.md,
Pacing) keeps every campaign in every auction and scales its bid by lambda; its gain (0.5) was
chosen on 2013-06-10 from 0.25, 0.5 and 1.0 by RMSE against plan. Run on the Windows mini PC.

| Pacer, per-node allowance on | Within 5% of budget | Overspent | Out before 23:00 | RMSE vs plan | Budget delivered | Cost per expected click (micros) |
|---|---|---|---|---|---|---|
| Bid scaling | 34 | 0 | 25 (mean 14:01) | 0.364 | 85.4% | 208,104 |
| Smart Pacing (throttling) | 20 | 0 | 0 | 0.215 | 72.5% | 183,552 |
| Unpaced | 34 | 0 | 27 (mean 14:40) | 0.405 | 83.2% | 197,425 |

- **Bid scaling did not pace this market.** It ran out early almost as often as no pacing (25
  campaigns against 27), and cost 13% more per expected click than throttling. Without the
  per-node allowance it overspent 29 campaigns (unpaced 34, Smart 11).
- Why: under second price a campaign pays the rival's score or the reserve, not its bid, and
  with five advertisers and one ad per advertiser per pod most slots have a weak rival or none
  (the price averaged 29% of the bid unpaced). Lowering the bid changes neither what the
  campaign wins nor what it pays until the bid drops below that price, and then it loses the
  slot outright. Spend responds to lambda as a step, not a slope, and a multiplicative
  controller on a step oscillates. Bid multipliers are the standard answer in thick auctions,
  where the price moves with the bid; this replay is thin.
- Smart Pacing kept every campaign in budget all day but delivered only 72.5% of budgets, its
  median campaign landing 25% short; experiment 14 explains why.

## Experiment 16: miscalibrated click-rate predictions in the auction

`results/exp16_calibration.jsonl`. The log's smoothed click rates are treated as the truth; the
auction ranks and prices with a distorted prediction and outcomes are scored on the truth. Whole
replay day 2013-06-11, second price, caps on, reserve 50 micros; the first leg has unlimited
budgets and no pacing, so only allocation and price move. Intervals: paired bootstrap over viewers (4,096 hash clusters, 2,000 resamples) for single
conditions, and a t interval over 10 seeds for the noise conditions. Allocative efficiency is the
true value delivered (bid x true rate x duration factor) over what true rates would have
delivered. Run on the Windows mini PC.

| Prediction | Change in true clicks | Change in cost per true click | Allocative efficiency |
|---|---|---|---|
| Every rate x 2.0 | 0.00% | **+98.11%** (billed per click instead: -0.94%) | 1.000 |
| Every rate x 1.25 | 0.00% | +24.55% | 1.000 |
| Every rate x 0.5 | 0.00% | -49.06% | 1.000 |
| One advertiser (adv3358) x 0.5 | +9.95% | -47.74% | **0.914** |
| One advertiser (adv1458) x 2.0 | +4.18% | -22.49% | 0.937 |
| Per-creative noise, sigma 0.1 (10 seeds) | +1.28% (0.00 to +2.57) | -0.97% (-6.42 to +4.48) | 0.994 (0.992 to 0.997) |
| Per-creative noise, sigma 0.25 | +3.33% (+0.28 to +6.38) | -15.70% (-29.55 to -1.86) | 0.949 (0.911 to 0.986) |
| Per-creative noise, sigma 0.5 | -10.07% (-20.05 to -0.08) | +14.23% (-20.25 to +48.70) | **0.792** (0.678 to 0.905) |

- **A uniform bias moves no impression but reprices every one.** At 2x, winners and true clicks
  are unchanged and advertisers pay 98.11% more per true click (short of 100% because 19% of
  slots clear at the fixed reserve). Billed per click at the cleared cost per click, the same
  bias nearly cancels (-0.94%): per-impression billing is what makes calibration a pricing
  problem, not only a ranking one.
- **A relative bias moves impressions.** Under-predicting the largest advertiser by half cost
  8.6% of allocative efficiency; it lost 57% of its true clicks, and the reserve priced 57% of
  slots instead of 19%. Total true clicks can rise while efficiency falls, because the auction
  maximises value (bid x rate), not clicks.
- **Noise costs efficiency roughly with its size:** 0.6% at sigma 0.1, 5.1% at 0.25, 20.8% at 0.5
  (worst seed 44%).

**With budgets binding** (second leg: each campaign's real budget, Smart Pacing from the forecast
day, same conditions except that the per-advertiser under-prediction and noise conditions were not
run here), a miscalibration that raises prices also spends budgets sooner, so it costs clicks:

| Prediction, paced | Change in true clicks | Change in cost per true click | True value delivered vs true rates |
|---|---|---|---|
| Every rate x 2.0 | **-22.11%** | **+59.95%** | 0.795 |
| Every rate x 1.25 | -7.26% | +18.18% | 0.936 |
| Every rate x 0.8 | +6.83% | -15.77% | 1.057 |
| Every rate x 0.5 | +17.87% | -40.33% | 1.156 |
| One advertiser x 2.0 (each of the five) | -1.51% to -6.64% | -2.08% to +14.26% | 0.974 to 0.987 |

- **With real budgets, a 2x over-prediction cost advertisers 22.11% of their true clicks and
  raised their cost per true click 59.95%.** Under-prediction does the reverse (cheaper
  impressions stretch the same budgets), which is why the last column can exceed 1 here: it is
  the value delivered relative to the true-rate run, not an efficiency bounded by it.
- The reserve priced 71% of slots in this leg against 19% unpaced: with every campaign throttled,
  most slots have no rival left (the same thinning experiment 14 describes).

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

### JMH with the auction

`results/jmh-auction.json`, same benchmark class, one fork, 5 measured iterations, run while the
laptop's load average was about 35 (another project's training), so the error bars are wide:

| What | Time |
|---|---|
| Second-price pricing of one assembled pod (every slot against its best legal rival) | **1.1 us** (+- 0.4) |
| One whole decision, second price | 22.6 us (+- 7.4) |
| One whole decision, first price (same scoring and assembly, no rival search) | 19.3 us (+- 4.0) |
| DP pod solve | 9.5 us (+- 5.0) |
| All 55 targeting predicates | 184 ns (+- 21) |

Pricing is about 1 us against a DP solve of 7 to 10 us: a small fraction of the decision, and
the whole-decision gap between the two rules is inside the noise of this run.

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

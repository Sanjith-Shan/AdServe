# AdServe design

## Sources and credits

AdServe is not a clone of any company's system. It is a video ad server built to understand
and measure trade-offs that ad-serving teams have described in public. These are the sources,
and what each one contributed.

- Netflix Technology Blog, *Behind the Scenes: Building a Robust Ads Event Processing Pipeline*
  (2025), and *Evolving Netflix's Ads Event Pipeline for Live, Part II* (2026-08-17). The opaque
  impression token that carries the serving region, the decision log on Kafka instead of a
  synchronous metadata write, frequency capping that cannot tolerate late events, and the
  lesson "take the database off the hot path when you can", which experiment 1 measures.
- Netflix Technology Blog, *Netflix Live Origin* (2025-12). Priority-based load shedding that
  serves higher-impact requests first and refuses the rest with a 503 and a retry hint, which
  is the shedding policy here (LIVE before VOD).
- Netflix Ads Engineering job postings (Software Engineer 4, Ads Engineering; Distributed
  Systems Engineer, Ad Server Platform) for the four-stage vocabulary: request orchestration,
  targeting evaluation, policy enforcement and ad selection under latency SLAs.
- Agarwal, Ghosh, Wei, You. *Budget Pacing for Targeted Online Advertisements at LinkedIn*,
  KDD 2014. The probabilistic throttling pacer.
- Xu, Lee, Li, Qi, Lu. *Smart Pacing for Effective Online Ad Campaign Optimization*, KDD 2015.
  The slot-allocation pacer (without its layered response-rate tiers; see Pacing).
- The PID pacer is ported from the author's own AdRankBench pacing simulator.
- IAB Tech Lab, VAST 4.x, for the tracking event names (impression, start, firstQuartile,
  midpoint, thirdQuartile, complete, click) and ad pod semantics.
- Zhang, Yuan, Wang, Shen. *Real-Time Bidding Benchmarking with iPinYou Dataset*, 2014, and the
  `wnzhang/make-ipinyou-data` repository, for the log layout and the advertiser industries
  used as competitive-separation categories.
- Netflix open source: the DGS framework (`netflix.github.io/dgs`) for the GraphQL campaign API,
  and Hollow (`hollow.how`) for the campaign snapshot.
- HdrHistogram (Gil Tene) for latency recording, and the coordinated-omission argument behind
  the open-loop load generator.

## What AdServe is, and is not

AdServe is a video ad server built to understand and measure the trade-offs that published
ad-serving designs describe: deciding under a latency budget with no database on the request
path, idempotent frequency counters under at-least-once delivery, budget pacing, pod assembly,
and priority shedding when a live event cuts to break. It is not a clone of any company's
system and does not claim to reproduce one. Everything runs on one laptop.

## Components

```
            gRPC AdDecision.Decide  /  REST /v1/decide          GraphQL /graphql (DGS)   /console
                        |                                               |                   |
                 +------v-----------------------------------------------v-------------------v--+
                 | server (Spring Boot 3.5, Java 21)                                             |
                 |  admission (LIVE before VOD)                                                  |
                 |  DecisionEngine: resolve > targeting > policy > caps > pacing > pod > tokens  |
                 |  campaign snapshot (in process) <- Postgres poll every 2 s, or Hollow deltas  |
                 +---+-----------------------------+--------------------------+------------------+
                     | one MGET per decision        | fire-and-forget            | never on the request path
                     v                              v                            v
                  Redis counters              Kafka ad.responses            Postgres campaign store
                     ^                              |
                     | same idempotent scripts      v
                  beacon consumer  <-- ad.beacons.<region> <-- devices (simulated players)
                                                    |
                                     Flink billing job: normalize > join > dedupe > billing_events
```

Modules: `contract/` (the proto), `api/` (generated Java), `core/` (the decision path and every
algorithm, no I/O), `server/`, `beacons/` (consumer and the Redis counter scripts), `billing/`
(Flink), `sim/` (data loaders, simulators, load generators, experiments), `ui/` (console).

## The decision path

One call per ad break. Every stage is timed and exported as `adserve_stage_seconds`.

1. **Resolve.** The request carries viewer, title, genre, break length, device, geo, serving
   region and priority. Segments come on the request or from the in-process viewer cache.
2. **Targeting.** Each campaign's targeting is compiled once per snapshot into bitsets over
   interned dictionaries (geos, segments, genres) and a device mask. Evaluation reads final
   fields and the request's bitsets and allocates nothing.
3. **Policy.** Flight window, budget remaining, and platform brand safety (an ad category never
   runs in some genres, whatever the campaign targeted).
4. **Frequency cap.** One Redis MGET for the day and week counters of every surviving *capped*
   campaign plus the viewer's hourly ad count, with a 20 ms deadline. If Redis does not answer,
   the configured cap mode decides: `UNKNOWN_ALLOW` serves as if counts were zero,
   `UNKNOWN_DENY` drops every capped campaign.
5. **Pacing gate.** Each campaign's pacer admits the request with its current pass-through rate.
6. **Selection and pod assembly.** Every creative of every surviving campaign becomes an item
   (duration, value). The DP solver fills the break (see Pod assembly). The pod is validated
   before it leaves the process; an invalid pod is replaced by an empty one and counted.
7. **Tokens.** Each impression gets a signed token: `base64url(Token || HMAC-SHA256)`. The token
   carries impression, campaign, creative, serving region, viewer and price, so a beacon can be
   verified and counted without a lookup, and a beacon that arrives in the wrong region knows
   where it was served.
8. **Log.** The whole decision (request, scored candidates and why each was dropped, the pod,
   tokens, prices) is offered to a bounded in-memory queue and published to `ad.responses` by one
   background thread with acks=1. The response never waits for it. When Kafka is down the queue
   fills and records are dropped and counted. The pod's counters are written with one
   fire-and-forget script call.

Nothing on this path awaits a database. The `--adserve.legacy-sync-write=true` mode inserts
each decision into Postgres before responding; it exists only as the baseline for experiments 1
and 6.

## Frequency capping

Counters live in Redis as `fc:{viewer}:campaign:d<day>`, `fc:{viewer}:campaign:w<week>` and
`al:{viewer}:h<hour>`. The `{viewer}` hash tag keeps one viewer's keys in one cluster slot, so
the scripts stay single-slot.

Two writers count the same impression: AdServe at decision time (optimistic) and the beacon
consumer when the device's IMPRESSION beacon arrives (authoritative). Both use the same stable
event id, `sha256(impression_id | IMPRESSION | 0)`, and the same Lua logic: `SET event NX EX`,
and only if that succeeded, increment. So a retried beacon, a redelivered Kafka batch, and the
decision-time write of the same impression all count once. The client's `beacon_id` is not used
for this because it is not unique across devices.

Why write at decision time at all: if only beacons counted, a lost or late beacon (a device that
went offline) would leave the counter low and the viewer could be shown the ad again.
Experiment 3 separates the two effects.

## Budget and the fleet

Spend is charged in process when an impression is served and reconciled once a second with the
confirmed spend the beacon consumer counts in Redis (`BudgetSync`), so a restart converges. In a
fleet, every node sees global spend only as of its last sync. `FleetBudgetLedger` gives each
node only its share of what remained at that sync, so the fleet cannot overshoot by more than
one impression per node. Experiment 2 measured the overshoot without it (BUG_LOG bug 4).

## Pacing

Three pacers behind one interface, a pass-through probability per campaign updated once per
one-minute slot, plus two baselines:

- **Throttle** (Agarwal et al., KDD 2014): multiply the rate by 1.1 or 0.9 depending on whether
  the last slot spent under or over its allocation (the remaining budget spread over the
  remaining forecast traffic).
- **Smart** (Xu et al., KDD 2015): re-allocate the remaining budget across the remaining slots in
  proportion to forecast traffic at every boundary, estimate what the next slot would spend at
  rate 1 from the last slot (spend over rate, smoothed, scaled by the forecast's shape), and set
  the rate to allocation over that estimate. The paper's layered rates by predicted response
  rate are not implemented: they need a per-request response prediction, and AdServe has only
  per-creative click rates from the logs.
- **PID**, ported from AdRankBench (kp 0.5, ki 0.05, kd 0.1) on the gap between planned and
  actual cumulative spend.
- **Unpaced** baseline, and a **perfect-forecast** baseline (`oracle` in the results) that runs
  the Smart controller with the replay day's own eligible traffic instead of the previous day's.
  It was meant to bound what forecast error costs. It did not beat the real forecast: it ran
  slightly ahead of plan and exhausted budgets about 1.7 hours early, so on this day the
  controller's spend estimate, not the forecast, was the limiting factor. The first oracle
  (foreknowledge of full-rate spend from an unpaced pass) was worse still and is archived.

Every pacer starts from a forecast warm start (BUG_LOG bug 5). The forecast is the previous
day's eligible traffic per campaign per minute (`sim forecast`), smoothed over 15 minutes.

## Pod assembly

The break length is the capacity; creatives are items with a duration (15, 30, 60 s) and a
value. Rules: at most one creative per advertiser (and so per campaign), competitive separation
by category (never adjacent, or at most one per pod), a minimum and maximum count, and the
viewer's remaining hourly ad load.

- **DP** over 15-second slots: a multiple-choice knapsack over advertisers with the ad count in
  the state, exact for capacity, count and one-per-advertiser. Separation is not in the state;
  when the DP's pick cannot be ordered legally it is repaired (drop the cheapest of the
  over-represented category, refill greedily, 1-swap). This is the solver the server uses.
- **Greedy** by value per second with a repair step for the minimum count, and **greedy with
  1-swap** local search.
- **Exact** branch and bound over every rule, pruning with the smaller of a fractional-knapsack
  bound and a best-remaining-values bound. It is the baseline; a node budget flags any break it
  could not prove, and those are excluded from the gap statistics.

Ordering: the most valuable spot plays first, then at each position the most valuable remaining
creative whose category differs from the previous one and whose removal keeps the rest
arrangeable (a multiset can be ordered with no equal neighbours iff its most common category
appears at most ceil(n/2) times). `PodValidator` checks every returned pod; jqwik properties
check every solver on thousands of random catalogues and prove branch and bound equals brute
force.

Value of one impression: `cpc_bid * click_rate * duration_factor`, with duration factors 0.6
(15 s), 1.0 (30 s), 1.7 (60 s). Longer spots cost more but less per second; the factors are an
assumption, not data.

## Overload

Two request classes. One token bucket refills at the measured capacity; LIVE may take any token,
VOD only while the bucket holds more than a reserve kept for LIVE (30%), and an in-flight bound
refuses VOD first as concurrency climbs. A refused request gets gRPC `RESOURCE_EXHAUSTED` with a
`retry-after-ms` trailer, or HTTP 503 with `Retry-After` and `Cache-Control: max-age` on the
REST facade, so clients and intermediaries back off instead of hammering.

## Campaign management

GraphQL on the DGS framework (`/graphql`): advertisers, campaigns with their flight, creatives,
caps and targeting, and delivery so far (spend, confirmed spend, budget fraction, pacing rate).
Every mutation refreshes the serving snapshot, so a campaign created through GraphQL is served
by the next decision (tested). gRPC stays on the decision path.

The campaign snapshot reaches serving nodes one of two ways: each node polls Postgres every two
seconds and rebuilds (default), or one publisher runs a Hollow producer cycle and every node
consumes snapshots and deltas from a blob store (`adserve.hollow.source=hollow`), which takes
the database out of the serving fleet's dependencies entirely.

## Billing

A Flink job over `ad.responses` and `ad.beacons.*`: **normalize** (verify each token, keep
IMPRESSION events, compute the stable event id), **join** by impression id in a keyed
co-process function (either side may arrive first; a 60-minute window), **deduplicate** by
event id in keyed state with a 24-hour TTL, and **publish** to `billing_events` with an insert
that does nothing on a repeated event id. Checkpoints are at-least-once. The table's primary key
makes the result exactly-once. Beacons that arrive in the other region are flagged `rerouted`
from the token's serving region. Experiment 7 audits the table against the ground truth and
against the beacon consumer's independent counters.

## Data, and every assumption the log could not supply

Replay day: iPinYou season 2, 2013-06-11 (1,745,722 impression rows). Forecast day: 2013-06-10
(1,920,370 rows). Five advertisers, 55 creatives.

Taken from the log: arrival time, viewer id, region (as the targeting geo), user agent, domain,
user tags (as segments), advertiser, creative, paying price, clicks.

Derived, deterministic, and assumed:

| What | Rule | Why |
|---|---|---|
| Campaign | one per (advertiser, creative) | the log has no campaign structure |
| Daily budget | the creative's actual spend that day (sum of paying price) | pacing runs against a real spend curve |
| CPC bid | advertiser's average price per impression over its average click rate | so a 30 s spot at average click rate is worth what the advertiser paid |
| Click rate | clicks over impressions, both days, smoothed toward the advertiser rate (2,000-impression prior) | from the logs, not a model |
| Category | the advertiser's industry from Zhang et al. 2014 (e-commerce, software, oil, tire) mapped to retail, software, auto | real, and two pairs collide, so separation binds |
| Targeting | fewest regions covering 90% of the creative's impressions (any if more than 20); device classes with 5% share; the advertiser's lifted user tags if they cover 60% | derived from real delivery |
| Creative duration | hash of creative id: 15 s 35%, 30 s 50%, 60 s 15% | the log has display creatives |
| Break length | hash of bid id: 30 s 10%, 60 s 25%, 90 s 45%, 120 s 20% | the log has no breaks |
| Device | user agent (phones and tablets are mobile); a stable 40% of other viewers labelled tv | 2013 logs have no smart TVs |
| Genre | hash of the domain into ten genres | the log has no titles |
| Serving region | hash of the region code into US_EAST or US_WEST | two data centres for routing |
| Frequency cap | hash of creative: 2, 3 or 4 a day (week 3x), a quarter uncapped | the log has no caps |
| Currency | paying price is CPM in fen; one impression in micros is price x 10 | units |

No one watched anything. Viewers are simulated in the sense that the arrival, frequency and
click structure is real and nothing else is.

## Runtime choices

The server runs on JDK 21 with a virtual thread per gRPC request, so the one blocking call (the
counter fetch) parks a virtual thread instead of a platform thread. The collector is G1.
Generational ZGC was the first default; experiment 9 measured both on the same bursts, and on
this CPU-contended laptop G1 held a lower p99 and missed no counter deadlines where ZGC missed
some, most likely because ZGC's concurrent collector threads compete for cores with the load
generator. Every result row records the server's flags (`server_jvm`); rows before that field
existed ran generational ZGC.

## Measurement

HdrHistogram in the load generator, which is open loop: request i has an intended send time
and latency is measured from it, so a stalled server shows up as latency, not as a slower
client (coordinated omission). The server exports Micrometer timers to Prometheus. Every result
line in `results/*.jsonl` records the machine, JVM flags, power mode, load average, offered load,
and that the generator ran on the same machine.

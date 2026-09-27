# Scaling notes

What would have to change for AdServe to serve a synchronized break for a very large live
audience. Nothing here is measured at that scale. Figures marked *measured* come from
`NUMBERS.md` (one laptop); everything else is arithmetic or design reasoning, and says so.

## The shape of the problem

A live event cuts to break and every player asks for a pod within a couple of seconds. The load
is not a daily peak that ramps over an hour. It is a wall: the whole concurrent audience, in the
time it takes players to reach the break marker. Three properties follow.

1. **The per-request budget has to hold at the wall, not on average.** p99 at the peak second is
   the number that matters, which is why experiment 1 offers the burst as an open-loop gamma
   curve instead of a steady rate.
2. **Anything shared and synchronous on the path multiplies.** One database write per decision
   is one write per viewer per break, all in the same seconds. That is the design experiment 1
   compares against, and the reason the decision log is fire-and-forget.
3. **Degradation has to be decided in advance.** When the wall exceeds capacity, somebody gets a
   slower or emptier answer. Deciding that live viewers are served first, and that on-demand
   requests get a 503 with a retry hint, is a policy, not an accident.

## Fleet sizing, as arithmetic

If one serving node sustains **R** decisions per second with p99 under the budget (*measured*
on the laptop for one node, with the load generator on the same machine), a break of **V**
viewers arriving over **T** seconds needs about `V / (T * R)` nodes before headroom, and the
peak second of a gamma-shaped arrival is roughly twice the mean. Two cheaper levers come first:

- **Spread the wall.** Players learn the break is coming (the live manifest carries the marker
  before it airs). Asking for the pod 20 to 30 seconds early, with a jittered start, turns a
  2-second wall into a 30-second ramp: fifteen times fewer nodes for the same audience. The
  decision is then slightly stale (a cap or budget can move in 30 s), which the pacers and the
  per-node budget allowance already tolerate.
- **Decide once per cohort where personalization adds nothing.** For a sponsor's
  break-exclusive pod, every viewer in a region gets the same creatives. Only caps and
  targeting make pods differ. A per-(region, device, segment-bucket) pod cached for the break,
  with a per-viewer cap check on top, removes most of the solver work.

## State and where it lives

| State | Where | Scaling rule |
|---|---|---|
| Campaigns, targeting, creatives | In process, compiled | Read-mostly and small: every node holds all of it. Delivered by Hollow snapshot plus deltas, so the fleet never reads the database (*measured* 1.1 KB delta for one changed campaign in a 5,000-campaign catalogue) |
| Frequency counters | Redis, keyed by `{viewer}` | Shards cleanly by viewer. No hot key: one viewer is one request per break. One round trip per decision with a deadline |
| Campaign spend | In process per node, reconciled every second | The hot key problem, avoided: every node would otherwise increment the same campaign counter. Each node spends only its share of what remained at the last sync (`FleetBudgetLedger`) |
| Pacing rates | In process per node | Each node paces against global spend. At fleet scale a central pacing service would publish rates every slot instead |
| Decision log | Kafka `ad.responses`, keyed by request id | Partitions scale with brokers; the producer is batched and never awaited |
| Beacons | Kafka `ad.beacons.<region>`, keyed by token | The token carries the serving region, so a misrouted beacon is forwarded, not joined against a replicated decision log |

## The degradation ladder

Measured on one node in experiments 5 and 6; the order is the design.

1. **Overload**: shed VOD first (503 or RESOURCE_EXHAUSTED with retry-after), keep LIVE p99.
2. **Redis slow or down**: the cap check has a 20 ms deadline. After it, the cap mode decides:
   serve as if uncapped (revenue first) or drop capped campaigns (viewer first). Either way the
   break is answered.
3. **Postgres down**: nothing on the path notices. The snapshot ages; the staleness gauge climbs.
4. **Kafka down**: decisions still go out. The bounded log buffer fills, then drops and counts.
   Billing is then short of decision context for the gap, which is what the billing job's
   unmatched-beacon counter and a recovery job exist for.

## What I would change next

- Move the pod-assembly inputs for LIVE breaks off the request path entirely: when the break is
  announced, precompute per-cohort candidate sets, and let the request do only the cap check and
  the final pick.
- Replace per-node pacing with a pacing service that publishes rates per slot, so pacing error
  does not grow with the number of nodes.
- Run the counter fetch and the pod solve concurrently: the solver can start on the candidate
  set and drop capped items when the counters arrive.

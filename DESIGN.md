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

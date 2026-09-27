# Superseded measurements

Kept so that nothing measured is lost. Each file here was replaced by a later run for the reason
given; the numbers in NUMBERS.md never come from this folder.

- `exp2_pacing_run2_fullrate_oracle.jsonl` (and its campaigns and curves files): the second
  pacing run (after BUG_LOG bugs 4 and 5 were fixed). Its "oracle" baseline knew each campaign's
  full-rate spend per minute from an all-unpaced pass. Once other campaigns of the same
  advertiser were throttled, competition changed, the oracle's foreknowledge was wrong, and it
  exhausted 50 campaigns by about 09:00 (RMSE 0.385, worse than the real pacers). The oracle was
  redefined as the slot-allocation controller given a perfect forecast: the replay day's own
  eligible traffic. Every other row of this run is identical in configuration to the current
  file. The first run (before bugs 4 and 5 were fixed) was not saved; its headline figures are in
  BUG_LOG.md.

- `exp7_billing_run1_job_not_running.jsonl`: the first billing run. The Flink job died at
  startup (missing `flink-connector-base`, BUG_LOG bug 10), so the billing table stayed empty
  and every row shows 0 billed. The audit also printed an agreement of 1.0 over zero campaigns
  compared, which was a bug in the audit; it now reports null and exits non-zero on an empty
  table. The player-side figures in these rows (impressions served, beacons and duplicates
  sent) are valid but are superseded by the rerun.
- `jmh-hotpath_run1_growing_counter_map.json`: the first JMH run. Every benchmark except
  `fullDecision` is valid and identical in method to the rerun. `fullDecision` (69.6 us +/- 65.9)
  gave every iteration a new viewer id against an in-memory counter map, so the map grew by
  millions of keys during the run and the figure measured the map, not the decision path.
- `exp7_billing_run2_reroute_flag_wrong.jsonl`: the second billing run (job running). Every
  deduplication figure in it is valid (0 duplicate rows, 0 missing, 0 billed without a beacon,
  53 and 54 of 54 campaigns agreeing with Redis, at 5, 10 and 20% duplicates), but the players
  sent beacons to the log row's region instead of the serving region, so about half the rows were
  flagged rerouted (BUG_LOG bug 12). Rerun with the fix.

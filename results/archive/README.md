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

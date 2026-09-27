#!/usr/bin/env bash
# Downloads the iPinYou season 2 logs the experiments replay (needs a Kaggle token in ~/.kaggle).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data/raw
for f in imp.20130610 imp.20130611 clk.20130610 clk.20130611; do
  [ -f "data/raw/$f.txt.bz2" ] || kaggle datasets download lastsummer/ipinyou \
    -f "ipinyou.contest.dataset/training2nd/$f.txt.bz2" -p data/raw
done
ls -la data/raw

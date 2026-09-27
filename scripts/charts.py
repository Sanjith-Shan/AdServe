"""README charts from results/*.jsonl. Run: python3 scripts/charts.py"""
import json, statistics, os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

SURFACE, INK, MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e7e6e1"
BLUE, ORANGE, AQUA, GRAY = "#2a78d6", "#eb6834", "#1baf7a", "#8a8984"
plt.rcParams.update({"font.family": "sans-serif", "font.size": 10, "axes.edgecolor": GRID,
                     "axes.labelcolor": MUTED, "xtick.color": MUTED, "ytick.color": MUTED,
                     "axes.facecolor": SURFACE, "figure.facecolor": SURFACE})

def rows(f):
    p = os.path.join("results", f)
    return [json.loads(l) for l in open(p)] if os.path.exists(p) else []

def style(ax, title):
    ax.set_title(title, loc="left", color=INK, fontsize=11, pad=12)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for s in ("top", "right"): ax.spines[s].set_visible(False)

def burst():
    r = rows("exp1_burst.jsonl")
    if not r: return
    fig, ax = plt.subplots(figsize=(7, 4.3))
    for label, color, name in (("adserve", BLUE, "AdServe (decision logged to Kafka)"),
                               ("legacy_sync_write", ORANGE, "Baseline (decision written to Postgres first)")):
        pts = [(o["burst_requests"], o["live"]["p99_ms"]) for o in r if o["label"] == label]
        sizes = sorted({n for n, _ in pts})
        med = [statistics.median([p for n, p in pts if n == s]) for s in sizes]
        ax.plot(sizes, med, color=color, linewidth=2, label=name)
        ax.scatter([n for n, _ in pts], [p for _, p in pts], s=22, color=color, edgecolors=SURFACE, linewidths=1.5, zorder=3)
        ax.annotate(f"{med[-1]:.0f} ms", (sizes[-1], med[-1]), xytext=(6, 0), textcoords="offset points", va="center", color=MUTED, fontsize=9)
    ax.set_yscale("log")
    ax.set_xlabel("requests arriving within 2 s as a live event cuts to break")
    ax.set_ylabel("p99 decision latency (ms, log)")
    ax.set_xticks(sizes); ax.set_xticklabels([f"{s:,}" for s in sizes])
    style(ax, "Burst p99, three repeats per size (dots), median (line)")
    fails = {o["burst_requests"]: 0 for o in r}
    for o in r:
        if o["label"] == "legacy_sync_write":
            fails[o["burst_requests"]] = max(fails[o["burst_requests"]], o["live"]["error_rate"])
    worst = max(fails.items(), key=lambda kv: kv[1])
    if worst[1] > 0:
        ax.annotate(f"baseline also failed up to {worst[1]*100:.0f}% of requests", (worst[0], 1400),
                    xytext=(-8, 0), textcoords="offset points", ha="right", va="center", color=MUTED, fontsize=8.5)
    ax.legend(frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.2), ncol=2, fontsize=9, labelcolor=INK)
    fig.text(0.01, 0.01, "One Apple M3 Pro laptop (generational ZGC run), load generator on the same machine. Real ad-break contexts from iPinYou 2013-06-11.",
             color=MUTED, fontsize=7.5)
    fig.tight_layout(rect=(0, 0.04, 1, 1))
    fig.savefig("docs/img/burst_p99.svg")

def pacing():
    p = "results/exp2_pacing_curves.json"
    if not os.path.exists(p): return
    c = json.load(open(p))
    fig, ax = plt.subplots(figsize=(7, 3.8))
    h = c["hour"]
    ax.plot(h, c["plan"], color=GRAY, linewidth=2, linestyle=(0, (4, 3)), label="plan (the day's eligible traffic)")
    for key, color, name in (("unpaced", ORANGE, "unpaced"), ("throttle", AQUA, "probabilistic throttling"), ("smart", BLUE, "smart pacing")):
        ax.plot(h, c[key], color=color, linewidth=2, label=name)
    ax.set_xlabel("hour of the replay day (UTC)")
    ax.set_ylabel("share of daily budget spent")
    ax.set_xlim(0, 24); ax.set_ylim(0, 1.05)
    style(ax, "Cumulative spend of all 55 campaigns against plan")
    ax.legend(frameon=False, loc="lower right", fontsize=9, labelcolor=INK)
    fig.text(0.01, 0.01, "Simulated 8-node fleet replaying 1,745,722 real ad breaks; budgets are each creative's real spend that day.",
             color=MUTED, fontsize=7.5)
    fig.tight_layout(rect=(0, 0.04, 1, 1))
    fig.savefig("docs/img/pacing.svg")

burst(); pacing()
print("charts written")

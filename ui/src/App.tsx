import { useCallback, useEffect, useMemo, useState } from "react";
import { CAMPAIGNS, CREATE, SET_ACTIVE, gql, type Campaign } from "./api";
import { CreateForm, type NewCampaign } from "./CreateForm";

// Money arrives in micro-units of the log's currency. Show it in whole units.
const money = (micros: number) => (micros / 1e6).toLocaleString(undefined, { maximumFractionDigits: 2 });
const pct = (x: number) => `${(x * 100).toFixed(1)}%`;

export function App() {
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [updated, setUpdated] = useState<Date | null>(null);

  const load = useCallback(async () => {
    try {
      const data = await gql<{ campaigns: Campaign[] }>(CAMPAIGNS);
      setCampaigns(data.campaigns);
      setUpdated(new Date());
      setError(null);
    } catch (e) {
      setError(String(e));
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 2000);
    return () => clearInterval(t);
  }, [load]);

  const shown = useMemo(
    () =>
      campaigns.filter(
        (c) => !filter || `${c.id} ${c.name} ${c.advertiser.name} ${c.category}`.toLowerCase().includes(filter.toLowerCase()),
      ),
    [campaigns, filter],
  );

  const totals = useMemo(() => {
    const budget = shown.reduce((s, c) => s + c.flight.dailyBudgetMicros, 0);
    const spend = shown.reduce((s, c) => s + c.delivery.spendMicros, 0);
    const exhausted = shown.filter((c) => c.delivery.budgetFraction >= 0.995).length;
    return { budget, spend, exhausted, active: shown.filter((c) => c.active).length };
  }, [shown]);

  async function create(n: NewCampaign) {
    await gql(CREATE, { input: n });
    await load();
  }

  async function toggle(c: Campaign) {
    await gql(SET_ACTIVE, { id: c.id, active: !c.active });
    await load();
  }

  return (
    <main>
      <header>
        <h1>AdServe delivery console</h1>
        <span className="muted">
          {updated ? `updated ${updated.toLocaleTimeString()}` : "loading"} · serving day {campaigns[0]?.delivery.day ?? "?"}
        </span>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="tiles">
        <Tile label="Active campaigns" value={`${totals.active} / ${shown.length}`} />
        <Tile label="Daily budget" value={money(totals.budget)} />
        <Tile label="Spent today" value={money(totals.spend)} />
        <Tile label="Delivered" value={totals.budget ? pct(totals.spend / totals.budget) : "0%"} />
        <Tile label="Out of budget" value={String(totals.exhausted)} />
      </section>

      <section>
        <div className="toolbar">
          <input placeholder="Filter by campaign, advertiser or category" value={filter} onChange={(e) => setFilter(e.target.value)} />
        </div>
        <table>
          <thead>
            <tr>
              <th>Campaign</th>
              <th>Advertiser</th>
              <th>Category</th>
              <th>Pacer</th>
              <th className="num">Budget</th>
              <th className="num">Spent</th>
              <th>Delivery</th>
              <th className="num">Pass-through</th>
              <th className="num">Cap/day</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {shown.map((c) => (
              <tr key={c.id} className={c.active ? "" : "inactive"}>
                <td title={c.id}>{c.name}</td>
                <td>{c.advertiser.name}</td>
                <td>{c.category}</td>
                <td>{c.pacer.toLowerCase()}</td>
                <td className="num">{money(c.flight.dailyBudgetMicros)}</td>
                <td className="num">{money(c.delivery.spendMicros)}</td>
                <td>
                  <Bar fraction={c.delivery.budgetFraction} />
                </td>
                <td className="num">{c.delivery.pacingRate == null ? "-" : pct(c.delivery.pacingRate)}</td>
                <td className="num">{c.cap.perDay || "-"}</td>
                <td>
                  <button onClick={() => toggle(c)}>{c.active ? "Pause" : "Resume"}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <CreateForm onCreate={create} />
      <footer className="muted">
        Campaign data comes from the GraphQL campaign API; spend is the serving node's view, confirmed spend comes from device beacons.
      </footer>
    </main>
  );
}

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <div className="tile">
      <div className="muted">{label}</div>
      <div className="value">{value}</div>
    </div>
  );
}

function Bar({ fraction }: { fraction: number }) {
  const w = Math.min(1, Math.max(0, fraction));
  return (
    <div className="bar" title={pct(fraction)}>
      <div className={fraction >= 0.995 ? "fill full" : "fill"} style={{ width: `${w * 100}%` }} />
      <span>{pct(fraction)}</span>
    </div>
  );
}

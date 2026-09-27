import { useState, type FormEvent } from "react";

export type NewCampaign = {
  id: string;
  advertiserId: string;
  advertiserName: string;
  name: string;
  category: string;
  cpcBidMicros: number;
  dailyBudgetMicros: number;
  startsAt: string;
  endsAt: string;
  pacer: string;
  capPerDay: number;
  capPerWeek: number;
  targeting: { geos: string[]; devices: string[] };
  creatives: { id: string; durationS: number; clickRate: number }[];
};

const today = new Date().toISOString().slice(0, 10);

export function CreateForm({ onCreate }: { onCreate: (c: NewCampaign) => Promise<void> }) {
  const [f, setF] = useState({
    id: "", advertiser: "", category: "retail", cpc: "0.5", budget: "100", start: "2013-06-11", end: today,
    pacer: "SMART", cap: "3", duration: "30", ctr: "0.001", geos: "", devices: "",
  });
  const [status, setStatus] = useState<string | null>(null);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    const id = f.id.trim();
    try {
      await onCreate({
        id,
        advertiserId: f.advertiser.trim().toLowerCase().replace(/\s+/g, "-"),
        advertiserName: f.advertiser.trim(),
        name: id,
        category: f.category,
        cpcBidMicros: Math.round(Number(f.cpc) * 1e6),
        dailyBudgetMicros: Math.round(Number(f.budget) * 1e6),
        startsAt: f.start,
        endsAt: f.end,
        pacer: f.pacer,
        capPerDay: Number(f.cap),
        capPerWeek: Number(f.cap) * 3,
        targeting: {
          geos: f.geos.split(",").map((s) => s.trim()).filter(Boolean),
          devices: f.devices.split(",").map((s) => s.trim().toUpperCase()).filter(Boolean),
        },
        creatives: [{ id: `${id}-${f.duration}s`, durationS: Number(f.duration), clickRate: Number(f.ctr) }],
      });
      setStatus(`Created ${id}. It is live on the next decision.`);
    } catch (err) {
      setStatus(String(err));
    }
  }

  return (
    <section className="create">
      <h2>New campaign</h2>
      <form onSubmit={submit}>
        <label>Id<input required value={f.id} onChange={set("id")} /></label>
        <label>Advertiser<input required value={f.advertiser} onChange={set("advertiser")} /></label>
        <label>Category<select value={f.category} onChange={set("category")}>
          {["retail", "auto", "software", "food", "telecom", "apparel"].map((c) => <option key={c}>{c}</option>)}
        </select></label>
        <label>CPC bid<input type="number" step="0.01" value={f.cpc} onChange={set("cpc")} /></label>
        <label>Daily budget<input type="number" value={f.budget} onChange={set("budget")} /></label>
        <label>Starts<input type="date" value={f.start} onChange={set("start")} /></label>
        <label>Ends<input type="date" value={f.end} onChange={set("end")} /></label>
        <label>Pacer<select value={f.pacer} onChange={set("pacer")}>
          {["SMART", "THROTTLE", "PID", "UNPACED"].map((p) => <option key={p}>{p}</option>)}
        </select></label>
        <label>Cap per day<input type="number" value={f.cap} onChange={set("cap")} /></label>
        <label>Spot length<select value={f.duration} onChange={set("duration")}>
          {["15", "30", "60"].map((d) => <option key={d}>{d}</option>)}
        </select></label>
        <label>Click rate<input type="number" step="0.0001" value={f.ctr} onChange={set("ctr")} /></label>
        <label>Geos (comma)<input value={f.geos} onChange={set("geos")} placeholder="any" /></label>
        <label>Devices (comma)<input value={f.devices} onChange={set("devices")} placeholder="tv, mobile, web" /></label>
        <button type="submit">Create</button>
      </form>
      {status && <p className="muted">{status}</p>}
    </section>
  );
}

// A tiny GraphQL client for the campaign management API (/graphql, DGS).

export type Campaign = {
  id: string;
  name: string;
  category: string;
  pacer: string;
  active: boolean;
  cpcBidMicros: number;
  advertiser: { id: string; name: string };
  cap: { perDay: number; perWeek: number };
  flight: { startsAt: string; endsAt: string; dailyBudgetMicros: number };
  creatives: { id: string; durationS: number; clickRate: number; valueMicros: number }[];
  delivery: { day: string; spendMicros: number; confirmedSpendMicros: number; budgetFraction: number; pacingRate: number | null };
};

export async function gql<T>(query: string, variables?: Record<string, unknown>): Promise<T> {
  const res = await fetch("/graphql", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ query, variables }),
  });
  const body = await res.json();
  if (body.errors?.length) throw new Error(body.errors.map((e: { message: string }) => e.message).join("; "));
  return body.data as T;
}

export const CAMPAIGNS = `{
  campaigns {
    id name category pacer active cpcBidMicros
    advertiser { id name }
    cap { perDay perWeek }
    flight { startsAt endsAt dailyBudgetMicros }
    creatives { id durationS clickRate valueMicros }
    delivery { day spendMicros confirmedSpendMicros budgetFraction pacingRate }
  }
}`;

export const CREATE = `mutation Create($input: CampaignInput!) { createCampaign(input: $input) { id } }`;
export const SET_ACTIVE = `mutation SetActive($id: ID!, $active: Boolean!) { setCampaignActive(id: $id, active: $active) { id active } }`;

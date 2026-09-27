-- AdServe campaign store. Read by the serving nodes into an in-process snapshot every few
-- seconds; never read or written on the decision path.

create table if not exists advertisers (
    id          text primary key,
    name        text not null
);

create table if not exists campaigns (
    id                  text primary key,
    advertiser_id       text not null references advertisers (id),
    name                text not null,
    category            text not null,
    cpc_bid_micros      bigint not null check (cpc_bid_micros >= 0),
    pacer               text not null default 'THROTTLE',
    cap_per_day         int not null default 0 check (cap_per_day >= 0),
    cap_per_week        int not null default 0 check (cap_per_week >= 0),
    targeting           jsonb not null default '{}'::jsonb,
    active              boolean not null default true,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

-- A flight is the window a campaign runs in and its daily budget inside that window.
create table if not exists flights (
    campaign_id         text primary key references campaigns (id) on delete cascade,
    starts_at           timestamptz not null,
    ends_at             timestamptz not null,
    daily_budget_micros bigint not null check (daily_budget_micros >= 0),
    check (ends_at > starts_at)
);

create table if not exists creatives (
    id              text primary key,
    campaign_id     text not null references campaigns (id) on delete cascade,
    duration_s      int not null check (duration_s in (15, 30, 60)),
    click_rate      double precision not null check (click_rate >= 0 and click_rate <= 1)
);
create index if not exists creatives_campaign on creatives (campaign_id);

-- Only written in --legacy-sync-write mode, the baseline for the hot-path experiment: every
-- decision is inserted here before the response goes back.
create table if not exists decisions_sync (
    request_id      text primary key,
    viewer_id       text not null,
    decided_at      timestamptz not null,
    pod_size        int not null,
    pod_value_micros bigint not null,
    record          bytea not null
);

-- Written by the billing job: one row per deduplicated impression event.
create table if not exists billing_events (
    event_id        text primary key,
    impression_id   text not null,
    campaign_id     text not null,
    creative_id     text not null,
    viewer_id       text not null,
    price_micros    bigint not null,
    decided_ts_ms   bigint not null,
    client_ts_ms    bigint not null,
    serving_region  text not null,
    arrival_region  text not null,
    rerouted        boolean not null,
    pipeline        text not null,
    billed_at       timestamptz not null default now()
);
create index if not exists billing_campaign on billing_events (campaign_id);

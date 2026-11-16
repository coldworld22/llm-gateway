create table tenants (
    id                   uuid primary key,
    name                 varchar(100) not null unique,
    monthly_token_budget bigint       not null check (monthly_token_budget > 0),
    requests_per_minute  int          not null check (requests_per_minute > 0),
    created_at           timestamptz  not null default now()
);

-- Only the SHA-256 of a key is stored. A tenant can hold several keys so they can be rotated
-- without downtime: issue the new one, move clients over, then revoke the old one.
create table api_keys (
    id         uuid primary key,
    tenant_id  uuid        not null references tenants (id),
    key_hash   char(64)    not null unique,
    label      varchar(100),
    created_at timestamptz not null default now(),
    revoked_at timestamptz
);

create table usage_records (
    id                uuid primary key,
    tenant_id         uuid          not null references tenants (id),
    route             varchar(100)  not null,
    provider          varchar(50)   not null,
    model             varchar(100)  not null,
    prompt_tokens     int           not null,
    completion_tokens int           not null,
    cost_usd          numeric(12,6) not null,
    cache_hit         boolean       not null,
    pii_redacted      int           not null,
    latency_ms        bigint        not null,
    created_at        timestamptz   not null default now()
);

-- Budget checks and the usage report both read "this tenant since the start of the month".
create index usage_records_tenant_created_idx on usage_records (tenant_id, created_at);

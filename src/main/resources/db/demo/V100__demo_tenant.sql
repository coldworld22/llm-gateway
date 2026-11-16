-- Local demo only (loaded by the "demo" profile). The key is "local-demo-key".
insert into tenants (id, name, monthly_token_budget, requests_per_minute)
values ('00000000-0000-0000-0000-000000000001', 'demo-team', 1000000, 60);

insert into api_keys (id, tenant_id, key_hash, label)
values ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001',
        '3a144c6d8b88728d4ac74c90e77c87f526b4c4df5c5cfbc51595b90070d4975b', 'demo');

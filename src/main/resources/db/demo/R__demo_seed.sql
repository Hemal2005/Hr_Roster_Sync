-- Demo data, loaded only under the demo profile. Repeatable: Flyway re-runs it when this
-- file changes, after all versioned migrations, so it must be safe to run again.
-- Employees are not seeded here: they come in through the API, like real HR data.

INSERT INTO tenant (id, name) VALUES
    ('acme',   'Acme Corp (demo)'),
    ('globex', 'Globex (demo)')
ON CONFLICT (id) DO NOTHING;

INSERT INTO office (tenant_id, code, active) VALUES
    ('acme',   'BLR-01', true),
    ('acme',   'HYD-02', true),
    ('acme',   'PUN-03', false),
    ('globex', 'BLR-01', true),
    ('globex', 'CHN-01', true)
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO trip (tenant_id, id, office_code, pickup_time, capacity) VALUES
    ('acme',   'T-ACME-1',   'BLR-01', now() + interval '1 day',  4),
    ('acme',   'T-ACME-2',   'HYD-02', now() + interval '2 days', 4),
    ('acme',   'T-ACME-OLD', 'BLR-01', now() - interval '1 day',  4),
    ('globex', 'T-GLOBEX-1', 'CHN-01', now() + interval '1 day',  6)
ON CONFLICT (tenant_id, id) DO NOTHING;

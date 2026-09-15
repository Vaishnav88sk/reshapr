-- Flyway migration V1.7.0
-- Add per-ConfigurationPlan header propagation policy (allow/deny + rewrite).
-- Stores the policy as a JSONB column. NULL means "use proxy defaults"
-- (baseline stripping plus the default deny-list: Authorization, Cookie).

ALTER TABLE configuration_plans
    ADD COLUMN IF NOT EXISTS header_policy JSONB;

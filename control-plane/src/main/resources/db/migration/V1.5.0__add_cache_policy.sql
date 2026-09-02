-- Flyway migration V1.5.0
-- Issue #323: Add per-ConfigurationPlan caching configuration
-- Stores MCP client-cache hints (ttlMs, cacheScope) as a JSONB column.
-- NULL means "use proxy defaults" (30 000 ms, "public").

ALTER TABLE configuration_plans
    ADD COLUMN IF NOT EXISTS cache_policy JSONB;

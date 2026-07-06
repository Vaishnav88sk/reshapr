-- Enable multiple artifacts of the same type per Service and per-plan artifact selection,
-- plus an organization-unique (optional) name for expositions.
--
-- 1. configuration_plans.included_artifacts: names of the attached artifacts selected for the plan.
--    Names are stored (not ids) for consistency with included_operations/excluded_operations and the
--    public API which references artifacts by name. An empty JSON array means "all attached artifacts
--    of the service apply" (backward compatible). The service main artifact is never impacted.
alter table if exists configuration_plans
    add column if not exists included_artifacts JSONB default '[]'::jsonb;

-- 2. expositions.name: optional, human-friendly, organization-unique identifier for an exposition.
--    No backfill for existing expositions: the name stays NULL and the name-based MCP endpoint
--    (/mcp/{org}/{exposition_name}) is simply not advertised for them. A slug is only proposed at
--    creation time (by the CLI or Web UI) when the user does not provide an explicit name.
alter table if exists expositions
    add column if not exists name varchar(255);

-- 3. Enforce organization-unique names, ignoring rows where name is not set (kept nullable to avoid
--    a breaking change). A partial unique index is used so multiple NULLs remain allowed.
create unique index if not exists ux_expositions_org_name
    on expositions (organization_id, name)
    where name is not null;

-- 4. Recreate the active_expositions view to expose the exposition name as exposition_name.
drop view if exists active_expositions;

create or replace view active_expositions as
   select
      e.id,
      e.organization_id,
      e.created_on,
      e.name as exposition_name,
      s.id as service_id,
      s.name as service_name,
      s.version as service_version,
      s.type as service_type,
      c.id as config_id,
      c.backend_endpoint as config_backend_endpoint,
      json_agg(JSON_BUILD_OBJECT('id', g.id, 'name', g.name, 'fqdns', g.fqdns)) as gateways
   from expositions e
      left join services s
         on e.service_id = s.id
      inner join configuration_plans c
         on e.configuration_plan_id = c.id
      left join gateway_groups gg
         on e.gateway_group_id = gg.id
      inner join gateways_gateway_groups ggg
         on gg.id = ggg.gateway_group_id
      left join gateways g
         on ggg.gateway_id = g.id
   group by e.id, s.id, c.id;

-- 5. Enforce unique artifact names within a service, required so that per-plan artifact selection
--    (configuration_plans.included_artifacts, stored as names) is deterministic. service_id is a globally
--    unique TSID, so (service_id, name) alone guarantees uniqueness (organization_id would be redundant
--    and adds no query selectivity, since all artifacts of a service belong to one organization). This
--    index also serves the service-scoped listing ordered by name. First de-duplicate any existing
--    colliding names by appending a numeric suffix (rename, never delete), then add a partial unique
--    index (service_id is nullable for organization-level artifacts, which are left unconstrained).
with numbered as (
    select
        id,
        row_number() over (
            partition by service_id, name
            order by id
        ) as rn
    from artifacts
    where service_id is not null
)
update artifacts a
set name = a.name || '-' || n.rn
from numbered n
where a.id = n.id
  and n.rn > 1;

create unique index if not exists ux_artifacts_service_name
    on artifacts (service_id, name)
    where service_id is not null;






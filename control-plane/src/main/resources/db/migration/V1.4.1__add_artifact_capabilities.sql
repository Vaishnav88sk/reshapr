-- Add a 'capabilities' column to artifacts holding the names of the elements declared by a custom
-- artifact (Prompts, Resources, CustomTools, ToolsOutputFilters): prompt names, resource/
-- resourceTemplate uris, custom tool names or filtered tool names. This provides a synthetic view of
-- what each attached artifact brings, to help compose a ConfigurationPlan (see included_artifacts).
--
alter table if exists artifacts
    add column if not exists capabilities JSONB default '[]'::jsonb;


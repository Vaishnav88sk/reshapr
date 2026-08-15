-- Add header filtering columns to configuration_plans table
alter table if exists configuration_plans
    add column allowed_request_headers JSONB,
    add column denied_request_headers JSONB,
    add column allowed_response_headers JSONB,
    add column denied_response_headers JSONB;

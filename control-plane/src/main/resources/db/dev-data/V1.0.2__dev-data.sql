insert into users (id, username, email, firstname, lastname, password, status) values ('1', 'admin', 'admin@reshapr.io', 'Reshapr', 'Admin', '$2a$10$Uc.SZ0hvGJQlYdsAp7be1.lFjmOnc7aAr4L0YY3/VN3oK.F8zJHRG', 'REGISTERED');
insert into users (id, username, email, firstname, lastname, password, status) values ('2', 'acme-admin', 'admin@acme.org', 'Acme', 'Admin', '$2a$10$Uc.SZ0hvGJQlYdsAp7be1.lFjmOnc7aAr4L0YY3/VN3oK.F8zJHRG', 'REGISTERED');
insert into users (id, username, email, firstname, lastname, password, status) values ('3', 'tyrell-admin', 'admin@tyrell.co', 'Tyrell', 'Admin', '$2a$10$Uc.SZ0hvGJQlYdsAp7be1.lFjmOnc7aAr4L0YY3/VN3oK.F8zJHRG', 'REGISTERED');

insert into organizations (id, name, description, icon, owner_id) values ('2', 'acme', 'Acme Organization', 'nope', '2');
insert into organizations (id, name, description, icon, owner_id) values ('3', 'tyrell', 'Tyrell Corporation', 'nope', '3');

update users set default_organization_id = '1' where id = '1';
update users set default_organization_id = '2' where id = '2';
update users set default_organization_id = '3' where id = '3';

insert into users_organizations (members_id, organizations_id) values ('2', '2');
insert into users_organizations (members_id, organizations_id) values ('3', '3');

insert into api_tokens (id, name, token, valid_until, user_id, organization_id) values ('1', 'Dev token', 'my-super-secret-token', '2026-12-24 23:59:59', '1', 'reshapr');
insert into api_tokens (id, name, token, valid_until, user_id, organization_id) values ('2', 'Acme token', 'my-super-secret-token', '2026-12-24 23:59:59', '2', 'acme');

insert into gateway_groups (id, name, organization_id, labels) values ('2', 'Dev Gateway Group for acme', 'acme', '{"env": "dev"}');

insert into quotas (id, organization_id, metric, enabled, m_limit, remaining) values ('11', 'acme', 'gateway-group.count', 'true', 3, 2);
insert into quotas (id, organization_id, metric, enabled, m_limit, remaining) values ('12', 'acme', 'exposition.count', 'true', 5, 5);
insert into quotas (id, organization_id, metric, enabled, m_limit, remaining) values ('13', 'acme', 'gateway.count', 'true', 1, 1);
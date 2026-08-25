# reShapr Admin CLI

The `reshapr admin` commands manage control-plane resources without requiring a
normal user login.

## Authentication

Provide the deployment admin API key through an environment variable:

```shell
export RESHAPR_ADMIN_API_KEY='<admin-api-key>'
```

Alternatively, pass `--admin-api-key <key>` after `reshapr admin`. The command
line option takes precedence over the environment variable.

Admin commands use the server saved by `reshapr login`. Override it when needed:

```shell
reshapr admin --server http://localhost:5555 <command>
```

## Organizations

Create new organization:

```shell
reshapr admin -organization create alpha \ 
  --description 'New alpha organization'
```

Delete an organization and cascade the removal of everything it owns  (services, artifacts, configuration plans, 
expositions, secrets, gateways, gateway groups, quotas, API tokens, shared resources and user memberships).
Gateways currently serving the organization expositions are notified so they stop routing traffic to them.

```shell
reshapr admin organization delete alpha
```

Add `-f, --force` to skip the interactive confirmation prompt (useful in
scripts or CI):

```shell
reshapr admin organization delete alpha --force
```

The built-in `reshapr` root organization is protected and cannot be deleted.

## Users

Create a user:

```shell
reshapr admin user create jdoe --email jdoe@example.com --password s3cret
```

Delete a user and cascade the removal of its organization memberships. Organizations previously owned by 
this user remain but become unowned — assign a new owner afterwards.

```shell
reshapr admin user delete jdoe
```

Add `-f, --force` to skip the interactive confirmation prompt:

```shell
reshapr admin user delete jdoe --force
```

The user owning the `reshapr` root organization is protected and cannot be
deleted.

## Memberships

Replace all organization memberships assigned to a user:

```shell
reshapr admin membership set laurent \
  --organizations '["acme", "tyrell", "lbroudoux"]'
```

The `--organizations` value must be a JSON array. This command replaces the
user's existing membership list.

## Organization quotas

Assign quota limits to an organization:

```shell
reshapr admin quota assign lbroudoux --quotas '[
  {"metric": "gateway-group.count", "enabled": true, "limit": 3},
  {"metric": "gateway.count", "enabled": true, "limit": 3},
  {"metric": "exposition.count", "enabled": true, "limit": 10}
]'
```

Each quota requires a metric name, an enabled flag, and a non-negative integer
limit.

## Service accounts

Declare a Kubernetes service account valid for all organizations:

```shell
reshapr admin service-account create reshapr-system-operator \
  --k8s-subject reshapr-system:reshapr-operator \
  --allowed-organizations '["*"]' \
  --validity-days 90
```

Replace `["*"]` with a JSON array of organization names to restrict access.

## Help and structured output

Use `--help` at any command level to discover available operations and options:

```shell
reshapr admin --help
reshapr admin quota assign --help
reshapr admin service-account create --help
```

Commands returning data support `--output json` and `--output yaml`.

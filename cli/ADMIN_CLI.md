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

Create a Kubernetes service account valid for all organizations:

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

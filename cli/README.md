# reshapr-cli

This is the command line interface for the Reshapr project.

## Installing the CLI

```shell
npm install -g @reshapr/reshapr-cli
```

## Running the CLI in dev mode

After cloning the repository, you can run the CLI in development mode using the following commands.

1. First, ensure you have the required dependencies installed. You can do this by running:

```shell
npm install
```

2. Next, you have to start the CLI in development mode using:

```shell    
npm run dev
```

This will keep the CLI running and watch for changes in the source code, allowing you to develop
and test your changes in real-time.

3. Finally, you have to link the `reshapr` binary to your JavaScript entrypoint for the CLI:

```shell
npm link
```

### Executing some commands

```shell
# Login to the reShapr local control-plane server
reshapr login --server http://localhost:5555

# Logout once your job is done.
reshapr logout 
```

## Admin operations

The `admin` command exposes control-plane administration without requiring a
normal user login. Supply the deployment's admin API key with the
`RESHAPR_ADMIN_API_KEY` environment variable:

```shell
export RESHAPR_ADMIN_API_KEY='<admin-api-key>'
reshapr admin --server http://localhost:5555 user create jdoe \
  --email jdoe@example.com --password '<password>' \
  --firstname John --lastname Doe
```

You can instead pass `--admin-api-key <key>` after `admin`; this takes
precedence over the environment variable. Avoid placing the key directly on
the command line in shared environments because it may be retained in shell
history or process listings.

The `--server <url>` admin option takes precedence over the server saved by
`reshapr login`. A server must be available from one of those sources.

### Available operations

```shell
# Create an organization, optionally owned by an existing user
reshapr admin organization create acme \
  --description 'Acme organization' --owner jdoe

# Assign organization quotas
reshapr admin quota assign acme --quotas \
  '[{"metric":"gateway-group.count","enabled":true,"limit":3},{"metric":"gateway.count","enabled":true,"limit":3}]'

# Replace all memberships for a user
reshapr admin membership set jdoe --organizations '["acme","shared"]'

# Create a Kubernetes service account valid for every organization
reshapr admin service-account create reshapr-system-operator \
  --k8s-subject 'reshapr-system:reshapr-operator' \
  --allowed-organizations '["*"]' --validity-days 90
```

The `--quotas`, `--organizations`, and `--allowed-organizations` values are
JSON arrays. `membership set` replaces the user's complete organization
membership list. Commands returning a resource also support
`--output json` and `--output yaml`.

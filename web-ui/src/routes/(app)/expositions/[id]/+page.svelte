<!--
  ~ Copyright The Reshapr Authors.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~ http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { apiClient, ApiError } from '$lib/api/client.js';
	import { parseArtifactRefList, type ArtifactRef, type ArtifactType } from '$lib/artifacts/index.js';
	import { avatarColor, avatarInitials } from '$lib/avatarColor.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import OrganizationBadge from '$lib/components/OrganizationBadge.svelte';
	import ServiceTypeBadge from '$lib/components/ServiceTypeBadge.svelte';
	import { auth } from '$lib/stores/auth.svelte.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { Badge } from '$lib/components/ui/badge/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { cn } from '$lib/utils.js';
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import {
		ApiGatewayIcon,
		ArrowRight01Icon,
		BubbleChatIcon,
		CheckmarkBadge01Icon,
		CloudServerIcon,
		Configuration01Icon,
		Copy01Icon,
		Delete02Icon,
		File01Icon,
		FilterIcon,
		GlobalIcon,
		Key01Icon,
		Link01Icon,
		McpServerIcon,
		Route01Icon,
		SecurityCheckIcon,
		ShieldEnergyIcon,
		SquareLock01Icon,
		TagsIcon,
		Tick02Icon,
		Timer01Icon,
		UserShield01Icon,
		Wrench01Icon
	} from '@hugeicons/core-free-icons';

	const id = $derived(page.params.id);

	// ── Raw API state ─────────────────────────────────────────
	let expo = $state<Record<string, unknown> | null>(null);
	let active = $state<Record<string, unknown> | null>(null);
	let artifacts = $state<ArtifactRef[]>([]);
	let backendSecret = $state<{ name: string; type: string } | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(true);

	// ── Small parsing helpers ─────────────────────────────────
	function rec(v: unknown): Record<string, unknown> | null {
		return v && typeof v === 'object' ? (v as Record<string, unknown>) : null;
	}
	function str(v: unknown): string | null {
		return typeof v === 'string' && v.trim() !== '' ? v.trim() : null;
	}
	function arr(v: unknown): unknown[] {
		return Array.isArray(v) ? v : [];
	}

	// ── Derived, typed views over the raw payloads ────────────
	const svc = $derived(rec(expo?.service));
	const plan = $derived(rec(expo?.configurationPlan));
	const gg = $derived(rec(expo?.gatewayGroup));

	const expoName = $derived(str(expo?.name));
	const isActive = $derived(active != null);

	const serviceName = $derived(str(svc?.name) ?? '');
	const serviceVersion = $derived(str(svc?.version));
	const serviceType = $derived(str(svc?.type));
	const serviceId = $derived(str(svc?.id));

	const heroTitle = $derived(expoName ?? serviceName ?? 'MCP Server');
	const heroSubtitle = $derived(
		serviceName ? `${serviceName}${serviceVersion ? ` : ${serviceVersion}` : ''}` : null
	);


	// ── Operations ────────────────────────────────────────────
	const HTTP_VERBS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']);
	const METHOD_STYLES: Record<string, string> = {
		GET: 'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400',
		POST: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400',
		PUT: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400',
		PATCH: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400',
		DELETE: 'bg-rose-500/10 text-rose-600 ring-rose-500/20 dark:text-rose-400'
	};
	function methodStyle(label: string | null): string {
		const key = (label ?? '').toUpperCase();
		return METHOD_STYLES[key] ?? 'bg-muted text-muted-foreground ring-border';
	}
	function splitOp(op: string): { method: string | null; rest: string } {
		const m = op.trim().match(/^(\S+)\s+(.+)$/);
		if (m && HTTP_VERBS.has(m[1].toUpperCase())) return { method: m[1].toUpperCase(), rest: m[2] };
		return { method: null, rest: op.trim() };
	}

	const opsMode = $derived.by<'include' | 'exclude' | 'all'>(() => {
		const inc = arr(plan?.includedOperations);
		const exc = arr(plan?.excludedOperations);
		if (inc.length) return 'include';
		if (exc.length) return 'exclude';
		return 'all';
	});
	const operations = $derived.by<string[]>(() =>
		(opsMode === 'exclude' ? arr(plan?.excludedOperations) : arr(plan?.includedOperations)).map(
			String
		)
	);

	// ── Capabilities (from custom artifacts included by the plan) ──
	type CapabilityGroup = {
		type: ArtifactType;
		label: string;
		items: { name: string; artifactName: string }[];
	};
	const CUSTOM_TYPES: { type: ArtifactType; label: string }[] = [
		{ type: 'RESHAPR_CUSTOM_TOOLS', label: 'Tools' },
		{ type: 'RESHAPR_PROMPTS', label: 'Prompts' },
		{ type: 'RESHAPR_RESOURCES', label: 'Resources' },
		{ type: 'RESHAPR_TOOLS_OUTPUT_FILTERS', label: 'Output filters' }
	];
	const CAPABILITY_ICONS: Record<string, typeof Wrench01Icon> = {
		RESHAPR_CUSTOM_TOOLS: Wrench01Icon,
		RESHAPR_PROMPTS: BubbleChatIcon,
		RESHAPR_RESOURCES: File01Icon,
		RESHAPR_TOOLS_OUTPUT_FILTERS: FilterIcon
	};
	const CAPABILITY_STYLES: Record<string, string> = {
		RESHAPR_CUSTOM_TOOLS: 'bg-blue-500/10 text-blue-600 ring-blue-500/20 dark:text-blue-400',
		RESHAPR_PROMPTS: 'bg-violet-500/10 text-violet-600 ring-violet-500/20 dark:text-violet-400',
		RESHAPR_RESOURCES: 'bg-emerald-500/10 text-emerald-600 ring-emerald-500/20 dark:text-emerald-400',
		RESHAPR_TOOLS_OUTPUT_FILTERS: 'bg-amber-500/10 text-amber-600 ring-amber-500/20 dark:text-amber-400'
	};

	const capabilityGroups = $derived.by<CapabilityGroup[]>(() => {
		const included = arr(plan?.includedArtifacts).map(String);
		const eligible = artifacts.filter(
			(a) => !a.mainArtifact && (included.length === 0 || included.includes(a.name))
		);
		return CUSTOM_TYPES.map(({ type, label }) => {
			const items = eligible
				.filter((a) => a.type === type)
				.flatMap((a) => a.capabilities.map((name) => ({ name, artifactName: a.name })));
			return { type, label, items };
		}).filter((g) => g.items.length > 0);
	});
	const totalCapabilities = $derived(capabilityGroups.reduce((n, g) => n + g.items.length, 0));

	// ── Backend ───────────────────────────────────────────────
	const backendEndpoint = $derived(str(plan?.backendEndpoint));
	const backendTimeout = $derived.by<string | null>(() => {
		const t = plan?.backendTimeout;
		if (t == null || t === '') return null;
		const n = Number(t);
		return Number.isNaN(n) ? String(t) : `${n} ms`;
	});
	const auditEnabled = $derived(plan?.audit === true);

	// ── MCP endpoint access (authentication of the MCP server) ──
	const mcpAuth = $derived.by(() => {
		const oauth = rec(plan?.oauth2Configuration);
		if (oauth) {
			return {
				kind: 'oauth' as const,
				servers: arr(oauth.authorizationServers).map(String),
				scopes: arr(oauth.scopes).map(String),
				jwksUri: str(oauth.jwksUri)
			};
		}
		if (str(plan?.apiKey)) return { kind: 'apikey' as const };
		return { kind: 'none' as const };
	});

	// ── Gateways / endpoint URLs (only available when active) ──
	const gateways = $derived.by<{ id: string; name: string | null; fqdns: string[] }[]>(() =>
		arr(active?.gateways)
			.map((g) => {
				const o = rec(g);
				if (!o) return null;
				return {
					id: str(o.id) ?? '',
					name: str(o.name),
					fqdns: arr(o.fqdns)
						.map(String)
						.filter((f) => f.length > 0)
				};
			})
			.filter((g): g is { id: string; name: string | null; fqdns: string[] } => g != null)
	);

	function endpointBase(fqdn: string): string {
		if (/^https?:\/\//i.test(fqdn)) return fqdn.replace(/\/+$/, '');
		const host = fqdn.split(/[:/]/, 1)[0].toLowerCase();
		const scheme = host === 'localhost' || host === '127.0.0.1' ? 'http' : 'https';
		return `${scheme}://${fqdn}`;
	}
	const encSeg = (s: string) => s.replace(/\s/g, '+');

	const endpointUrls = $derived.by<string[]>(() => {
		const idStr = str(expo?.id) ?? '';
		const org = str(expo?.organizationId) ?? '';
		const name = expoName ?? '';
		if (!idStr) return [];
		const fqdns: string[] = [];
		for (const g of gateways) for (const f of g.fqdns) if (!fqdns.includes(f)) fqdns.push(f);
		const urls: string[] = [];
		for (const fqdn of fqdns) {
			const base = endpointBase(fqdn);
			if (name && org) urls.push(`${base}/mcp/${org}/${encSeg(name)}`);
			urls.push(`${base}/mcp/${encSeg(idStr)}`);
		}
		return urls;
	});

	// ── Gateway group labels ──────────────────────────────────
	const ggLabels = $derived.by<[string, string][]>(() => {
		const l = rec(gg?.labels);
		return l ? Object.entries(l).map(([k, v]) => [k, String(v)] as [string, string]) : [];
	});

	// ── Load ──────────────────────────────────────────────────
	async function loadArtifacts(sid: string) {
		try {
			artifacts = parseArtifactRefList(await apiClient().listArtifactRefsByService(sid));
		} catch {
			artifacts = [];
		}
	}

	async function loadBackendSecret(secretId: string) {
		try {
			const refs = await apiClient().listSecretRefs();
			const match = (Array.isArray(refs) ? refs : [])
				.map(rec)
				.find((s) => s && str(s.id) === secretId);
			backendSecret = match
				? { name: str(match.name) ?? secretId, type: str(match.type) ?? '' }
				: { name: secretId, type: '' };
		} catch {
			backendSecret = { name: secretId, type: '' };
		}
	}

	async function load(expoId: string) {
		loading = true;
		error = null;
		artifacts = [];
		backendSecret = null;
		try {
			const c = apiClient();
			const detail = rec(await c.getExposition(expoId));
			expo = detail;
			active = rec(await c.getActiveExpositionOrNull(expoId));

			const p = rec(detail?.configurationPlan);
			const sid = str(rec(detail?.service)?.id);
			const secretId = str(p?.backendSecretId);
			await Promise.all([
				sid ? loadArtifacts(sid) : Promise.resolve(),
				secretId ? loadBackendSecret(secretId) : Promise.resolve()
			]);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		const expoId = id;
		if (!expoId) return;
		void load(expoId);
	});

	// ── Copy endpoint URL ─────────────────────────────────────
	let copiedUrl = $state<string | null>(null);
	let copiedTimer: ReturnType<typeof setTimeout> | undefined;
	async function copyUrl(url: string) {
		try {
			await navigator.clipboard.writeText(url);
			copiedUrl = url;
			clearTimeout(copiedTimer);
			copiedTimer = setTimeout(() => (copiedUrl = null), 1500);
		} catch {
			// Clipboard may be unavailable (insecure context); ignore.
		}
	}

	async function onDelete() {
		if (!id || !confirm('Delete this MCP server?')) return;
		try {
			await apiClient().deleteExposition(id);
			goto('/expositions');
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}
</script>

<svelte:head>
	<title>{heroTitle} — MCP Server — reShapr</title>
</svelte:head>

<p class="mb-4">
	<a href="/expositions" class="text-primary text-sm hover:underline">← MCP Servers</a>
</p>

{#if error}
	<div class="mb-4"><ApiErrorAlert message={error} /></div>
{/if}

<!-- ═══════════════════════════════════════════════════════════ -->
<!-- Hero                                                         -->
<!-- ═══════════════════════════════════════════════════════════ -->
<div class="bg-card mb-6 flex flex-wrap items-start justify-between gap-4 rounded-xl border p-6">
	<div class="flex min-w-0 items-start gap-4">
		<span
			class="flex size-14 shrink-0 items-center justify-center rounded-xl text-lg font-semibold text-white"
			style="background-color: {avatarColor(serviceName || heroTitle)};"
			aria-hidden="true"
		>
			{avatarInitials(serviceName || heroTitle)}
		</span>
		<div class="min-w-0">
			<div class="flex flex-wrap items-center gap-2">
				<h1 class="text-2xl font-bold tracking-tight break-all">{heroTitle}</h1>
				<span
					class={cn(
						'inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
						isActive
							? 'bg-primary/10 text-primary ring-primary/20'
							: 'bg-muted text-muted-foreground ring-border'
					)}
				>
					<span
						class={cn(
							'size-1.5 rounded-full',
							isActive ? 'bg-primary' : 'bg-muted-foreground/50'
						)}
					></span>
					{isActive ? 'Active' : 'Inactive'}
				</span>
			</div>
			{#if heroSubtitle}
				<div class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
					<span class="text-muted-foreground">Backed by</span>
					<span class="text-foreground font-medium break-all">{serviceName || '—'}</span>
					{#if serviceVersion}
						<span class="text-muted-foreground">version {serviceVersion}</span>
					{/if}
					{#if serviceId}
						<a
							href="/services/{serviceId}"
							class="text-primary text-xs inline-flex items-center gap-1 hover:underline"
						>
							Open service <HugeiconsIcon icon={ArrowRight01Icon} size={14} />
						</a>
					{/if}
				</div>
			{/if}
			<div class="mt-2 flex flex-wrap items-center gap-2">
				<code class="text-muted-foreground bg-muted rounded px-1.5 py-0.5 font-mono text-xs break-all">
					{id}
				</code>
				{#if serviceType}
					<ServiceTypeBadge type={serviceType} />
				{/if}
				{#if auth.isAdmin && str(expo?.organizationId)}
					<OrganizationBadge organizationName={str(expo?.organizationId) ?? ''} />
				{/if}
			</div>
		</div>
	</div>
	<div class="flex shrink-0 items-center gap-2">
		<Button variant="outline" onclick={() => id && void load(id)}>Refresh</Button>
		<Button variant="destructive" onclick={() => void onDelete()}>
			<HugeiconsIcon icon={Delete02Icon} size={16} />
			Delete
		</Button>
	</div>
</div>

{#if loading}
	<div class="grid gap-6 lg:grid-cols-2">
		{#each Array.from({ length: 4 }, (_, i) => i) as i (i)}
			<div class="bg-muted/40 h-64 animate-pulse rounded-xl border"></div>
		{/each}
	</div>
{:else if expo}
	<div class="grid items-start gap-6 lg:grid-cols-2">
		<!-- Left column: configuration plan + backend -->
		<div class="space-y-6">
			<!-- ─── Configuration Plan (operations + capabilities) ── -->
		<Card.Root>
			<Card.Header>
				<div class="flex items-center gap-2">
					<HugeiconsIcon icon={Configuration01Icon} size={18} class="text-muted-foreground" />
					<Card.Title class="text-base">Configuration plan</Card.Title>
				</div>
				<Card.Description>
					{#if str(plan?.name)}
						<span class="text-foreground font-medium">{str(plan?.name)}</span>
						{#if str(plan?.description)}<span> — {str(plan?.description)}</span>{/if}
					{:else}
						Operations and capabilities exposed to the LLM.
					{/if}
				</Card.Description>
			</Card.Header>
			<Card.Content class="space-y-4 text-sm">
				<!-- Operations -->
				<div>
					<div class="mb-2 flex items-center gap-2">
						<HugeiconsIcon icon={Route01Icon} size={15} class="text-muted-foreground" />
						<span class="font-medium">Operations</span>
						{#if opsMode === 'exclude'}
							<Badge variant="outline" class="text-[10px] uppercase">excluded</Badge>
						{:else if opsMode === 'include'}
							<span class="text-muted-foreground text-xs">{operations.length}</span>
						{/if}
					</div>
					{#if opsMode === 'all'}
						<p class="text-muted-foreground text-xs">
							No filter — all operations of the service are exposed.
						</p>
					{:else}
						{#if opsMode === 'exclude'}
							<p class="text-muted-foreground mb-2 text-xs">
								All operations except the following are exposed:
							</p>
						{/if}
						<div class="flex flex-wrap gap-1.5">
							{#each operations as op (op)}
								{@const parts = splitOp(op)}
								<span
									class="bg-muted/50 inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 font-mono text-xs"
									title={op}
								>
									{#if parts.method}
										<span
											class={cn(
												'rounded px-1 text-[10px] font-bold ring-1 ring-inset',
												methodStyle(parts.method)
											)}>{parts.method}</span
										>
									{/if}
									<span class="max-w-[16rem] truncate">{parts.rest}</span>
								</span>
							{/each}
						</div>
					{/if}
				</div>

				<!-- Capabilities -->
				<div class="border-t pt-4">
					<div class="mb-2 flex items-center gap-2">
						<HugeiconsIcon icon={Wrench01Icon} size={15} class="text-muted-foreground" />
						<span class="font-medium">Capabilities</span>
						<span class="text-muted-foreground text-xs">{totalCapabilities}</span>
					</div>
					{#if capabilityGroups.length === 0}
						<p class="text-muted-foreground text-xs">
							No custom tools, prompts, resources or output filters attached.
						</p>
					{:else}
						<div class="space-y-3">
							{#each capabilityGroups as group (group.type)}
								{@const Icon = CAPABILITY_ICONS[group.type]}
								<div>
									<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
										{#if Icon}<HugeiconsIcon icon={Icon} size={13} />{/if}
										<span>{group.label}</span>
										<span class="opacity-70">{group.items.length}</span>
									</div>
									<div class="flex flex-wrap gap-1.5">
										{#each group.items as item (group.type + '::' + item.artifactName + '::' + item.name)}
											<span
												class={cn(
													'inline-flex items-center rounded-md px-2 py-0.5 font-mono text-xs ring-1 ring-inset',
													CAPABILITY_STYLES[group.type] ??
														'bg-muted text-muted-foreground ring-border'
												)}
												title={`Defined by artifact ${item.artifactName}`}
											>
												{item.name}
											</span>
										{/each}
									</div>
								</div>
							{/each}
						</div>
					{/if}
				</div>

				{#if str(plan?.id)}
					<a href="/services/{serviceId}/plans/{str(plan?.id)}" class="text-primary inline-flex items-center gap-1 text-xs hover:underline">
						Open configuration plan <HugeiconsIcon icon={ArrowRight01Icon} size={14} />
					</a>
				{/if}
			</Card.Content>
		</Card.Root>

		<!-- ─── Backend ─────────────────────────────────────── -->
		<Card.Root>
			<Card.Header>
				<div class="flex items-center gap-2">
					<HugeiconsIcon icon={CloudServerIcon} size={18} class="text-muted-foreground" />
					<Card.Title class="text-base">Backend</Card.Title>
				</div>
				<Card.Description>Where and how reShapr calls the underlying API.</Card.Description>
			</Card.Header>
			<Card.Content class="space-y-4 text-sm">
				<div>
					<div class="text-muted-foreground mb-1 text-xs">Endpoint</div>
					<code
						class="bg-muted block w-full overflow-x-auto rounded-md px-2 py-1.5 font-mono text-xs"
						title={backendEndpoint ?? ''}
					>
						{backendEndpoint ?? '—'}
					</code>
				</div>

				<!-- Backend authentication -->
				<div>
					<div class="text-muted-foreground mb-1 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={SquareLock01Icon} size={13} />
						<span>Authentication</span>
					</div>
					{#if backendSecret}
						<div class="flex flex-wrap items-center gap-2">
							<Badge variant="secondary" class="gap-1">
								<HugeiconsIcon icon={Key01Icon} size={12} />
								{backendSecret.name}
							</Badge>
							{#if backendSecret.type}
								<span class="text-muted-foreground text-[10px] uppercase">{backendSecret.type}</span>
							{/if}
						</div>
						<p class="text-muted-foreground mt-1 text-xs">
							Credentials injected from a stored secret reference.
						</p>
					{:else}
						<span class="text-muted-foreground text-xs">
							No authentication — the backend is called anonymously.
						</span>
					{/if}
				</div>

				<div class="border-t pt-4">
					<div class="text-muted-foreground mb-1 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={Timer01Icon} size={13} /> Timeout
					</div>
					<div class="text-muted-foreground">{backendTimeout ?? 'default'}</div>
				</div>
			</Card.Content>
		</Card.Root>
		</div>

		<!-- ─── Access (gateways, URLs, auth) ───────────────── -->
		<Card.Root>
			<Card.Header>
				<div class="flex items-center gap-2">
					<HugeiconsIcon icon={McpServerIcon} size={18} class="text-muted-foreground" />
					<Card.Title class="text-base">Access</Card.Title>
				</div>
				<Card.Description>Where clients reach this MCP server, and how they authenticate.</Card.Description>
			</Card.Header>
			<Card.Content class="space-y-4 text-sm">
				<!-- Gateway group -->
				<div>
					<div class="text-muted-foreground mb-1 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={TagsIcon} size={13} /> Gateway group
					</div>
					{#if str(gg?.name) || str(gg?.id)}
						<div class="flex flex-wrap items-center gap-2">
							<Badge variant="secondary" class="gap-1 text-xs">
								<HugeiconsIcon icon={TagsIcon} size={12} />
								{str(gg?.name) ?? '—'}
							</Badge>
							{#if ggLabels.length > 0}
								{#each ggLabels as [k, v] (k)}
									<Badge
											class="border-transparent font-mono text-[10px] text-white"
											style="background-color: {avatarColor(k)};"
									>
										{k}={v}
									</Badge>
								{/each}
							{/if}
						</div>
					{:else}
						<span class="text-muted-foreground text-xs">—</span>
					{/if}
				</div>

				<!-- Gateways -->
				<div class="border-t pt-4">
					<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={ApiGatewayIcon} size={13} /> Gateways
					</div>
					{#if !isActive}
						<p class="text-muted-foreground text-xs">
							Inactive — no gateway currently serves this MCP server.
						</p>
					{:else if gateways.length === 0}
						<p class="text-muted-foreground text-xs">No gateway attached.</p>
					{:else}
						<div class="flex flex-wrap gap-1.5">
							{#each gateways as g (g.id)}
								<Badge variant="outline" class="gap-1" title={g.fqdns.join(', ')}>
									<HugeiconsIcon icon={ApiGatewayIcon} size={12} />
									{g.name ?? g.id}
								</Badge>
							{/each}
						</div>
					{/if}
				</div>

				<!-- Endpoint URLs -->
				<div class="border-t pt-4">
					<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={Link01Icon} size={13} /> Endpoint URLs
					</div>
					{#if endpointUrls.length === 0}
						<p class="text-muted-foreground text-xs">
							{isActive ? 'No endpoint URL available.' : 'Available once the server is active.'}
						</p>
					{:else}
						<div class="flex flex-col gap-1.5">
							{#each endpointUrls as url (url)}
								<div class="bg-muted/50 flex min-w-0 items-center gap-1 rounded-md px-2 py-1">
									<code class="min-w-0 flex-1 truncate font-mono text-xs" title={url}>{url}</code>
									<button
										type="button"
										class="text-muted-foreground hover:text-foreground shrink-0 rounded p-0.5 transition-colors"
										title="Copy URL"
										aria-label="Copy URL"
										onclick={() => void copyUrl(url)}
									>
										<HugeiconsIcon
											icon={copiedUrl === url ? Tick02Icon : Copy01Icon}
											size={14}
											class={copiedUrl === url ? 'text-primary' : ''}
										/>
									</button>
								</div>
							{/each}
						</div>
					{/if}
				</div>

				<!-- MCP authentication -->
				<div class="border-t pt-4">
					<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={UserShield01Icon} size={13} /> Client authentication
					</div>
					{#if mcpAuth.kind === 'oauth'}
						<div class="flex items-center gap-2">
							<Badge class="bg-primary/10 text-primary gap-1 border-transparent">
								<HugeiconsIcon icon={ShieldEnergyIcon} size={12} /> OAuth 2.0
							</Badge>
						</div>
						{#if mcpAuth.servers.length}
							<dl class="mt-2 space-y-1 text-xs">
								<div>
									<dt class="text-muted-foreground">Authorization servers</dt>
									<dd class="mt-0.5 flex flex-wrap gap-1">
										{#each mcpAuth.servers as s (s)}
											<code class="bg-muted rounded px-1 py-0.5 font-mono break-all">{s}</code>
										{/each}
									</dd>
								</div>
							</dl>
						{/if}
						{#if mcpAuth.scopes.length}
							<div class="mt-2 text-xs">
								<span class="text-muted-foreground">Scopes: </span>
								{#each mcpAuth.scopes as sc (sc)}
									<code class="bg-muted mr-1 rounded px-1 py-0.5 font-mono">{sc}</code>
								{/each}
							</div>
						{/if}
					{:else if mcpAuth.kind === 'apikey'}
						<Badge variant="secondary" class="gap-1">
							<HugeiconsIcon icon={Key01Icon} size={12} /> API key
						</Badge>
						<p class="text-muted-foreground mt-1 text-xs">
							Clients must present the plan API key to reach this server.
						</p>
					{:else}
						<Badge variant="outline" class="gap-1">
							<HugeiconsIcon icon={GlobalIcon} size={12} /> Public
						</Badge>
						<p class="text-muted-foreground mt-1 text-xs">
							No client authentication required.
						</p>
					{/if}
				</div>

				<!-- Audit -->
				<div class="border-t pt-4">
					<div class="text-muted-foreground mb-1.5 flex items-center gap-1.5 text-xs">
						<HugeiconsIcon icon={SecurityCheckIcon} size={13} /> Audit
					</div>
					{#if auditEnabled}
						<span class="text-primary inline-flex items-center gap-1 text-sm">
							<HugeiconsIcon icon={CheckmarkBadge01Icon} size={14} /> Enabled
						</span>
						<p class="text-muted-foreground mt-1 text-xs">
							Requests handled by this MCP server are recorded in the audit log.
						</p>
					{:else}
						<span class="text-muted-foreground text-sm">Disabled</span>
					{/if}
				</div>
			</Card.Content>
		</Card.Root>
	</div>
{/if}

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
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Alert from '$lib/components/ui/alert/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { Checkbox } from '$lib/components/ui/checkbox/index.js';
	import { Textarea } from '$lib/components/ui/textarea/index.js';
	import * as Card from '$lib/components/ui/card/index.js';
	import { formatOperationsList, parseOperationsList } from '$lib/operationsList.js';

	const id = $derived(page.params.id);

	let raw = $state('');
	let includedOperationsText = $state('');
	let excludedOperationsText = $state('');
	/** Names of attached artifacts selected for this plan. Empty = all attached artifacts. */
	let includedArtifacts = $state<string[]>([]);
	/** Attached artifacts of the plan's service, used to populate the selector. */
	let attachedArtifacts = $state<{ name: string; type: string }[]>([]);
	let error = $state<string | null>(null);
	let apiKeyShown = $state<string | null>(null);
	let loading = $state(true);

	/** Union of attached artifact names and currently included names (to keep orphan selections editable). */
	const artifactOptions = $derived.by(() => {
		const names = new Set<string>();
		const out: { name: string; type: string | null }[] = [];
		for (const a of attachedArtifacts) {
			if (names.has(a.name)) continue;
			names.add(a.name);
			out.push({ name: a.name, type: a.type });
		}
		for (const n of includedArtifacts) {
			if (names.has(n)) continue;
			names.add(n);
			out.push({ name: n, type: null });
		}
		return out;
	});

	async function load() {
		if (!id) return;
		error = null;
		try {
			const p = await apiClient().getConfigurationPlan(id);
			raw = JSON.stringify(p, null, 2);
			syncOperationsFromRaw();
			await loadAttachedArtifacts(p);
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	async function loadAttachedArtifacts(plan: unknown) {
		const serviceId =
			plan && typeof plan === 'object' && typeof (plan as Record<string, unknown>).serviceId === 'string'
				? String((plan as Record<string, unknown>).serviceId)
				: '';
		if (!serviceId) {
			attachedArtifacts = [];
			return;
		}
		try {
			const refs = await apiClient().listArtifactRefsByService(serviceId);
			const list = Array.isArray(refs) ? refs : [];
			attachedArtifacts = list
				.filter((r) => r && typeof r === 'object' && (r as Record<string, unknown>).mainArtifact !== true)
				.map((r) => {
					const o = r as Record<string, unknown>;
					return {
						name: typeof o.name === 'string' ? o.name : '',
						type: typeof o.type === 'string' ? o.type : ''
					};
				})
				.filter((a) => a.name.length > 0);
		} catch {
			// Non-critical: fall back to editing included artifacts as free text via JSON.
			attachedArtifacts = [];
		}
	}

	$effect(() => {
		loading = true;
		void load();
	});

	function syncOperationsFromRaw() {
		try {
			const p = JSON.parse(raw) as Record<string, unknown>;
			includedOperationsText = formatOperationsList(p.includedOperations);
			excludedOperationsText = formatOperationsList(p.excludedOperations);
			includedArtifacts = Array.isArray(p.includedArtifacts)
				? p.includedArtifacts.map(String).filter((s) => s.length > 0)
				: [];
		} catch {
			/* raw not valid JSON yet */
		}
	}

	function toggleArtifact(name: string, checked: boolean) {
		if (checked) {
			if (!includedArtifacts.includes(name)) includedArtifacts = [...includedArtifacts, name];
		} else {
			includedArtifacts = includedArtifacts.filter((n) => n !== name);
		}
	}

	function applyOperationsToDocument() {
		error = null;
		try {
			const p = JSON.parse(raw) as Record<string, unknown>;
			const includedOperations = parseOperationsList(includedOperationsText);
			const excludedOperations = parseOperationsList(excludedOperationsText);
			if (includedOperations.length) p.includedOperations = includedOperations;
			else delete p.includedOperations;
			if (excludedOperations.length) p.excludedOperations = excludedOperations;
			else delete p.excludedOperations;
			if (includedArtifacts.length) p.includedArtifacts = includedArtifacts;
			else delete p.includedArtifacts;
			raw = JSON.stringify(p, null, 2);
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		}
	}

	async function onSave(ev: SubmitEvent) {
		ev.preventDefault();
		if (!id) return;
		error = null;
		applyOperationsToDocument();
		if (error) return;
		try {
			const parsed = JSON.parse(raw) as Record<string, unknown>;
			await apiClient().updateConfigurationPlan(id, parsed);
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	async function onRenew() {
		if (!id) return;
		error = null;
		try {
			const out = (await apiClient().renewApiKey(id)) as { apiKey?: string };
			apiKeyShown = out.apiKey ?? '(see server response)';
			await load();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		}
	}

	let deleteOpen = $state(false);

	function onDelete() {
		if (!id) return;
		deleteOpen = true;
	}

	async function confirmDeletePlan() {
		if (!id) return;
		await apiClient().deleteConfigurationPlan(id);
		goto('/plans');
	}
</script>

<p class="mb-4">
	<a href="/plans" class="text-primary text-sm hover:underline">← Plans</a>
</p>

<div class="mb-6 flex flex-wrap items-center justify-between gap-4">
	<h1 class="text-2xl font-bold tracking-tight">Plan {id}</h1>
	<div class="flex flex-wrap gap-2">
		<Button variant="outline" onclick={() => void onRenew()}>Renew API key</Button>
		<Button variant="destructive" onclick={() => void onDelete()}>Delete</Button>
	</div>
	</div>

{#if apiKeyShown}
	<Alert.Root class="mb-4">
		<Alert.Title>New API key</Alert.Title>
		<Alert.Description>
			<code class="text-xs break-all">{apiKeyShown}</code>
		</Alert.Description>
	</Alert.Root>
{/if}

{#if error}
	<ApiErrorAlert message={error} />
{/if}

<Card.Root class="mb-6 max-w-2xl">
	<Card.Header>
		<Card.Title class="text-base">Operations filter</Card.Title>
		<Card.Description>
			Equivalent to <code class="text-xs">--io</code> / <code class="text-xs">--eo</code> on
			<code class="text-xs">reshapr config create</code>. Merged into the JSON below on save.
		</Card.Description>
	</Card.Header>
	<Card.Content class="space-y-4">
		<div class="space-y-2">
			<Label for="includedOps">Included operations</Label>
			<Textarea
				id="includedOps"
				bind:value={includedOperationsText}
				rows={4}
				class="font-mono text-xs"
				disabled={loading}
				placeholder={'POST /tests/{testId}/start\nGET /masters'}
			/>
		</div>
		<div class="space-y-2">
			<Label for="excludedOps">Excluded operations (optional)</Label>
			<Textarea
				id="excludedOps"
				bind:value={excludedOperationsText}
				rows={3}
				class="font-mono text-xs"
				disabled={loading}
			/>
		</div>
		<Button type="button" variant="outline" disabled={loading} onclick={() => applyOperationsToDocument()}>
			Preview in JSON
		</Button>
	</Card.Content>
</Card.Root>

<Card.Root class="mb-6 max-w-2xl">
	<Card.Header>
		<Card.Title class="text-base">Included artifacts</Card.Title>
		<Card.Description>
			Equivalent to <code class="text-xs">--ia</code> on
			<code class="text-xs">reshapr config create</code>. Select which attached artifacts (Prompts,
			Resources, CustomTools, OutputFilters) this plan exposes. Merged into the JSON below on save.
		</Card.Description>
	</Card.Header>
	<Card.Content class="space-y-3">
		{#if artifactOptions.length === 0}
			<p class="text-muted-foreground text-sm">
				No attached artifacts found for this service. All attached artifacts (if any) will be included.
			</p>
		{:else}
			<p class="text-muted-foreground text-xs">
				Leave all unchecked to include <strong>all</strong> attached artifacts of the service.
			</p>
			<div class="flex flex-col gap-2">
				{#each artifactOptions as opt (opt.name)}
					<label class="flex items-center gap-2 text-sm">
						<Checkbox
							checked={includedArtifacts.includes(opt.name)}
							disabled={loading}
							onCheckedChange={(v) => toggleArtifact(opt.name, v === true)}
						/>
						<span class="font-mono text-xs">{opt.name}</span>
						{#if opt.type}
							<span class="text-muted-foreground text-[10px] uppercase">{opt.type}</span>
						{/if}
					</label>
				{/each}
			</div>
		{/if}
		<Button type="button" variant="outline" disabled={loading} onclick={() => applyOperationsToDocument()}>
			Preview in JSON
		</Button>
	</Card.Content>
</Card.Root>

<form class="space-y-4" onsubmit={onSave}>
	<p class="text-muted-foreground text-sm">
		Full JSON edit (PUT). Keep <code class="text-xs">id</code> and
		<code class="text-xs">organizationId</code> from the loaded document.
	</p>
	<Textarea class="font-mono text-xs" rows={22} bind:value={raw} disabled={loading} />
	<Button type="submit">Save</Button>
</form>

<ConfirmDialog
	bind:open={deleteOpen}
	title="Delete configuration plan"
	description={`You are about to delete the configuration plan "${id}". This action cannot be undone.`}
	confirmLabel="Delete"
	onConfirm={confirmDeletePlan}
>
	<p class="text-muted-foreground text-sm">
		The MCP endpoint exposed by this plan will no longer be served. Any client connected through it
		will lose access until another plan is configured.
	</p>
</ConfirmDialog>


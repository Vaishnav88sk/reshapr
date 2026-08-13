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
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { auth } from '$lib/stores/auth.svelte.js';
  import { Button } from '$lib/components/ui/button/index.js';
  import { Input } from '$lib/components/ui/input/index.js';
  import { Label } from '$lib/components/ui/label/index.js';
  import { Switch } from '$lib/components/ui/switch/index.js';
  import { Card, CardContent } from '$lib/components/ui/card/index.js';
  import QuotaGauge from '$lib/components/QuotaGauge.svelte';
  import PageHeader from '$lib/components/PageHeader.svelte';
  import { HugeiconsIcon, type IconSvgElement } from '@hugeicons/svelte';
  import {
    ApiGatewayIcon,
    Building01Icon,
    McpServerIcon,
    Search01Icon,
    TagsIcon
  } from '@hugeicons/core-free-icons';

  // ── Types ──────────────────────────────────────────────────
  interface Organization {
    name: string;
    description: string | null;
    icon: string | null;
    ownerUsername: string | null;
  }

  /** Quota entry as returned by the control-plane admin API. */
  interface Quota {
    organizationId: string;
    metric: string;
    enabled: boolean;
    limit: number;
    remaining: number;
  }

  /** Editable local state for a single metric. */
  interface QuotaState {
    metric: string;
    label: string;
    description: string;
    icon: IconSvgElement;
    /** Whether a quota already exists server-side for this metric. */
    exists: boolean;
    /** Current consumption (limit - remaining), fixed while editing. */
    used: number;
    // Editable fields.
    enabled: boolean;
    limit: number;
    // Loaded reference values (for dirty detection / reset).
    initialEnabled: boolean;
    initialLimit: number;
  }

  // Known quota metrics. The `metric` key must match the control-plane
  // `QuotaMetric` enum string representation (see QuotaMetric.java).
  const KNOWN_METRICS = [
    {
      metric: 'exposition.count',
      label: 'MCP Servers',
      description: 'Maximum number of MCP server expositions the organization can run.',
      icon: McpServerIcon
    },
    {
      metric: 'gateway-group.count',
      label: 'Gateway Groups',
      description: 'Maximum number of gateway groups the organization can define.',
      icon: TagsIcon
    },
    {
      metric: 'gateway.count',
      label: 'Gateways',
      description: 'Maximum number of gateways the organization can register.',
      icon: ApiGatewayIcon
    }
  ] as const;

  // The organization list is loaded fully client-side for the autocomplete
  // (the API has no server-side search). A large page size minimizes the
  // number of round-trips even when there are many organizations.
  const PAGE_SIZE = 100;

  // ── Organization search state ─────────────────────────────
  let allOrganizations = $state<Organization[]>([]);
  let orgsLoading = $state(true);
  let orgQuery = $state('');
  let suggestionsOpen = $state(false);
  let highlightIndex = $state(-1);
  let selectedOrg = $state<Organization | null>(null);

  const filteredOrganizations = $derived(
    orgQuery.trim() === ''
      ? allOrganizations
      : allOrganizations.filter((o) => o.name.toLowerCase().includes(orgQuery.toLowerCase()))
  );

  // Only a capped number of suggestions is rendered; keyboard navigation
  // operates on this same visible slice for consistency.
  const visibleSuggestions = $derived(filteredOrganizations.slice(0, 12));

  // ── Quotas state ──────────────────────────────────────────
  let quotaStates = $state<QuotaState[]>([]);
  let quotasLoading = $state(false);
  let quotasError = $state('');
  let saving = $state(false);
  let saveError = $state('');
  let saveSuccess = $state('');

  const isDirty = $derived(
    quotaStates.some((q) => q.enabled !== q.initialEnabled || q.limit !== q.initialLimit)
  );

  // ── Lifecycle ─────────────────────────────────────────────
  onMount(() => {
    if (!auth.isAdmin) {
      goto('/');
      return;
    }
    fetchOrganizations();
  });

  // ── Organization fetching ─────────────────────────────────
  async function fetchPage(page: number): Promise<Organization[] | null> {
    const res = await fetch(`/api/admin/organizations?page=${page}&size=${PAGE_SIZE}`);
    if (!res.ok) return null;
    return (await res.json()) as Organization[];
  }

  // Load every page sequentially until a short (last) page is reached.
  // The endpoint has no server-side search, so we need the full list
  // client-side for the autocomplete to match any existing organization.
  // Results are appended progressively so suggestions appear while loading.
  async function fetchOrganizations() {
    orgsLoading = true;
    try {
      let acc: Organization[] = [];
      for (let p = 0; ; p++) {
        const next = await fetchPage(p);
        if (next === null) break; // Network/HTTP error — stop with what we have.
        acc = [...acc, ...next];
        allOrganizations = acc;
        if (next.length < PAGE_SIZE) break; // Last page reached.
      }
    } catch {
      // Non-critical: search will match whatever was loaded so far.
    } finally {
      orgsLoading = false;
    }
  }

  // ── Organization selection ────────────────────────────────
  function handleSearchKeydown(e: KeyboardEvent) {
    if (!suggestionsOpen || visibleSuggestions.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      highlightIndex = (highlightIndex + 1) % visibleSuggestions.length;
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      highlightIndex =
        highlightIndex <= 0 ? visibleSuggestions.length - 1 : highlightIndex - 1;
    } else if (e.key === 'Enter' && highlightIndex >= 0) {
      e.preventDefault();
      selectOrganization(visibleSuggestions[highlightIndex]);
    } else if (e.key === 'Escape') {
      suggestionsOpen = false;
    }
  }

  function selectOrganization(org: Organization) {
    selectedOrg = org;
    orgQuery = org.name;
    suggestionsOpen = false;
    highlightIndex = -1;
    loadQuotas(org.name);
  }

  // ── Quotas fetching ───────────────────────────────────────
  async function loadQuotas(orgName: string) {
    quotasLoading = true;
    quotasError = '';
    saveError = '';
    saveSuccess = '';
    quotaStates = [];
    try {
      const res = await fetch(`/api/admin/quotas/organization/${encodeURIComponent(orgName)}`);
      if (res.status === 404) {
        quotasError = 'Organization not found.';
        return;
      }
      if (!res.ok) {
        quotasError = 'Failed to load quotas for this organization.';
        return;
      }
      const quotas = (await res.json()) as Quota[];
      quotaStates = KNOWN_METRICS.map((def) => buildState(def, quotas));
    } catch {
      quotasError = 'Network error while loading quotas.';
    } finally {
      quotasLoading = false;
    }
  }

  function buildState(def: (typeof KNOWN_METRICS)[number], quotas: Quota[]): QuotaState {
    const existing = quotas.find((q) => q.metric === def.metric);
    const limit = existing ? existing.limit : 0;
    const remaining = existing ? existing.remaining : 0;
    const used = Math.max(0, limit - remaining);
    return {
      metric: def.metric,
      label: def.label,
      description: def.description,
      icon: def.icon,
      exists: existing != null,
      used,
      enabled: existing ? existing.enabled : false,
      limit,
      initialEnabled: existing ? existing.enabled : false,
      initialLimit: limit
    };
  }

  // ── Slider helpers ────────────────────────────────────────
  /** Upper bound of the slider — adapts to the current value. */
  function sliderMax(state: QuotaState): number {
    return Math.max(50, state.initialLimit * 2, state.limit);
  }

  /** Live gauge preview using the fixed usage and the edited limit. */
  function previewQuota(state: QuotaState) {
    return {
      used: state.used,
      limit: state.limit,
      remaining: Math.max(0, state.limit - state.used)
    };
  }

  function clampLimit(state: QuotaState, value: number) {
    const v = Number.isFinite(value) ? Math.max(0, Math.round(value)) : 0;
    state.limit = v;
    saveSuccess = '';
  }

  function resetChanges() {
    for (const s of quotaStates) {
      s.enabled = s.initialEnabled;
      s.limit = s.initialLimit;
    }
    saveError = '';
    saveSuccess = '';
  }

  // ── Save ──────────────────────────────────────────────────
  async function saveQuotas() {
    if (!selectedOrg) return;
    saving = true;
    saveError = '';
    saveSuccess = '';
    try {
      // Send every metric that is enabled or already persisted, so that
      // toggling a metric off is also persisted. We use the `/force`
      // endpoint and explicitly recompute `remaining` from the fixed
      // consumption (`used`), so raising the limit also frees up capacity
      // instead of leaving `remaining` untouched.
      const payload = quotaStates
        .filter((s) => s.enabled || s.exists)
        .map((s) => ({
          metric: s.metric,
          enabled: s.enabled,
          limit: s.limit,
          remaining: Math.max(0, s.limit - s.used)
        }));

      const res = await fetch(
        `/api/admin/quotas/organization/${encodeURIComponent(selectedOrg.name)}/force`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        }
      );

      if (res.ok) {
        const updated = (await res.json()) as Quota[];
        quotaStates = KNOWN_METRICS.map((def) => buildState(def, updated));
        saveSuccess = 'Quotas updated successfully.';
      } else if (res.status === 404) {
        saveError = 'Organization not found.';
      } else if (res.status === 403) {
        saveError = 'Forbidden: admin access required.';
      } else if (res.status === 401) {
        saveError = 'Session expired. Please refresh the page.';
      } else {
        const body = await res.text();
        saveError = `Failed to update quotas: ${body || res.statusText}`;
      }
    } catch {
      saveError = 'Network error. Please try again.';
    } finally {
      saving = false;
    }
  }
</script>

<svelte:head>
  <title>Quotas — reShapr</title>
</svelte:head>

<svelte:document onclick={() => (suggestionsOpen = false)} />

<div class="space-y-6">
  <PageHeader
    title="Quotas"
    subtitle="Search an organization to review its resource usage and adjust its quota limits."
  >
    {#snippet actions()}
      {#if selectedOrg}
        <Button variant="outline" onclick={resetChanges} disabled={!isDirty || saving}>
          Reset
        </Button>
        <Button onclick={saveQuotas} disabled={!isDirty || saving}>
          {#if saving}
            <div
              class="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent"
            ></div>
            Saving…
          {:else}
            Save Changes
          {/if}
        </Button>
      {/if}
    {/snippet}
  </PageHeader>

  <!-- ── Organization search ─────────────────────────────── -->
  <div class="max-w-xl space-y-2">
    <Label for="orgSearch">Organization</Label>
    <div class="relative" role="none" onclick={(e) => e.stopPropagation()}>
      <span
        class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
      >
        <HugeiconsIcon icon={Search01Icon} size={16} />
      </span>
      <Input
        id="orgSearch"
        type="text"
        class="pl-9"
        placeholder="Search an organization…"
        bind:value={orgQuery}
        autocomplete="off"
        onfocus={() => {
          suggestionsOpen = true;
          highlightIndex = -1;
        }}
        oninput={() => {
          suggestionsOpen = true;
          highlightIndex = -1;
        }}
        onkeydown={handleSearchKeydown}
      />
      {#if suggestionsOpen}
        <ul
          class="absolute z-50 mt-1 max-h-64 w-full overflow-y-auto rounded-md border bg-popover p-1 text-popover-foreground shadow-md"
        >
          {#if filteredOrganizations.length > 0}
            {#each visibleSuggestions as suggestion, i (suggestion.name)}
              <li>
                <button
                  type="button"
                  class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-sm cursor-pointer hover:bg-accent hover:text-accent-foreground {i ===
                  highlightIndex
                    ? 'bg-accent text-accent-foreground'
                    : ''}"
                  onmousedown={() => selectOrganization(suggestion)}
                >
                  <span
                    class="flex h-5 w-5 shrink-0 items-center justify-center rounded bg-primary/10 text-primary"
                  >
                    <HugeiconsIcon icon={Building01Icon} size={12} />
                  </span>
                  <span class="font-medium">{suggestion.name}</span>
                  {#if suggestion.description}
                    <span class="truncate text-xs text-muted-foreground">— {suggestion.description}</span>
                  {/if}
                </button>
              </li>
            {/each}
            {#if filteredOrganizations.length > 12}
              <li class="px-2 py-1.5 text-xs text-muted-foreground">
                {filteredOrganizations.length - 12} more… keep typing to narrow down.
              </li>
            {/if}
          {:else if orgsLoading}
            <li class="flex items-center gap-2 px-2 py-1.5 text-sm text-muted-foreground">
              <div
                class="h-3.5 w-3.5 animate-spin rounded-full border-2 border-primary border-t-transparent"
              ></div>
              Loading organizations…
            </li>
          {:else}
            <li class="px-2 py-1.5 text-sm text-muted-foreground">No organization found.</li>
          {/if}
        </ul>
      {/if}
    </div>
    <p class="text-xs text-muted-foreground">
      {#if orgsLoading}
        Loading organizations… ({allOrganizations.length} loaded)
      {:else}
        Start typing to find an organization by name ({allOrganizations.length} total).
      {/if}
    </p>
  </div>

  <!-- ── Quotas panel ────────────────────────────────────── -->
  {#if selectedOrg}
    {#if quotasLoading}
      <div class="flex items-center justify-center py-12">
        <div
          class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"
        ></div>
        <span class="ml-3 text-sm text-muted-foreground">Loading quotas…</span>
      </div>
    {:else if quotasError}
      <div class="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
        {quotasError}
      </div>
    {:else}
      <div class="space-y-4">
        {#each quotaStates as state, i (state.metric)}
          <Card>
            <CardContent class="space-y-4 p-5">
              <!-- Header row: icon, label, enable toggle -->
              <div class="flex items-start justify-between gap-4">
                <div class="flex items-start gap-3">
                  <span
                    class="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
                  >
                    <HugeiconsIcon icon={state.icon} class="size-5" />
                  </span>
                  <div>
                    <div class="text-sm font-semibold text-foreground">{state.label}</div>
                    <div class="text-xs text-muted-foreground">{state.description}</div>
                  </div>
                </div>
                <div class="flex shrink-0 items-center gap-2">
                  <Label for="enabled-{state.metric}" class="text-xs text-muted-foreground">
                    {state.enabled ? 'Enabled' : 'Disabled'}
                  </Label>
                  <Switch
                    id="enabled-{state.metric}"
                    checked={state.enabled}
                    onCheckedChange={(v) => {
                      quotaStates[i].enabled = v;
                      saveSuccess = '';
                    }}
                  />
                </div>
              </div>

              <!-- Live usage gauge (previews the edited limit) -->
              <QuotaGauge
                quota={previewQuota(state)}
                label="Usage"
                class={state.enabled ? '' : 'opacity-50'}
              />

              <!-- Limit editor: slider + numeric input -->
              <div class="grid gap-3 sm:grid-cols-[1fr_auto] sm:items-center">
                <div class="flex items-center gap-3">
                  <span class="w-6 text-right text-xs text-muted-foreground tabular-nums">0</span>
                  <input
                    type="range"
                    min="0"
                    max={sliderMax(state)}
                    step="1"
                    value={state.limit}
                    disabled={!state.enabled}
                    oninput={(e) => clampLimit(quotaStates[i], e.currentTarget.valueAsNumber)}
                    class="h-2 flex-1 cursor-pointer appearance-none rounded-full bg-muted accent-primary disabled:cursor-not-allowed disabled:opacity-50"
                    aria-label="{state.label} limit"
                  />
                  <span class="w-10 text-left text-xs text-muted-foreground tabular-nums"
                    >{sliderMax(state)}</span
                  >
                </div>
                <div class="flex items-center gap-2">
                  <Label for="limit-{state.metric}" class="text-xs text-muted-foreground">Limit</Label>
                  <Input
                    id="limit-{state.metric}"
                    type="number"
                    min="0"
                    class="w-28"
                    value={state.limit}
                    disabled={!state.enabled}
                    oninput={(e) => clampLimit(quotaStates[i], e.currentTarget.valueAsNumber)}
                  />
                </div>
              </div>

              <p class="text-xs text-muted-foreground">
                {#if state.enabled}
                  {state.used} used · {Math.max(0, state.limit - state.used)} remaining after save
                {:else}
                  Quota disabled — this metric is not enforced for the organization.
                {/if}
              </p>
            </CardContent>
          </Card>
        {/each}
      </div>

      {#if saveError}
        <div class="rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {saveError}
        </div>
      {/if}
      {#if saveSuccess}
        <div class="rounded-md bg-primary/10 px-4 py-3 text-sm text-primary">
          {saveSuccess}
        </div>
      {/if}
    {/if}
  {:else}
    <Card>
      <CardContent class="flex flex-col items-center gap-2 py-12 text-center">
        <span
          class="flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary"
        >
          <HugeiconsIcon icon={Building01Icon} class="size-6" />
        </span>
        <p class="text-sm font-medium text-foreground">No organization selected</p>
        <p class="max-w-sm text-sm text-muted-foreground">
          Use the search box above to pick an organization and manage its quota limits.
        </p>
      </CardContent>
    </Card>
  {/if}
</div>













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

<!-- Shared display of a configuration plan's audit setting. -->
<script lang="ts">
	import { HugeiconsIcon } from '@hugeicons/svelte';
	import { SecurityCheckIcon, CheckmarkBadge01Icon } from '@hugeicons/core-free-icons';
	import { auditOf } from './planView.js';

	let { plan }: { plan: Record<string, unknown> | null } = $props();

	const auditEnabled = $derived(auditOf(plan));
</script>

<div>
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


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
	import { apiClient, ApiError } from '$lib/api/client.js';
	import ApiErrorAlert from '$lib/components/ApiErrorAlert.svelte';
	import ArtifactSecretSelect from './ArtifactSecretSelect.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import * as Dialog from '$lib/components/ui/dialog/index.js';
	import { Input } from '$lib/components/ui/input/index.js';
	import { Label } from '$lib/components/ui/label/index.js';
	import { cn } from '$lib/utils.js';

	export type ImportArtifactMode = 'import' | 'attach';

	/**
	 * Reusable dialog to import a service specification (main artifact) or attach a
	 * pre-existing artifact. It supports two sources: a local file upload or a
	 * remote URL, optionally authenticated with an `ARTIFACT` secret fetched
	 * server-side by the control plane.
	 *
	 * The component is controlled through the bindable `open` prop so it can be
	 * embedded in a page (behind an "Import" button) or reused in the upcoming
	 * Quick start wizard. `onDone` is invoked with the API response on success.
	 */
	let {
		open = $bindable(false),
		mode,
		title,
		description,
		onDone
	}: {
		open?: boolean;
		mode: ImportArtifactMode;
		title?: string;
		description?: string;
		onDone?: (result: unknown) => void;
	} = $props();

	type Source = 'file' | 'url';

	const DEFAULTS = {
		import: {
			title: 'Import a service specification',
			description:
				'Upload a specification file or reference a URL to register a new service and its main artifact.',
			submitLabel: 'Import'
		},
		attach: {
			title: 'Attach an existing artifact',
			description:
				'Upload an artifact document or reference a URL to attach it to an existing service.',
			submitLabel: 'Attach'
		}
	} as const;

	const resolvedTitle = $derived(title ?? DEFAULTS[mode].title);
	const resolvedDescription = $derived(description ?? DEFAULTS[mode].description);
	const submitLabel = $derived(DEFAULTS[mode].submitLabel);

	let source = $state<Source>('file');
	let files = $state<FileList | undefined>(undefined);
	let url = $state('');
	let secretName = $state('');
	let serviceName = $state('');
	let serviceVersion = $state('');
	let submitting = $state(false);
	let error = $state<string | null>(null);

	function reset() {
		source = 'file';
		files = undefined;
		url = '';
		secretName = '';
		serviceName = '';
		serviceVersion = '';
		error = null;
		submitting = false;
	}

	function handleOpenChange(next: boolean) {
		if (submitting) return;
		open = next;
		if (!next) reset();
	}

	async function submit() {
		error = null;
		submitting = true;
		try {
			const client = apiClient();
			let result: unknown;

			if (source === 'file') {
				const file = files?.[0];
				if (!file || !file.size) {
					error = 'Please choose a file.';
					return;
				}
				if (mode === 'import') {
					const extra: Record<string, string> = {};
					if (serviceName.trim()) extra.serviceName = serviceName.trim();
					if (serviceVersion.trim()) extra.serviceVersion = serviceVersion.trim();
					result = await client.importArtifactFile(file, extra);
				} else {
					result = await client.attachArtifactFile(file);
				}
			} else {
				const u = url.trim();
				if (!u) {
					error = 'Please provide a URL.';
					return;
				}
				if (mode === 'import') {
					const p = new URLSearchParams();
					p.set('url', u);
					p.set('mainArtifact', 'true');
					if (secretName.trim()) p.set('secretName', secretName.trim());
					if (serviceName.trim()) p.set('serviceName', serviceName.trim());
					if (serviceVersion.trim()) p.set('serviceVersion', serviceVersion.trim());
					result = await client.importArtifactUrl(p);
				} else {
					result = await client.attachArtifactUrl(u, secretName.trim() || undefined);
				}
			}

			onDone?.(result);
			open = false;
			reset();
		} catch (e) {
			error = e instanceof ApiError ? e.message : String(e);
		} finally {
			submitting = false;
		}
	}
</script>

<Dialog.Root {open} onOpenChange={handleOpenChange}>
	<Dialog.Content class="sm:max-w-lg">
		<Dialog.Header>
			<Dialog.Title>{resolvedTitle}</Dialog.Title>
			<Dialog.Description>{resolvedDescription}</Dialog.Description>
		</Dialog.Header>

		<div class="space-y-4">
			{#if error}
				<ApiErrorAlert message={error} />
			{/if}

			<!-- Source segmented control -->
			<div class="bg-muted grid grid-cols-2 gap-1 rounded-lg p-1">
				<button
					type="button"
					class={cn(
						'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
						source === 'file'
							? 'bg-background text-foreground shadow-sm'
							: 'text-muted-foreground hover:text-foreground'
					)}
					onclick={() => (source = 'file')}
				>
					Local file
				</button>
				<button
					type="button"
					class={cn(
						'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
						source === 'url'
							? 'bg-background text-foreground shadow-sm'
							: 'text-muted-foreground hover:text-foreground'
					)}
					onclick={() => (source = 'url')}
				>
					Remote URL
				</button>
			</div>

			{#if source === 'file'}
				<div class="space-y-2">
					<Label for="artifact-import-file">File</Label>
					<Input
						id="artifact-import-file"
						type="file"
						bind:files
						accept={mode === 'import'
							? '.json,.yaml,.yml,.graphql,.graphqls,.gql,.proto'
							: '.json,.yaml,.yml'}
					/>
				</div>
			{:else}
				<div class="space-y-2">
					<Label for="artifact-import-url">URL</Label>
					<Input
						id="artifact-import-url"
						bind:value={url}
						placeholder="https://…"
						autocomplete="off"
					/>
				</div>
				<div class="space-y-2">
					<Label for="artifact-import-secret">Secret (optional)</Label>
					<ArtifactSecretSelect id="artifact-import-secret" bind:value={secretName} />
					<p class="text-muted-foreground text-xs">
						An <code class="text-xs">ARTIFACT</code> secret used by the control plane to fetch the URL.
					</p>
				</div>
			{/if}

			{#if mode === 'import'}
				<div class="grid grid-cols-2 gap-3">
					<div class="space-y-2">
						<Label for="artifact-import-service-name">Service name (optional)</Label>
						<Input
							id="artifact-import-service-name"
							bind:value={serviceName}
							placeholder="GraphQL only"
							autocomplete="off"
						/>
					</div>
					<div class="space-y-2">
						<Label for="artifact-import-service-version">Service version (optional)</Label>
						<Input
							id="artifact-import-service-version"
							bind:value={serviceVersion}
							placeholder="GraphQL only"
							autocomplete="off"
						/>
					</div>
				</div>
			{/if}
		</div>

		<Dialog.Footer>
			<Button variant="outline" onclick={() => handleOpenChange(false)} disabled={submitting}>
				Cancel
			</Button>
			<Button onclick={() => void submit()} disabled={submitting}>
				{submitting ? 'Working…' : submitLabel}
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>


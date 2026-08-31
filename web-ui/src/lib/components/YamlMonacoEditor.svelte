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
	import { ensureMonacoYaml, getMonacoYamlHandle } from '$lib/monaco/setup.js';
	import { artifactModelUri, buildMonacoYamlSchemaForPath } from '$lib/monaco/schemas.js';
	import { RESHAPR_DARK_THEME, defineReshaprDarkTheme } from '$lib/monaco/theme.js';
	import { theme } from '$lib/stores/theme.svelte.js';
	import ScrollableCode from '$lib/components/ScrollableCode.svelte';
	import type * as Monaco from 'monaco-editor';

	let {
		value = '',
		readOnly = false,
		height = '24rem',
		schemaUri = undefined,
		onChange = undefined,
		onValidationChange = undefined
	}: {
		value?: string;
		readOnly?: boolean;
		height?: string;
		/** Public path to the JSON Schema (e.g. `/schemas/Prompts-v1alpha1-schema.json`). */
		schemaUri?: string;
		onChange?: (value: string) => void;
		onValidationChange?: (markers: Monaco.editor.IMarker[]) => void;
	} = $props();

	let container = $state<HTMLDivElement | null>(null);
	let editor = $state<Monaco.editor.IStandaloneCodeEditor | null>(null);
	let model = $state<Monaco.editor.ITextModel | null>(null);
	let monacoRef = $state<typeof Monaco | null>(null);
	let loading = $state(true);
	let loadError = $state<string | null>(null);
	let validationMarkers = $state<Monaco.editor.IMarker[]>([]);
	let lineCount = $state(0);

	/** Minimap is only shown for documents larger than this many lines. */
	const MINIMAP_LINE_THRESHOLD = 50;

	const heightStyle = $derived(typeof height === 'number' ? `${height}px` : height);
	const minimapEnabled = $derived(lineCount > MINIMAP_LINE_THRESHOLD);
	// Sync with the app theme: minimalist reshapr-dark (based on vs-dark) or built-in vs.
	const monacoTheme = $derived(theme.resolved === 'dark' ? RESHAPR_DARK_THEME : 'vs');
	const schemaErrors = $derived(validationMarkers.filter((marker) => marker.severity === 8));
	const yamlMarkers = $derived(validationMarkers);
	const showValidationSummary = $derived(
		schemaUri !== undefined && yamlMarkers.length > 0
	);

	function emitValidation(monaco: typeof Monaco) {
		if (!model) return;
		const markers = monaco.editor.getModelMarkers({ resource: model.uri, owner: 'yaml' });
		validationMarkers = markers;
		onValidationChange?.(markers);
	}

	onMount(() => {
		let disposed = false;
		let markerDisposable: Monaco.IDisposable | null = null;
		let contentDisposable: Monaco.IDisposable | null = null;
		let lineCountDisposable: Monaco.IDisposable | null = null;

		void (async () => {
			if (!container) return;
			try {
				const monaco = await ensureMonacoYaml();
				if (disposed || !container) return;
				monacoRef = monaco;
				if (theme.resolved === 'dark') defineReshaprDarkTheme(monaco);

				if (schemaUri) {
					getMonacoYamlHandle()?.update({
						schemas: [buildMonacoYamlSchemaForPath(schemaUri)]
					});
				}

				const uri = monaco.Uri.parse(
					schemaUri ? artifactModelUri(schemaUri) : `inmemory://reshapr/artifact/${crypto.randomUUID()}.yaml`
				);
				const textModel = monaco.editor.createModel(value, 'yaml', uri);
				model = textModel;
				lineCount = textModel.getLineCount();
				lineCountDisposable = textModel.onDidChangeContent(() => {
					lineCount = textModel.getLineCount();
				});

				const instance = monaco.editor.create(container, {
					model: textModel,
					language: 'yaml',
					readOnly,
					automaticLayout: true,
					minimap: { enabled: minimapEnabled },
					scrollBeyondLastLine: false,
					wordWrap: 'on',
					tabSize: 2,
					fontSize: 13,
					theme: monacoTheme,
					quickSuggestions: {
						other: 'on',
						comments: 'off',
						strings: 'on'
					},
					suggestOnTriggerCharacters: true,
					wordBasedSuggestions: 'off',
					tabCompletion: 'on',
					suggest: {
						showProperties: true,
						snippetsPreventQuickSuggestions: false
					}
				});
				editor = instance;

				if (onChange) {
					contentDisposable = textModel.onDidChangeContent(() => {
						onChange(textModel.getValue());
					});
				}

				markerDisposable = monaco.editor.onDidChangeMarkers((uris) => {
					if (uris.some((u) => u.toString() === textModel.uri.toString())) {
						emitValidation(monaco);
					}
				});
				emitValidation(monaco);
				// YAML worker validation is async on first open — refresh markers once settled.
				window.setTimeout(() => emitValidation(monaco), 400);
			} catch (e) {
				loadError = e instanceof Error ? e.message : String(e);
			} finally {
				if (!disposed) loading = false;
			}
		})();

		return () => {
			disposed = true;
			markerDisposable?.dispose();
			contentDisposable?.dispose();
			lineCountDisposable?.dispose();
			editor?.dispose();
			model?.dispose();
			editor = null;
			model = null;
		};
	});

	$effect(() => {
		// When onChange is wired (editable mode), the model is the source of truth — do not push
		// prop updates on every keystroke or monaco-yaml validation/completion breaks.
		if (!editor || !model || onChange) return;
		const current = model.getValue();
		if (value !== current) {
			model.setValue(value);
		}
	});

	$effect(() => {
		if (!editor) return;
		editor.updateOptions({ readOnly });
	});

	/** Imperatively replace the editor content (used to insert example templates). */
	export function setValue(text: string) {
		if (!model || !editor) return;
		model.setValue(text);
		editor.focus();
		editor.revealLine(model.getLineCount());
	}

	$effect(() => {
		// Toggle the minimap based on the document length.
		editor?.updateOptions({ minimap: { enabled: minimapEnabled } });
	});

	$effect(() => {
		// Keep the editor theme in sync with the application theme (setTheme is global).
		const monaco = monacoRef;
		if (!monaco) return;
		if (theme.resolved === 'dark') defineReshaprDarkTheme(monaco);
		monaco.editor.setTheme(monacoTheme);
	});
</script>

{#if loadError}
	<ScrollableCode text={value || '—'} maxHeight={heightStyle} />
	<p class="text-destructive mt-2 text-xs">Editor failed to load: {loadError}</p>
{:else}
	<div class="relative overflow-hidden rounded-lg border" style:height={heightStyle}>
		<div bind:this={container} class="h-full w-full" role="presentation"></div>
		{#if loading}
			<div
				class="bg-muted text-muted-foreground absolute inset-0 flex items-center justify-center font-mono text-xs"
			>
				Loading editor…
			</div>
		{/if}
		{#if showValidationSummary}
			<div
				class="border-destructive/40 bg-background/95 absolute inset-x-2 bottom-2 z-10 rounded-md border px-3 py-2 text-xs shadow-md backdrop-blur-sm"
			>
				<p class="text-destructive font-medium">
					{schemaErrors.length > 0
						? `${schemaErrors.length} schema ${schemaErrors.length === 1 ? 'error' : 'errors'}`
						: `${yamlMarkers.length} schema ${yamlMarkers.length === 1 ? 'warning' : 'warnings'}`}
				</p>
				<ul class="text-muted-foreground mt-1 space-y-0.5">
					{#each yamlMarkers.slice(0, 5) as marker (marker.message + marker.startLineNumber)}
						<li>
							Line {marker.startLineNumber}: {marker.message}
						</li>
					{/each}
					{#if yamlMarkers.length > 5}
						<li>…and {yamlMarkers.length - 5} more</li>
					{/if}
				</ul>
			</div>
		{/if}
	</div>
{/if}

import adapter from '@sveltejs/adapter-node';
import { sveltekit } from '@sveltejs/kit/vite';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [
    tailwindcss(),
    sveltekit({
      preprocess: vitePreprocess(),
      adapter: adapter(),
      // SvelteKit 3 removed the implicit `$lib` alias; keep the existing imports working.
      alias: { $lib: 'src/lib' }
    })
  ],
  resolve: {
    alias: [
      // monaco-editor >= 0.56 ships an `exports` map that hides the `esm/vs/` deep paths
      // still used by monaco-yaml's dependencies (monaco-worker-manager, monaco-types).
      { find: /^monaco-editor\/esm\/vs\/(.*)$/, replacement: 'monaco-editor/$1' }
    ]
  },
  worker: {
    format: 'es'
  }
});

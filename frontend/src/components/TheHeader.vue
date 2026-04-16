<script setup lang="ts">
import { nextTick } from 'vue'
import { useAnalyzerStore } from '../store/analyzer'
import { examples } from '../constants/examples'

const analyzerStore = useAnalyzerStore()
const exampleNames = Object.keys(examples) as Array<keyof typeof examples>

async function handlePresetChange(event: Event) {
  const name = (event.target as HTMLSelectElement).value as keyof typeof examples
  analyzerStore.loadExample(name)
  await nextTick()
}

async function onAnalyze() {
  if (document.activeElement instanceof HTMLElement) {
    document.activeElement.blur()
  }
  await nextTick()
  await analyzerStore.analyze()
}
</script>

<template>
  <header class="h-14 border-b border-slate-700 flex items-center justify-between px-6 shrink-0 bg-slate-800 z-30">
    <div class="font-bold text-lg tracking-wide flex items-center gap-2 text-white">
      <span class="text-indigo-400">⚡</span> Code Analyzer
    </div>

    <div class="flex items-center gap-3">
      <label class="flex items-center gap-2 text-sm font-semibold text-slate-200">
        <span class="text-slate-400">Preset</span>
        <select
            :value="analyzerStore.selectedExample"
            @change="handlePresetChange"
            class="h-9 rounded-md border border-slate-600 bg-slate-900 px-3 text-sm font-semibold text-slate-100 outline-none focus:border-indigo-400"
        >
          <option v-for="name in exampleNames" :key="name" :value="name">
            {{ name }}
          </option>
        </select>
      </label>

      <button
          @click="onAnalyze"
          :disabled="analyzerStore.isLoading"
          class="bg-indigo-600 hover:bg-indigo-500 text-white px-5 py-2 rounded-md font-semibold text-sm transition disabled:opacity-50 flex items-center gap-2"
      >
        <span v-if="analyzerStore.isLoading" class="animate-spin text-lg">⏳</span>
        {{ analyzerStore.isLoading ? 'Analyzing...' : 'Run Analysis' }}
      </button>
    </div>
  </header>
</template>
<script setup lang="ts">
import TheHeader from './components/TheHeader.vue'
import CodeEditor from './components/CodeEditor.vue'
import FlowVisualizer from './components/FlowVisualizer.vue'
import MemoryInspector from './components/MemoryInspector.vue'
import ExecutionControls from './components/ExecutionControls.vue' // <-- Новый импорт
import SyntaxErrorToast from './components/SyntaxErrorToast.vue'
import { useAnalyzerStore } from './store/analyzer'

const analyzerStore = useAnalyzerStore()
</script>

<template>
  <div class="h-screen w-screen bg-slate-900 flex flex-col text-white font-sans overflow-hidden">
    <TheHeader />

    <div class="flex-1 flex overflow-hidden relative">
      <div class="flex-1 border-r border-slate-700 relative">
        <CodeEditor />
      </div>

      <div class="flex-1 relative bg-slate-50 text-black">
        <FlowVisualizer />

        <MemoryInspector v-if="analyzerStore.isAnalyzed" />

        <ExecutionControls v-if="analyzerStore.isAnalyzed" />

        <div v-if="analyzerStore.isLoading" class="absolute inset-0 flex items-center justify-center bg-slate-50/80 backdrop-blur-sm z-50 flex-col gap-4">
          <div class="w-12 h-12 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin"></div>
          <p class="text-indigo-600 font-bold animate-pulse tracking-widest uppercase">Building AST...</p>
        </div>
      </div>
    </div>

    <SyntaxErrorToast />
  </div>
</template>
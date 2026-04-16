<script setup lang="ts">
import { useAnalyzerStore } from '../store/analyzer'

const analyzerStore = useAnalyzerStore()
</script>

<template>
  <Transition name="slide-up">
    <div v-if="analyzerStore.errorMessage" class="fixed bottom-6 right-6 z-[9999] max-w-xl rounded-xl border border-red-500 bg-red-950 p-4 text-red-100 shadow-2xl">
      <div class="flex items-center gap-2 font-bold text-red-200">
        <span>⚠️</span> Syntax Error
      </div>
      <div class="mt-2 whitespace-pre-wrap font-mono text-xs opacity-90">{{ analyzerStore.errorMessage }}</div>
      <button @click="analyzerStore.errorMessage = null" class="mt-4 w-full rounded-md border border-red-400/30 px-3 py-1.5 text-xs font-semibold hover:bg-red-900 transition">
        Dismiss
      </button>
    </div>
  </Transition>
</template>

<style scoped>
.slide-up-enter-active, .slide-up-leave-active { transition: all 0.3s ease; }
.slide-up-enter-from, .slide-up-leave-to { transform: translateY(20px); opacity: 0; }
</style>
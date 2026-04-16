<script setup lang="ts">
import { useAnalyzerStore } from '../store/analyzer'

const analyzerStore = useAnalyzerStore()
</script>

<template>
  <div class="absolute bottom-6 right-6 z-30 flex gap-3">
    <button
        class="rounded-lg bg-slate-800 px-5 py-2.5 text-sm font-bold text-white shadow-xl transition hover:bg-slate-700 active:scale-95"
        @click="analyzerStore.reset()"
    >
      Reset
    </button>
    <button
        class="rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-bold text-white shadow-xl transition hover:bg-indigo-500 active:scale-95 disabled:opacity-40 disabled:scale-100 disabled:cursor-not-allowed"
        @click="analyzerStore.stepBackward()"
        :disabled="analyzerStore.currentStepIndex === 0"
    >
      Step Back
    </button>
    <button
        class="rounded-lg bg-green-600 px-5 py-2.5 text-sm font-bold text-white shadow-xl transition hover:bg-green-500 active:scale-95 disabled:opacity-40 disabled:scale-100 disabled:cursor-not-allowed"
        @click="analyzerStore.stepForward()"
        :disabled="analyzerStore.currentStepIndex === analyzerStore.executionTrace.length - 1 || analyzerStore.isExceptionStalled"
    >
      Step Forward
    </button>
  </div>
</template>
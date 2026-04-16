<script setup lang="ts">
import { useAnalyzerStore } from '../store/analyzer'

const analyzerStore = useAnalyzerStore()

const isErrorKey = (key: string | number) => String(key).startsWith('__error')
const isArray = (obj: any) => obj?.className?.endsWith('[]')
</script>

<template>
  <div class="absolute top-10 right-4 z-10 bg-white/90 backdrop-blur border border-slate-200 rounded-xl p-4 shadow-xl min-w-[220px]">
    <section>
      <h3 class="text-xs font-bold text-slate-500 uppercase tracking-wider mb-2 border-b pb-1">Stack</h3>
      <div v-if="!Object.keys(analyzerStore.visibleMemory).length" class="text-sm text-slate-400 italic text-center py-2">Empty</div>
      <div v-else class="space-y-1">
        <div v-for="(val, key) in analyzerStore.visibleMemory" :key="key"
             :class="isErrorKey(key) ? 'bg-red-50 border border-red-200 p-2 rounded' : 'flex justify-between bg-slate-100 rounded px-2 py-1'">
          <template v-if="isErrorKey(key)">
            <div class="text-[10px] font-bold text-red-600 uppercase">Runtime Error</div>
            <div class="text-sm font-mono text-red-800">{{ val }}</div>
          </template>
          <template v-else>
            <span class="font-mono text-sm text-indigo-600 font-semibold">{{ key }}</span>
            <span class="font-mono text-sm text-slate-800">{{ val }}</span>
          </template>
        </div>
      </div>
    </section>

    <section class="mt-4">
      <h3 class="text-xs font-bold text-slate-500 uppercase tracking-wider mb-2 border-b pb-1">Heap</h3>
      <div v-if="!analyzerStore.currentHeap.length" class="text-sm text-slate-400 italic text-center py-2">Empty</div>
      <div v-else class="space-y-2">
        <div v-for="obj in analyzerStore.currentHeap" :key="obj.id"
             class="rounded-md border border-sky-300 bg-sky-50 p-2 text-xs">
          <div class="font-mono font-bold text-sky-700">
            <template v-if="isArray(obj)">
              {{ obj.className }} (#{{ obj.id }}) <br/>
              <span class="text-slate-500">[{{ (obj.values ?? []).join(', ') }}]</span>
            </template>
            <template v-else>
              <div class="mb-1">{{ obj.className }} (#{{ obj.id }})</div>
              <div
                  v-if="obj.fields && Object.keys(obj.fields).length > 0"
                  class="pl-2 border-l-2 border-sky-300/50 space-y-0.5 mt-1"
              >
                <div v-for="(val, key) in obj.fields" :key="key" class="text-[10px] font-mono">
                  <span class="text-slate-500">{{ key }}:</span>
                  <span class="text-indigo-600 font-bold ml-1">{{ val }}</span>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </section>

    <div class="mt-4 pt-2 border-t text-[10px] text-slate-400 text-center uppercase tracking-tighter">
      Step {{ analyzerStore.currentStepIndex }} / {{ analyzerStore.executionTrace.length - 1 }}
    </div>
  </div>
</template>

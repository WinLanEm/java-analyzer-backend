<script setup lang="ts">
import { ref, shallowRef, watch, computed } from 'vue'
import { Editor } from '@guolao/vue-monaco-editor'
import { Background } from '@vue-flow/background'
import { VueFlow } from '@vue-flow/core'
import type { Edge, Node } from '@vue-flow/core'
import type * as monaco from 'monaco-editor'

import { useAnalyzerStore } from './store/analyzer'
import { applyDagreLayout } from './utils/layout'
import type { AnalyzerNode } from './types'

const analyzerStore = useAnalyzerStore()

// --- 1. ЛОГИКА MONACO EDITOR ---
const monacoEditor = shallowRef<monaco.editor.IStandaloneCodeEditor | null>(null)
const activeDecorationIds = shallowRef<string[]>([])

function updateActiveLineHighlight(activeNodeId: string | null) {
  if (!monacoEditor.value) return

  if (!activeNodeId) {
    activeDecorationIds.value = monacoEditor.value.deltaDecorations(activeDecorationIds.value, [])
    return
  }

  const activeNode = analyzerStore.nodes.find((node) => node.id === activeNodeId)
  if (!activeNode) {
    activeDecorationIds.value = monacoEditor.value.deltaDecorations(activeDecorationIds.value, [])
    return
  }

  activeDecorationIds.value = monacoEditor.value.deltaDecorations(activeDecorationIds.value, [{
    range: {
      startLineNumber: activeNode.line,
      startColumn: 1,
      endLineNumber: activeNode.line,
      endColumn: 1,
    },
    options: {
      isWholeLine: true,
      className: 'analyzer-active-line',
      glyphMarginClassName: 'analyzer-active-line-glyph',
    },
  }])

  monacoEditor.value.revealLineInCenter(activeNode.line)
}

function handleEditorMount(editor: monaco.editor.IStandaloneCodeEditor) {
  monacoEditor.value = editor
  updateActiveLineHighlight(analyzerStore.activeNodeId)
}

// --- 2. ЛОГИКА ГРАФА VUE FLOW ---
const flowNodes = ref<Node[]>([])

const flowEdges = computed<Edge[]>(() => {
  return analyzerStore.edges.map((edge) => {
    const isActive = analyzerStore.activeNodeId !== null &&
        (edge.source === analyzerStore.activeNodeId || edge.target === analyzerStore.activeNodeId)
    return {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label,
      animated: isActive,
      style: { stroke: isActive ? '#22c55e' : '#64748b', strokeWidth: isActive ? 3 : 2 },
      labelStyle: { fill: isActive ? '#15803d' : '#475569', fontWeight: 600 },
    }
  })
})

// Теперь мы строим граф не при загрузке страницы, а когда пришли данные от "бэкенда"
watch(
    () => analyzerStore.isAnalyzed,
    (isAnalyzed) => {
      if (isAnalyzed) {
        const mappedNodes: Node[] = analyzerStore.nodes.map((node) => ({
          id: node.id,
          type: node.type,
          data: node,
          position: { x: 0, y: 0 }
        }))

        const mappedEdges: Edge[] = analyzerStore.edges.map((edge) => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          label: edge.label
        }))

        flowNodes.value = applyDagreLayout(mappedNodes, mappedEdges) as Node[]
      } else {
        flowNodes.value = []
      }
    }
)

watch(
    () => analyzerStore.activeNodeId,
    (activeNodeId) => {
      updateActiveLineHighlight(activeNodeId)
    }
)
</script>

<template>
  <div class="h-screen w-screen bg-slate-900 flex flex-col text-white font-sans overflow-hidden">
    <div class="h-14 border-b border-slate-700 flex items-center justify-between px-6 shrink-0 bg-slate-800">
      <div class="font-bold text-lg tracking-wide flex items-center gap-2">
        <span class="text-indigo-400">⚡</span> Code Analyzer
      </div>
      <button
          @click="analyzerStore.analyze()"
          :disabled="analyzerStore.isLoading"
          class="bg-indigo-600 hover:bg-indigo-500 text-white px-5 py-2 rounded-md font-semibold text-sm transition shadow-lg disabled:opacity-50 flex items-center gap-2"
      >
        <span v-if="analyzerStore.isLoading" class="animate-spin text-lg leading-none">⏳</span>
        {{ analyzerStore.isLoading ? 'Analyzing...' : 'Run Analysis' }}
      </button>
    </div>

    <div class="flex-1 flex overflow-hidden">
      <div class="flex-1 relative border-r border-slate-700">
        <div class="absolute inset-0">
          <Editor
              v-model:value="analyzerStore.code"
              @mount="handleEditorMount"
              theme="vs-dark"
              language="java"
              :options="{ automaticLayout: true, glyphMargin: true, minimap: { enabled: false } }"
          />
        </div>
      </div>

      <div class="flex-1 relative bg-slate-50 text-black">

        <div v-if="!analyzerStore.isAnalyzed && !analyzerStore.isLoading" class="absolute inset-0 flex items-center justify-center text-slate-400 flex-col gap-3">
          <div class="text-4xl">🚀</div>
          <p class="font-medium text-lg">Click "Run Analysis" to start</p>
        </div>

        <div v-if="analyzerStore.isLoading" class="absolute inset-0 flex items-center justify-center bg-slate-50/80 backdrop-blur-sm z-50 flex-col gap-4">
          <div class="w-12 h-12 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin"></div>
          <p class="text-indigo-600 font-bold animate-pulse tracking-widest">BUILDING AST...</p>
        </div>

        <template v-if="analyzerStore.isAnalyzed">
          <div class="absolute top-4 right-4 z-10 bg-white/90 backdrop-blur border border-slate-200 rounded-xl p-4 shadow-xl min-w-[200px]">
            <h3 class="text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Memory Stack</h3>
            <div v-if="Object.keys(analyzerStore.currentMemory).length === 0" class="text-sm text-slate-400 italic">
              Empty
            </div>
            <div v-else class="space-y-1">
              <div v-for="(value, key) in analyzerStore.currentMemory" :key="key" class="flex justify-between items-center bg-slate-100 rounded px-2 py-1">
                <span class="font-mono text-sm text-indigo-600 font-semibold">{{ key }}</span>
                <span class="font-mono text-sm text-slate-800">{{ value }}</span>
              </div>
            </div>
            <div class="mt-3 text-xs text-slate-400 border-t pt-2 text-center">
              Step {{ analyzerStore.currentStepIndex }} / {{ analyzerStore.executionTrace.length - 1 }}
            </div>
          </div>

          <div class="absolute inset-0">
            <VueFlow v-if="flowNodes.length > 0" :nodes="flowNodes" :edges="flowEdges" :fit-view-on-init="true">
              <Background pattern-color="#94a3b8" />

              <template #node-action="props">
                <div :class="[
                  'bg-white border rounded-xl px-4 py-3 shadow-sm min-w-[200px] text-center transition-all duration-300',
                  props.id === analyzerStore.activeNodeId ? 'ring-4 ring-indigo-500 border-indigo-500 scale-105 shadow-lg' : 'border-slate-300'
                ]">
                  <div class="text-xs font-bold text-slate-500 uppercase mb-1">Action</div>
                  <div class="font-bold text-slate-900">{{ (props.data as AnalyzerNode).label }}</div>
                  <div class="text-xs text-slate-500 mt-1">Line {{ (props.data as AnalyzerNode).line }}</div>
                </div>
              </template>

              <template #node-condition="props">
                <div :class="[
                  'bg-amber-100 border rounded-xl px-4 py-3 shadow-sm min-w-[200px] text-center transition-all duration-300',
                  props.id === analyzerStore.activeNodeId ? 'ring-4 ring-indigo-500 border-indigo-500 scale-105 shadow-lg' : 'border-amber-400'
                ]">
                  <div class="text-xs font-bold text-amber-700 uppercase mb-1">Condition</div>
                  <div class="font-bold text-slate-900">{{ (props.data as AnalyzerNode).label }}</div>
                  <div class="text-xs text-slate-500 mt-1">Line {{ (props.data as AnalyzerNode).line }}</div>
                </div>
              </template>
            </VueFlow>
          </div>

          <div class="absolute bottom-4 right-4 z-10 flex gap-3">
            <button
                class="rounded-lg bg-slate-800 px-4 py-2 text-sm font-semibold text-white shadow-lg transition hover:bg-slate-700"
                @click="analyzerStore.reset()"
            >
              Reset
            </button>
            <button
                class="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-lg transition hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed"
                @click="analyzerStore.stepBackward()"
                :disabled="analyzerStore.currentStepIndex === 0"
            >
              Step Back
            </button>
            <button
                class="rounded-lg bg-green-600 px-4 py-2 text-sm font-semibold text-white shadow-lg transition hover:bg-green-500 disabled:opacity-50 disabled:cursor-not-allowed"
                @click="analyzerStore.stepForward()"
                :disabled="analyzerStore.currentStepIndex === analyzerStore.executionTrace.length - 1"
            >
              Step Forward
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style>
.analyzer-active-line {
  background: rgba(34, 197, 94, 0.18);
}
.analyzer-active-line-glyph {
  background: #22c55e;
  border-radius: 9999px;
  margin-left: 6px;
  width: 10px !important;
  height: 10px !important;
}
</style>
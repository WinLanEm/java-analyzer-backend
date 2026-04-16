<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { Editor } from '@guolao/vue-monaco-editor'
import { Background } from '@vue-flow/background'
import { Handle, Position, VueFlow } from '@vue-flow/core'
import type { Edge, Node } from '@vue-flow/core'
import type * as monaco from 'monaco-editor'

import { useAnalyzerStore } from './store/analyzer'
import { applyDagreLayout } from './utils/layout'
import type { AnalyzerNode } from './types'

const analyzerStore = useAnalyzerStore()
let errorToastTimer: ReturnType<typeof setTimeout> | null = null

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

const hasRuntimeError = computed(() => {
  return Object.keys(analyzerStore.currentMemory).some((key) => key.startsWith('__error'))
})

const flowEdges = computed<Edge[]>(() => {
  return analyzerStore.edges.map((edge) => {
    const isActive = analyzerStore.activeNodeId !== null &&
        (edge.source === analyzerStore.activeNodeId || edge.target === analyzerStore.activeNodeId)
    const sourceHandle = edge.isBackEdge ? 'left' : edge.label === 'false' ? 'right' : 'bottom'
    const targetHandle = edge.isBackEdge ? 'left' : 'top'

    return {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      type: 'smoothstep',
      sourceHandle,
      targetHandle,
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
          type: 'smoothstep',
          isBackEdge: edge.isBackEdge,
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

watch(
    () => analyzerStore.errorMessage,
    (errorMessage) => {
      if (errorToastTimer) {
        clearTimeout(errorToastTimer)
        errorToastTimer = null
      }

      if (errorMessage) {
        errorToastTimer = setTimeout(() => {
          analyzerStore.errorMessage = null
          errorToastTimer = null
        }, 10000)
      }
    }
)

onBeforeUnmount(() => {
  if (errorToastTimer) {
    clearTimeout(errorToastTimer)
  }
})

function isErrorMemoryKey(key: string | number): boolean {
  return String(key).startsWith('__error')
}

function isThrowNode(node: AnalyzerNode): boolean {
  return node.label.toLowerCase().includes('throw') || node.isError === true
}
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
              <div
                  v-for="(value, key) in analyzerStore.currentMemory"
                  :key="key"
                  :class="[
                    isErrorMemoryKey(key)
                      ? 'rounded-md border border-red-500 bg-red-900/90 p-3 text-red-100 shadow-lg'
                      : 'flex justify-between items-center bg-slate-100 rounded px-2 py-1'
                  ]"
              >
                <template v-if="isErrorMemoryKey(key)">
                  <div class="text-xs font-bold uppercase tracking-wider text-red-300">Runtime Error</div>
                  <div class="mt-1 font-mono text-sm text-red-100">{{ value }}</div>
                </template>
                <template v-else>
                  <span class="font-mono text-sm text-indigo-600 font-semibold">{{ key }}</span>
                  <span class="font-mono text-sm text-slate-800">{{ value }}</span>
                </template>
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
                  'border rounded-xl px-4 py-3 shadow-sm min-w-[200px] text-center transition-all duration-300',
                  isThrowNode(props.data as AnalyzerNode) ? 'bg-red-100 border-red-500' : 'bg-white border-slate-300',
                  props.id === analyzerStore.activeNodeId && hasRuntimeError ? 'runtime-error-node scale-105 shadow-lg' : '',
                  props.id === analyzerStore.activeNodeId && !hasRuntimeError ? 'ring-4 ring-indigo-500 border-indigo-500 scale-105 shadow-lg' : ''
                ]">
                  <Handle id="top" type="target" :position="Position.Top" class="opacity-0" />
                  <Handle id="bottom" type="source" :position="Position.Bottom" class="opacity-0" />
                  <Handle id="left" type="source" :position="Position.Left" class="opacity-0" />
                  <Handle id="left" type="target" :position="Position.Left" class="opacity-0" />
                  <Handle id="right" type="source" :position="Position.Right" class="opacity-0" />
                  <Handle id="right" type="target" :position="Position.Right" class="opacity-0" />
                  <div :class="[
                    'text-xs font-bold uppercase mb-1',
                    isThrowNode(props.data as AnalyzerNode) ? 'text-red-700' : 'text-slate-500'
                  ]">Action</div>
                  <div class="font-bold text-slate-900">{{ (props.data as AnalyzerNode).label }}</div>
                  <div class="text-xs text-slate-500 mt-1">Line {{ (props.data as AnalyzerNode).line }}</div>
                </div>
              </template>

              <template #node-condition="props">
                <div :class="[
                  'bg-amber-100 border rounded-xl px-4 py-3 shadow-sm min-w-[200px] text-center transition-all duration-300',
                  props.id === analyzerStore.activeNodeId && hasRuntimeError ? 'runtime-error-node scale-105 shadow-lg' : '',
                  props.id === analyzerStore.activeNodeId && !hasRuntimeError ? 'ring-4 ring-indigo-500 border-indigo-500 scale-105 shadow-lg' : '',
                  props.id !== analyzerStore.activeNodeId ? 'border-amber-400' : ''
                ]">
                  <Handle id="top" type="target" :position="Position.Top" class="opacity-0" />
                  <Handle id="bottom" type="source" :position="Position.Bottom" class="opacity-0" />
                  <Handle id="left" type="source" :position="Position.Left" class="opacity-0" />
                  <Handle id="left" type="target" :position="Position.Left" class="opacity-0" />
                  <Handle id="right" type="source" :position="Position.Right" class="opacity-0" />
                  <Handle id="right" type="target" :position="Position.Right" class="opacity-0" />
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

    <div
        v-if="analyzerStore.errorMessage"
        class="fixed bottom-6 right-6 z-[9999] max-w-xl rounded-xl border border-red-500 bg-red-950 p-4 text-red-100 shadow-2xl"
    >
      <div class="font-bold text-red-200">Syntax Error</div>
      <div class="mt-2 whitespace-pre-wrap font-mono text-sm">{{ analyzerStore.errorMessage }}</div>
      <button
          class="mt-4 rounded-md border border-red-400 px-3 py-1 text-sm font-semibold text-red-100 transition hover:bg-red-900"
          @click="analyzerStore.errorMessage = null"
      >
        Close
      </button>
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
.runtime-error-node {
  border-color: #ef4444;
  box-shadow: 0 0 0 4px rgba(239, 68, 68, 0.65);
  animation: runtime-error-pulse 1.2s ease-in-out infinite;
}
@keyframes runtime-error-pulse {
  0%, 100% {
    box-shadow: 0 0 0 4px rgba(239, 68, 68, 0.55);
  }
  50% {
    box-shadow: 0 0 0 8px rgba(239, 68, 68, 0.2);
  }
}
</style>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { VueFlow, Handle, Position, type Node, type Edge } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { useAnalyzerStore } from '../store/analyzer'
import { applyDagreLayout } from '../utils/layout'
import type { AnalyzerNode } from '../types'

const analyzerStore = useAnalyzerStore()
const flowNodes = ref<Node[]>([])

const hasRuntimeError = computed(() =>
    Object.keys(analyzerStore.currentMemory).some(key => key.startsWith('__error'))
)

const flowEdges = computed<Edge[]>(() => {
  return analyzerStore.edges.map(edge => {
    const isActive = analyzerStore.activeNodeId !== null &&
        (edge.source === analyzerStore.activeNodeId || edge.target === analyzerStore.activeNodeId)

    return {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      type: 'smoothstep',
      sourceHandle: edge.isBackEdge ? 'left' : edge.label === 'false' ? 'right' : 'bottom',
      targetHandle: edge.isBackEdge ? 'left' : 'top',
      label: edge.label,
      animated: isActive,
      style: { stroke: isActive ? '#22c55e' : '#64748b', strokeWidth: isActive ? 3 : 2 },
      labelStyle: { fill: isActive ? '#15803d' : '#475569', fontWeight: 600 },
    }
  })
})

function rebuildFlowLayout() {
  if (!analyzerStore.isAnalyzed) {
    flowNodes.value = []
    return
  }

  const mappedNodes: Node[] = analyzerStore.nodes.map(node => ({
    id: node.id,
    type: node.type,
    data: node,
    position: { x: 0, y: 0 }
  }))

  const mappedEdges: Edge[] = analyzerStore.edges.map(edge => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    type: 'smoothstep',
    isBackEdge: edge.isBackEdge,
    label: edge.label
  }))

  flowNodes.value = applyDagreLayout(mappedNodes, mappedEdges) as Node[]
}

watch([() => analyzerStore.isAnalyzed, () => analyzerStore.activeMethodSignature], rebuildFlowLayout)

const isThrowNode = (node: AnalyzerNode) =>
    node.label.toLowerCase().includes('throw') || node.isError === true
</script>

<template>
  <div class="absolute inset-0 flex flex-col">
    <div v-if="analyzerStore.isAnalyzed" class="sticky top-0 z-20 bg-slate-800/80 backdrop-blur text-sm py-2 px-4 border-b border-slate-700 flex items-center gap-2 text-slate-100">
      <span class="font-semibold text-sky-300 text-[10px] uppercase">▣ Class</span>
      <span class="font-mono text-xs">{{ analyzerStore.currentStepData.currentContext?.className ?? 'Unknown' }}</span>
      <span class="text-slate-500 mx-1">/</span>
      <span class="font-semibold text-emerald-300 text-[10px] uppercase">ƒ Method</span>
      <span class="font-mono text-xs">{{ analyzerStore.currentStepData.currentContext?.methodName ?? 'Unknown' }}</span>
    </div>

    <div v-if="!analyzerStore.isAnalyzed && !analyzerStore.isLoading" class="flex-1 flex items-center justify-center text-slate-400 flex-col gap-3">
      <div class="text-5xl animate-bounce">🚀</div>
      <p class="font-medium text-lg">Enter code and click "Run Analysis"</p>
    </div>

    <VueFlow v-else class="flex-1" :nodes="flowNodes" :edges="flowEdges" :fit-view-on-init="true">
      <Background pattern-color="#cbd5e1" :gap="20" />

      <template #node-action="props">
        <div :class="[
          'border rounded-xl px-4 py-3 shadow-sm min-w-[200px] text-center transition-all duration-300',
          isThrowNode(props.data) ? 'bg-red-50 border-red-500' : 'bg-white border-slate-300',
          props.id === analyzerStore.activeNodeId ? (hasRuntimeError ? 'runtime-error-node' : 'ring-4 ring-indigo-500 border-indigo-500 scale-105') : ''
        ]">
          <Handle id="top" type="target" :position="Position.Top" class="opacity-0" />
          <Handle id="bottom" type="source" :position="Position.Bottom" class="opacity-0" />
          <Handle id="left" type="source" :position="Position.Left" class="opacity-0" />
          <Handle id="left" type="target" :position="Position.Left" class="opacity-0" />
          <Handle id="right" type="source" :position="Position.Right" class="opacity-0" />
          <Handle id="right" type="target" :position="Position.Right" class="opacity-0" />

          <div :class="['text-[10px] font-bold uppercase mb-1', isThrowNode(props.data) ? 'text-red-700' : 'text-slate-400']">Action</div>
          <div class="font-bold text-slate-900 text-sm">{{ (props.data as AnalyzerNode).label }}</div>
          <div class="text-[10px] text-slate-400 mt-1">Line {{ (props.data as AnalyzerNode).line }}</div>
        </div>
      </template>

      <template #node-condition="props">
        <div :class="[
          'bg-amber-50 border rounded-xl px-4 py-3 shadow-sm min-w-[200px] text-center transition-all duration-300',
          props.id === analyzerStore.activeNodeId ? (hasRuntimeError ? 'runtime-error-node' : 'ring-4 ring-indigo-500 border-indigo-500 scale-105') : 'border-amber-400'
        ]">
          <Handle id="top" type="target" :position="Position.Top" class="opacity-0" />
          <Handle id="bottom" type="source" :position="Position.Bottom" class="opacity-0" />
          <Handle id="left" type="source" :position="Position.Left" class="opacity-0" />
          <Handle id="left" type="target" :position="Position.Left" class="opacity-0" />
          <Handle id="right" type="source" :position="Position.Right" class="opacity-0" />
          <Handle id="right" type="target" :position="Position.Right" class="opacity-0" />

          <div class="text-[10px] font-bold text-amber-700 uppercase mb-1">Condition</div>
          <div class="font-bold text-slate-900 text-sm">{{ (props.data as AnalyzerNode).label }}</div>
          <div class="text-[10px] text-slate-500 mt-1">Line {{ (props.data as AnalyzerNode).line }}</div>
        </div>
      </template>
    </VueFlow>
  </div>
</template>
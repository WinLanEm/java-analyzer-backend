import { defineStore } from 'pinia'
import type { AnalyzerEdge, AnalyzerNode, BackendPayload, ExecutionStep, GraphDTO, ObjectInstance } from '../types'

const API_URL = 'http://localhost:8080'  //БЭКЕНД

import { examples } from '../constants/examples'
const defaultCode = examples['Basic Loop']

interface AnalyzerState {
  code: string
  graphs: Record<string, GraphDTO>
  nodes: AnalyzerNode[]
  edges: AnalyzerEdge[]
  executionTrace: ExecutionStep[]
  currentStepIndex: number
  isLoading: boolean
  isAnalyzed: boolean
  errorMessage: string | null
  selectedExample: keyof typeof examples
}

export const useAnalyzerStore = defineStore('analyzer', {
  state: (): AnalyzerState => ({
    code: defaultCode,
    graphs: {},
    nodes: [],
    edges: [],
    executionTrace: [],
    currentStepIndex: 0,
    isLoading: false,
    isAnalyzed: false,
    errorMessage: null,
    selectedExample: 'Basic Loop',
  }),
  
  getters: {
    currentStepData(state): ExecutionStep {
      return (
        state.executionTrace[state.currentStepIndex] ?? {
          step: 0,
          methodSignature: null,
          activeNodeId: null,
          memory: {},
          callStack: [],
          heap: [],
        }
      )
    },
    activeMethodSignature(): string | null {
      return this.currentStepData.methodSignature
    },
    activeNodeId(): string | null {
      return this.currentStepData.activeNodeId
    },
    currentMemory(): Record<string, any> {
      return this.currentStepData.memory
    },
    visibleMemory(): Record<string, any> {
      return Object.fromEntries(
        Object.entries(this.currentStepData.memory).filter(([key]) => key !== 'this' && !key.startsWith('__'))
      )
    },
    currentHeap(): ObjectInstance[] {
      return this.currentStepData.heap ?? []
    },
    isExceptionStalled(): boolean {
      const activeNodeId = this.currentStepData.activeNodeId
      if (activeNodeId === null) {
        return true
      }
      if (activeNodeId === 'runtime-error' || activeNodeId.startsWith('exit')) {
        return true
      }

      const node = this.nodes.find((candidate) => candidate.id === this.currentStepData.activeNodeId)
      return node?.label?.trim().startsWith('EXIT') ?? false
    },
  },
  
  actions: {
    async analyze() {
      this.isLoading = true
      this.isAnalyzed = false
      this.nodes = []
      this.edges = []
      this.graphs = {}
      this.executionTrace = []
      this.errorMessage = null

      try {
        const response = await fetch(`${API_URL}/analyze`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ code: this.code }),
        })

        if (!response.ok) {
          let errorMessage = `Backend returned HTTP ${response.status}`

          try {
            const errorBody = await response.json()
            errorMessage = errorBody.error ?? errorMessage
          } catch {
            // Keep the status-based fallback when the response body is not JSON.
          }

          this.errorMessage = errorMessage
          return
        }

        const data: BackendPayload = await response.json()
        
        this.graphs = data.graphs
        this.executionTrace = data.executionTrace
        this.currentStepIndex = 0
        this.syncActiveGraph()
        
      } catch (error) {
        console.error('Analysis failed:', error)
        alert('Ошибка при анализе кода. Убедитесь что бэкенд запущен на порту 8080')
        this.errorMessage = 'Check backend connection'
        
        this.nodes = []
        this.edges = []
        this.graphs = {}
        this.executionTrace = []
        this.isAnalyzed = false
        
      } finally {
        this.isLoading = false
        this.isAnalyzed = this.errorMessage === null
      }
    },
    
    reset() {
      this.currentStepIndex = 0
      this.syncActiveGraph()
    },
    
    stepForward() {
      if (this.isExceptionStalled) {
        return
      }
      if (this.currentStepIndex < this.executionTrace.length - 1) {
        this.currentStepIndex++
        this.syncActiveGraph()
      }
    },
    
    stepBackward() {
      if (this.currentStepIndex > 0) {
        this.currentStepIndex--
        this.syncActiveGraph()
      }
    },

    syncActiveGraph() {
      const methodSignature = this.currentStepData.methodSignature
      const graph = methodSignature ? this.graphs[methodSignature] : undefined

      this.nodes = graph?.nodes ?? []
      this.edges = graph?.edges ?? []
    },

    loadExample(exampleName: keyof typeof examples) {
      this.selectedExample = exampleName
      this.code = examples[exampleName]
      this.nodes = []
      this.edges = []
      this.graphs = {}
      this.executionTrace = []
      this.currentStepIndex = 0
      this.isAnalyzed = false
      this.errorMessage = null
    },
  },
})

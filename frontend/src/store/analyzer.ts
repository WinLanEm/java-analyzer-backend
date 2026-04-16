import { defineStore } from 'pinia'
import type { AnalyzerEdge, AnalyzerNode, BackendPayload, ExecutionStep } from '../types'

const API_URL = 'http://localhost:8080'  //БЭКЕНД

const defaultCode = `public class Demo {
  public static void main(String[] args) {
    int x = 10;
    
    if (x > 5) {
      x = x - 1;
    } else {
      x = x + 1;
    }
    
    System.out.println(x);
  }
}`

interface AnalyzerState {
  code: string
  nodes: AnalyzerNode[]
  edges: AnalyzerEdge[]
  executionTrace: ExecutionStep[]
  currentStepIndex: number
  isLoading: boolean
  isAnalyzed: boolean
}

export const useAnalyzerStore = defineStore('analyzer', {
  state: (): AnalyzerState => ({
    code: defaultCode,
    nodes: [],
    edges: [],
    executionTrace: [],
    currentStepIndex: 0,
    isLoading: false,
    isAnalyzed: false,
  }),
  
  getters: {
    currentStepData(state): ExecutionStep {
      return (
        state.executionTrace[state.currentStepIndex] ?? {
          step: 0,
          activeNodeId: null,
          memory: {},
        }
      )
    },
    activeNodeId(): string | null {
      return this.currentStepData.activeNodeId
    },
    currentMemory(): Record<string, any> {
      return this.currentStepData.memory
    },
  },
  
  actions: {
    async analyze() {
      this.isLoading = true
      this.isAnalyzed = false
      this.nodes = []
      this.edges = []
      this.executionTrace = []

      try {
        const response = await fetch(`${API_URL}/analyze`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ code: this.code }),
        })

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`)
        }

        const data: BackendPayload = await response.json()
        
        this.nodes = data.graph.nodes
        this.edges = data.graph.edges
        this.executionTrace = data.executionTrace
        this.currentStepIndex = 0
        
      } catch (error) {
        console.error('Analysis failed:', error)
        alert('Ошибка при анализе кода. Убедитесь что бэкенд запущен на порту 8080')
        
        this.nodes = []
        this.edges = []
        this.executionTrace = []
        this.isAnalyzed = false
        
      } finally {
        this.isLoading = false
        this.isAnalyzed = true
      }
    },
    
    reset() {
      this.currentStepIndex = 0
    },
    
    stepForward() {
      if (this.currentStepIndex < this.executionTrace.length - 1) {
        this.currentStepIndex++
      }
    },
    
    stepBackward() {
      if (this.currentStepIndex > 0) {
        this.currentStepIndex--
      }
    },
  },
})
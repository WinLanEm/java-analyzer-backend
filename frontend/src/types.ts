export interface AnalyzerNode {
  id: string
  label: string
  type: 'action' | 'condition'
  line: number
  isError?: boolean
}

export interface AnalyzerEdge {
  id: string
  source: string
  target: string
  label?: string
  isBackEdge?: boolean
}
export interface ExecutionStep {
  step: number
  activeNodeId: string | null
  memory: Record<string, any>
}

export interface BackendPayload {
  graph: {
    nodes: AnalyzerNode[]
    edges: AnalyzerEdge[]
  }
  executionTrace: ExecutionStep[]
}

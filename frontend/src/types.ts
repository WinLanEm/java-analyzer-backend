export interface AnalyzerNode {
  id: string
  label: string
  type: 'action' | 'condition'
  line: number
  isError?: boolean
  isCall?: boolean
  callTarget?: string | null
  callReceiver?: string | null
  callMethodName?: string | null
}

export interface AnalyzerEdge {
  id: string
  source: string
  target: string
  label?: string
  isBackEdge?: boolean
}

export interface GraphDTO {
  nodes: AnalyzerNode[]
  edges: AnalyzerEdge[]
  parameters?: string[]
}

export interface ObjectInstance {
  id: number
  className: string
  values?: any[]
}

export interface CurrentContext {
  className: string | null
  methodName: string | null
}

export interface ExecutionStep {
  step: number
  methodSignature: string | null
  activeNodeId: string | null
  memory: Record<string, any>
  callStack?: string[]
  heap?: ObjectInstance[]
  currentContext?: CurrentContext
}

export interface BackendPayload {
  graphs: Record<string, GraphDTO>
  executionTrace: ExecutionStep[]
}

import dagre from 'dagre'
import { Position } from '@vue-flow/core'

const NODE_WIDTH = 200
const NODE_HEIGHT = 100

export function applyDagreLayout(nodes: any[], edges: any[]) {
  const graph = new dagre.graphlib.Graph()

  graph.setDefaultEdgeLabel(() => ({}))
  graph.setGraph({ rankdir: 'TB' })

  nodes.forEach((node) => {
    graph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT })
  })

  edges.forEach((edge) => {
    graph.setEdge(edge.source, edge.target)
  })

  dagre.layout(graph)

  return nodes.map((node) => {
    const positionedNode = graph.node(node.id)

    return {
      ...node,
      position: {
        x: positionedNode.x - NODE_WIDTH / 2,
        y: positionedNode.y - NODE_HEIGHT / 2,
      },
      sourcePosition: Position.Bottom,
      targetPosition: Position.Top,
    }
  })
}

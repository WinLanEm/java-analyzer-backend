<script setup lang="ts">
import { shallowRef, watch, nextTick, onMounted } from 'vue'
import { Editor } from '@guolao/vue-monaco-editor'
import type * as monaco from 'monaco-editor'
import { useAnalyzerStore } from '../store/analyzer'

const analyzerStore = useAnalyzerStore()
const monacoEditor = shallowRef<monaco.editor.IStandaloneCodeEditor | null>(null)
const activeDecorationIds = shallowRef<string[]>([])

function updateActiveLineHighlight() {
  const editor = monacoEditor.value
  if (!editor) return

  const activeNodeId = analyzerStore.activeNodeId
  if (!activeNodeId) {
    activeDecorationIds.value = editor.deltaDecorations(activeDecorationIds.value, [])
    return
  }

  const activeNode = analyzerStore.nodes.find(n => n.id === activeNodeId)
  if (!activeNode) {
    activeDecorationIds.value = editor.deltaDecorations(activeDecorationIds.value, [])
    return
  }

  activeDecorationIds.value = editor.deltaDecorations(activeDecorationIds.value, [{
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

  editor.revealLineInCenter(activeNode.line)
}

function handleEditorMount(editor: monaco.editor.IStandaloneCodeEditor) {
  monacoEditor.value = editor

  editor.onKeyDown((event) => {
    if (event.browserEvent.key === ' ') {
      event.browserEvent.stopPropagation()
      if (event.browserEvent.defaultPrevented) {
        event.preventDefault()
        editor.trigger('keyboard', 'type', { text: ' ' })
      }
    }
  })

  configureEditor()
}

function configureEditor() {
  const editor = monacoEditor.value
  if (!editor) return

  editor.updateOptions({
    tabSize: 2,
    insertSpaces: true,
    readOnly: false,
    automaticLayout: true,
    glyphMargin: true,
    minimap: { enabled: false }
  })
  editor.getModel()?.updateOptions({ tabSize: 2, insertSpaces: true })
  editor.focus()
}

function handleContainerKeydown(event: KeyboardEvent) {
  if (event.key === ' ') event.stopPropagation()
}

watch(() => analyzerStore.activeNodeId, () => updateActiveLineHighlight())
watch(() => analyzerStore.code, (newCode) => {
  if (monacoEditor.value && monacoEditor.value.getValue() !== newCode) {
    monacoEditor.value.setValue(newCode)
  }
})

defineExpose({ configureEditor })
</script>

<template>
  <div class="absolute inset-0" @keydown="handleContainerKeydown">
    <Editor
        v-model:value="analyzerStore.code"
        @mount="handleEditorMount"
        theme="vs-dark"
        language="java"
        :options="{ readOnly: false }"
    />
  </div>
</template>

<style>
.analyzer-active-line { background: rgba(34, 197, 94, 0.18); }
.analyzer-active-line-glyph {
  background: #22c55e;
  border-radius: 9999px;
  margin-left: 6px;
  width: 10px !important;
  height: 10px !important;
}
</style>
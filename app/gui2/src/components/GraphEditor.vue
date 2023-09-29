<script setup lang="ts">
import CodeEditor from '@/components/CodeEditor.vue'
import ComponentBrowser from '@/components/ComponentBrowser.vue'
import GraphEdge from '@/components/GraphEdge.vue'
import GraphNode from '@/components/GraphNode.vue'
import TopBar from '@/components/TopBar.vue'

import { useGraphStore } from '@/stores/graph'
import { ExecutionContext, useProjectStore } from '@/stores/project'
import type { Rect } from '@/stores/rect'
import { modKey, useWindowEvent } from '@/util/events'
import { useNavigator } from '@/util/navigator'
import type { Opt } from '@/util/opt'
import { Vec2 } from '@/util/vec2'
import type { ContentRange, ExprId } from 'shared/yjsModel'
import { onMounted, onUnmounted, reactive, ref, shallowRef } from 'vue'

const EXECUTION_MODES = ['design', 'live']

const title = ref('Test Project')
const mode = ref('design')
const viewportNode = ref<HTMLElement>()
const navigator = useNavigator(viewportNode)
const graphStore = useGraphStore()
const projectStore = useProjectStore()
const executionCtx = shallowRef<ExecutionContext>()
const componentBrowserVisible = ref(false)
const componentBrowserPosition = ref(Vec2.Zero())

const nodeRects = reactive(new Map<ExprId, Rect>())
const exprRects = reactive(new Map<ExprId, Rect>())

onMounted(async () => {
  const executionCtxPromise = projectStore.createExecutionContextForMain()
  onUnmounted(async () => {
    executionCtx.value = undefined
    const ctx = await executionCtxPromise
    if (ctx != null) ctx.destroy()
  })
  executionCtx.value = (await executionCtxPromise) ?? undefined
})

function updateNodeRect(id: ExprId, rect: Rect) {
  nodeRects.set(id, rect)
}

function updateExprRect(id: ExprId, rect: Rect) {
  exprRects.set(id, rect)
}

const circlePos = ref(Vec2.Zero())

function updateMousePos() {
  const pos = navigator.sceneMousePos
  if (pos != null) {
    circlePos.value = pos
  }
}

function keyboardBusy() {
  return document.activeElement != document.body
}

useWindowEvent('keydown', (e) => {
  if (keyboardBusy()) return
  const pos = navigator.sceneMousePos

  if (modKey(e)) {
    switch (e.key) {
      case 'z':
        projectStore.undoManager.undo()
        break
      case 'y':
        projectStore.undoManager.redo()
        break
    }
  } else {
    switch (e.key) {
      case 'Enter':
        if (pos != null && !componentBrowserVisible.value) {
          componentBrowserPosition.value = pos
          componentBrowserVisible.value = true
        }
        break
      case 'n': {
        if (pos != null) graphStore.createNode(pos, 'hello "world"! 123 + x')
        break
      }
    }
  }
})

function updateNodeContent(id: ExprId, range: ContentRange, content: string) {
  graphStore.replaceNodeSubexpression(id, range, content)
}

function moveNode(id: ExprId, delta: Vec2) {
  const node = graphStore.nodes.get(id)
  if (node == null) return
  const newPosition = node.position.addScaled(delta, 1 / navigator.scale)
  graphStore.setNodePosition(id, newPosition)
}
</script>

<template>
  <div ref="viewportNode" class="viewport" v-on="navigator.events" @mousemove="updateMousePos">
    <svg :viewBox="navigator.viewBox">
      <GraphEdge
        v-for="(edge, index) in graphStore.edges"
        :key="index"
        :edge="edge"
        :node-rects="nodeRects"
        :expr-rects="exprRects"
        :expr-nodes="graphStore.exprNodes"
      />
    </svg>
    <div :style="{ transform: navigator.transform }" class="htmlLayer">
      <GraphNode
        v-for="[id, node] in graphStore.nodes"
        :key="id"
        :node="node"
        @updateRect="updateNodeRect(id, $event)"
        @delete="graphStore.deleteNode(id)"
        @updateExprRect="updateExprRect"
        @updateContent="(range, c) => updateNodeContent(id, range, c)"
        @movePosition="moveNode(id, $event)"
      />
    </div>
    <ComponentBrowser
      v-if="componentBrowserVisible"
      :navigator="navigator"
      :position="componentBrowserPosition"
      @finished="componentBrowserVisible = false"
    />
    <TopBar
      v-model:mode="mode"
      :title="title"
      :modes="EXECUTION_MODES"
      :breadcrumbs="['main', 'ad_analytics']"
      @breadcrumbClick="console.log(`breadcrumb #${$event + 1} clicked.`)"
      @back="console.log('breadcrumbs \'back\' button clicked.')"
      @forward="console.log('breadcrumbs \'forward\' button clicked.')"
      @execute="console.log('\'execute\' button clicked.')"
    />
    <CodeEditor ref="codeEditor" />
  </div>
</template>

<style scoped>
.viewport {
  position: relative;
  contain: layout;
  overflow: clip;
}

svg {
  position: absolute;
  top: 0;
  left: 0;
}

.htmlLayer {
  position: absolute;
  top: 0;
  left: 0;
  width: 0;
  height: 0;
}

.circle {
  position: absolute;
  width: 10px;
  height: 10px;
  border-radius: 5px;
  background-color: purple;
}
</style>
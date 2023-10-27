<script setup lang="ts">
import { nodeEditBindings } from '@/bindings'
import CircularMenu from '@/components/CircularMenu.vue'
import NodeTree from '@/components/GraphEditor/NodeTree.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import LoadingVisualization from '@/components/visualizations/LoadingVisualization.vue'
import { injectGraphSelection } from '@/providers/graphSelection'
import { provideVisualizationConfig } from '@/providers/visualizationConfig'
import type { Node } from '@/stores/graph'
import { useProjectStore } from '@/stores/project'
import { useSuggestionDbStore } from '@/stores/suggestionDatabase'
import {
  DEFAULT_VISUALIZATION_CONFIGURATION,
  DEFAULT_VISUALIZATION_IDENTIFIER,
  useVisualizationStore,
  type Visualization,
} from '@/stores/visualization'
import { colorFromString } from '@/util/colors'
import { usePointer, useResizeObserver } from '@/util/events'
import { methodNameToIcon, typeNameToIcon } from '@/util/getIconName'
import type { Opt } from '@/util/opt'
import { qnJoin, tryQualifiedName } from '@/util/qualifiedName'
import { Rect } from '@/util/rect'
import { unwrap } from '@/util/result'
import { Vec2 } from '@/util/vec2'
import type { ContentRange, ExprId, VisualizationIdentifier } from 'shared/yjsModel'
import { computed, onUpdated, reactive, ref, shallowRef, watch, watchEffect } from 'vue'

const MAXIMUM_CLICK_LENGTH_MS = 300
const MAXIMUM_CLICK_DISTANCE_SQ = 50

const props = defineProps<{
  node: Node
}>()

const emit = defineEmits<{
  updateRect: [rect: Rect]
  updateExprRect: [id: ExprId, rect: Rect]
  updateContent: [updates: [range: ContentRange, content: string][]]
  movePosition: [delta: Vec2]
  setVisualizationId: [id: Opt<VisualizationIdentifier>]
  setVisualizationVisible: [visible: boolean]
  delete: []
  replaceSelection: []
  'update:selected': [selected: boolean]
  outputPortAction: []
}>()

const visualizationStore = useVisualizationStore()
const nodeSelection = injectGraphSelection(true)

const nodeId = computed(() => props.node.rootSpan.astId)
const rootNode = ref<HTMLElement>()
const nodeSize = useResizeObserver(rootNode)
const editableRootNode = ref<HTMLElement>()
const menuVisible = ref(false)

const isSelected = computed(() => nodeSelection?.isSelected(nodeId.value) ?? false)
watch(isSelected, (selected) => {
  menuVisible.value = menuVisible.value && selected
})
const visPreprocessor = ref(DEFAULT_VISUALIZATION_CONFIGURATION)

const isAutoEvaluationDisabled = ref(false)
const isDocsVisible = ref(false)
const isVisualizationVisible = computed(() => props.node.vis?.visible ?? false)

const visualization = shallowRef<Visualization>()

const projectStore = useProjectStore()

const visualizationData = projectStore.useVisualizationData(() => {
  if (!isVisualizationVisible.value || !visPreprocessor.value) return
  return {
    ...visPreprocessor.value,
    expressionId: props.node.rootSpan.astId,
  }
})

watchEffect(() => {
  const size = nodeSize.value
  if (!size.isZero()) {
    emit('updateRect', new Rect(props.node.position, nodeSize.value))
  }
})

const transform = computed(() => {
  let pos = props.node.position
  return `translate(${pos.x}px, ${pos.y}px)`
})

function getRelatedSpanOffset(domNode: globalThis.Node, domOffset: number): number {
  if (domNode instanceof HTMLElement && domOffset == 1) {
    const offsetData = domNode.dataset.spanStart
    const offset = (offsetData != null && parseInt(offsetData)) || 0
    const length = domNode.textContent?.length ?? 0
    return offset + length
  } else if (domNode instanceof Text) {
    const siblingEl = domNode.previousElementSibling
    if (siblingEl instanceof HTMLElement) {
      const offsetData = siblingEl.dataset.spanStart
      if (offsetData != null)
        return parseInt(offsetData) + domOffset + (siblingEl.textContent?.length ?? 0)
    }
    const offsetData = domNode.parentElement?.dataset.spanStart
    if (offsetData != null) return parseInt(offsetData) + domOffset
  }
  return 0
}

function updateRange(range: ContentRange, threhsold: number, adjust: number) {
  range[0] = updateOffset(range[0], threhsold, adjust)
  range[1] = updateOffset(range[1], threhsold, adjust)
}

function updateOffset(offset: number, threhsold: number, adjust: number) {
  return offset >= threhsold ? offset + adjust : offset
}

function updateExprRect(id: ExprId, rect: Rect) {
  emit('updateExprRect', id, rect)
}

interface TextEdit {
  range: ContentRange
  content: string
}

const editsToApply = reactive<TextEdit[]>([])

function editContent(e: Event) {
  e.preventDefault()
  if (!(e instanceof InputEvent)) return

  const domRanges = e.getTargetRanges()
  const ranges = domRanges.map<ContentRange>((r) => {
    return [
      getRelatedSpanOffset(r.startContainer, r.startOffset),
      getRelatedSpanOffset(r.endContainer, r.endOffset),
    ]
  })

  switch (e.inputType) {
    case 'insertText': {
      const content = e.data ?? ''
      for (let range of ranges) {
        if (range[0] != range[1]) {
          editsToApply.push({ range, content: '' })
        }
        editsToApply.push({ range: [range[1], range[1]], content })
      }
      break
    }
    case 'insertFromDrop':
    case 'insertFromPaste': {
      const content = e.dataTransfer?.getData('text/plain')
      if (content != null) {
        for (let range of ranges) {
          editsToApply.push({ range, content })
        }
      }
      break
    }
    case 'deleteByCut':
    case 'deleteWordBackward':
    case 'deleteWordForward':
    case 'deleteContentBackward':
    case 'deleteContentForward':
    case 'deleteByDrag': {
      for (let range of ranges) {
        editsToApply.push({ range, content: '' })
      }
      break
    }
  }
}

watch(editsToApply, () => {
  if (editsToApply.length === 0) return
  saveSelections()
  let edit: TextEdit | undefined
  const updates: [ContentRange, string][] = []
  while ((edit = editsToApply.shift())) {
    const range = edit.range
    const content = edit.content
    const adjust = content.length - (range[1] - range[0])
    editsToApply.forEach((e) => updateRange(e.range, range[1], adjust))
    if (selectionToRecover) {
      selectionToRecover.ranges.forEach((r) => updateRange(r, range[1], adjust))
      if (selectionToRecover.anchor != null) {
        selectionToRecover.anchor = updateOffset(selectionToRecover.anchor, range[1], adjust)
      }
      if (selectionToRecover.focus != null) {
        selectionToRecover.focus = updateOffset(selectionToRecover.focus, range[1], adjust)
      }
    }
    updates.push([range, content])
  }
  emit('updateContent', updates)
})

interface SavedSelections {
  anchor: number | null
  focus: number | null
  ranges: ContentRange[]
}

let selectionToRecover: SavedSelections | null = null

function saveSelections() {
  const root = editableRootNode.value
  const selection = window.getSelection()
  if (root == null || selection == null || !selection.containsNode(root, true)) return
  const ranges: ContentRange[] = Array.from({ length: selection.rangeCount }, (_, i) =>
    selection.getRangeAt(i),
  )
    .filter((r) => r.intersectsNode(root))
    .map((r) => [
      getRelatedSpanOffset(r.startContainer, r.startOffset),
      getRelatedSpanOffset(r.endContainer, r.endOffset),
    ])

  let anchor =
    selection.anchorNode && root.contains(selection.anchorNode)
      ? getRelatedSpanOffset(selection.anchorNode, selection.anchorOffset)
      : null
  let focus =
    selection.focusNode && root.contains(selection.focusNode)
      ? getRelatedSpanOffset(selection.focusNode, selection.focusOffset)
      : null

  selectionToRecover = {
    anchor,
    focus,
    ranges,
  }
}

onUpdated(() => {
  const root = editableRootNode.value

  function findTextNodeAtOffset(offset: number | null): { node: Text; offset: number } | null {
    if (offset == null) return null
    for (let textSpan of root?.querySelectorAll<HTMLSpanElement>('span[data-span-start]') ?? []) {
      if (textSpan.children.length > 0) continue
      const start = parseInt(textSpan.dataset.spanStart ?? '0')
      const text = textSpan.textContent ?? ''
      const end = start + text.length
      if (start <= offset && offset <= end) {
        let remainingOffset = offset - start
        for (let node of textSpan.childNodes) {
          if (node instanceof Text) {
            let length = node.data.length
            if (remainingOffset > length) {
              remainingOffset -= length
            } else {
              return {
                node,
                offset: remainingOffset,
              }
            }
          }
        }
      }
    }
    return null
  }

  if (selectionToRecover != null && editableRootNode.value != null) {
    const saved = selectionToRecover
    selectionToRecover = null
    const selection = window.getSelection()
    if (selection == null) return

    for (let range of saved.ranges) {
      const start = findTextNodeAtOffset(range[0])
      const end = findTextNodeAtOffset(range[1])
      if (start == null || end == null) continue
      let newRange = document.createRange()
      newRange.setStart(start.node, start.offset)
      newRange.setEnd(end.node, end.offset)
      selection.addRange(newRange)
    }
    if (saved.anchor != null || saved.focus != null) {
      const anchor = findTextNodeAtOffset(saved.anchor) ?? {
        node: selection.anchorNode,
        offset: selection.anchorOffset,
      }
      const focus = findTextNodeAtOffset(saved.focus) ?? {
        node: selection.focusNode,
        offset: selection.focusOffset,
      }
      if (anchor.node == null || focus.node == null) return
      selection.setBaseAndExtent(anchor.node, anchor.offset, focus.node, focus.offset)
    }
  }
})

function updatePreprocessor(
  visualizationModule: string,
  expression: string,
  ...positionalArgumentsExpressions: string[]
) {
  visPreprocessor.value = { visualizationModule, expression, positionalArgumentsExpressions }
}

function switchToDefaultPreprocessor() {
  visPreprocessor.value = DEFAULT_VISUALIZATION_CONFIGURATION
}

provideVisualizationConfig({
  fullscreen: false,
  width: null,
  height: 150,
  get types() {
    return visualizationStore.types(expressionInfo.value?.typename)
  },
  get isCircularMenuVisible() {
    return menuVisible.value
  },
  get nodeSize() {
    return nodeSize.value
  },
  get currentType() {
    return props.node.vis ?? DEFAULT_VISUALIZATION_IDENTIFIER
  },
  hide: () => emit('setVisualizationVisible', false),
  updateType: (id) => emit('setVisualizationId', id),
})

watchEffect(async () => {
  if (props.node.vis == null) {
    return
  }

  visualization.value = undefined
  const module = await visualizationStore.get(props.node.vis)
  if (module) {
    if (module.defaultPreprocessor != null) {
      updatePreprocessor(...module.defaultPreprocessor)
    } else {
      switchToDefaultPreprocessor()
    }
    visualization.value = module.default
  }
})

const effectiveVisualization = computed(() => {
  if (!visualization.value || visualizationData.value == null) {
    return LoadingVisualization
  }
  return visualization.value
})

watch(
  () => [isAutoEvaluationDisabled.value, isDocsVisible.value, isVisualizationVisible.value],
  () => {
    rootNode.value?.focus()
  },
)

const editableKeydownHandler = nodeEditBindings.handler({
  selectAll() {
    const element = editableRootNode.value
    const selection = window.getSelection()
    if (element == null || selection == null) return
    const range = document.createRange()
    range.selectNodeContents(element)
    selection.removeAllRanges()
    selection.addRange(range)
  },
})

const startEpochMs = ref(0)
let startEvent: PointerEvent | null = null
let startPos = Vec2.Zero()

const dragPointer = usePointer((pos, event, type) => {
  emit('movePosition', pos.delta)
  switch (type) {
    case 'start': {
      startEpochMs.value = Number(new Date())
      startEvent = event
      startPos = pos.absolute
      event.stopImmediatePropagation()
      break
    }
    case 'stop': {
      if (
        Number(new Date()) - startEpochMs.value <= MAXIMUM_CLICK_LENGTH_MS &&
        startEvent != null &&
        pos.absolute.distanceSquare(startPos) <= MAXIMUM_CLICK_DISTANCE_SQ
      ) {
        nodeSelection?.handleSelectionOf(startEvent, new Set([nodeId.value]))
        menuVisible.value = true
      }
      startEvent = null
      startEpochMs.value = 0
    }
  }
})

const suggestionDbStore = useSuggestionDbStore()

const expressionInfo = computed(() =>
  projectStore.computedValueRegistry.getExpressionInfo(props.node.rootSpan.astId),
)
const outputTypeName = computed(() => expressionInfo.value?.typename ?? 'Unknown')
const executionState = computed(() => expressionInfo.value?.payload.type ?? 'Unknown')
const suggestionEntry = computed(() => {
  const method = expressionInfo.value?.methodCall?.methodPointer
  if (method == null) return undefined
  const moduleName = tryQualifiedName(method.module)
  const methodName = tryQualifiedName(method.name)
  if (!moduleName.ok || !methodName.ok) return undefined
  const qualifiedName = qnJoin(unwrap(moduleName), unwrap(methodName))
  const [id] = suggestionDbStore.entries.nameToId.lookup(qualifiedName)
  if (id == null) return undefined
  return suggestionDbStore.entries.get(id)
})
const icon = computed(() => {
  if (suggestionEntry.value?.iconName) {
    return suggestionEntry.value.iconName
  }
  const methodName = expressionInfo.value?.methodCall?.methodPointer.name
  if (methodName != null) {
    return methodNameToIcon(methodName)
  } else if (outputTypeName.value != null) {
    return typeNameToIcon(outputTypeName.value)
  } else {
    return 'in_out'
  }
})
const color = computed(() =>
  suggestionEntry.value?.groupIndex != null
    ? `var(--group-color-${suggestionDbStore.groups[suggestionEntry.value.groupIndex]?.name})`
    : colorFromString(expressionInfo.value?.typename ?? 'Unknown'),
)

function hoverExpr(id: ExprId | undefined) {
  if (nodeSelection != null) nodeSelection.hoveredExpr = id
}
</script>

<template>
  <div
    ref="rootNode"
    class="GraphNode"
    :style="{
      transform,
      '--node-group-color': color,
    }"
    :class="{
      dragging: dragPointer.dragging,
      selected: nodeSelection?.isSelected(nodeId),
      visualizationVisible: isVisualizationVisible,
      ['executionState-' + executionState]: true,
    }"
  >
    <div class="selection" v-on="dragPointer.events"></div>
    <div class="binding" @pointerdown.stop>
      {{ node.binding }}
    </div>
    <CircularMenu
      v-if="menuVisible"
      v-model:isAutoEvaluationDisabled="isAutoEvaluationDisabled"
      v-model:isDocsVisible="isDocsVisible"
      :isVisualizationVisible="isVisualizationVisible"
      @update:isVisualizationVisible="emit('setVisualizationVisible', $event)"
    />
    <component
      :is="effectiveVisualization"
      v-if="isVisualizationVisible && effectiveVisualization != null"
      :data="visualizationData"
      @update:preprocessor="updatePreprocessor"
    />
    <div class="node" v-on="dragPointer.events">
      <SvgIcon class="icon grab-handle" :name="icon"></SvgIcon>
      <span
        ref="editableRootNode"
        class="editable"
        contenteditable
        spellcheck="false"
        @beforeinput="editContent"
        @keydown="editableKeydownHandler"
        @pointerdown.stop
        @blur="projectStore.stopCapturingUndo()"
        ><NodeTree
          :ast="node.rootSpan"
          :nodeSpanStart="node.rootSpan.span()[0]"
          @updateExprRect="updateExprRect"
          @updateHoveredExpr="hoverExpr($event)"
      /></span>
    </div>
    <div key="outputPort" class="outputPort" @pointerdown="emit('outputPortAction')">
      <svg viewBox="-22 -35 22 38" xmlns="http://www.w3.org/2000/svg" class="outputPortCap">
        <path d="M 0 0 a 19 19 0 0 1 -19 -19" class="outputPortCapLine" />
        <rect height="6" width="6" x="0" y="-3" class="outputPortCapButt" />
      </svg>
      <svg
        viewBox="0 -35 1 38"
        preserveAspectRatio="none"
        xmlns="http://www.w3.org/2000/svg"
        class="outputPortBar"
      >
        <path d="M 0 0 h 1" class="outputPortBarLine" />
      </svg>
      <svg viewBox="0 -35 22 38" xmlns="http://www.w3.org/2000/svg" class="outputPortCap">
        <path d="M 0 0 a 19 19 0 0 0 19 -19" class="outputPortCapLine" />
        <rect height="6" width="6" x="-6" y="-3" class="outputPortCapButt" />
      </svg>
      />
    </div>
    <div class="outputTypeName">{{ outputTypeName }}</div>
  </div>
</template>

<style scoped>
.outputPort {
  width: 100%;
  margin: 0;
  position: fixed;
  top: 0px;
  left: 0px;
  display: flex;
  opacity: 0;
}
.outputPort:hover {
  opacity: 1;
}
.outputPortCap {
  flex: none;
  height: 38px;
  width: 22px;
}
.outputPortCapLine {
  fill: none;
  stroke: var(--node-group-color);
  opacity: 30%;
  stroke-width: 6px;
  stroke-linecap: round;
}
.outputPortCapButt {
  fill: var(--node-group-color);
  opacity: 30%;
}
.outputPortBar {
  height: 38px;
  width: 100%;
}
.outputPortBarLine {
  fill: none;
  stroke: var(--node-group-color);
  opacity: 30%;
  /* 6px + extra width to prevent antialiasing issues:
     The 1px on the top will draw mostly under the node, but will ensure the line meets the node.
     (The 1px on the bottom will be clipped.) */
  stroke-width: 8px;
}
.GraphNode {
  --node-height: 32px;
  --node-border-radius: calc(var(--node-height) * 0.5);
  --output-port-padding: 6px;

  --node-group-color: #357ab9;

  --node-color-primary: color-mix(in oklab, var(--node-group-color) 100%, transparent 0%);
  --node-color-port: color-mix(in oklab, var(--node-color-primary) 75%, white 15%);
  --node-color-error: color-mix(in oklab, var(--node-group-color) 30%, rgba(255, 0, 0) 70%);

  &.executionState-Unknown,
  &.executionState-Pending {
    --node-color-primary: color-mix(in oklab, var(--node-group-color) 60%, #aaa 40%);
  }

  position: absolute;
  border-radius: var(--node-border-radius);
  transition: box-shadow 0.2s ease-in-out;
  ::selection {
    background-color: rgba(255, 255, 255, 20%);
  }

  padding-left: var(--output-port-padding);
  padding-right: var(--output-port-padding);
}

.node {
  position: relative;
  top: 0;
  left: 0;
  caret-shape: bar;
  height: var(--node-height);
  background: var(--node-color-primary);
  background-clip: padding-box;
  border-radius: var(--node-border-radius);
  display: flex;
  flex-direction: row;
  align-items: center;
  white-space: nowrap;
  padding: 4px 8px;
  z-index: 2;
  transition:
    background 0.2s ease,
    outline 0.2s ease;
  outline: 0px solid transparent;
}
.GraphNode .selection {
  position: absolute;
  inset: calc(0px - var(--selected-node-border-width) + var(--output-port-padding));
  --node-current-selection-width: 0px;

  &:before {
    content: '';
    opacity: 0;
    position: absolute;
    border-radius: var(--node-border-radius);
    display: block;
    inset: var(--selected-node-border-width);
    box-shadow: 0 0 0 var(--node-current-selection-width) var(--node-color-primary);

    transition:
      box-shadow 0.2s ease-in-out,
      opacity 0.2s ease-in-out;
  }
}

.GraphNode:is(:hover, .selected) .selection:before,
.GraphNode .selection:hover:before {
  --node-current-selection-width: var(--selected-node-border-width);
}

.GraphNode .selection:hover:before {
  opacity: 0.15;
}
.GraphNode.selected .selection:before {
  opacity: 0.2;
}

.GraphNode.selected .selection:hover:before {
  opacity: 0.3;
}
.binding {
  user-select: none;
  margin-right: 10px;
  color: black;
  position: absolute;
  right: 100%;
  top: 50%;
  transform: translateY(-50%);
  opacity: 0;
  transition: opacity 0.2s ease-in-out;
}

.GraphNode .selection:hover + .binding,
.GraphNode.selected .binding {
  opacity: 1;
}

.editable {
  outline: none;
  height: 24px;
  display: inline-flex;
  align-items: center;
}

.container {
  position: relative;
  display: flex;
  gap: 4px;
}

.grab-handle {
  color: white;
  margin-right: 10px;
}

.CircularMenu {
  z-index: 1;
}

.outputTypeName {
  user-select: none;
  position: absolute;
  left: 50%;
  top: 110%;
  transform: translateX(-50%);
  opacity: 0;
  transition: opacity 0.3s ease-in-out;
  pointer-events: none;
  z-index: 10;
  color: var(--node-color-primary);
}

.GraphNode:hover .outputTypeName {
  opacity: 1;
}
</style>
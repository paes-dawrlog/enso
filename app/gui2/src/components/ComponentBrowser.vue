<script setup lang="ts">
import { makeComponentList, type Component } from '@/components/ComponentBrowser/component'
import { Filtering } from '@/components/ComponentBrowser/filtering'
import { default as SvgIcon } from '@/components/SvgIcon.vue'
import { default as ToggleIcon } from '@/components/ToggleIcon.vue'
import { useSuggestionDbStore } from '@/stores/suggestionDatabase'
import { useApproach } from '@/util/animation'
import { useResizeObserver } from '@/util/events'
import type { useNavigator } from '@/util/navigator'
import { Vec2 } from '@/util/vec2'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

const ITEM_SIZE = 32
const TOP_BAR_HEIGHT = 32

const props = defineProps<{
  position: Vec2
  navigator: ReturnType<typeof useNavigator>
}>()

const emit = defineEmits<{
  (e: 'finished'): void
}>()

onMounted(() => {
  if (inputField.value != null) {
    inputField.value.focus({ preventScroll: true })
    selectLastAfterRefresh()
  }
})

// === Position ===

const transform = computed(() => {
  const nav = props.navigator
  const pos = props.position
  return `${nav.transform} translate(${pos.x}px, ${pos.y}px) scale(${
    1 / nav.scale
  }) translateY(-100%)`
})

// === Input and Filtering ===

const cbRoot = ref<HTMLElement>()
const inputField = ref<HTMLElement>()
const inputText = ref('')
const filterFlags = ref({ showUnstable: false, showLocal: false })

const currentFiltering = computed(() => {
  const input = inputText.value
  const pathPatternSep = inputText.value.lastIndexOf('.')
  return new Filtering({
    pattern: input.substring(pathPatternSep + 1),
    qualifiedNamePattern: input.substring(0, pathPatternSep),
    ...filterFlags.value,
  })
})

watch(currentFiltering, selectLastAfterRefresh)

function handleDefocus(e: FocusEvent) {
  const stillInside =
    cbRoot.value != null &&
    e.relatedTarget instanceof Node &&
    cbRoot.value.contains(e.relatedTarget)
  if (stillInside) {
    if (inputField.value != null) {
      inputField.value.focus({ preventScroll: true })
    }
  } else {
    emit('finished')
  }
}

// === Components List and Positions ===

const suggestionDbStore = useSuggestionDbStore()

const components = computed(() => {
  return makeComponentList(suggestionDbStore.entries, currentFiltering.value)
})

const visibleComponents = computed(() => {
  if (scroller.value == null) return []
  const scrollPosition = animatedScrollPosition.value
  const topmostVisible = componentAtY(scrollPosition)
  const bottommostVisible = Math.max(
    0,
    componentAtY(animatedScrollPosition.value + scrollerSize.value.y),
  )
  return components.value.slice(bottommostVisible, topmostVisible + 1).map((component, i) => {
    return { component, index: i + bottommostVisible }
  })
})

function componentPos(index: number) {
  return listContentHeight.value - (index + 1) * ITEM_SIZE
}

function componentAtY(pos: number) {
  return Math.floor((listContentHeight.value - pos) / ITEM_SIZE)
}

function componentStyle(index: number) {
  return { transform: `translateY(${componentPos(index)}px)` }
}

function componentColor(component: Component): string {
  // TODO[ao]: A set of default color should be specified in css, see #7785.
  return suggestionDbStore.groups[component.group ?? -1]?.color ?? '#006b8a'
}

// === Highlight ===

const selected = ref<number | null>(null)
const highlightPosition = ref(0)
const selectedPosition = computed(() =>
  selected.value != null ? componentPos(selected.value) : null,
)
const highlightHeight = computed(() => (selected.value != null ? ITEM_SIZE : 0))
const animatedHighlightPosition = useApproach(highlightPosition)
const animatedHighlightHeight = useApproach(highlightHeight)

watch(selectedPosition, (newPos) => {
  if (newPos == null) return
  highlightPosition.value = newPos
  if (animatedHighlightHeight.value <= 1.0) {
    animatedHighlightPosition.skip()
  }
})

const highlightClipPath = computed(() => {
  let height = animatedHighlightHeight.value
  let position = animatedHighlightPosition.value
  let top = position + ITEM_SIZE - height
  let bottom = listContentHeight.value - position - ITEM_SIZE
  return `inset(${top}px 0px ${bottom}px 0px round 16px)`
})

function navigateUp() {
  if (selected.value != null && selected.value < components.value.length - 1) {
    selected.value += 1
  }
  scrollToSelected()
}

function navigateDown() {
  if (selected.value == null) {
    selected.value = components.value.length - 1
  } else if (selected.value > 0) {
    selected.value -= 1
  }
  scrollToSelected()
}

/**
 * Select the last element after updating component list.
 *
 * As the list changes the scroller's content, we need to wait a frame so the scroller
 * recalculates its height and setting scrollTop will work properly.
 */
function selectLastAfterRefresh() {
  selected.value = 0
  nextTick(() => {
    scrollToSelected()
    animatedScrollPosition.skip()
    animatedHighlightPosition.skip()
  })
}

// === Scrolling ===

const scroller = ref<HTMLElement>()
const scrollerSize = useResizeObserver(scroller)
const scrollPosition = ref(0)
const animatedScrollPosition = useApproach(scrollPosition)

const listContentHeight = computed(() =>
  // We add a top padding of TOP_BAR_HEIGHT / 2 - otherwise the topmost entry would be covered
  // by top bar.
  Math.max(components.value.length * ITEM_SIZE + TOP_BAR_HEIGHT / 2, scrollerSize.value.y),
)
const listContentHeightPx = computed(() => `${listContentHeight.value}px`)

function scrollToSelected() {
  if (selectedPosition.value == null) return
  scrollPosition.value = Math.max(selectedPosition.value - scrollerSize.value.y + ITEM_SIZE, 0)
}

function updateScroll() {
  if (scroller.value && Math.abs(scroller.value.scrollTop - animatedScrollPosition.value) > 1.0) {
    scrollPosition.value = scroller.value.scrollTop
    animatedScrollPosition.skip()
  }
}

// === Documentation Panel ===

const docsVisible = ref(true)

// === Key Events Handler ===

function handleKeydown(e: KeyboardEvent) {
  switch (e.key) {
    case 'Enter':
      e.stopPropagation()
      emit('finished')
      break
    case 'ArrowUp':
      e.preventDefault()
      navigateUp()
      break
    case 'ArrowDown':
      e.preventDefault()
      navigateDown()
      break
    case 'Escape':
      e.preventDefault()
      selected.value = null
      break
  }
}
</script>

<template>
  <div
    ref="cbRoot"
    class="ComponentBrowser"
    :style="{ transform, '--list-height': listContentHeightPx }"
    tabindex="-1"
    @focusout="handleDefocus"
    @keydown="handleKeydown"
  >
    <div class="panels">
      <div class="panel components">
        <div class="top-bar">
          <div class="top-bar-inner">
            <ToggleIcon v-model="filterFlags.showLocal" icon="local_scope2" />
            <ToggleIcon icon="command_key3" />
            <ToggleIcon v-model="filterFlags.showUnstable" icon="unstable2" />
            <ToggleIcon icon="marketplace" />
            <ToggleIcon v-model="docsVisible" icon="right_side_panel" class="first-on-right" />
          </div>
        </div>
        <div class="components-content">
          <div
            ref="scroller"
            class="list"
            :scrollTop.prop="animatedScrollPosition.value"
            @wheel.stop.passive
            @scroll="updateScroll"
          >
            <div class="list-variant" style="">
              <div
                v-for="item in visibleComponents"
                :key="item.component.suggestionId"
                class="component"
                :style="componentStyle(item.index)"
                @mousemove="selected = item.index"
              >
                <SvgIcon
                  :name="item.component.icon"
                  :style="{ color: componentColor(item.component) }"
                />
                {{ item.component.label }}
              </div>
            </div>
            <div class="list-variant selected" :style="{ clipPath: highlightClipPath }">
              <div
                v-for="item in visibleComponents"
                :key="item.component.suggestionId"
                class="component"
                :style="{
                  backgroundColor: componentColor(item.component),
                  ...componentStyle(item.index),
                }"
              >
                <SvgIcon :name="item.component.icon" />
                {{ item.component.label }}
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="panel docs" :class="{ hidden: !docsVisible }">DOCS</div>
    </div>
    <div class="CBInput"><input ref="inputField" v-model="inputText" /></div>
  </div>
</template>

<style scoped>
.ComponentBrowser {
  --list-height: 0px;
  width: fit-content;
  color: rgba(0, 0, 0, 0.6);
  font-size: 11.5px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.panels {
  display: flex;
  flex-direction: row;
  gap: 4px;
}

.panel {
  height: 380px;
  border: none;
  border-radius: 20px;
  background-color: #eaeaea;
}

.components {
  width: 190px;
  position: relative;
}

.components-content {
  position: absolute;
  top: 0px;
  padding: 4px;
  width: 100%;
  height: 100%;
}

.docs {
  width: 406px;
  clip-path: inset(0 0 0 0 round 20px);
  transition: clip-path 0.2s;
}
.docs.hidden {
  clip-path: inset(0 100% 0 0 round 20px);
}

.list {
  top: 20px;
  width: 100%;
  height: calc(100% - 20px);
  overflow-x: hidden;
  overflow-y: auto;
  position: relative;
}

.list-variant {
  top: 0px;
  width: 100%;
  height: var(--list-height);
  position: absolute;
}

.component {
  width: 100%;
  height: 32px;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  padding: 9px;
  display: flex;
  position: absolute;
  line-height: 1;
}
.selected {
  color: white;
  & svg {
    color: white;
  }
}

.top-bar {
  width: 100%;
  height: 40px;
  padding: 4px;
  background-color: #eaeaea;
  border-radius: 20px;
  position: absolute;
  top: 0px;
  z-index: 1;
}

.top-bar-inner {
  width: 100%;
  height: 100%;
  border-radius: 16px;
  border: 0.5px solid rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: row;
  gap: 12px;
  padding: 7px;

  & svg {
    color: rgba(0, 0, 0, 0.18);
    transition: color 0.2s;
  }
  & .first-on-right {
    margin-left: auto;
  }
  & > svg.toggledOn {
    color: rgba(0, 0, 0, 0.6);
  }

  & > svg:not(.toggledOn):hover {
    color: rgba(0, 0, 0, 0.3);
  }
}

.CBInput {
  border-radius: 20px;
  background-color: #eaeaea;
  height: 40px;
  padding: 12px;
  display: flex;
  flex-direction: row;

  & input {
    border: none;
    outline: none;
    min-width: 0;
    flex-grow: 1;
    background: none;
    font: inherit;
  }
}
</style>
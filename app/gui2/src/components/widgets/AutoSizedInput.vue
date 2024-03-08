<script setup lang="ts">
import { useAutoBlur } from '@/util/autoBlur'
import { getTextWidthByFont } from '@/util/measurement'
import { computed, ref, watch, type StyleValue } from 'vue'

const [model, modifiers] = defineModel<string>()
const props = defineProps<{ autoSelect?: boolean }>()

const innerModel = modifiers.lazy ? ref(model.value) : model
if (modifiers.lazy) watch(model, (newVal) => (innerModel.value = newVal))
const onChange = modifiers.lazy ? () => (model.value = innerModel.value) : undefined

const inputNode = ref<HTMLInputElement>()
useAutoBlur(inputNode)
function onFocus() {
  if (props.autoSelect) {
    inputNode.value?.select()
  }
}

const cssFont = computed(() => {
  if (inputNode.value == null) return ''
  let style = window.getComputedStyle(inputNode.value)
  return style.font
})

const getTextWidth = (text: string) => getTextWidthByFont(text, cssFont.value)
const inputWidth = computed(() => getTextWidth(`${innerModel.value}`))
const inputStyle = computed<StyleValue>(() => ({ width: `${inputWidth.value}px` }))

function onEnterDown() {
  inputNode.value?.blur()
}

defineExpose({
  inputWidth,
  getTextWidth,
  select: () => inputNode.value?.select(),
  focus: () => inputNode.value?.focus(),
})
</script>

<template>
  <input
    ref="inputNode"
    v-model="innerModel"
    class="AutoSizedInput"
    :style="inputStyle"
    @keydown.backspace.stop
    @keydown.delete.stop
    @keydown.enter.stop="onEnterDown"
    @change="onChange"
    @focus="onFocus"
  />
</template>

<style scoped>
.AutoSizedInput {
  position: relative;
  display: inline-block;
  background: none;
  border: none;
  text-align: center;
  font-weight: 800;
  line-height: 171.5%;
  height: 24px;
  appearance: textfield;
  -moz-appearance: textfield;
  cursor: default;
  user-select: all;
  box-sizing: content-box;
  &:focus {
    outline: none;
  }
}

input::-webkit-outer-spin-button,
input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
</style>
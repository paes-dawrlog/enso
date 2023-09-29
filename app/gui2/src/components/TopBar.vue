<script setup lang="ts">
import NavBar from '@/components/NavBar.vue'
import ProjectTitle from '@/components/ProjectTitle.vue'
import { useGuiConfig } from '@/providers/guiConfig'
import { computed } from 'vue'

const props = defineProps<{ title: string; breadcrumbs: string[]; modes: string[]; mode: string }>()
const emit = defineEmits<{
  execute: []
  back: []
  forward: []
  breadcrumbClick: [index: number]
  'update:mode': [mode: string]
}>()

const config = useGuiConfig()

const barStyle = computed(() => {
  const offset = config.value.window?.topBarOffset ?? '0'
  return {
    marginLeft: `${offset}px`,
  }
})
</script>

<template>
  <div class="TopBar" :style="barStyle">
    <ProjectTitle
      :title="props.title"
      :modes="props.modes"
      :mode="props.mode"
      @update:mode="emit('update:mode', $event)"
      @execute="emit('execute')"
    />
    <NavBar
      :breadcrumbs="props.breadcrumbs"
      @back="emit('back')"
      @forward="emit('forward')"
      @breadcrumbClick="emit('breadcrumbClick', $event)"
    />
  </div>
</template>

<style scoped>
.TopBar {
  position: absolute;
  display: flex;
  gap: 8px;
  top: 9px;
  /* FIXME[sb]: Get correct offset from dashboard. */
  left: 9px;
}
</style>
@/providers/guiConfig
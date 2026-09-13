<script setup lang="ts">
import { ApiError } from '@/api/errors'
import ConflictAlert from '@/ui/page/ConflictAlert.vue'

defineProps<{ error: ApiError | null }>()
defineEmits<{ reload: [] }>()
</script>

<template>
  <ConflictAlert :error="error" @reload="$emit('reload')" />
</template>

<script setup lang="ts">
// 代码示例面板（FE-050）：按 sample_type/build_tool 请求 code-sample，
// 只读展示、一键复制保留换行；Token 位置固定占位符，不写剪贴板外存储。
import { computed, ref, watch } from 'vue'
import PageState from '@/components/PageState.vue'
import { fetchCodeSample, type AccessMode, type BuildTool, type CodeSampleResult, type SampleType } from '@/api/developerAccess'
import { isAbortError } from '@/api/errors'
import { toErrorMessage } from '@/api/errors'

const props = defineProps<{
  aliasId: string | null
  mode: AccessMode | null
}>()

const sampleTypes: { value: SampleType; label: string }[] = [
  { value: 'DEPENDENCY', label: '依赖' },
  { value: 'CONFIG', label: '配置' },
  { value: 'SYNC', label: '同步调用' },
  { value: 'ASYNC', label: '异步调用' },
  { value: 'STREAM', label: '流式调用' },
  { value: 'HTTP', label: 'HTTP' },
]
const buildTools: { value: BuildTool; label: string }[] = [
  { value: 'MAVEN', label: 'Maven' },
  { value: 'GRADLE', label: 'Gradle' },
]

const sampleType = ref<SampleType>('SYNC')
const buildTool = ref<BuildTool>('MAVEN')
const sample = ref<CodeSampleResult | null>(null)
const loading = ref(false)
const error = ref('')
const copied = ref(false)

let seq = 0
let controller: AbortController | null = null

async function load(): Promise<void> {
  if (!props.aliasId || !props.mode) return
  const current = ++seq
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const query = {
      alias_id: props.aliasId,
      mode: props.mode,
      sample_type: sampleType.value,
      ...(sampleType.value === 'DEPENDENCY' ? { build_tool: buildTool.value } : {}),
    }
    const result = await fetchCodeSample(query, controller.signal)
    if (current !== seq) return
    sample.value = result
  } catch (e) {
    if (current !== seq || isAbortError(e)) return
    error.value = toErrorMessage(e)
    sample.value = null
  } finally {
    if (current === seq) loading.value = false
  }
}

watch([() => props.aliasId, () => props.mode, sampleType, buildTool], () => {
  copied.value = false
  void load()
}, { immediate: true })

const placeholderText = computed(() => {
  // 占位符扫描：示例代码中的固定占位符
  if (!sample.value) return []
  const matches = sample.value.content.match(/lai_[a-z_]+/g) ?? []
  return [...new Set(matches)].map((item) => item.slice(1, -1))
})

async function copySample(): Promise<void> {
  if (!sample.value) return
  try {
    await navigator.clipboard.writeText(sample.value.content)
    copied.value = true
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <section class="lai-sample">
    <div class="lai-sample-toolbar">
      <div
        class="lai-sample-tabs"
        role="tablist"
        aria-label="示例类型"
      >
        <button
          v-for="item in sampleTypes"
          :key="item.value"
          type="button"
          class="lai-btn lai-sample-tab"
          :class="{ 'lai-sample-tab-active': sampleType === item.value }"
          @click="sampleType = item.value"
        >
          {{ item.label }}
        </button>
      </div>
      <select
        v-if="sampleType === 'DEPENDENCY'"
        v-model="buildTool"
        class="lai-input lai-sample-tool"
        aria-label="构建工具"
      >
        <option
          v-for="item in buildTools"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
        </option>
      </select>
      <button
        type="button"
        class="lai-btn"
        :disabled="!sample"
        @click="copySample"
      >
        {{ copied ? '已复制' : '复制示例' }}
      </button>
    </div>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="error"
      status="error"
      :message="error"
      @retry="load"
    />
    <PageState
      v-else-if="!sample"
      status="empty"
      message="没有可用的示例"
    />
    <template v-else>
      <p
        v-if="sample.filename"
        class="lai-sample-file"
      >
        {{ sample.filename }}（{{ sample.language }}）
      </p>
      <pre
        class="lai-sample-code"
        data-testid="code-sample"
      >{{ sample.content }}</pre>
      <p
        v-if="placeholderText.length > 0"
        class="lai-sample-placeholders"
      >
        替换以下占位符后执行：{{ placeholderText.join('、') }}（Token 固定为 lai_your_token，请前往访问凭证页创建真实 Token）
      </p>
    </template>
  </section>
</template>

<style scoped>
.lai-sample-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.lai-sample-tabs {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.lai-sample-tab-active {
  border-color: #0969da;
  color: #0969da;
}
.lai-sample-tool {
  width: auto;
  padding: 5px 8px;
}
.lai-sample-file {
  font-size: 12px;
  color: #57606a;
  margin: 0 0 4px;
}
.lai-sample-code {
  margin: 0;
  padding: 12px 16px;
  background: #0d1117;
  color: #e6edf3;
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre;
}
.lai-sample-placeholders {
  font-size: 12px;
  color: #9a6700;
  margin: 8px 0 0;
}
</style>

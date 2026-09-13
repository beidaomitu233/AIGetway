<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Button, Checkbox, Input, Select } from 'ant-design-vue'
import PageState from '@/components/PageState.vue'
import VersionConflictBanner from '@/components/VersionConflictBanner.vue'
import { useFormSubmit } from '@/composables/useFormSubmit'
import {
  type AccessCredentialDetail,
  type AccessCredentialSecretResult,
  type AccessCredentialSavePayload,
  createAccessCredential,
  fetchAccessCredential,
  updateAccessCredential,
} from '@/api/accessCredentials'

const props = defineProps<{
  open: boolean
  /** null 为创建；否则为编辑目标 id。 */
  accessId: string | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  created: [result: AccessCredentialSecretResult]
  saved: []
}>()

const loading = ref(false)
const loadError = ref<unknown>(null)
const loaded = ref<AccessCredentialDetail | null>(null)

const form = reactive({
  name: '',
  application: '',
  allowed_alias_ids: [] as string[],
  ip_allowlist: [] as string[],
  newIp: '',
  expires_at: '',
  enabled: true,
})
const version = ref<number | null>(null)
const localErrors = ref<Record<string, string>>({})

const { submitting, conflictError, errorText, submit } = useFormSubmit()

/** Alias 可选项：正式联调时由已发布 Alias 列表提供；夹具阶段由后端契约提供。 */
const aliasOptions = ref<Array<{ value: string; label: string }>>([])

async function load(): Promise<void> {
  if (props.accessId === null) {
    loaded.value = null
    return
  }
  loading.value = true
  loadError.value = null
  try {
    loaded.value = await fetchAccessCredential(props.accessId)
    form.name = loaded.value.name
    form.application = loaded.value.application
    form.allowed_alias_ids = [...loaded.value.allowed_alias_ids]
    form.ip_allowlist = [...loaded.value.ip_allowlist]
    form.expires_at = loaded.value.expires_at ?? ''
    version.value = loaded.value.version
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}
onMounted(load)

const IP_PATTERN =
  /^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(\/\d{1,2})?|[0-9a-fA-F:]+(\/\d{1,3})?)$/

function addIp(): void {
  const value = form.newIp.trim()
  if (value === '' || !IP_PATTERN.test(value) || form.ip_allowlist.length >= 100) return
  if (!form.ip_allowlist.includes(value)) {
    form.ip_allowlist.push(value)
  }
  form.newIp = ''
}

function removeIp(index: number): void {
  form.ip_allowlist.splice(index, 1)
}

function validate(): boolean {
  const errors: Record<string, string> = {}
  const name = form.name.trim()
  if (name.length < 2 || name.length > 64) errors.name = '名称长度为 2—64 字符'
  const application = form.application.trim()
  if (application.length < 1 || application.length > 64) {
    errors.application = '应用标识长度为 1—64 字符'
  }
  if (form.expires_at !== '') {
    const target = new Date(form.expires_at).getTime()
    if (Number.isNaN(target)) {
      errors.expires_at = '有效期格式不正确'
    } else if (props.accessId === null && target < Date.now() + 5 * 60_000) {
      errors.expires_at = '创建时有效期需晚于当前时间至少 5 分钟'
    }
  }
  if (form.ip_allowlist.some((ip) => !IP_PATTERN.test(ip))) {
    errors.ip_allowlist = 'IP 或 CIDR 格式不正确'
  }
  localErrors.value = errors
  return Object.keys(errors).length === 0
}

/** 打开时重置：避免卸载前后写表单值触发 Ant Input 内部 nextTick 访问已卸载节点。 */
function resetForm(): void {
  form.name = ''
  form.application = ''
  form.allowed_alias_ids = []
  form.ip_allowlist = []
  form.expires_at = ''
  form.enabled = true
  localErrors.value = {}
}

watch(() => props.open, (open) => {
  if (open) resetForm()
})

async function save(): Promise<void> {
  if (!validate()) return
  const payload: AccessCredentialSavePayload = {
    name: form.name.trim(),
    application: form.application.trim(),
    allowed_alias_ids: [...form.allowed_alias_ids],
    ip_allowlist: [...form.ip_allowlist],
    expires_at: form.expires_at === '' ? null : new Date(form.expires_at).toISOString(),
    enabled: form.enabled,
  }
  await submit(async () => {
    if (props.accessId === null) {
      const result = await createAccessCredential(payload)
      emit('update:open', false)
      emit('created', result)
    } else {
      await updateAccessCredential(props.accessId, { ...payload, version: version.value! })
      emit('update:open', false)
      emit('saved')
    }
  })
}

function cancel(): void {
  emit('update:open', false)
}

function fieldError(field: string): string | undefined {
  return localErrors.value[field]
}

const dialogTitle = computed(() => (props.accessId === null ? '创建访问凭证' : '编辑访问凭证'))
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc="cancel"
    >
      <div
        class="lai-dialog lai-dialog-wide"
        role="dialog"
        aria-modal="true"
        :aria-label="dialogTitle"
      >
        <h2 class="lai-dialog-title">
          {{ dialogTitle }}
        </h2>

        <PageState
          v-if="loading"
          status="loading"
        />
        <PageState
          v-else-if="loadError"
          status="error"
          :error="loadError"
          @retry="load"
        />
        <template v-else>
          <VersionConflictBanner
            :error="conflictError"
            @reload="load"
          />

          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="ac-name"
            >名称</label>
            <Input
              id="ac-name"
              v-model:value="form.name"
              :maxlength="64"
            />
            <p
              v-if="fieldError('name')"
              class="lai-form-message-error"
            >
              {{ fieldError('name') }}
            </p>
          </div>

          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="ac-app"
            >应用标识</label>
            <Input
              id="ac-app"
              v-model:value="form.application"
              :maxlength="64"
            />
            <p
              v-if="fieldError('application')"
              class="lai-form-message-error"
            >
              {{ fieldError('application') }}
            </p>
          </div>

          <div class="lai-form-field">
            <span class="lai-form-label">允许的 Alias（空数组允许全部已发布 Alias）</span>
            <Select
              v-model:value="form.allowed_alias_ids"
              class="lai-filter-select"
              mode="multiple"
              aria-label="允许 Alias"
              placeholder="全部已发布 Alias"
              :options="aliasOptions"
            />
          </div>

          <div class="lai-form-field">
            <span class="lai-form-label">IP 白名单（空数组不限制；最多 100 项）</span>
            <div class="lai-kv-row">
              <Input
                v-model:value="form.newIp"
                class="lai-filter-input"
                placeholder="IPv4、IPv6 或 CIDR"
                @keydown.enter.prevent="addIp"
              />
              <Button @click="addIp">
                添加
              </Button>
            </div>
            <ul class="lai-related-list">
              <li
                v-for="(ip, index) in form.ip_allowlist"
                :key="ip"
              >
                {{ ip }}
                <Button
                  type="link"
                  @click="removeIp(index)"
                >
                  移除
                </Button>
              </li>
            </ul>
            <p
              v-if="fieldError('ip_allowlist')"
              class="lai-form-message-error"
            >
              {{ fieldError('ip_allowlist') }}
            </p>
          </div>

          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="ac-exp"
            >有效期至（留空长期有效）</label>
            <Input
              id="ac-exp"
              v-model:value="form.expires_at"
              type="datetime-local"
            />
            <p
              v-if="fieldError('expires_at')"
              class="lai-form-message-error"
            >
              {{ fieldError('expires_at') }}
            </p>
          </div>

          <div
            v-if="props.accessId === null"
            class="lai-form-field"
          >
            <label
              class="lai-form-label"
              for="ac-enabled"
            >创建后立即启用</label>
            <Checkbox
              id="ac-enabled"
              v-model:checked="form.enabled"
            />
          </div>

          <p
            v-if="errorText"
            class="lai-form-message-error"
            role="alert"
          >
            {{ errorText }}
          </p>

          <div class="lai-dialog-actions">
            <Button
              :disabled="submitting"
              @click="cancel"
            >
              取消
            </Button>
            <Button
              type="primary"
              :disabled="submitting || conflictError !== null"
              @click="save"
            >
              {{ submitting ? '保存中…' : props.accessId === null ? '创建并签发 Token' : '保存' }}
            </Button>
          </div>
        </template>
      </div>
    </div>
  </Teleport>
</template>

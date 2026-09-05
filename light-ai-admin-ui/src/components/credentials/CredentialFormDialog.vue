<script setup lang="ts">
// Credential 新增/编辑表单（FE-013，附录 4.2.4.3）。
// 密钥来源创建后只读；INLINE 密钥两次输入一致；EXTERNAL 编辑可改引用；
// 编辑不接受 secret_value（密钥变更必须使用轮换）。
import { computed, ref, watch } from 'vue'
import SecretInput from '@/components/SecretInput.vue'
import type { CredentialDetail, CredentialListItem, SecretSource } from '@/api/credentials'
import { toErrorMessage } from '@/api/errors'

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 新建传 null；编辑传详情或列表行。 */
    credential: CredentialListItem | CredentialDetail | null
    submitting?: boolean
    error?: unknown
  }>(),
  {
    submitting: false,
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [
    command:
      | {
          mode: 'create'
          name: string
          secret_source: SecretSource
          secret_value?: string | undefined
          secret_ref?: string | undefined
          weight: number
          rpm_limit: number | null
          tpm_limit: number | null
          concurrent_limit: number | null
          enabled: boolean
        }
      | {
          mode: 'update'
          name: string
          secret_ref?: string | undefined | null
          weight: number
          rpm_limit: number | null
          tpm_limit: number | null
          concurrent_limit: number | null
          enabled: boolean
          version: number
        },
  ]
}>()

const isEdit = computed(() => props.credential !== null)

const name = ref('')
const secretSource = ref<SecretSource>('INLINE_ENCRYPTED')
const secretValue = ref('')
const secretValueConfirm = ref('')
const secretRef = ref('')
const weight = ref<number>(1)
const rpmLimit = ref<string>('')
const tpmLimit = ref<string>('')
const concurrentLimit = ref<string>('')
const enabled = ref(true)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    const source = props.credential
    name.value = source?.name ?? ''
    secretSource.value = source?.secret_source ?? 'INLINE_ENCRYPTED'
    secretValue.value = ''
    secretValueConfirm.value = ''
    secretRef.value = ''
    weight.value = source?.weight ?? 1
    rpmLimit.value = source?.rpm_limit == null ? '' : String(source.rpm_limit)
    tpmLimit.value = source?.tpm_limit == null ? '' : String(source.tpm_limit)
    concurrentLimit.value = source?.concurrent_limit == null ? '' : String(source.concurrent_limit)
    enabled.value = source?.enabled ?? true
  },
  { immediate: true },
)

function parseLimit(raw: string, max?: number): number | null | 'invalid' {
  if (raw === '') return null
  if (!/^\d+$/.test(raw)) return 'invalid'
  const value = Number(raw)
  if (!Number.isSafeInteger(value) || value < 1) return 'invalid'
  if (max !== undefined && value > max) return 'invalid'
  return value
}

const nameInvalid = computed(() => name.value.trim().length < 2 || name.value.trim().length > 64)
const secretRequired = computed(() => !isEdit.value)
const secretValueInvalid = computed(
  () =>
    secretRequired.value &&
    secretSource.value === 'INLINE_ENCRYPTED' &&
    (secretValue.value.length < 1 || secretValue.value.length > 4096),
)
const secretConfirmInvalid = computed(
  () =>
    secretRequired.value &&
    secretSource.value === 'INLINE_ENCRYPTED' &&
    secretValue.value !== secretValueConfirm.value,
)
const secretRefInvalid = computed(() => {
  if (secretSource.value !== 'EXTERNAL_REF') return false
  if (isEdit.value) return secretRef.value.length > 512
  return secretRef.value.length < 1 || secretRef.value.length > 512
})
const weightInvalid = computed(() => !Number.isInteger(weight.value) || weight.value < 1 || weight.value > 100)
const rpmInvalid = computed(() => parseLimit(rpmLimit.value) === 'invalid')
const tpmInvalid = computed(() => parseLimit(tpmLimit.value) === 'invalid')
const concurrentInvalid = computed(() => parseLimit(concurrentLimit.value, 100000) === 'invalid')

const confirmDisabled = computed(
  () =>
    props.submitting ||
    nameInvalid.value ||
    secretValueInvalid.value ||
    secretConfirmInvalid.value ||
    secretRefInvalid.value ||
    weightInvalid.value ||
    rpmInvalid.value ||
    tpmInvalid.value ||
    concurrentInvalid.value,
)

function confirm(): void {
  if (confirmDisabled.value) return
  const base = {
    name: name.value.trim(),
    weight: weight.value,
    rpm_limit: parseLimit(rpmLimit.value) === 'invalid' ? null : (parseLimit(rpmLimit.value) as number | null),
    tpm_limit: parseLimit(tpmLimit.value) === 'invalid' ? null : (parseLimit(tpmLimit.value) as number | null),
    concurrent_limit:
      parseLimit(concurrentLimit.value, 100000) === 'invalid'
        ? null
        : (parseLimit(concurrentLimit.value, 100000) as number | null),
    enabled: enabled.value,
  }
  if (isEdit.value && props.credential) {
    emit('confirm', {
      mode: 'update',
      ...base,
      secret_ref: secretSource.value === 'EXTERNAL_REF' ? secretRef.value : null,
      version: props.credential.version,
    })
    return
  }
  emit('confirm', {
    mode: 'create',
    ...base,
    secret_source: secretSource.value,
    secret_value: secretSource.value === 'INLINE_ENCRYPTED' ? secretValue.value : undefined,
    secret_ref: secretSource.value === 'EXTERNAL_REF' ? secretRef.value : undefined,
  })
}

function close(): void {
  emit('update:open', false)
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc="close"
    >
      <div
        class="lai-dialog lai-cred-form"
        role="dialog"
        aria-modal="true"
        :aria-label="isEdit ? '编辑 Credential' : '新增 Credential'"
      >
        <h2 class="lai-dialog-title">
          {{ isEdit ? '编辑 Credential' : '新增 Credential' }}
        </h2>

        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="lai-cred-name"
          >名称<span
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <input
            id="lai-cred-name"
            v-model="name"
            class="lai-input"
            type="text"
            maxlength="64"
          >
          <p
            v-if="nameInvalid"
            class="lai-form-message-error"
          >
            名称长度为 2—64 字符
          </p>
        </div>

        <div class="lai-form-field">
          <span class="lai-form-label">密钥来源</span>
          <label
            v-if="!isEdit"
            class="lai-radio"
          >
            <input
              v-model="secretSource"
              type="radio"
              value="INLINE_ENCRYPTED"
            >
            加密存储（保存密钥原文）
          </label>
          <label
            v-if="!isEdit"
            class="lai-radio"
          >
            <input
              v-model="secretSource"
              type="radio"
              value="EXTERNAL_REF"
            >
            外部引用（保存 secret_ref）
          </label>
          <span
            v-else
            class="lai-static-text"
          >{{ secretSource === 'EXTERNAL_REF' ? '外部引用' : '加密存储' }}（创建后不可切换）</span>
        </div>

        <template v-if="secretSource === 'INLINE_ENCRYPTED' && !isEdit">
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-cred-secret"
            >密钥<span
              class="lai-required"
              aria-hidden="true"
            >*</span></label>
            <SecretInput
              id="lai-cred-secret"
              v-model="secretValue"
              placeholder="1—4096 字符"
            />
            <p
              v-if="secretValueInvalid"
              class="lai-form-message-error"
            >
              密钥长度为 1—4096 字符
            </p>
          </div>
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-cred-secret-confirm"
            >确认密钥<span
              class="lai-required"
              aria-hidden="true"
            >*</span></label>
            <SecretInput
              id="lai-cred-secret-confirm"
              v-model="secretValueConfirm"
              placeholder="再次输入密钥"
            />
            <p
              v-if="secretConfirmInvalid"
              class="lai-form-message-error"
            >
              两次输入的密钥不一致
            </p>
          </div>
        </template>

        <div
          v-if="secretSource === 'EXTERNAL_REF'"
          class="lai-form-field"
        >
          <label
            class="lai-form-label"
            for="lai-cred-secret-ref"
          >secret_ref<span
            v-if="!isEdit"
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <input
            id="lai-cred-secret-ref"
            v-model="secretRef"
            class="lai-input"
            type="text"
            maxlength="512"
            autocomplete="off"
          >
          <p
            v-if="secretRefInvalid"
            class="lai-form-message-error"
          >
            引用长度为 1—512 字符
          </p>
        </div>

        <div class="lai-form-grid">
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-cred-weight"
            >权重（1—100）</label>
            <input
              id="lai-cred-weight"
              v-model.number="weight"
              class="lai-input"
              type="number"
              min="1"
              max="100"
            >
          </div>
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-cred-rpm"
            >RPM 限额（空为不限制）</label>
            <input
              id="lai-cred-rpm"
              v-model="rpmLimit"
              class="lai-input"
              type="text"
              inputmode="numeric"
              placeholder="不限制"
            >
            <p
              v-if="rpmInvalid"
              class="lai-form-message-error"
            >
              需为正整数
            </p>
          </div>
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-cred-tpm"
            >TPM 限额（空为不限制）</label>
            <input
              id="lai-cred-tpm"
              v-model="tpmLimit"
              class="lai-input"
              type="text"
              inputmode="numeric"
              placeholder="不限制"
            >
            <p
              v-if="tpmInvalid"
              class="lai-form-message-error"
            >
              需为正整数
            </p>
          </div>
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-cred-concurrent"
            >并发上限（空为不限制）</label>
            <input
              id="lai-cred-concurrent"
              v-model="concurrentLimit"
              class="lai-input"
              type="text"
              inputmode="numeric"
              placeholder="不限制"
            >
            <p
              v-if="concurrentInvalid"
              class="lai-form-message-error"
            >
              范围为 1—100000
            </p>
          </div>
        </div>

        <label class="lai-radio">
          <input
            v-model="enabled"
            type="checkbox"
          >
          启用（发布后参与选择）
        </label>

        <p
          v-if="error"
          class="lai-form-message-error"
          role="alert"
        >
          {{ toErrorMessage(error) }}
        </p>

        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="submitting"
            @click="close"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="confirmDisabled"
            @click="confirm"
          >
            {{ submitting ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lai-cred-form {
  width: 560px;
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 96px);
  overflow: auto;
}
.lai-radio {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  margin: 4px 0;
}
.lai-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.lai-static-text {
  font-size: 13px;
  color: #57606a;
}
</style>

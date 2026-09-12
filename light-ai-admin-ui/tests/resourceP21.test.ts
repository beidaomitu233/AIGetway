import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import KeyValueEditor from '@/components/KeyValueEditor.vue'
import CredentialRotateDialog from '@/components/credentials/CredentialRotateDialog.vue'
import CredentialFormDialog from '@/components/credentials/CredentialFormDialog.vue'
import CandidateFormDialog from '@/pages/aliases/CandidateFormDialog.vue'
import { normalizeResourceUrl, headersValid } from '@/utils/resourceValidation'
import type { CredentialPoolOption } from '@/api/modelAliases'

afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks() })
describe('FE-211 请求头与 URL 安全', () => {
  it.each(['ftp://example.com', 'https://user:pass@example.com', 'https://example.com?key=value', 'https://example.com#secret', 'invalid'])('阻止危险地址 %s', (url) => {
    expect(normalizeResourceUrl(url)).toBeNull()
  })
  it('保留合法路径，网络安全由后端判定', () => {
    expect(normalizeResourceUrl(' https://api.example.com/v1/ ')).toBe('https://api.example.com/v1')
  })
  it.each(['Authorization', 'APIKEY', 'Cookie', 'Proxy-Authorization'])('阻止认证头 %s', (key) => {
    expect(headersValid([{ key, value: 'fixture' }])).toBe(false)
  })
  it('阻止重复键和换行注入', () => {
    expect(headersValid([{ key: 'X-Test', value: 'a' }, { key: 'x-test', value: 'b' }])).toBe(false)
    expect(headersValid([{ key: 'X-Test', value: 'a\r\nb' }])).toBe(false)
  })
  it('新增空行保留，合法输入才更新 payload，非法值不会静默写入', async () => {
    const wrapper = mount(defineComponent({ components: { KeyValueEditor }, setup() { return { value: ref({}) } }, template: '<KeyValueEditor v-model="value" />' }))
    await wrapper.find('button').trigger('click')
    expect(wrapper.findAll('input')).toHaveLength(2)
    const editor = wrapper.findComponent(KeyValueEditor)
    expect(editor.emitted('validity')?.at(-1)).toEqual([false])
    await wrapper.find('input').setValue('X-Region')
    await wrapper.findAll('input')[1]!.setValue('cn')
    expect(editor.emitted('update:modelValue')?.at(-1)).toEqual([{ 'X-Region': 'cn' }])
    await wrapper.find('input').setValue('Authorization')
    expect(editor.emitted('validity')?.at(-1)).toEqual([false])
    expect(editor.emitted('update:modelValue')?.at(-1)).toEqual([{ 'X-Region': 'cn' }])
    wrapper.unmount()
  })
  it('外部重载更新输入，最多 20 项', async () => {
    const wrapper = mount(KeyValueEditor, { props: { modelValue: {} } })
    await wrapper.setProps({ modelValue: Object.fromEntries(Array.from({ length: 20 }, (_, i) => ['X-' + i, 'v'])) })
    expect(wrapper.findAll('input')).toHaveLength(40)
    expect(wrapper.findAll('button').at(-1)!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
describe('FE-212 一次性密钥输入', () => {
  it('轮换提交后清空，不能复用失败请求的原文', async () => {
    const wrapper = mount(CredentialRotateDialog, { props: { open: true, credentialName: '测试 Key', version: 2 }, attachTo: document.body })
    await flushPromises()
    const inputs = [...document.querySelectorAll<HTMLInputElement>('.lai-dialog input')]
    expect(inputs).toHaveLength(2)
    for (const input of inputs) {
      input.value = 'fixture-secret'
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }
    await flushPromises()
    expect(inputs.map((input) => input.value)).toEqual(['fixture-secret', 'fixture-secret'])
    document.querySelector<HTMLButtonElement>('.lai-dialog .lai-btn-primary')!.click()
    await flushPromises()
    expect(wrapper.emitted('confirm')?.[0]).toEqual([{ secret_value: 'fixture-secret', secret_value_confirm: 'fixture-secret', version: 2 }])
    expect(inputs.every((input) => input.value === '')).toBe(true)
    wrapper.unmount()
  })
  it('提交中 Escape 不关闭', async () => {
    const wrapper = mount(CredentialRotateDialog, { props: { open: true, credentialName: '测试', version: 1, submitting: true }, global: { stubs: { Teleport: true } } })
    await wrapper.find('.lai-dialog-overlay').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('update:open')).toBeUndefined()
    wrapper.unmount()
  })
  it('新增弹窗关闭重开不会恢复密钥', async () => {
    const wrapper = mount(CredentialFormDialog, { props: { open: true, credential: null }, global: { stubs: { Teleport: true } } })
    const secret = wrapper.find('input[type="password"]')
    await secret.setValue('fixture-secret')
    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    expect((wrapper.find('input[type="password"]').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })
})
describe('FE-215 候选选项竞态', () => {
  it('旧模型选项迟到不覆盖当前模型', async () => {
    let finishFirst!: (value: CredentialPoolOption[]) => void
    const first = new Promise<CredentialPoolOption[]>((resolve) => { finishFirst = resolve })
    const loadPools = vi.fn().mockReturnValueOnce(first).mockResolvedValueOnce([{ id: 'pool-b', name: '渠道 B', channel_id: 'b', credential_available: 1, status: 'ACTIVE' }])
    const wrapper = mount(CandidateFormDialog, { props: { open: true, aliasId: 'v', candidate: null, modelGroups: [{ providerName: '测试', models: ['a', 'b'].map((id) => ({ id, label: id, supportStream: true, contextWindow: 100 })) }], loadPools }, global: { stubs: { Teleport: true } } })
    await wrapper.find('#lai-candidate-model').setValue('a')
    await wrapper.find('#lai-candidate-model').setValue('b')
    await flushPromises()
    finishFirst([{ id: 'pool-a', name: '渠道 A', channel_id: 'a', credential_available: 1, status: 'ACTIVE' }])
    await flushPromises()
    expect(wrapper.find('#lai-candidate-pool').text()).toContain('渠道 B')
    expect(wrapper.find('#lai-candidate-pool').text()).not.toContain('渠道 A')
    wrapper.unmount()
  })
})

import { describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { mount } from '@vue/test-utils'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import FormField from '@/components/FormField.vue'
import SecretInput from '@/components/SecretInput.vue'
import { useFormSubmit } from '@/composables/useFormSubmit'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { ApiError } from '@/api/errors'

describe('ConfirmDialog', () => {
  const impact = [
    { entity_type: 'provider_model', id: 'm-1', name: 'gpt-4o', relation: '候选引用' },
    { entity_type: 'route_candidate', id: 'c-1', name: '候选 1', relation: '直接引用' },
  ]

  it('取消只关闭弹窗，不产生确认事件', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: {
        open: true,
        title: '删除 Provider',
        message: '删除后需要重新发布才生效。',
        impact,
      },
      global: { stubs: { teleport: true } },
    })
    await wrapper.findAll('button')[0].trigger('click')
    expect(wrapper.emitted('update:open')).toEqual([[false]])
    expect(wrapper.emitted('confirm')).toBeUndefined()
  })

  it('展示影响对象列表', () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: true, title: '删除', message: '确认删除？', impact },
      global: { stubs: { teleport: true } },
    })
    const text = wrapper.text()
    expect(text).toContain('provider_model · gpt-4o（候选引用）')
    expect(text).toContain('route_candidate · 候选 1（直接引用）')
  })

  it('高风险操作需填写原因，未填时确认禁用', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: {
        open: true,
        title: '停用 Provider',
        message: '停用后新请求不再路由到该 Provider。',
        danger: true,
        requireReason: true,
      },
      global: { stubs: { teleport: true } },
    })
    expect(
      (wrapper.findAll('button').at(-1)!.element as HTMLButtonElement).disabled,
    ).toBe(true)
    await wrapper.find('#lai-dialog-reason').setValue('连接异常排查')
    const confirmButton = wrapper.findAll('button').at(-1)!
    expect((confirmButton.element as HTMLButtonElement).disabled).toBe(false)
    await confirmButton.trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[{ reason: '连接异常排查', confirmText: '' }]])
  })

  it('固定确认文本不匹配时禁用确认', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: {
        open: true,
        title: '全部撤销',
        message: '将还原全部未发布修改。',
        requireConfirmText: 'REVERT ALL',
      },
      global: { stubs: { teleport: true } },
    })
    expect(
      (wrapper.findAll('button').at(-1)!.element as HTMLButtonElement).disabled,
    ).toBe(true)
    await wrapper.find('#lai-dialog-confirm-text').setValue('REVERT ALL')
    const confirmButton = wrapper.findAll('button').at(-1)!
    expect((confirmButton.element as HTMLButtonElement).disabled).toBe(false)
    await confirmButton.trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([
      [{ reason: '', confirmText: 'REVERT ALL' }],
    ])
  })
})

describe('FormField', () => {
  it('展示字段错误与必填标记', () => {
    const wrapper = mount(FormField, {
      props: { label: '名称', required: true, error: '名称必填' },
      slots: { default: '<input class="lai-input" />' },
    })
    expect(wrapper.text()).toContain('名称必填')
    expect(wrapper.find('.lai-required').exists()).toBe(true)
    expect(wrapper.find('.lai-form-field-error').exists()).toBe(true)
  })

  it('无错误时展示提示信息', () => {
    const wrapper = mount(FormField, {
      props: { label: '名称', hint: '创建后不可修改' },
      slots: { default: '<input class="lai-input" />' },
    })
    expect(wrapper.text()).toContain('创建后不可修改')
    expect(wrapper.find('.lai-form-message-error').exists()).toBe(false)
  })
})

describe('SecretInput', () => {
  it('默认掩码显示且可切换', async () => {
    const wrapper = mount(SecretInput, {
      props: { modelValue: 'lai-secret-value' },
    })
    const input = wrapper.find('input')
    expect(input.attributes('type')).toBe('password')
    expect(input.attributes('autocomplete')).toBe('new-password')
    await wrapper.find('button').trigger('click')
    expect(input.attributes('type')).toBe('text')
  })

  it('输入触发更新事件，卸载时清空内存值', async () => {
    const wrapper = mount(SecretInput, { props: { modelValue: '' } })
    await wrapper.find('input').setValue('lai-new-secret')
    expect(wrapper.emitted('update:modelValue')).toEqual([['lai-new-secret']])
    wrapper.unmount()
    const events = wrapper.emitted('update:modelValue')!
    expect(events.at(-1)).toEqual([''])
  })
})

describe('useFormSubmit', () => {
  function buildHost(action: () => Promise<void>) {
    return defineComponent({
      setup() {
        const form = useFormSubmit()
        const run = () => form.submit(action)
        return { form, run }
      },
      template: '<div/>',
    })
  }

  it('提交成功返回 ok 并清空错误', async () => {
    const wrapper = mount(buildHost(() => Promise.resolve()))
    const vm = wrapper.vm as never as { run: () => Promise<{ ok: boolean }> }
    const result = await vm.run()
    expect(result.ok).toBe(true)
  })

  it('提交中重复触发被忽略', async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const wrapper = mount(buildHost(() => gate))
    const vm = wrapper.vm as never as {
      run: () => Promise<{ ok: boolean }>
      form: { submitting: { value: boolean } }
    }
    const first = vm.run()
    expect(vm.form.submitting.value).toBe(true)
    const second = await vm.run()
    expect(second.ok).toBe(false)
    release()
    expect((await first).ok).toBe(true)
    expect(vm.form.submitting.value).toBe(false)
  })

  it('字段错误与版本冲突分类展示', async () => {
    const fieldError = new ApiError(
      400,
      {
        code: 'FIELD_VALIDATION_FAILED',
        type: 'validation',
        message: '校验失败',
        errors: [{ field: 'base_url', code: 'INVALID', message: '地址不合法' }],
      },
      'req-field-1',
    )
    const wrapper = mount(buildHost(() => Promise.reject(fieldError)))
    const vm = wrapper.vm as never as {
      run: () => Promise<{ ok: boolean }>
      form: { fieldMessages: { value: Record<string, string> } }
    }
    const result = await vm.run()
    expect(result.ok).toBe(false)
    expect(vm.form.fieldMessages.value.base_url).toBe('地址不合法')

    const conflict = new ApiError(
      409,
      {
        code: 'CONFIG_VERSION_CONFLICT',
        type: 'conflict',
        message: '已被修改',
        current_version: 9,
      },
      'req-conflict-1',
    )
    const wrapper2 = mount(buildHost(() => Promise.reject(conflict)))
    const vm2 = wrapper2.vm as never as {
      run: () => Promise<{ ok: boolean }>
      form: { conflictError: { value: ApiError | null } }
    }
    await vm2.run()
    expect(vm2.form.conflictError.value?.serverVersion).toBe(9)
  })
})

describe('useDirtyGuard', () => {
  function buildHost(dirty: () => boolean) {
    return defineComponent({
      setup() {
        useDirtyGuard(dirty)
        return {}
      },
      template: '<div/>',
    })
  }

  async function mountRouteHost(dirty: () => boolean) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/a', component: buildHost(dirty) },
        { path: '/b', component: defineComponent({ template: '<div/>' }) },
      ],
    })
    void router.push('/a')
    await router.isReady()
    const wrapper = mount(
      defineComponent({ template: '<RouterView />' }),
      { global: { plugins: [router] } },
    )
    return { wrapper, router }
  }

  it('脏表单离开需确认，取消则停留', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { wrapper, router } = await mountRouteHost(() => true)
    await router.push('/b')
    expect(confirmSpy).toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/a')
    wrapper.unmount()
  })

  it('确认后允许离开', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { wrapper, router } = await mountRouteHost(() => true)
    await router.push('/b')
    expect(confirmSpy).toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/b')
    wrapper.unmount()
  })

  it('未修改时离开无需确认', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { wrapper, router } = await mountRouteHost(() => false)
    await router.push('/b')
    expect(confirmSpy).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/b')
    wrapper.unmount()
  })

  it('ref 可作为脏检查输入', async () => {
    const dirty = ref(false)
    const { wrapper, router } = await mountRouteHost(() => dirty.value)
    await router.push('/b')
    expect(router.currentRoute.value.path).toBe('/b')
    wrapper.unmount()
  })
})

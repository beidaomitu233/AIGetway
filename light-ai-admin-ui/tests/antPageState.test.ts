import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ApiError } from '@/api/errors'
import AsyncState from '@/ui/page/AsyncState.vue'
import ConflictAlert from '@/ui/page/ConflictAlert.vue'
import PageCard from '@/ui/page/PageCard.vue'
import PageHeader from '@/ui/page/PageHeader.vue'

describe('Ant Design page framework', () => {
  it('renders loading, empty, and error states with retry', async () => {
    const wrapper = mount(AsyncState, {
      props: { status: 'loading' },
    })
    expect(wrapper.get('[role="status"]').attributes('aria-label')).toBe('加载中')

    await wrapper.setProps({ status: 'empty', message: '筛选无结果' })
    expect(wrapper.text()).toContain('筛选无结果')

    await wrapper.setProps({
      status: 'error',
      error: new ApiError(500, { code: 'E_TEST', type: 'server', message: '请求失败' }, 'req-1'),
    })
    expect(wrapper.text()).toContain('请求ID req-1')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('keeps conflict content and exposes an explicit reload action', async () => {
    const wrapper = mount(ConflictAlert, {
      props: {
        error: new ApiError(
          409,
          { code: 'CONFIG_VERSION_CONFLICT', type: 'conflict', message: '配置版本冲突', current_version: 7 },
          'req-2',
        ),
      },
    })
    expect(wrapper.text()).toContain('服务端最新版本：7')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })

  it('provides card and header slots for page composition', () => {
    const wrapper = mount(PageCard, {
      slots: { default: '<div>内容</div>' },
    })
    expect(wrapper.classes()).toContain('lai-page-card')
    expect(wrapper.text()).toContain('内容')

    const header = mount(PageHeader, {
      props: { title: '应用', description: '管理企业应用' },
      slots: { actions: '<button>新建</button>' },
    })
    expect(header.text()).toContain('应用')
    expect(header.text()).toContain('新建')
  })
})

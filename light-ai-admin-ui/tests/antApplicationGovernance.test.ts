import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ApplicationQuotaSummary from '@/pages/applications/ApplicationQuotaSummary.vue'

describe('Ant Design application governance surfaces', () => {
  it('renders quota usage as a card with progress indicators and keeps the detail table contract', () => {
    const wrapper = mount(ApplicationQuotaSummary, {
      props: {
        timezone: 'Asia/Shanghai',
        quota: {
          token_limit: '1000000', tokens_used: '1200', tokens_reserved: '100',
          amount_limit: '1000', amount_used: '12.50', amount_reserved: '1.25',
          currency: 'CNY', period_end: null,
        } as any,
      },
    })
    expect(wrapper.find('.ant-card').exists()).toBe(true)
    expect(wrapper.findAll('.ant-progress').length).toBe(2)
    expect(wrapper.find('[aria-label="额度使用明细"]').text()).toContain('998,700')
    expect(wrapper.text()).toContain('当前周期结束')
  })

  it('does not emit a misleading percentage for unlimited dimensions', () => {
    const wrapper = mount(ApplicationQuotaSummary, {
      props: {
        timezone: 'UTC',
        quota: {
          token_limit: null, tokens_used: '10', tokens_reserved: '2',
          amount_limit: null, amount_used: '1', amount_reserved: '0', currency: 'CNY', period_end: null,
        } as any,
      },
    })
    expect(wrapper.findAll('.ant-progress').length).toBe(2)
    expect(wrapper.find('[aria-label="额度使用明细"]').text()).toContain('不限')
  })
})

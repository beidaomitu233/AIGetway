import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { ApplicationQuotaPolicy } from '@/api/applications'
import ApplicationQuotaSummary from '@/pages/applications/ApplicationQuotaSummary.vue'

const baseQuota: ApplicationQuotaPolicy = {
  id: 'quota-1',
  token_limit: '1000000',
  tokens_used: '1200',
  tokens_reserved: '100',
  amount_limit: '1000',
  amount_used: '12.50',
  amount_reserved: '1.25',
  currency: 'CNY',
  rpm: 60,
  tpm: 100000,
  period_type: 'LIFECYCLE',
  period_start: null,
  period_end: null,
  period_id: null,
  policy_version: '1',
  timezone: 'Asia/Shanghai',
  reset_at: null,
  tokens_remaining: '998700',
  amount_remaining: '986.25',
  admission_blocked: false,
  version: '1',
}

describe('Ant Design application governance surfaces', () => {
  it('renders quota usage as a card with progress indicators and keeps the detail table contract', () => {
    const wrapper = mount(ApplicationQuotaSummary, {
      props: {
        timezone: 'Asia/Shanghai',
        quota: baseQuota,
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
          ...baseQuota,
          token_limit: null,
          tokens_used: '10',
          tokens_reserved: '2',
          amount_limit: null,
          amount_used: '1',
          amount_reserved: '0',
        },
      },
    })
    expect(wrapper.findAll('.ant-progress').length).toBe(2)
    expect(wrapper.find('[aria-label="额度使用明细"]').text()).toContain('不限')
  })
})

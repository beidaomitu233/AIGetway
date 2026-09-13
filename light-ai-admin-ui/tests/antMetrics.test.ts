import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MetricCard from '@/ui/metrics/MetricCard.vue'
import TrendChart from '@/components/TrendChart.vue'

describe('Ant Design metrics foundation', () => {
  it('renders a compact status metric card', () => {
    const wrapper = mount(MetricCard, {
      props: { label: '成功率', value: '98.2%', hint: '近 24 小时', status: 'success' },
    })
    expect(wrapper.text()).toContain('成功率')
    expect(wrapper.text()).toContain('98.2%')
    expect(wrapper.classes()).toContain('lai-metric-success')
  })

  it('uses an explicit empty state instead of drawing zero values', () => {
    const wrapper = mount(TrendChart, { props: { buckets: [], series: [] } })
    expect(wrapper.find('[role="img"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无趋势数据')
  })
})

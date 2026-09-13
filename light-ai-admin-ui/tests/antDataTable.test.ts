import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DataTable from '@/components/DataTable.vue'

interface Row {
  id: string
  name: string
}

describe('Ant Design data table foundation', () => {
  it('renders typed rows and scoped cell slots', () => {
    const wrapper = mount(DataTable<Row>, {
      props: {
        columns: [
          { key: 'name', label: '名称' },
          { key: 'id', label: '标识' },
        ],
        rows: [{ id: 'app-1', name: '示例应用' }],
        rowKey: row => row.id,
      },
      slots: {
        name: ({ row }: { row: Row }) => `应用：${row.name}`,
      },
    })
    expect(wrapper.text()).toContain('应用：示例应用')
    expect(wrapper.text()).toContain('app-1')
  })

  it('renders the explicit empty state when no rows are available', () => {
    const wrapper = mount(DataTable<Row>, {
      props: {
        columns: [{ key: 'name', label: '名称' }],
        rows: [],
        rowKey: row => row.id,
      },
    })
    expect(wrapper.text()).toContain('暂无数据')
  })
})

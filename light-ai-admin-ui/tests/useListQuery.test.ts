import { describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { type PageResult } from '@/api/contracts'
import { ApiError } from '@/api/errors'

interface Row {
  id: string
  name: string
}

type Fetcher = (params: Record<string, unknown>, signal: AbortSignal) => Promise<PageResult<Row>>

function pageResult(items: Row[], total = items.length): PageResult<Row> {
  return {
    items,
    total,
    page: 1,
    page_size: 20,
    sort: 'updated_at',
    query_started_at: '2026-09-05T10:00:00Z',
    data_updated_at: '2026-09-05T10:00:01Z',
  }
}

const Host = defineComponent({
  props: {
    fetcher: { type: Function, required: true },
  },
  setup(props) {
    const query = useListQuery<Record<string, FilterValue>, Row>({
      fields: {
        keyword: { default: '', url: true },
        status: { default: [], url: true },
        clientIp: { default: '', url: false },
      },
      defaultSort: 'updated_at',
      fetcher: props.fetcher as never,
    })
    return { ...query }
  },
  template: '<div>{{ items.length }}-{{ status }}</div>',
})

async function mountList(
  fetcher: Fetcher,
  initialQuery?: Record<string, string>,
): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/list', name: 'list', component: defineComponent({ template: '<div/>' }) }],
  })
  void router.push(initialQuery ? { path: '/list', query: initialQuery } : '/list')
  await router.isReady()
  const wrapper = mount(Host, {
    props: { fetcher: fetcher as never },
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('useListQuery', () => {
  it('初始加载并读取 URL 中的筛选条件', async () => {
    const fetcher = vi.fn((_params: Record<string, unknown>, _signal: AbortSignal) =>
      Promise.resolve(pageResult([{ id: 't-1', name: 'a' }])),
    )
    const { wrapper } = await mountList(fetcher as never, { keyword: 'openai', page: '2' })
    expect(fetcher).toHaveBeenCalledTimes(1)
    const params = fetcher.mock.calls[0][0] as Record<string, unknown>
    expect(params.keyword).toBe('openai')
    expect(params.page).toBe(2)
    expect(params.page_size).toBe(20)
    expect(params.sort).toBe('updated_at')
    expect((wrapper.vm as never as { status: string }).status).toBe('ready')
  })

  it('筛选变化同步 URL 且页码重置为 1', async () => {
    const fetcher = vi.fn((_params: Record<string, unknown>, _signal: AbortSignal) =>
      Promise.resolve(pageResult([])),
    )
    const { wrapper, router } = await mountList(fetcher as never)
    ;(wrapper.vm as never as { applyFilters: (p: unknown) => void }).applyFilters({
      keyword: 'deepseek',
    })
    await flushPromises()
    expect(router.currentRoute.value.query.keyword).toBe('deepseek')
    expect(router.currentRoute.value.query.page).toBe('1')
  })

  it('敏感筛选不进入 URL，仅保留在内存', async () => {
    const fetcher = vi.fn((_params: Record<string, unknown>, _signal: AbortSignal) =>
      Promise.resolve(pageResult([])),
    )
    const { wrapper, router } = await mountList(fetcher as never)
    ;(wrapper.vm as never as { applyFilters: (p: unknown) => void }).applyFilters({
      clientIp: '10.0.0.1',
    })
    await flushPromises()
    expect(router.currentRoute.value.query.clientIp).toBeUndefined()
    const latestParams = fetcher.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(latestParams.clientIp).toBe('10.0.0.1')
  })

  it('快速切换筛选只接受最新响应', async () => {
    let resolveFirst: (value: PageResult<Row>) => void = () => {}
    const first = new Promise<PageResult<Row>>((resolve) => {
      resolveFirst = resolve
    })
    const fetcher = vi
      .fn()
      .mockImplementationOnce((_params: Record<string, unknown>, _signal: AbortSignal) => first)
      .mockImplementationOnce(
        (_params: Record<string, unknown>, _signal: AbortSignal) =>
          Promise.resolve(pageResult([{ id: 't-2', name: 'second' }])),
      )
    const { wrapper } = await mountList(fetcher as never)
    ;(wrapper.vm as never as { applyFilters: (p: unknown) => void }).applyFilters({ keyword: 'b' })
    await flushPromises()
    resolveFirst(pageResult([{ id: 't-1', name: 'first' }]))
    await flushPromises()
    expect((wrapper.vm as never as { items: Row[] }).items).toEqual([
      { id: 't-2', name: 'second' },
    ])
  })

  it('错误不覆盖已有数据，重试后恢复', async () => {
    const fetcher = vi
      .fn()
      .mockImplementationOnce(
        (_params: Record<string, unknown>, _signal: AbortSignal) =>
          Promise.resolve(pageResult([{ id: 't-1', name: 'kept' }], 5)),
      )
      .mockImplementationOnce(
        (_params: Record<string, unknown>, _signal: AbortSignal) =>
          Promise.reject(
            new ApiError(
              503,
              {
                code: 'OBSERVATION_DATA_UNAVAILABLE',
                type: 'availability',
                message: '观测数据暂不可读',
                retryable: true,
              },
              'req-list-1',
            ),
          ),
      )
      .mockImplementationOnce(
        (_params: Record<string, unknown>, _signal: AbortSignal) =>
          Promise.resolve(pageResult([{ id: 't-2', name: 'new' }], 6)),
      )
    const { wrapper } = await mountList(fetcher as never)
    const vm = wrapper.vm as never as {
      status: string
      items: Row[]
      error: unknown
      refresh: () => void
    }
    vm.refresh()
    await flushPromises()
    expect(vm.status).toBe('error')
    expect(vm.items).toEqual([{ id: 't-1', name: 'kept' }])
    expect((vm.error as ApiError).code).toBe('OBSERVATION_DATA_UNAVAILABLE')
    vm.refresh()
    await flushPromises()
    expect(vm.status).toBe('ready')
    expect(vm.items).toEqual([{ id: 't-2', name: 'new' }])
  })

  it('浏览器后退还原筛选并重新查询', async () => {
    const fetcher = vi.fn((_params: Record<string, unknown>, _signal: AbortSignal) =>
      Promise.resolve(pageResult([])),
    )
    const { wrapper, router } = await mountList(fetcher as never)
    ;(wrapper.vm as never as { applyFilters: (p: unknown) => void }).applyFilters({ keyword: 'v2' })
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(2)
    await router.back()
    await flushPromises()
    expect(router.currentRoute.value.query.keyword).toBeUndefined()
    const latestParams = fetcher.mock.calls.at(-1)![0] as Record<string, unknown>
    expect(latestParams.keyword).toBe('')
    expect(fetcher).toHaveBeenCalledTimes(3)
  })
})

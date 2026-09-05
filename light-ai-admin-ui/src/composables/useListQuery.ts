import { onScopeDispose, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, TimeoutError, isAbortError } from '@/api/errors'
import { type PageResult } from '@/api/contracts'

export type FilterValue = string | number | boolean | string[] | null | undefined

export interface ListFieldDef {
  default: FilterValue
  /** 敏感筛选不写入 URL，仅保留当前页内存。 */
  url: boolean
}

export interface ListRequestParams {
  page: number
  page_size: number
  sort: string
}

export type ListStatus = 'loading' | 'ready' | 'error'

interface UseListQueryOptions<F extends Record<string, FilterValue>, TItem> {
  fields: { [K in keyof F]: ListFieldDef }
  defaultSort: string
  defaultPageSize?: number
  fetcher: (params: ListRequestParams & F, signal: AbortSignal) => Promise<PageResult<TItem>>
}

/**
 * 列表查询状态：分页/排序/筛选同步 URL，浏览器后退还原；
 * 筛选变化递增请求序号，只接受最新响应；页面离开中止在途请求。
 */
export function useListQuery<F extends Record<string, FilterValue>, TItem>(
  options: UseListQueryOptions<F, TItem>,
) {
  const route = useRoute()
  const router = useRouter()

  const state = reactive(
    Object.fromEntries(
      Object.entries(options.fields).map(([key, def]) => [key, def.default]),
    ),
  ) as F

  const page = ref(1)
  const pageSize = ref(options.defaultPageSize ?? 20)
  const sort = ref(options.defaultSort)
  const items = shallowRef<TItem[]>([])
  const total = ref(0)
  const status = ref<ListStatus>('loading')
  const refreshing = ref(false)
  const error = shallowRef<ApiError | TimeoutError | Error | null>(null)
  const queryStartedAt = ref('')
  const dataUpdatedAt = ref('')

  let seq = 0
  let controller: AbortController | null = null

  function isFilterKey(key: string): boolean {
    return Object.prototype.hasOwnProperty.call(options.fields, key)
  }

  function serializeStateToQuery(): Record<string, string | string[]> {
    const query: Record<string, string | string[]> = {}
    query.page = String(page.value)
    query.page_size = String(pageSize.value)
    if (sort.value) query.sort = sort.value
    for (const [key, def] of Object.entries(options.fields)) {
      if (!def.url) continue
      const value = state[key as keyof F]
      if (value === null || value === undefined || value === '' ) continue
      if (Array.isArray(value)) {
        if (value.length > 0) query[key] = [...value]
      } else {
        query[key] = String(value)
      }
    }
    return query
  }

  function queryEquals(a: Record<string, string | string[]>, b: Record<string, string | string[]>): boolean {
    const keysA = Object.keys(a)
    if (keysA.length !== Object.keys(b).length) return false
    return keysA.every((key) => {
      const va = a[key]
      const vb = b[key]
      if (Array.isArray(va) && Array.isArray(vb)) {
        return va.length === vb.length && va.every((item, i) => item === vb[i])
      }
      return va === vb
    })
  }

  function parseFilterValue(raw: string | string[] | undefined, def: ListFieldDef): FilterValue {
    if (raw === undefined) return def.default
    if (Array.isArray(def.default) || Array.isArray(raw)) {
      const list = Array.isArray(raw) ? raw : [raw]
      return list.length > 0 ? list : def.default
    }
    if (typeof def.default === 'number') {
      const parsed = Number(raw)
      return Number.isFinite(parsed) ? parsed : def.default
    }
    if (typeof def.default === 'boolean') {
      return raw === 'true'
    }
    return raw
  }

  function readQueryIntoState(): void {
    const query = route.query
    page.value = parsePositiveInt(query.page, 1)
    pageSize.value = parsePositiveInt(query.page_size, options.defaultPageSize ?? 20)
    sort.value = typeof query.sort === 'string' && query.sort !== '' ? query.sort : options.defaultSort
    for (const [key, def] of Object.entries(options.fields)) {
      const raw = query[key]
      const value = def.url ? parseFilterValue(raw as string | string[] | undefined, def) : def.default
      ;(state as Record<string, FilterValue>)[key] = value
    }
  }

  function parsePositiveInt(raw: unknown, fallback: number): number {
    if (typeof raw !== 'string' || raw === '') return fallback
    const parsed = Number(raw)
    if (!Number.isInteger(parsed) || parsed < 1) return fallback
    return parsed
  }

  async function fetchPage(): Promise<void> {
    seq += 1
    const currentSeq = seq
    controller?.abort()
    controller = new AbortController()
    if (items.value.length > 0 || total.value > 0) refreshing.value = true
    try {
      const params = {
        ...state,
        page: page.value,
        page_size: pageSize.value,
        sort: sort.value,
      } as ListRequestParams & F
      const result = await options.fetcher(params, controller.signal)
      if (currentSeq !== seq) return
      items.value = result.items
      total.value = result.total
      queryStartedAt.value = result.query_started_at
      dataUpdatedAt.value = result.data_updated_at
      error.value = null
      status.value = 'ready'
      refreshing.value = false
    } catch (e) {
      if (currentSeq !== seq || isAbortError(e)) return
      error.value = e instanceof ApiError || e instanceof TimeoutError ? e : new Error('网络请求失败')
      status.value = 'error'
      refreshing.value = false
    }
  }

  function pushQuery(replace: boolean): void {
    const query = serializeStateToQuery()
    if (queryEquals(query, route.query as Record<string, string | string[]>)) return
    const navigate = replace ? router.replace : router.push
    void navigate({ query: query as never })
  }

  function applyFilters(partial: Partial<F>): void {
    for (const [key, value] of Object.entries(partial)) {
      if (!isFilterKey(key)) continue
      ;(state as Record<string, FilterValue>)[key] = value
    }
    page.value = 1
    pushQuery(false)
    void fetchPage()
  }

  function applySort(value: string): void {
    sort.value = value
    page.value = 1
    pushQuery(false)
    void fetchPage()
  }

  function applyPage(value: number): void {
    page.value = value
    pushQuery(false)
    void fetchPage()
  }

  function applyPageSize(value: number): void {
    pageSize.value = value
    page.value = 1
    pushQuery(false)
    void fetchPage()
  }

  function refresh(): void {
    void fetchPage()
  }

  readQueryIntoState()
  void fetchPage()

  watch(
    () => route.query,
    (query) => {
      if (queryEquals(query as Record<string, string | string[]>, serializeStateToQuery())) return
      readQueryIntoState()
      void fetchPage()
    },
  )

  onScopeDispose(() => {
    controller?.abort()
  })

  return {
    state,
    page,
    pageSize,
    sort,
    items,
    total,
    status,
    refreshing,
    error,
    queryStartedAt,
    dataUpdatedAt,
    applyFilters,
    applySort,
    applyPage,
    applyPageSize,
    refresh,
  }
}

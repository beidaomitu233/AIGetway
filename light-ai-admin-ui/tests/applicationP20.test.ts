import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { routes } from '@/app/router'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'
import { application } from './fixtures/application'
import { dataEnvelope, errorEnvelope, installJsonFetchStub, pageEnvelope } from './helpers/fetchStub'
import { amountUsage, decimalUnits, positiveAmount, validIpRule } from '@/pages/applications/applicationValues'

enableAutoUnmount(afterEach)
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
async function page(path: string, permissions: string[] = [...bootstrapFixtures.SYSTEM_ADMIN.permissions]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useBootstrapStore()
  store.$patch({ status: 'ready', userId: 'admin', displayName: '管理员', timezone: 'Asia/Shanghai', permissions })
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router, pinia] } })
  await flushPromises()
  return { wrapper, router, store }
}
function button(wrapper: VueWrapper, text: string) { return wrapper.findAll('button').find(item => item.text() === text)! }
function baseStub() {
  return installJsonFetchStub(({ url }) => {
    if (url.pathname.endsWith('/model-aliases')) return pageEnvelope([])
    if (url.pathname.endsWith('/keys') || url.pathname.endsWith('/quota/adjustments') || url.pathname.endsWith('/members')) return dataEnvelope([])
    return dataEnvelope(application)
  })
}
async function fillCreate(wrapper: VueWrapper) {
  for (const [name, value] of Object.entries({ name: '测试应用', code: 'test-app', token_limit: '100', amount_limit: '10.12345678', currency: 'CNY', rpm: '20', tpm: '1000' })) {
    await wrapper.get(`input[name="${name}"]`).setValue(value)
  }
}
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }) }

describe('FE-P20 页面边界（同契约夹具，非真实联调）', () => {
  it('金额保留 8 位小数及大整数精度', () => {
    expect(amountUsage('9007199254740993.00000001', '0.00000002', '9007199254740994', 'CNY')).toBe('9007199254740993.00000003 / 9007199254740994.00 CNY')
    expect(positiveAmount('NaN')).toBe(false)
    expect(positiveAmount('1e3')).toBe(false)
    expect(positiveAmount('1.000000001')).toBe(false)
    expect(decimalUnits('-0.00000001')).toBe(-1n)
    expect(amountUsage('broken', '0', '1', 'CNY')).toBe('金额数据异常')
  })
  it.each(['127.0.0.1', '10.0.0.0/8', '2001:db8::1/64', '::1', '::ffff:192.0.2.1'])('接受有效 IP/CIDR：%s', value => expect(validIpRule(value)).toBe(true))
  it.each(['999.1.1.1', '10.0.0.1/33', '::1/129', ':::', 'https://example.com', '01.2.3.4', '1.2.3.4/-1'])('拒绝非法 IP/CIDR：%s', value => expect(validIpRule(value)).toBe(false))

  it('列表筛选与排序写 URL，后退还原，空结果不误报未创建', async () => {
    const stub = installJsonFetchStub(() => pageEnvelope([]))
    const { wrapper, router } = await page('/ui/applications?owner_id=alice')
    expect(wrapper.text()).toContain('没有符合筛选条件')
    await wrapper.get('[aria-label="负责人账号筛选"]').setValue('bob')
    await flushPromises()
    await wrapper.get('[aria-label="排序"]').setValue('name asc')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ owner_id: 'bob', sort: 'name asc' })
    expect(stub.calls.at(-1)?.url).toContain('owner_id=bob')
    router.back()
    await vi.waitFor(() => expect(router.currentRoute.value.query.sort).toBe('last_called_at desc'))
    await flushPromises()
    expect(wrapper.get('[aria-label="排序"]').element).toHaveProperty('value', 'last_called_at desc')
  })
  it('首次 loading 可见，过期请求响应不能覆盖新筛选结果', async () => {
    const requests: { signal: AbortSignal; resolve: (response: Response) => void }[] = []
    vi.stubGlobal('fetch', vi.fn((_input: unknown, init: RequestInit) => new Promise<Response>(resolve => requests.push({ signal: init.signal as AbortSignal, resolve }))))
    const { wrapper } = await page('/ui/applications')
    expect(wrapper.find('[aria-label="加载中"]').exists()).toBe(true)
    await wrapper.get('input[type="search"]').setValue('new')
    await flushPromises()
    expect(requests[0]!.signal.aborted).toBe(true)
    requests.at(-1)!.resolve(json(pageEnvelope([{ ...application, name: '最新应用' }]).body))
    await flushPromises()
    requests[0]!.resolve(json(pageEnvelope([{ ...application, name: '过期应用' }]).body))
    await flushPromises()
    expect(wrapper.text()).toContain('最新应用')
    expect(wrapper.text()).not.toContain('过期应用')
    await wrapper.get('input[type="search"]').setValue('pending')
    await flushPromises()
    wrapper.unmount()
    expect(requests.at(-1)!.signal.aborted).toBe(true)
  })
  it('刷新失败保留已有列表并显示错误，不能变成空列表', async () => {
    let failed = false
    installJsonFetchStub(() => failed ? errorEnvelope(503, 'UNAVAILABLE', '服务暂不可用') : pageEnvelope([application]))
    const { wrapper } = await page('/ui/applications')
    failed = true
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(application.name)
    expect(wrapper.text()).toContain('服务暂不可用')
    expect(wrapper.text()).not.toContain('尚未创建应用')
  })
  it('无列表权限不发请求，403 显示明确错误', async () => {
    const stub = baseStub()
    const { wrapper } = await page('/ui/applications', [])
    expect(stub.calls).toHaveLength(0)
    expect(wrapper.text()).toContain('无权查看应用')
    expect(wrapper.text()).not.toContain('新建应用')
  })
  it('服务端跨应用 403 不读取子资源', async () => {
    const stub = installJsonFetchStub(() => errorEnvelope(403, 'ACCESS_DENIED', '无权访问此应用'))
    const { wrapper } = await page(`/ui/applications/${application.id}`)
    expect(stub.calls).toHaveLength(1)
    expect(wrapper.text()).toContain('无权访问此应用')
    expect(wrapper.text()).not.toContain('接入密钥')
  })
  it('无密钥权限不加载或渲染密钥页签', async () => {
    const stub = baseStub()
    const { wrapper } = await page(`/ui/applications/${application.id}?tab=keys`, [Permission.applicationView])
    expect(stub.calls.some(call => call.url.endsWith('/keys'))).toBe(false)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).not.toContain('接入密钥')
    expect(wrapper.find('a[href*="audit-logs"]').exists()).toBe(false)
  })
  it('同一详情组件切换应用时清理旧数据和旧请求，页签后退同步', async () => {
    const stub = installJsonFetchStub(({ url }) => {
      if (url.pathname.endsWith('/members') || url.pathname.endsWith('/keys') || url.pathname.endsWith('/quota/adjustments')) return dataEnvelope([])
      return dataEnvelope(url.pathname.endsWith('/other') ? { ...application, id: 'other', name: '另一应用' } : application)
    })
    const { wrapper, router } = await page(`/ui/applications/${application.id}`)
    await router.push(`/ui/applications/other?tab=members`)
    await flushPromises()
    expect(wrapper.text()).toContain('另一应用')
    expect(wrapper.text()).not.toContain(application.name)
    expect(stub.calls.some(call => call.url.endsWith('/other/members'))).toBe(true)
    await router.push('/ui/applications/other?tab=quota')
    await flushPromises()
    expect(wrapper.find('[role="tab"][aria-selected="true"]').text()).toBe('额度与速率')
  })
  it('成员错误保持详情其他页签数据并可重试', async () => {
    installJsonFetchStub(({ url }) => url.pathname.endsWith('/members') ? errorEnvelope(503, 'UNAVAILABLE', '成员源不可用') : url.pathname.endsWith('/keys') || url.pathname.endsWith('/quota/adjustments') ? dataEnvelope([]) : dataEnvelope(application))
    const { wrapper } = await page(`/ui/applications/${application.id}?tab=members`)
    expect(wrapper.text()).toContain('成员加载失败')
    expect(wrapper.text()).toContain(application.name)
    expect(button(wrapper, '概览').exists()).toBe(true)
  })
  it('创建必须明确填写额度，非法金额与小数 Token 禁止提交', async () => {
    const stub = baseStub()
    const { wrapper } = await page('/ui/applications/new')
    expect(wrapper.get('input[name="amount_limit"]').element).toHaveProperty('value', '')
    await fillCreate(wrapper)
    await wrapper.get('input[name="amount_limit"]').setValue('NaN')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[data-test="save-application"]').attributes('disabled')).toBeDefined()
    await wrapper.get('input[name="amount_limit"]').setValue('10')
    await wrapper.get('input[name="token_limit"]').setValue('1.2')
    await wrapper.get('form').trigger('submit')
    expect(stub.calls.filter(call => call.method === 'POST')).toHaveLength(0)
  })
  it('无限制显示风险且保存摘要需要再次确认', async () => {
    const stub = baseStub()
    const { wrapper } = await page('/ui/applications/new')
    await fillCreate(wrapper)
    await wrapper.findAll('input[type="checkbox"]')[0]!.setValue(false)
    expect(wrapper.text()).toContain('已选择无限制')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.find('[aria-label="保存摘要"]').exists()).toBe(true)
    expect(stub.calls.filter(call => call.method === 'POST')).toHaveLength(0)
  })
  it('编辑不依赖模型目录，409 保留输入并显式对比新版本', async () => {
    let latest = false
    const stub = installJsonFetchStub(({ method }) => {
      if (method === 'PUT') { latest = true; return errorEnvelope(409, 'CONFIG_VERSION_CONFLICT', '版本冲突') }
      return dataEnvelope(latest ? { ...application, name: '服务器新名称', version: 3 } : application)
    })
    const { wrapper } = await page(`/ui/applications/${application.id}/settings`)
    expect(stub.calls).toHaveLength(1)
    await wrapper.get('input[name="name"]').setValue('我的修改')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('您的输入已保留')
    await button(wrapper, '读取最新版本并对比').trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-label="版本差异"]').text()).toContain('服务器新名称')
    expect(wrapper.get('input[name="name"]').element).toHaveProperty('value', '我的修改')
    await button(wrapper, '已核对，保留输入并使用最新版本').trigger('click')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(stub.calls.filter(call => call.method === 'PUT').at(-1)?.body.version).toBe(3)
  })
  it('未知应用状态保留状态文本并禁用写操作', async () => {
    installJsonFetchStub(({ url }) => url.pathname.endsWith('/keys') || url.pathname.endsWith('/quota/adjustments') ? dataEnvelope([]) : dataEnvelope({ ...application, status: 'FROZEN' }))
    const { wrapper } = await page(`/ui/applications/${application.id}`)
    expect(wrapper.text()).toContain('FROZEN')
    expect(button(wrapper, '管理授权')).toBeUndefined()
    expect(button(wrapper, '编辑策略')).toBeUndefined()
  })
  it('密钥非法 IP 和超应用 RPM 不能签发，复制失败保留一次性弹窗', async () => {
    const stub = installJsonFetchStub(({ url, method }) => {
      if (url.pathname.endsWith('/keys') && method === 'POST') return dataEnvelope({ key_id: 'key-1', application_id: application.id, key_value: 'fixture-key-once', version: 1 })
      if (url.pathname.endsWith('/keys') || url.pathname.endsWith('/quota/adjustments')) return dataEnvelope([])
      return dataEnvelope(application)
    })
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    const { wrapper } = await page(`/ui/applications/${application.id}`)
    await button(wrapper, '签发密钥').trigger('click')
    const form = wrapper.get('form[aria-label="签发应用密钥"]')
    await form.get('input').setValue('测试密钥')
    await form.get('textarea').setValue('999.1.1.1')
    await form.trigger('submit')
    expect(stub.calls.filter(call => call.method === 'POST')).toHaveLength(0)
    await form.get('textarea').setValue('10.0.0.0/8')
    await form.findAll('input[type="number"]')[0]!.setValue('61')
    await form.trigger('submit')
    expect(stub.calls.filter(call => call.method === 'POST')).toHaveLength(0)
    await form.findAll('input[type="number"]')[0]!.setValue('20')
    await form.trigger('submit')
    await flushPromises()
    await button(wrapper, '复制密钥').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('复制失败')
    expect(wrapper.text()).toContain('fixture-key-once')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    await button(wrapper, '我已保存').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('fixture-key-once')
    expect(stub.calls.filter(call => call.method === 'POST')).toHaveLength(1)
  })
  it('撤销取消不写入，失败保留原因且不能误报成功', async () => {
    const stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'POST') return errorEnvelope(503, 'UNAVAILABLE', '撤销未完成')
      if (url.pathname.endsWith('/keys')) return dataEnvelope([{ id: 'key-1', name: '密钥一', status: 'ACTIVE', virtual_model_ids: [], masked_value: 'lai_****', rotation_generation: 1, version: 2 }])
      if (url.pathname.endsWith('/quota/adjustments')) return dataEnvelope([])
      return dataEnvelope(application)
    })
    const { wrapper } = await page(`/ui/applications/${application.id}`)
    await wrapper.get('[data-test="key-revoke-key-1"]').trigger('click')
    await button(wrapper, '取消').trigger('click')
    expect(stub.calls.filter(call => call.method === 'POST')).toHaveLength(0)
    await wrapper.get('[data-test="key-revoke-key-1"]').trigger('click')
    const form = wrapper.get('[aria-labelledby="application-key-action-title"]')
    await form.get('textarea').setValue('停用旧服务')
    await form.trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('撤销未完成')
    expect(form.get('textarea').element).toHaveProperty('value', '停用旧服务')
  })
  it('额度预览与低于用量警告使用精确值，403 写入失败保留表单', async () => {
    const stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'PUT') return errorEnvelope(403, 'ACCESS_DENIED', '额度操作无权限')
      if (url.pathname.endsWith('/keys') || url.pathname.endsWith('/quota/adjustments')) return dataEnvelope([])
      return dataEnvelope(application)
    })
    const { wrapper } = await page(`/ui/applications/${application.id}?tab=quota`)
    expect(wrapper.find('[aria-label="额度使用明细"]').text()).toContain('987.00')
    await button(wrapper, '编辑策略').trigger('click')
    const dialog = wrapper.get('[aria-labelledby="application-quota-title"]')
    await dialog.findAll('input[type="number"]')[0]!.setValue('100')
    await dialog.get('textarea').setValue('控制预算')
    expect(dialog.text()).toContain('保存后立即停止新请求')
    const save = dialog.findAll('button').find(b => b.text().includes('保存'))!
    await save.trigger('click')
    await flushPromises()
    expect(stub.calls.some(call => call.method === 'PUT')).toBe(true)
    expect(wrapper.text()).toContain('额度操作无权限')
    expect(dialog.get('textarea').element).toHaveProperty('value', '控制预算')
  })

  it('权限失效的刷新响应清除旧列表，不泄露之前的应用', async () => {
    let denied = false
    installJsonFetchStub(() => denied ? errorEnvelope(403, 'ACCESS_DENIED', '范围已收回') : pageEnvelope([application]))
    const { wrapper } = await page('/ui/applications')
    denied = true
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('范围已收回')
    expect(wrapper.text()).not.toContain(application.name)
  })
  it('签发中重复提交只发送一次，身份切换后丢弃迟到的密钥原文', async () => {
    let finish: ((response: Response) => void) | undefined
    let posts = 0
    vi.stubGlobal('fetch', vi.fn((input: string, init: RequestInit) => {
      if (init.method === 'POST') { posts++; return new Promise<Response>(resolve => { finish = resolve }) }
      return Promise.resolve(json({ data: input.endsWith('/keys') || input.endsWith('/quota/adjustments') ? [] : application }))
    }))
    const { wrapper, store } = await page(`/ui/applications/${application.id}`)
    await button(wrapper, '签发密钥').trigger('click')
    const form = wrapper.get('form[aria-label="签发应用密钥"]')
    await form.get('input').setValue('待签发密钥')
    await form.trigger('submit')
    await form.trigger('submit')
    expect(posts).toBe(1)
    store.$patch({ userId: 'new-user', permissions: [Permission.applicationView] })
    await flushPromises()
    finish!(json({ data: { key_value: 'late-fixture-secret', application_id: application.id, key_id: 'key-late' } }))
    await flushPromises()
    expect(wrapper.text()).not.toContain('late-fixture-secret')
    expect(wrapper.find('#application-secret-title').exists()).toBe(false)
  })
  it('创建表单切换身份清除上一身份输入', async () => {
    baseStub()
    const { wrapper, store } = await page('/ui/applications/new')
    await fillCreate(wrapper)
    store.$patch({ userId: 'next-user', displayName: '下一用户' })
    await flushPromises()
    expect(wrapper.get('input[name="name"]').element).toHaveProperty('value', '')
    expect(wrapper.get('input[name="owner_id"]').element).toHaveProperty('value', 'next-user')
    expect(wrapper.get('input[name="amount_limit"]').element).toHaveProperty('value', '')
  })})

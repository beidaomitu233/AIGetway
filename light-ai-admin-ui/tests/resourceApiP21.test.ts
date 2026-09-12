import { afterEach, describe, expect, it } from 'vitest'
import { createCandidate, updateCandidate, deleteCandidate, checkCandidate } from '@/api/modelAliases'
import { rotateCredential, updateCredential, fetchCredentials } from '@/api/credentials'
import { fetchProviderModels, startBatchCheck, fetchBatchCheckJob } from '@/api/providerModels'
import { dataEnvelope, pageEnvelope, installJsonFetchStub, type FetchStub } from './helpers/fetchStub'
let stub: FetchStub
afterEach(() => stub?.restore())
describe('FE-P21 已交付 HTTP 契约', () => {
  it('候选创建与修改绑定父虚拟模型，允许零权重', async () => {
    stub = installJsonFetchStub(() => dataEnvelope({ id: 'route', version: 2 }))
    const body = { upstream_model_id: 'model', channel_id: 'channel', priority: 1, weight: 0, enabled: true }
    await createCandidate('virtual', body)
    await updateCandidate('virtual', 'route', { ...body, version: 1 })
    await deleteCandidate('virtual', 'route', 2)
    await checkCandidate('virtual', 'route', { upstream_model_id: 'model', channel_credential_id: 'key', mode: 'MINIMAL_CHAT', timeout_ms: 1000 })
    expect(stub.calls.map((call) => new URL(call.url, 'http://localhost').pathname)).toEqual(['/admin/virtual-models/virtual/routes', '/admin/virtual-models/virtual/routes/route', '/admin/virtual-models/virtual/routes/route', '/admin/virtual-models/virtual/routes/route/check'])
    expect(stub.calls[0]!.body).toMatchObject(body)
    expect(stub.calls[0]!.body).not.toHaveProperty('credential_pool_id')
  })
  it('渠道 Key 读取、修改和轮换均使用嵌套路由', async () => {
    stub = installJsonFetchStub(({ method }) => method === 'GET' ? pageEnvelope([]) : dataEnvelope({ id: 'key', version: 2 }))
    await fetchCredentials('channel', {})
    await updateCredential('channel', 'key', { name: '测试', weight: 1, enabled: true, version: 1 })
    await rotateCredential('channel', 'key', { secret_value: 'test-fixture', secret_value_confirm: 'test-fixture', version: 1 })
    expect(stub.calls.map((call) => new URL(call.url, 'http://localhost').pathname)).toEqual(['/admin/channels/channel/credentials', '/admin/channels/channel/credentials/key', '/admin/channels/channel/credentials/key/rotate'])
  })
  it('上游查询和批量检测使用 channel_id 与 upstream_model_ids', async () => {
    stub = installJsonFetchStub(({ method }) => method === 'GET' ? pageEnvelope([]) : dataEnvelope({ id: 'job', status: 'RUNNING' }))
    await fetchProviderModels({ channel_id: 'channel' })
    await startBatchCheck({ channel_id: 'channel', upstream_model_ids: ['model'], channel_credential_id: 'key', mode: 'MINIMAL_CHAT', timeout_ms: 1000 })
    expect(stub.calls[0]!.url).toContain('/admin/upstream-models?channel_id=channel')
    expect(stub.calls[1]!.body).toMatchObject({ channel_id: 'channel', upstream_model_ids: ['model'], channel_credential_id: 'key' })
  })
  it('批量详情消费服务端平铺字段，不假设额外 job 包装', async () => {
    stub = installJsonFetchStub(() => dataEnvelope({ id: 'job', status: 'PARTIAL_FAILED', total_count: 2, completed_count: 2, items: [{ id: 'i', upstream_model_id: 'model', status: 'FAILED', error_code: 'UPSTREAM_TIMEOUT' }] }))
    const detail = await fetchBatchCheckJob('job')
    expect(detail.job.status).toBe('PARTIAL_FAILED')
    expect(detail.items[0]!.upstream_model_id).toBe('model')
  })
})

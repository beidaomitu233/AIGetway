import { afterEach, describe, expect, it, vi } from "vitest"
import { createPinia, setActivePinia } from "pinia"
import { createMemoryHistory, createRouter } from "vue-router"
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils"
import { routes } from "@/app/router"
import { Permission } from "@/app/permissions"
import { useBootstrapStore } from "@/stores/bootstrap"
import { dataEnvelope, errorEnvelope, installJsonFetchStub } from "./helpers/fetchStub"

enableAutoUnmount(afterEach)
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

const appId = "00000000-0000-0000-0000-000000000001"
const policy = {
  id: "00000000-0000-0000-0000-000000000010",
  version: 1,
  enabled: true,
  keyword_action: "BLOCK",
  anomaly_window_seconds: 60,
  anomaly_request_threshold: 10,
  anomaly_token_threshold: null,
  anomaly_amount_threshold: null,
  anomaly_block_seconds: 300,
  whitelist_mode: "OFF",
  keywords: [{
    id: "00000000-0000-0000-0000-000000000011",
    keyword: "secret",
    match_type: "CONTAINS",
    ignore_case: true,
    application_id: null,
    action: "BLOCK",
    enabled: true,
  }],
  whitelist_application_ids: [],
}

async function page(permissions: string[] = [Permission.riskControlView, Permission.riskControlManage]) {
  setActivePinia(createPinia())
  const store = useBootstrapStore()
  store.$patch({ status: "ready", userId: "admin", displayName: "管理员", permissions })
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push("/ui/risk-control")
  await router.isReady()
  const wrapper = mount({ template: "<RouterView />" }, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

function stubPolicy() {
  return installJsonFetchStub(({ url, method }) => {
    if (url.pathname === "/admin/risk-control/policy" && method === "GET") return dataEnvelope(policy)
    if (url.pathname === "/admin/risk-control/applications") {
      return dataEnvelope([{ id: appId, code: "demo", name: "演示应用", status: "ACTIVE" }])
    }
    if (url.pathname === "/admin/risk-control/events") {
      return dataEnvelope([{ id: "event-1", created_at: "2026-09-15T00:00:00Z", application_id: appId, request_id: "req-1", event_type: "KEYWORD", action: "BLOCK", rule_id: policy.keywords[0].id, reason: "关键词规则命中" }])
    }
    if (url.pathname === "/admin/risk-control/policy" && method === "PUT") return dataEnvelope({ ...policy, version: 2 })
    return errorEnvelope(404, "OBJECT_NOT_FOUND", "not found")
  })
}

describe("risk control page", () => {
  it("loads one policy page and filters events by application/type/time", async () => {
    const stub = stubPolicy()
    const { wrapper } = await page()
    expect(wrapper.text()).toContain("风险控制")
    expect(wrapper.text()).toContain("secret")
    expect(wrapper.text()).toContain("作用于全部应用")
    expect(stub.calls.filter(call => call.url.includes("/admin/risk-control/")).map(call => call.url)).toEqual(expect.arrayContaining([
      expect.stringContaining("/policy"),
      expect.stringContaining("/applications"),
      expect.stringContaining("/events?limit=50"),
    ]))
    const inputs = wrapper.findAll("input")
    const from = inputs.find(input => input.attributes("placeholder")?.includes("开始时间"))!
    const to = inputs.find(input => input.attributes("placeholder")?.includes("结束时间"))!
    await from.setValue("2026-09-14T00:00:00Z")
    await to.setValue("2026-09-15T00:00:00Z")
    const filterButton = wrapper.findAll("button").find(button => button.text().replace(/\s/g, "") === "筛选")!
    await filterButton.trigger("click")
    await flushPromises()
    const latest = stub.calls.at(-1)!
    expect(latest.url).toContain("from=2026-09-14T00%3A00%3A00Z")
    expect(latest.url).toContain("to=2026-09-15T00%3A00%3A00Z")
  })

  it("requires a reason and sends a versioned policy replacement", async () => {
    const stub = stubPolicy()
    const { wrapper } = await page()
    const save = wrapper.findAll("button").find(button => button.text().replace(/\s/g, "") === "保存风险策略")!
    expect(save.attributes("disabled")).toBeDefined()
    const reason = wrapper.find('input[placeholder*="变更原因"]')
    await reason.setValue("启用关键词规则")
    await flushPromises()
    await save.trigger("click")
    await flushPromises()
    const put = stub.calls.find(call => call.method === "PUT")!
    expect(put.body).toMatchObject({ version: 1, reason: "启用关键词规则", keywords: [{ keyword: "secret", id: policy.keywords[0].id }] })
    expect(wrapper.text()).toContain("v2")
  })

  it("does not render write controls for a read-only risk viewer", async () => {
    const stub = stubPolicy()
    const { wrapper } = await page([Permission.riskControlView])
    expect(stub.calls.some(call => call.method === "PUT")).toBe(false)
    expect(wrapper.text()).toContain("风险控制")
    expect(wrapper.text()).not.toContain("保存风险策略")
    expect(wrapper.find("button").text()).toMatch(/刷\s*新/)
  })

  it("keeps policy visible when event query fails", async () => {
    installJsonFetchStub(({ url }) => {
      if (url.pathname.endsWith("/policy")) return dataEnvelope(policy)
      if (url.pathname.endsWith("/applications")) return dataEnvelope([])
      if (url.pathname.endsWith("/events")) return errorEnvelope(503, "UNAVAILABLE", "风险事件服务暂不可用")
      return errorEnvelope(404, "OBJECT_NOT_FOUND", "not found")
    })
    const { wrapper } = await page()
    expect(wrapper.text()).toContain("风险控制")
    expect(wrapper.text()).toContain("风险事件服务暂不可用")
    expect(wrapper.text()).toContain("secret")
  })
})


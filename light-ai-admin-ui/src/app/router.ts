import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router'
import { routerBase } from './runtimeConfig'
import { Permission } from './permissions'

const placeholder = () => import('@/components/ModulePlaceholder.vue')

/** 已实现模块的页面组件；未实现模块使用 ModulePlaceholder，随任务包替换。 */
const pages = {
  providerList: () => import('@/pages/providers/ProviderListPage.vue'),
  providerForm: () => import('@/pages/providers/ProviderFormPage.vue'),
  providerDetail: () => import('@/pages/providers/ProviderDetailPage.vue'),
  poolList: () => import('@/pages/credentialPools/PoolListPage.vue'),
  poolForm: () => import('@/pages/credentialPools/PoolFormPage.vue'),
  poolDetail: () => import('@/pages/credentialPools/PoolDetailPage.vue'),
  traceList: () => import('@/pages/traces/TraceListPage.vue'),
  traceDetail: () => import('@/pages/traces/TraceDetailPage.vue'),
  overview: () => import('@/pages/overview/OverviewPage.vue'),
  usage: () => import('@/pages/usage/UsagePage.vue'),
  usageAdjustments: () => import('@/pages/usage/UsageAdjustmentsPage.vue'),
  auditList: () => import('@/pages/audit/AuditListPage.vue'),
  auditDetail: () => import('@/pages/audit/AuditDetailPage.vue'),
  riskControl: () => import('@/pages/risk/RiskControlPage.vue'),
  applicationList: () => import('@/pages/applications/ApplicationListPage.vue'),
  applicationForm: () => import('@/pages/applications/ApplicationFormPage.vue'),
  applicationDetail: () => import('@/pages/applications/ApplicationDetailPage.vue'),
  applicationIntegration: () => import('@/pages/applications/ApplicationIntegrationPage.vue'),
}

function moduleRoute(
  name: string,
  path: string,
  title: string,
  permission: string,
  component?: RouteRecordRaw['component'],
): RouteRecordRaw {
  return { path, name, component: component ?? placeholder, meta: { title, permission } }
}

/** 全部页面路由按 FRONTEND_PLAN 第 2 节注册；未实现模块由 ModulePlaceholder 承接，随任务包替换。 */
export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/ui/applications' },
  { path: '/ui', redirect: '/ui/applications' },
  { path: '/ui/', redirect: '/ui/applications' },
  moduleRoute('overview', '/ui/overview', '运行概览', Permission.overviewView, pages.overview),

  moduleRoute('application-list', '/ui/applications', '应用', Permission.applicationView, pages.applicationList),
  moduleRoute('application-new', '/ui/applications/new', '新建应用', Permission.applicationManage, pages.applicationForm),
  moduleRoute('application-detail', '/ui/applications/:id', '应用详情', Permission.applicationView, pages.applicationDetail),
  moduleRoute('application-edit', '/ui/applications/:id/settings', '编辑应用', Permission.applicationManage, pages.applicationForm),
  moduleRoute('application-integration', '/ui/applications/:id/integration', '开发接入', Permission.applicationView, pages.applicationIntegration),

  moduleRoute('provider-list', '/ui/channels', '渠道', Permission.providerView, pages.providerList),
  moduleRoute('provider-new', '/ui/channels/new', '新建渠道', Permission.providerManage, pages.providerForm),
  moduleRoute('provider-detail', '/ui/channels/:id', '渠道详情', Permission.providerView, pages.providerDetail),
  moduleRoute('provider-edit', '/ui/channels/:id/edit', '编辑渠道', Permission.providerManage, pages.providerForm),

  { path: '/ui/credential-pools/:pathMatch(.*)*', redirect: '/ui/channels' },

  // 旧模型、虚拟模型、限流、可靠性和熔断页面已并入应用/渠道工作台；
  // 保留只读重定向，避免历史书签落入失效页面。
  { path: '/ui/models/:pathMatch(.*)*', redirect: '/ui/applications' },
  { path: '/ui/limit-policies/:pathMatch(.*)*', redirect: '/ui/applications' },
  { path: '/ui/reliability-policies/:pathMatch(.*)*', redirect: '/ui/channels' },
  { path: '/ui/circuits/:pathMatch(.*)*', redirect: '/ui/channels' },

  moduleRoute('trace-list', '/ui/traces', 'Trace', Permission.traceView, pages.traceList),
  moduleRoute('trace-detail', '/ui/traces/:traceId', 'Trace 详情', Permission.traceView, pages.traceDetail),

  moduleRoute('usage', '/ui/usage', 'Usage 与 Cost', Permission.usageView, pages.usage),
  moduleRoute('usage-adjustments', '/ui/usage/adjustments', '额度流水', Permission.applicationQuotaView, pages.usageAdjustments),

  // 系统管理仅保留审计；发布、运行参数和旧访问凭证不再提供独立页面。
  { path: '/ui/config/:pathMatch(.*)*', redirect: '/ui/applications' },
  { path: '/ui/runtime-config', redirect: '/ui/applications' },
  { path: '/ui/access-credentials/:pathMatch(.*)*', redirect: '/ui/applications' },

  moduleRoute('risk-control', '/ui/risk-control', '风险控制', Permission.riskControlView, pages.riskControl),

  moduleRoute('audit-list', '/ui/audit-logs', '审计日志', Permission.auditView, pages.auditList),
  moduleRoute('audit-detail', '/ui/audit-logs/:id', '审计详情', Permission.auditView, pages.auditDetail),

  { path: '/ui/developer-access', redirect: '/ui/applications' },

  {
    path: '/ui/forbidden',
    name: 'forbidden',
    component: () => import('@/pages/forbidden/ForbiddenPage.vue'),
    meta: { title: '无访问权限', public: true },
  },
  {
    path: '/ui/not-found',
    name: 'not-found',
    component: () => import('@/pages/notFound/NotFoundPage.vue'),
    meta: { title: '页面不存在', public: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found-fallback',
    component: () => import('@/pages/notFound/NotFoundPage.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

export function createAppRouter(): Router {
  return createRouter({
    history: createWebHistory(routerBase()),
    routes,
  })
}

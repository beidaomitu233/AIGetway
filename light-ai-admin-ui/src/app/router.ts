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
  { path: '/', redirect: '/ui/overview' },
  moduleRoute('overview', '/ui/overview', '运行概览', Permission.overviewView, pages.overview),

  moduleRoute('provider-list', '/ui/providers', 'Provider', Permission.providerView, pages.providerList),
  moduleRoute('provider-new', '/ui/providers/new', '新建 Provider', Permission.providerManage, pages.providerForm),
  moduleRoute('provider-detail', '/ui/providers/:id', 'Provider 详情', Permission.providerView, pages.providerDetail),
  moduleRoute('provider-edit', '/ui/providers/:id/edit', '编辑 Provider', Permission.providerManage, pages.providerForm),

  moduleRoute('pool-list', '/ui/credential-pools', '凭证池', Permission.credentialView, pages.poolList),
  moduleRoute('pool-new', '/ui/credential-pools/new', '新建凭证池', Permission.credentialManage, pages.poolForm),
  moduleRoute('pool-detail', '/ui/credential-pools/:id', '凭证池详情', Permission.credentialView, pages.poolDetail),
  moduleRoute('pool-edit', '/ui/credential-pools/:id/edit', '编辑凭证池', Permission.credentialManage, pages.poolForm),

  { path: '/ui/provider-models', name: 'model-list', component: () => import('@/pages/models/ModelListPage.vue'), meta: { title: '模型', permission: Permission.modelView } },
  { path: '/ui/provider-models/new', name: 'model-new', component: () => import('@/pages/models/ModelFormPage.vue'), meta: { title: '新建模型', permission: Permission.modelManage } },
  { path: '/ui/provider-models/import', name: 'model-import', component: () => import('@/pages/models/ModelImportPage.vue'), meta: { title: '模型导入', permission: Permission.modelImport } },
  { path: '/ui/provider-models/:id', name: 'model-detail', component: () => import('@/pages/models/ModelDetailPage.vue'), meta: { title: '模型详情', permission: Permission.modelView } },
  { path: '/ui/provider-models/:id/edit', name: 'model-edit', component: () => import('@/pages/models/ModelFormPage.vue'), meta: { title: '编辑模型', permission: Permission.modelManage } },

  { path: '/ui/model-aliases', name: 'alias-list', component: () => import('@/pages/aliases/AliasListPage.vue'), meta: { title: '模型别名', permission: Permission.aliasView } },
  { path: '/ui/model-aliases/new', name: 'alias-new', component: () => import('@/pages/aliases/AliasFormPage.vue'), meta: { title: '新建模型别名', permission: Permission.aliasManage } },
  { path: '/ui/model-aliases/:id', name: 'alias-detail', component: () => import('@/pages/aliases/AliasDetailPage.vue'), meta: { title: '模型别名详情', permission: Permission.aliasView } },
  { path: '/ui/model-aliases/:id/edit', name: 'alias-edit', component: () => import('@/pages/aliases/AliasFormPage.vue'), meta: { title: '编辑模型别名', permission: Permission.aliasManage } },

  moduleRoute('limit-list', '/ui/limit-policies', '限流策略', Permission.limitView),
  moduleRoute('limit-new', '/ui/limit-policies/new', '新建限流策略', Permission.limitManage),
  moduleRoute('limit-detail', '/ui/limit-policies/:id', '限流策略详情', Permission.limitView),
  moduleRoute('limit-edit', '/ui/limit-policies/:id/edit', '编辑限流策略', Permission.limitManage),

  moduleRoute(
    'reliability-list',
    '/ui/reliability-policies',
    '可靠性策略',
    Permission.reliabilityView,
  ),
  moduleRoute(
    'reliability-new',
    '/ui/reliability-policies/new',
    '新建可靠性策略',
    Permission.reliabilityManage,
  ),
  moduleRoute(
    'reliability-detail',
    '/ui/reliability-policies/:id',
    '可靠性策略详情',
    Permission.reliabilityView,
  ),
  moduleRoute(
    'reliability-edit',
    '/ui/reliability-policies/:id/edit',
    '编辑可靠性策略',
    Permission.reliabilityManage,
  ),

  moduleRoute('circuit-list', '/ui/circuits', '熔断状态', Permission.circuitView),
  moduleRoute('circuit-detail', '/ui/circuits/:id', '熔断详情', Permission.circuitView),

  moduleRoute('trace-list', '/ui/traces', 'Trace', Permission.traceView, pages.traceList),
  moduleRoute('trace-detail', '/ui/traces/:traceId', 'Trace 详情', Permission.traceView, pages.traceDetail),

  moduleRoute('usage', '/ui/usage', 'Usage 与 Cost', Permission.usageView, pages.usage),

  moduleRoute('drafts', '/ui/config/drafts', '待发布变更', Permission.draftView),
  moduleRoute('publish', '/ui/config/publish', '配置发布', Permission.publishView),
  moduleRoute(
    'publish-record',
    '/ui/config/publish/records/:id',
    '发布详情',
    Permission.publishView,
  ),

  moduleRoute('runtime-config', '/ui/runtime-config', '运行参数', Permission.runtimeConfigView),

  moduleRoute('access-list', '/ui/access-credentials', '访问凭证', Permission.accessView),
  moduleRoute('access-detail', '/ui/access-credentials/:id', '访问凭证详情', Permission.accessView),

  moduleRoute('audit-list', '/ui/audit-logs', '审计日志', Permission.auditView),
  moduleRoute('audit-detail', '/ui/audit-logs/:id', '审计详情', Permission.auditView),

  moduleRoute(
    'developer-access',
    '/ui/developer-access',
    '接入说明与测试',
    Permission.developerView,
  ),

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

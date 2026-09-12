import { Permission } from './permissions'

export interface NavItem {
  title: string
  to: string
  permission: string
}

export interface NavSection {
  title: string
  items: NavItem[]
}

/** 一级/二级导航按 PRD 2.3 模块顺序组织，显示由角色权限过滤。 */
export const navSections: NavSection[] = [
  {
    title: '运行概览',
    items: [
      { title: '运行摘要', to: '/ui/overview', permission: Permission.overviewView },
    ],
  },
  {
    title: '应用中心',
    items: [
      { title: '应用', to: '/ui/applications', permission: Permission.applicationView },
    ],
  },
  {
    title: 'AI 资源',
    items: [
      { title: '渠道', to: '/ui/providers', permission: Permission.providerView },
      { title: '上游 Key 池', to: '/ui/credential-pools', permission: Permission.credentialView },
      { title: '上游模型', to: '/ui/provider-models', permission: Permission.modelView },
      { title: '虚拟模型与路由', to: '/ui/model-aliases', permission: Permission.aliasView },
    ],
  },
  {
    title: '可靠性与配置',
    items: [
      { title: '限流策略', to: '/ui/limit-policies', permission: Permission.limitView },
      { title: '可靠性策略', to: '/ui/reliability-policies', permission: Permission.reliabilityView },
      { title: '熔断状态', to: '/ui/circuits', permission: Permission.circuitView },
    ],
  },
  {
    title: '调用观测',
    items: [
      { title: '调用记录', to: '/ui/traces', permission: Permission.traceView },
      { title: '用量与成本', to: '/ui/usage', permission: Permission.usageView },
      { title: '额度流水', to: '/ui/usage/adjustments', permission: Permission.applicationQuotaView },
    ],
  },
  {
    title: '系统管理',
    items: [
      { title: '待发布变更', to: '/ui/config/drafts', permission: Permission.draftView },
      { title: '配置发布', to: '/ui/config/publish', permission: Permission.publishView },
      { title: '运行参数', to: '/ui/runtime-config', permission: Permission.runtimeConfigView },
      { title: '旧访问凭证', to: '/ui/access-credentials', permission: Permission.accessView },
      { title: '审计日志', to: '/ui/audit-logs', permission: Permission.auditView },
    ],
  },
  {
    title: '开发接入',
    items: [
      { title: '接入说明与测试', to: '/ui/developer-access', permission: Permission.developerView },
    ],
  },
]

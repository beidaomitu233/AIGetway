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

/** P3：风险控制与审计同属治理入口，策略集中在单页。 */
export const navSections: NavSection[] = [
  {
    title: '运行概览',
    items: [
      { title: '运行摘要', to: '/ui/overview', permission: Permission.overviewView },
    ],
  },
  {
    title: '接入管理',
    items: [
      { title: '应用', to: '/ui/applications', permission: Permission.applicationView },
      { title: '渠道', to: '/ui/channels', permission: Permission.providerView },
    ],
  },
  {
    title: '安全治理',
    items: [
      { title: '风险控制', to: '/ui/risk-control', permission: Permission.riskControlView },
    ],
  },
  {
    title: '运行观测',
    items: [
      { title: '调用记录', to: '/ui/traces', permission: Permission.traceView },
      { title: '用量与成本', to: '/ui/usage', permission: Permission.usageView },
    ],
  },
  {
    title: '系统管理',
    items: [
      { title: '审计日志', to: '/ui/audit-logs', permission: Permission.auditView },
    ],
  },
]

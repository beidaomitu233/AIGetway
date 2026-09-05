/**
 * 权限标识假设：bootstrap.permissions[] 的取值尚未在后端冻结，
 * 此处按 PROJECT_DOCUMENT 2.4.2 功能权限矩阵给出前端消费口径，
 * 已在 COMMUNICATION.md 登记待后端确认（C-022）。
 */
export const Permission = {
  overviewView: 'overview.view',

  providerView: 'provider.view',
  providerManage: 'provider.manage',
  providerCheck: 'provider.check',

  credentialView: 'credential.view',
  credentialManage: 'credential.manage',
  credentialCheck: 'credential.check',

  modelView: 'model.view',
  modelManage: 'model.manage',
  modelImport: 'model.import',

  aliasView: 'alias.view',
  aliasManage: 'alias.manage',

  limitView: 'limit.view',
  limitManage: 'limit.manage',

  reliabilityView: 'reliability.view',
  reliabilityManage: 'reliability.manage',

  circuitView: 'circuit.view',
  circuitOperate: 'circuit.operate',

  traceView: 'trace.view',
  traceDiagnostics: 'trace.diagnostics',
  traceExport: 'trace.export',

  usageView: 'usage.view',
  usageExport: 'usage.export',

  draftView: 'draft.view',
  draftRevert: 'draft.revert',

  publishView: 'publish.view',
  publishManage: 'publish.manage',

  runtimeConfigView: 'runtimeconfig.view',
  runtimeConfigManage: 'runtimeconfig.manage',

  accessView: 'access.view',
  accessManage: 'access.manage',

  auditView: 'audit.view',
  auditExport: 'audit.export',

  developerView: 'developer.view',
  developerTest: 'developer.test',
} as const

export type PermissionKey = (typeof Permission)[keyof typeof Permission]

/** 角色代码与显示名：bootstrap.roles[] 的取值口径，同 C-022 登记。 */
export const RoleLabels: Record<string, string> = {
  SYSTEM_ADMIN: '系统管理员',
  OPERATOR: '运维人员',
  DEVELOPER: '开发人员',
  VIEWER: '只读人员',
}

/** 应用页面的金额只做十进制定点运算，不经过浮点数。 */
const SCALE = 100_000_000n
export function decimalUnits(value: string): bigint | null {
  if (!/^-?\d+(?:\.\d{1,8})?$/.test(value.trim())) return null
  const negative = value.trim().startsWith('-')
  const [whole = '0', fraction = ''] = value.trim().replace(/^-/, '').split('.')
  const units = BigInt(whole) * SCALE + BigInt(fraction.padEnd(8, '0'))
  return negative ? -units : units
}
export function decimalText(units: bigint): string {
  const absolute = units < 0n ? -units : units
  const fraction = (absolute % SCALE).toString().padStart(8, '0').replace(/0+$/, '').padEnd(2, '0')
  return (units < 0n ? '-' : '') + (absolute / SCALE).toString() + '.' + fraction
}
export function amountUsage(used: string, reserved: string, limit: string | null, currency: string): string {
  const a = decimalUnits(used), b = decimalUnits(reserved), cap = limit === null ? null : decimalUnits(limit)
  if (a === null || b === null || (limit !== null && cap === null)) return '金额数据异常'
  return decimalText(a + b) + ' / ' + (cap === null ? '不限' : decimalText(cap)) + ' ' + currency
}
export function remainingAmount(used: string, reserved: string, limit: string | null): string {
  if (limit === null) return '不限'
  const a = decimalUnits(used), b = decimalUnits(reserved), cap = decimalUnits(limit)
  return a === null || b === null || cap === null ? '金额数据异常' : decimalText(cap - a - b)
}
export function positiveAmount(value: string): boolean {
  const units = decimalUnits(value)
  return units !== null && units > 0n
}
export function positiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

/**
 * Token 计数为 64 位，后端以十进制字符串传输（BE-P20-102）。
 * 页面展示与比较统一走 BigInt，避免 JS Number 精度丢失与字符串拼接。
 */
export function integerUnits(value: string): bigint | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return /^-?\d+$/.test(trimmed) ? BigInt(trimmed) : null
}
export function integerText(value: bigint): string {
  return value.toLocaleString('en-US')
}
export function tokenUsageText(used: string, reserved: string, limit: string | null): string {
  const u = integerUnits(used), r = integerUnits(reserved)
  if (u === null || r === null) return '数据异常'
  const consumed = u + r
  if (limit === null) return `${integerText(consumed)} / 不限`
  const cap = integerUnits(limit)
  return cap === null ? '数据异常' : `${integerText(consumed)} / ${integerText(cap)}`
}
export function tokenRemainingText(used: string, reserved: string, limit: string | null): string {
  if (limit === null) return '不限'
  const u = integerUnits(used), r = integerUnits(reserved), cap = integerUnits(limit)
  return u === null || r === null || cap === null ? '数据异常' : integerText(cap - u - r)
}
export function positiveIntegerText(value: string): boolean {
  const units = integerUnits(value)
  return units !== null && units > 0n
}
/** 表单数值输入回填：仅接受可安全表示的非负整数，超出范围返回 null 由校验拦截。 */
export function toSafeInteger(value: string | null): number | null {
  if (value === null) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}
export function validPeriod(type: string, start: string, end: string): boolean {
  if (!['LIFECYCLE', 'DAY', 'MONTH', 'CUSTOM'].includes(type)) return false
  return type !== 'CUSTOM' || (Number.isFinite(Date.parse(start)) && Number.isFinite(Date.parse(end)) && Date.parse(start) < Date.parse(end))
}
export const applicationStatusLabels: Record<string, string> = { ACTIVE: '启用', DISABLED: '已停用', ARCHIVED: '已归档' }
export const applicationEnvironmentLabels: Record<string, string> = { DEV: '开发', TEST: '测试', STAGING: '预发布', PROD: '生产' }
export const applicationBudgetStatusLabels: Record<string, string> = {
  NORMAL: '额度正常', EXHAUSTED: '额度已耗尽', UNLIMITED: '未设额度上限',
}
export function successRateText(value: string | null): string {
  if (value === null || !/^\d+(?:\.\d+)?$/.test(value.trim())) return '—'
  const rate = Number(value)
  if (!Number.isFinite(rate) || rate < 0 || rate > 1) return '数据异常'
  return `${(rate * 100).toFixed(1)}%`
}
/** 不解析域名，不接受 URL、端口、zone id；IPv6 的地址语法交给浏览器 URL 解析器。 */
export function validIpRule(value: string): boolean {
  const parts = value.split('/')
  if (parts.length > 2) return false
  const address = parts[0] ?? ''
  const ipv6 = address.includes(':')
  if (ipv6) {
    if (!/^[0-9a-fA-F:.]+$/.test(address)) return false
    try { new URL('http://[' + address + ']/') } catch { return false }
  } else if (!/^(?:\d{1,3}\.){3}\d{1,3}$/.test(address) || address.split('.').some(part => Number(part) > 255 || (part.length > 1 && part.startsWith('0')))) return false
  return parts.length === 1 || (/^\d{1,3}$/.test(parts[1] ?? '') && Number(parts[1]) <= (ipv6 ? 128 : 32))
}

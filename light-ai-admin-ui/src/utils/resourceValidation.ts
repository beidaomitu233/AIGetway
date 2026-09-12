/** 即时语法校验；DNS、目标网络和重定向仍由服务端判定。 */
export function normalizeResourceUrl(raw: string): string | null {
  const value = raw.trim().replace(/\/+$/, '')
  try {
    const url = new URL(value)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash || url.search) return null
    return value
  } catch { return null }
}

export interface HeaderRow { key: string; value: string }
const forbidden = new Set(['authorization', 'proxy-authorization', 'x-api-key', 'api-key', 'apikey', 'x-auth-token', 'x-goog-api-key', 'cookie', 'set-cookie', 'anthropic-api-key'])
export function headerRowError(row: HeaderRow, rows: HeaderRow[]): string {
  const key = row.key.trim().toLowerCase()
  if (!key) return '请输入请求头名称'
  if (forbidden.has(key)) return '禁止认证与 Cookie 类请求头'
  if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(row.key.trim()) || /[\r\n]/.test(row.value)) return '请求头名称或值格式不正确'
  if (rows.filter((item) => item.key.trim().toLowerCase() === key).length > 1) return '键名重复（不区分大小写）'
  return ''
}
export function headersValid(rows: HeaderRow[]): boolean {
  return rows.length <= 20 && rows.every((row) => !headerRowError(row, rows))
}

export function resourceHost(raw: string): string {
  try { return new URL(raw).host || '地址格式错误' } catch { return '地址格式错误' }
}

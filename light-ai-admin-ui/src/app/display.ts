const runtimeModeLabels: Record<string, string> = {
  LOCAL_RUNTIME: '本地运行',
  EMBEDDED: '嵌入模式',
  STANDALONE_SERVER: '独立部署',
}

export const connectionStatusLabels: Record<string, string> = {
  UNKNOWN: '未检测',
  AVAILABLE: '可用',
  UNAVAILABLE: '不可用',
}

export const poolStatusLabels: Record<string, string> = {
  AVAILABLE: '可用',
  PARTIAL_AVAILABLE: '部分可用',
  UNAVAILABLE: '不可用',
  DISABLED: '已停用',
}

export const credentialHealthLabels: Record<string, string> = {
  HEALTHY: '健康',
  UNKNOWN: '未知',
  RATE_LIMITED: '限流中',
  INVALID: '无效',
  UNAVAILABLE: '不可用',
  DISABLED: '已停用',
}

export const selectionStrategyLabels: Record<string, string> = {
  LEAST_CONCURRENT: '最少并发',
  ROUND_ROBIN: '轮询',
  WEIGHTED_RANDOM: '加权随机',
}

export const secretSourceLabels: Record<string, string> = {
  INLINE_ENCRYPTED: '加密存储',
  EXTERNAL_REF: '外部引用',
}

export const checkStatusLabels: Record<string, string> = {
  SUCCEEDED: '成功',
  FAILED: '失败',
}

/** 未知枚举值按服务端原样显示，不做猜测性翻译。 */
export function displayLabel(
  labels: Record<string, string>,
  value: string | null | undefined,
): string {
  if (value === null || value === undefined || value === '') return '—'
  return labels[value] ?? value
}

export function runtimeModeLabel(value: string | null | undefined): string {
  return displayLabel(runtimeModeLabels, value)
}

/** ISO 8601 时间按系统时区展示；空值返回占位符。 */
export function formatDateTime(
  iso: string | null | undefined,
  timezone: string,
  placeholder = '—',
): string {
  if (!iso) return placeholder
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(date)
  } catch {
    return iso
  }
}

/** 时长展示：小于 1000ms 显示毫秒，其余转换为秒保留两位。 */
export function formatDuration(ms: number | null | undefined, placeholder = '—'): string {
  if (ms === null || ms === undefined) return placeholder
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}


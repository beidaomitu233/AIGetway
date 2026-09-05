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

export const healthStatusLabels: Record<string, string> = {
  HEALTHY: '健康',
  UNKNOWN: '未知',
  RATE_LIMITED: '限流中',
  INVALID: '无效',
  UNAVAILABLE: '不可用',
  DISABLED: '已停用',
}

export const secretSourceLabels: Record<string, string> = {
  INLINE_ENCRYPTED: '加密存储',
  EXTERNAL_REF: '外部引用',
}

export const selectionStrategyLabels: Record<string, string> = {
  LEAST_CONCURRENT: '最少并发',
  ROUND_ROBIN: '轮询',
  WEIGHTED_RANDOM: '按权重随机',
}

const checkModeLabels: Record<string, string> = {
  MINIMAL_CHAT: '最小对话',
  CONNECTION_ONLY: '仅连接',
}

export const checkStatusLabels: Record<string, string> = {
  SUCCEEDED: '成功',
  FAILED: '失败',
}

const batchJobStatusLabels: Record<string, string> = {
  PENDING: '等待中',
  RUNNING: '执行中',
  SUCCEEDED: '已完成',
  PARTIAL_FAILED: '部分失败',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const batchItemStatusLabels: Record<string, string> = {
  PENDING: '等待',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const runtimeAvailabilityLabels: Record<string, string> = {
  AVAILABLE: '可调用',
  CAPACITY_EXHAUSTED: '容量不足',
  CIRCUIT_OPEN: '熔断打开',
  DISABLED: '已停用',
  UNAVAILABLE: '不可用',
}

export const poolStatusLabels: Record<string, string> = {
  AVAILABLE: '可用',
  PARTIAL_AVAILABLE: '部分可用',
  UNAVAILABLE: '不可用',
  DISABLED: '已停用',
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

export function connectionStatusLabel(value: string | null | undefined): string {
  return displayLabel(connectionStatusLabels, value)
}

export function healthStatusLabel(value: string | null | undefined): string {
  return displayLabel(healthStatusLabels, value)
}

export function secretSourceLabel(value: string | null | undefined): string {
  return displayLabel(secretSourceLabels, value)
}

export function selectionStrategyLabel(value: string | null | undefined): string {
  return displayLabel(selectionStrategyLabels, value)
}

export function checkModeLabel(value: string | null | undefined): string {
  return displayLabel(checkModeLabels, value)
}

export function checkStatusLabel(value: string | null | undefined): string {
  return displayLabel(checkStatusLabels, value)
}

export function batchJobStatusLabel(value: string | null | undefined): string {
  return displayLabel(batchJobStatusLabels, value)
}

export function batchItemStatusLabel(value: string | null | undefined): string {
  return displayLabel(batchItemStatusLabels, value)
}

export function runtimeAvailabilityLabel(value: string | null | undefined): string {
  return displayLabel(runtimeAvailabilityLabels, value)
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

export const traceStatusLabels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  STREAM_INTERRUPTED: '流中断',
}

export const recoveryActionLabels: Record<string, string> = {
  RETRY: '重试',
  CREDENTIAL_FAILOVER: '凭证切换',
  FALLBACK: '候选切换',
  FAIL: '最终失败',
}

export const attemptTypeLabels: Record<string, string> = {
  INITIAL: '首次尝试',
  RETRY: '重试',
  CREDENTIAL_FAILOVER: '凭证切换',
  FALLBACK: '候选切换',
  HALF_OPEN_PROBE: '半开探测',
}

export const sourceModeLabels: Record<string, string> = {
  LOCAL_RUNTIME: '本地运行',
  EMBEDDED: '嵌入模式',
  STANDALONE_SERVER: '独立部署',
}

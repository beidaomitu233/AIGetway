const runtimeModeLabels: Record<string, string> = {
  LOCAL_RUNTIME: '本地运行',
  EMBEDDED: '嵌入模式',
  STANDALONE_SERVER: '独立部署',
}

const connectionStatusLabels: Record<string, string> = {
  UNKNOWN: '未检测',
  AVAILABLE: '可用',
  UNAVAILABLE: '不可用',
}

const healthStatusLabels: Record<string, string> = {
  HEALTHY: '健康',
  UNKNOWN: '未知',
  RATE_LIMITED: '限流中',
  INVALID: '无效',
  UNAVAILABLE: '不可用',
  DISABLED: '已停用',
}

const secretSourceLabels: Record<string, string> = {
  INLINE_ENCRYPTED: '加密存储',
  EXTERNAL_REF: '外部引用',
}

const selectionStrategyLabels: Record<string, string> = {
  LEAST_CONCURRENT: '最少并发',
  ROUND_ROBIN: '轮询',
  WEIGHTED_RANDOM: '按权重随机',
}

const checkModeLabels: Record<string, string> = {
  MINIMAL_CHAT: '最小对话',
  CONNECTION_ONLY: '仅连接',
}

const checkStatusLabels: Record<string, string> = {
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

const scopeTypeLabels: Record<string, string> = {
  MODEL_ALIAS: '模型别名',
  PROVIDER_MODEL: '模型',
  CREDENTIAL: '凭证',
}

const overflowStrategyLabels: Record<string, string> = {
  REJECT: '直接拒绝',
  QUEUE: '进入排队',
}

const counterStoreLabels: Record<string, string> = {
  OK: '正常',
  DEGRADED: '降级',
  UNAVAILABLE: '不可用',
}

const queueStatusLabels: Record<string, string> = {
  WAITING: '等待中',
  ACQUIRED: '已取得容量',
  TIMEOUT: '等待超时',
  REJECTED: '队列已满',
  CANCELLED: '已取消',
}

const recoveryActionLabels: Record<string, string> = {
  RETRY: '重试',
  CREDENTIAL_FAILOVER: '凭证切换',
  FALLBACK: '候选切换',
  FAIL: '终止',
}

const circuitStateLabels: Record<string, string> = {
  CLOSED: '闭合',
  OPEN: '打开',
  HALF_OPEN: '半开',
}

const openSourceLabels: Record<string, string> = {
  AUTO: '自动',
  MANUAL: '人工',
}

const triggerTypeLabels: Record<string, string> = {
  AUTO_THRESHOLD: '阈值触发',
  PROBE_SUCCESS: '探测成功',
  PROBE_FAILURE: '探测失败',
  MANUAL_OPEN: '人工打开',
  MANUAL_RECOVER: '人工恢复',
}

const runtimeAvailabilityLabels: Record<string, string> = {
  AVAILABLE: '可调用',
  CAPACITY_EXHAUSTED: '容量不足',
  CIRCUIT_OPEN: '熔断打开',
  DISABLED: '已停用',
  UNAVAILABLE: '不可用',
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

export function scopeTypeLabel(value: string | null | undefined): string {
  return displayLabel(scopeTypeLabels, value)
}

export function overflowStrategyLabel(value: string | null | undefined): string {
  return displayLabel(overflowStrategyLabels, value)
}

export function counterStoreStatusLabel(value: string | null | undefined): string {
  return displayLabel(counterStoreLabels, value)
}

export function queueStatusLabel(value: string | null | undefined): string {
  return displayLabel(queueStatusLabels, value)
}

export function recoveryActionLabel(value: string | null | undefined): string {
  return displayLabel(recoveryActionLabels, value)
}

export function circuitStateLabel(value: string | null | undefined): string {
  return displayLabel(circuitStateLabels, value)
}

export function openSourceLabel(value: string | null | undefined): string {
  return displayLabel(openSourceLabels, value)
}

export function triggerTypeLabel(value: string | null | undefined): string {
  return displayLabel(triggerTypeLabels, value)
}

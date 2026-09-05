const runtimeModeLabels: Record<string, string> = {
  LOCAL_RUNTIME: '本地运行',
  EMBEDDED: '嵌入模式',
  STANDALONE_SERVER: '独立部署',
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

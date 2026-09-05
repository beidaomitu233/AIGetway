<script setup lang="ts" generic="T extends { bucket_start: string; bucket_end: string }">
import { computed } from 'vue'

export interface TrendSeries {
  label: string
  color: string
  /** 与 buckets 对齐的值序列；null 表示该桶无该序列数据（不显示为 0）。 */
  values: Array<number | null>
  unit?: 'count' | 'percent' | 'ms' | 'cost'
}

const props = withDefaults(
  defineProps<{
    /** 连续时间桶（升序、无重复，由服务端保证）。 */
    buckets: T[]
    series: TrendSeries[]
    height?: number
    emptyText?: string
  }>(),
  {
    height: 220,
    emptyText: '暂无趋势数据',
  },
)

const emit = defineEmits<{ 'bucket-click': [bucket: T] }>()

const WIDTH = 720
const PADDING = { top: 16, right: 16, bottom: 28, left: 48 }

const plotWidth = WIDTH - PADDING.left - PADDING.right
const plotHeight = computed(() => props.height - PADDING.top - PADDING.bottom)

const maxValue = computed(() => {
  let max = 0
  for (const series of props.series) {
    for (const value of series.values) {
      if (value !== null && value > max) max = value
    }
  }
  // 比例指标固定 0—100
  const isPercent = props.series.some((s) => s.unit === 'percent')
  return isPercent ? 100 : max === 0 ? 1 : max * 1.15
})

const bucketWidth = computed(() => (props.buckets.length > 0 ? plotWidth / props.buckets.length : 0))

const xCenter = (index: number): number =>
  PADDING.left + bucketWidth.value * (index + 0.5)

const yFor = (value: number): number =>
  PADDING.top + plotHeight.value - (value / maxValue.value) * plotHeight.value

const gridLines = computed(() => {
  const lines: Array<{ y: number; label: string }> = []
  const steps = 4
  for (let i = 0; i <= steps; i += 1) {
    const value = (maxValue.value / steps) * i
    lines.push({ y: yFor(value), label: formatAxis(value) })
  }
  return lines
})

function formatAxis(value: number): string {
  if (value >= 1000000) return `${(value / 1000000).toFixed(1)}M`
  if (value >= 1000) return `${(value / 1000).toFixed(1)}k`
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

const seriesPaths = computed(() =>
  props.series.map((series) => {
    const segments: string[][] = []
    let current: string[] = []
    series.values.forEach((value, index) => {
      if (value === null) {
        if (current.length > 0) segments.push(current)
        current = []
        return
      }
      current.push(`${xCenter(index)},${yFor(value)}`)
    })
    if (current.length > 0) segments.push(current)
    return { ...series, segments }
  }),
)

const xTickLabels = computed(() => {
  if (props.buckets.length === 0) return []
  const maxTicks = 8
  const step = Math.max(1, Math.ceil(props.buckets.length / maxTicks))
  const labels: Array<{ x: number; text: string }> = []
  props.buckets.forEach((bucket, index) => {
    if (index % step !== 0) return
    const start = new Date(bucket.bucket_start)
    const isDay = props.buckets.length > 0 && new Date(props.buckets[0]!.bucket_end).getTime() - new Date(props.buckets[0]!.bucket_start).getTime() > 20 * 3600 * 1000
    labels.push({
      x: xCenter(index),
      text: isDay
        ? `${start.getMonth() + 1}/${start.getDate()}`
        : `${String(start.getHours()).padStart(2, '0')}:${String(start.getMinutes()).padStart(2, '0')}`,
    })
  })
  return labels
})

function bucketTitle(bucket: T, index: number): string {
  const parts = props.series.map((series) => {
    const value = series.values[index]
    return `${series.label}: ${value === null ? '—' : value}`
  })
  return [`${bucket.bucket_start} ~ ${bucket.bucket_end}`, ...parts].join('\n')
}
</script>

<template>
  <div
    v-if="buckets.length === 0"
    class="lai-empty lai-chart-empty"
  >
    <p class="lai-empty-text">
      {{ emptyText }}
    </p>
  </div>
  <svg
    v-else
    class="lai-chart"
    :viewBox="`0 0 ${WIDTH} ${height}`"
    role="img"
    aria-label="趋势图"
  >
    <g
      v-for="line in gridLines"
      :key="line.y"
    >
      <line
        :x1="PADDING.left"
        :x2="WIDTH - PADDING.right"
        :y1="line.y"
        :y2="line.y"
        stroke="#f0f1f3"
      />
      <text
        :x="PADDING.left - 6"
        :y="line.y + 4"
        text-anchor="end"
        class="lai-chart-axis"
      >
        {{ line.label }}
      </text>
    </g>
    <g
      v-for="path in seriesPaths"
      :key="path.label"
    >
      <polyline
        v-for="(segment, segmentIndex) in path.segments"
        :key="`${path.label}-${segmentIndex}`"
        :points="segment.join(' ')"
        fill="none"
        :stroke="path.color"
        stroke-width="2"
      />
    </g>
    <g
      v-for="(bucket, index) in buckets"
      :key="bucket.bucket_start"
      class="lai-chart-bucket"
      :title="bucketTitle(bucket, index)"
      @click="emit('bucket-click', bucket)"
    >
      <rect
        :x="PADDING.left + bucketWidth * index"
        :y="PADDING.top"
        :width="bucketWidth"
        :height="plotHeight"
        fill="transparent"
      />
      <template
        v-for="chartSeries in series"
        :key="`${chartSeries.label}-${index}`"
      >
        <circle
          v-show="chartSeries.values[index] !== null"
          :cx="xCenter(index)"
          :cy="yFor(chartSeries.values[index] ?? 0)"
          r="2.5"
          :fill="chartSeries.color"
        />
      </template>
    </g>
    <text
      v-for="tick in xTickLabels"
      :key="tick.x"
      :x="tick.x"
      :y="height - 8"
      text-anchor="middle"
      class="lai-chart-axis"
    >
      {{ tick.text }}
    </text>
  </svg>
</template>

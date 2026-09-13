import { theme } from 'ant-design-vue'
import type { ConfigProviderProps } from 'ant-design-vue/es/config-provider'

export const LIGHT_AI_COLORS = {
  primary: '#2563EB',
  primaryHover: '#1D4ED8',
  layoutBackground: '#F4F7FB',
  surface: '#FFFFFF',
  text: '#172033',
  textSecondary: '#667085',
  border: '#E4EAF2',
  success: '#16A34A',
  warning: '#D97706',
  error: '#DC2626',
} as const

const fontFamily =
  '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif'

/**
 * Single source of truth for the management console visual language.
 * Business pages must consume this theme instead of introducing local palettes.
 */
export const lightAiTheme = {
  algorithm: theme.defaultAlgorithm,
  token: {
    colorPrimary: LIGHT_AI_COLORS.primary,
    colorInfo: LIGHT_AI_COLORS.primary,
    colorSuccess: LIGHT_AI_COLORS.success,
    colorWarning: LIGHT_AI_COLORS.warning,
    colorError: LIGHT_AI_COLORS.error,
    colorBgLayout: LIGHT_AI_COLORS.layoutBackground,
    colorBgContainer: LIGHT_AI_COLORS.surface,
    colorText: LIGHT_AI_COLORS.text,
    colorTextSecondary: LIGHT_AI_COLORS.textSecondary,
    colorBorder: LIGHT_AI_COLORS.border,
    colorBorderSecondary: LIGHT_AI_COLORS.border,
    borderRadius: 8,
    controlHeight: 36,
    fontFamily,
    fontSize: 14,
    lineHeight: 1.55,
  },
  components: {
    Layout: {
      colorBgHeader: LIGHT_AI_COLORS.surface,
      colorBgBody: LIGHT_AI_COLORS.layoutBackground,
      colorBgTrigger: LIGHT_AI_COLORS.surface,
    },
    Menu: {
      colorItemBg: LIGHT_AI_COLORS.surface,
      colorItemBgSelected: '#EAF2FF',
      colorItemTextSelected: LIGHT_AI_COLORS.primary,
      colorItemBgHover: '#F5F8FF',
      colorItemTextHover: LIGHT_AI_COLORS.primaryHover,
      radiusItem: 8,
    },
  },
} satisfies NonNullable<ConfigProviderProps['theme']>

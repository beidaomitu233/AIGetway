import { describe, expect, it } from 'vitest'

import { LIGHT_AI_COLORS, lightAiTheme } from '@/design/theme'

describe('Ant Design foundation theme', () => {
  it('uses the approved blue and light surface palette', () => {
    expect(LIGHT_AI_COLORS.primary).toBe('#2563EB')
    expect(lightAiTheme.token?.colorPrimary).toBe(LIGHT_AI_COLORS.primary)
    expect(lightAiTheme.token?.colorBgLayout).toBe(LIGHT_AI_COLORS.layoutBackground)
    expect(lightAiTheme.token?.colorBgContainer).toBe(LIGHT_AI_COLORS.surface)
  })

  it('defines layout and navigation states for the management shell', () => {
    expect(lightAiTheme.algorithm).toBeDefined()
    expect(lightAiTheme.components?.Layout).toEqual(
      expect.objectContaining({
        colorBgHeader: LIGHT_AI_COLORS.surface,
        colorBgBody: LIGHT_AI_COLORS.layoutBackground,
      }),
    )
    expect(lightAiTheme.components?.Menu).toEqual(
      expect.objectContaining({
        colorItemBgSelected: '#EAF2FF',
        colorItemTextSelected: LIGHT_AI_COLORS.primary,
      }),
    )
  })
})

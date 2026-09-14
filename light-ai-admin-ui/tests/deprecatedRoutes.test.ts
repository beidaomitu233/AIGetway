import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '@/app/router'

describe('retired management routes', () => {
  it.each([
    ['/ui/models/virtual/alias-1/edit', '/ui/applications'],
    ['/ui/models/upstream', '/ui/applications'],
    ['/ui/limit-policies/new', '/ui/applications'],
    ['/ui/reliability-policies/policy-1', '/ui/channels'],
    ['/ui/circuits/circuit-1', '/ui/channels'],
    ['/ui/config/drafts', '/ui/applications'],
    ['/ui/runtime-config', '/ui/applications'],
    ['/ui/access-credentials', '/ui/applications'],
    ['/ui/developer-access', '/ui/applications'],
  ])('%s redirects to the supported workspace', async (legacyPath, expectedPath) => {
    const router = createRouter({ history: createMemoryHistory(), routes })
    await router.push(legacyPath)
    expect(router.currentRoute.value.path).toBe(expectedPath)
  })
})

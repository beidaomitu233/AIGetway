import { request } from './http'

export interface RiskKeywordRule { id: string; keyword: string; match_type: 'EXACT' | 'CONTAINS'; ignore_case: boolean; application_id: string | null; action: 'BLOCK' | 'RECORD'; enabled: boolean }
export interface RiskPolicy { id: string; version: number; enabled: boolean; keyword_action: 'BLOCK' | 'RECORD'; anomaly_window_seconds: number; anomaly_request_threshold: number | null; anomaly_token_threshold: number | null; anomaly_amount_threshold: string | null; anomaly_block_seconds: number; whitelist_mode: 'OFF' | 'RECORD' | 'ENFORCE'; keywords: RiskKeywordRule[]; whitelist_application_ids: string[] }
export interface RiskPolicyPayload { version: number; enabled: boolean; keyword_action: string; anomaly_window_seconds: number; anomaly_request_threshold: number | null; anomaly_token_threshold: number | null; anomaly_amount_threshold: string | null; anomaly_block_seconds: number; whitelist_mode: string; keywords: (Omit<RiskKeywordRule, 'id'> & { id?: string | null })[]; whitelist_application_ids: string[]; reason: string }
export interface RiskEvent { id: string; created_at: string; application_id: string | null; request_id: string | null; event_type: string; action: string; rule_id: string | null; reason: string | null }
export interface RiskApplication { id: string; code: string; name: string; status: string }
export function fetchRiskPolicy(signal?: AbortSignal): Promise<RiskPolicy> { return request({ path: '/risk-control/policy', signal }) }
export function replaceRiskPolicy(payload: RiskPolicyPayload): Promise<RiskPolicy> { return request({ path: '/risk-control/policy', method: 'PUT', body: payload }) }
export interface RiskEventFilters { limit?: number; application_id?: string; event_type?: string; from?: string; to?: string }
export function fetchRiskEvents(filters: RiskEventFilters = {}, signal?: AbortSignal): Promise<RiskEvent[]> { return request({ path: '/risk-control/events', query: { ...filters }, signal }) }
export function fetchRiskApplications(signal?: AbortSignal): Promise<RiskApplication[]> { return request({ path: '/risk-control/applications', signal }) }

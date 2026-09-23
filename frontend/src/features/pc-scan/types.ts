export type PartType = 'CPU' | 'GPU' | 'MOTHERBOARD' | 'RAM' | 'STORAGE' | 'PSU' | 'CASE' | 'COOLER' | 'MONITOR'
export type PartInput = {
  type: PartType
  displayName: string
  rawName: string | null
  quantity: number
  source: 'AUTO' | 'MANUAL'
  catalogProductId: string | null
  matchStatus: 'UNMATCHED' | 'MATCHED'
  specs: Record<string, string | number | boolean | null>
}
export type ScanWarning = { scope: string; code: string; message: string }
export type ScanResult = {
  schemaVersion: 1
  collectorVersion: string
  collectedAt: string
  parts: PartInput[]
  warnings: ScanWarning[]
}
export type ScanStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_WARNINGS' | 'FAILED' | 'EXPIRED'
export type ScanSession = { sessionId: string; readToken: string; launchUri: string; expiresAt: string }
export type ScanView = {
  sessionId: string
  status: ScanStatus
  expiresAt: string
  result: ScanResult | null
  failure: { code: string; message: string } | null
}

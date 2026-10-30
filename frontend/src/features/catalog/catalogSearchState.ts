import { isPageNumber, isRecord } from '../../lib/pageState.ts'

export type SearchRequest = { keyword: string; page: number }
export const isSearchRequest = (value: unknown): value is SearchRequest | null =>
  value === null || isRecord(value) && typeof value.keyword === 'string' &&
    value.keyword.length <= 100 && isPageNumber(value.page)
export const isSearchMode = (value: unknown): value is 'product' | 'model' => value === 'product' || value === 'model'
export const isExpandedId = (value: unknown): value is string | null => value === null || typeof value === 'string'

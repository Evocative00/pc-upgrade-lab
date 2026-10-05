import { csrfHeaders } from '../../lib/csrf.ts'

// 로그인 상태 API. 서버 구현과 규격: docs/week2-auth-pc-contract.md
export type AuthUser = {
  id: number
  name: string
  email: string | null
  provider: string
}

export type AuthProvider = 'google' | 'kakao'

// unavailable: 인증 API가 아직 없는 서버(404 등). 로그인 안내 대신 PC API의 응답으로 판단한다.
export type MeResult =
  | { status: 'signedIn'; user: AuthUser }
  | { status: 'signedOut' }
  | { status: 'unavailable' }

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isUser(value: unknown): value is AuthUser {
  return isObject(value) && Number.isSafeInteger(value.id) && Number(value.id) > 0 &&
    typeof value.name === 'string' &&
    (value.email === null || value.email === undefined || typeof value.email === 'string') &&
    typeof value.provider === 'string'
}

export function loginUrl(provider: AuthProvider) {
  return `/oauth2/authorization/${provider}`
}

export function createAuthClient(fetcher: typeof fetch = globalThis.fetch, baseUrl = '/api/auth') {
  async function get(path: string): Promise<Response | null> {
    try {
      return await fetcher(`${baseUrl}${path}`, { headers: { Accept: 'application/json' }, cache: 'no-store' })
    } catch {
      return null
    }
  }

  return {
    async me(): Promise<MeResult> {
      const response = await get('/me')
      if (response === null) return { status: 'unavailable' }
      if (response.status === 401) return { status: 'signedOut' }
      if (!response.ok) return { status: 'unavailable' }
      const body: unknown = await response.json().catch(() => null)
      if (!isUser(body)) return { status: 'unavailable' }
      return { status: 'signedIn', user: { ...body, email: body.email ?? null } }
    },

    // Google은 필수 제공. 목록 API가 없거나 실패하면 Google만 보여 준다.
    async providers(): Promise<AuthProvider[]> {
      const response = await get('/providers')
      const body: unknown = response?.ok ? await response.json().catch(() => null) : null
      if (!Array.isArray(body)) return ['google']
      const known = body.filter((item): item is AuthProvider => item === 'google' || item === 'kakao')
      return known.length > 0 ? known : ['google']
    },

    async logout(): Promise<void> {
      const response = await fetcher(`${baseUrl}/logout`, {
        method: 'POST',
        headers: { Accept: 'application/json', ...csrfHeaders() },
        cache: 'no-store',
      })
      // 이미 세션이 끝난 경우(401)도 로그아웃된 상태로 본다.
      if (!response.ok && response.status !== 401) {
        throw new Error(`로그아웃하지 못했습니다 (HTTP ${response.status}).`)
      }
    },
  }
}

export const authClient = createAuthClient()

// 서버가 발급한 CSRF 토큰 쿠키를 저장·수정·삭제 요청 헤더로 보낸다.
// 규격: docs/week2-auth-pc-contract.md (쿠키 XSRF-TOKEN → 헤더 X-XSRF-TOKEN)
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'

export function readCookie(name: string, cookie: string): string | null {
  for (const part of cookie.split(';')) {
    const [key, ...rest] = part.trim().split('=')
    if (key === name) {
      try {
        return decodeURIComponent(rest.join('='))
      } catch {
        return null
      }
    }
  }
  return null
}

// 쿠키가 아직 없으면(인증 미연결 환경) 헤더를 붙이지 않는다.
export function csrfHeaders(cookie = typeof document === 'undefined' ? '' : document.cookie): Record<string, string> {
  const token = readCookie(CSRF_COOKIE, cookie)
  return token ? { [CSRF_HEADER]: token } : {}
}

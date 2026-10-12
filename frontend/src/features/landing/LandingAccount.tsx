import { useState } from 'react'
import { paths } from '../../lib/router.ts'
import { logout, useAuth } from '../auth/authStore.ts'
import { saveActiveDraft } from '../auth/draftBridge.ts'
import { nameInitial, providerLabel } from './landingContent.ts'

// 랜딩 탭 바 아래의 로그인 안내 또는 로그인한 회원 정보.
// 로그인 상태를 아직 모르거나 인증 API가 없을 때도 로그인 안내를 보인다.
export function LandingAccount() {
  const auth = useAuth()
  const [error, setError] = useState<string | null>(null)
  const [loggingOut, setLoggingOut] = useState(false)

  async function handleLogout() {
    if (loggingOut) return
    setLoggingOut(true)
    setError(null)
    try {
      await logout()
    } catch (logoutError) {
      setError(logoutError instanceof Error ? logoutError.message : '로그아웃하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setLoggingOut(false)
    }
  }

  if (auth.status === 'signedIn') {
    return (
      <section className="landing-account landing-account--member" aria-label="내 계정">
        <span className="landing-account__avatar" aria-hidden="true">{nameInitial(auth.user.name)}</span>
        <span className="landing-account__who">
          <span className="landing-account__name">{auth.user.name}</span>
          <span className="landing-account__provider">{providerLabel(auth.user.provider)}</span>
        </span>
        <button type="button" className="landing-account__logout" aria-label={loggingOut ? '로그아웃 중' : '로그아웃'} onClick={handleLogout} disabled={loggingOut}>
          <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3" /><path d="M10 16l-4-4 4-4" /><path d="M6 12h10" /></svg>
        </button>
        {error !== null && <span role="alert" className="landing-account__error">{error}</span>}
      </section>
    )
  }

  return (
    <section className="landing-account" aria-label="로그인">
      <p>로그인하면 구성한 PC를<br />내 PC로 저장할 수 있어요.</p>
      <a
        href={paths.login()}
        className="landing-account__login"
        onClick={(event) => {
          if (saveActiveDraft()) {
            setError(null)
            return
          }
          event.preventDefault()
          setError('작성 중인 구성을 임시 보관하지 못했습니다. 부품 용량·수량과 브라우저 저장소 설정을 확인한 뒤 다시 시도해 주세요.')
        }}
      >
        로그인
      </a>
      <span className="landing-account__providers">Google · 카카오 · 네이버</span>
      {error !== null && <span role="alert" className="landing-account__error">{error}</span>}
    </section>
  )
}

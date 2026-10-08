import { useEffect, useState } from 'react'
import './App.css'
import { BackendStatus } from './components/BackendStatus.tsx'
import { logout, refreshAuth, useAuth } from './features/auth/authStore.ts'
import { saveActiveDraft } from './features/auth/draftBridge.ts'
import { LoginFailurePage, LoginPage, LoginSuccessPage } from './features/auth/pages/LoginPages.tsx'
import { LandingPage } from './features/landing/LandingPage.tsx'
import { PcDetailPage } from './features/pc/pages/PcDetailPage.tsx'
import { PcEditPage, PcNewPage } from './features/pc/pages/PcFormPages.tsx'
import { PcListPage } from './features/pc/pages/PcListPage.tsx'
import { navigate, paths, useRoute, type Route } from './lib/router.ts'

// 자동 인식 패널(PcScanPanel)은 PC 등록·수정 폼 안에서 onApply로 연결한다.
function Page({ route }: { route: Route }) {
  switch (route.name) {
    // 랜딩 화면은 App에서 머리말 없이 따로 그린다.
    case 'home':
      return null
    case 'list':
      return <PcListPage />
    case 'new':
      return <PcNewPage />
    case 'detail':
      return <PcDetailPage key={route.id} id={route.id} />
    case 'edit':
      return <PcEditPage key={route.id} id={route.id} />
    case 'login':
      return <LoginPage />
    case 'loginSuccess':
      return <LoginSuccessPage />
    case 'loginFailure':
      return <LoginFailurePage key={route.reason} reason={route.reason} />
    case 'notFound':
      return (
        <div className="empty">
          <p>페이지를 찾을 수 없습니다.</p>
          <a href={paths.list()}>내 PC 목록으로</a>
        </div>
      )
  }
}

// 로그인 상태. 인증 API가 아직 없는 서버(unavailable)에서는 아무것도 표시하지 않는다.
// 소셜 로그인은 첫 로그인 때 계정을 만들므로 회원가입도 같은 로그인 화면을 쓴다.
// alwaysShowLogin이면 로그인 상태를 확인하지 못했을 때(확인 중·인증 API 없음)에도 로그인 링크를 보인다.
function AuthStatus({ loginLabel = '로그인', alwaysShowLogin = false }: { loginLabel?: string; alwaysShowLogin?: boolean }) {
  const auth = useAuth()
  const [error, setError] = useState<string | null>(null)
  const [loggingOut, setLoggingOut] = useState(false)

  async function handleLogout() {
    if (loggingOut) return
    setLoggingOut(true)
    setError(null)
    try {
      await logout()
      navigate(paths.list())
    } catch (logoutError) {
      setError(logoutError instanceof Error ? logoutError.message : '로그아웃하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setLoggingOut(false)
    }
  }

  if (auth.status === 'signedIn') {
    return (
      <div className="auth-status">
        <span>{auth.user.name} 님</span>
        <button
          type="button"
          className="link-button"
          onClick={handleLogout}
          disabled={loggingOut}
        >
          {loggingOut ? '로그아웃 중…' : '로그아웃'}
        </button>
        {error !== null && <span role="alert" className="error">{error}</span>}
      </div>
    )
  }

  if (auth.status === 'signedOut' || alwaysShowLogin) {
    return (
      <div className="auth-status">
        <a
          href={paths.login()}
          onClick={(event) => {
            if (saveActiveDraft()) {
              setError(null)
              return
            }
            event.preventDefault()
            setError('작성 중인 구성을 임시 보관하지 못했습니다. 부품 용량·수량과 브라우저 저장소 설정을 확인한 뒤 다시 시도해 주세요.')
          }}
        >
          {loginLabel}
        </a>
        {error !== null && <span role="alert" className="error">{error}</span>}
      </div>
    )
  }

  return null
}

function App() {
  const route = useRoute()
  const isAuthPage = route.name === 'login' || route.name === 'loginSuccess' || route.name === 'loginFailure'

  // 처음 열 때 한 번 로그인 상태를 확인한다. 로그인 성공 화면은 직접 다시 확인한다.
  useEffect(() => {
    if (route.name !== 'loginSuccess') void refreshAuth()
    // 최초 마운트 때만 확인한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (route.name === 'home') {
    return <LandingPage auth={<AuthStatus loginLabel="로그인 / 회원가입" alwaysShowLogin />} />
  }

  return (
    <>
      <header className="app-header">
        <a href={paths.home()} className="app-title">
          PC 업그레이드 실험실
        </a>
        <nav className="app-nav">
          <a href={paths.list()}>내 PC</a>
          <a href={paths.new()}>PC 구성하기</a>
        </nav>
        <AuthStatus />
      </header>
      <main className={isAuthPage ? 'app-main app-main--auth' : 'app-main'}>
        <Page route={route} />
      </main>
      <footer className="app-footer">
        <BackendStatus />
      </footer>
    </>
  )
}

export default App

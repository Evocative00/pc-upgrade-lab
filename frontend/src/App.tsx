import { useEffect, useState } from 'react'
import './App.css'
import { BackendStatus } from './components/BackendStatus.tsx'
import { authKey, logout, refreshAuth, useAuth } from './features/auth/authStore.ts'
import { closeLogin, openLogin, useLoginDialog } from './features/auth/loginDialog.ts'
import { LoginDialog, LoginFailurePage, LoginSuccessPage } from './features/auth/pages/LoginPages.tsx'
import { BootScreen } from './features/landing/BootScreen.tsx'
import { hasSeenBoot, markBootSeen } from './features/landing/bootScript.ts'
import { LandingPage, LandingTabs } from './features/landing/LandingPage.tsx'
import { PcDetailPage } from './features/pc/pages/PcDetailPage.tsx'
import { PcEditPage, PcNewPage } from './features/pc/pages/PcFormPages.tsx'
import { PcListPage } from './features/pc/pages/PcListPage.tsx'
import { UpgradeEntryPage, UpgradeSavedPcPage } from './features/planner/UpgradeEntryPages.tsx'
import { navigate, parseRoute, paths, useRoute, type Route } from './lib/router.ts'
import { usePageScroll } from './lib/usePageScroll.ts'
import { pageStateStore } from './lib/pageState.ts'

// 자동 인식 패널(PcScanPanel)은 PC 등록·수정 폼 안에서 onApply로 연결한다.
function Page({ route }: { route: Route }) {
  const auth = useAuth()
  if (auth.status === 'loading' && route.name !== 'loginSuccess') {
    return <p role="status">로그인 상태를 확인하는 중…</p>
  }
  // 통신 장애는 로그아웃이 아니다. 회원을 재확인하기 전까지 개인 임시 입력을 표시하거나 지우지 않는다.
  if (auth.status === 'unavailable' && pageStateStore().getOwner() !== 'guest' &&
    !['loginSuccess', 'loginFailure'].includes(route.name)) {
    return <div className="empty">
      <p role="status">로그인 상태를 확인하지 못했습니다. 임시 입력은 이 탭에 보관되어 있습니다.</p>
      <button type="button" className="button" onClick={() => void refreshAuth()}>로그인 상태 다시 확인</button>
    </div>
  }
  switch (route.name) {
    // 랜딩 화면은 App에서 머리말 없이 따로 그린다.
    case 'home':
    case 'login':
      return null
    case 'list':
      return <PcListPage />
    case 'new':
      return <PcNewPage />
    case 'detail':
      return <PcDetailPage key={route.id} id={route.id} />
    case 'edit':
      return <PcEditPage key={route.id} id={route.id} />
    case 'upgrade':
      return <UpgradeEntryPage />
    case 'upgradePc':
      return <UpgradeSavedPcPage key={route.id} id={route.id} />
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
function AuthStatus() {
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

  if (auth.status === 'signedOut') {
    return (
      <div className="auth-status">
        <button type="button" className="link-button" aria-haspopup="dialog" onClick={openLogin}>
          로그인
        </button>
      </div>
    )
  }

  return null
}

function App() {
  const route = useRoute()
  const auth = useAuth()
  const loginOpen = useLoginDialog()
  const loginRoute = route.name === 'login'
  const isAuthPage = route.name === 'loginSuccess' || route.name === 'loginFailure'
  const loginDialog = (loginOpen || loginRoute) && <LoginDialog onDismiss={() => {
    closeLogin()
    if (loginRoute) window.location.replace(paths.home())
  }} />
  // 홈으로 처음 들어온 세션에서만 로딩 화면을 한 번 보여 준다.
  const [booting, setBooting] = useState(() => parseRoute(window.location.hash).name === 'home' && !hasSeenBoot())
  usePageScroll(`${authKey(auth)}:${booting}`)

  // 처음 열 때 한 번 로그인 상태를 확인한다. 로그인 성공 화면은 직접 다시 확인한다.
  useEffect(() => {
    if (route.name !== 'loginSuccess') void refreshAuth()
    // 최초 마운트 때만 확인한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (route.name === 'home' || loginRoute) {
    if (booting) {
      return (
        <BootScreen
          onDone={() => {
            markBootSeen()
            setBooting(false)
          }}
        />
      )
    }
    return <><LandingPage />{loginDialog}</>
  }

  // PC 구성 화면은 랜딩과 같은 왼쪽 탭 바를 쓰고, 메뉴·로그인이 겹치는 머리말은 숨긴다.
  // 업그레이드 플랜·내 PC 화면도 구성 화면과 같은 틀을 쓴다.
  const sideTab = route.name === 'new' ? 'new'
    : route.name === 'upgrade' || route.name === 'upgradePc' ? 'upgrade'
      : route.name === 'list' || route.name === 'detail' || route.name === 'edit' ? 'list' : null
  if (sideTab !== null) {
    return (
      <>
        <div className="app-side-nav">
          <LandingTabs current={sideTab} />
        </div>
        <main className="app-main">
          <Page key={authKey(auth)} route={route} />
        </main>
        <footer className="app-footer">
          <BackendStatus />
        </footer>
        {loginDialog}
      </>
    )
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
          <a href={paths.upgrade()}>업그레이드 플랜</a>
        </nav>
        <AuthStatus />
      </header>
      <main className={isAuthPage ? 'app-main app-main--auth' : 'app-main'}>
        <Page key={isAuthPage ? 'auth' : authKey(auth)} route={route} />
      </main>
      <footer className="app-footer">
        <BackendStatus />
      </footer>
      {loginDialog}
    </>
  )
}

export default App

import { useEffect, useState } from 'react'
import { paths, type LoginFailureReason } from '../../../lib/router.ts'
import { authClient, loginUrl, type AuthProvider } from '../authClient.ts'
import { SocialLoginButton } from '../components/SocialLoginButton.tsx'
import { refreshAuth } from '../authStore.ts'
import { loadDraft, type PcDraft } from '../draftBridge.ts'
import './login.css'

function PcMark() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true" focusable="false">
      <rect x="6" y="6" width="12" height="12" rx="2" />
      <rect x="9" y="9" width="6" height="6" rx="1" />
      <path d="M9 3v3m6-3v3M9 18v3m6-3v3M3 9h3m-3 6h3m12-6h3m-3 6h3" />
    </svg>
  )
}

function PcIllustration() {
  return (
    <div className="login-intro__illustration" aria-hidden="true">
      <svg viewBox="0 0 440 240" fill="none" focusable="false">
        <path className="login-art__grid" d="M20 200h400M20 160h400M20 120h400M60 40v160m80-160v160m80-160v160m80-160v160m80-160v160" />
        <ellipse className="login-art__ground" cx="225" cy="210" rx="185" ry="9" />
        <rect className="login-art__body" x="48" y="55" width="245" height="139" rx="12" />
        <rect className="login-art__screen" x="60" y="67" width="221" height="111" rx="5" />
        <path className="login-art__line" d="M145 194v14m51-14v14m-72 3h95" />
        <rect className="login-art__chip" x="107" y="94" width="40" height="40" rx="7" />
        <rect className="login-art__core" x="118" y="105" width="18" height="18" rx="3" />
        <path className="login-art__line" d="M118 88v6m11-6v6m-11 40v6m11-6v6m-28-35h6m-6 11h6m40-11h6m-6 11h6M175 101h76m-76 13h51m-51 13h64" />
        <rect className="login-art__bar" x="91" y="152" width="80" height="5" rx="2.5" />
        <rect className="login-art__muted-bar" x="179" y="152" width="72" height="5" rx="2.5" />
        <rect className="login-art__body" x="315" y="36" width="78" height="175" rx="12" />
        <path className="login-art__line" d="M330 57h20m12 0h14" />
        <circle className="login-art__fan" cx="354" cy="104" r="22" />
        <circle className="login-art__fan" cx="354" cy="159" r="22" />
        <circle className="login-art__core" cx="354" cy="104" r="5" />
        <circle className="login-art__core" cx="354" cy="159" r="5" />
        <path className="login-art__line" d="m339 89 30 30m-30 0 30-30m-30 55 30 30m-30 0 30-30" />
        <circle className="login-art__power" cx="376" cy="195" r="2" />
      </svg>
      <span>BUILD. SAVE. UPGRADE.</span>
    </div>
  )
}

// 로그인 후 돌아갈 작성 화면. 초안이 없으면 저장한 PC 목록으로 간다.
function draftReturnPath(draft: PcDraft | null) {
  if (draft === null) return paths.list()
  return draft.pcId === null ? paths.new() : paths.edit(draft.pcId)
}

function DraftSummary({ draft }: { draft: PcDraft | null }) {
  if (draft === null) return null

  return (
    <p className="login-draft">
      작성 중인 구성 <strong>{draft.request.name || '이름 없음'}</strong>
      (부품 {draft.request.parts.length}개)을 이 브라우저 탭에 임시 보관했습니다.
      로그인 후 다시 불러오며, 저장 버튼을 눌러야 계정에 저장됩니다.
    </p>
  )
}

// 저장할 때 로그인이 필요하면 이동하는 안내 화면. 추가 제공자는 서버 설정이 있을 때만 보인다.
export function LoginPage() {
  const [draft] = useState(() => loadDraft())
  const [providers, setProviders] = useState<AuthProvider[]>(['google'])

  useEffect(() => {
    let ignore = false
    authClient.providers().then((list) => {
      if (!ignore) setProviders(list)
    })
    return () => {
      ignore = true
    }
  }, [])

  return (
    <section className="login-layout" aria-labelledby="login-title">
      <div className="login-intro">
        <p className="login-intro__eyebrow">PC UPGRADE LAB</p>
        <h1 id="login-title">내 PC의 다음을,<br />여기서 시작하세요.</h1>
        <p className="login-intro__description">
          고민하며 고른 부품부터 다음 업그레이드까지.<br className="login-intro__break" />{' '}
          나만의 PC 구성을 한곳에 모아두세요.
        </p>
        <PcIllustration />
        <div className="login-intro__benefit">
          <span className="login-intro__benefit-icon"><PcMark /></span>
          <p><strong>저장해두고, 언제든 이어서</strong><span>내 PC 구성을 다시 확인하고 수정할 수 있어요.</span></p>
        </div>
      </div>
      <div className="login-card">
        <div className="login-card__mark"><PcMark /></div>
        <div className="login-card__heading">
          <h2>로그인</h2>
          <p>사용하던 계정으로 간편하게 시작하세요.</p>
        </div>
        <DraftSummary draft={draft} />
        <div className="login-card__providers" role="group" aria-label="소셜 계정으로 로그인">
          {providers.map((provider) => (
            <SocialLoginButton key={provider} provider={provider} href={loginUrl(provider)} />
          ))}
        </div>
        <p className="login-card__first-visit">처음 로그인하면 계정이 자동으로 만들어집니다.</p>
        <div className="login-card__guest">
          <p>부품 검색과 PC 구성은 로그인 없이도 이용할 수 있어요.</p>
          <a className="back-link" href={draftReturnPath(draft)}>
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true" focusable="false"><path d="m8 5-5 5 5 5M3 10h14" /></svg>
            {draft === null ? '내 PC 목록으로 돌아가기' : '로그인하지 않고 작성 화면으로 돌아가기'}
          </a>
        </div>
      </div>
    </section>
  )
}

// 인증 서버가 로그인 성공 후 돌려보내는 화면. 세션을 확인한 뒤 초안이 있으면 작성 화면으로 돌아간다.
export function LoginSuccessPage() {
  const [message, setMessage] = useState('로그인 상태를 확인하고 있습니다…')
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let ignore = false
    refreshAuth().then((state) => {
      if (ignore) return
      if (state.status === 'signedIn') {
        const draft = loadDraft()
        setMessage(draft === null
          ? `${state.user.name} 님, 로그인되었습니다.`
          : `${state.user.name} 님, 로그인되었습니다. 작성 중이던 구성을 불러옵니다.`)
        // 초안은 폼에 채워 보여 주기만 한다. 저장은 사용자가 저장 버튼을 눌러야 한다.
        window.location.replace(draftReturnPath(draft))
      } else {
        setFailed(true)
        setMessage('로그인 상태를 확인하지 못했습니다. 다시 시도해 주세요.')
      }
    })
    return () => {
      ignore = true
    }
  }, [])

  return (
    <section className="login-card login-card--result">
      <div className="login-card__mark"><PcMark /></div>
      <h1>로그인 확인</h1>
      <p role="status">{message}</p>
      {failed && <LoginRetryLinks draft={loadDraft()} />}
    </section>
  )
}

export function LoginFailurePage({ reason }: { reason: LoginFailureReason }) {
  const [draft] = useState(() => loadDraft())

  return (
    <section className="login-card login-card--result">
      <div className="login-card__mark"><PcMark /></div>
      <h1>{reason === 'cancelled' ? '로그인을 취소했습니다' : '로그인하지 못했습니다'}</h1>
      <p role="alert">
        {reason === 'cancelled'
          ? '계정에 저장되지 않았습니다.'
          : '로그인 중 문제가 발생해 계정에 저장되지 않았습니다. 잠시 후 다시 시도해 주세요.'}
        {draft !== null && ' 작성 중인 구성은 그대로 남아 있습니다.'}
      </p>
      <LoginRetryLinks draft={draft} />
    </section>
  )
}

function LoginRetryLinks({ draft }: { draft: PcDraft | null }) {
  return (
    <div className="actions">
      <a className="button button--ghost" href={draftReturnPath(draft)}>
        {draft === null ? '목록으로' : '작성 화면으로 돌아가기'}
      </a>
      <a className="button button--primary" href={paths.login()}>
        다시 로그인
      </a>
    </div>
  )
}

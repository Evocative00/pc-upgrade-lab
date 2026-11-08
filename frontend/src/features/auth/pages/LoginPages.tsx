import { useEffect, useId, useRef, useState, type MouseEvent } from 'react'
import { paths, type LoginFailureReason } from '../../../lib/router.ts'
import { authClient, loginUrl, type AuthProvider } from '../authClient.ts'
import { SocialLoginButton } from '../components/SocialLoginButton.tsx'
import { refreshAuth } from '../authStore.ts'
import { loadDraft, saveActiveDraft, type PcDraft } from '../draftBridge.ts'
import { openLogin } from '../loginDialog.ts'
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

// 작성 화면을 유지한 채 로그인 방법을 고른다. 소셜 로그인으로 나갈 때만 최신 초안을 보관한다.
export function LoginDialog({ onDismiss }: { onDismiss: () => void }) {
  const titleId = useId()
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [draft, setDraft] = useState(() => loadDraft())
  const [providers, setProviders] = useState<AuthProvider[]>(['google'])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const opener = document.activeElement
    dialog.showModal()
    return () => {
      if (dialog.open) dialog.close()
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus({ preventScroll: true })
    }
  }, [])

  useEffect(() => {
    let ignore = false
    authClient.providers().then((list) => {
      if (!ignore) setProviders(list)
    })
    return () => {
      ignore = true
    }
  }, [])

  function handleProviderClick(event: MouseEvent<HTMLAnchorElement>) {
    if (event.defaultPrevented) return
    if (saveActiveDraft()) {
      setDraft(loadDraft())
      setError(null)
      return
    }
    event.preventDefault()
    setError('작성 중인 구성을 임시 보관하지 못했습니다. 부품 용량·수량과 브라우저 저장소 설정을 확인한 뒤 다시 시도해 주세요.')
  }

  return (
    <dialog ref={dialogRef} className="login-card login-dialog" aria-labelledby={titleId}
      onCancel={(event) => { event.preventDefault(); onDismiss() }}
      onClick={(event) => {
        if (event.target !== event.currentTarget) return
        const bounds = event.currentTarget.getBoundingClientRect()
        if (event.clientX < bounds.left || event.clientX > bounds.right ||
          event.clientY < bounds.top || event.clientY > bounds.bottom) onDismiss()
      }}>
      <button type="button" className="login-card__close" aria-label="로그인 닫기" onClick={onDismiss}>
        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true" focusable="false">
          <path d="m5 5 10 10M15 5 5 15" />
        </svg>
      </button>
      <div className="login-card__mark"><PcMark /></div>
      <div className="login-card__heading">
        <h2 id={titleId}>로그인</h2>
        <p>사용하던 계정으로 간편하게 시작하세요.</p>
      </div>
      <DraftSummary draft={draft} />
      <div className="login-card__providers" role="group" aria-label="소셜 계정으로 로그인">
        {providers.map((provider) => (
          <SocialLoginButton key={provider} provider={provider} href={loginUrl(provider)} onClick={handleProviderClick} />
        ))}
      </div>
      {error !== null && <p className="login-card__error error" role="alert">{error}</p>}
      <p className="login-card__first-visit">처음 로그인하면 계정이 자동으로 만들어집니다.</p>
      <div className="login-card__guest">
        <p>부품 검색과 PC 구성은 로그인 없이도 이용할 수 있어요.</p>
        <button type="button" className="login-card__continue link-button" onClick={onDismiss}>로그인 없이 계속하기</button>
      </div>
    </dialog>
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
      <button type="button" className="button button--primary" onClick={openLogin}>
        다시 로그인
      </button>
    </div>
  )
}

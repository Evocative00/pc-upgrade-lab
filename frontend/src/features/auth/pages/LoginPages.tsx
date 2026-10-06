import { useEffect, useState } from 'react'
import { paths, type LoginFailureReason } from '../../../lib/router.ts'
import { authClient, loginUrl, type AuthProvider } from '../authClient.ts'
import { refreshAuth } from '../authStore.ts'
import { loadDraft, type PcDraft } from '../draftBridge.ts'

const PROVIDER_LABELS: Record<AuthProvider, string> = {
  google: 'Google로 계속하기',
  kakao: '카카오로 계속하기',
  naver: '네이버로 계속하기',
}

// 로그인 후 돌아갈 작성 화면. 초안이 없으면 저장한 PC 목록으로 간다.
function draftReturnPath(draft: PcDraft | null) {
  if (draft === null) return paths.list()
  return draft.pcId === null ? paths.new() : paths.edit(draft.pcId)
}

function DraftSummary({ draft }: { draft: PcDraft | null }) {
  if (draft === null) return null

  return (
    <p className="notice">
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
    <section className="login">
      <h1>로그인하고 내 PC로 저장하기</h1>
      <p className="muted">
        PC 구성과 부품 검색은 로그인 없이 사용할 수 있습니다. 계정에 저장하고 나중에 다시 보거나 수정하려면 로그인해 주세요.
      </p>
      <DraftSummary draft={draft} />
      <div className="login__buttons">
        {providers.map((provider) => (
          <a key={provider} className={`button login__button login__button--${provider}`} href={loginUrl(provider)}>
            {PROVIDER_LABELS[provider]}
          </a>
        ))}
      </div>
      <a className="back-link" href={draftReturnPath(draft)}>
        {draft === null ? '← 목록으로' : '← 로그인하지 않고 작성 화면으로 돌아가기'}
      </a>
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
    <section className="login">
      <h1>로그인</h1>
      <p role="status">{message}</p>
      {failed && <LoginRetryLinks draft={loadDraft()} />}
    </section>
  )
}

export function LoginFailurePage({ reason }: { reason: LoginFailureReason }) {
  const [draft] = useState(() => loadDraft())

  return (
    <section className="login">
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

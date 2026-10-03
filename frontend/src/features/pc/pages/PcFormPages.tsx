import { useEffect, useRef, useState } from 'react'
import { navigate, paths } from '../../../lib/router.ts'
import { clearDraftFor, loadDraft, saveDraft } from '../../auth/draftBridge.ts'
import { PcForm } from '../components/PcForm.tsx'
import { pcRepository } from '../pcApi.ts'
import { PcApiError } from '../pcRepository.ts'
import type { PcRequest } from '../types.ts'
import { usePcQuery } from '../usePcQuery.ts'

function useMountedPage() {
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  return mounted
}

// 로그인이 필요하면(401) 작성 내용을 임시 보관하고 로그인 안내로 이동한다. 그 외 오류는 폼에 표시한다.
async function saveOrAskLogin(request: PcRequest, pcId: number | null, save: () => Promise<void>) {
  try {
    await save()
  } catch (error) {
    if (error instanceof PcApiError && error.status === 401) {
      if (!saveDraft(request, pcId)) {
        throw new PcApiError(
          '로그인이 필요합니다. 이 브라우저에서 임시 보관을 사용할 수 없어 로그인 후 내용이 사라질 수 있습니다. 내용을 복사해 두고 로그인해 주세요.',
          401, 'UNAUTHORIZED',
        )
      }
      navigate(paths.login())
      return
    }
    throw error
  }
}

function RestoredDraftNotice({ onReset }: { onReset: () => void }) {
  return (
    <div className="notice" role="status">
      <p>임시 보관한 구성을 불러왔습니다. 내용을 확인한 뒤 저장 버튼을 눌러야 계정에 저장됩니다.</p>
      <button
        type="button"
        className="link-button"
        onClick={() => {
          if (window.confirm('작성 중인 구성을 초기화할까요? 계정에 저장된 PC는 삭제되지 않습니다.')) onReset()
        }}
      >
        작성 중인 구성 초기화
      </button>
    </div>
  )
}

export function PcNewPage() {
  const mounted = useMountedPage()
  // 로그인 왕복 후 돌아오면 새 구성 초안을 폼에 채운다. 자동으로 저장하지는 않는다.
  const [draft, setDraft] = useState(() => {
    const saved = loadDraft()
    return saved !== null && saved.pcId === null ? saved.request : null
  })
  const [formKey, setFormKey] = useState(0)

  return (
    <>
      <div className="page-head">
        <div>
          <a href={paths.list()} className="back-link">
            ← 목록
          </a>
          <h1>새 PC 등록</h1>
        </div>
      </div>
      {draft !== null && (
        <RestoredDraftNotice
          onReset={() => {
            clearDraftFor(null)
            setDraft(null)
            setFormKey((key) => key + 1)
          }}
        />
      )}
      <PcForm
        key={formKey}
        initial={draft ?? { name: '', parts: [] }}
        submitLabel="저장"
        onCancel={() => navigate(paths.list())}
        onSubmit={(request) => saveOrAskLogin(request, null, async () => {
          const pc = await pcRepository.create(request)
          clearDraftFor(null)
          // 서버 저장은 계속될 수 있지만, 이미 떠난 화면의 응답으로 현재 화면을 바꾸지 않는다.
          if (mounted.current) navigate(paths.detail(pc.id))
        })}
      />
    </>
  )
}

export function PcEditPage({ id }: { id: number }) {
  const mounted = useMountedPage()
  const query = usePcQuery(() => pcRepository.get(id))
  // 수정 중 세션이 끝나 로그인을 다녀왔다면 같은 PC의 초안을 채운다.
  const [draft, setDraft] = useState(() => {
    const saved = loadDraft()
    return saved !== null && saved.pcId === id ? saved.request : null
  })
  const [formKey, setFormKey] = useState(0)

  if (query.status === 'loading') {
    return <p className="muted">불러오는 중…</p>
  }

  if (query.status === 'error') {
    if (draft !== null) {
      return (
        <div className="empty">
          <p className="error">PC를 불러오지 못했습니다: {query.message}</p>
          <p className="muted">작성 중인 내용은 이 브라우저 탭에 보관되어 있습니다.</p>
          <a className="button button--primary" href={paths.login()}>로그인하기</a>
        </div>
      )
    }
    return <p className="error">PC를 불러오지 못했습니다: {query.message}</p>
  }

  const pc = query.data

  if (pc === null) {
    return (
      <div className="empty">
        <p>PC를 찾을 수 없습니다.</p>
        <a href={paths.list()}>목록으로</a>
      </div>
    )
  }

  return (
    <>
      <div className="page-head">
        <div>
          <a href={paths.detail(pc.id)} className="back-link">
            ← {pc.name}
          </a>
          <h1>PC 수정</h1>
        </div>
      </div>
      {draft !== null && (
        <RestoredDraftNotice
          onReset={() => {
            clearDraftFor(id)
            setDraft(null)
            setFormKey((key) => key + 1)
          }}
        />
      )}
      <PcForm
        key={formKey}
        initial={draft ?? pc}
        submitLabel="저장"
        onCancel={() => navigate(paths.detail(pc.id))}
        onSubmit={(request) => saveOrAskLogin(request, pc.id, async () => {
          await pcRepository.update(pc.id, request)
          clearDraftFor(pc.id)
          if (mounted.current) navigate(paths.detail(pc.id))
        })}
      />
    </>
  )
}

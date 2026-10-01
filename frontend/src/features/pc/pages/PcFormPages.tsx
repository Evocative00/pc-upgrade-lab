import { useEffect, useRef } from 'react'
import { navigate, paths } from '../../../lib/router.ts'
import { PcForm } from '../components/PcForm.tsx'
import { pcRepository } from '../pcRepository.ts'
import { usePcQuery } from '../usePcQuery.ts'

function useMountedPage() {
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  return mounted
}

export function PcNewPage() {
  const mounted = useMountedPage()

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
      <PcForm
        initial={{ name: '', parts: [] }}
        submitLabel="등록"
        visual
        onCancel={() => navigate(paths.list())}
        onSubmit={async (request) => {
          const pc = await pcRepository.create(request)
          // 서버 저장은 계속될 수 있지만, 이미 떠난 화면의 응답으로 현재 화면을 바꾸지 않는다.
          if (mounted.current) navigate(paths.detail(pc.id))
        }}
      />
    </>
  )
}

export function PcEditPage({ id }: { id: number }) {
  const mounted = useMountedPage()
  const query = usePcQuery(() => pcRepository.get(id))

  if (query.status === 'loading') {
    return <p className="muted">불러오는 중…</p>
  }

  if (query.status === 'error') {
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
      <PcForm
        initial={pc}
        submitLabel="저장"
        onCancel={() => navigate(paths.detail(pc.id))}
        onSubmit={async (request) => {
          await pcRepository.update(pc.id, request)
          if (mounted.current) navigate(paths.detail(pc.id))
        }}
      />
    </>
  )
}

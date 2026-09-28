import { useEffect, useState } from 'react'

export type QueryState<T> =
  | { status: 'loading' }
  | { status: 'ready'; data: T }
  | { status: 'error'; message: string }

// 화면이 열릴 때 한 번 불러온다. 대상이 바뀌면 호출하는 쪽에서 key로 다시 마운트한다.
export function usePcQuery<T>(load: () => Promise<T>): QueryState<T> {
  const [state, setState] = useState<QueryState<T>>({ status: 'loading' })

  useEffect(() => {
    let ignore = false

    load().then(
      (data) => {
        if (!ignore) setState({ status: 'ready', data })
      },
      (error: unknown) => {
        if (!ignore) {
          setState({
            status: 'error',
            message: error instanceof Error ? error.message : '알 수 없는 오류',
          })
        }
      },
    )

    return () => {
      ignore = true
    }
    // 최초 마운트 때만 불러온다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return state
}

import { useEffect, useState } from 'react'

export type QueryState<T> =
  | { status: 'loading' }
  | { status: 'ready'; data: T }
  | { status: 'error'; message: string }

// 대상이 바뀌면 key로 다시 마운트하고, 같은 화면에서 다시 읽으려면 attempt를 늘린다.
export function usePcQuery<T>(load: () => Promise<T>, attempt = 0): QueryState<T> {
  const [result, setResult] = useState<{ attempt: number; state: QueryState<T> }>()

  useEffect(() => {
    let ignore = false

    load().then(
      (data) => {
        if (!ignore) setResult({ attempt, state: { status: 'ready', data } })
      },
      (error: unknown) => {
        if (!ignore) {
          setResult({
            attempt,
            state: {
              status: 'error',
              message: error instanceof Error ? error.message : '알 수 없는 오류',
            },
          })
        }
      },
    )

    return () => {
      ignore = true
    }
    // 콜백이 바뀌어도 최초 마운트와 재시도 때만 불러온다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt])

  return result?.attempt === attempt ? result.state : { status: 'loading' }
}

import { useState } from 'react'
import './App.css'
import { PcScanPanel } from './features/pc-scan/PcScanPanel'

type HealthResponse = {
  status: string
  service: string
}

function App() {
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('아직 연결을 확인하지 않았습니다.')
  const [result, setResult] = useState<HealthResponse | null>(null)

  async function checkBackend() {
    setLoading(true)
    setMessage('백엔드에 요청하는 중입니다.')
    setResult(null)

    try {
      const response = await fetch('/api/health', {
        signal: AbortSignal.timeout(5000),
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status} 오류`)
      }

      const data: unknown = await response.json()

      if (
        typeof data !== 'object' ||
        data === null ||
        !('status' in data) ||
        !('service' in data) ||
        data.status !== 'UP' ||
        data.service !== 'pc-upgrade-lab'
      ) {
        throw new Error('예상한 백엔드 응답과 다릅니다.')
      }

      setResult({
        status: data.status,
        service: data.service,
      })
      setMessage('백엔드 연결 성공')
    } catch (error) {
      const detail =
        error instanceof Error ? error.message : '알 수 없는 오류'

      setMessage(`백엔드 연결 실패: ${detail}`)
    } finally {
      setLoading(false)
    }
  }

  return (
    <main>
      <h1>PC 업그레이드 실험실</h1>
      <p>React · TypeScript · Vite · Spring Boot 연결 확인</p>

      <button
        type="button"
        onClick={checkBackend}
        disabled={loading}
      >
        {loading ? '확인 중…' : '백엔드 연결 확인'}
      </button>

      <p role="status">{message}</p>

      {result && <pre>{JSON.stringify(result, null, 2)}</pre>}

      <p>이 화면은 백엔드 API 통신을 확인합니다. DB 연결 상태는 /actuator/health에서 확인할 수 있습니다.</p>
      <PcScanPanel />
    </main>
  )
}

export default App

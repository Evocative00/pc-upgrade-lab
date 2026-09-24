import { usePcScan } from './usePcScan'
import type { ScanResult } from './types'
import './pc-scan.css'

const labels = {
  IDLE: 'Windows PC의 CPU·GPU·RAM과 메인보드·저장장치를 읽습니다.',
  PREPARING: '검사를 준비하고 있습니다.',
  CREATED: '아래 버튼으로 보조 프로그램을 실행해 주세요.',
  RUNNING: 'PC 사양을 읽고 있습니다.',
  COMPLETED: 'PC 사양을 불러왔습니다.',
  COMPLETED_WITH_WARNINGS: 'PC 사양을 불러왔습니다. 확인하지 못한 정보는 직접 보완해 주세요.',
  FAILED: '사양을 불러오지 못했습니다.',
  EXPIRED: '2분 안에 검사가 완료되지 않았습니다. 설치와 실행 여부를 확인하고 다시 시도해 주세요.',
  CONNECTION_ERROR: '서버와 연결하지 못했습니다.',
}

// 수집기의 경고 코드를 화면 안내로 바꾼다. 새로운 코드는 아래 렌더링에서 서버의 message를 대신 표시한다.
const warningLabels: Record<string, string> = {
  NO_DEVICE: 'Windows가 장치 정보를 반환하지 않았습니다.',
  CIM_QUERY_FAILED: '장치 조회에 실패했습니다. 직접 입력해 주세요.',
  MODEL_UNKNOWN: '정확한 모델명을 확인해 주세요.',
  CAPACITY_UNKNOWN: '용량을 확인하지 못했습니다.',
  VRAM_UNAVAILABLE: '그래픽 메모리 용량은 직접 확인해 주세요.',
}

/**
 * 자동 인식 실행·상태·결과를 보여 주는 패널. 입력 폼과 연결할 때 부모 화면이 onApply를 전달한다.
 * 사용자가 '입력란에 반영'을 눌러야 콜백이 호출된다. 콜백을 생략하면 결과 확인용으로만 사용할 수 있다.
 * 부모 화면은 기존 MANUAL 부품을 보존하고 AUTO 항목을 교체하며, 편집 내용을 덮어쓸 때 사용자의 확인을 받는다.
 * 여기서는 폼 병합이나 PC 저장 API 호출을 하지 않는다. 연결 예시는 docs/week1-contract.md를 참고한다.
 */
export function PcScanPanel({ onApply }: { onApply?: (result: ScanResult) => void }) {
  const scan = usePcScan()
  const busy = ['PREPARING', 'CREATED', 'RUNNING'].includes(scan.status)
  // 일부 제원이 없어도 읽은 부품은 활용할 수 있으므로 경고가 있는 완료 상태도 반영을 허용한다.
  const canApply = scan.status === 'COMPLETED' || scan.status === 'COMPLETED_WITH_WARNINGS'
  return (
    <section className="pc-scan" aria-labelledby="pc-scan-title">
      <h2 id="pc-scan-title">내 PC 불러오기</h2>
      <p>처음에는 Windows 보조 프로그램 설치가 필요합니다. 파워·케이스·쿨러·모니터는 직접 입력해 주세요.</p>
      <p>부품 이름과 제원을 실행 중인 서버로 보냅니다. 기기 일련번호는 보내지 않습니다.</p>
      <div className="pc-scan-actions">
        {!busy && <button type="button" onClick={() => { void scan.prepare() }}>내 PC 불러오기</button>}
        {/* 브라우저에서 사용자가 직접 링크를 눌러 설치된 Windows 프로그램을 연다. */}
        {scan.launchUri && <a className="pc-scan-launch" href={scan.launchUri}>보조 프로그램 실행</a>}
        {busy && <button type="button" onClick={scan.reset}>화면에서 취소</button>}
      </div>
      <p role="status" aria-live="polite">{labels[scan.status]}</p>
      {scan.status === 'CREATED' && <p>브라우저의 프로그램 열기 확인 창에서 실행을 허용해 주세요.</p>}
      {scan.error && <p role="alert">{scan.error}</p>}
      {scan.result && <>
        <div className="pc-scan-table-wrap">
          <table>
            <caption>자동으로 읽은 부품 · {scan.result.parts.length}개 항목</caption>
            <thead><tr><th scope="col">종류</th><th scope="col">이름</th><th scope="col">수량</th></tr></thead>
            <tbody>{scan.result.parts.map((part, index) => <tr key={`${part.type}-${index}`}>
              <td>{part.type}</td><td>{part.displayName}</td><td>{part.quantity}</td>
            </tr>)}</tbody>
          </table>
        </div>
        {scan.result.warnings.length > 0 && <details>
          <summary>직접 확인할 정보 {scan.result.warnings.length}개</summary>
          <ul>{scan.result.warnings.map((warning, index) => <li key={index}>
            {warning.scope}: {warningLabels[warning.code] ?? warning.message}
          </li>)}</ul>
        </details>}
        {onApply && canApply && <button type="button" onClick={() => onApply(scan.result!)}>입력란에 반영</button>}
      </>}
    </section>
  )
}

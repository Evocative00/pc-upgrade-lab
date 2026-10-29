import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { usePcScan } from './usePcScan'
import type { ScanResult } from './types'
import './pc-scan.css'

const labels = {
  IDLE: 'Windows PC의 CPU·GPU·RAM과 메인보드·저장장치를 읽습니다.',
  PREPARING: '검사를 준비하고 있습니다.',
  CREATED: '보조 프로그램을 여는 중입니다. 브라우저의 프로그램 열기 확인 창에서 실행을 허용해 주세요.',
  RUNNING: 'PC 사양을 읽고 있습니다.',
  COMPLETED: 'PC 사양을 불러와 입력란에 반영했습니다.',
  COMPLETED_WITH_WARNINGS: 'PC 사양을 불러와 입력란에 반영했습니다. 확인하지 못한 정보는 직접 보완해 주세요.',
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

// 보조 프로그램이 서버에 응답을 보냈다면 설치되어 있는 것이다.
const collectorSeen = ['RUNNING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED']
// ponytail: 웹 페이지는 설치 여부를 직접 알 수 없어, 실행 후 이 시간 안에 응답이 없으면 미설치로 보고 설치 안내를 띄운다.
// 느린 PC에서는 설치되어 있어도 안내가 먼저 뜰 수 있으며, 응답이 오면 '설치됨' 안내로 바뀐다.
const INSTALL_GUESS_MS = 5000

function ScanDialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const titleId = useId()
  const dialogRef = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = dialogRef.current
    dialog?.showModal()
    return () => dialog?.close()
  }, [])
  return (
    <dialog ref={dialogRef} className="pc-scan-dialog" aria-labelledby={titleId}
      onCancel={(event) => { event.preventDefault(); onClose() }}>
      <h2 id={titleId}>{title}</h2>
      {children}
      <div className="pc-scan-actions">
        <button type="button" className="button button--primary" onClick={onClose}>확인</button>
      </div>
    </dialog>
  )
}

/**
 * 자동 인식 실행·상태·결과를 보여 주는 패널. '내 PC 불러오기'를 누르면 보조 프로그램을 바로 연다.
 * 결과가 오면 onApply를 자동으로 한 번 호출한다. 부모 화면은 기존 MANUAL 부품을 보존하고 AUTO 항목을 교체하며,
 * 편집 내용을 덮어쓸 때 사용자의 확인을 받는다. 여기서는 폼 병합이나 PC 저장 API 호출을 하지 않는다.
 */
export function PcScanPanel({ onApply }: { onApply?: (result: ScanResult) => void }) {
  const scan = usePcScan()
  const descriptionId = useId()
  const [expanded, setExpanded] = useState(false)
  const [installTimedOut, setInstallTimedOut] = useState(false)
  const [closedDialog, setClosedDialog] = useState<'install' | 'installed' | null>(null)
  const appliedResult = useRef<ScanResult | null>(null)
  const busy = ['PREPARING', 'CREATED', 'RUNNING'].includes(scan.status)
  // 일부 제원이 없어도 읽은 부품은 활용할 수 있으므로 경고가 있는 완료 상태도 반영한다.
  const canApply = scan.status === 'COMPLETED' || scan.status === 'COMPLETED_WITH_WARNINGS'

  useEffect(() => {
    if (scan.status !== 'CREATED') return
    const timer = setTimeout(() => setInstallTimedOut(true), INSTALL_GUESS_MS)
    return () => clearTimeout(timer)
  }, [scan.status])

  // 같은 결과는 한 번만 반영한다. 다시 불러오면 새 결과 객체가 와서 다시 반영된다.
  useEffect(() => {
    if (!onApply || !canApply || !scan.result || appliedResult.current === scan.result) return
    appliedResult.current = scan.result
    onApply(scan.result)
  }, [onApply, canApply, scan.result])

  function start() {
    setInstallTimedOut(false)
    setClosedDialog(null)
    void scan.prepare()
  }

  const dialog = collectorSeen.includes(scan.status)
    ? closedDialog === 'installed' ? null : 'installed'
    : scan.status === 'CREATED' && installTimedOut && closedDialog !== 'install' ? 'install' : null

  return (
    <section className="pc-scan" aria-labelledby="pc-scan-title">
      <div className="pc-scan-head">
        <h2 id="pc-scan-title">내 PC 불러오기</h2>
        {busy
          ? <button type="button" className="button button--ghost" onClick={scan.reset}>화면에서 취소</button>
          : <button type="button" className="button button--primary" onClick={start}>내 PC 불러오기</button>}
        <button type="button" className="pc-scan-toggle" aria-expanded={expanded} aria-controls={descriptionId}
          aria-label={expanded ? '설명 접기' : '설명 펼치기'} onClick={() => setExpanded((value) => !value)}>
          <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
            <path d="M3 6l5 5 5-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
      </div>
      <div id={descriptionId} hidden={!expanded} className="pc-scan-description">
        <p>이 PC의 Windows 사용자 계정에 보조 프로그램을 먼저 설치해 주세요. 파워·케이스·쿨러·모니터는 직접 입력해 주세요.</p>
        <p>부품 이름과 제원을 실행 중인 서버로 보냅니다. 기기 일련번호는 보내지 않습니다.</p>
        <p>읽어 온 부품은 입력란에 자동으로 반영됩니다. 직접 입력한 부품은 그대로 둡니다.</p>
      </div>
      <p role="status" aria-live="polite">{labels[scan.status]}</p>
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
      </>}

      {dialog === 'installed' && (
        <ScanDialog title="보조 프로그램이 설치되어 있습니다" onClose={() => setClosedDialog('installed')}>
          <p>{busy ? '보조 프로그램이 실행되어 PC 사양을 읽고 있습니다. 완료되면 입력란에 자동으로 반영됩니다.' : labels[scan.status]}</p>
        </ScanDialog>
      )}
      {dialog === 'install' && (
        <ScanDialog title="보조 프로그램 설치 안내" onClose={() => setClosedDialog('install')}>
          <p>보조 프로그램의 응답이 없습니다. 처음 사용하는 PC라면 아래 순서로 설치해 주세요. 노트북과 데스크톱에서는 각각 설치해야 합니다.</p>
          <ol>
            <li>프로젝트 폴더(pc-upgrade-lab)에서 PowerShell을 엽니다.</li>
            <li>아래 설치 명령을 실행합니다. 수집기 코드를 갱신했을 때도 다시 실행해 주세요.</li>
          </ol>
          <pre><code>{'powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\\collector\\windows\\Install-Collector.ps1'}</code></pre>
          <p>설치 후 이 PC의 백엔드를 실행하고 '내 PC 불러오기'를 다시 눌러 주세요. 브라우저의 프로그램 열기 확인 창에서는 실행을 허용합니다.</p>
          <p>설치했는데도 열리지 않으면 아래 명령으로 설치와 서버 연결 상태를 확인해 주세요.</p>
          <pre><code>{'powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\\collector\\windows\\Test-CollectorSetup.ps1'}</code></pre>
          {scan.launchUri && <p>이미 설치했다면 <a href={scan.launchUri}>보조 프로그램 다시 열기</a></p>}
        </ScanDialog>
      )}
    </section>
  )
}

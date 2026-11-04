import { useId, useRef } from 'react'

/**
 * VRAM 확인 방법 팝업을 여는 버튼. 그래픽카드 행의 '?'와 그래픽카드 검색 창의 안내에서 같이 쓴다.
 * variant: icon은 작은 '?' 버튼, link는 글자 버튼이다.
 */
export function VramHelpButton({ variant = 'icon' }: { variant?: 'icon' | 'link' }) {
  const titleId = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  return <>
    {variant === 'icon'
      ? <button type="button" className="vram-field__help" aria-label="VRAM 확인 방법" title="VRAM 확인 방법"
        onClick={() => dialog.current?.showModal()}>?</button>
      : <button type="button" className="link-button" onClick={() => dialog.current?.showModal()}>VRAM 확인 방법 보기</button>}
    <dialog ref={dialog} className="vram-help" aria-labelledby={titleId}
      // 그래픽카드 검색 창 안에서도 열리므로 닫기 이벤트가 바깥 창으로 번지지 않게 한다.
      onCancel={(event) => { event.stopPropagation() }}
      onClick={(event) => { event.stopPropagation(); if (event.target === event.currentTarget) event.currentTarget.close() }}>
      <h2 id={titleId}>내 그래픽카드 VRAM 확인하기</h2>
      <ol>
        <li><kbd>Ctrl</kbd> + <kbd>Shift</kbd> + <kbd>Esc</kbd>를 눌러 <strong>작업 관리자</strong>를 엽니다.</li>
        <li>왼쪽(또는 위쪽)의 <strong>성능</strong> 탭을 누릅니다.</li>
        <li><strong>GPU</strong> 항목을 고르고, 오른쪽 아래의 <strong>전용 GPU 메모리</strong> 값을 확인합니다. (예: 8.0GB → 8GB)</li>
      </ol>
      <p className="muted">GPU가 두 개 보이면 내장 그래픽(Intel UHD 등)이 아닌 그래픽카드 쪽 값을 고르세요.
        '공유 GPU 메모리'는 VRAM이 아닙니다.</p>
      <details>
        <summary>다른 방법: DirectX 진단 도구</summary>
        <p><kbd>Win</kbd> + <kbd>R</kbd> → <code>dxdiag</code> 입력 → <strong>디스플레이</strong> 탭의 <strong>디스플레이 메모리(VRAM)</strong> 값을 확인합니다.</p>
      </details>
      {/* 부품 행이 저장 폼 안에 있어 중첩 form 대신 버튼으로 닫는다. */}
      <button type="button" className="button button--primary" onClick={() => dialog.current?.close()}>확인</button>
    </dialog>
  </>
}

/** 그래픽카드를 고르기 전에 VRAM을 먼저 확인하라는 안내. 같은 모델도 VRAM이 다른 제품이 있어 고른 뒤 다시 확인하는 일을 줄인다. */
export function VramCheckNotice() {
  // 팝업(dialog)이 안에 들어가므로 p가 아닌 div로 둔다.
  return <div className="vram-notice" role="note">
    <strong>먼저 내 VRAM을 확인하세요.</strong> 같은 모델도 VRAM이 다른 제품이 있어요(예: RTX 3060 8GB·12GB).
    확인한 뒤 제품 이름의 VRAM(예: 12G)이 같은 것을 고르면 다시 고칠 필요가 없습니다. <VramHelpButton variant="link" />
  </div>
}

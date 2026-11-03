import { useEffect, useId, useRef } from 'react'
import { CatalogPicker } from '../catalog/CatalogPicker.tsx'
import type { CatalogModel, CatalogProduct } from '../catalog/catalogTypes.ts'
import type { PartType } from '../pc-scan/types.ts'
import { VramCheckNotice } from '../pc/components/VramHelp.tsx'

type Props = {
  stateKey: string
  label: string
  type: PartType
  initialQuery: string
  onClose: () => void
  onSelect: (product: CatalogProduct) => void
  onSelectModel: (model: CatalogModel) => void
}

// 네이티브 모달은 배경 조작과 Tab 이탈을 막고, 닫을 때 열었던 자리로 돌아간다.
export function CatalogPickerDialog({ stateKey, label, type, initialQuery, onClose, onSelect, onSelectModel }: Props) {
  const titleId = useId()
  const dialogRef = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const opener = document.activeElement
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    dialog.showModal()

    return () => {
      dialog.close()
      document.body.style.overflow = previousOverflow
      if ((opener instanceof HTMLElement || opener instanceof SVGElement) && opener.isConnected) {
        opener.focus({ preventScroll: true })
      } else {
        // 빈 자리의 추가 버튼은 제품 선택 후 사라질 수 있다.
        document.querySelector<HTMLInputElement>('.pc-form input')?.focus({ preventScroll: true })
      }
    }
  }, [])

  return (
    <dialog ref={dialogRef} className="builder__picker" aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      onClick={(event) => {
        if (event.target !== event.currentTarget) return
        const bounds = event.currentTarget.getBoundingClientRect()
        if (event.clientX < bounds.left || event.clientX > bounds.right ||
          event.clientY < bounds.top || event.clientY > bounds.bottom) onClose()
      }}>
      <h2 id={titleId}>{label} 고르기</h2>
      {/* 같은 모델도 VRAM이 다른 그래픽카드가 있어, 검색 전에 내 VRAM부터 확인하게 한다. */}
      {type === 'GPU' && <VramCheckNotice />}
      <CatalogPicker stateKey={stateKey} type={type} initialQuery={initialQuery} onClose={onClose} onSelect={onSelect} onSelectModel={onSelectModel} />
    </dialog>
  )
}

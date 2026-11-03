import { useId } from 'react'
import { COMMON_VRAM_GIB, resolveVram, withVramInput } from '../gpuVram.ts'
import type { PartDraft } from '../types.ts'
import { VramHelpButton } from './VramHelp.tsx'

/**
 * 그래픽카드 행의 VRAM 칸. 모델에 VRAM이 하나뿐이거나 이름에 적혀 있으면 자동으로 보여 주고,
 * 여러 개(예: RTX 3060 8/12GB)거나 모르는 모델이면 사용자가 고른다. '?'를 누르면 확인 방법을 띄운다.
 */
export function VramField({ draft, onChange }: { draft: PartDraft; onChange: (draft: PartDraft) => void }) {
  const selectId = useId()
  const vram = resolveVram(draft)
  const auto = vram.source === 'NAME' || vram.source === 'MODEL'
  // 고를 값: 모델 선택지가 있으면 그것만, 모르는 모델이면 일반적인 용량 전체
  const choices = vram.options && vram.options.length > 1 ? vram.options : COMMON_VRAM_GIB
  const needsChoice = vram.gib === null && vram.options !== null && vram.options.length > 1

  return <span className={`part-row__unit vram-field${needsChoice ? ' vram-field--needed' : ''}`}>
    <label htmlFor={selectId} className="muted">VRAM</label>
    {auto
      ? <span className="vram-field__auto" id={selectId}><strong>{vram.gib}GB</strong> <small>자동</small></span>
      : <select id={selectId} value={vram.gib ?? ''} aria-invalid={needsChoice || undefined}
        onChange={(event) => onChange({ ...withVramInput(draft, event.target.value === '' ? null : Number(event.target.value)), edited: true })}>
        <option value="">{needsChoice ? '선택' : '모름'}</option>
        {choices.map((gib) => <option key={gib} value={gib}>{gib}GB</option>)}
      </select>}
    <VramHelpButton />
  </span>
}

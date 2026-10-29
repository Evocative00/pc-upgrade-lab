import type { KeyboardEvent, ReactNode } from 'react'
import { chipLabel, slotInfo, type RamModuleView, type SlotId } from './buildSlots.ts'

type Box = { x: number; y: number; w: number; h: number }
// fadeKey가 바뀐 자리만 다시 그려지고 페이드가 걸린다.
export type SlotView = { name: string; fadeKey: string }
type Props = {
  slots: Partial<Record<SlotId, SlotView>>
  ramModules: RamModuleView[]
  active: SlotId | null
  onPick: (slot: SlotId) => void
}

// 어두운 기판 위에 빈 자리는 얇은 테두리, 채운 부품은 강조색 #1E3A8A 계열로 빛나게 한다.
const COLOR = {
  base: '#0d0f14',
  board: '#11141b',
  edge: '#232836',
  trace: '#1f2431',
  neon: '#3b5bd0',
  heatsink: '#181b23',
  fin: '#232733',
  filled: '#2643a6',
  fan: '#3d5cc4',
  glow: '#5b7be0',
  text: '#f3f6ff',
  boardText: '#7c8496',
}

// sub는 자리 위에 작게 쓰는 규격 이름(예: PCIE X16)이다.
function EmptyBox({ x, y, w, h, text, vertical = false, sub }: Box & { text: string; vertical?: boolean; sub?: string }) {
  const cx = x + w / 2
  const cy = y + h / 2 + (sub ? 8 : 0)
  return <>
    <rect x={x} y={y} width={w} height={h} rx={6} className="case-slot__empty" />
    {sub && <text x={cx} y={cy - 20} className="case-slot__sub">{sub}</text>}
    <text x={cx} y={cy} className="case-slot__hint" transform={vertical ? `rotate(-90 ${cx} ${cy})` : undefined}>
      + {text}
    </text>
  </>
}

function FilledBox({ x, y, w, h, rx = 6 }: Box & { rx?: number }) {
  return <rect x={x} y={y} width={w} height={h} rx={rx} fill={COLOR.filled} filter="url(#case-glow)" />
}

function Lines({ x, y, lines, size = 13, max = 22 }: { x: number; y: number; lines: string[]; size?: number; max?: number }) {
  const shown = lines.filter(Boolean)
  return (
    <text x={x} y={y - ((shown.length - 1) * size) / 2} fill={COLOR.text} textAnchor="middle" dominantBaseline="middle">
      {shown.map((line, index) => (
        <tspan key={index} x={x} dy={index === 0 ? 0 : size + 2} fontSize={index === 0 ? size : size - 2}
          fontWeight={index === 0 ? 700 : 500}>
          {line.length > max ? `${line.slice(0, max - 1)}…` : line}
        </tspan>
      ))}
    </text>
  )
}

// 자리 하나. 키보드(Enter·Space)로도 고를 수 있다.
function Slot({ id, part, active, onPick, empty, filled }: {
  id: SlotId
  part: SlotView | undefined
  active: boolean
  onPick: (slot: SlotId) => void
  empty: ReactNode
  filled: (part: SlotView) => ReactNode
}) {
  const label = slotInfo(id).label
  function handleKey(event: KeyboardEvent<SVGGElement>) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onPick(id)
    }
  }
  return (
    <g role="button" tabIndex={0} className={`case-slot${active ? ' case-slot--active' : ''}`}
      aria-label={part ? `${label}: ${part.name} (변경)` : `${label} 추가`}
      aria-pressed={active} onClick={() => onPick(id)} onKeyDown={handleKey}>
      <g key={part?.fadeKey ?? 'empty'} className={part ? 'case-slot__fade' : undefined}>
        {part ? filled(part) : empty}
      </g>
    </g>
  )
}

const GPU_FANS = [140, 240, 340]
// 쉬라우드 칸: 파워(작게) | 저장장치 1 | 저장장치 2. 그림 비율은 원래 그림(360×518)과 같다.
const PSU_BOX = { x: 84, y: 468, w: 96, h: 88 }
const STORAGE_BOXES = { SSD: { x: 192, y: 468, w: 96, h: 88 }, HDD: { x: 300, y: 468, w: 96, h: 88 } } as const

export function CaseView({ slots, ramModules, active, onPick }: Props) {
  const slot = (id: SlotId) => ({ id, part: slots[id], active: active === id, onPick })

  return (
    <svg viewBox="60 64 360 518" className="case-view" role="group" aria-label="PC 구성 그림. 자리를 누르면 해당 부품을 고릅니다.">
      <defs>
        {/* 네온: 가까운 밝은 빛 + 넓게 퍼지는 빛 두 겹 */}
        <filter id="case-glow" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur in="SourceAlpha" stdDeviation="3" result="near" />
          <feGaussianBlur in="SourceAlpha" stdDeviation="12" result="far" />
          <feFlood floodColor={COLOR.glow} floodOpacity="0.9" />
          <feComposite in2="near" operator="in" result="nearGlow" />
          <feFlood floodColor={COLOR.neon} floodOpacity="0.6" />
          <feComposite in2="far" operator="in" result="farGlow" />
          <feMerge><feMergeNode in="farGlow" /><feMergeNode in="nearGlow" /><feMergeNode in="SourceGraphic" /></feMerge>
        </filter>
      </defs>

      {/* 기판·쉬라우드 사이 모서리 틈을 메우는 바탕. 케이스 외곽 여백은 그리지 않는다. */}
      <rect x={68} y={72} width={344} height={502} rx={12} fill={COLOR.base} pointerEvents="none" />

      {/* 메인보드 기판. 기판을 누르면 메인보드를 고른다. */}
      <Slot {...slot('MOTHERBOARD')}
        empty={<>
          <rect x={68} y={72} width={344} height={378} rx={12} fill={COLOR.board} stroke={COLOR.edge} />
          <text x={240} y={432} className="case-slot__sub">MAINBOARD · 미선택</text>
        </>}
        filled={(part) => <>
          <rect x={68} y={72} width={344} height={378} rx={12} fill={COLOR.board} stroke={COLOR.glow} strokeWidth={3}
            filter="url(#case-glow)" />
          <text x={240} y={432} textAnchor="middle" dominantBaseline="middle" fill={COLOR.boardText} fontSize={12} fontWeight={700}>
            {part.name.length > 30 ? `${part.name.slice(0, 29)}…` : part.name}
          </text>
        </>} />

      {/* 회로선·방열판 장식 (누를 수 없음) */}
      <g pointerEvents="none" stroke={COLOR.trace} strokeWidth={2} fill="none" opacity={0.85}>
        <path d="M92 140 H170 M92 160 H170 M300 270 V290 H400 M196 250 V286 M284 250 V286 M90 410 H260" />
        <path d="M380 80 V110 H400 M100 290 H180" />
      </g>
      <g pointerEvents="none" fill={COLOR.heatsink}>
        <rect x={88} y={84} width={50} height={40} rx={4} />
        {[0, 1, 2, 3, 4].map((i) => <rect key={i} x={92 + i * 9} y={88} width={5} height={32} rx={1} fill={COLOR.fin} />)}
        <rect x={88} y={182} width={40} height={56} rx={4} />
        {/* 저장장치 자리가 쉬라우드로 옮겨 가 비는 곳은 칩셋 방열판으로 채운다. */}
        <rect x={96} y={250} width={92} height={28} rx={4} />
      </g>

      {/* 쉬라우드(파워·저장장치 덮개) 구역 */}
      <rect x={68} y={450} width={344} height={124} rx={12} fill={COLOR.base} pointerEvents="none" />
      <line x1={68} y1={452} x2={412} y2={452} stroke={COLOR.edge} strokeWidth={1.5} pointerEvents="none" />

      <Slot {...slot('COOLER')}
        empty={<EmptyBox x={164} y={86} w={148} h={36} text="쿨러" />}
        filled={(part) => <>
          <FilledBox x={164} y={86} w={148} h={36} />
          <Lines x={238} y={104} lines={[part.name]} size={12} max={20} />
        </>} />

      {/* CPU 소켓 테두리는 항상 보이고, 칩 자리만 바뀐다. */}
      <rect x={188} y={134} width={104} height={104} rx={8} fill="none" stroke={COLOR.edge} strokeWidth={1.5} pointerEvents="none" />
      {/* 빈 자리는 어둡게 두고, 부품을 채우면 파란 네온이 들어온다. */}
      <Slot {...slot('CPU')}
        empty={<EmptyBox x={194} y={140} w={92} h={92} text="CPU" sub="SOCKET" />}
        filled={(part) => <>
          <FilledBox x={200} y={146} w={80} h={80} rx={4} />
          <Lines x={240} y={186} lines={chipLabel(part.name)} size={13} max={11} />
        </>} />

      <Slot {...slot('RAM')}
        empty={<>
          <EmptyBox x={326} y={116} w={28} h={140} text="메모리" vertical />
          <rect x={362} y={116} width={28} height={140} rx={6} className="case-slot__empty" />
        </>}
        filled={() => <>
          {[326, 362].map((x, index) => ramModules[index] ? (
            <g key={ramModules[index].key} aria-label={`${ramModules[index].name}: ${ramModules[index].label.join(' ')}`}>
              <FilledBox x={x} y={116} w={28} h={140} />
              <text x={x + 14} y={186} fill={COLOR.text} textAnchor="middle" dominantBaseline="middle"
                transform={`rotate(-90 ${x + 14} 186)`} fontSize={12}>
                <tspan fontWeight={700}>{ramModules[index].label[0]}</tspan> {ramModules[index].label[1]}
              </text>
            </g>
          ) : <rect key={x} x={x} y={116} width={28} height={140} rx={6} className="case-slot__empty" />)}
        </>} />

      <Slot {...slot('GPU')}
        empty={<EmptyBox x={88} y={298} w={304} h={96} text="그래픽카드" sub="PCIE X16" />}
        filled={(part) => <>
          <FilledBox x={88} y={298} w={304} h={96} rx={8} />
          {GPU_FANS.map((cx) => <g key={cx}>
            <circle cx={cx} cy={336} r={27} fill={COLOR.fan} stroke={COLOR.text} strokeWidth={2} />
            <circle cx={cx} cy={336} r={6} fill={COLOR.text} />
          </g>)}
          <Lines x={240} y={381} lines={[part.name]} size={12} />
        </>} />

      <Slot {...slot('PSU')}
        empty={<EmptyBox {...PSU_BOX} text="파워" />}
        filled={(part) => <>
          <FilledBox {...PSU_BOX} />
          <circle cx={132} cy={494} r={18} fill={COLOR.fan} stroke={COLOR.text} strokeWidth={2} />
          <path d="M132 478 V510 M116 494 H148 M121 483 L143 505 M143 483 L121 505" stroke={COLOR.text} strokeWidth={1.2} />
          <Lines x={132} y={534} lines={[part.name]} size={10} max={14} />
        </>} />

      {(['SSD', 'HDD'] as const).map((id) => {
        const box = STORAGE_BOXES[id]
        return <Slot key={id} {...slot(id)}
          empty={<EmptyBox {...box} text={slotInfo(id).label} />}
          filled={(part) => <>
            <FilledBox {...box} />
            <Lines x={box.x + box.w / 2} y={box.y + box.h / 2} lines={chipLabel(part.name)} size={11} max={13} />
          </>} />
      })}
    </svg>
  )
}

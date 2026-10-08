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

const FILLED_TEXT = '#091b37'

function EmptyBox({ x, y, w, h, text, vertical = false }: Box & { text: string; vertical?: boolean }) {
  const cx = x + w / 2
  const cy = y + h / 2
  return <>
    <rect x={x} y={y} width={w} height={h} rx={6} className="case-slot__empty" />
    <text x={cx} y={cy} className="case-slot__hint" transform={vertical ? `rotate(-90 ${cx} ${cy})` : undefined}>
      + {text}
    </text>
  </>
}

function FilledBox({ x, y, w, h, rx = 6 }: Box & { rx?: number }) {
  return <rect x={x} y={y} width={w} height={h} rx={rx} fill="#3b82f6" filter="url(#case-glow)" />
}

function Lines({ x, y, lines, size = 13 }: { x: number; y: number; lines: string[]; size?: number }) {
  const shown = lines.filter(Boolean)
  return (
    <text x={x} y={y - ((shown.length - 1) * size) / 2} fill={FILLED_TEXT} textAnchor="middle" dominantBaseline="middle">
      {shown.map((line, index) => (
        <tspan key={index} x={x} dy={index === 0 ? 0 : size + 2} fontSize={index === 0 ? size : size - 3}
          fontWeight={index === 0 ? 700 : 400}>
          {line.length > 22 ? `${line.slice(0, 21)}…` : line}
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

const FANS = [140, 240, 340]

export function CaseView({ slots, ramModules, active, onPick }: Props) {
  const slot = (id: SlotId) => ({ id, part: slots[id], active: active === id, onPick })

  return (
    <svg viewBox="60 64 360 518" className="case-view" role="group" aria-label="PC 구성 그림. 자리를 누르면 해당 부품을 고릅니다.">
      <defs>
        <filter id="case-glow" x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur in="SourceGraphic" stdDeviation="5" result="blur" />
          <feFlood floodColor="#60a5fa" floodOpacity="0.7" />
          <feComposite in2="blur" operator="in" result="glow" />
          <feMerge><feMergeNode in="glow" /><feMergeNode in="SourceGraphic" /></feMerge>
        </filter>
      </defs>

      {/* 기판·쉬라우드 사이 모서리 틈을 메우는 바탕. 케이스 외곽 여백은 그리지 않는다. */}
      <rect x={68} y={72} width={344} height={502} rx={12} fill="#16325c" pointerEvents="none" />

      {/* 메인보드 기판. 기판을 누르면 메인보드를 고른다. */}
      <Slot {...slot('MOTHERBOARD')}
        empty={<>
          <rect x={68} y={72} width={344} height={378} rx={12} fill="#1d3f73" />
          <text x={240} y={426} className="case-slot__hint">+ 메인보드</text>
        </>}
        filled={(part) => <>
          <rect x={68} y={72} width={344} height={378} rx={12} fill="#1d3f73" stroke="#3b82f6" strokeWidth={3}
            filter="url(#case-glow)" />
          <text x={240} y={426} textAnchor="middle" dominantBaseline="middle" fill="#bfdbfe" fontSize={12} fontWeight={700}>
            {part.name.length > 30 ? `${part.name.slice(0, 29)}…` : part.name}
          </text>
        </>} />

      {/* 회로선·방열판 장식 (누를 수 없음) */}
      <g pointerEvents="none" stroke="#3566a8" strokeWidth={2} fill="none" opacity={0.8}>
        <path d="M92 140 H170 M92 160 H170 M300 270 V290 H400 M196 250 V286 M284 250 V286 M90 410 H260" />
        <path d="M380 80 V110 H400 M100 290 H180" />
      </g>
      <g pointerEvents="none" fill="#2a5591">
        <rect x={88} y={84} width={50} height={40} rx={4} />
        {[0, 1, 2, 3, 4].map((i) => <rect key={i} x={92 + i * 9} y={88} width={5} height={32} rx={1} fill="#4677bd" />)}
        <rect x={88} y={182} width={40} height={56} rx={4} />
      </g>

      {/* 쉬라우드(파워 덮개) 구역 */}
      <rect x={68} y={450} width={344} height={124} rx={12} fill="#16325c" pointerEvents="none" />
      <line x1={68} y1={452} x2={412} y2={452} stroke="#93b8e8" strokeWidth={2} pointerEvents="none" />

      <Slot {...slot('COOLER')}
        empty={<EmptyBox x={150} y={86} w={180} h={36} text="쿨러" />}
        filled={(part) => <>
          <FilledBox x={150} y={86} w={180} h={36} />
          <Lines x={240} y={104} lines={[part.name]} size={12} />
        </>} />

      {/* CPU 소켓 테두리는 항상 보이고, 칩 자리만 바뀐다. */}
      <rect x={188} y={134} width={104} height={104} rx={6} fill="none" stroke="#fff" strokeWidth={3} pointerEvents="none" />
      <Slot {...slot('CPU')}
        empty={<EmptyBox x={200} y={146} w={80} h={80} text="CPU" />}
        filled={(part) => <>
          <FilledBox x={200} y={146} w={80} h={80} rx={4} />
          <Lines x={240} y={186} lines={chipLabel(part.name)} size={13} />
        </>} />

      <Slot {...slot('RAM')}
        empty={<>
          <EmptyBox x={316} y={116} w={28} h={140} text="메모리" vertical />
          <rect x={352} y={116} width={28} height={140} rx={6} className="case-slot__empty" />
        </>}
        filled={() => <>
          {[316, 352].map((x, index) => ramModules[index] ? (
            <g key={ramModules[index].key} aria-label={`${ramModules[index].name}: ${ramModules[index].label.join(' ')}`}>
              <FilledBox x={x} y={116} w={28} h={140} />
              <text x={x + 14} y={186} fill={FILLED_TEXT} textAnchor="middle" dominantBaseline="middle"
                transform={`rotate(-90 ${x + 14} 186)`} fontSize={12}>
                <tspan fontWeight={700}>{ramModules[index].label[0]}</tspan> {ramModules[index].label[1]}
              </text>
            </g>
          ) : <rect key={x} x={x} y={116} width={28} height={140} rx={6} className="case-slot__empty" />)}
        </>} />

      <Slot {...slot('SSD')}
        empty={<EmptyBox x={96} y={250} w={92} h={28} text="저장장치 1" />}
        filled={(part) => <>
          <FilledBox x={96} y={250} w={92} h={28} rx={4} />
          <Lines x={142} y={264} lines={[part.name]} size={10} />
        </>} />

      <Slot {...slot('GPU')}
        empty={<EmptyBox x={88} y={298} w={304} h={96} text="그래픽카드" />}
        filled={(part) => <>
          <FilledBox x={88} y={298} w={304} h={96} rx={8} />
          {FANS.map((cx) => <g key={cx}>
            <circle cx={cx} cy={336} r={27} fill="#2f6fd6" stroke={FILLED_TEXT} strokeWidth={2} />
            <circle cx={cx} cy={336} r={6} fill={FILLED_TEXT} />
          </g>)}
          <Lines x={240} y={381} lines={[part.name]} size={12} />
        </>} />

      <Slot {...slot('PSU')}
        empty={<EmptyBox x={84} y={468} w={176} h={88} text="파워" />}
        filled={(part) => <>
          <FilledBox x={84} y={468} w={176} h={88} />
          <circle cx={128} cy={512} r={32} fill="#2f6fd6" stroke={FILLED_TEXT} strokeWidth={2} />
          <path d="M128 482 V542 M98 512 H158 M107 491 L149 533 M149 491 L107 533" stroke={FILLED_TEXT} strokeWidth={1.5} />
          <Lines x={208} y={512} lines={chipLabel(part.name)} size={11} />
        </>} />

      <Slot {...slot('HDD')}
        empty={<EmptyBox x={276} y={468} w={120} h={88} text="저장장치 2" />}
        filled={(part) => <>
          <FilledBox x={276} y={468} w={120} h={88} />
          <Lines x={336} y={512} lines={chipLabel(part.name)} size={11} />
        </>} />
    </svg>
  )
}

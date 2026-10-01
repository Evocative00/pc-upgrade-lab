import type { KeyboardEvent, ReactNode } from 'react'
import { chipLabel, ramLabel, ramSlotCount, SLOTS, type Build, type BuildPart, type SlotId } from './buildSlots.ts'

type Box = { x: number; y: number; w: number; h: number }
type Props = { build: Build; active: SlotId | null; onPick: (slot: SlotId) => void }

const FILLED_TEXT = '#10300a'
const slotLabel = (id: SlotId) => SLOTS.find((slot) => slot.id === id)?.label ?? id

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
  return <rect x={x} y={y} width={w} height={h} rx={rx} fill="#62b23c" filter="url(#case-glow)" />
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

// 자리 하나. key가 제품 ID라서 바뀐 자리만 다시 그려지고 페이드가 걸린다.
function Slot({ id, part, active, onPick, empty, filled }: {
  id: SlotId
  part: BuildPart | undefined
  active: boolean
  onPick: (slot: SlotId) => void
  empty: ReactNode
  filled: (part: BuildPart) => ReactNode
}) {
  const label = slotLabel(id)
  function handleKey(event: KeyboardEvent<SVGGElement>) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onPick(id)
    }
  }
  return (
    <g role="button" tabIndex={0} className={`case-slot${active ? ' case-slot--active' : ''}`}
      aria-label={part ? `${label}: ${part.product.modelName} (변경)` : `${label} 추가`}
      aria-pressed={active} onClick={() => onPick(id)} onKeyDown={handleKey}>
      <g key={part?.product.id ?? 'empty'} className={part ? 'case-slot__fade' : undefined}>
        {part ? filled(part) : empty}
      </g>
    </g>
  )
}

const FANS = [140, 240, 340]

export function CaseView({ build, active, onPick }: Props) {
  const slot = (id: SlotId) => ({ id, part: build[id], active: active === id, onPick })
  const ram = build.RAM
  const ramSlots = ram ? ramSlotCount(ram.specs) : 0
  const [ramSize, ramType] = ramLabel(ram?.specs ?? null)

  return (
    <svg viewBox="0 0 480 640" className="case-view" role="group" aria-label="PC 구성 그림. 자리를 누르면 해당 부품을 고릅니다.">
      <defs>
        <filter id="case-glow" x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur in="SourceGraphic" stdDeviation="5" result="blur" />
          <feFlood floodColor="#7ee04f" floodOpacity="0.7" />
          <feComposite in2="blur" operator="in" result="glow" />
          <feMerge><feMergeNode in="glow" /><feMergeNode in="SourceGraphic" /></feMerge>
        </filter>
      </defs>

      {/* 케이스 외형: 상단 전원·USB 패널, 하단 받침 */}
      <rect x={80} y={590} width={70} height={16} rx={4} fill="#c9ccc5" />
      <rect x={330} y={590} width={70} height={16} rx={4} fill="#c9ccc5" />
      <rect x={40} y={28} width={400} height={566} rx={28} fill="#f4f5f3" stroke="#d3d6cf" strokeWidth={2} />
      <rect x={186} y={40} width={108} height={18} rx={9} fill="#e1e4dd" />
      <circle cx={202} cy={49} r={5} fill="#9aa094" />
      <rect x={220} y={45} width={18} height={8} rx={2} fill="#9aa094" />
      <rect x={244} y={45} width={18} height={8} rx={2} fill="#9aa094" />
      <rect x={268} y={45} width={14} height={8} rx={2} fill="#9aa094" />

      {/* 메인보드 기판. 기판을 누르면 메인보드를 고른다. */}
      <Slot {...slot('MOTHERBOARD')}
        empty={<>
          <rect x={68} y={72} width={344} height={378} rx={12} fill="#2f5a1f" />
          <text x={240} y={426} className="case-slot__hint">+ 메인보드</text>
        </>}
        filled={(part) => <>
          <rect x={68} y={72} width={344} height={378} rx={12} fill="#2f5a1f" stroke="#62b23c" strokeWidth={3}
            filter="url(#case-glow)" />
          <text x={240} y={426} textAnchor="middle" dominantBaseline="middle" fill="#b9e89e" fontSize={12} fontWeight={700}>
            {part.product.modelName.length > 30 ? `${part.product.modelName.slice(0, 29)}…` : part.product.modelName}
          </text>
        </>} />

      {/* 회로선·방열판 장식 (누를 수 없음) */}
      <g pointerEvents="none" stroke="#4c8a33" strokeWidth={2} fill="none" opacity={0.8}>
        <path d="M92 140 H170 M92 160 H170 M300 270 V290 H400 M196 250 V286 M284 250 V286 M90 410 H260" />
        <path d="M380 80 V110 H400 M100 290 H180" />
      </g>
      <g pointerEvents="none" fill="#3f7a2a">
        <rect x={88} y={84} width={50} height={40} rx={4} />
        {[0, 1, 2, 3, 4].map((i) => <rect key={i} x={92 + i * 9} y={88} width={5} height={32} rx={1} fill="#5a9a40" />)}
        <rect x={88} y={182} width={40} height={56} rx={4} />
      </g>

      {/* 쉬라우드(파워 덮개) 구역 */}
      <rect x={68} y={450} width={344} height={124} rx={12} fill="#264a19" pointerEvents="none" />
      <line x1={68} y1={452} x2={412} y2={452} stroke="#9fc98a" strokeWidth={2} pointerEvents="none" />

      <Slot {...slot('COOLER')}
        empty={<EmptyBox x={150} y={86} w={180} h={36} text="쿨러" />}
        filled={(part) => <>
          <FilledBox x={150} y={86} w={180} h={36} />
          <Lines x={240} y={104} lines={[part.product.modelName]} size={12} />
        </>} />

      {/* CPU 소켓 테두리는 항상 보이고, 칩 자리만 바뀐다. */}
      <rect x={188} y={134} width={104} height={104} rx={6} fill="none" stroke="#fff" strokeWidth={3} pointerEvents="none" />
      <Slot {...slot('CPU')}
        empty={<EmptyBox x={200} y={146} w={80} h={80} text="CPU" />}
        filled={(part) => <>
          <FilledBox x={200} y={146} w={80} h={80} rx={4} />
          <Lines x={240} y={186} lines={chipLabel(part.product.modelName)} size={13} />
        </>} />

      <Slot {...slot('RAM')}
        empty={<>
          <EmptyBox x={316} y={116} w={28} h={140} text="메모리" vertical />
          <rect x={352} y={116} width={28} height={140} rx={6} className="case-slot__empty" />
        </>}
        filled={() => <>
          {[316, 352].map((x, index) => index < ramSlots ? (
            <g key={x}>
              <FilledBox x={x} y={116} w={28} h={140} />
              <text x={x + 14} y={186} fill={FILLED_TEXT} textAnchor="middle" dominantBaseline="middle"
                transform={`rotate(-90 ${x + 14} 186)`} fontSize={12}>
                <tspan fontWeight={700}>{ramSize}</tspan> {ramType}
              </text>
            </g>
          ) : <rect key={x} x={x} y={116} width={28} height={140} rx={6} className="case-slot__empty" />)}
        </>} />

      <Slot {...slot('SSD')}
        empty={<EmptyBox x={96} y={250} w={92} h={28} text="SSD" />}
        filled={(part) => <>
          <FilledBox x={96} y={250} w={92} h={28} rx={4} />
          <Lines x={142} y={264} lines={[part.product.modelName]} size={10} />
        </>} />

      <Slot {...slot('GPU')}
        empty={<EmptyBox x={88} y={298} w={304} h={96} text="그래픽카드" />}
        filled={(part) => <>
          <FilledBox x={88} y={298} w={304} h={96} rx={8} />
          {FANS.map((cx) => <g key={cx}>
            <circle cx={cx} cy={336} r={27} fill="#4e9a2d" stroke={FILLED_TEXT} strokeWidth={2} />
            <circle cx={cx} cy={336} r={6} fill={FILLED_TEXT} />
          </g>)}
          <Lines x={240} y={381} lines={[part.product.modelName]} size={12} />
        </>} />

      <Slot {...slot('PSU')}
        empty={<EmptyBox x={84} y={468} w={176} h={88} text="파워" />}
        filled={(part) => <>
          <FilledBox x={84} y={468} w={176} h={88} />
          <circle cx={128} cy={512} r={32} fill="#4e9a2d" stroke={FILLED_TEXT} strokeWidth={2} />
          <path d="M128 482 V542 M98 512 H158 M107 491 L149 533 M149 491 L107 533" stroke={FILLED_TEXT} strokeWidth={1.5} />
          <Lines x={208} y={512} lines={chipLabel(part.product.modelName)} size={11} />
        </>} />

      <Slot {...slot('HDD')}
        empty={<EmptyBox x={276} y={468} w={120} h={88} text="HDD" />}
        filled={(part) => <>
          <FilledBox x={276} y={468} w={120} h={88} />
          <Lines x={336} y={512} lines={chipLabel(part.product.modelName)} size={11} />
        </>} />
    </svg>
  )
}

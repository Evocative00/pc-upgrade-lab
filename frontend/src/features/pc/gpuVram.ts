import type { PartInput } from '../pc-scan/types.ts'

/**
 * 데스크톱 그래픽카드 모델별 VRAM(GiB) 선택지. 제조사 공식 사양 기준이다.
 * 선택지가 하나면 자동으로 채우고, 여러 개면(예: RTX 3060 8/12GB) 사용자가 고른다.
 * 노트북 GPU는 업그레이드 대상이 아니라 넣지 않는다.
 */
const VRAM_OPTIONS: Record<string, number[]> = {
  // NVIDIA GeForce GTX
  'gtx 950': [2, 4], 'gtx 960': [2, 4], 'gtx 970': [4], 'gtx 980': [4], 'gtx 980 ti': [6],
  'gtx 1050': [2, 3], 'gtx 1050 ti': [4], 'gtx 1060': [3, 5, 6], 'gtx 1070': [8], 'gtx 1070 ti': [8],
  'gtx 1080': [8], 'gtx 1080 ti': [11],
  'gtx 1630': [4], 'gtx 1650': [4], 'gtx 1650 super': [4], 'gtx 1660': [6], 'gtx 1660 super': [6], 'gtx 1660 ti': [6],
  // NVIDIA GeForce RTX
  'rtx 2060': [6, 12], 'rtx 2060 super': [8], 'rtx 2070': [8], 'rtx 2070 super': [8], 'rtx 2080': [8],
  'rtx 2080 super': [8], 'rtx 2080 ti': [11],
  'rtx 3050': [6, 8], 'rtx 3060': [8, 12], 'rtx 3060 ti': [8], 'rtx 3070': [8], 'rtx 3070 ti': [8],
  'rtx 3080': [10, 12], 'rtx 3080 ti': [12], 'rtx 3090': [24], 'rtx 3090 ti': [24],
  'rtx 4060': [8], 'rtx 4060 ti': [8, 16], 'rtx 4070': [12], 'rtx 4070 super': [12], 'rtx 4070 ti': [12],
  'rtx 4070 ti super': [16], 'rtx 4080': [16], 'rtx 4080 super': [16], 'rtx 4090': [24],
  'rtx 5050': [8], 'rtx 5060': [8], 'rtx 5060 ti': [8, 16], 'rtx 5070': [12], 'rtx 5070 ti': [16],
  'rtx 5080': [16], 'rtx 5090': [32],
  // AMD Radeon RX
  'rx 470': [4, 8], 'rx 480': [4, 8], 'rx 570': [4, 8], 'rx 580': [4, 8], 'rx 590': [8],
  'rx 5500 xt': [4, 8], 'rx 5600 xt': [6], 'rx 5700': [8], 'rx 5700 xt': [8],
  'rx 6400': [4], 'rx 6500 xt': [4, 8], 'rx 6600': [8], 'rx 6600 xt': [8], 'rx 6650 xt': [8], 'rx 6700': [10],
  'rx 6700 xt': [12], 'rx 6750 xt': [12], 'rx 6800': [16], 'rx 6800 xt': [16], 'rx 6900 xt': [16], 'rx 6950 xt': [16],
  'rx 7600': [8], 'rx 7600 xt': [16], 'rx 7700 xt': [12], 'rx 7800 xt': [16], 'rx 7900 gre': [16],
  'rx 7900 xt': [20], 'rx 7900 xtx': [24],
  'rx 9060 xt': [8, 16], 'rx 9070': [16], 'rx 9070 xt': [16],
  // Intel Arc
  'arc a380': [6], 'arc a580': [8], 'arc a750': [8], 'arc a770': [8, 16], 'arc b570': [10], 'arc b580': [12],
}

// 모델을 모를 때 고를 수 있는 일반적인 VRAM 용량
export const COMMON_VRAM_GIB = [2, 3, 4, 6, 8, 10, 11, 12, 16, 20, 24, 32]

const GIB = 1024 ** 3

/** 판매명·Windows 이름에서 칩셋 키(예: 'rtx 4070 ti super')를 찾는다. 노트북 GPU와 모르는 모델은 null. */
export function gpuModelKey(name: string): string | null {
  const text = name.toLowerCase().replace(/[^a-z0-9]+/g, ' ')
  if (/\b(laptop|mobile|max q)\b/.test(text)) return null
  const nvidia = text.match(/\b(gtx|rtx)\s*(\d{3,4})(?:\s+(ti\s+super|ti|super))?\b/)
  if (nvidia) return [nvidia[1], nvidia[2], nvidia[3]].filter(Boolean).join(' ')
  const amd = text.match(/\brx\s*(\d{3,4})(?:\s+(xtx|xt|gre))?\b/)
  if (amd) return ['rx', amd[1], amd[2]].filter(Boolean).join(' ')
  const intel = text.match(/\barc\s*([ab]\d{3})\b/)
  return intel ? `arc ${intel[1]}` : null
}

/** 모델의 VRAM 선택지(GiB). 표에 없는 모델이면 null. */
export function vramOptions(name: string): number[] | null {
  const key = gpuModelKey(name)
  return key && Object.hasOwn(VRAM_OPTIONS, key) ? VRAM_OPTIONS[key] : null
}

// 이름에 적힌 VRAM(예: '12G', '8GB'). 모델 선택지가 있으면 그 안의 값만 믿는다.
function vramInName(name: string, options: number[] | null): number | null {
  const found = [...name.matchAll(/\b(\d{1,2})\s*G(?:B)?\b/gi)].map((match) => Number(match[1]))
    .filter((value) => value >= 1 && value <= 48 && (!options || options.includes(value)))
  return found.length === 1 ? found[0] : null
}

export type VramSource = 'INPUT' | 'NAME' | 'MODEL'
export type ResolvedVram = { gib: number | null; source: VramSource | null; options: number[] | null }

/**
 * 그래픽카드의 VRAM. 사용자가 고른 값(specs.vramBytes) → 이름에 적힌 값 → 모델 선택지가 하나뿐일 때 그 값 순서로 정한다.
 * 선택지가 여러 개인데 고르지 않았으면 gib는 null이고 options로 고를 값을 알려 준다.
 */
export function resolveVram(part: Pick<PartInput, 'displayName' | 'specs'>): ResolvedVram {
  const options = vramOptions(part.displayName)
  const input = part.specs.vramBytes
  if (typeof input === 'number' && Number.isFinite(input) && input > 0) return { gib: input / GIB, source: 'INPUT', options }
  const named = vramInName(part.displayName, options)
  if (named !== null) return { gib: named, source: 'NAME', options }
  if (options?.length === 1) return { gib: options[0], source: 'MODEL', options }
  return { gib: null, source: null, options }
}

/**
 * 다른 용량이 명시된 판매 상품을 고르거나 이름의 용량을 바꾸면 이전 장치의 VRAM을 지운다.
 * 같은 모델의 이름만 정리하거나 용량이 일치하면 기존 제원을 보존한다.
 */
export function clearStaleVram<T extends PartInput>(previous: PartInput, next: T): T {
  const input = next.specs.vramBytes
  if (next.type !== 'GPU' || typeof input !== 'number') return next
  const options = vramOptions(next.displayName)
  const named = vramInName(next.displayName, options)
  const previousNamed = vramInName(previous.displayName, vramOptions(previous.displayName))
  const newProduct = next.catalogProductId !== null && next.catalogProductId !== previous.catalogProductId
  if (named !== null && named * GIB !== input && (newProduct || named !== previousNamed)) return withVramInput(next, null)
  if (gpuModelKey(previous.displayName) === gpuModelKey(next.displayName)) return next
  return options && !options.includes(input / GIB) ? withVramInput(next, null) : next
}

/** 사용자가 고른 VRAM(GiB)을 부품 제원에 기록한다. null이면 미확인으로 둔다. */
export function withVramInput<T extends PartInput>(part: T, gib: number | null): T {
  return { ...part, specs: { ...part.specs, vramBytes: gib === null ? null : gib * GIB } }
}

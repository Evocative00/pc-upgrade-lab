// 랜딩 문구. 서비스가 실제로 제공하는 검사와 가격 안내 범위를 설명한다.
const assetBase = '/pc-assembly/'

// 시작 화면의 미리받기와 랜딩 본문에 같은 모바일 경계를 적용한다.
export const compactLandingQuery = '(max-width: 640px)'

// 캔버스의 처음과 끝은 고해상도 정지 컷을 사용한다. 요청에 실패하면 기존 이미지로 대체한다.
type AssemblyStill = { image: string; hdImage?: string; endpoint?: 'intro' | 'finished'; alt: string }
export const assemblyStills: AssemblyStill[] = [
  { image: `${assetBase}desktop/pose-0239.webp`, hdImage: `${assetBase}intro-desktop-hd.webp`, endpoint: 'intro', alt: '부품별로 분해된 PC' },
  { image: `${assetBase}desktop/pose-0040.webp`, alt: '옆 패널이 열린 채 부품이 장착된 PC' },
  { image: `${assetBase}poster-desktop.webp`, hdImage: `${assetBase}finished-desktop-hd.webp`, endpoint: 'finished', alt: '조립이 끝난 PC' },
]

// 진행도 0–1/3, 1/3–2/3, 2/3–1을 정지 컷 1~3으로 나눈다.
export function assemblyStepAt(progress: number): number {
  const value = Number.isFinite(progress) ? progress : 0
  return value < 1 / 3 ? 0 : value < 2 / 3 ? 1 : 2
}

// 첫 화면 진행도는 조립 완료를 100으로 두고 120까지 간다. 100 이후는 전원 문구와 캐치프레이즈 구간이다.
export const heroEnd = 120

// 진행도 아래에 5%마다 바뀌는 문구. 조립 순서를 따른다.
export const heroQuips = [
  '부품 상자 뜯는 중',
  '설명서는 일단 옆에 두는 중',
  '정전기 방지 손목띠 찾는 중',
  '메인보드 꺼내는 중',
  'CPU 방향 세 번 확인하는 중',
  'CPU 조립 중',
  '서멀 구리스 얼마나 짤지 고민하는 중',
  'CPU 쿨러 올리는 중',
  '램 몇 개 꽂을지 고민하는 중',
  '램 딸깍 소리 기다리는 중',
  '메인보드 케이스에 넣는 중',
  '나사 하나 어디 갔는지 찾는 중',
  'SSD 끼우는 중',
  '그래픽카드 찾는 중',
  '그래픽카드 길이 재는 중',
  '파워 용량 다시 계산하는 중',
  '파워 케이블 연결하는 중',
  '선 정리 포기할지 고민하는 중',
  '케이스 팬 방향 확인하는 중',
  '옆 패널 닫는 중',
]

const ramp = (value: number, from: number, to: number) => Math.min(1, Math.max(0, (value - from) / (to - from)))

export type HeroCopy = {
  // 화면에 적는 진행도(0–100)
  percent: number
  quip: number
  // 각 문구 묶음의 불투명도(0–1)
  intro: number
  progress: number
  power: number
  final: number
}

// 0–2 인트로에서 진행도로 전환, 100–103 진행도 퇴장, 103–110 전원, 111–120 캐치프레이즈.
export function heroCopyAt(percent: number): HeroCopy {
  const value = Number.isFinite(percent) ? Math.min(heroEnd, Math.max(0, percent)) : 0
  const start = ramp(value, 0, 2)
  return {
    percent: Math.min(100, Math.floor(value)),
    quip: Math.min(heroQuips.length - 1, Math.floor(value / 5)),
    intro: 1 - start,
    progress: Math.min(start, 1 - ramp(value, 100, 103)),
    power: Math.min(ramp(value, 103, 105), 1 - ramp(value, 108, 110)),
    final: ramp(value, 111, 114),
  }
}

export type VirtualBuildSlot = {
  slot: string
  part: string
  note: string
  // 현재가는 가격 데이터를 연결하기 전까지 자리 표시로 둔다.
  price: string
  kept?: boolean
  warn?: boolean
}

export const virtualBuildSlots: VirtualBuildSlot[] = [
  { slot: 'CPU', part: '8코어 데스크톱 CPU', note: '메인보드 소켓과 일치', price: '[현재가]' },
  { slot: 'CPU 쿨러', part: '타워형 공랭 쿨러', note: '케이스 장착 높이 안쪽', price: '[현재가]' },
  { slot: '메인보드', part: 'ATX 메인보드', note: 'RAM 규격과 일치', price: '[현재가]' },
  { slot: 'RAM', part: '32GB (16GB × 2)', note: '슬롯 4개 중 2개 사용', price: '[현재가]' },
  { slot: '그래픽카드', part: '3팬 그래픽카드', note: '케이스 장착 길이 안쪽', price: '[현재가]' },
  { slot: '파워', part: '650W', note: '새 그래픽카드 권장 용량보다 낮음', price: '—', kept: true, warn: true },
  { slot: 'SSD', part: '1TB NVMe', note: '그대로 사용', price: '—', kept: true },
  { slot: '케이스', part: '미들타워 · 강화유리', note: '모든 부품 장착 가능', price: '[현재가]' },
]

export const landingDetails = [
  { n: '01', title: '사양을 받아 적을 필요가 없습니다', body: 'Windows PC의 CPU, 그래픽카드, RAM, 메인보드, 저장장치를 자동으로 읽어 옵니다. 장치 관리자를 열어 모델명을 찾아볼 일이 없어집니다.' },
  { n: '02', title: '부품 조합의 호환성을 미리 확인합니다', body: 'CPU와 메인보드 소켓, 메인보드와 RAM 규격, 그래픽카드 전력과 파워 용량, 그래픽카드 길이와 케이스 공간. 사고 나서 알게 되는 문제들을 미리 확인합니다.' },
  { n: '03', title: '가격은 지금 기준으로 더합니다', body: '확인된 국내 현재 상품가로 합계를 계산하고, 참고가와 가격 미확인 항목은 구분해 보여 줍니다. 구매 전에 가격의 출처와 확인 시각도 살펴보세요.' },
  { n: '04', title: '전부 새로 살 필요는 없습니다', body: '쓰던 부품은 그대로 두고 바꿀 것만 골라 넣습니다. 그래픽카드만 바꿀 때 파워도 같이 바꿔야 하는지 같은 질문에 바로 답이 나옵니다.' },
  { n: '05', title: '로그인은 저장할 때만', body: '구성은 바로 시작합니다. 나중에 다시 보고 싶을 때 Google, 카카오, 네이버 중 하나로 로그인하면 내 PC로 저장됩니다.' },
]

const providerNames: Record<string, string> = { google: 'Google', kakao: '카카오', naver: '네이버' }

export function providerLabel(provider: string): string {
  return `${providerNames[provider.toLowerCase()] ?? provider} 계정`
}

// 아바타에 넣을 이름 첫 글자. 이름이 비어 있으면 물음표.
export function nameInitial(name: string): string {
  return Array.from(name.trim())[0] ?? '?'
}

// 랜딩 테마는 이 화면에서만 쓰는 사용자 편의 설정이다. 저장하지 못하면 다크로 시작한다.
export type LandingTheme = 'dark' | 'light'
const themeKey = 'pc-upgrade-lab.landing-theme'

export function readLandingTheme(): LandingTheme {
  try {
    return globalThis.localStorage?.getItem(themeKey) === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

export function saveLandingTheme(theme: LandingTheme) {
  try {
    globalThis.localStorage?.setItem(themeKey, theme)
  } catch {
    // 저장하지 못해도 현재 화면의 테마는 바뀐다.
  }
}

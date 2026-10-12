// 랜딩 문구. 서비스가 실제로 제공하는 검사와 가격 안내 범위를 설명한다.
const assetBase = '/pc-assembly/'

export type AssemblyStep = {
  label: string
  title: string
  body: string
  meta: string
  // 모션 감소 설정에서 세로로 나열할 정지 컷
  image: string
  alt: string
}

// 스크롤 진행도 0 = 분해(pose-0239), 1 = 완성(포스터). 컷 이미지는 같은 렌더 시퀀스에서 골랐다.
export const assemblySteps: AssemblyStep[] = [
  {
    label: 'STEP 1 / 3',
    title: '일단 다 꺼내 놓습니다',
    body: 'CPU, 쿨러, 메인보드, 그래픽카드, 파워, 팬. 지금 내 PC에 들어 있는 부품이 이렇게 한 장에 펼쳐집니다. 어떤 걸 남기고 어떤 걸 바꿀지는 여기서 고릅니다.',
    meta: 'CPU · COOLER · MB · RAM · GPU · PSU · FAN',
    image: `${assetBase}desktop/pose-0239.webp`,
    alt: '부품별로 분해된 PC',
  },
  {
    label: 'STEP 2 / 3',
    title: '맞는 자리에만 들어갑니다',
    body: 'CPU 소켓과 RAM 규격을 메인보드와 비교합니다. 맞지 않는 조합은 경고하고, 근거가 부족한 항목은 확인이 필요한 상태로 알려 드립니다.',
    meta: 'CPU ↔ MB ↔ RAM',
    image: `${assetBase}desktop/pose-0040.webp`,
    alt: '옆 패널이 열린 채 부품이 장착된 PC',
  },
  {
    label: 'STEP 3 / 3',
    title: '패널을 닫으면 끝',
    body: '그래픽카드 길이와 케이스 공간, 그래픽카드의 권장 파워 용량과 파워 제원을 비교합니다. 구매 전에 장착 조건과 전원 연결을 확인해 보세요.',
    meta: 'GPU ↔ PSU ↔ CASE',
    image: `${assetBase}poster-desktop.webp`,
    alt: '조립이 끝난 PC',
  },
]

// 진행도 0–1/3, 1/3–2/3, 2/3–1을 STEP 1~3으로 나눈다.
export function assemblyStepAt(progress: number): number {
  const value = Number.isFinite(progress) ? progress : 0
  return value < 1 / 3 ? 0 : value < 2 / 3 ? 1 : 2
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
  return `${providerNames[provider.toLowerCase()] ?? provider} 계정으로 로그인`
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

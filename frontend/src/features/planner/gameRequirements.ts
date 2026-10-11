export type RequirementTier = {
  cpu: string[]
  gpu: string[]
  ramGiB: number
  vramGiB?: number
  storage: string
  os: string
  context: string
}

export type GameRequirements = {
  name: string
  source: string
  sourceName: string
  checkedOn: string
  minimum: RequirementTier
  recommended: RequirementTier
  note?: string
}

// 공식 게임 요구 사양의 Windows 기준 요약. 부품 성능 순위나 예상 FPS 데이터가 아니다.
// 출처의 요구 사양이 바뀌면 확인 날짜와 두 단계의 값을 함께 검토한다.
export const GAME_REQUIREMENTS: Record<string, GameRequirements> = {
  '리그 오브 레전드': {
    name: '리그 오브 레전드',
    source: 'https://support.riotgames.com/en-us/league-of-legends/performance/minimum-and-recommended-system-requirements-league-of-legends',
    sourceName: 'Riot Games 공식 지원', checkedOn: '2026-10-11',
    minimum: { cpu: ['Core i3-530', 'A6-3650'], gpu: ['GeForce 400 Series', 'Radeon HD 6570', 'Intel HD 4600'],
      ramGiB: 2, vramGiB: 1, storage: '16GB 여유 공간 · HDD', os: 'Windows 10 빌드 19041+ 또는 Windows 11 · DX11',
      context: '낮음 · 1024×768' },
    recommended: { cpu: ['Core i5-8250', 'Ryzen 3 1200'], gpu: ['GeForce 560', 'Radeon HD 6950', 'Intel UHD 630'],
      ramGiB: 4, vramGiB: 2, storage: '16GB 여유 공간 · SSD', os: 'Windows 11 · DX11',
      context: '높음 · 1920×1080' },
  },
  '발로란트': {
    name: '발로란트',
    source: 'https://support.riotgames.com/en-us/valorant/support-tools/minimum-recommended-pc-specs',
    sourceName: 'Riot Games 공식 지원', checkedOn: '2026-10-11',
    minimum: { cpu: ['Core i3-540', 'Athlon 200GE'], gpu: ['Intel HD 4000', 'Radeon R5 220'],
      ramGiB: 4, storage: '공식 표에 용량 미기재', os: 'Windows 10 빌드 19041+ 또는 Windows 11 · DX11',
      context: '공식 최소 단계 · 30fps' },
    recommended: { cpu: ['Core i3-4150', 'Ryzen 3 1200'], gpu: ['GeForce GT 730', 'Radeon R7 240'],
      ramGiB: 4, storage: '공식 표에 용량 미기재', os: 'Windows 10 빌드 19041+ 또는 Windows 11 · DX11',
      context: '공식 권장 단계 · 60fps' },
    note: '독립형 그래픽카드는 VRAM 1GB 이상이 별도로 필요합니다. 공식 144fps 단계는 이 비교에 포함하지 않았습니다.',
  },
  '배틀그라운드': {
    name: '배틀그라운드',
    source: 'https://support.pubg.com/hc/en-us/articles/115002108373-What-are-the-minimum-and-recommended-requirements-to-play-PUBG',
    sourceName: 'PUBG 공식 지원', checkedOn: '2026-10-11',
    minimum: { cpu: ['Core i5-4430', 'FX-6300'], gpu: ['GeForce GTX 960', 'Radeon R7 370'],
      ramGiB: 8, storage: '40GB 여유 공간', os: 'Windows 10 · DirectX 11', context: '공식 최소 단계' },
    recommended: { cpu: ['Core i5-6600K', 'Ryzen 5 1600'], gpu: ['GeForce GTX 1060', 'Radeon RX 580'],
      ramGiB: 16, storage: '50GB 여유 공간', os: 'Windows 10 · DirectX 11', context: '공식 권장 단계' },
    note: '공식 GPU 예시의 VRAM은 최소 GTX 960 2GB/R7 370 2GB, 권장 GTX 1060 3GB/RX 580 4GB입니다.',
  },
  '오버워치 2': {
    name: '오버워치 2',
    source: 'https://store.steampowered.com/app/2357570/Overwatch_2/',
    sourceName: '게임 배급사의 Steam 요구 사양', checkedOn: '2026-10-11',
    minimum: { cpu: ['Core i3 계열', 'Phenom X3 8650'], gpu: ['GeForce GTX 600 Series', 'Radeon HD 7000 Series'],
      ramGiB: 6, storage: '50GB 여유 공간', os: 'Windows 10 64비트 · DirectX 11', context: '공식 최소 단계' },
    recommended: { cpu: ['Core i7 계열', 'Ryzen 5 계열'],
      gpu: ['GeForce GTX 1060', 'GeForce GTX 1650', 'Radeon R9 380', 'Radeon RX 6400', 'Intel Arc A770'],
      ramGiB: 8, storage: '50GB 여유 공간', os: 'Windows 10 64비트 · DirectX 11', context: '공식 권장 단계' },
    note: 'CPU·GPU의 계열 요구 사항은 특정 모델 성능 판정으로 해석하지 않습니다.',
  },
  '사이버펑크 2077': {
    name: '사이버펑크 2077',
    source: 'https://store.steampowered.com/app/1091500/2077/',
    sourceName: '게임 배급사의 Steam 요구 사양', checkedOn: '2026-10-11',
    minimum: { cpu: ['Core i7-6700', 'Ryzen 5 1600'],
      gpu: ['GeForce GTX 1060', 'Radeon RX 580', 'Intel Arc A380'],
      ramGiB: 12, vramGiB: 6, storage: '70GB 여유 공간 · SSD 필수',
      os: 'Windows 10 64비트 · DirectX 12', context: '1080p · 낮음 · 30fps (광선 추적 끔)' },
    recommended: { cpu: ['Core i7-12700', 'Ryzen 7 7800X3D'],
      gpu: ['GeForce RTX 2060 SUPER', 'Radeon RX 5700 XT', 'Intel Arc A770'],
      ramGiB: 16, vramGiB: 8, storage: '70GB 여유 공간 · SSD 필수',
      os: 'Windows 10 64비트 · DirectX 12', context: '1080p · 높음 · 60fps (광선 추적 끔)' },
    note: '공식 해상도·화질 조건은 사용자가 선택한 목표 해상도·FPS와 다를 수 있습니다.',
  },
}

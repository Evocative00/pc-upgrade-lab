/**
 * 게임별 예상 프레임 계산의 기준점. 게임사 공식 단계 또는 공개된 측정값이며, 화면에는 출처와 신뢰도를 함께 보여 준다.
 * 롤·발로란트·배틀그라운드·오버워치 2는 해상도별 공식 프레임 자료가 없어 리뷰·판매점·제조사 자료를 쓴다.
 * 자료의 표를 통째로 옮기지 않고 계산에 필요한 값만 출처와 함께 둔다. 확인일: 2026-10-14.
 */
export type FrameResolution = 'FHD' | 'QHD' | '4K'

// OFFICIAL: 게임사 공식, REVIEW: 리뷰 실측, COMMUNITY: 커뮤니티 실측, RETAILER: 판매점 자료(측정 방법 미공개),
// VENDOR: 하드웨어 제조사 홍보 수치('최대' 값).
export type FrameSourceKind = 'OFFICIAL' | 'REVIEW' | 'COMMUNITY' | 'RETAILER' | 'VENDOR'

export type FrameSource = { id: string; name: string; url: string; kind: FrameSourceKind; note: string }

export type FramePoint = {
  gpu: string
  resolution: FrameResolution
  fps: number
  sourceId: string
  // 측정에 쓴 CPU. CPU 한계 상한을 내 CPU 기준으로 환산할 때 쓴다.
  cpu?: string
}

export type FramePreset = {
  label: string
  // OFFICIAL_TIER: '이 GPU들 중 하나면 이 프레임'이라는 공식 단계. 예시 중 가장 낮은 GPU를 기준점으로 쓴다.
  // MEASURED: GPU별 측정값. 내 GPU와 성능이 가까운 측정값을 기준점으로 쓴다.
  mode: 'OFFICIAL_TIER' | 'MEASURED'
  points: FramePoint[]
  // OFFICIAL_TIER의 공식 CPU. 내 CPU가 이보다 낮으면 병목 가능성을 안내한다.
  cpu?: string[]
  // 1080p에서 CPU가 프레임을 제한하는 게임이면 측정 최댓값을 내 CPU 성능으로 환산한 상한을 적용한다.
  cpuLimited?: boolean
}

export type GameFrameProfile = { presets: FramePreset[]; note: string }

export const FRAME_SOURCES: Record<string, FrameSource> = {
  'cdpr-official': { id: 'cdpr-official', name: 'CD PROJEKT RED 공식 사양 (2.0 이후)', kind: 'OFFICIAL',
    url: 'https://support.cdprojektred.com/en/cyberpunk/pc/sp-technical/issue/1556/cyberpunk-2077-system-requirements',
    note: '광선 추적 끔 1080p 단계만 사용. 4K 울트라 단계는 업스케일링 없는 실측과 크게 어긋나 제외.' },
  'vrla-lol': { id: 'vrla-lol', name: 'VRLA Tech · League of Legends', kind: 'RETAILER',
    url: 'https://vrlatech.com/league-of-legends/',
    note: 'Ryzen 7 9800X3D, 매우 높음, DX11. 측정 방법 미공개. RTX 40/50 12종.' },
  'vrla-ow2': { id: 'vrla-ow2', name: 'VRLA Tech · Overwatch 2', kind: 'RETAILER',
    url: 'https://vrlatech.com/overwatch-2/',
    note: 'Ryzen 7 9800X3D, 울트라, DX11. 측정 방법 미공개. RTX 40/50 12종.' },
  'vortex-pubg': { id: 'vortex-pubg', name: 'vortexgaming 배틀그라운드 교전 구간 측정', kind: 'COMMUNITY',
    url: 'https://vortexgaming.io/postdetail/1147594',
    note: 'Ryzen 7 7800X3D, FHD, 교전 구간 평균. 그래픽 설정 미기재.' },
  'prosettings-5080': { id: 'prosettings-5080', name: 'ProSettings RTX 5080 리뷰 (2026-03-31)', kind: 'REVIEW',
    url: 'https://prosettings.net/reviews/nvidia-geforce-rtx-5080/',
    note: 'Ryzen 7 9800X3D. 배틀그라운드 1080p 경쟁용 설정 400fps, 1440p 최고 설정 평균 310fps.' },
  'riot-valorant': { id: 'riot-valorant', name: 'Riot Games 발로란트 공식 사양', kind: 'OFFICIAL',
    url: 'https://support.riotgames.com/valorant/support-tools/minimum-recommended-pc-specs',
    note: '144fps 고사양 단계(GTX 1050 Ti, i5-9400F). 해상도·화질 미기재라 1080p로 가정.' },
  'evetech-valorant': { id: 'evetech-valorant', name: 'Evetech RTX 3060 발로란트 (2026-05-20)', kind: 'RETAILER',
    url: 'https://evezone.evetech.co.za/performance-pulse/rtx-3060-valorant-fps-performance-benchmark-best-settings',
    note: 'Ryzen 5 5600·Core i5-12400급 CPU 가정. 1080p 높음 220~280fps, 1440p 중간 160~200fps. 측정 방법 미공개.' },
  'nvidia-valorant': { id: 'nvidia-valorant', name: 'NVIDIA 발로란트 성능 소개 (2026-09-17)', kind: 'VENDOR',
    url: 'https://www.nvidia.com/en-us/geforce/news/valorant-pros-nrg-use-rtx-5080-nvidia-reflex/',
    note: '최고 설정 "최대" 수치. CPU 미기재(최상급 CPU로 가정). RTX 5080 1080p 1000fps·4K 700fps 사용.' },
}

const vrla = (sourceId: string, rows: [string, number, number, number][]): FramePoint[] =>
  rows.flatMap(([gpu, fhd, qhd, uhd]) => [
    { gpu, resolution: 'FHD', fps: fhd, sourceId, cpu: 'Ryzen 7 9800X3D' },
    { gpu, resolution: 'QHD', fps: qhd, sourceId, cpu: 'Ryzen 7 9800X3D' },
    { gpu, resolution: '4K', fps: uhd, sourceId, cpu: 'Ryzen 7 9800X3D' },
  ])

export const GAME_FRAME_PROFILES: Record<string, GameFrameProfile> = {
  '사이버펑크 2077': {
    note: '게임사 공식 단계(광선 추적 끔)를 기준으로 계산합니다.',
    presets: [
      { label: '낮음 (공식 최소 30fps)', mode: 'OFFICIAL_TIER', cpu: ['Core i7-6700', 'Ryzen 5 1600'],
        points: ['GeForce GTX 1060', 'Radeon RX 580', 'Intel Arc A380']
          .map((gpu) => ({ gpu, resolution: 'FHD', fps: 30, sourceId: 'cdpr-official' })) },
      { label: '높음 (공식 권장 60fps)', mode: 'OFFICIAL_TIER', cpu: ['Core i7-12700', 'Ryzen 7 7800X3D'],
        points: ['GeForce RTX 2060 SUPER', 'Radeon RX 5700 XT', 'Intel Arc A770']
          .map((gpu) => ({ gpu, resolution: 'FHD', fps: 60, sourceId: 'cdpr-official' })) },
    ],
  },
  '리그 오브 레전드': {
    note: '판매점 공개 수치(측정 방법 미공개)를 기준으로 한 추정입니다. 1080p에서는 CPU 영향이 큽니다.',
    presets: [{ label: '매우 높음', mode: 'MEASURED', cpuLimited: true, points: vrla('vrla-lol', [
      ['GeForce RTX 5090', 499, 340, 198], ['GeForce RTX 5080', 457, 315, 178], ['GeForce RTX 5070 Ti', 415, 286, 164],
      ['GeForce RTX 5070', 368, 249, 146], ['GeForce RTX 5060 Ti', 290, 194, 114], ['GeForce RTX 5060', 272, 182, 106],
      ['GeForce RTX 4090', 485, 332, 192], ['GeForce RTX 4080 SUPER', 439, 300, 174], ['GeForce RTX 4070 Ti SUPER', 408, 277, 155],
      ['GeForce RTX 4070 SUPER', 384, 255, 152], ['GeForce RTX 4070', 356, 235, 136], ['GeForce RTX 4060 Ti', 290, 198, 115],
    ]) }],
  },
  '오버워치 2': {
    note: '판매점 공개 수치(측정 방법 미공개)를 기준으로 한 추정입니다. 빠른 GPU는 1080p에서 CPU 영향이 큽니다.',
    presets: [{ label: '울트라', mode: 'MEASURED', cpuLimited: true, points: vrla('vrla-ow2', [
      ['GeForce RTX 5090', 520, 385, 228], ['GeForce RTX 5080', 438, 322, 192], ['GeForce RTX 5070 Ti', 374, 274, 162],
      ['GeForce RTX 5070', 312, 228, 136], ['GeForce RTX 5060 Ti', 254, 182, 104], ['GeForce RTX 5060', 208, 148, 84],
      ['GeForce RTX 4090', 416, 302, 178], ['GeForce RTX 4080 SUPER', 348, 250, 148], ['GeForce RTX 4070 Ti SUPER', 296, 214, 126],
      ['GeForce RTX 4070 SUPER', 250, 178, 104], ['GeForce RTX 4070', 202, 142, 84], ['GeForce RTX 4060 Ti', 162, 110, 62],
    ]) }],
  },
  '배틀그라운드': {
    note: '커뮤니티 실측(1080p)과 리뷰(1440p)를 기준으로 한 추정입니다. 4K는 GPU 해상도 비율로 환산합니다.',
    presets: [{ label: '높음 수준 (출처별 설정 상이)', mode: 'MEASURED', cpuLimited: true, points: [
      { gpu: 'GeForce RTX 5060', resolution: 'FHD', fps: 190, sourceId: 'vortex-pubg', cpu: 'Ryzen 7 7800X3D' },
      { gpu: 'GeForce RTX 5060 Ti', resolution: 'FHD', fps: 200, sourceId: 'vortex-pubg', cpu: 'Ryzen 7 7800X3D' },
      { gpu: 'GeForce RTX 5070', resolution: 'FHD', fps: 250, sourceId: 'vortex-pubg', cpu: 'Ryzen 7 7800X3D' },
      // 1080p는 경쟁용(낮은) 설정 값이라 최상위 GPU의 CPU 한계 기준으로 쓴다.
      { gpu: 'GeForce RTX 5080', resolution: 'FHD', fps: 400, sourceId: 'prosettings-5080', cpu: 'Ryzen 7 9800X3D' },
      { gpu: 'GeForce RTX 5080', resolution: 'QHD', fps: 310, sourceId: 'prosettings-5080', cpu: 'Ryzen 7 9800X3D' },
    ] }],
  },
  '발로란트': {
    note: '공식 144fps 단계와 판매점·제조사 수치를 섞은 추정이라 오차가 큽니다. CPU 영향이 매우 큽니다.',
    presets: [{ label: '높음 (1440p는 중간 설정 기준)', mode: 'MEASURED', cpuLimited: true, points: [
      { gpu: 'GeForce GTX 1050 Ti', resolution: 'FHD', fps: 144, sourceId: 'riot-valorant', cpu: 'Core i5-9400F' },
      { gpu: 'GeForce RTX 3060', resolution: 'FHD', fps: 250, sourceId: 'evetech-valorant', cpu: 'Ryzen 5 5600' },
      { gpu: 'GeForce RTX 3060', resolution: 'QHD', fps: 180, sourceId: 'evetech-valorant', cpu: 'Ryzen 5 5600' },
      { gpu: 'GeForce RTX 5080', resolution: 'FHD', fps: 1000, sourceId: 'nvidia-valorant', cpu: 'Ryzen 7 9800X3D' },
      { gpu: 'GeForce RTX 5080', resolution: '4K', fps: 700, sourceId: 'nvidia-valorant', cpu: 'Ryzen 7 9800X3D' },
    ] }],
  },
}

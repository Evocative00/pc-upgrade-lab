/**
 * 영상 편집·개발 프로그램의 공식 요구 사양과 예상 수치 계산 기준. 확인일 2026-10-28.
 * 공식 사양이 막혀 있거나 없으면 2차 자료임을 출처 종류로 밝힌다. 화면에는 출처와 신뢰도를 함께 보여 준다.
 */
import type { Workload } from './plannerGoal.ts'

export type WorkloadSourceKind = 'OFFICIAL' | 'SECONDARY' | 'REVIEW' | 'BENCHMARK_DB'
export type WorkloadSource = { id: string; name: string; url: string; kind: WorkloadSourceKind; note: string }

export type Requirement = { ramGiB?: number; vramGiB?: number; cores?: number; note?: string }

// 예상 수치 종류. BLENDER_RENDER: 기준 장면 렌더 시간, COMPILE: 코드 컴파일 시간,
// EMULATORS: 동시에 띄울 수 있는 Android 에뮬레이터 수, CONTAINERS: Docker 컨테이너·가상 머신 수,
// VIDEO: 편집 해상도별 RAM·VRAM 여유와 CPU·GPU 성능 수준.
export type EstimateKind = 'BLENDER_RENDER' | 'COMPILE' | 'EMULATORS' | 'CONTAINERS' | 'VIDEO'

export type ProgramProfile = {
  name: string
  sourceIds: string[]
  minimum: Requirement
  recommended: Requirement
  // 작업 규모별 여유 있게 쓰는 기준(영상은 편집 해상도). 공식 권장보다 높을 수 있다.
  comfortable?: Record<Workload, Requirement & { label: string }>
  estimates: EstimateKind[]
  keyParts: string
}

export const WORKLOAD_SOURCES: Record<string, WorkloadSource> = {
  'adobe-premiere': { id: 'adobe-premiere', name: 'Adobe Premiere 기술 요구 사항 (26.x)', kind: 'OFFICIAL',
    url: 'https://helpx.adobe.com/premiere/desktop/get-started/technical-requirements/adobe-premiere-pro-technical-requirements.html',
    note: '최소 8GB RAM·VRAM 4GB(HD), 권장 16GB(HD)·32GB(4K 이상)·VRAM 8GB. 2026-10-07 갱신.' },
  'resolve-readme': { id: 'resolve-readme', name: 'DaVinci Resolve 요구 사양 (Blackmagic 안내문 재인용)', kind: 'SECONDARY',
    url: 'https://davinciresolveclub.com/davinci-resolve-system-requirements-2026/',
    note: 'Blackmagic 공식 안내문을 옮긴 2차 자료. 16GB RAM(Fusion은 32GB), GPU VRAM 4GB 이상.' },
  'puget-premiere': { id: 'puget-premiere', name: 'Puget Systems Premiere Pro 하드웨어 가이드 (2026-08-12)', kind: 'REVIEW',
    url: 'https://www.pugetsystems.com/solutions/video-editing-workstations/adobe-premiere-pro/hardware-recommendations/',
    note: '시퀀스 해상도별 최소 VRAM 1080p 4GB·4K 8GB·6K 12GB·8K 16GB 이상. RAM은 작업용 워크스테이션 기준(1080p 64GB~).' },
  'puget-resolve': { id: 'puget-resolve', name: 'Puget Systems DaVinci Resolve 하드웨어 가이드 (2026-03-26)', kind: 'REVIEW',
    url: 'https://www.pugetsystems.com/solutions/video-editing-workstations/davinci-resolve/hardware-recommendations/',
    note: '타임라인 해상도별 최소 VRAM 1080p 8GB·4K 12GB·6K/8K 20GB 이상. RAM은 작업용 워크스테이션 기준(1080p 64GB~).' },
  'blender-requirements': { id: 'blender-requirements', name: 'Blender 공식 요구 사양', kind: 'OFFICIAL',
    url: 'https://www.blender.org/download/requirements/', note: '최소 4코어·8GB·VRAM 2GB, 권장 8코어·32GB·VRAM 8GB.' },
  'blender-opendata': { id: 'blender-opendata', name: 'Blender Open Data (CPU·GPU 렌더링 점수)', kind: 'BENCHMARK_DB',
    url: 'https://opendata.blender.org/', note: '공개 데이터. CPU 1,423종·GPU 179종, Blender 4.5 분당 샘플 수 중앙값.' },
  'openbenchmarking-blender': { id: 'openbenchmarking-blender', name: 'OpenBenchmarking Blender BMW27 (CPU)', kind: 'BENCHMARK_DB',
    url: 'https://mail.openbenchmarking.org/test/pts/blender', note: 'Ryzen 7 9700X CPU 렌더 약 86초(Blender 5.2). 렌더 시간 기준점.' },
  'openbenchmarking-kernel': { id: 'openbenchmarking-kernel', name: 'OpenBenchmarking 리눅스 커널 컴파일 시간', kind: 'BENCHMARK_DB',
    url: 'https://mail.openbenchmarking.org/test/pts/build-linux-kernel',
    note: 'defconfig 빌드. Ryzen 9 9950X 56초, 7900X 73초, 9800X3D 86초, 5950X 95초. 컴파일 시간 기준점.' },
  'vscode-requirements': { id: 'vscode-requirements', name: 'VS Code 요구 사양', kind: 'OFFICIAL',
    url: 'https://code.visualstudio.com/docs/supporting/requirements', note: '1.6GHz CPU, 1GB RAM.' },
  'android-studio': { id: 'android-studio', name: 'Android Studio 설치 요구 사양', kind: 'OFFICIAL',
    url: 'https://developer.android.com/studio/install',
    note: '최소 8GB(에뮬레이터 사용 시 16GB·VRAM 4GB), 권장 32GB·VRAM 8GB. 실행 중인 에뮬레이터 1개당 RAM 약 4GB.' },
  'unity-requirements': { id: 'unity-requirements', name: 'Unity 6 시스템 요구 사양', kind: 'OFFICIAL',
    url: 'https://docs.unity3d.com/6000.0/Documentation/Manual/system-requirements.html', note: 'RAM 8GB 권장, 큰 프로젝트는 더 필요.' },
  'docker-desktop': { id: 'docker-desktop', name: 'Docker Desktop for Windows 설치 요구 사양', kind: 'OFFICIAL',
    url: 'https://docs.docker.com/desktop/setup/install/windows-install/',
    note: '8GB RAM, 가상화 지원. WSL2 기본 설정은 전체 RAM의 50%를 Docker에 할당.' },
}

export const PROGRAM_PROFILES: Record<string, ProgramProfile> = {
  'Premiere Pro': {
    name: 'Premiere Pro', sourceIds: ['adobe-premiere', 'puget-premiere'], estimates: ['VIDEO'],
    keyParts: 'CPU · GPU · RAM·VRAM(해상도에 따라)',
    minimum: { ramGiB: 8, vramGiB: 4, note: 'CPU: Intel 6세대·Ryzen 1000 이상' },
    recommended: { ramGiB: 32, vramGiB: 8, note: 'CPU: Intel 11세대·Ryzen 3000 이상 · HD는 16GB로 충분' },
    // RAM은 Adobe 공식(HD 16GB, 4K 이상 32GB), VRAM은 Puget 해상도별 최소값.
    comfortable: {
      LIGHT: { label: '1080p 편집', ramGiB: 16, vramGiB: 4 },
      MEDIUM: { label: '4K 편집', ramGiB: 32, vramGiB: 8 },
      HEAVY: { label: '6K~8K 편집', ramGiB: 64, vramGiB: 12 },
    },
  },
  'DaVinci Resolve': {
    name: 'DaVinci Resolve', sourceIds: ['resolve-readme', 'puget-resolve'], estimates: ['VIDEO'],
    keyParts: 'GPU·VRAM > RAM',
    minimum: { ramGiB: 16, vramGiB: 4 },
    recommended: { ramGiB: 32, vramGiB: 8, note: 'Fusion은 32GB' },
    // RAM은 Blackmagic 안내(16GB, Fusion 32GB)를 해상도에 맞춰 늘린 값, VRAM은 Puget 해상도별 최소값.
    comfortable: {
      LIGHT: { label: '1080p 편집', ramGiB: 16, vramGiB: 8 },
      MEDIUM: { label: '4K 편집', ramGiB: 32, vramGiB: 12 },
      HEAVY: { label: '6K~8K 편집', ramGiB: 64, vramGiB: 20 },
    },
  },
  Blender: {
    name: 'Blender', sourceIds: ['blender-requirements', 'blender-opendata', 'openbenchmarking-blender'],
    estimates: ['BLENDER_RENDER'], keyParts: 'GPU·VRAM (미지원 GPU는 CPU)',
    minimum: { ramGiB: 8, vramGiB: 2, cores: 4 }, recommended: { ramGiB: 32, vramGiB: 8, cores: 8 },
  },
  'VS Code': {
    name: 'VS Code', sourceIds: ['vscode-requirements', 'openbenchmarking-kernel'], estimates: ['COMPILE'],
    keyParts: 'CPU (빌드 속도)',
    minimum: { ramGiB: 1 }, recommended: { ramGiB: 1 },
  },
  'Android Studio': {
    name: 'Android Studio', sourceIds: ['android-studio', 'openbenchmarking-kernel'], estimates: ['EMULATORS', 'COMPILE'],
    keyParts: 'RAM · CPU · SSD',
    minimum: { ramGiB: 16, vramGiB: 4, note: '에뮬레이터 없으면 8GB' }, recommended: { ramGiB: 32, vramGiB: 8 },
  },
  Unity: {
    name: 'Unity', sourceIds: ['unity-requirements', 'openbenchmarking-kernel'], estimates: ['COMPILE'],
    keyParts: 'CPU · RAM · SSD',
    minimum: { ramGiB: 8 }, recommended: { ramGiB: 32, note: '공식 8GB · 큰 프로젝트는 32GB 이상' },
  },
  'Docker·가상 머신': {
    name: 'Docker·가상 머신', sourceIds: ['docker-desktop', 'openbenchmarking-kernel'], estimates: ['CONTAINERS', 'COMPILE'],
    keyParts: 'RAM · CPU 코어 수',
    minimum: { ramGiB: 8 }, recommended: { ramGiB: 32, note: '공식 권장 없음 · 여러 개 동시 실행 기준' },
  },
}

// 컴파일 시간 기준점(리눅스 커널 defconfig, 초). CPU 점수 비율로 내 CPU 시간을 환산한다.
export const COMPILE_ANCHORS: { cpu: string; seconds: number }[] = [
  { cpu: 'Ryzen 9 9950X', seconds: 56 }, { cpu: 'Ryzen 9 7900X', seconds: 73 },
  { cpu: 'Ryzen 7 9800X3D', seconds: 86 }, { cpu: 'Ryzen 9 5950X', seconds: 95 },
]
// Blender BMW27 장면 CPU 렌더 시간 기준점(초).
export const BLENDER_RENDER_ANCHOR = { cpu: 'Ryzen 7 9700X', seconds: 86 }
// 컨테이너·VM 수 계산 가정. 화면에 가정임을 표시한다.
export const CONTAINER_ASSUMPTION = { dockerShare: 0.5, lightContainerGiB: 0.5, vmGiB: 4, hostReserveGiB: 8 }
export const EMULATOR_ASSUMPTION = { studioGiB: 8, emulatorGiB: 4 }

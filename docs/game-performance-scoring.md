# 게임 공식 요구 사양과 벤치마크 비교

Issue #35의 게임 5종 진단은 공식 예시 모델·입력 용량 확인, 공개 벤치마크 배율, 해상도별 예상 프레임을 보여 준다.
**판정 방침: 정확성보다 활용도를 우선한다.** 근거가 완전히 같지 않아도 공통 눈금으로 환산해 결과를 내고,
환산 여부·출처 종류·오차 범위를 화면에 함께 표시해 사용자가 신뢰도를 판단하게 한다.
업그레이드 플랜은 점검 우선순위를 보여 주는 시연 기능이며, 특정 구매 제품·비용은 계산하지 않는다.

## 공식 요구 사양 확인

- CPU·GPU는 공식 예시 모델과 같으면 그대로 확인한다. 다른 모델이면 벤치마크 배율로 충족·부족을 **추정**하고,
  '공식 동일 모델 확인이 아닌 추정'이라는 문구와 환산 여부를 함께 표시한다.
- RAM은 장치 하나의 용량과 1~64 범위의 정수 수량으로 계산한다. 잘못된 수량·용량은 확인된 자료로 합산하지 않는다.
- GPU 모델과 VRAM 조건을 함께 확인할 때에는 같은 GPU의 정보를 사용한다. 다른 GPU의 용량을 빌려 충족으로 표시하지 않는다.
- OS·명령어 집합·게임별 추가 조건 등 자동 확인하지 않는 범위는 별도 확인 항목이다. ARM CPU의 Blender 점수는 Windows 게임 지원 근거가 아니다.
- 공식 사양의 제품군 표기나 누락된 제원은 판단을 보류한다.

## 벤치마크 배율

`benchmarkComparison.ts`와 `partScores.ts`는 모델을 식별한 뒤 비교 대상의 점수 배율을 계산한다.
CPU는 Blender CPU 렌더링 자료를 4.5 기준으로 환산한 값이다(멀티코어 처리 성능이라 게임 성능과 차이가 있다).
GPU는 모든 자료를 GTX 1060 6GB = 100 공통 눈금으로 두고, 시험 그룹이 달라도 환산해 비교한다(`bridged` 표시).

1. 모델의 제품 번호·CPU 접미사·GPU 메모리 변형을 보존한다. 자료에 없는 GTX 980M, RX 6800M, RTX 5090 D 등을 데스크톱 기본 모델로 대체하지 않는다.
2. 알려진 카탈로그 판매명은 칩셋을 구분할 수 있을 때 연결한다. 'GeForce GTX 1060', 'RX 580 Series'처럼 VRAM 없이 적힌 이름은
   자료의 메모리 변형 중 낮은 점수를 쓴다(성능을 부풀리지 않는 쪽).
3. 시험 그룹이 다르면 공통 눈금으로 환산해 비교하고 결과에 '다른 시험 표를 환산한 비교(오차 약 ±15%)'를 표시한다.
4. 점수가 있는 공식 예시 중 가장 낮은 점수를 기준으로 삼는다. 그 기준 이상이면 충족이다(공식 대안 하나를 넘었으므로).
   기준보다 낮은데 자료가 없는 예시가 섞여 있으면 부족으로 단정하지 않고 보류한다.
5. 기준 이상·미만은 해당 벤치마크 배율의 설명이다. 게임 실행 가능 여부나 구매 보증이 아니다.
6. 모델을 식별할 수 없거나 점수가 있는 예시가 하나도 없으면(제품군 표기만 있는 경우 포함) 판단을 보류한다.

## 해상도별 예상 프레임

`frameEstimate.ts`·`gameFrameData.ts`. 5종 모두 FHD·QHD·4K 예상 프레임을 **추정**으로 표시한다.

- 기준점: 사이버펑크는 CD PROJEKT RED 공식 1080p 단계(낮음 30fps·높음 60fps, 광선 추적 끔). 4K 울트라 단계는 업스케일링 없는 실측과
  크게 어긋나 제외했다. 롤·오버워치 2는 VRLA Tech 해상도별 수치(판매점, 측정 방법 미공개), 배틀그라운드는 커뮤니티 실측과
  ProSettings 리뷰, 발로란트는 Riot 공식 144fps 단계·Evetech·NVIDIA 홍보 수치. 출처와 종류는 화면에 표시한다.
- 계산: 예상 fps = 기준 fps × (내 GPU의 목표 해상도 성능 ÷ 기준 GPU의 기준점 해상도 성능). 해상도 성능은 1080p 상대값에
  Tom's Hardware 같은 표의 1440p·4K ÷ 1080p 비율(`data/benchmark/gpu-resolution-scaling-2026-10-11.json`)을 곱한다.
  측정값은 내 GPU와 성능이 가까운 두 기준점을, 공식 단계는 예시 중 가장 낮은 GPU를 쓴다.
- 오차: 출처 종류별(공식 ±10%, 리뷰·커뮤니티 ±15%, 판매점 ±20%, 제조사 홍보 ±25%), 다른 해상도로 환산하면 +10%.
  기준점과 GPU 성능 차이가 0.4~2.5배를 벗어나면 정확한 값 대신 하한·상한만 표시한다.
- CPU: 롤·발로란트·배틀그라운드·오버워치 2는 최상위 GPU의 1080p 측정값을 내 CPU의 Blender 점수 비율로 낮춘 상한을
  모든 해상도에 적용한다. Blender 멀티코어 점수라 게임 단일 코어 성능과 차이가 있다.

## 영상 편집·개발 프로그램

`workloadData.ts`·`workloadEstimate.ts`·`WorkloadDiagnosis.tsx`. 플랜에서 용도를 '영상·그래픽 작업' 또는 '개발'로 고르면 표시한다.

- 요구 사양: 제작사 공식 최소·권장 RAM·VRAM·코어. Premiere Pro는 Adobe 공식(26.x), DaVinci Resolve는 공식 안내문을 옮긴 2차 자료,
  Blender·VS Code·Android Studio·Unity·Docker Desktop은 각 공식 문서. 영상은 작업 규모를 편집 해상도(1080p·4K·6K~8K)로 보고
  RAM은 공식 안내, VRAM은 Puget Systems 해상도별 최소값으로 판정한다.
- RAM은 모듈 용량 × 수량 합계, VRAM은 제원이 없으면 판매명의 용량 표기('16G')에서 읽고 '이름에서 추정'으로 표시한다.
- 예상 성능(모두 추정):
  - Blender: Blender Open Data GPU 렌더링 점수표(`gpuBlenderScores.ts`, 데스크톱 179종, CPU 점수와 같은 단위)로
    GPU·CPU 렌더 시간을 계산한다. BMW27 장면 기준점은 OpenBenchmarking의 Ryzen 7 9700X CPU 86초, 오차 ±25%.
    이 GPU 점수는 Blender 렌더링 전용이며 게임 비교에는 쓰지 않는다.
  - 코드 컴파일: OpenBenchmarking 리눅스 커널 빌드 시간 기준점 4개 중 CPU 점수가 가까운 것을 점수 비율로 환산, 오차 ±30%.
  - Android Studio: (RAM − 8GB) ÷ 에뮬레이터 1개당 4GB(공식 안내).
  - Docker: 기본 할당 RAM 50%에 컨테이너 0.5GB, VM 4GB(Windows용 8GB 제외)를 가정. 가정값을 화면에 표시한다.
  - Premiere Pro(`premiereEstimate.ts`): 편집 해상도(1080p·4K·8K)를 직접 고른다.
    - 내보내기 시간(10분 영상 → H.264, 하드웨어 인코딩): Techgage 2020 실측(Premiere 14.2, i9-10980XE + RTX 2060 SUPER,
      4K 60초 → 22초, 8K 22초 → 31초)을 CPU·GPU 점수 비율로 환산. 4K는 GPU 영향이 크고(지수 0.5), 8K는 CPU 위주(지수 0.9).
      1080p는 실측이 없어 4K의 35%로 가정. Intel Quick Sync가 있으면 20% 단축. 오차 ±40%. 최신 버전은 더 빠를 수 있다.
    - 타임라인 재생(원활·가끔 끊김·프록시 권장): 해상도별 CPU 점수·GPU 상대값·VRAM(Puget 최소값) 기준. 단계 기준은 팀 설정이다.
    - 해상도별 RAM·VRAM 확인: 1080p 16GB·4GB, 4K 32GB·8GB, 8K 64GB·16GB.
  - Premiere·Resolve 공통: 팀이 정한 '4K 편집 원활' 기준 부품 대비 CPU·GPU 배율과 Intel Quick Sync 유무.
    PugetBench 점수는 상업 이용에 라이선스가 필요해 옮기지 않았다.

## 자료와 재생성

- CPU: `frontend/src/features/planner/cpuBlenderScores.ts`, 생성기 `frontend/scripts/build-cpu-blender-scores.mjs`.
  [Blender Open Data 다운로드](https://opendata.blender.org/download/)는 원본 데이터를 CC0로 제공한다.
  생성기는 단일 소켓의 monster·junkshop·classroom 측정값을 모아 CPU별 중앙값을 계산하고 버전 보정 계수를 적용한다.
  CPU의 버전 환산값은 렌더링 비교 참고이며, 이번 PR 검토에서 원본 전체를 새로 내려받아 재생성하지는 않았다.
- Blender GPU: `data/benchmark/blender-gpu-2026-10-28/`(검색 API 응답), 생성기 `frontend/scripts/build-gpu-blender-scores.mjs`.
- GPU: `data/benchmark/gpu-relative-performance-2026-10-11.json`, 생성기 `frontend/scripts/build-gpu-relative-scores.mjs`.
  모델별 출처와 변형 정보를 생성 결과에 보존하며, 시험 그룹이 다른 모델은 비교하지 않는다.
  측정값의 재사용 범위·출처·갱신일과 추가 변형의 자료는 새로운 배포·추가 수집 전에 확인한다.
- [UL Benchmarks API](https://support.benchmarks.ul.com/support/solutions/articles/44002238427-api-documentation)는 별도 접근 권한을 전제로 하며 이 기능에서 호출하지 않는다.

## 검증 범위

프론트엔드 테스트·lint·build와 잘못된 모델·수량·시험 그룹에 대한 회귀 검증을 수행한다.
자동 테스트의 가상 fixture와 실제 번들 점수표 검사를 구분한다. 실제 계정 로그인·MySQL 자료 변경·게임 실측 FPS 검증은 포함하지 않는다.

# 고상준 1주차 구현·검증 현황

작성: 2026-09-23. 기준 dev: `3b91d9e8117f479a6eefc37b46c14a0d6fcad495`.

## 구현한 범위

| 담당 작업 | 현재 상태 |
| --- | --- |
| 공통 부품 JSON·PC API 규격 | 문서·Java PartInput·TypeScript 타입·공통 샘플 제공 |
| PC 저장 구조 | PcConfiguration / PcPart 엔티티·Repository·Flyway V1 SQL 구현 |
| 여러 RAM·저장장치, 미확정 제품 | 여러 행·수량·원문·제원 JSON 보존, 미연결 제품 ID null 허용 |
| Windows 사양 수집 | PowerShell CIM 수집기·사용자별 설치/제거·오류 경고 구현 |
| 웹 ↔ 수집기 연결 | 검사 생성·상태 조회·시작·결과·실패 API, 분리된 토큰, 2분 만료 구현 |
| React 연결 | 불러오기 패널·실행 링크·상태/경고·결과 반영 콜백 구현 |
| 부품 데이터 조사 | 9개 JSON 파일 실제 검사, 후보 비교와 후속 선정안 작성 |
| PC 등록·조회·수정 API 및 전체 화면 통합 | 팀원 담당 코드 연결 전 |

## 실행해서 확인한 결과

- Java 21 / Gradle 9.7.1: `test` **9개 성공, 실패 0, 누락 0**.
- 백엔드 `bootJar`: 실행 JAR 생성 성공.
- Flyway V1 + JPA: H2 MySQL 모드에서 스키마 적용·검증 성공.
- 저장 계층: 두 RAM·두 저장장치 저장/재조회, 원문·64비트 용량 유지, 다른 ownerKey 조회 차단, 같은 PC 수정 후 자식 행 중복 제거 확인.
- 검사 세션: 서로 다른 토큰/권한 거절, 중복 결과 거절, 정확한 만료 시점 차단, 일부 실패·전체 실패 상태 확인.
- 검사 HTTP 계약: 요청 헤더·no-store·중첩 입력 검증·한글 원문 보존 확인.
- local 프로필의 검사 서비스가 Spring 컨텍스트에 등록되는지 확인.
- PowerShell 7.5.4 / Linux fixture 검사: **13개 검증 성공**. RAM 슬롯 구분·다중 디스크·누락값 null·부분 실패 보존·식별자 제외 확인.
- 프런트엔드: `npm run build`, `npm run lint` 성공.
- 브라우저 자동 검증은 실행 환경에서 브라우저가 시작 중 종료되어 완료하지 못했다. 화면 동작 검증은 아래 실제 Windows 확인 항목에 포함한다.

### 사용자 노트북에서 확인한 진행 결과

- 사용자가 제공한 `/actuator/health` 응답에서 전체 상태와 `components.db.status`가 UP임을 확인했다. 실제 MySQL 연결 확인이며 PC 구성 저장·재조회 검증과 구분한다.
- 사용자 제공 실행 화면에서 실제 사양이 웹에 도착했다. 노트북 1대에서 수집 → 서버 결과 수신 → 웹 표시 흐름을 확인한 결과다.
- 화면에는 Core Ultra 7 155H, Intel Arc Graphics와 RTX 3050 Laptop GPU, RAM 8항목, 메인보드 16Z90SP, Samsung 저장장치 1항목이 표시됐다. 합계 13항목이다.
- GPU 경고 2건은 수집기가 각 GPU의 VRAM 용량을 미확정으로 반환하도록 구현한 결과다.
- 사용자의 `Win32_PhysicalMemory` 조회에서 RAM은 서로 다른 DeviceLocator 8개로 보고됐다. Controller0/1의 ChannelA~D 각각 4 GiB이며, 보고된 용량의 합계는 32 GiB다. 웹의 RAM 8항목과 일치하며, 이 결과만으로 교체 가능한 메모리 모듈이나 슬롯 수를 판단하지 않는다.
- 사용자 제공 MySQL 조회 화면에서 `pc_configuration`, `pc_part`, `flyway_schema_history` 테이블을 확인했다. Flyway 이력은 installed_rank=1, version=1, description=`create pc configuration`, success=1이다. 실제 MySQL의 V1 스키마 생성과 적용 이력을 확인한 결과다.
- 사용자가 보조 프로그램 실행 전 `화면에서 취소`를 누른 뒤 새 검사를 시작해, 사양이 다시 정상 표시되는 것을 확인했다고 보고했다.
- Windows/PowerShell의 정확한 버전, 설치·제거의 반복 검증, 다른 PC 검증은 아직 기록되지 않았다.

### GitHub 공유 상태

- `feature/week1-foundation` 브랜치의 원격 push를 확인했다. 사용자의 기존 `README.md` 수정은 해당 변경에 포함되지 않았다.
- [PR #3](https://github.com/Evocative00/pc-upgrade-lab/pull/3)이 `dev` 대상으로 생성됐다. 병합 전 코드 검토에서 이전에 테스트한 실행 코드와 일치함을 확인했다.
- PR 검토 시 GitHub Actions 실행·커밋 상태 검사 기록은 없었다. 위 검증 결과는 개발 환경에서 실행한 테스트와 사용자 노트북 확인 결과다.

## 아직 확인하지 않은 범위

- Windows PowerShell 버전 확인, 설치·제거의 반복 검증, 수집 중 화면 취소·시간 초과·다중 탭의 실제 동작. 노트북 1대의 정상 수집/표시와 보조 프로그램 실행 전 화면 취소 후 재실행은 위 사용자 결과로 확인했다.
- 실제 Windows PC에서 읽은 CPU·GPU 등 사양과 작업 관리자/시스템 정보 비교. RAM의 위치·개별 용량은 위 CIM 조회 결과와 대조했다.
- 실제 MySQL에서 PC 저장·수정·서버 재시작 후 재조회. DB 연결 UP, V1 적용 이력, H2 테스트를 이 검증의 완료로 보고하지 않는다.
- 문경민의 PC API와 김재훈의 전체 화면을 연결한 등록 → 수정 → 재조회 시연.
- 실제 계정 인증, 계정별 최대 5대 제한, 외부 배포용 수집기.
- 데이터 원천 공동 최종 선정·이용 조건 확인·카탈로그 실제 적재와 목록 API.

## 다음 작업

1. PR #3의 최신 검증 기록을 반영한 뒤 기반 구현을 검토하고 dev에 병합한다.
2. [공통 규격](week1-contract.md)을 기준으로 팀원 모듈을 연결한다.
3. 연결된 PC API로 실제 MySQL 저장·수정·서버 재시작 후 재조회를 확인한다.
4. 실제 Windows에서 수집 중 화면 취소·만료·다중 탭을 확인하고, 이후 데스크톱에서도 수집한다.
5. 데이터 원천을 공동 최종 선정하고 이용 조건과 카탈로그 적용 범위를 확정한다.

GitHub push와 PR 생성을 확인한 기록이다. dev 병합과 팀원 모듈 연결까지 완료한 상태는 아니다.

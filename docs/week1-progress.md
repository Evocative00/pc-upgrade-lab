# 고상준 1주차 구현·검증 현황

작성: 2026-09-23. 기준 dev: `3b91d9e8117f479a6eefc37b46c14a0d6fcad495`.
업데이트: 2026-09-28. 아래 통합 검증은 `fix/week1-integration`의 결과이며 dev 반영은 통합 PR 병합 후 완료된다.

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
| 실제 MySQL 저장 검증 | Windows에서 mysqlTest 1개 통과, 저장·수정·Spring 컨텍스트 재시작 후 재조회·테스트 데이터 정리 확인 |
| PC 등록·조회·수정 API | 팀원 API/요청 파일 통합, 페이지 범위 오류 수정, Windows local/MySQL HTTP 기본 흐름 및 서버 재시작 후 조회 검증 완료 |
| 전체 등록·목록·수정 화면 통합 | 김재훈의 프런트엔드 연결 작업 남음 |

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
- 후속 MySQL 검증 도구: 기존 백엔드 테스트 9개 재통과, `mysqlTestClasses` 컴파일 성공. `mysqlTest` 실행 경로에 MySQL 드라이버가 있고 H2가 없으며, 일반 `build`에 포함되지 않는 것을 확인했다. 실제 MySQL 실행 결과는 아래 사용자 노트북 확인 항목에 기록했다.
- 브라우저 자동 검증은 실행 환경에서 브라우저가 시작 중 종료되어 완료하지 못했다. 화면 동작 검증은 아래 실제 Windows 확인 항목에 포함한다.

### 사용자 노트북에서 확인한 진행 결과

- 사용자가 제공한 `/actuator/health` 응답에서 전체 상태와 `components.db.status`가 UP임을 확인했다. 실제 MySQL 연결 확인이며 PC 구성 저장·재조회 검증과 구분한다.
- 사용자 제공 실행 화면에서 실제 사양이 웹에 도착했다. 노트북 1대에서 수집 → 서버 결과 수신 → 웹 표시 흐름을 확인한 결과다.
- 화면에는 Core Ultra 7 155H, Intel Arc Graphics와 RTX 3050 Laptop GPU, RAM 8항목, 메인보드 16Z90SP, Samsung 저장장치 1항목이 표시됐다. 합계 13항목이다.
- GPU 경고 2건은 수집기가 각 GPU의 VRAM 용량을 미확정으로 반환하도록 구현한 결과다.
- 사용자의 `Win32_PhysicalMemory` 조회에서 RAM은 서로 다른 DeviceLocator 8개로 보고됐다. Controller0/1의 ChannelA~D 각각 4 GiB이며, 보고된 용량의 합계는 32 GiB다. 웹의 RAM 8항목과 일치하며, 이 결과만으로 교체 가능한 메모리 모듈이나 슬롯 수를 판단하지 않는다.
- 사용자 제공 MySQL 조회 화면에서 `pc_configuration`, `pc_part`, `flyway_schema_history` 테이블을 확인했다. Flyway 이력은 installed_rank=1, version=1, description=`create pc configuration`, success=1이다. 실제 MySQL의 V1 스키마 생성과 적용 이력을 확인한 결과다.
- 사용자가 보조 프로그램 실행 전 `화면에서 취소`를 누른 뒤 새 검사를 시작해, 사양이 다시 정상 표시되는 것을 확인했다고 보고했다.
- 2026-09-23 사용자 Windows에서 `Verify-MySql.ps1`을 실행했다. Gradle 9.7.1 출력에서 `MySqlPersistenceTests > persistsUpdatedPartsAcrossApplicationRestart() PASSED`, `BUILD SUCCESSFUL in 20s`를 확인했다. 실제 MySQL에 검증용 PC·부품 저장, 같은 PC 수정, 새 Spring 컨텍스트에서 재조회, 원문·64비트 용량·JSON null 유지, 교체된 자식 행 수와 테스트 데이터 정리까지 통과했다.
- Windows/PowerShell의 정확한 버전, 설치·제거의 반복 검증은 아직 기록되지 않았다. 이후 데스크톱 정상 수집 결과는 아래에 별도로 기록했다.

### 사용자 데스크톱에서 확인한 진행 결과 (2026-09-28)

- 수집기 설치·실행 안내와 진단 스크립트를 보완한 후 사용자가 정상 실행을 확인했다. 제공 화면에 i5-13600KF, RTX 5060 Ti, RAM 2항목, 메인보드 1항목, 저장장치 2항목이 표시됐다. 총 7항목이며 GPU VRAM 미확정 경고 1건은 현재 수집기의 의도된 동작이다.
- API 원본 `70b079b`, 요청 파일 원본 `7aa6986`을 통합하고 페이지 조회 위치 초과 시 500이 발생하는 문제를 400 `INVALID_INPUT`으로 처리하도록 수정했다. 회귀 테스트와 N09/E10 요청을 추가했다.
- 고상준이 기존 local/MySQL 설정으로 HTTP 요청을 실행했다. N01은 201과 ID `1`, 샘플 부품 11항목을 반환했다. N03~N07은 모두 200이며 반복 수정 후에도 RAM 2·STORAGE 2·총 11항목을 유지했다.
- 이름 `김민성 검증용 PC (수정)`, SSD 이름 `TEST SSD A UPDATED`, PSU 수량 `2`를 확인했다. N08/N09/E01~E10은 검증자가 안내한 기대 결과와 일치했다고 보고했다.
- 백엔드 프로세스를 정지·재시작한 뒤 새 POST 없이 R01을 실행했다고 검증자가 확인했다. 200 응답 본문에서 ID `1`, 수정값, 11항목, createdAt/updatedAt이 N07과 같았다.
- 세부 근거와 확인 범위는 [PC API 검증 기록](pc-api-validation.md)에 남겼다. 위 결과는 API 검증이며 전체 프런트엔드의 등록·목록·수정 연결은 별도 작업이다.

### GitHub 공유 상태

- `feature/week1-foundation` 브랜치의 원격 push를 확인했다. 사용자의 기존 `README.md` 수정은 해당 변경에 포함되지 않았다.
- [PR #3](https://github.com/Evocative00/pc-upgrade-lab/pull/3)이 `dev`에 병합됐다. 병합 커밋은 `ebb0b84aed842dce7df338ab26d83f9a9dba45e7`이다. 병합 전 코드 검토에서 이전에 테스트한 실행 코드와 일치함을 확인했다.
- PR 검토 시 GitHub Actions 실행·커밋 상태 검사 기록은 없었다. 위 검증 결과는 개발 환경에서 실행한 테스트와 사용자 노트북 확인 결과다.
- 2026-09-28 통합 검증 시 사용자 브랜치는 `fix/week1-integration`이며, 팀원 원본 커밋과 수집기 수정이 포함된 상태다. API 수정과 이번 검증 기록을 커밋한 뒤 dev 대상 통합 PR로 공유한다.

## 아직 확인하지 않은 범위

- Windows PowerShell 버전 확인, 설치·제거의 반복 검증, 수집 중 화면 취소·시간 초과·다중 탭의 실제 동작. 노트북과 데스크톱의 정상 수집/표시, 노트북에서 보조 프로그램 실행 전 화면 취소 후 재실행은 위 결과로 확인했다.
- 실제 Windows PC에서 읽은 CPU·GPU 등 사양과 작업 관리자/시스템 정보 비교. RAM의 위치·개별 용량은 위 CIM 조회 결과와 대조했다.
- 김재훈의 전체 화면과 PC API를 연결한 등록 → 수정 → 재조회 시연. HTTP 요청을 통한 등록·수정 및 `bootRun` 프로세스 종료·재실행 후 조회는 2026-09-28 검증했다.
- 실제 계정 인증, 계정별 최대 5대 제한, 외부 배포용 수집기.
- 데이터 원천 공동 최종 선정·이용 조건 확인·카탈로그 실제 적재와 목록 API.

## 다음 작업

1. API 수정과 이번 검증 기록을 커밋하고 `fix/week1-integration` → `dev` 통합 PR을 생성한다. 병합 후 기존 PR #8과 이슈 #6/#7을 정리한다.
2. [공통 규격](week1-contract.md)에 따라 전체 프런트엔드의 등록·목록·상세·수정 화면을 PC API와 연결한다.
3. 연결된 화면에서 등록 → 조회 → 수정 → 새로고침 후 재조회까지 시연한다.
4. 실제 Windows에서 수집 중 화면 취소·만료·다중 탭과 설치·제거 반복을 확인한다.
5. 데이터 원천을 공동 최종 선정하고 이용 조건과 카탈로그 적용 범위를 확정한다.

기반 구현, 저장 계층 검사와 2026-09-28 API 통합 검증까지의 기록이다. 전체 화면 연결과 통합 브랜치의 dev 반영은 다음 작업이다.

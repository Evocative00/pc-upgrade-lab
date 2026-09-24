# 프로젝트 문서

처음 합류한 팀원이 1주차 코드를 읽고 자신의 기능을 연결하기 위한 안내다.
현재 제공된 기능은 **Windows 사양 자동 인식, PC 저장 계층, 공통 데이터 규격**이다.
PC 등록·조회·수정 API와 전체 입력 화면은 이 기반에 연결할 작업이다.

## 처음 읽는 순서

1. [실행 방법](week1-setup.md): 최신 dev를 받아 내 PC에서 실행한다.
2. [공통 규격](week1-contract.md): 부품 필드, 단위, API 요청·응답을 확인한다.
3. 아래 표에서 담당 기능의 파일을 순서대로 읽는다.
4. [검증 현황](week1-progress.md)과 [MySQL 검사](week1-mysql-verification.md)에서 확인한 범위를 구분한다.

| 담당 작업 | 코드 읽는 순서 | 연결할 지점 |
| --- | --- | --- |
| 문경민: PC API | [PartInput](../backend/src/main/java/com/pcupgradelab/pc/PartInput.java) → [PcConfiguration](../backend/src/main/java/com/pcupgradelab/pc/PcConfiguration.java) → [PcPart](../backend/src/main/java/com/pcupgradelab/pc/PcPart.java) → [Repository](../backend/src/main/java/com/pcupgradelab/pc/PcConfigurationRepository.java) | 서비스의 트랜잭션 안에서 생성·조회·update를 사용하고, 응답 DTO를 만든다. |
| 김재훈: 화면·API 연결 | [types.ts](../frontend/src/features/pc-scan/types.ts) → [PcScanPanel](../frontend/src/features/pc-scan/PcScanPanel.tsx) → [usePcScan](../frontend/src/features/pc-scan/usePcScan.ts) | 부모 화면에서 onApply로 수집 결과를 입력 폼에 반영하고 PC API와 연결한다. |
| 김민성: API 예시·검증 | [PC 요청 예시](examples/pc-request.json) → [PC 저장 테스트](../backend/src/test/java/com/pcupgradelab/pc/PcPersistenceTests.java) → [스캔 HTTP 테스트](../backend/src/test/java/com/pcupgradelab/scan/ScanControllerTests.java) | 공통 규격을 기준으로 정상·잘못된 입력·수정 후 중복 여부 등을 확인한다. |
| 자동 인식 유지보수·통합 | [ScanController](../backend/src/main/java/com/pcupgradelab/scan/ScanController.java) → [ScanService](../backend/src/main/java/com/pcupgradelab/scan/ScanService.java) → [Invoke-Scan](../collector/windows/Invoke-Scan.ps1) → [PcInventory](../collector/windows/PcInventory.psm1) | 브라우저·서버·수집기가 같은 세션 ID와 공통 데이터 형식으로 통신하는지 확인한다. |

## 자동 인식은 어떻게 동작하나

1. 화면의 `PcScanPanel`이 `usePcScan.prepare()`를 호출한다.
2. 훅이 `ScanController`에 검사 생성을 요청하고, `ScanService`가 메모리에 임시 세션을 만든다.
3. 사용자가 실행 링크를 누르면 설치된 `Invoke-Scan.ps1`이 시작된다.
4. 수집기가 서버에 시작을 등록하고, `PcInventory.psm1`으로 사양을 읽어 결과를 보낸다.
5. 훅이 서버 상태를 반복 조회해 패널에 결과와 경고를 표시한다.
6. 입력 폼을 연결한 화면에서는 사용자가 '입력란에 반영'을 누를 때 `onApply(result)`가 호출된다.

이 과정은 **사양 수집과 결과 전달**이다. PC 구성을 MySQL에 자동 저장하지 않는다.
현재 `App.tsx`는 `onApply`를 생략한 결과 확인용 패널을 보여 준다.

## PC 저장은 어떻게 연결하나

팀원이 구현할 연결 흐름은 **입력 폼 → PC API의 Controller/Service → Repository → MySQL**이다.

- `PartInput`은 전달 형식(DTO), `PcConfiguration`과 `PcPart`는 DB 행에 대응하는 객체(엔티티)다.
- `Repository`는 엔티티를 저장·조회하는 창구다. 직접 SQL을 작성하지 않아도 Spring Data JPA가 구현을 제공한다.
- 서비스의 `@Transactional`은 DB 작업을 하나의 처리 단위로 묶는 선언이다. 조회·수정·응답 DTO 변환을 그 안에서 처리한다.
- PC 수정은 `pc.update(name, parts)`를 사용한다. 기존 PC ID를 유지하면서 **부품 목록 전체**를 교체한다.
- 목록 조회는 이름·날짜 등의 요약, 상세 조회는 부품까지 포함한다. 정확한 응답 형식과 코드 예시는 [공통 규격](week1-contract.md)에 있다.

## 연결할 때 지킬 데이터 규칙

- `quantity`는 장치 개수이고 `capacityBytes`는 **장치 한 개의 bytes 용량**이다. 이름이 같아도 RAM·저장장치를 임의로 합치지 않는다.
- 미확인 제원은 `null`이다. 미확인 값을 0으로 바꾸거나, GPU 이름만 보고 정확한 판매 제품을 확정하지 않는다.
- `UNMATCHED`는 카탈로그 미연결 상태다. 자동 수집 실패를 뜻하지 않는다.
- 수집 결과를 폼에 반영할 때 기존 `MANUAL` 부품은 보존하고 `AUTO` 항목은 교체한다. 편집 내용을 덮어쓸 때는 확인을 받는다. 이 처리는 부모 화면에서 구현한다.
- '화면에서 취소'는 화면의 대기를 끝낸다. 실행된 Windows 프로그램을 종료하거나 서버 세션을 취소하는 기능은 아니다.
- `ownerKey`는 서버가 정한다. 로컬용 `local-dev` 값을 로그인 인증으로 간주하거나 요청 본문에서 소유자를 받지 않는다.
- Java의 `PartInput`/`ScanDtos`, 프런트 `types.ts`, PowerShell 수집기, 공통 규격 문서는 같은 필드 이름과 의미를 사용한다.

## DB와 테스트를 읽을 때

[V1 SQL](../backend/src/main/resources/db/migration/V1__create_pc_configuration.sql)은 PC와 부품 테이블을 처음 만드는 파일이다.
`pc_part.pc_id`가 PC를 가리키고, 부품 제원은 `specs` JSON에 저장된다. 부품 목록의 저장·교체는 `PcConfiguration`이 관리한다.

**이미 적용한 V1 파일은 주석만 추가하는 경우에도 수정하지 않는다.** Flyway는 파일 체크섬을 저장해 변경 여부를 확인한다.
새로운 스키마 변경이 필요하면 후속 버전 파일을 추가하고, 설명은 엔티티 주석과 문서에 작성한다.

| 검사 | 확인하는 범위 | 준비물 |
| --- | --- | --- |
| `gradlew.bat test` | H2에서 저장 규칙, 스캔 상태, HTTP 요청·응답 규격 | JDK 21, Gradle 의존성 |
| `Test-Inventory.ps1` | 가짜 장치 데이터로 수집기 변환 규칙 검사 | PowerShell |
| `Verify-MySql.ps1` | 실제 MySQL 저장·수정·새 Spring 컨텍스트에서 재조회 | Windows, 기존 V1 테이블, 개인 DB 설정 |
| 실제 화면·수집기 실행 | 내 Windows 사양이 서버를 거쳐 화면에 도착하는지 확인 | 같은 PC의 MySQL·백엔드·프런트·설치된 수집기 |

H2 검사가 실제 MySQL 검사를 대신하지 않으며, 저장 계층 검사가 전체 화면 통합을 대신하지도 않는다.
검사별 실행 방법은 [실행 안내](week1-setup.md)와 [MySQL 검사 안내](week1-mysql-verification.md)를 참고한다.

## 그 밖의 문서

- [부품 데이터 조사](week1-data-review.md): 후보 데이터의 필드와 한계, 공동 선정할 사항.
- [스캔 결과 예시](examples/scan-result.json): 자동 인식 JSON 형식을 이해하기 위한 개발용 샘플.

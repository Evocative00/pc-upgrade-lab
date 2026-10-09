# 카탈로그 확장 구현과 적재 미리보기

2026-10-09 사용자가 승인한 **모델·판매 상품 구분, 공통 ID, SSD·보드 슬롯 구조 구현과 DB 없는 미리보기 준비**의 산출물이다. 이후 사용자의 **V13·V14 로컬 MySQL 적용 승인**에 따라 구조 적용과 기존 데이터 보존 검증을 완료했다. 제품 채택·자료 적재·신규 가격 수집·배포는 후속 컨펌 대상이다.

## 구현 범위

| 범위 | 구현 내용 | 기존 자료 처리 |
| --- | --- | --- |
| 모델 식별 | `catalog_model`, 출처, 별칭; 모델과 RAM 규격군 종류 구분 | 기존 제품을 모델로 자동 승격하지 않음 |
| 상품 식별 | 제품의 `canonical_id`, `model_id`, `identity_kind`, `role`, 검토 근거 | 기존 UUID 유지; 기존 행은 공통 ID/모델 null, `LEGACY_UNCLASSIFIED`/`UNASSIGNED` |
| PC 저장 | 선택적 `catalogModelId`·`recognitionLevel`; 종류·제품/모델 관계 검증 | 기존 요청 생략/null 허용; 수량·제원·원문·소유권 유지 |
| 저장장치 | `storage_spec`; 십진 광고 용량, SATA/PCIe·NVMe, 형태/치수/방열판 | 제조사 명목 용량과 실제 수집 bytes를 구분 |
| 보드 슬롯 | 리비전별 프로필, 슬롯 길이/버스/프로토콜, CPU·BIOS·공유 조건, 근거 | 자료 없음·부분 자료를 미지원으로 해석하지 않음 |
| 프론트 | 제품 연결/모델만 확인 선택, SSD 제원, 보드 슬롯 근거 조회 | 모델만 확인한 항목은 상품가 합계에 포함하지 않음 |
| 미리보기 | 원자료 328개 해시 검증, 기존 300종 ID 매핑 계획, 후보 78종 | DB 연결·제품/가격 생성 없음 |

신규 스키마는 [V13](../backend/src/main/resources/db/migration/V13__add_catalog_identity.sql)과 [V14](../backend/src/main/resources/db/migration/V14__add_storage_catalog_support.sql)다. V1~V12는 수정하지 않았다. 승인한 로컬 MySQL에는 V14까지 적용됐으며, 다른 PC는 최신 코드를 받고 정상 백엔드를 시작하면 Flyway가 두 구조를 적용한다. 기존 UUID·제품·가격·PC 자료를 유지하고 후보 자료는 자동 적재하지 않는다. [적용 기록](../data/catalog-review/schema-applied-2026-10-09.json)

## 백엔드 실행 구성과 팀원 적용

이번 변경으로 새 프로필·환경변수·실행 구성은 필요하지 않다. 기존 IntelliJ `backend [bootRun]`을 그대로 사용한다.

- 프로젝트 SDK와 Gradle JVM: **JDK 21**, 프로젝트 Gradle Wrapper 사용.
- 기존 `SPRING_PROFILES_ACTIVE=local`, `DB_PASSWORD`, 개인 `application-local.properties`와 사용 중인 OAuth 환경변수 유지.
- 이 PC의 `backend [bootRun]`에는 위 환경변수가 이미 설정돼 있다. 개인 실행 구성을 변경하지 않았다.
- 실행 중인 서버는 최신 코드로 재시작한다. 이 PC는 이미 V14라 같은 구조를 중복 적용하지 않는다. 다른 PC의 첫 시작에는 V13·V14 적용 로그를 확인한다.
- `ddl-auto=validate`, Flyway 사용을 유지한다. seed·가격 import를 시작 설정에 추가하지 않는다.
- 기존 `importCatalogPrices`와 `setupDevCatalog -PapplyDevCatalog=true`도 현재 엔티티 구조를 검사하므로 **V14까지 준비된 DB**가 필요하다. 다른 PC는 해당 작업 전에 정상 `bootRun`으로 최신 마이그레이션을 적용한다.

개인 설정·환경변수·MySQL 자료는 Git pull로 전달되지 않는다. 적용됐어도 모델·SSD·슬롯 테이블은 아직 비어 있으므로 모델 목록이나 슬롯 근거가 없는 것은 현재 단계에서 정상이다. 설치된 Windows 수집기는 pull 후 [기존 설치 안내](week1-setup.md#3-windows-보조-프로그램-설치)에 따라 재설치해야 새 필드를 전송한다. 이번 작업에서는 설치된 수집기를 변경하지 않았다.

## 모델 확인과 제품 연결

- **모델만 확인:** 모델 ID와 MODEL(RAM 규격군은 SPEC_GROUP)을 저장한다. 제품 ID는 null, `UNMATCHED`이며 가격을 붙이지 않는다.
- **제품 연결:** 기존 제품 ID와 `MATCHED`를 사용한다. 제품의 모델 관계만으로 사용자 PC의 정확 변형 확인 수준을 자동 설정하지 않는다.
- **정확 변형 확인:** PHYSICAL_VARIANT 요청은 별도 검토한 제품 분류와 연결이 필요하다. 제품과 모델 ID를 함께 보내면 부품 종류와 관계가 모두 일치해야 한다.
- **수집기:** 두 신규 필드는 null로 보내며 이름만으로 모델·제품을 확정하지 않는다. BOM과 장치별 수량·용량을 유지한다. 편집된 AUTO 항목 교체 확인과 MANUAL 보존 규칙도 유지한다.

읽기 API는 기존 검토용 카탈로그와 같이 `local` 프로필에서 제공한다. 모델 등록과 상품 분류를 수정하는 HTTP API는 제공하지 않는다.

내부 상품 바인딩은 같은 제품에 대한 검토를 잠금으로 직렬화하고, 잠금 후 최신 행을 다시 읽어 이미 승인된 공통 ID·모델·분류를 덮어쓰지 않도록 한다. MySQL의 REPEATABLE READ 일반 조회는 첫 조회의 스냅샷을 유지할 수 있으므로 새로 읽을 때도 잠금을 명시한다. 실제 MySQL에서의 동작은 별도 통합 검증 대상이다. [MySQL 8.4 트랜잭션 격리 수준](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)

| 요청 | 반환 |
| --- | --- |
| `GET /api/catalog/models?type=CPU&q=...&page=0&size=20` | 모델 목록과 페이지 정보 |
| `GET /api/catalog/models/{id}` | 모델, 별칭과 근거, 관련 제품 후보; 원본 payload 제외 |
| `GET /api/catalog/products/{id}` | 기존 상세 + 식별 메타데이터, STORAGE 제원 지원 |
| `GET /api/catalog/products/{id}/storage-support` | 보드 리비전별 슬롯·조건·출처; 자료 없음은 false/false/빈 목록 |

슬롯 자료 조회는 SSD 호환 판정 기능이 아니다. 현재 CPU·보드·RAM 호환 검사 범위에 SSD가 자동 추가되지는 않는다. 메모리 전압·DDR3L·CUDIMM 세부 조건과 구형 CPU 규격 적재도 후속 단계다.

## DB 없는 미리보기 실행

프로젝트 루트에서 JDK 21로 실행한다. DB_PASSWORD, MySQL, 개인 properties, OAuth 키 없이 자료를 검증한다. Gradle 배포본과 의존성이 처음에는 필요할 수 있다.

```powershell
.\backend\gradlew.bat --project-dir .\backend previewCatalogExpansion
```

이 작업은 Spring·웹 서버·Flyway·JDBC를 시작하지 않는다. `--apply`나 `-Papply...=true`는 거부하며 실제 적용 기능이 없다. 기존 `importCatalogPrices`·`setupDevCatalog`와 별도 작업이다. pull·build/test·미리보기가 카탈로그나 가격을 자동 적재하지 않는다.

Node.js 24로 생성 파일이 원자료와 일치하는지만 확인할 수도 있다.

```powershell
node data/catalog-review/build-expansion-preview-2026-10-09.mjs --check
```

원자료를 승인된 연구 내용으로 수정한 경우 같은 스크립트를 `--check` 없이 실행하면 미리보기 JSON을 재생성한다. 원자료의 해시가 바뀌면 먼저 관련 검토표를 다시 생성·검토해야 한다.

### 미리보기 수량과 검토 순서

| 묶음 | 수량 | 현재 상태 |
| --- | --- | --- |
| 기존 제품 | 300 | 검토한 BUILDCORES 원본 ID로 공통 ID 계산 계획; 로컬 제품 ID는 미조회/null |
| 기존 승인 가격 | 72 | 기존 자료 유지; 현재 웹 가격을 재수집한 결과 아님 |
| 우선 검토 | 52 | SSD 9 + 최신 Intel 20 + 최신 AMD 20 + 누락 GPU 3 |
| 후속 검토 | 26 | 구형 Intel 16 + 데스크톱 RAM 10 |
| 별도 보류 | 6 | Ryzen AI 데스크톱: 단품 판매·보드 지원 확인 필요; 78종에 미포함 |
| 신규 제품·가격 생성 | 0 / 0 | 실제 DB에 적재하지 않음 |

미리보기의 모든 신규 후보는 `NEEDS_REVIEW`이며 채택 미승인이다. 부품번호 미확인 65종은 임의로 채우지 않는다. 조사에서 확인한 부품번호도 국내 판매 SKU 승인과는 구분한다.

기존 제품의 공통 ID는 `BUILDCORES + 정확한 원본 externalId`로 결정적으로 계산한다. 이름·PN 유사도로 로컬 행을 추정하지 않는다. 로컬 적용 시 원본 출처의 동일성·종류를 검사하고 기존 제품 UUID를 그대로 유지해야 한다. 지금 미리보기는 로컬 DB를 조회하지 않아 매핑 성공을 주장하지 않는다.

미리보기 원자료: [JSON](../data/catalog-review/catalog-expansion-preview-2026-10-09.json). 제품별 근거·미확인 항목은 [후보 목록](catalog-expansion-candidates-2026-10-09.md), 전체 방향은 [설계안](catalog-db-expansion-design-2026-10-09.md)에 있다.

## 후속 컨펌

다음 단계는 우선 52종의 모델·정확 제품 구분, 공식 제원과 CPU 지원표/BIOS·슬롯 근거를 보강해 **실제로 채택할 목록**을 제시하는 것이다. 승인된 목록과 적재 변화량을 준비한 뒤 자료의 실제 적재를 확인받는다. V13·V14 구조는 이미 적용됐으며 추가 스키마 변경이 필요하면 후속 버전과 적용 범위를 별도로 제시한다.

중앙 가격 API, 관측 신선도(48시간 경고·7일 합계 제외), 매일 가격 수집은 설계 방향이며 이번 구현에는 포함되지 않는다. 현재 가격 새로고침은 저장된 관측을 다시 조회한다. 실제 가격을 새로 수집하거나 중앙 DB와 동기화하지 않는다.

## 검증 기록

| 검증 | 결과와 범위 |
| --- | --- |
| 프론트 `npm.cmd test` | 114개 통과. 기존 로그인·가격·RAM 수량·새 모델 저장/초안·슬롯 응답 검증 포함 |
| 프론트 lint / build | 모두 통과 |
| 최종 H2 전체 회귀 | JDK 21에서 `test --rerun-tasks`: 325개 모두 통과. 실패·오류·스킵 0; 67개 suite |
| 미리보기 오류 수정 후 | 9개 경계 테스트 모두 통과. UUID/문자열 비교 및 JSON 숫자 표기 차이를 수정; 실제 값 변경·승인 승격·원자료 변조는 거부 |
| 최종 H2 변경 범위 | 모델/PC/스캔·저장장치·미리보기 41개 모두 통과. 같은 제품에 대한 동시 바인딩과 이미 조회한 행의 최신 상태 재검증 2건 포함 |
| `previewCatalogExpansion` | 성공. 기존 300/72, 후보 78(52+26), 별도 보류 6, 원자료 328개 해시 확인. 신규 제품/가격 0/0 |
| 미리보기 적용 요청 거부 | `-PapplyCatalogExpansion=true` 실행을 guard에서 예상대로 거부. 실제 DB 적용 경로 없음 |
| Node 생성기 `--check` | 성공. 저장된 미리보기와 원자료 일치 |
| 수집기 `Test-Inventory.ps1` | 가짜 장치 데이터 14개 assertion 통과. 신규 모델 확인 필드는 null; UTF-8 BOM 보존 |
| 실제 MySQL 8.4.11 | V12 정상 이력·문자셋 확인 후 V13·V14 적용, Flyway 검증 및 Hibernate validate 통과. 기존 19개 테이블의 원래 컬럼 전체 자료·행 수 동일, 새 테이블 11개 모두 0행 |

첫 전체 회귀에서 발견한 미리보기 실패 3건을 수정한 뒤 변경 범위 41개와 미리보기를 통과했고, GitHub 업로드 전 전체 회귀 325개를 다시 실행해 모두 통과했다. 동시 바인딩 검증은 H2의 독립 트랜잭션으로 수행했다. 실제 MySQL에서는 이번 스키마 적용·JPA 구조 확인·기존 자료 보존을 검증했으며 동시 바인딩 통합 시나리오까지 실행한 것으로 기록하지 않는다.

실제 MySQL에는 웹 서버 없이 일회성 non-web Spring 실행으로 Flyway와 JPA 검증만 수행했다. 모든 seed/import를 껐고, 적용 전 백업은 Git 제외 대상인 `backend/build/catalog-apply-session/`에만 남겼다. 상품 300종·가격 매핑/관측 각 72종·PC 1개/부품 항목 14개와 기존 모든 원래 컬럼 자료를 보존했다. 새 공통 ID·모델·제품 분류·PC 확인 수준은 아직 채우지 않았다.

`mysqlTest`·일반 웹 `bootRun`·실제 Windows CIM/URI 통합·OAuth·두 기기 중앙 동기화·신규 상품가 수집은 수행하지 않았다. 기존 V1~V12와 가격 원자료도 변경하지 않았다. 코드·문서·연구 자료는 `dev` 업로드 범위이며 개인 설정·백업·기존 별도 로컬 변경은 포함하지 않는다.

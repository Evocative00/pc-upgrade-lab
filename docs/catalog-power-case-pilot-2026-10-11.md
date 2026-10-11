# 파워 케이스 7종과 호환성 확장

2026-10-11 사용자 승인 후 파워 4종·케이스 3종과 확정 판매가 2건을 실제 MySQL에 반영하고 중앙 가격 서버에 배포했다. 최신 중앙 묶음은 **315종·판매가 83종·기존 참고가 227종·가격 보류 5종**이다. V15 적용·기존 행 보존·적재 재실행·실제 Worker 및 Java HTTPS 검증을 통과했고, 기존 설정으로 backend를 재시작한 뒤 로컬 API에서 상품·가격·호환성·인증·CSRF 동작까지 확인했다.

원 [7종 검토 자료](../data/catalog-review/power-case-pilot-2026-10-11.json)와 [미게시 stage](../data/catalog-shared/power-case-stage-2026-10-11.json)의 `databaseApplyApproved`·`publicationApproved`는 계속 `false`로 보존한다. 실제 DB 반영은 별도 [DB 승인 파일](../data/catalog-review/power-case-database-approval-2026-10-11.json)의 `databaseApplyApproved=true`, 중앙 게시는 별도 [release 승인 파일](../data/catalog-shared/approved-power-case-extension-2026-10-11.json)의 두 승인 값 `true`를 검증해 실행했다. DB 승인 파일의 `publicationApproved=false`는 DB 승인만을 뜻한다. 자동 적재·상품 자동 활성화는 하지 않는다.

승인 범위와 실제 DB·배포·재시작·API 검증의 전체 결과는 [최종 반영 기록](../data/catalog-review/power-case-rollout-2026-10-11.json)에 모았다. 이 JSON은 `completedAt` 시점의 완료 기록이며 `currentWorkGitCommitOrPush=NOT_RUN`도 당시 상태다. 이후 사용자 승인으로 진행하는 GitHub 커밋·push 상태는 저장소 이력에서 확인하며 이 검증 원본은 수정하지 않는다.

## 선정한 상품과 제원

| 종류 | 정확한 상품 | 출력 또는 장착 규격 | 공표 치수와 제한 | 제조사 근거 |
| --- | --- | --- | --- | --- |
| PSU | MSI MAG A650BN | 650 W, ATX | 깊이 140 × 폭 150 × 높이 86 mm | [제원](https://www.msi.com/Power-Supply/MAG-A650BN/Specification) |
| PSU | MSI MAG A750GL PCIE5 II | 750 W, ATX 3.1 | 140 × 150 × 86 mm | [제원](https://www.msi.com/Power-Supply/MAG-A750GL-PCIE5-II/Specification), [케이블 구성](https://www.msi.com/Power-Supply/MAG-A750GL-PCIE5-II/) |
| PSU | MSI MAG A850GL PCIE5 II | 850 W, ATX 3.1 | 140 × 150 × 86 mm | [제원](https://www.msi.com/Power-Supply/MAG-A850GL-PCIE5-II/Specification) |
| PSU | Corsair SF1000 Platinum 2024 CP-9020257-KR | 1,000 W, SFX, ATX 3.1 | 100 × 125 × 63.5 mm | [SF1000과 SF1000L 구분](https://www.corsair.com/kr/ko/explorer/diy-builder/power-supply-units/sf1000-platinum-atx-31-vs-sf1000l-what-are-the-differences/) |
| CASE | Fractal Pop Air Black Solid FD-C-POA1A-01 | ATX·Micro-ATX·Mini-ITX 보드, ATX PSU | PSU 170 mm, GPU 405 mm, 슬롯 7개 | [Black Solid 제원](https://www.fractal-design.com/products/cases/pop-series/pop-air/pop-air-black-solid/) |
| CASE | ASUS Prime AP201 Mesh Black 90DC00G0-B39000 | Micro-ATX·Mini-ITX 보드, ATX PSU | PSU 140–180 mm, GPU 338 mm, 슬롯 4개 | [제원](https://www.asus.com/sg/motherboards-components/cases/prime/asus-prime-ap201-microatx-case/techspec/), [PSU 배치 조건](https://www.asus.com/za/support/faq/1048059/) |
| CASE | Cooler Master MasterBox NR200 Black MCB-NR200-KNNN-S00 | Mini-ITX·Mini-DTX 보드, SFX·SFX-L PSU | PSU 130 mm, GPU 330 × 156 × 60 mm, 슬롯 3개 | [일반 NR200 제원](https://www.coolermaster.com/ko-kr/products/masterbox-nr200.html) |

Pop Air의 GPU 길이는 전면 기본 팬 장착 기준이다. AP201의 PSU·GPU 공간은 PSU 위치와 냉각장치·저장장치 배치에 따라 달라진다. NR200의 GPU 길이·높이에는 전원 연결 공간도 포함된다. 따라서 세 케이스의 GPU 공간을 조건 없이 장착 가능한 값으로 취급하지 않는다. 미공표 높이·두께는 `null`이다.

750GL II의 16핀 케이블 출력은 공식 제원표와 소개 페이지가 서로 달라 `maxPowerW=null`로 보존한다. SF1000의 16핀 명칭도 공식 본문과 케이블 표가 달라 세부 규격을 확정하지 않는다. 플러그 수와 독립 케이블 수는 별도 필드이며, A650BN의 독립 케이블 수는 미확인이다. SATA·IDE·FDD 전원은 이번 검사 범위에 포함하지 않는다.

전체 값, 단일 변형 식별 근거, 출처와 가격 보류 사유는 [7종 검토 JSON](../data/catalog-review/power-case-pilot-2026-10-11.json)에 보존한다. 다른 색상·패널·지역 SKU·II 이전 버전·NR200P/MAX/V3의 자료를 가져와 채우지 않는다.

## 가격 반영 기준

가격 후보는 신품 상품 1개 기준, 배송비·카드·회원·현금 조건을 제외한 일반판매가다. 정확한 변형과 판매 구성, 판매 상태를 함께 확인해야 한다. 배송 포함 여부가 다른 가격을 임의로 차감하거나 검색 캐시 금액을 현재가로 사용하지 않는다.

2026-10-11 관측하고 승인 후 반영한 가격은 A650BN [75,600원](https://prod.danawa.com/info/?pcode=15283025), Pop Air Black Solid [149,000원](https://prod.danawa.com/info/?pcode=17357669) 두 건이다. 원문 일반 판매행의 `data-base-price`와 별도 배송비 필드를 확인했다. 숨김 상태인 배송비 포함 요약의 금액은 사용하지 않는다. 두 관측은 실제 DB와 중앙 공표 승인본에 추가했으며 원래 관측 시각을 보존한다.

750GL II·850GL II는 국내 판매 페이지의 II 버전 식별, SF1000은 KR SKU와 재고 확인이 남아 있다. AP201 Mesh Black은 가격비교가 중지됐고, 일반 NR200 Black의 정확한 국내 판매가는 미확인이다. 보류 상품 5종에는 가격 관측이나 자동 추정 가격을 만들지 않는다.

상품 화면에서는 기존 결정대로 금액만 표시하고, 가격 근거·관측 시각·내부 검토 상태는 상품 밑에 붙이지 않는다. 신규 7종은 실제 DB에 등록했고, 최신 backend 재시작 후 로컬 API에서 315종 전체 가격과 7종 상세 제원·출처·호환성 연결을 확인했다. 브라우저 수동 화면 검증은 별도로 남아 있다.

## 추가한 호환성 검사

| 조합 | 검사하는 근거 | 확인이 필요한 조건 |
| --- | --- | --- |
| 메인보드 ↔ 케이스 | 케이스의 전체 지원 폼팩터 목록 | 목록 미확인, E-ATX의 실제 폭 |
| GPU ↔ 케이스 | 길이·높이·두께·슬롯 폭 | 조건부 공간, 라디에이터·팬·전원 케이블 여유 |
| PSU ↔ 케이스 | PSU 폼팩터·깊이 | 브래킷·드라이브 베이·배선·장착 위치 |
| GPU ↔ PSU | 제조사 시스템 요구 출력, 직접 연결 플러그·독립 케이블·공표 케이블 출력 | 권장 출력 미달, 케이블 수·16핀 변형·전력 분배 미확인 |

공표 최소 출력 미달과 확인된 기본 장착 규격 불일치는 `INCOMPATIBLE`이다. 권장 출력 미달이나 자료·사용 조건 미확인은 `NEEDS_CHECK`다. 6+2핀 하나를 6핀과 8핀 요구에 중복 배정하지 않으며, 16핀의 세부 규격과 변환 어댑터 호환성을 이름이나 모양으로 확정하지 않는다.

기존 CPU·메인보드·RAM 검사는 유지한다. 메모리 용량 설명은 GiB로 표시하고 BIOS와 ECC 안내에서 내부 필드 이름·영문 원문을 제거했다. 전체 PC 전력 여유·피크 전력, 메인보드 PCIe 슬롯과 CPU 전원 단자, 쿨러·저장장치 호환성은 이번 검사 범위 밖이며 `fullPcCompatibilityChecked=false`다.

## 저장 구조와 API

신규 `V15__add_psu_case_catalog_support.sql`은 파워·케이스 제원 및 케이블·지원 규격 목록의 구조만 추가한다. 승인된 실제 DB에 V14→V15를 적용했고 기존 4,223행을 모두 보존한 채 새 테이블 5개를 추가했다. 적용된 V1–V14 파일은 수정하지 않는다. 일반 실행이나 테스트가 7종 상품·가격을 자동 적재하지 않는다.

파워·케이스의 목록형 제원은 카탈로그 상세 응답에만 포함한다. PC `PartInput.specs`와 수집 결과의 단순 값 계약은 유지하며, PC에는 사용자가 선택한 `catalogProductId`로 연결한다. 이번 작업은 Windows가 PSU·케이스를 자동 식별하는 기능을 추가하지 않는다. 모델만 확인하는 기능은 기존 모델 종류에 한정한다.

`POST /api/compatibility/check`는 기존 CPU·보드·RAM 요청에 선택 필드 `gpuProductId`, `psuProductId`, `caseProductId`, `presentComponents`를 추가한다. `presentComponents`에는 입력된 GPU·PSU·CASE의 종류를 중복 없이 보낸다. ID가 `null`인 입력도 빈 자리와 구분해 미연결로 검사한다. 한 종류에 여러 행 또는 복수 수량이 있으면 한 상품으로 임의 검사하지 않는다.

기존 요청은 `CPU_MOTHERBOARD_RAM_V1`, 추가 부품 요청은 `CPU_MOTHERBOARD_RAM_GPU_PSU_CASE_V2` 범위다. 프론트는 추가 부품에 V1 응답이 오면 호환 결과로 표시하지 않는다. 조회 검사이며 PC·제원·가격을 저장하지 않는다. CSRF와 기존 세션 규칙은 유지한다.

## DB 없는 미리보기

프로젝트 루트에서 JDK 21로 실행한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend previewPowerCaseCatalog
```

DB 비밀번호·OAuth 키·개인 설정·실행 중인 웹 서버가 필요 없다. 외부 판매처에 접속하지 않고 저장소의 검토 자료만 검사한다. 출력은 무시되는 `backend/build/catalog-power-case-preview/` 아래에 만들며, 실제 상품·제원·가격·중앙 확장을 위한 제안과 테이블별 예상 행 수를 보고한다. 기존 DB의 중복이나 현재 상태를 읽은 결과는 아니다.

`previewPowerCaseCatalog`에는 적용 모드가 없다. `--apply`를 지원하지 않으며 실제 반영 옵션을 거절한다. 중앙 확장 미리보기는 운영 승인본을 바꾸거나 배포 명령을 실행하지 않는다.

## 승인 파일과 전용 적재기

전용 `importPowerCaseCatalog`의 기본 실행도 DB 없는 미리보기다. 읽기 대조와 실제 적용은 명시적인 별도 실행 옵션으로 분리한다. 적용에는 추가로 정확한 승인 파일이 필요하다. 승인 파일은 검토 JSON의 SHA-256, 상품 공통 ID 7개, 테이블별 추가 행 수, 정확한 가격 2건의 판매처·판매상품 ID·금액·관측 시각을 고정한다. 해시는 UTF-8 BOM 제거·CRLF를 LF로 변환한 뒤 계산한다. 승인 제안 파일은 `PENDING`, `databaseApplyApproved=false`, `publicationApproved=false`로 생성한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importPowerCaseCatalog
```

승인 제안은 `backend/build/catalog-power-case-import/database-approval-proposed.json`에 생성한다. `checkPowerCaseDb=true`는 실제 DB 읽기 대조이며 별도 요청 후에만 실행한다. 쓰기는 `applyPowerCaseCatalog=true`와 `powerCaseApprovalFile`을 함께 요구하고, 승인 파일을 검증하기 전에는 Spring·개인 설정·DB를 열지 않는다.

| 추가한 테이블 | 실제 추가 행 수 |
| --- | ---: |
| `catalog_product` / `catalog_reference_price` | 각각 7 |
| `catalog_product_source` | 14 |
| `psu_spec` / `psu_power_connector` | 4 / 15 |
| `case_spec` / `case_motherboard_form_factor` / `case_psu_form_factor` | 3 / 7 / 4 |
| `catalog_price_mapping` / `catalog_price_observation` | 각각 2 |
| 모델·별칭 신규 등록 | 0 |

적재기는 V15가 이미 적용된 DB만 사용하며 Flyway·웹 서버·다른 seed를 실행하지 않는다. 기존 승인 카탈로그 308종과 호환 근거·가격을 대조하고, 공통 ID·외부 출처·정확 상품·제원의 충돌을 거절한다. 실제 적용은 상품·제원·출처·가격·식별 연결을 한 트랜잭션으로 처리하고, 같은 입력을 다시 실행해도 중복을 만들지 않는다. 기존 행의 변경이 발견되면 롤백한다. 기존 상품 활성화·모델 생성은 하지 않는다.

제조사 PN이 공표되지 않은 MSI 상품도 이 7종의 정확한 제조사 외부 ID와 판매 구성 근거에 한정해 연결한다. PN이나 SKU를 만들어 기존 적재 규칙을 우회하지 않는다. 가격 보류 5종은 계속 미확인으로 둔다. 이번 실행은 별도 DB 승인 파일을 받아 읽기 대조·적용·같은 승인 입력 재실행을 완료했다. 다른 기기의 DB는 pull이나 일반 서버 시작으로 상품·가격이 적재되지 않으며, 해당 DB의 준비 상태와 승인 범위에 맞춰 명시적으로 실행해야 한다.

### 다른 PC의 실제 DB 적용 순서

아래 명령은 프로젝트 루트의 PowerShell에서 실행한다. JDK 21·MySQL 8.4와 본인의 기존 `backend/src/main/resources/application-local.properties`를 사용한다. DB 작업을 실행할 터미널에도 `DB_PASSWORD`가 필요하며, IntelliJ의 환경변수가 별도 PowerShell로 전달되지는 않는다. 개인 설정·기존 OAuth 키·중앙 가격 환경변수 세 개와 팀 토큰은 보존하고 `.env`가 자동으로 읽힌다고 가정하지 않는다.

대상 DB에는 기존 승인 상품 **308종·판매가 81종·호환 근거·공통 ID 연결**이 먼저 준비돼 있어야 한다. 빈 DB나 일부만 등록된 DB는 이 적재기로 준비할 수 없다. 기존 준비 이력은 [300종 및 식별 구조](catalog-expansion-implementation-2026-10-09.md), [첫 14종 적재](catalog-pilot-review-2026-10-10.md), [ASUS 판매상품 보강](catalog-all-shared-prices-2026-10-10.md#판매-구성-146종-후속-검토와-4종-미리보기)를 확인한다. 본인 DB의 적용 범위가 승인됐는지 확인한 뒤 진행한다.

1. V15 이전 DB라면 기존 `SPRING_PROFILES_ACTIVE=local`·DB·OAuth 실행 구성으로 일반 backend를 시작해 Flyway V15를 적용한다. 이 단계는 실제 스키마를 변경하며 상품·가격은 자동 적재하지 않는다. 시작 로그에서 V15 완료를 확인하고 서버를 종료한다. V15가 이미 적용돼 있으면 이 단계의 마이그레이션은 필요 없다.

   ```powershell
   .\backend\gradlew.bat --project-dir .\backend bootRun
   ```

2. 기존 308종과 출처·제원·가격을 읽기 전용으로 대조한다. 이 적재 작업에는 OAuth 키·웹 서버가 필요 없고 Flyway와 다른 seed도 실행하지 않는다.

   ```powershell
   .\backend\gradlew.bat --project-dir .\backend importPowerCaseCatalog -PcheckPowerCaseDb=true
   ```

3. 공개 [DB 승인 파일](../data/catalog-review/power-case-database-approval-2026-10-11.json)을 확인한다. 신규 상품 7종·가격 관측 2건과 검토 자료 해시를 고정한 `decision=APPROVED`, `databaseApplyApproved=true` 파일이다. 미리보기에서 만든 `PENDING` 제안이나 중앙 release 승인 파일을 DB 승인 파일로 대신 사용하지 않는다.

   ```powershell
   Get-Content .\data\catalog-review\power-case-database-approval-2026-10-11.json -Raw
   ```

4. 읽기 대조가 통과했고 해당 DB의 적용이 승인된 경우에만 상품·제원·출처·가격을 한 트랜잭션으로 반영한다. Gradle의 승인 파일 옵션은 `backend` 폴더 기준이므로 아래 `../data/...` 경로를 그대로 사용한다.

   ```powershell
   .\backend\gradlew.bat --project-dir .\backend importPowerCaseCatalog -PapplyPowerCaseCatalog=true "-PpowerCaseApprovalFile=../data/catalog-review/power-case-database-approval-2026-10-11.json"
   ```

5. 최초 반영 결과의 `createdProducts=7`, `createdSources=14`, `productBindings=7`, 가격 연결·관측 각각 2건, 모델·활성화 0건을 확인한다. 이미 같은 7종이 완전히 등록된 DB에서 같은 명령을 재실행하면 `unchangedProducts=7`, 생성·쓰기 0건이다. 부분 적재나 기존 값 충돌은 거절되므로 DB 초기화나 출처 값 변경으로 우회하지 않는다.

6. 본인의 기존 JDK 21·`local`·DB·OAuth·중앙 가격 실행 구성으로 backend를 다시 시작한다. 프론트는 기존 5173 포트에서 실행하고, 가격·제원과 새 호환성 검사를 확인한다. 중앙 Worker는 이미 배포돼 있으므로 다른 PC의 DB 준비에 Worker 재배포나 팀 토큰 교체는 필요 없다.

## 중앙 가격 미게시 검증

```powershell
.\backend\gradlew.bat --project-dir .\backend previewPowerCaseSharedCatalog
```

`backend/build/catalog-power-case-stage/`에 기존 308종·판매가 81종을 포함한 315종·판매가 83종의 미게시 스냅샷을 만든다. 기존 참고가 227종의 값과 근거를 그대로 유지하고 새 상품 5종에는 가격을 추가하지 않는다. [미게시 검토 manifest](../data/catalog-shared/power-case-stage-2026-10-11.json)는 조사 자료 해시를 고정하며 DB·공표 승인 모두 false다.

Worker와 Java의 미리보기 소비 경로는 명시적인 선택과 별도 버전·고정 자료 검증을 사용한다. Java 미리보기는 로컬 loopback과 테스트 전용 토큰에 한정한다. 미리보기 작업 자체는 중앙 배포·운영 버전 전환을 실행하지 않는다. 승인 후 추가한 운영 release와 미게시 stage는 같은 상품·가격 자료를 검증하되 승인 파일과 소비 경로를 구분한다.

생성 파일을 사용한 Worker 로컬 검증은 프로젝트 루트에서 `node workers/catalog-prices/scripts/verify-power-case-stage.mjs`로 실행한다. Miniflare 메모리 환경에서 기존·신규 판매가와 기존 참고가를 모두 대조하며 Cloudflare에 접속하거나 배포하지 않는다. 315종·판매가 83종·기존 참고가 227종의 생성 파일 검증은 통과했다. 이번 운영 전환은 별도 승인 release와 운영 버전 선택으로 완료했으며 미게시 manifest의 false 값을 바꾸지 않았다.

## 실제 DB 반영과 중앙 배포 결과

승인 범위인 V15 구조·신규 상품 7종·확정 가격 2종을 반영했다. 신규 상품 7종에 공통 ID를 연결하고 출처 14행·판매상품 연결 2행·가격 관측 2행을 포함해 총 65행을 추가했다. 적용 전후 36개 테이블을 감사했으며 기존 상품·제원·가격·PC·회원 자료의 변경은 없었다. 같은 승인 입력의 재실행은 기존 상품 7종을 확인하고 쓰기 0건으로 끝났으며 모델 생성·상품 활성화도 0건이다.

공개 감사 기록은 [V14 적용 전 상태](../data/catalog-review/power-case-db-before-2026-10-11.json), [V15 마이그레이션 보존 감사](../data/catalog-review/power-case-db-migration-audit-2026-10-11.json), [V15 적용 후 적재 전 상태](../data/catalog-review/power-case-db-v15-before-apply-2026-10-11.json), [읽기 대조](../data/catalog-review/power-case-db-check-2026-10-11.json), [실제 적재](../data/catalog-review/power-case-db-apply-2026-10-11.json), [재실행](../data/catalog-review/power-case-db-replay-2026-10-11.json), [최종 36테이블 감사](../data/catalog-review/power-case-db-verified-2026-10-11.json)에 남겼다. 비밀번호·OAuth 키·팀 토큰과 비공개 원본 행은 포함하지 않는다.

중앙 Worker는 `2026-10-11T08:02:20.607388Z`(한국 시간 17:02:20)에 version `8a7a5ad7-e8b0-4814-91ec-c595e6717186`, deployment `dbc186d9-4ff5-4441-a765-f5ce130e8036`으로 배포하고 트래픽 100%를 전환했다. 기존 팀 Secret과 서버 환경변수를 유지했다. 최신 315종 카탈로그 헤더 요청에서 판매가 83종·`NO_PRICE` 232종, 참고가 227종·`NO_REFERENCE` 88종을 실제 HTTPS로 대조했다. 기존 14·307·308종 버전과 헤더 없는 기본 응답, 신규 ID의 구버전 미등록 처리, 무인증 401·캐시·콘텐츠 헤더까지 36개 요청을 통과했다. [실제 Worker HTTPS 검증 기록](../data/catalog-review/power-case-worker-production-2026-10-11.json).

참고가 227종의 값·근거·관측 시각·내용 버전은 그대로이며 [315종용 승인 참고가](../data/catalog-shared/approved-power-case-reference-prices-2026-10-11.json)는 카탈로그 버전과 게시 승인만 맞춘다. 새 7종에 참고가를 생성하지 않았다. 실제 Java HTTPS에서도 315종·판매가 83종·참고가 227종과 이전 307종·80가격/308종·81가격, 기본 v1 14종·8가격, 인증 실패 401·최대 100개 묶음 조회를 통과했다. [Java HTTPS 검증 기록](../data/catalog-review/power-case-java-https-2026-10-11.json).

실행 중 신규 7종의 출처 조회 시각이 9시간 늦게 표시되는 오류를 확인했다. 신규 PSU·CASE 출처 조회와 상세 응답 변환에 UTC 읽기를 적용해 원래 14개 출처 시각을 보존했고 기존 자료의 읽기 경로는 유지했다. 관련 3개 클래스·21개 회귀 테스트를 통과했으며 신규 시간대 매개변수 5개 사례를 포함한다.

JDK 21 backend를 8080 포트에서 PID 11200으로 재시작하고 기존 5173 프론트엔드를 유지했다. Flyway V15는 최신 상태로 추가 마이그레이션 0건이며 개인 DB·OAuth·중앙 가격 설정 변경과 seed 실행도 없었다. [backend 재시작 기록](../data/catalog-review/power-case-backend-restart-2026-10-11.json).

이 재시작 기록은 검증 당시 상태다. 이후 8080 포트 충돌의 원인이 검증 후 남아 있던 PID 11200임을 확인해 해당 backend를 종료하고 8080 포트가 비어 있음을 확인했다. 다시 사용할 때는 기존 IntelliJ backend 실행 구성을 시작하거나, 동일한 개인 환경이 준비된 터미널에서 위 `bootRun` 명령을 실행한다. DB 반영과 중앙 배포를 다시 할 필요는 없다.

`http://127.0.0.1:5173` Vite 프록시 API에서 전체 315종·판매가 83종·참고가 227종, 신규 7종 상세 제원·출처 14개와 정확한 시각, 보류 5종의 가격·참고가 없음 처리를 확인했다. 네 부품 조합을 포함한 호환성 10개 사례에서 확정 불일치 우선 처리, 미연결 확인 필요, 기존 V1 범위, 32 GiB와 한국어 안내를 검사했다. CSRF 헤더 없는 요청은 403, 올바른 쿠키·헤더 요청은 200이며 익명·개발 헤더 PC 요청은 401이었다. 검증은 읽기 전용으로 PC·회원·가격을 저장하거나 로그인을 시도하지 않았다. [로컬 API 검증 기록](../data/catalog-review/power-case-local-api-2026-10-11.json).

## 검증 결과

| 검증 | 결과 |
| --- | --- |
| 구현 단계 프론트엔드 전체 테스트 | 142개 통과; 운영 승인 추가 전의 전수 결과 |
| 구현 단계 프론트엔드 lint·build | 통과 |
| 운영 승인 추가 후 가격 Worker 테스트·타입 검사·build | 1,155개 테스트 및 타입 검사·build 통과 |
| 운영 승인·적재·공유 관련 백엔드 선별 H2 테스트 | 25개 테스트 클래스·146개 테스트 통과 |
| 신규 PSU·CASE 출처 UTC 조회 수정 회귀 테스트 | 별도 3개 클래스·21개 테스트 통과, 실패·오류·제외 0; 시간대 매개변수 5개 사례 포함 |
| 구현 단계 백엔드 전체 H2 테스트 | 100개 테스트 클래스·518개 테스트 통과, 실패·오류·제외 0; 운영 승인 추가 전의 전수 결과 |
| DB 없는 상품·적재 승인 제안·중앙 확장 미리보기 | 통과, DB 접근·쓰기·게시 0 |
| 생성한 중앙 파일의 Miniflare 대조 | 315종·판매가 83종·기존 참고가 227종 통과 |
| 실제 MySQL V14→V15 마이그레이션 감사 | 기존 4,223행 보존·새 테이블 5개·통과 |
| 실제 MySQL 읽기 대조·적재·재실행·36테이블 감사 | 7종·가격 2종·총 65행 추가, 재실행 쓰기 0건·통과 |
| 실제 Worker 배포 및 HTTPS 조회 | 트래픽 100%, 36개 요청·315종·판매가 83종·참고가 227종·이전 버전 보존·통과 |
| Java 실제 HTTPS | 315종·판매가 83종·참고가 227종·이전 버전·기본 v1·인증 401·최대 100개 조회 통과 |
| 기존 설정의 backend 재시작 | JDK 21·8080/기존 프론트 5173·V15 최신 상태·추가 마이그레이션 0·개인 설정 변경 0 |
| 실제 Vite 프록시 로컬 API | 315종 전체 가격·신규 상세 7종/출처 14개·호환성 10사례·인증·CSRF 통과, DB 쓰기 0 |
| 브라우저·OAuth 수동 검증 / 별도 `mysqlTest` | 미실행 |

H2 통합 테스트에는 기존 행 보존, 출처·판매상품 충돌, 마지막 가격 저장 실패의 전체 롤백과 중복 없는 재실행을 포함한다. 각 테스트 행은 서로 다른 실행 범위이므로 합산하지 않는다. 실제 MySQL·Worker·Java·로컬 API 결과는 위 별도 실행·감사 기록으로 확인했다. 브라우저와 OAuth 수동 검증 및 별도 `mysqlTest`는 실행하지 않았다.

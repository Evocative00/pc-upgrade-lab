# 전체 307종 중앙 가격 전환과 가격 확보

2026-10-10. **최신 운영은 308종·가격 81종·가격 미확인 227종이다.** ASUS 인텍앤컴퍼니 판매상품 1종·275,350원 추가와 MySQL 반영·무료 Worker 배포·backend 재시작을 완료했다. 최신 실행·팀원 적용은 [Worker 안내](catalog-worker-prices-2026-10-10.md)를 따른다. 아래는 기존 307종 전환과 80종 가격 확보 기록이다. 최초 75종 전환 때 공통 ID 293개를 연결했고, 이후 승인한 5종 가격을 추가했다. 기존 307종 클라이언트에는 계속 80종 가격을 제공한다.

## 최초 307종 전환 범위 (75종 가격)

| 항목 | 전환 전 | 적용 후 |
| --- | ---: | ---: |
| 중앙 가격 조회 대상 | 14종 | 307종 |
| 로컬 가격 조회 대상 | 293종 | 0종 |
| 중앙 등록 가격 | 8종 | 75종 |
| 중앙 가격 미확인 | 6종 | 232종 |

307종은 CPU 72, GPU 80, 보드 73, RAM 60, 모니터 20, SSD 2종이다. 기존 293종에 공통 ID와 출처 근거만 연결한다. 상품 ID·제원·가격·PC 연결·활성화·검증 상태와 기존 14종을 보존하며, 판매 상품·설치 모델 구분을 임의로 승격하지 않는다. RAM은 단품 26종과 키트 34종의 단위를 구분한다. 새 스키마 마이그레이션은 필요 없다.

전체 중앙 조회로 바뀌어도 가격을 아직 확보하지 못한 제품은 **중앙 가격 미확인**이다. 검색 실패나 빈 가격 HTML만으로 품절·단종으로 확정하지 않는다.

## 가격 자료와 조사

`data/catalog-shared/all-catalog-identities-2026-10-10.json`은 기존 300종 seed와 승인 신규 7종에서 생성한다. `approved-prices-2026-10-10.json`은 기존 72종과 pilot 8종 중 중복 6종을 최신 관측으로 대체한 74종이며 원래 관측시각을 유지한다.

기존 72종 현재 상품가를 다시 수집했다. 기존 최신 승인 자료와 비교한 **금액 변경은 22종**이다. SSD 2종은 기존 관측을 보존한다. 모니터 16종의 국내 페이지를 조사해 상품가 4종을 관측했고, 그중 정확 PN이 맞는 Dell S2721DGF **976,500원** 1종을 더한 **75종·미확인 232종**을 승인받아 중앙 Worker에 반영했다. LG 3종은 지역 SKU가 달라 동등성 검토를 보류했다. 로컬 DB 가격은 덮어쓰지 않았다.

- [74종 가격 갱신 미리보기](../data/catalog-review/all-catalog-price-refresh-proposal-2026-10-10.json)
- [현재 승인 가격 80종](../data/catalog-shared/active-approved-prices.json), [최초 75종 검토 당시 제안](../data/catalog-review/all-catalog-price-proposal-2026-10-10.json), [승인·반영 범위 보고서](../data/catalog-review/all-catalog-price-proposal-2026-10-10.json.report.json)
- [모니터 16종 조사와 지역 SKU 보류 근거](../data/catalog-review/all-catalog-monitor-price-candidates-2026-10-10.json)
- [변동 22종과 중앙 반영 보고서](../data/catalog-review/all-catalog-price-refresh-proposal-2026-10-10.json.report.json)
- [307종 식별 감사](../data/catalog-review/all-catalog-identity-audit-2026-10-10.json)
- [233종 가격 확보 조사·보류 사유](../data/catalog-review/all-catalog-price-acquisition-2026-10-10.json)
- [CPU·GPU·RAM 우선 12종의 정확 PN 조사와 보류 사유](../data/catalog-review/all-catalog-priority12-price-candidates-2026-10-10.json)

초기 미확인 233종 중 Dell 1종을 반영해 232종이 남았다. 정확한 부품번호·판매 구성·RAM 개수·보드 리비전·국내 신품 판매를 검토한 뒤 수집한다. 배송비·쿠폰·프로모션·현금 전용 가격은 제외한다. 검색 후보는 승인된 가격이 아니다. 새 GPU 후보 10종 중 9종은 현재 원문의 일반 상품가 영역이 비었고, 1종은 OC 변형이 달라 보류했다. 과거 고가 후보 6종도 판매처 교차 검토가 필요하다.

최초 233종 일괄 검색은 HTML을 읽기 전 연결 실패가 발생했다. 이후 명시적으로 네트워크 실행을 허용한 재조사는 **233종 전체 완료·요청 실패 0건·판매 후보 150종·검색 후보 없음 83종**이다. 후보 150종의 첫 상세 페이지를 추가 조사해 **74개 페이지에서 일반 상품가를 읽었다**. 이는 상품 구성 미검증 자료이며 기존 Dell 후보도 포함하므로 확보 가격 75종에 더하지 않는다. 최초 실패나 검색 후보 없음은 제품·재고·가격이 없다는 근거로 사용하지 않는다.

- [233종 판매 후보 검색](../data/catalog-review/all-catalog-danawa-discovery-retry-2026-10-10.json)
- [150종 상세 페이지 가격 후보 조사](../data/catalog-review/all-catalog-price-candidate-pages-2026-10-10.json)

검색 결과에는 5800X 대신 5800X3D 기념판, 250K Plus 대신 270K Plus, BOX 부품 대신 멀티팩·벌크 상품 등이 실제로 섞였다. 페이지 본문의 부품번호 문자열 출현도 관련 상품이나 긴 SKU의 일부일 수 있어 일치 확정에 사용하지 않는다. 다음 가격 확보는 이 후보를 정확 PN·국내 판매 구성과 대조하는 작업이다.

최초 보드 6종 조사에서 MSI MPG B650 CARBON WIFI 915,000원은 실제 판매처 일반가와도 대조해 후보로 준비했으며, 당시 승인한 75종에는 포함하지 않았다. 이후 이번 5종 추가 승인을 받아 현재 80종 가격에 포함했다. 설치 모델 참조인 B850M Pro RS·TUF B860-PLUS WIFI의 관측값은 판매 상품으로 승격하지 않고 보류했다. [보드 6종 조사와 보류 근거](../data/catalog-review/all-catalog-board6-price-candidates-2026-10-10.json).

## 승인한 5종 가격 추가

미확인 232종을 모두 검토해 정확한 판매 구성과 현재 일반 상품가가 확인된 5종을 승인받아 추가했다. 기존 75종의 금액·출처·관측시각과 307종의 식별 정보를 보존한 **현재 중앙 운영은 가격 80종·미확인 227종**이다.

| 제품 | 상품가 |
| --- | ---: |
| AMD Ryzen 7 3800X (100-100000025BOX) | 560,760원 |
| AMD Ryzen 7 5800X3D (100-100000651POF) | 582,330원 |
| AMD Ryzen 9 7950X (100-100000514WOF) | 1,840,490원 |
| MSI GeForce RTX 3060 VENTUS 2X 8G OC | 472,590원 |
| MSI MPG B650 CARBON WIFI | 915,000원 |

배송비·쿠폰·프로모션·현금 전용 가격을 제외한 국내 신품 일반 상품가다. CPU 3종과 보드 1종은 높은 판매가 후보라는 검토 표시를 유지했으며, 이 금액을 통상 시세나 판매처의 실제 창고 재고로 확정하지 않는다. 나머지 227종은 정확 SKU·리비전·판매 구성 등의 근거가 부족한 146종과 조사 페이지에서 현재 일반 상품가를 확보하지 못한 81종이다. 검색 후보 없음이나 빈 가격 페이지를 전체 시장의 구매 불가로 판정하지 않는다.

- [232종 전체 판정과 5종 가격 근거](../data/catalog-review/remaining232-price-review-2026-10-10.json)
- [80종 가격 미리보기 검증](../data/catalog-review/remaining232-price-preview-verification-2026-10-10.json)
- [사용자 승인·운영 반영·홈페이지 API 검증](../data/catalog-review/remaining232-price-publication-2026-10-10.json)

이번 변경은 승인된 가격 자료만 무료 Worker에 재배포했다. **DB 쓰기·제품/제원 변경·환경변수/팀 토큰 변경·backend 재시작은 모두 0건**이다. 이미 중앙 연결을 완료한 기기는 홈페이지에서 가격을 새로 조회하면 된다. 가격 자동 수집·자동 배포는 없다.

## v2와 이후 가격 갱신

`GET /api/v2/prices?canonicalIds=UUID,...`는 307종을 대상으로 한다. 팀 토큰은 기존과 같으며 구 backend를 위한 `/api/v1/prices`의 14종·8가격 자료도 유지한다.

- `catalogVersion`: 제품 식별 정보 SHA-256. 가격만 바뀌면 유지된다.
- `priceVersion`: 검토 관측 묶음 SHA-256. 새 관측을 반영하면 바뀐다.
- Java는 제품 식별·RAM 단위·금액·출처 URL·관측시각과 48시간 경고·7일 만료를 검사한다. 이후 승인 가격을 Worker에 반영하면 팀원 backend를 가격마다 수정할 필요가 없다.
- 요청을 50종씩 나누며 실패한 묶음만 연결 장애로 표시한다. 응답 제한은 64KiB다.
- PC와 서버 사이 최대 5초 시각 차이를 허용하며, 신선도는 검증한 서버 시각과 수신 후 경과 시간으로 계산한다. 관측시각이 서버 시각보다 미래인 가격은 거절한다.
- 정기 수집과 자동 배포는 아직 없다. 화면 새로고침은 중앙의 마지막 등록 관측을 읽는다.

## 적용 순서와 실행 설정

JDK 21, 프로젝트 루트에서 다음 작업은 DB 없이 검증한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend verifyAllSharedCatalog importAllSharedIdentities
.\backend\gradlew.bat --project-dir .\backend exportSharedCatalog verifyWorkerCatalog
```

최초 전체 전환에서 **Worker v1·v2 재배포 → 로컬 공통 ID 연결 → 새 backend 재시작 → 홈페이지 프록시의 307종 조회**를 완료했다. 307종 모두 `SHARED`, 가격 75종 `OK`·`FRESH`, 미확인 232종 `NO_PRICE`이며 `LOCAL`·`UNAVAILABLE`은 0종이다. 홈페이지에서 새로고침하면 적용 결과를 읽는다. 브라우저 화면을 직접 조작하는 검수는 별도다.

다른 기기의 기존 307종 DB에서 공통 ID를 한 번 연결하는 명시적 명령은 아래와 같다. 실제 적용은 해당 DB에 대한 승인 범위에서 실행한다. 개인 MySQL 설정·`DB_PASSWORD`가 필요하다. 웹 서버·Flyway·seed·가격 적재는 실행하지 않는다. 전체를 검사한 뒤 한 트랜잭션으로 연결하며 기존 자료가 다르면 중단한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importAllSharedIdentities -PcheckSharedIdentitiesDb=true
.\backend\gradlew.bat --project-dir .\backend importAllSharedIdentities -PapplySharedIdentities=true
```

팀원·다른 기기에도 같은 DB 준비 상태와 일회성 연결이 필요하다. GitHub pull·일반 bootRun·build/test가 DB 자료를 자동 변경하지 않는다.

**IntelliJ 환경변수는 기존 세 개 그대로다.** `CATALOG_SHARED_PRICES_ENABLED=true`, `CATALOG_SHARED_PRICES_BASE_URL=https://pc-upgrade-shared-prices.joony1024.workers.dev`, 기존 `CATALOG_SHARED_PRICES_API_TOKEN`을 사용한다. DB/OAuth 설정은 보존했다. 최초 전체 전환 때 이 기기는 기존 실행 구성의 값으로 재시작했다. 이번 5종 가격 추가에는 설정 변경이나 재시작이 필요하지 않았다.

옵션 없는 파일 내보내기의 기본 자료는 **`data/catalog-shared/active-approved-prices.json`의 승인 80종**이다. 누락되거나 잘못된 파일이면 중단하며 과거 74종으로 되돌리지 않는다. 최초 74종 자료는 생성 근거와 이력을 검증하기 위해 보존한다.

다음 가격 묶음을 검토할 때 다른 파일을 명시하는 옵션은 아래와 같다. 기존 `CatalogPriceImportBatch` 형식이며 정확한 source identity·판매 구성·상품번호·근거 URL을 검사한다. 파일 옵션은 backend 환경변수가 아니다.

```powershell
.\backend\gradlew.bat --project-dir .\backend exportSharedCatalog '-PreviewedSharedPricesFile=../data/catalog-review/승인한-가격-묶음.json'
.\backend\gradlew.bat --project-dir .\backend verifyWorkerCatalog '-PreviewedSharedPricesFile=../data/catalog-review/승인한-가격-묶음.json'
```

위 명령은 공개 산출물만 생성·검증한다. 이후 승인된 자료로 기본 가격 파일을 갱신하고 Worker에 배포한다. 로컬 DB 가격 적재는 별도이며 중앙 가격 갱신에는 필요하지 않다.

## 최초 전체 전환의 검증 이력

전체 backend H2 테스트 **388개 통과** 후 시각 오차·실제 DB 근거 시각 읽기를 보완하고 관련 테스트 **42개를 다시 실행해 모두 통과**했다. DB 비연결 자료·307종 식별·74종 가격 원본 대조·293개 연결 재실행 안전성·기존 데이터 보존·가격 버전 분리·새 가격 수용·잘못된 응답 거절·일부 장애 격리를 검증했다.

승인 가격 75종을 내보낸 자료로 Worker 테스트 **524개**, TypeScript 검사·번들 생성·실제 Wrangler 로컬 실행을 통과했다. 실제 Java 클라이언트와 로컬 Worker 연결에서도 **v1 14종·8가격 / v2 307종·75가격·미확인 232종**을 확인했다. 로컬 검증은 합성 토큰을 사용했다. 기본 승인 파일을 사용하는 경로와 파일 누락·오류 거절을 추가 검증하고 해당 테스트 **12개**·기본 내보내기·Worker 자료 대조를 통과했다.

실제 MySQL 8.4.11의 **읽기 전용 307종 대조 후 293개 공통 ID를 한 트랜잭션으로 연결**했다. 기존 seed와 pilot의 서로 다른 근거 시각 저장 경로를 정확하게 읽으며, 기존 DB 시각을 수정하거나 9시간 오차를 허용하지 않는다. 변경 전후 **29개 테이블의 보존 검증을 통과**했다. 기존 상품 공통 필드·제원·가격·PC 연결·pilot 14종은 보존했고 가격 쓰기·제품 생성·모델 생성은 0건이다. 백엔드 재시작 시 Flyway 14개 마이그레이션을 검증했으며 새 마이그레이션 적용은 없었다.

최초 전체 전환의 무료 Worker 배포는 **2026-10-10 18:25:45 KST**, 버전 `71504364-1cef-40ec-9bff-96a584991895`, 배포 `2065fb60-1bf7-408b-b310-22b01dd0ea1b`다. 운영 HTTPS의 실제 Java 조회에서 307/75/232와 무인증 401·최대 요청 100개·캐시 금지·nosniff를 확인했다. 홈페이지의 `/api` 프록시를 통한 전체 조회·기존 ID와 공통 필드 보존·Dell 상세 가격을 확인했다. 추가 유료 서비스와 정기 수집은 없다.

- [실제 승인·배포·DB 보존·홈페이지 API 검증 결과](../data/catalog-review/all-catalog-central-rollout-2026-10-10.json)
- [실제 적용 전 검증 이력](../data/catalog-review/all-catalog-central-verification-2026-10-10.json)

## 5종 추가 후 운영 검증

2026-10-10 **19:38:44 KST** (`2026-10-10T10:38:44.649971Z`)에 기존 무료 Worker를 재배포했다. Cloudflare version은 `a12e2ae2-4431-4024-bd9b-7f72e11f1563`, deployment는 `63016ff8-d994-4152-8034-4b3c4d914385`이며 트래픽 100%다.

Java 자료 생성·원본 대조(v1 참조 응답 53개·v2 491개), Worker 테스트 **554개**, TypeScript 검사와 번들 생성을 통과했다. 운영 HTTPS 4개 묶음 조회에서 **307종·가격 80종·미확인 227종·FRESH 80종**, v1 호환 14종·8가격과 무인증 401을 확인했다. 홈페이지 프록시 7페이지와 추가 5종 상세 조회에서도 `SHARED=307`, `OK=80`, `NO_PRICE=227`, `FRESH=80`, `LOCAL=0`, `UNAVAILABLE=0`과 합계 포함 80종을 확인했다.

제품 식별 `catalogVersion`은 그대로이며 가격 버전만 `prices-v1-7262b6764824fb79d2c8ea952808e814637558c30749366f923b3d4c791167bc`로 갱신했다. 이번 가격 변경에서 backend 전체 테스트 재실행과 브라우저 UI 직접 검수는 하지 않았다. [최종 반영 보고서](../data/catalog-review/remaining232-price-publication-2026-10-10.json).

시각 처리의 공식 근거는 [MySQL 날짜 함수](https://dev.mysql.com/doc/refman/8.4/en/date-and-time-functions.html)와 [Connector/J 시각 처리 설정](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html)이다.

## 판매 구성 146종 후속 검토와 4종 미리보기

판매 구성 확인이 필요했던 146종을 모두 후속 조사했다. 기존 ID에 바로 넣을 수 있는 새 가격은 0종이며, 138종은 판매 구성·국내 품번·판매 상태 확인이 더 필요하고 8종은 조사한 경로에 현재 유효한 상품가가 없다. 조사 경로 밖의 국내 전체 재고가 없다는 뜻은 아니다. [146종 검토 결과](../data/catalog-review/held146-price-review-2026-10-10.json).

사용자가 승인한 기능 보완과 첫 4종 미리보기는 아래와 같다. 모두 배송·쿠폰·카드 할인·프로모션을 제외한 상품가 후보다. **구현 승인은 실제 상품 등록·가격 반영 승인이 아니다.**

| 후보 | 관측 상품가 | 적재 미리보기 | 남은 확인 |
| --- | ---: | --- | --- |
| Core Ultra 5 245K | 330,500원 | 보류 | 국내 정품 BOX의 실제 출고 품번 |
| Core Ultra 5 250K Plus | 342,100원 | 보류 | 국내 정품 BOX의 실제 출고 품번 |
| MSI MAG B860M MORTAR WIFI | 260,900원 | 보류 | 검토한 판매 구성의 국내 유통사 |
| ASUS TUF GAMING B860-PLUS WIFI 인텍앤컴퍼니 | 275,350원 | 별도 판매상품 1개·가격 1건 후보 | 실제 DB·중앙 배포 승인 |

ASUS는 기존 설치 모델을 보존하고 그 모델에 연결할 `PHYSICAL_VARIANT`·`PURCHASE_CANDIDATE` 상품을 따로 준비한다. 제조사 PN은 `null`로 유지하고 모델·유통사·단품 구성·DDR5·LGA1851·ATX·WiFi와 근거 URL·검토 시각을 기록한다. 명시되지 않은 PCB 리비전의 동등성은 주장하지 않는다. 기존 설치 모델의 가격이나 PC 합계에 이 판매상품 가격을 자동 대입하지 않는다. 가격 경로의 검토와 제품 전체 제원·BIOS 호환 검증/활성화는 별개이며 새 상품은 미검증·비활성으로 준비한다.

`CatalogPriceImportService`는 위 예외를 PN이 공표되지 않은 별도 메인보드 판매상품에만 허용한다. DB에 등록된 공통 ID·분류·역할·제원과 근거의 구성 전체를 대조한다. CPU/RAM/GPU, 모델참조와 기존의 알려진 PN을 숨기는 입력은 계속 거절한다. 컴퓨존·아이코다 공개 상세 페이지 수집 경로는 추가했으며 CPU 두 후보는 `PENDING`이어서 가격 입력 행을 만들지 않는다.

프로젝트 루트에서 실행하는 미리보기는 DB·개인 properties·OAuth 키 없이 사용할 수 있다. 기존 IntelliJ 환경변수와 backend 실행 구성은 바꿀 필요가 없다. JDK 21은 기존처럼 필요하다.

```powershell
.\backend\gradlew.bat --project-dir .\backend previewRetailCatalog
```

`candidates=4 ready=1 held=3 productionProducts=307 productionPrices=80 databaseAccess=0 publications=0`을 확인한다. 산출물은 Git에서 제외된 `backend/build/catalog-retail-preview/`에만 쓴다. 신규 공통 ID를 포함한 308종·81가격과 Java HTTP 참조 응답을 **로컬 미리보기**로 내보내며, 운영 Worker의 생성 자료와 기본 307/80 입력은 변경하지 않는다. `--apply`와 `-Papply*=true`는 지원하지 않는다.

이후 Java가 내보낸 응답과 로컬 Worker 미리보기를 대조하려면 아래 명령을 사용한다. 팀 토큰 대신 합성 토큰을 사용하며 Cloudflare와 실제 DB에는 연결하지 않는다.

```powershell
npm.cmd --prefix workers/catalog-prices run test:retail-preview
```

CPU 후보를 재관측할 때도 결과는 파일만 만든다. 매핑 미완료 후보가 있으면 종료 코드 2와 보고서의 `candidateCount=2`, 입력 행 0건이 정상이다. 이것을 적재 성공으로 기록하지 않는다.

```powershell
node backend/tools/collect-retail-prices.mjs --manifest data/catalog-review/retail-cpu-pending-manifest-2026-10-10.json --output backend/build/catalog-retail-preview/cpu-current.json
```

근거는 [4종 제안 입력](../data/catalog-review/retail-four-preview-2026-10-10.json)과 [공개 페이지 관측 기록](../data/catalog-review/retail-four-public-evidence-2026-10-10.json)에 있다. PN 미확인 CPU와 MSI 유통사는 확정하지 않았다. 이미지 속 품번 확인을 위한 Computer Use는 Windows에서 현재 URL을 검증하지 못해 중단됐으며, 공개 HTML·제조사 문서로 확인한 범위만 기록했다.

가격·중앙 조회·미리보기 관련 backend H2 테스트 **94개**, Node 수집기 테스트 **43개**, Worker 테스트 **566개**, 타입 검사·로컬 번들이 통과했다. 실제 Java 미리보기 HTTP 응답 **497개**도 로컬 Worker와 전부 일치했다. backend 전체 실행은 기존 300종 적재/H2 JSON 처리 구간에서 5분 49초 후 중단하고 관련 검증으로 전환했으므로 전체 테스트 통과를 주장하지 않는다. 실제 MySQL 쓰기·운영 배포·backend 재시작·Git 업로드는 0건이다. [미리보기 최종 검증 기록](../data/catalog-review/retail-four-preview-verification-2026-10-10.json).

이전 RAM 전압 검토의 오류도 정정했다. `CMK32GX5M2E6000C36`의 실제 DB와 현재 제조사 사양은 모두 **1.35V**다. 원본 수집 자료의 1.4V를 DB 값으로 잘못 분류한 기록이며, DB 제원 수정은 필요 없다. [실제 조회와 정정 근거](../data/catalog-review/held146-ram-voltage-correction-2026-10-10.json).

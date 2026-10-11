# 첫 14종 상세 검토와 승인된 수동 적재

14종의 공식 제원·CPU 지원·슬롯·판매 단위·상품가 검토와 수동 적재 구현을 완료했다. 사용자 승인 후 실제 MySQL의 읽기 전용 사전검사를 통과했고, **2026-10-10 02:35:32 KST에 카탈로그 보강과 8종 가격의 로컬 MySQL 반영을 완료했다.** 전체 H2 345개 테스트, 기존 행의 백업 대조, 동일 자료의 DB 재검사도 모두 통과했다. 선정 기준은 플랫폼과 기능 범위이며 국내 인기·가성비 순위를 입증한 목록은 아니다.

## 승인된 카탈로그 반영 범위

| 대상 | 반영 범위 수량 | 내용 |
| --- | ---: | --- |
| 기존 상품 재사용 | 7 | 정확한 BUILDCORES externalId로 연결, 기존 로컬 UUID·제원 보존 |
| 신규 상품 | 7 | Intel CPU 2·보드 3은 모델 참조, Samsung SSD 2는 정확 BW 판매 변형 |
| 모델 | 13 | CPU 4·보드 4·GPU 칩 2·SSD 2·공통 RAM 규격 그룹 1 |
| 모델 근거 | 14 | 제조사 제원 발췌, 정확 카드/키트와 상위 모델의 근거 범위를 구분 |
| 상품의 공통 ID·모델 연결 | 14 | 정확 출처로 로컬 ID를 해결하고 결정적 공통 ID·기존 연결의 충돌 검사 |
| 보드 슬롯 프로필 / 슬롯 | 4 / 19 | MODEL 범위, 리비전 미확인·부분 자료로 저장 |
| CPU 메모리 지원 프로필 | 4 | 기존 AMD 2개 보존·신규 Intel 2개 추가; 일반 UDIMM·CUDIMM 및 장착 수 조건 구분 |
| CPU 지원 근거 | 8쌍 | 기존 B650 2쌍 보존·신규 보드 6쌍 추가. MSI B860 미확인 2쌍도 미확인 상태로 기록 |

공통 ID는 각 DB의 로컬 UUID를 대신하지 않는다. 구현한 적재 서비스는 정확한 출처로 기존 행을 찾고 제원·공통 ID·모델 연결·제조사 근거·지원 자료의 충돌을 검사한다. 기존 300종의 UUID와 제원, 가격 이력·장기 기준가격, PC 저장 행을 보존한다. 모델과 상품의 활성화·검증 상태를 자동 승격하지 않으며 신규 생성 시 기본 미검증·비활성 상태를 유지한다. GitHub 게시와 구매 후보 활성화는 이번 반영 승인에 포함되지 않는다.

같은 승인 자료를 다시 실행하면 이미 동일한 상품·모델·출처·연결·슬롯·가격 관측을 재사용한다. 같은 식별자의 내용이 다르면 덮어쓰지 않고 거절한다. 실제 적용은 한 트랜잭션이며, 중간 충돌이나 가격 실패가 생기면 카탈로그·근거·가격 변경 전체를 롤백한다. 사전검사 통과 후에도 적용 트랜잭션에서 DB 상태를 다시 확인한다.

[적재 미리보기](../data/catalog-review/pilot-import-preview-2026-10-10.json)는 **상세 검토 당시의 승인 전 단계**를 보존한 원자료다. 그 파일의 승인 플래그를 바꾸지 않고, [별도 승인 기록](../data/catalog-review/pilot-apply-approval-2026-10-10.json)이 미리보기와 [승인 가격 8종](../data/catalog-review/pilot-approved-prices-2026-10-10.json)의 정확한 정규화 해시·반영 범위를 고정한다. 파일을 변경하면 승인 해시 검사에서 거절하므로 새로운 자료는 다시 검토한다.

두 RAM은 **DDR5 DIMM 16GB non-ECC unbuffered 공통 규격 그룹**에만 연결한다. 서로 다른 키트 PN·타이밍·전압·가격은 각 상품에 보존하며, 규격 그룹을 정확 모듈 PN으로 해석하지 않는다. 장착 수량은 2개, 장치 하나의 용량은 17,179,869,184 bytes, 판매 가격은 키트 1세트다. 키트 PN·QVL 모델 게재만으로 개별 모듈·rank·설치 CPU·BIOS·시험 DIMM 수를 확정하지 않는다.

## 확인한 지원 범위와 보류

| 조합 / 상품 | 확인 결과 | 사용 제한 |
| --- | --- | --- |
| B650M MORTAR + 9600X·9700X | 기존 공식 지원표의 B0 / 최소 BIOS `7D76vAE` 근거 재사용 | 최신 표를 새로 확인한 결과는 아님. 설치 BIOS 미확인 |
| B850M Pro RS + 9600X·9700X | 현행 제조사 표의 최소 BIOS `3.12` | 설치 BIOS·실물 리비전 미확인 |
| ASUS TUF B860-PLUS + 245K·250K Plus | 현행 제조사 표에 `ALL/all` | 숫자 BIOS 버전으로 변환하거나 전체 PC 호환으로 확장하지 않음 |
| MSI B860M MORTAR + 두 Intel CPU | CPU별 지원표 접근 실패 | `UNVERIFIED`, 최소 BIOS `null`; 일반 BIOS 릴리스 문구로 추정하지 않음 |
| Intel 245K | 글로벌 BOX `BX80768245K`가 공식 주문정보의 retired/discontinued 구역에 있음 | 설치 PC 참조로 저장. 국내 판매 SKU·구매 활성화 보류; 다른 BOX/TRAY와 구분 |
| Ripjaws S5 32GB 키트 | 공식 제원·키트 QVL은 확인 | 국내 정확 키트 가격 매핑 보류. 검색된 다나와 16GB 단품을 연결하지 않음 |
| 보드 4종 | 모델별 제원·부분 슬롯 자료 확인 | 실물 리비전이 없어 모두 모델 참조. SSD 호환 자동 판정 완료로 취급하지 않음 |
| 870 EVO / 990 PRO 1TB | 국내 공식 BW PN 및 동일 PN 판매 페이지 확인 | 990의 connectorKey, 870의 ATA 프로토콜·방열판 여부는 근거 부족으로 `null` |

ASRock 최소 BIOS는 [9600X 지원표](https://www.asrock.com/support/cpu.asp?s=AM5&u=767)·[9700X 지원표](https://www.asrock.com/support/cpu.asp?s=AM5&u=766), ASUS의 라벨은 [정확 보드 CPU 지원표](https://www.asus.com/in/motherboards-components/motherboards/tuf-gaming/tuf-gaming-b860-plus-wifi/helpdesk_qvl_cpu/?model2Name=TUF-GAMING-B860-PLUS-WIFI), Intel 박스 수명주기는 [공식 주문정보](https://www.intel.com/content/www/us/en/products/sku/241067/intel-core-ultra-5-processor-245k-24m-cache-up-to-5-20-ghz/ordering.html)를 근거로 한다. BIOS 문자열은 자동 대소 비교하지 않는다.

## 가격 검토

한국 시간 2026-10-10에 공개 페이지를 직접 읽었다. UTC 원문 시각은 2026-10-09이며 관측 파일에 그대로 보존했다. 배송료·카드·멤버십·쿠폰·적립·추가 상품 가격은 제외했다. **아래 8종 관측 자료의 로컬 DB 반영은 승인됐으며**, 적용 직전 동일한 8개 URL을 한 번씩 다시 읽어 승인 금액과 공개 판매 조건을 대조했다.

| 상품 | 관측 상품가 | 가격 근거 / 적용 전 조건 |
| --- | ---: | --- |
| Ryzen 5 9600X | 399,000원 | 기존 검토 다나와 매핑 재관측 |
| Ryzen 7 9700X | 686,000원 | 기존 검토 다나와 매핑 재관측 |
| MSI B650M MORTAR WIFI | 202,710원 | 기존 검토 다나와 매핑 재관측; 리비전 확인과 별개 |
| G.SKILL Neo 32GB 2×16GB 키트 | 939,000원 | 정확 키트의 일반 상품가를 재확인. 높은 금액이나 오류로 단정할 근거 없음; 구매 추천의 가격 적정성은 미검증 |
| MSI RTX 5060 VENTUS 2X OC | 668,860원 | 기존 정확 카드 다나와 매핑 재관측 |
| SAPPHIRE RX 9060 XT PULSE OC 16GB | 822,300원 | 기존 정확 카드 다나와 매핑 재관측 |
| Samsung 870 EVO 1TB BW | 459,000원 | 삼성씨앤에이치 정확 PN 단품의 공개 판매가. MANUFACTURER 상품 출처로 반영 |
| Samsung 990 PRO 1TB BW | 399,000원 | 같은 판매사의 정확 PN 단품 공개 판매가. MANUFACTURER 상품 출처로 반영 |

기존 6종은 [다나와 재관측 파일](../data/catalog-review/pilot-existing-prices-2026-10-10.json)에 정확 상품/offer/시각이 있다. SSD 2종은 [870 판매 페이지](https://www.samsungzip.shop/goods/goods_view.php?goodsNo=1000000066)·[990 판매 페이지](https://www.samsungzip.shop/goods/goods_view.php?goodsNo=1000000113)의 명시 판매가·폼 가격·단품 옵션·구매 활성 여부를 대조했다. 단일 판매점 관측이며 국내 최저가를 뜻하지 않는다. 주문 활성은 실제 창고 재고·최종 결제·배송을 확인한 결과가 아니다.

Intel 2·신규 보드 3은 모델 범위의 일반 상품가도 수집했지만 **정확 박스 PN/리비전의 가격으로 적재하지 않고 보류 자료**에만 남겼다. Ripjaws S5 키트는 국내 정확 매핑이 없어 가격을 생성하지 않았다. 따라서 14종 모두 가격 적용이 준비됐다고 보지 않는다.

기존 6종의 재관측은 가격 이력을 추가하며 가격이 있는 제품 수를 늘리지 않는다. 이번 DB 반영으로 SSD 2종의 가격 매핑과 총 8개 관측을 추가했고, 가격이 있는 제품은 기존 72종에서 **74종**, 관측 이력은 **80개**가 됐다. 백업 대조에서 기존 72개 가격 매핑과 72개 관측은 모든 값이 보존됐다. `CatalogPriceImportBatch`는 BUILDCORES와 MANUFACTURER의 정확 출처 식별을 지원하도록 구현했으며, 상품명만으로 매칭하지 않는다. 기존 `importCatalogPrices`의 72종 기본 스냅샷과 `setupDevCatalog` 준비 경로는 그대로 유지한다.

[적용 직전 재확인](../data/catalog-review/pilot-price-preapply-recheck-2026-10-10.json)에서 8종 모두 승인 금액과 일치했다. SSD는 정확 BW PN·표시/폼 상품가·구매 버튼·추가상품 0·할인/쿠폰 미적용을 확인했고, 일부 JavaScript 초기화 필드는 미추출로 남겼다. 실물 재고 수량을 검증한 결과로 확장하지 않는다. 재확인의 새 시각으로 승인 파일의 원 관측시각을 교체하지 않았다.

수동 적재는 **승인된 관측 스냅샷의 재현**이며 실행할 때 웹 가격을 다시 수집하지 않는다. 오래된 관측을 현재 가격으로 바꾸거나 원 시각을 갱신하지 않는다. 다른 PC도 같은 상품·offer·금액·관측시각을 재현할 수 있다. 새로운 현재가가 필요하면 별도로 정확 판매 구성을 재확인·수집하고 반영 자료를 검토한다. `pull`, 화면 새로고침, 일반 `bootRun`은 카탈로그나 가격을 적재·갱신하지 않는다.

## 수동 실행

Node.js 24로 생성 자료와 정규화 원자료 해시를 확인한다. DB·비밀번호·OAuth 설정은 필요 없다.

```powershell
node data/catalog-review/build-pilot-selection-2026-10-10.mjs --check
node data/catalog-review/build-pilot-preview-2026-10-10.mjs --check
```

JDK 21에서 현재 서버 DTO 규칙으로 제원·모델·슬롯·가격 및 공통 ID를 검증하는 DB 없는 작업은 다음과 같다.

```powershell
.\backend\gradlew.bat --project-dir .\backend previewPilotCatalog
```

`previewPilotCatalog`는 기존 상세 검토 전용 작업이다. Spring·Flyway·웹 서버·JDBC를 시작하지 않고 적용 인자를 거부한다. 승인된 수동 적재는 별도 `importPilotCatalog` 작업을 사용한다.

프로젝트 루트의 PowerShell에서 실행한다. **기본 명령은 DB 없는 미리보기**이며 JDK 21 외에 개인 DB 설정·DB_PASSWORD·OAuth 키가 필요 없다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importPilotCatalog
```

실제 DB를 읽는 사전검사는 다음과 같다. 자료·승인 해시를 먼저 검사한 뒤 DB에서 정확 출처·로컬 ID·기존 제원·충돌과 신규 반영 수량을 확인하며 쓰지 않는다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importPilotCatalog -PcheckPilotDb=true
```

승인된 카탈로그와 8종 가격을 한 트랜잭션으로 반영하는 명령은 다음과 같다. `checkPilotDb`와 `applyPilotCatalog`를 함께 지정하지 않는다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importPilotCatalog -PapplyPilotCatalog=true
```

DB 사전검사·실제 반영에는 **MySQL 8.4, V14까지 적용된 스키마, 기존 300종 카탈로그, 개인 `backend/src/main/resources/application-local.properties`, 실행 환경의 `DB_PASSWORD`**가 필요하다. `.env`는 자동으로 읽히지 않는다. 카탈로그가 비었거나 미완성이면 [기존 준비 절차](catalog-current-prices.md)를 먼저 따른다. V14 이전 DB는 승인된 스키마 변경 범위에서 일반 `bootRun`을 재시작해 마이그레이션을 준비한 뒤 수동 적재한다.

수동 DB 작업은 `local` 프로필을 사용하며 **OAuth 키·웹 서버·Flyway·SQL 초기화·다른 seed runner를 실행하지 않는다.** 기존 개인 설정을 삭제하거나 교체할 필요가 없다. 일반 backend의 JDK 21·`local`·DB_PASSWORD·OAuth 실행 구성에도 이번 변경용 새 환경변수나 구성 변경은 필요 없다.

## 실제 적용 감사와 검증 기록

DB 반영·H2·백업 대조와 적용 후 읽기 전용 재검사를 완료했다. [실제 적용 감사 자료](../data/catalog-review/pilot-applied-2026-10-10.json)에 최종 행 수, 승인된 변경 범위, 테이블별 보존 결과가 있다. 사전검사 수량과 실제 MySQL 커밋 결과를 구분한다.

| 검사 | 현재 기록 |
| --- | --- |
| 상세 검토 시 DB 없는 검사 | Node 선정/상세 `--check`, 기존 78종 검토, `previewPilotCatalog` 및 당시 typed preview 단위 검사 통과 |
| 적용 직전 가격 재확인 | 8개 URL 각각 GET 1회, 승인 금액 8개 일치; 원 승인 파일·관측시각 보존 |
| 실제 MySQL 읽기 전용 사전검사 | 통과. 최초 추가 예상: 상품 7·모델 13·상품 근거 14·연결 14·슬롯 프로필 4/슬롯 19·CPU 메모리 2·CPU 지원 6·가격 매핑 2/관측 8 |
| 전체 H2 회귀 | 72개 suite·345개 테스트 통과. 실패·오류·skip 0 |
| 실제 MySQL 커밋 | 2026-10-10 02:35:32 KST `COMMITTED`. 신규 상품 7·모델 13·상품 근거 14·연결 14·슬롯 프로필 4/슬롯 19·CPU 메모리 2·CPU 지원 6·가격 매핑 2/관측 8 |
| 기존 행 백업 대조 | PASS. 기존 4,056행 중 4,049행의 모든 값 동일. 기존 상품 7행은 승인된 식별 관련 8컬럼만 변경. 누락·기타 변경 0, 신규 행 161 |
| 최종 행 수 | 상품 307·모델 13·상품 출처 853·가격 매핑 74·관측 80·가격 있는 제품 74 |
| 동일 자료 재검사·중복 방지 확인 | 적용 후 `-PcheckPilotDb=true` PASS. 추가·변경 대상 전부 0 |

기존 상품 7행에서 바뀐 컬럼은 `canonical_id`, `identity_evidence_source_id`, `identity_kind`, `identity_review_scope`, `identity_reviewed_at`, `model_id`, `role`, `updated_at`이다. 기존 상품 UUID·제원·장기 기준가격, 기존 가격 매핑·관측, CPU 지원·메모리 자료, PC·PC 부품·회원·소셜 계정, Flyway 이력은 보존됐다. 저장된 PC 부품에 모델 연결을 자동 추가하지 않았다. 모델·상품의 미검증 상태와 구매 활성화 제한도 유지했다.

H2 검증은 실제 MySQL 보존 감사·OAuth 로그인·프론트·Windows 수집기·실제 조립 통합 검증을 대신하지 않는다. 미확인 SKU·보드 리비전·RAM rank/QVL·MSI B860 CPU 지원과 구매 후보 활성화는 위 보류 상태를 유지한다. 후속으로 보류 근거를 해소하거나 다른 상품·새 가격을 추가할 때 해당 범위를 따로 검토한다.

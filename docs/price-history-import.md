# 가격 판단(가격 이력·분석) 개발 안내

부품 상세 화면의 "가격 판단"과 관련 API·Gradle 작업을 설명한다. 이 기능은 **등록된 판매가 관측만으로 현재 가격 수준을 비교**한다. 미래 가격을 수치로 예측하지 않으며, 뉴스·이슈 분석은 아직 범위에 넣지 않았다.

## 기존 데이터와의 관계

- 기존 테이블은 **읽기만** 한다. 대상은 `catalog_product`, `catalog_product_source`, spec 테이블, `catalog_price_mapping`, `catalog_price_observation`, `catalog_reference_price`다. 이 테이블들의 구조와 행은 바꾸지 않는다.
- 새 데이터는 V16이 만든 별도 테이블 두 개에 저장한다. 두 테이블 모두 `product_id`로 `catalog_product(id)`를 참조한다.
  - `price_history_observation`: 팀이 검토한 관측 파일에서 가져온 가격 이력
  - `price_analysis_result`: 명시적으로 실행한 분석 작업의 판정 스냅샷
- 외래 키에는 `ON DELETE CASCADE`를 둔다. 기존 상품 정리 작업을 새 테이블이 막지 않게 하기 위해서다.
- 기존 테이블 접근은 조회 전용 `ExistingPriceObservationQuery`로만 한다. 이 저장소는 `Repository`를 상속하므로 `save`나 `delete` 메서드가 없다.
- `PriceTrendIntegrationTests`가 H2에서 이 동작을 확인한다. 이력 가져오기·분석 저장·API 조회 전후에 기존 7개 테이블의 전체 행이 같은지 비교한다.
- `currentPrice`, `priceStatus`, `referenceEstimate`, `CatalogProductView`는 변경하지 않았다. 참고가와 추정가는 시계열에 넣지 않는다.

## 판정 규칙 (`trend-v2`)

판정 규칙은 `PriceTrendAnalyzer`에 있다. 임계값은 팀 결정 사항이며 이 클래스의 상수에서만 바꾼다. 자료가 오래됐거나 적어도 판정은 숨기지 않는다. 대신 판정 옆에 **별도 표시**를 붙인다.

| 항목 | 기준 |
| --- | --- |
| 비교 단위 | 판매처·판매처 상품 ID·판매 URL·SKU·판매 단위(RAM은 모듈 수까지)가 같은 관측만 한 시계열로 본다. 가장 최근에 관측된 판매 구성을 판정한다. |
| 비교 기간 | **마지막 관측 시각부터 90일 전까지.** 오래된 자료도 그 시점의 판매가끼리 비교한다. 하루에 관측이 여러 건이면 그날의 마지막 금액만 쓰고, 빠진 날은 채우지 않는다. |
| 자료 부족 | 관측이 없거나, 고유 관측일(UTC)이 1일뿐이라 비교할 대상이 없는 경우에만 판정하지 않는다. |
| 좋은 가격 | 최신가가 중앙값의 97% 이하이거나, 중앙값보다 낮으면서 기간 최저가의 102% 이하 |
| 대기 권장 | 최신가가 중앙값의 105% 이상 |
| 평균 수준 | 그 밖의 경우. 가격 변동이 없는 시계열도 여기에 해당한다. |
| 표시: 최신화되지 않음 (`stale`) | 마지막 관측이 7일 이상 지났다. 판정은 그 시점 가격 기준이며, 지금 판매가는 다를 수 있다. |
| 표시: 관측일 부족 (`lowConfidence`) | 판정은 냈지만 고유 관측일이 14일 미만이라 참고용이다. |

2026-10-11까지의 승인 관측은 상품별로 1~3일치다. 그래서 현재는 관측일이 2~3일인 상품에 판정과 두 표시가 함께 붙는다. 대부분 가격 변동이 없어 "평균 수준"으로 나온다. 관측일이 1일인 상품은 "자료 부족"이다. `trend-v1`에서는 오래되거나 적은 자료를 모두 "자료 부족"으로 숨겼지만, v2부터는 판정과 표시를 나눠 보여 준다. 저장된 분석 결과의 `method_version`으로 둘을 구분한다.

## API (local 프로필, GET, 로그인 불필요)

| 요청 | 내용 |
| --- | --- |
| `GET /api/catalog/products/{id}/price-history` | 판매 구성별 관측 목록. 각 관측에 `amountKrw`, UTC `observedAt`, `evidenceUrl`, `origin`(`CATALOG`=기존 관측, `HISTORY`=새 이력)이 있다. |
| `GET /api/catalog/products/{id}/price-insight` | 요청 시점에 계산한 판정과 근거. `verdict`, `reason`, 표시 `stale`·`lowConfidence`, 비교 기간, 관측일 수, 기준값(`minComparableDays`, `minObservedDays`, `staleAfterDays`), 최신 관측, 중앙값·최저·최고, `changeRate7d`를 담는다. `basis`는 항상 `REGISTERED_OBSERVATIONS`다. |

- `{id}`는 로컬 상품 ID다. `canonicalId`를 넣지 않는다. 없는 상품이면 `404 CATALOG_PRODUCT_NOT_FOUND`를 반환한다.
- 화면 API는 `price_analysis_result`를 읽지 않는다. 판정은 요청할 때마다 새로 계산한다.

## Gradle 작업 (프로젝트 루트에서 실행)

```powershell
.\backend\gradlew.bat --project-dir .\backend importPriceHistory
.\backend\gradlew.bat --project-dir .\backend importPriceHistory -PcheckPriceHistoryDb=true
.\backend\gradlew.bat --project-dir .\backend importPriceHistory -PapplyPriceHistory=true
.\backend\gradlew.bat --project-dir .\backend analyzePriceTrends
.\backend\gradlew.bat --project-dir .\backend analyzePriceTrends -PapplyPriceAnalysis=true
```

| 실행 | DB 접근 | 쓰기 |
| --- | --- | --- |
| `importPriceHistory` | 없음 | 없음. 파일 형식과 고유 관측 수만 검사한다. 현재 승인 파일 5개는 241행이고, 중복을 빼면 159건이다. |
| `-PcheckPriceHistoryDb=true` | 읽기 전용 | 없음. 새로 넣을 건수와 이미 있는 관측, 건너뛸 행을 보고한다. |
| `-PapplyPriceHistory=true` | 읽기·쓰기 | `price_history_observation`에만 한 트랜잭션으로 추가한다. |
| `analyzePriceTrends` | 읽기 전용 | 없음. 판정 분포만 출력한다. |
| `-PapplyPriceAnalysis=true` | 읽기·쓰기 | `price_analysis_result`에 스냅샷 행을 추가한다. |

- DB 경로에는 개인 `application-local.properties`와 실행 환경의 `DB_PASSWORD`가 필요하다. 웹 서버·Flyway·seed·중앙 가격 연결은 실행하지 않고, OAuth 키도 필요 없다.
- V16이 없는 DB에서는 JPA 검증이 실패한다. 승인된 DB 변경 범위에서 일반 `bootRun`을 재시작해 V16을 먼저 적용한다.
- 실제 DB 쓰기(`apply`)와 `bootRun`에 의한 V16 적용은 명시적으로 승인받은 경우에만 수행한다.

### 가져오기 규칙

- 상품 연결은 기존 가격 가져오기와 같다. `product.sourceName + externalId`로 출처를 찾고 제조사·모델명·PN을 확인한다. RAM은 키트 모듈 수가 같은지도 확인한다.
- 카탈로그에 없는 상품은 만들지 않고 건너뛴다(`skippedUnknownProduct`). 상품을 활성화하지도 않는다.
- 다음 경우에도 연결하지 않고 건너뛴다(`skippedMismatch`).
  - 식별 정보나 판매 단위가 다른 행
  - 다른 상품에 이미 매핑된 판매처 상품
  - 기존 판매처 매핑과 판매 구성(SKU·URL·판매 단위·검토 시각)이 다른 행
- 기존 이력에만 등록된 판매처 상품도 다른 상품이나 판매 구성으로 바꾸지 않는다. 입력 파일끼리 또는 저장된 이력과 충돌하면 실행 전체를 거절한다.
- 같은 판매처 상품의 같은 `observedAt`에서 금액·근거가 모두 같은 경우에만 중복이다.
  - 기존 `catalog_price_observation` 또는 가격 이력에 같은 관측이 있으면 다시 넣지 않는다.
  - 같은 시각의 금액 또는 근거 URL이 다르면 실행 전체를 거절한다.
- `observedAt`은 원본 UTC 값을 그대로 저장한다. 품절·수집 실패·누락 날짜는 행을 만들지 않는다.
- 파워·케이스 승인 파일(`approved-power-case-extension-2026-10-11.json`)은 판매 구성 정보가 없는 다른 형식이다. 그래서 가져오기 대상에서 뺐다. 그 2건은 승인 적재된 DB의 `catalog_price_observation`에서 읽는다.

## 새 관측 파일 추가

1. 팀이 검토한 관측을 `data/price-history/observations-YYYY-MM-DD.json`로 추가한다.
   - 형식은 기존 승인 파일과 같다: `schemaVersion: 1`, `items[].product/offer/price`. 예시는 `data/catalog-current-prices/observations-2026-10-06.json`이다.
   - 금액은 배송비·할인을 뺀 국내 신품 상품가다. 재고가 있는(`inStock: true`) `NEW` 상품만 넣는다.
   - RAM은 `saleUnit: "RAM_KIT"`과 키트의 `moduleCount`를 적는다.
2. 기본 `importPriceHistory`로 파일 형식을 검사한다.
3. `-PcheckPriceHistoryDb=true`로 각자의 DB에서 반영될 건수를 확인한다.
4. 승인을 받은 뒤 `-PapplyPriceHistory=true`로 반영한다.
   - pull이나 서버 시작은 이력을 자동으로 적재하지 않는다. 각 PC의 DB에서 따로 실행해야 한다.

## 남은 결정·후속 작업

- 최소 관측일(14일)과 임계값(-3%, 최저가 +2%, +5%)은 팀이 확정해야 한다.
- 관측 파일을 누가, 어떤 주기로 검토해 추가할지 정해야 한다.
- 뉴스·이슈 근거와 업그레이드 플랜 화면의 판정 배지는 후속 이슈로 남긴다.

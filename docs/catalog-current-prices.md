# 국내 신품 상품가

현재 구매 판단에 사용할 값은 **국내 신품의 상품가**다. 배송료, 쿠폰, 카드 할인, 적립금, 프로모션 조건은 수집하거나 계산하지 않는다. 기존 `catalog_reference_price`의 장기 기준가격과 별도로 저장한다.

## 팀원이 pull한 뒤 가격이 없을 때

**Git은 각 PC의 MySQL 데이터를 공유하지 않는다.** 저장소에 있는 관측 파일과 다른 개발자의 DB에 저장한 관측 행은 별개다. 카탈로그 300종이 이미 보여도 `catalog_price_mapping`·`catalog_price_observation`에 가격을 가져오지 않았다면 `currentPrice`는 `null`이다. 화면의 가격 새로고침은 저장된 API 값을 다시 읽을 뿐, DB를 채우거나 판매처를 새로 수집하지 않는다.

2026-10-08 추가한 Gradle 작업 `importCatalogPrices`는 검토한 기존 22종과 추가 50종, **총 72종**을 한 번 실행하고 종료한다. 웹 서버를 띄우지 않으므로 일반 서버의 8080 포트와 겹치지 않는다. JDK 21, 실행 중인 MySQL, 개인 `application-local.properties`의 DB URL·계정과 실행 환경의 `DB_PASSWORD`를 준비한다. 가격 작업에는 Google/Kakao/Naver 키가 필요 없으며 OAuth 등록 설정을 사용하지 않는다. IntelliJ `bootRun`에 넣은 환경변수는 PowerShell이나 다른 실행 구성에 자동 전달되지 않고 `.env`도 자동으로 읽히지 않는다.

가격 미리보기와 실제 DB 반영은 **V12까지 적용된 테이블**을 사용하며 Flyway를 실행하지 않는다. 처음 만든 MySQL DB는 반영 전에 개인 설정을 준비하고 일반 `bootRun`을 한 번 실행해 마이그레이션을 적용한 뒤 종료한다. 기존 DB는 이력과 소유자를 확인하며 초기화하지 않는다. 일반 `bootRun`, pull, build/test에서는 카탈로그·가격을 자동 적재하지 않는다. 아래 `setupDevCatalog`의 기본 미리보기에는 DB가 필요 없다.

### 새 DB 또는 300종 카탈로그 준비가 끝나지 않은 DB

`setupDevCatalog`는 승인된 카탈로그 300종·호환 근거·상품가 72종을 함께 준비하는 별도 작업이다. JDK 21에서 기본 미리보기로 사용할 검토 자료를 확인한다. 기본 실행은 DB에 연결하지 않으며 `DB_PASSWORD`와 OAuth 키 없이 실행할 수 있다.

```powershell
.\backend\gradlew.bat --project-dir .\backend setupDevCatalog
```

`CATALOG PREVIEW: reviewedProducts=300, reviewedPrices=72, databaseWrites=0`을 확인한다. 이 미리보기는 검토 자료의 건수를 확인하며 기존 제품과의 충돌을 검사하지 않는다. **내 개발 DB 준비를 선택한 경우에만** V12까지 적용된 DB, 개인 DB 설정과 해당 실행 환경의 `DB_PASSWORD`를 준비하고 명시적인 옵션으로 반영한다. OAuth 키는 반영에도 필요 없다.

```powershell
.\backend\gradlew.bat --project-dir .\backend setupDevCatalog -PapplyDevCatalog=true
```

카탈로그·호환 근거·가격을 한 트랜잭션으로 검증·적재한다. 기존 식별·제원과 충돌하는 경우 중단하며 PC 데이터와 개인 설정을 초기화하지 않는다. 테이블이 없는 DB에 마이그레이션을 자동 적용하는 작업은 아니다. 준비를 마치면 일반 백엔드를 다시 실행한다.

### 기존 300종 카탈로그에 가격만 추가

`importCatalogPrices`는 기존 제품을 참조하며 제품·호환 근거를 새로 만들지 않는다. 다른 seed·가격 자동 가져오기를 켜지 않고 기존 스키마와 제품 식별을 확인한다. 테이블이 없거나 제품 준비가 끝나지 않았으면 위 준비 순서를 먼저 따른다.

최신 `dev`를 받은 뒤 프로젝트 루트에서 먼저 미리보기를 실행한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importCatalogPrices
```

`PREVIEW`의 `checked=72`, `newMappings`, `newObservations`, `unchanged`를 확인한다. 아직 가격이 없는 동일 카탈로그 DB라면 새 매핑·관측이 각각 72개다. 기존 22종만 적용돼 있다면 각각 50개가 추가 대상이다. 미리보기는 가격 행을 저장하지 않으며, 실제 반영은 **명시적인 옵션**으로만 실행한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importCatalogPrices -PapplyPrices=true
```

`COMMITTED`가 나온 뒤 같은 미리보기를 다시 실행하면 `newMappings=0`, `newObservations=0`, `unchanged=72`가 나온다. 전체 배치의 제품 식별·판매 단위·충돌을 검증해 한 트랜잭션으로 반영하며, 같은 관측을 재실행해도 중복 행을 추가하지 않는다. 제품의 내부 ID를 하드코딩하지 않고 각 DB의 BuildCores 원본 식별과 제조사·모델·부품번호를 대조한다. 기존의 다른 가격·제품 제원·장기 기준가격은 초기화하지 않는다. 더 최근에 저장한 관측이 있으면 API는 그 값을 계속 선택한다.

이 자료는 **2026-10-06·2026-10-08에 실제 확인한 관측 스냅샷**이며 실행일의 실시간 최저가가 아니다. 승인된 300종 중 가격 미확인 228종은 계속 `null`이다. 다른 가격을 만들거나 새 판매가를 반영하려면 별도의 수집·제품 식별 검토가 필요하다. 개인 DB 설정·비밀번호·OAuth 키를 커밋하거나 공유 파일에 넣지 않는다.

아래 날짜별 수집·적용·검증 기록은 당시 결과를 보존한다. 팀원이 자신의 DB에 가져오기를 실행한 사실이나 실제 로그인 검증을 대신하지 않는다.

### 팀원용 명령 검증 기록 (2026-10-08)

백엔드 H2 전체 테스트 288개(신규 11개 포함)가 실패·오류·건너뜀 없이 통과했다. 빈 카탈로그와 부분 카탈로그 준비, 재실행, 최신 가격·기준가격·기존 PC 보존, 실패 시 전체 롤백을 검사했다. 최초 전체 실행은 테스트 JVM 메모리 부족으로 중단됐으며, 테스트 최대 힙을 1GB로 지정한 뒤 전체를 다시 실행해 통과했다.

`setupDevCatalog` 기본 미리보기와 `bootJar`가 성공했고, JAR에 포함한 두 관측 파일의 내용이 검토한 원본과 일치했다. 이 데스크톱의 기존 MySQL에서는 OAuth 키 없이 가격 미리보기를 실행해 `checked=72`, `newMappings=0`, `newObservations=0`, `unchanged=72`를 확인했다. 웹 실행 환경변수가 있어도 별도 웹 서버를 시작하지 않았다. **새 빈 MySQL에 전체 카탈로그를 적재하는 실행과 실제 소셜 계정 로그인은 이 검증에 포함되지 않는다.**

## 저장과 조회

V12는 두 테이블을 추가한다. 기존 마이그레이션과 기준가격 행은 변경하지 않는다.

| 테이블 | 역할 | 주요 제약 |
| --- | --- | --- |
| `catalog_price_mapping` | 내부 제품과 검토한 판매 제품의 연결, SKU, 판매 단위, 검토 시각 | 판매처 코드 + 외부 ID는 하나의 내부 제품에만 연결 |
| `catalog_price_observation` | 상품가, 실제 확인 시각, 근거 URL의 관측 이력 | 양의 정수 KRW, 매핑 + 시각 유일, 매핑과 제품의 복합 FK |

제품 목록·상세 API의 `product.currentPrice`는 다음 형태이며, 관측이 없으면 `null`이다.

```json
{
  "amountKrw": 215990,
  "sourceName": "DANAWA",
  "sourceUrl": "https://prod.danawa.com/info/?pcode=16101353",
  "observedAt": "2026-10-06T12:00:00Z"
}
```

위 값은 형식 예시다. API는 가장 최근에 **저장한 관측**을 반환하며, 요청할 때 판매처를 다시 조회하지 않는다. 같은 확인 시각이면 관측 ID로 순서를 결정한다. 여러 판매처가 추가되더라도 최신 관측을 선택하는 현재 정책이 전체 판매처의 최저가 계산을 뜻하지는 않는다.

화면은 출처와 한국 시간의 확인 시각을 표시한다. 가격 새로고침은 저장된 최신 API 값을 다시 읽는다. 가격 미확인은 0원으로 대체하지 않으며, 일부만 계산된 합계에는 미확인 항목 수를 표시한다. RAM 가격은 판매 키트 전체 가격이다. 동일 제품의 실제 장착 모듈 수가 `moduleCount`의 정수 배수일 때만 키트 수로 환산한다.

## 첫 매핑과 수집

[`initial-danawa-mappings.json`](../data/catalog-current-prices/initial-danawa-mappings.json)은 BuildCores 외부 ID와 정확한 제조사·모델·부품번호를 국내 판매 페이지에 연결한다. `review`에는 예상 상품명과 제품 식별 근거를 남긴다. CPU의 정품 박스와 벌크·멀티팩, GPU의 OC·색상·냉각판 변형, RAM의 RGB·타이밍·키트 용량은 구분한다. 하드웨어 리비전이 확인되지 않은 후보는 수집 대상에서 제외한다.

2026-10-06 21:39(한국 시간)에 CPU 6·GPU 5·메인보드 9·RAM 2, **22종 모두** 실제 페이지에서 수집했다. [관측 파일](../data/catalog-current-prices/observations-2026-10-06.json)과 [수집 보고서](../data/catalog-current-prices/observations-2026-10-06.report.json)에 확인 시각과 URL을 기록했다. 이어서 로컬 MySQL을 읽기 전용으로 조회해 22종의 기존 제품 식별과 판매 단위가 모두 일치함을 확인했다.

**2026-10-06 21:50(한국 시간), 사용자의 명시적 승인에 따라 실제 로컬 MySQL에 V12와 22종의 상품가를 반영했다.** Flyway V12 적용과 가격 가져오기 `COMMITTED`를 확인했다. 판매처 매핑 22행·가격 관측 22행이 저장됐으며, CPU 6·GPU 5·메인보드 9·RAM 2종이다. 전체 카탈로그 300종 중 22종에 현재 상품가가 있고 나머지 278종은 `null`이다. 적용과 후속 검증은 [적용 보고서](../data/catalog-current-prices/applied-2026-10-06.json)에 기록한다.

Node.js 24에서 프로젝트 루트 기준으로 실행한다. 이 명령은 JSON 파일만 만들며 DB에 접속하지 않는다.

```powershell
node backend/tools/collect-danawa-prices.mjs --manifest data/catalog-current-prices/initial-danawa-mappings.json --output backend/build/catalog-prices/current-prices.json
node --test backend/tools/collect-danawa-prices.test.mjs
```

수집기는 검토한 상품명이 일치하는 페이지의 일반 판매 영역에서 활성 구매 링크가 있는 `data-base-price`를 읽는다. 배송료 포함 대표값이나 멤버십·카드 혜택가는 사용하지 않는다. 표시된 일반 판매 행 중 가장 낮은 상품가를 기록한다. 이는 페이지의 모든 판매처를 별도로 탐색한 결과는 아니다.

품절, 제목 불일치, 형식 변화, 요청 실패는 오류 보고서에 남기고 해당 제품의 가격을 생성하지 않는다. 일부 실패 시 성공 항목 파일과 보고서를 함께 남기고 종료 코드 2를 반환한다. 오류를 검토한 뒤 성공 항목을 사용할 수 있다. 웹 검색의 캐시 가격은 관측 파일에 넣지 않는다. 매핑의 `verifiedAt`은 재수집 때 유지하고 관측의 `observedAt`만 갱신한다.

## 추가 50종 반영

**2026-10-08 19:37(한국 시간), 기존 카탈로그의 가격 미확인 제품 50종에 국내 신품 상품가를 추가했다.** 제품 자체를 새로 생성한 것은 아니며, 전체 카탈로그는 300종이다. 이번 배치는 CPU 27·메인보드 12·GPU 7·모니터 4종이다. 기존 22종과 합쳐 현재 상품가가 있는 제품은 **72종**, 미확인은 **228종**이다.

[검토한 매핑](../data/catalog-current-prices/expansion50-danawa-mappings-2026-10-08.json), [실제 관측 50종](../data/catalog-current-prices/observations-expansion50-2026-10-08.json), [수집·선정 보고서](../data/catalog-current-prices/observations-expansion50-2026-10-08.report.json), [DB 적용 보고서](../data/catalog-current-prices/applied-expansion50-2026-10-08.json)에 제품 식별, 가격, URL과 시각을 기록했다. 관측은 2026-10-08 19:18~19:29(한국 시간)의 실제 페이지에서 수집한 값이다. 숫자 PN이 국내 페이지에 직접 표시되지 않는 CPU는 공식 박스 PN과 정확한 모델·정품 포장·쿨러 구성을 대조했고, 그 근거 범위를 매핑의 `review`에 명시했다. 기존 PN 미확인 값은 그대로 유지했다.

실제 페이지 114개를 확인해 일반 신품 상품가 57개를 확보했다. 그중 희소 고가 6개는 [보류 기록](../data/catalog-current-prices/deferred-expansion50-2026-10-08.json)에 실제 관측값을 보존하고 이번 배치에서 제외했다. 판매가 없는 페이지, 용량·OC·냉각판이 다른 GPU, 하드웨어 리비전 미확정 제품과 PN·키트 구성이 불명확한 RAM도 적용하지 않았다. 기존 22종의 가격은 다시 수집하거나 갱신하지 않았다.

V12가 이미 적용된 로컬 MySQL에서 신규 매핑 50행·관측 50행의 `COMMITTED`를 확인했다. 재실행 미리보기는 `checked=50`, `newMappings=0`, `newObservations=0`, `unchanged=50`이었다. 저장된 72종의 제품 식별·SKU·판매 단위·금액·근거 URL·UTC 절대 시각이 입력과 모두 일치했다. 기존 22종 가격과 기준가격 300행의 데이터 digest도 이전과 같았다.

실제 목록·상세 API 72종을 대조하고 가격 미확인 228종의 `null`을 확인했다. 프론트엔드 응답 검증과 72종 각각의 가격 합계 계산을 통과했다. 새 가격을 포함한 CPU·보드·GPU·모니터·RAM 5개 부품의 상품가 합계는 3,609,210원이었다. RAM 키트 환산과 불완전 키트·미확인 항목 처리도 확인했다. 수집기 테스트 14개를 통과했으며, 이번 데이터 확대에는 애플리케이션 코드를 변경하지 않아 백엔드 H2·프론트엔드 전체 테스트는 다시 실행하지 않았다. 브라우저 화면과 Google 로그인은 이번 검증 범위에 포함하지 않았다.

추가 50종을 재수집할 때는 다음 매핑 파일을 사용한다. 이 명령도 관측 파일만 생성하며 DB 가격을 자동으로 갱신하지 않는다.

```powershell
node backend/tools/collect-danawa-prices.mjs --manifest data/catalog-current-prices/expansion50-danawa-mappings-2026-10-08.json --output backend/build/catalog-prices/expansion50-current-prices.json
```

## 검증과 DB 반영

입력 파일은 `schemaVersion: 1`, `items` 배열이며 각 항목에 `product`, `offer`, `price`가 있다. 수집 파일에는 `review`를 넣지 않는다. 가져오기 검증은 다음을 확인한다.

- 카탈로그의 제조사·모델·부품번호 일치와 판매처 ID의 중복 연결 방지
- RAM 키트의 정확한 모듈 수, 일반 부품의 1개 단위
- 양의 정수 상품가, 과거 또는 현재 시각, HTTPS 근거 URL
- `condition: "NEW"`, `inStock: true`, 알 수 없는 필드·JSON 중복 키·타입 변환 거절
- 같은 관측 재실행은 건너뛰고, 같은 시각에 다른 값이면 전체 배치 실패

가져오기는 기본 비활성이며, 활성화해도 `dry-run` 기본값은 `true`다. 배치 전체를 검증한 뒤 하나의 트랜잭션으로 반영한다. 가격 반영이 제품의 제원 검증 상태나 장기 기준가격을 확정하지는 않는다.

실제 MySQL 스키마 변경과 가격 반영은 별도 실행 단계다. 위 팀원용 `importCatalogPrices`는 Flyway를 실행하지 않는다. 직접 **`bootRun`으로 가져오기를 켜는 기존 경로는 미리보기 모드에서도 시작 시 Flyway로 마이그레이션을 적용할 수 있다.** 따라서 그 경로는 실제 DB 변경 실행을 요청받은 뒤 실행한다. 첫 22종은 위 승인·반영 기록에 따라 적용했으며, 다음은 이후 검토한 다른 관측 파일을 가져올 때 사용하는 설정이다.

```text
--catalog.price-import.enabled=true
--catalog.price-import.file=<검토한 JSON의 절대 경로>
--catalog.price-import.dry-run=true
```

V12 적용 후 미리보기 결과의 `checked`, `newMappings`, `newObservations`, `unchanged`를 확인하고, 실제 반영 시에만 `dry-run=false`를 사용한다. DB 비밀번호는 기존 개인 설정과 환경변수로 전달하며 파일이나 명령 인수에 넣지 않는다.

## 적용 후 검증

첫 22종 반영 당시 실제 로컬 MySQL과 API에서 다음 검증을 완료했다. 추가 50종의 검증 결과는 위 확대 기록을 따른다. 브라우저 화면 조작 검증은 하지 않았으며 `browserVerified=false`다.

- 동일 관측 파일의 재실행 미리보기는 `checked=22`, `newMappings=0`, `newObservations=0`, `unchanged=22`였다.
- 목록 API에서 전체 300종과 가격 22종·미확인 278종을 확인했다. 가격이 있는 22종의 목록·상세 API에서 금액, 출처 URL, `observedAt`이 모두 일치했다.
- 실제 API 응답을 프론트엔드 `catalogClient`에 넣어 가격 22종의 응답 검증을 통과했다. 실가격을 사용한 4개 부품 합계는 **2,483,010원**이었다. RAM 2개 모듈 키트와 분리된 모듈 행의 합계가 같았고, 불완전 키트는 합계에서 제외되며 미확인 항목은 유지됐다.

MySQL 세션의 `SYSTEM` 한국 시간과 Hibernate의 UTC 바인딩 차이로 신규 가격의 절대 시각에 9시간 오차가 있음을 발견했다. 신규 가격 서비스에만 트랜잭션 연결의 UTC 세션 범위를 적용하고, 종료 시 원래 시간대로 복원하도록 보완했다. 기존 설정과 테이블은 변경하지 않았으며, 신규 매핑 22행의 `verified_at`과 관측 22행의 `observed_at`만 올바른 UTC 절대 시각으로 원자적으로 보정했다. DB의 절대 시각과 API 응답을 다시 대조했다. 금액·제품 연결은 유지됐고, 기존 기준가격 300행의 `UNCONFIRMED`·금액 NULL 상태와 데이터 digest도 보존됐다.

최초 코드 검증은 백엔드 H2 269개(신규 가격 10개 포함), 프론트엔드 78개, 수집기 14개 테스트와 프론트엔드 lint·build를 통과했다. 시간대 보완 직후에는 관련 18개 테스트와 `bootJar` 재빌드가 성공했으며, 당시 전체 H2 테스트는 다시 실행하지 않았다. 이후 전체 재검증 결과는 아래에 기록한다. 이 자동 검증과 실제 MySQL·API 검증은 별도 결과다.

## GitHub 반영 전 최종 검증

2026-10-08, 시간대 보완을 포함한 최종 코드로 백엔드 일반 H2 테스트를 `test --rerun-tasks`로 다시 실행해 **277개 모두 통과**했다(실패·오류·건너뜀 0). 프론트엔드 테스트 78개와 lint·build, 수집기 테스트 14개도 통과했다. 커밋할 관측 파일의 SHA-256이 적용 보고서와 일치하고, 개인 설정·비밀값·빌드 결과물이 커밋 대상에 포함되지 않은 것을 확인했다. 실제 로컬 MySQL·API 72종 검증은 위 적용 기록을 따른다.

## 이어서 할 일

1. 같은 식별 검토를 거쳐 현재 판매 중인 제품으로 매핑을 확대한다. 수집 실패·품절·오래된 관측의 화면 처리 정책도 정한다.
2. 가격 수집의 주기와 운영 위치를 정한 뒤 자동 실행을 연결한다.
3. 브라우저에서 가격 표시·새로고침·구성 합계 흐름을 확인한다.
4. STORAGE·PSU·CASE·COOLER 카탈로그와 장착·전력 관련 누락 제원을 보완한다. DB 점검 내역은 [감사 기록](catalog-db-audit-2026-10-06.md)을 참고한다.

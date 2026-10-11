# 중앙 상품가 Worker

**2026-10-11 실제 배포한 최신 운영 묶음은 315종이다: 확인한 판매가 83종·기존 참고가 227종·가격 보류 5종.** 승인 후 PSU 4종·케이스 3종과 정확한 상품가 2종을 추가했고 실제 HTTPS 조회를 통과했다. 기존 v1 14종·8가격, v2 307종·80가격 및 308종·81가격 응답을 보존한다. 최신 backend 코드는 카탈로그 버전 헤더로 315종을 조회하며, 헤더 없는 현재가 요청은 307종·참고가 요청은 308종 계약을 유지한다. [PSU·케이스 실제 DB·배포 결과](../../docs/catalog-power-case-pilot-2026-10-11.md), [기존 운영 설정](../../docs/catalog-worker-prices-2026-10-10.md), [참고가 기준](../../docs/catalog-current-prices.md#참고가-227종-게시-완료)을 확인한다. 환경변수 세 개와 팀 토큰은 그대로 사용한다.

승인 카탈로그와 가격 관측을 제공하는 Cloudflare Workers 모듈이다. 실행 시 새 가격을 수집하지 않는다. MySQL·KV·D1·R2·Cron이나 PC 데이터는 사용하지 않는다. 상품가·출처·원래 관측 시각은 승인 자료 그대로이며 응답 시각과 48시간 경고·7일 만료 판정만 요청 시 계산한다.

`GET /api/v2/prices?canonicalIds=<UUID[,UUID...]>`에 `Authorization: Bearer <팀 토큰>`을 전달한다. 구 backend의 `/api/v1/prices`도 같은 토큰을 사용한다. 토큰은 32 random bytes를 표현하는 64자리 hex 문자열이며 대소문자를 구별한다. Worker의 `SHARED_PRICES_API_TOKEN` Secret과 각 Java backend의 `CATALOG_SHARED_PRICES_API_TOKEN` 환경변수에 같은 값을 설정한다. 프론트엔드 환경변수에 넣지 않는다. 미설정·잘못된 Worker Secret은 503, 토큰 불일치는 401이다.

참고가는 `GET /api/v1/reference-prices?canonicalIds=<UUID[,UUID...]>`에서 같은 인증으로 제공한다. 과거 판매가·출시가·추정가의 근거와 범위를 구분하며 현재가 83종의 관측값이나 48시간·7일 판정을 덮어쓰지 않는다. 새 PSU·케이스 7종에는 참고가를 만들지 않는다. 화면은 상품 가격의 금액을 표시하고 출처·근거는 API 데이터에 보존한다.

## 자료 생성과 로컬 검증

프로젝트 루트, JDK 21에서 승인 자료를 내보내고 원본과 대조한다. 실제 DB는 사용하지 않는다.

```powershell
.\backend\gradlew.bat --project-dir .\backend exportSharedCatalog
.\backend\gradlew.bat --project-dir .\backend verifyWorkerCatalog
.\backend\gradlew.bat --project-dir .\backend exportApprovedRetailCatalog
node workers/catalog-prices/scripts/export-reference-preview.mjs --approved
.\backend\gradlew.bat --project-dir .\backend exportApprovedPowerCaseCatalog
.\backend\gradlew.bat --project-dir .\backend verifyApprovedPowerCaseCatalog
cd workers/catalog-prices
npm.cmd ci
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
node scripts/verify-power-case-release.mjs --runtime
npm.cmd run test:java-client
npm.cmd run test:wrangler
```

- `generated/catalog.json`·`contract-fixtures.json`은 기존 v1, `catalog-v2.json`·`contract-fixtures-v2.json`은 전체 v2의 Java 검증·실제 HTTP 응답에서 생성한다. 직접 편집하지 않는다.
- 기본 export·검증은 `data/catalog-shared/active-approved-prices.json`의 승인 80종을 사용한다. 파일이 없거나 잘못되면 실패하며 과거 74종으로 돌아가지 않는다. `approved-prices-2026-10-10.json`의 최초 74종은 원본 대조·backend의 식별 검증 자료로 보존한다. 새 승인 묶음을 검토할 때는 명시적 가격 파일 옵션을 사용한다.
- `exportApprovedRetailCatalog`는 `approved-retail-extension-2026-10-10.json`에서 이전 운영 묶음 308종·81가격과 `generated/catalog-retail-approved.json`·`contract-fixtures-retail-approved.json`을 생성한다. 기존 307종 산출물은 보존한다. 참고가 export의 `--approved`는 별도 승인본 `active-reference-prices.json`을 사용하며 원 검토본과의 일치를 확인한다. `build`도 승인 참고가 227종의 산출물을 대조한다.
- `exportApprovedPowerCaseCatalog`는 별도 `approved-power-case-extension-2026-10-11.json`의 DB·공표 승인 `true`와 `approved-power-case-reference-prices-2026-10-11.json`을 검증한다. `catalog-power-case-approved.json`, `contract-fixtures-power-case-approved.json`, `reference-prices-power-case-approved.json`, `approved-power-case-release.json`을 생성하며 기존 14·307·308종 산출물과 참고가 308종 버전을 보존한다. 원 검토본과 stage의 두 승인 값은 `false`로 보존하며 자동 활성화하지 않는다. 실제 DB 적재는 별도의 `power-case-database-approval-2026-10-11.json` 승인을 사용한다.
- 최신 공개 버전은 `all-catalog-v1-6bd04f97b7cba4fe30dc5113d15db56847026a744cf6c5f7006da62a1e9e140a`다. 가격 버전은 `prices-v1-dfa1885e2f5d29b7c747eb4c3a730467aa4b78f70830c9d682e2be104825c09b`이며 참고가 내용 버전은 기존 값을 유지한다. 이 카탈로그 버전을 `X-Catalog-Version` 헤더로 요청할 때만 315종을 선택한다.
- `build`는 승인 파일·제조사 식별 7종·상품가 2종·검토자료 SHA·기존 308종과 참고가 227종 보존을 검사한다. `verify-power-case-release.mjs --runtime`은 합성 토큰으로 로컬 workerd에서 이전 버전과 315종 응답을 전수 대조한다. DB·클라우드 업로드나 개인 설정 변경은 하지 않는다.
- `npm test`는 실제 workerd에서 v1 기준 응답 53개와 v2 기준 응답, 인증·요청·최대 응답 크기를 비교한다. 315종 승인 묶음에는 Java 실제 HTTP 기준 응답 509개를 포함하며 운영 승인 추가 후 전체 1,155개 테스트·타입 검사·build를 통과했다. 이전 307·308종 기준 응답도 계속 검사한다. 테스트용 고정 시각 헤더는 배포 진입점에 포함되지 않는다.
- `test:java-client`는 임의 loopback 포트와 합성 토큰으로 실제 Java 클라이언트를 연결하고 종료한다. JDK 21 환경이 필요하다.
- `test:wrangler`는 실제 Wrangler 설정으로 로컬 서버·토큰 마스킹을 확인한다. 기존 `.dev.vars`가 있으면 보존하고 중단하며, 직접 만든 임시 합성 파일만 삭제한다.
- `build`는 로컬 `dist/index.js`를 만든다. 클라우드에 업로드하지 않는다. 테스트 소요 시간은 무료 플랜의 실제 CPU 계측을 대신하지 않는다.

## 승인한 코드 재배포

로컬 검증 후 기존 Worker와 팀 Secret을 유지하여 실행한다. 이 명령은 실제 클라우드 배포이므로 승인된 반영 단계에서만 사용한다.

```powershell
cd workers/catalog-prices
node node_modules/wrangler/bin/wrangler.js deploy --config wrangler.jsonc --strict --minify
```

새 Secret 등록이나 토큰 교체는 필요 없다. 배포 후 기존 팀 토큰으로 315종 버전의 현재가 83종·`NO_PRICE` 232종과 별도 참고가 227종을 대조한다. 신규 7종의 참고가는 모두 `NO_REFERENCE`이며, 두 현재가만 추가하고 나머지 5종은 미확인을 보존한다. 무인증 401과 이전 14·307·308종 버전도 함께 확인한다. 실제 배포·DB 반영 결과는 [PSU·케이스 반영 문서](../../docs/catalog-power-case-pilot-2026-10-11.md)에 기록한다.

2026-10-11 승인된 실제 배포는 `2026-10-11T08:02:20.607388Z`(한국 시간 17:02:20), version `8a7a5ad7-e8b0-4814-91ec-c595e6717186`, deployment `dbc186d9-4ff5-4441-a765-f5ce130e8036`, 트래픽 100%다. 기존 팀 Secret을 재사용했다. [공개 HTTPS 검증 기록](../../data/catalog-review/power-case-worker-production-2026-10-11.json)의 36개 요청에서 현재가 **315종·83 `OK`·232 `NO_PRICE`**, 참고가 **227 `OK`·88 `NO_REFERENCE`**, 신규 상품 7종의 가격 2건·보류 5건, 이전 버전·기본 응답·무인증 401·캐시와 콘텐츠 헤더를 모두 확인했다. 참고가 227종의 값·근거·내용 버전은 그대로 유지했다. [실제 Java HTTPS](../../data/catalog-review/power-case-java-https-2026-10-11.json), [기존 설정의 backend 재시작](../../data/catalog-review/power-case-backend-restart-2026-10-11.json), [Vite 프록시 로컬 API](../../data/catalog-review/power-case-local-api-2026-10-11.json) 검증도 모두 통과했다. 브라우저·OAuth 수동 검증과 별도 `mysqlTest`는 실행하지 않았다.

## 로컬 서버

Wrangler 설정 옆의 개인 `.dev.vars`에 `SHARED_PRICES_API_TOKEN`을 설정한 뒤 `npm.cmd run dev`를 실행한다. 값은 Git·채팅·로그에 남기지 않는다. `.dev.vars*`·`.env*`·빌드·캐시·의존성 폴더는 무시된다. 개발 서버는 `http://127.0.0.1:8787`에서만 연결을 받으며 `--local`로 실행한다. `dev` 명령은 외부 cf 자료 조회·사용 통계를 끈다.

2026-10-10 사용자 승인 후 Workers Free에 **https://pc-upgrade-shared-prices.joony1024.workers.dev**로 처음 배포했다. 최초 v1 배포는 `deploy --config wrangler.jsonc --strict --minify --secrets-file .env.production-secrets.local`로 코드·Secret을 함께 등록했으며 version은 `b32e13a6-1f38-468d-8490-a84f2726259a`였다. 최초 전체 v2 전환 시각은 `2026-10-10T09:25:45.216455Z`, version은 `71504364-1cef-40ec-9bff-96a584991895`, deployment는 `2065fb60-1bf7-408b-b310-22b01dd0ea1b`다. 당시 가격 5종 추가 재배포는 `2026-10-10T10:38:44.649971Z`(19:38:44 KST), version `a12e2ae2-4431-4024-bd9b-7f72e11f1563`, deployment `63016ff8-d994-4152-8034-4b3c4d914385`, 트래픽 100%였다. 기존 팀 Secret과 무료 서비스를 유지했다. `wrangler secret put`도 즉시 배포를 만들며 새 배포·토큰 교체를 로컬 검증 명령에 포함하지 않는다. 자동 배포 스크립트나 GitHub 배포 연동은 구성하지 않았다.

중앙 연결을 처음 설정할 때 운영 backend는 `CATALOG_SHARED_PRICES_ENABLED=true`, 위 HTTPS origin의 `CATALOG_SHARED_PRICES_BASE_URL`, 운영 Secret과 동일한 `CATALOG_SHARED_PRICES_API_TOKEN`을 기존 실행 구성에 추가하고 재시작한다. `.env.backend-prices.local`은 실제 환경변수 복사용 개인 파일이며 Spring이 자동으로 읽지 않는다. 기존 DB/OAuth 설정은 보존한다. 이미 중앙 연결을 완료했다면 최신 코드를 pull하고 backend를 재시작하며 같은 환경변수와 토큰을 사용한다. [운영 설정과 검증 결과](../../docs/catalog-worker-prices-2026-10-10.md)를 따른다.

팀원·다른 기기의 DB도 기존 카탈로그 준비와 `importAllSharedIdentities`의 일회성 명시 적용이 필요하다. GitHub pull이나 일반 backend 시작은 상품·가격·공통 ID를 자동 적재하지 않는다. 최초 307종 공통 ID 연결은 기존 제품·제원·가격·PC 연결을 보존했으며 가격·제품·모델 생성은 0건이고 당시 Flyway V14를 확인했다. 이번 작업 기기는 승인 후 V15와 PSU·케이스 7종·판매가 2종을 별도 적재했다. 다른 기기의 315종 준비는 기존 308종·81가격과 공통 ID를 확인하고 V15 적용→읽기 대조→별도 DB 승인 파일의 명시적 적재→기존 설정 재시작 순서로 진행한다. 정확한 명령과 승인 파일 경로는 [다른 PC의 실제 DB 적용 순서](../../docs/catalog-power-case-pilot-2026-10-11.md#다른-pc의-실제-db-적용-순서)를 따른다. 개인 DB·OAuth·중앙 가격 환경은 유지하며 Worker 재배포나 새 팀 토큰은 필요 없다.

프로젝트 루트에서 아래 명시적 작업으로 실제 Java HTTPS 계약·401·최대100개 조회를 검증한다. 개인 환경 토큰이 필요하며 DB는 시작하지 않는다. 2026-10-11에는 315종·판매가 83종·참고가 227종과 기존 307종·80가격/308종·81가격, 기본 v1 14종·8가격까지 실제 Java HTTPS로 확인했다.

```powershell
.\backend\gradlew.bat --project-dir .\backend verifyDeployedPrices -PdeployedPricesBaseUrl=https://pc-upgrade-shared-prices.joony1024.workers.dev -PdeployedPricesFullCatalog=true
```

최신 backend 재시작은 JDK 21·8080 포트와 기존 5173 프론트엔드를 유지했으며 V15 최신 상태·추가 마이그레이션 0건·개인 설정 변경 0건을 확인했다. 로컬 프록시 API에서는 전체 가격 315종과 신규 상세 7종·출처 14개 정확 시각·호환성 10개 사례·기존 V1 범위·한국어 GiB 안내·인증과 CSRF를 검사했다. 신규 PSU·CASE 출처 시각의 9시간 조회 오류는 UTC 읽기 경로를 수정해 해결했고 관련 3개 클래스·21개 회귀 테스트를 별도로 통과했다. Worker 1,155개 테스트와 합산하지 않는다. [전체 반영·검증 범위](../../docs/catalog-power-case-pilot-2026-10-11.md#검증-결과).

승인과 실제 DB·Worker 배포·재시작·API 결과를 연결한 [최종 반영 기록](../../data/catalog-review/power-case-rollout-2026-10-11.json)을 함께 확인한다. 이 JSON의 Git 커밋·push 미실행 표시는 기록 생성 당시 상태이며 후속 승인 업로드의 상태는 저장소 이력에서 확인한다. 검증 당시 PID 11200 backend는 이후 종료해 8080 포트를 비웠으며 다시 실행할 때는 기존 개인 실행 구성을 사용한다.

최초 운영 Java HTTPS 검증에서 인증 실패 HTTP 401·최대100개 조회를 확인했다. 80종 가격 단계에는 HTTPS 4개 묶음에서 **v2 307종·80가격·227 `NO_PRICE`·FRESH 80종**과 무인증 401을 확인했다. 당시 홈페이지 프록시에서도 중앙 307종·가격 80종을 확인했다. [80종 가격 반영 이력](../../data/catalog-review/remaining232-price-publication-2026-10-10.json), [최초 DB 공통 ID 연결·보존 기록](../../data/catalog-review/all-catalog-central-rollout-2026-10-10.json).

2026-10-10 참고가 게시 version은 `b95ed106-8dda-452f-9633-25e52f29ccdf`였다. 당시 backend 66개·Worker 637개 테스트와 실제 Java HTTPS 검증을 통과했으며 홈페이지 API 전수 확인은 **중앙 308종·판매가 81종·참고가 227종·가격 자료 없음 0종·연결 실패 0종**이었다. 당시 참고가 게시에서는 MySQL을 변경하지 않았다. [이전 참고가 게시·재시작 검증 기록](../../data/catalog-review/reference-price-publication-2026-10-10.json). 최신 315종 배포·DB 반영·검증 범위는 위 2026-10-11 결과를 따른다.

현재 패키지는 Wrangler 4.149.0이 사용하는 Miniflare 5.20261006.1-alpha와 workerd 1.20261006.1을 고정한다. 테스트에서는 해당 패키지의 공식 `convertV4MiniflareOptions` 어댑터를 사용한다. 로컬 설정의 compatibility date는 이 runtime에 맞춘 2026-10-06이다.

공식 근거: [로컬 실행과 workerd](https://developers.cloudflare.com/workers/local-development/), [Miniflare 테스트](https://developers.cloudflare.com/workers/testing/miniflare/writing-tests/), [Secret과 로컬 개인 파일](https://developers.cloudflare.com/workers/configuration/secrets/), [Workers Web Crypto](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/).

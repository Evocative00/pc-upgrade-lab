# 중앙 상품가 Worker

**현재 운영은 308종 전체에 가격 자료를 제공한다: 확인한 판매가 81종·참고가 227종(모델 5·출시 3·추정 219).** 승인한 ASUS 판매상품과 참고가 게시를 완료했다. 기존 v1 14종·8가격과 구 backend용 v2 307종·80가격도 유지한다. 최신 backend는 카탈로그 버전 헤더로 308종을 조회하고 참고가를 별도 API로 받는다. [현재 설정·적용 결과](../../docs/catalog-worker-prices-2026-10-10.md)와 [참고가 기준·합계](../../docs/catalog-current-prices.md#참고가-227종-게시-완료)를 먼저 확인한다. 환경변수 세 개와 팀 토큰은 그대로 사용한다.

승인 카탈로그와 가격 관측을 제공하는 Cloudflare Workers 모듈이다. 실행 시 새 가격을 수집하지 않는다. MySQL·KV·D1·R2·Cron이나 PC 데이터는 사용하지 않는다. 상품가·출처·원래 관측 시각은 승인 자료 그대로이며 응답 시각과 48시간 경고·7일 만료 판정만 요청 시 계산한다.

`GET /api/v2/prices?canonicalIds=<UUID[,UUID...]>`에 `Authorization: Bearer <팀 토큰>`을 전달한다. 구 backend의 `/api/v1/prices`도 같은 토큰을 사용한다. 토큰은 32 random bytes를 표현하는 64자리 hex 문자열이며 대소문자를 구별한다. Worker의 `SHARED_PRICES_API_TOKEN` Secret과 각 Java backend의 `CATALOG_SHARED_PRICES_API_TOKEN` 환경변수에 같은 값을 설정한다. 프론트엔드 환경변수에 넣지 않는다. 미설정·잘못된 Worker Secret은 503, 토큰 불일치는 401이다.

참고가는 `GET /api/v1/reference-prices?canonicalIds=<UUID[,UUID...]>`에서 같은 인증으로 제공한다. 과거 판매가·출시가·추정가의 근거와 범위를 구분하며 현재가 81종의 관측값이나 48시간·7일 판정을 덮어쓰지 않는다. 홈페이지에서는 현재가 합계와 참고가격 합계를 구분한다.

## 자료 생성과 로컬 검증

프로젝트 루트, JDK 21에서 승인 자료를 내보내고 원본과 대조한다. 실제 DB는 사용하지 않는다.

```powershell
.\backend\gradlew.bat --project-dir .\backend exportSharedCatalog
.\backend\gradlew.bat --project-dir .\backend verifyWorkerCatalog
.\backend\gradlew.bat --project-dir .\backend exportApprovedRetailCatalog
node workers/catalog-prices/scripts/export-reference-preview.mjs --approved
cd workers/catalog-prices
npm.cmd ci
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
npm.cmd run test:java-client
npm.cmd run test:wrangler
```

- `generated/catalog.json`·`contract-fixtures.json`은 기존 v1, `catalog-v2.json`·`contract-fixtures-v2.json`은 전체 v2의 Java 검증·실제 HTTP 응답에서 생성한다. 직접 편집하지 않는다.
- 기본 export·검증은 `data/catalog-shared/active-approved-prices.json`의 승인 80종을 사용한다. 파일이 없거나 잘못되면 실패하며 과거 74종으로 돌아가지 않는다. `approved-prices-2026-10-10.json`의 최초 74종은 원본 대조·backend의 식별 검증 자료로 보존한다. 새 승인 묶음을 검토할 때는 명시적 가격 파일 옵션을 사용한다.
- `exportApprovedRetailCatalog`는 `approved-retail-extension-2026-10-10.json`에서 최신 308종·81가격과 `generated/catalog-retail-approved.json`·`contract-fixtures-retail-approved.json`을 생성한다. 기존 307종 산출물은 보존한다. 참고가 export의 `--approved`는 별도 승인본 `active-reference-prices.json`을 사용하며 원 검토본과의 일치를 확인한다. `build`도 승인 참고가 227종의 산출물을 대조한다.
- `npm test`는 실제 workerd에서 v1 기준 응답 53개와 v2 기준 응답, 인증·요청·최대 응답 크기를 비교한다. v2는 최초 승인 가격 74종에서 455개, 현재 승인 가격 80종에서 491개의 기준 응답을 생성한다. 테스트용 고정 시각 헤더는 배포 진입점에 포함되지 않는다.
- `test:java-client`는 임의 loopback 포트와 합성 토큰으로 실제 Java 클라이언트를 연결하고 종료한다. JDK 21 환경이 필요하다.
- `test:wrangler`는 실제 Wrangler 설정으로 로컬 서버·토큰 마스킹을 확인한다. 기존 `.dev.vars`가 있으면 보존하고 중단하며, 직접 만든 임시 합성 파일만 삭제한다.
- `build`는 로컬 `dist/index.js`를 만든다. 클라우드에 업로드하지 않는다. 테스트 소요 시간은 무료 플랜의 실제 CPU 계측을 대신하지 않는다.

## 로컬 서버

Wrangler 설정 옆의 개인 `.dev.vars`에 `SHARED_PRICES_API_TOKEN`을 설정한 뒤 `npm.cmd run dev`를 실행한다. 값은 Git·채팅·로그에 남기지 않는다. `.dev.vars*`·`.env*`·빌드·캐시·의존성 폴더는 무시된다. 개발 서버는 `http://127.0.0.1:8787`에서만 연결을 받으며 `--local`로 실행한다. `dev` 명령은 외부 cf 자료 조회·사용 통계를 끈다.

2026-10-10 사용자 승인 후 Workers Free에 **https://pc-upgrade-shared-prices.joony1024.workers.dev**로 실제 배포했다. 최초 v1 배포는 `deploy --config wrangler.jsonc --strict --minify --secrets-file .env.production-secrets.local`로 코드·Secret을 함께 등록했으며 version은 `b32e13a6-1f38-468d-8490-a84f2726259a`였다. 최초 전체 v2 전환 시각은 `2026-10-10T09:25:45.216455Z`, version은 `71504364-1cef-40ec-9bff-96a584991895`, deployment는 `2065fb60-1bf7-408b-b310-22b01dd0ea1b`다. 최신 가격 5종 추가 재배포는 `2026-10-10T10:38:44.649971Z`(19:38:44 KST), version `a12e2ae2-4431-4024-bd9b-7f72e11f1563`, deployment `63016ff8-d994-4152-8034-4b3c4d914385`, 트래픽 100%다. 기존 팀 Secret과 무료 서비스를 유지했다. `wrangler secret put`도 즉시 배포를 만들며 새 배포·토큰 교체를 로컬 검증 명령에 포함하지 않는다. 자동 배포 스크립트나 GitHub 배포 연동은 구성하지 않았다.

중앙 연결을 처음 설정할 때 운영 backend는 `CATALOG_SHARED_PRICES_ENABLED=true`, 위 HTTPS origin의 `CATALOG_SHARED_PRICES_BASE_URL`, 운영 Secret과 동일한 `CATALOG_SHARED_PRICES_API_TOKEN`을 기존 실행 구성에 추가하고 재시작한다. `.env.backend-prices.local`은 실제 환경변수 복사용 개인 파일이며 Spring이 자동으로 읽지 않는다. 기존 DB/OAuth 설정은 보존한다. 이미 중앙 연결을 완료했다면 최신 코드를 pull하고 backend를 재시작하며 같은 환경변수와 토큰을 사용한다. [운영 설정과 검증 결과](../../docs/catalog-worker-prices-2026-10-10.md)를 따른다.

팀원·다른 기기의 DB도 기존 307종 준비와 `importAllSharedIdentities`의 일회성 명시 적용이 필요하다. GitHub pull이나 일반 backend 시작은 공통 ID를 자동 적재하지 않는다. 최초 공통 ID 연결은 기존 제품·제원·가격·PC 연결을 보존했으며 가격·제품·모델 생성은 0건이다. backend 재시작 시 Flyway V14까지 확인했고 새 마이그레이션은 실행되지 않았다.

프로젝트 루트에서 아래 명시적 작업으로 실제 Java HTTPS 계약·401·최대100개 조회 검증을 완료했다. 개인 환경 토큰이 필요하며 DB는 시작하지 않는다.

```powershell
.\backend\gradlew.bat --project-dir .\backend verifyDeployedPrices -PdeployedPricesBaseUrl=https://pc-upgrade-shared-prices.joony1024.workers.dev -PdeployedPricesFullCatalog=true
```

최초 운영 Java HTTPS 검증에서 인증 실패 HTTP 401·최대100개 조회를 확인했다. 80종 가격 단계에는 HTTPS 4개 묶음에서 **v2 307종·80가격·227 `NO_PRICE`·FRESH 80종**과 무인증 401을 확인했다. 당시 홈페이지 프록시에서도 중앙 307종·가격 80종을 확인했다. [80종 가격 반영 이력](../../data/catalog-review/remaining232-price-publication-2026-10-10.json), [최초 DB 공통 ID 연결·보존 기록](../../data/catalog-review/all-catalog-central-rollout-2026-10-10.json).

최신 참고가 게시 version은 `b95ed106-8dda-452f-9633-25e52f29ccdf`다. backend 66개·Worker 637개 테스트와 실제 Java HTTPS 검증을 통과했으며 홈페이지 API 전수 확인은 **중앙 308종·판매가 81종·참고가 227종·가격 자료 없음 0종·연결 실패 0종**이었다. 참고가 게시에서는 MySQL을 변경하지 않았다. [최신 게시·재시작 검증 기록](../../data/catalog-review/reference-price-publication-2026-10-10.json).

현재 패키지는 Wrangler 4.149.0이 사용하는 Miniflare 5.20261006.1-alpha와 workerd 1.20261006.1을 고정한다. 테스트에서는 해당 패키지의 공식 `convertV4MiniflareOptions` 어댑터를 사용한다. 로컬 설정의 compatibility date는 이 runtime에 맞춘 2026-10-06이다.

공식 근거: [로컬 실행과 workerd](https://developers.cloudflare.com/workers/local-development/), [Miniflare 테스트](https://developers.cloudflare.com/workers/testing/miniflare/writing-tests/), [Secret과 로컬 개인 파일](https://developers.cloudflare.com/workers/configuration/secrets/), [Workers Web Crypto](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/).

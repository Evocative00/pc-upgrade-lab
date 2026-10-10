# Cloudflare Worker 가격 제공기와 팀 토큰 실행 안내

**현재 운영은 308종 전체에 가격 자료를 제공한다: 확인한 판매가 81종·참고가 227종(모델 5·출시 3·추정 219).** 사용자 승인 후 기존 무료 Worker에 참고가를 게시하고 backend를 재시작했다. 홈페이지 API 전수 확인에서 중앙 308종·로컬 0종·가격 자료 없음 0종·연결 실패 0종이었다. [참고가 게시 검증](../data/catalog-review/reference-price-publication-2026-10-10.json)에 결과를 기록했다. 직전 ASUS 판매상품 추가의 [DB 반영 기록](../data/catalog-review/retail-asus-applied-verification-2026-10-10.json)과 아래 최초 배포·307종 전환 기록은 이력이다.

### 이번 버전의 실행과 팀원 적용

환경변수 3개·운영 URL·팀 토큰은 그대로 사용한다. 업데이트한 backend를 재시작하면 중앙 가격 조회에 승인된 카탈로그 버전을 자동으로 보낸다. 별도의 IntelliJ 실행 구성 변경은 필요 없다. 새 Flyway 마이그레이션도 없다.

팀원의 로컬 DB에 새 판매상품이 아직 없다면 GitHub 코드 업데이트만으로 상품이 생기지는 않는다. 기존 307종과 첫 14종 모델 연결이 준비된 DB에서 아래 미리보기·읽기 전용 검사를 먼저 실행한다. 실제 반영 명령은 해당 로컬 DB 소유자가 승인한 뒤 실행한다. DB 검사·반영에는 기존 개인 DB 설정과 `DB_PASSWORD`가 필요하며 OAuth 키는 필요 없다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importRetailCatalog
.\backend\gradlew.bat --project-dir .\backend importRetailCatalog -PcheckRetailDb=true
.\backend\gradlew.bat --project-dir .\backend importRetailCatalog -PapplyRetailCatalog=true
```

반영 후 평소 backend를 재시작한다. 동일 자료 재실행은 추가로 쓰지 않으며, 일부만 들어 있거나 기존 자료가 다르면 중단한다. 일반 `bootRun`·pull·test는 이 상품을 자동 적재하지 않는다.

기존 backend의 조회를 유지하기 위해 `/api/v2/prices`는 `X-Catalog-Version` 헤더가 없거나 기존 307종 버전이면 **307종·80가격**, 새 승인 버전이면 **308종·81가격**을 제공한다. 최신 Java client가 이 헤더를 자동 지정한다. 알 수 없는 버전은 409, 잘못된 헤더는 400으로 거절한다. v1 **14종·8가격**도 유지한다. 새 승인 자료는 `approved-retail-extension-2026-10-10.json`과 별도 `generated/catalog-retail-approved.json`에 있으며 기존 307종 식별·80종 가격 원본은 유지한다. 새 자료 생성은 `exportApprovedRetailCatalog`를 사용한다.

가격 자동 수집은 아직 없다. 새로고침은 승인된 관측값을 다시 조회하며, 관측 후 48시간 경고·7일 합계 제외 정책은 같다.

직전 ASUS 추가 단계에서 관련 backend 106개(101개 + 적용 전용 5개), Worker 572개, Java HTTP 참조 응답 1,532개, 로컬·실제 HTTPS Java client 검증이 통과했다. 실제 MySQL 31개 테이블의 기존 행은 보존됐고 승인한 6개 테이블에 새 상품 관련 행이 각각 1개 추가됐다. 동일 자료 재실행은 0쓰기였다. 재시작 시 Flyway V14를 검증하고 새 마이그레이션 없이 시작했다. 당시 홈페이지 프록시 API 전수 확인 결과 중앙 308종·가격 81종·미확인 227종·로컬 0종·연결 실패 0종이었다. 새 상품은 기존 ASUS 설치 모델과 같은 modelId를 사용한다. 실제 브라우저 수동 검수·OAuth 로그인·전체 backend 테스트 재실행은 그 단계에서 하지 않았다. GitHub 업로드도 하지 않았다.

2026-10-10. 사용자가 승인한 **Worker 제공기·팀용 토큰 연동·로컬 검증·Workers Free 실제 배포**를 완료했다. 운영 주소는 **https://pc-upgrade-shared-prices.joony1024.workers.dev**다. 구현 전 완료 작업은 GitHub `dev`의 [`8d02f94`](https://github.com/Evocative00/pc-upgrade-lab/commit/8d02f94d996cb372e1bfa7b6899c3362244b3c89)에 저장했다. 아래 Worker·토큰·운영 검증 추가분은 아직 로컬 변경이며 GitHub에 추가 업로드하지 않았다.

## 제공 범위와 자료 생성

**참고가 확장 게시 완료:** 기존 현재가 81종을 보존하고 남은 227종의 독립 `GET /api/v1/reference-prices` 경로를 게시했다. 승인본 `active-reference-prices.json`을 우선 사용하며 검토 원본은 미승인 상태로 보존한다. 미승인 자료를 별도로 사용하는 검증에서는 가짜 토큰과 명시적 로컬 설정만 허용한다. v1/v2 현재가 계약·307/308 버전 분기·48시간/7일 기준은 유지한다. 환경변수 세 개와 팀 토큰은 그대로다. 가격 의미·합계·계산 근거는 [참고가 227종 게시 안내](catalog-current-prices.md#참고가-227종-게시-완료)를 따른다. 게시용 backend 66개·Worker 637개와 실제 HTTPS Java 검증이 통과했으며 MySQL 변경은 없었다.

`workers/catalog-prices/`는 기존 Java backend와 별개의 작은 TypeScript 제공기다. `GET /api/v2/prices?canonicalIds=공통UUID,...` 계약으로 승인된 **307종·가격 80종·가격 미확인 227종**을 제공한다. 최초 75종은 재관측한 기존 72종·보존한 SSD 2종·Dell S2721DGF 976,500원 1종이며, 이후 승인한 CPU 3종·GPU 1종·보드 1종의 가격을 추가했다. 기존 `GET /api/v1/prices`는 **14종·가격 8종·가격 미확인 6종**, 자료 버전 `pilot-2026-10-10-28cc2bacb534dcc2-3028116ce4cae9ad`를 유지한다. RAM 판매 단위, 원 금액·출처·`observedAt`을 보존하고 요청마다 UTC 응답시각과 관측 후 48시간 경고·7일 만료를 계산한다.

Java의 `SharedCatalogSnapshot`이 식별·가격 근거를 검사한 뒤 `exportSharedCatalog`가 공개 산출물을 생성한다.

- `generated/catalog.json`: 상품 식별·승인 가격·정책·버전. Worker 코드에 포함하며 별도 DB나 저장소를 사용하지 않는다.
- `generated/contract-fixtures.json`: 실제 Java HTTP 제공기를 고정 시각으로 실행해 받은 응답. 전체 14종, 가격 없음, 미등록, 100개 최대 요청과 101개 거절, 각 가격의 48시간·7일 전후 경계를 비교한다.
- `generated/catalog-v2.json`·`contract-fixtures-v2.json`: 전체 307종과 승인 80종, 독립 `catalogVersion`·`priceVersion` 및 같은 정책의 Java 참조 응답이다.

이 파일들은 공개 검증 자료로 Git에 포함할 수 있다. 직접 편집하지 않고 원 승인 자료를 통해 생성한다. 기본 export·`verifyWorkerCatalog`는 `data/catalog-shared/active-approved-prices.json`의 승인 80종을 사용한다. 이 파일은 승인 묶음과 바이트가 동일하며, 없거나 잘못되면 과거 가격으로 되돌아가지 않고 실패한다. 최초 `approved-prices-2026-10-10.json` 74종은 원본 대조와 backend의 검증 자료로 보존한다. 일반 backend 시작·pull·test가 export나 실제 DB 적재를 자동 실행하지 않는다.

**가격 자동 수집·갱신은 여전히 없다.** v2는 제품 식별 버전과 관측 가격 버전을 분리했다. 이후 새 가격 묶음을 검토·승인하고 중앙 자료를 갱신해 Worker를 재배포하면, 동일한 제품의 가격 변경·미확인 가격 추가는 backend를 가격마다 수정하지 않아도 조회된다. 배포와 화면 새로고침은 관측시각을 현재로 바꾸지 않으며, 관측이 7일 지나면 합계에서 제외한다. 정기 수집·자동 배포는 아직 구성하지 않았다.

## 토큰과 backend 실행 구성

Worker는 `SHARED_PRICES_API_TOKEN`이라는 Secret을 요구한다. 토큰은 무작위 32바이트를 64자리 hex로 표현한 값이며 정확한 `Authorization: Bearer <token>` 헤더만 허용한다. Secret 누락·잘못된 형식은 HTTP 503, 인증 실패는 HTTP 401이다. 오류 응답은 토큰·입력값을 반사하지 않는다. 요청마다 외부 서비스·DB를 호출하지 않는다.

이 토큰은 우리 가격 API의 팀 비밀값이며 Cloudflare 계정 관리용 API 토큰과 별개다. 실제 배포 승인 뒤 운영 값을 생성해 Worker Secret에 등록했다. 로컬 원본은 `workers/catalog-prices/.env.production-secrets.local`, backend 환경변수 복사용 개인 파일은 `workers/catalog-prices/.env.backend-prices.local`이다. 두 파일은 Git에서 제외하며 값은 출력하지 않았다. backend용 파일에는 아래 세 환경변수의 실제 값이 들어 있다. 팀원·다른 기기에는 같은 토큰을 개인적으로 전달하며 GitHub pull로 공유하지 않는다.

**기존 backend 실행 구성은 그대로 사용할 수 있다.** 중앙 조회 기본값은 꺼짐이다. 기존 MySQL·JDK 21·`SPRING_PROFILES_ACTIVE=local`·DB/OAuth 설정을 보존한다. 새 개인 파일을 덮어쓰지 않는다.

Worker에 연결할 때만 **backend 실행 구성**에 다음 세 환경변수를 추가한다.

```text
CATALOG_SHARED_PRICES_ENABLED=true
CATALOG_SHARED_PRICES_BASE_URL=https://pc-upgrade-shared-prices.joony1024.workers.dev
CATALOG_SHARED_PRICES_API_TOKEN=<운영 Worker Secret과 같은 64자리 hex 토큰>
```

IntelliJ의 기존 `backend [bootRun]` 실행 구성의 **환경변수**에 위 세 값을 추가하고 재시작한다. 이미 설정했다면 값은 그대로 사용한다. 실제 값은 개인 `.env.backend-prices.local`에서 본인이 복사한다. 파일이 존재하는 것만으로 Spring이 읽지는 않는다. 기존 `JAVA_HOME`·JDK 21·`SPRING_PROFILES_ACTIVE=local`·DB_PASSWORD·OAuth 설정은 유지한다. 최초 307종 전환 때 로컬 backend를 재시작했고 Flyway V14까지의 기존 구조를 확인했으며 새 마이그레이션은 없었다. 이번 5종 가격 갱신에서는 재시작이나 설정 변경 없이 운영 조회에 반영했다. 토큰은 Worker와 같은 값을 사용한다. 중앙 HTTPS 조회를 켜면 유효한 토큰이 필수이며, 기존 Java loopback 제공기에 연결할 때만 빈 토큰을 허용한다. 중앙 조회를 끈 경우 토큰 설정을 요구하지 않는다. 로컬 Worker 검수에는 base URL을 `http://127.0.0.1:8787`과 해당 로컬 토큰으로 바꾼다.

개인 `application-local.properties`를 사용하는 경우 대응 속성은 다음과 같다. 환경변수 방식을 권장한다. `.env`를 만들기만 해서는 Spring backend가 읽지 않는다.

```properties
catalog.shared-prices.enabled=true
catalog.shared-prices.base-url=https://pc-upgrade-shared-prices.joony1024.workers.dev
catalog.shared-prices.api-token=<개인 토큰>
```

frontend 설정·`VITE_*`·프록시는 바꿀 필요가 없다. frontend는 평소 로컬 backend만 호출한다. 토큰을 Git·화면 응답·쿼리·로그·채팅에 넣지 않는다. client는 origin URL만 허용하고 리다이렉트를 따르지 않으며, 기본 2초·64KiB 제한과 기존 장애 캐시·합계 제외 동작을 유지한다.

팀원·다른 기기의 DB에도 **기존 307종 준비와 공통 ID 연결을 위한 `importAllSharedIdentities`의 일회성 명시 적용**이 필요하다. pull·일반 backend 시작·build/test는 이 DB 작업을 실행하지 않는다. 최초 공통 ID 연결은 기존 제품·제원·가격·PC 연결·분류를 보존했으며 가격·제품·모델 생성은 0건이다. 중앙 가격 80종을 로컬 MySQL에 복사하는 작업은 아니다. 이번 5종 가격 추가는 DB에 연결하지 않았다. 준비와 읽기 전용 사전검사·명시 적용은 [전체 전환 안내](catalog-all-shared-prices-2026-10-10.md)를 따른다.

## DB·Cloudflare 계정 없는 로컬 검증

Node.js 24와 JDK 21을 사용하고 `JAVA_HOME`·IDE Gradle JVM을 JDK 21로 맞춘다. Gradle/Vite/Wrangler 전역 설치는 필요 없다. 프로젝트 루트 PowerShell에서 실행한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend exportSharedCatalog
.\backend\gradlew.bat --project-dir .\backend verifyWorkerCatalog
```

이 두 작업은 Spring·Flyway·JDBC·MySQL·OAuth를 시작하지 않는다. 공개 산출물을 생성하거나 대조하고 Java 참조 HTTP 서버는 임의 loopback 포트에서 검증 후 종료한다.

Worker 검증 명령은 `workers/catalog-prices/package.json`을 기준으로 한다. 별도 package lock으로 의존성을 고정하고 실제 `workerd` 런타임에서 인증·응답 계약을 검사한다. 통합 검사에서는 임의 loopback 포트와 합성 테스트 토큰으로 Java client가 Worker를 직접 조회한 뒤 서버를 종료한다. Cloudflare 로그인·배포·원격 미리보기·클라우드 Secret 설정은 실행하지 않는다.

```powershell
Set-Location workers/catalog-prices
npm.cmd ci
npm.cmd run typecheck
npm.cmd run build
npm.cmd test
npm.cmd run test:java-client
npm.cmd run test:wrangler
```

`test:wrangler`는 실제 로컬 Wrangler 설정·401·로그의 Secret 숨김을 확인한다. 기존 `.dev.vars`가 있으면 읽거나 덮어쓰지 않고 중단한다. 파일이 없을 때만 임시 합성 값을 배타적으로 만들고, 실패 경로까지 자신의 임시 파일·프로세스를 정리한다. 개인 파일이 이미 있으면 `npm test`와 본인의 수동 실행을 사용한다.

버전은 Wrangler `4.149.0`, Miniflare `5.20261006.1-alpha`, workerd `1.20261006.1`, TypeScript `6.0.2`, esbuild `0.28.2`로 고정했다. Wrangler가 사용하는 공식 Miniflare 버전과 맞추고 v5가 제공하는 옵션 변환 어댑터를 사용한다. 번들·검사·로컬 실행에는 계정과 실제 팀 토큰이 필요 없다. `npm run build`는 로컬 esbuild 번들 생성이며 클라우드 업로드 명령이 아니다.

## 직접 실행할 때

Worker의 로컬 Secret은 `workers/catalog-prices/.dev.vars`에만 저장한다. 이 파일과 변형·캐시·빌드 결과는 Git에서 제외한다. 값은 Wrangler 설정의 `vars`에 적지 않는다. [공식 Secret 로컬 설정](https://developers.cloudflare.com/workers/configuration/secrets/)

Worker 디렉터리에서 새 **로컬 검수용** 토큰을 만들 때 다음 명령은 값을 화면에 출력하지 않는다. 기존 `.dev.vars`가 있으면 보존하고 재생성하지 않는다. 배포된 중앙 API를 사용할 때는 이미 준비한 운영 개인 파일의 팀 토큰을 사용한다.

```powershell
if (Test-Path -LiteralPath .dev.vars) { throw '기존 .dev.vars를 보존하세요.' }
$workerLocalToken = node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('hex'))"
if ($LASTEXITCODE -ne 0 -or $workerLocalToken -cnotmatch '^[0-9a-f]{64}$') { throw '토큰 생성 실패' }
[System.IO.File]::WriteAllText((Join-Path (Get-Location) '.dev.vars'), "SHARED_PRICES_API_TOKEN=$workerLocalToken`n")
```

이 값은 backend 실행 구성에 개인적으로 전달한다. 작업 로그나 채팅으로 보내지 않는다. 로컬 서버는 loopback에만 열고, backend의 선택 설정과 같은 토큰으로 연결한다. 평소 backend를 직접 시작하는 것은 기존 Flyway 동작을 포함하므로 본인의 준비된 MySQL 환경에서 수행한다. 이번 자동 검증은 평소 backend를 시작하지 않는다.

```powershell
npm.cmd run dev
```

이 명령은 명시적 `wrangler dev --local`로 `http://127.0.0.1:8787`을 연다. 종료는 Ctrl+C다. 가격 조회·인증 실패·만료·장애 화면의 직접 확인 순서는 [기존 공유 가격 안내](catalog-shared-prices-2026-10-10.md)의 검수 순서를 따른다. 제공기만 Worker로 바꾸고 backend 토큰을 추가한다.

## 최초 v1 검증과 배포 기록

2026-10-10 최초 14종 v1 검증 결과:

- `exportSharedCatalog`·`verifyWorkerCatalog`: 상품 14종·가격 8종·실제 Java HTTP 참조 53개 생성과 일치 검사 통과. 첫 검증에서 Windows pretty printer의 CRLF 비교 차이를 발견해 생성 문자열도 LF로 통일한 뒤 재검증했다.
- backend 공유 기능 **27개 통과**(기존 19개·토큰 8개), 실패·오류·건너뜀 0. 개인 환경 토큰이 테스트 서버로 전송되지 않도록 격리한 뒤 관련 9개를 다시 검사했고 통과했다. 전체 backend 371개 검사는 이전 체크포인트에서 통과했으며 이번 변경에서는 공유 범위만 실행했다. 최종 backend 테스트 보고서는 마지막 9개 재검증 결과다.
- Worker `npm test`: 실제 Miniflare/workerd **62개 통과**(Java 참조 53개·추가 9개). 48시간/7일 경계, 100개 최대 요청, 미등록/가격 없음, 인증·잘못된 요청·원 관측 보존을 포함한다. 테스트용 시각 헤더는 실제 제공기 번들에 없음을 확인했다.
- `npm run typecheck`·`npm run build`: 통과. 로컬 번들 **8,146 bytes**, 업로드 없음.
- `npm run test:java-client`: 실제 Java client가 loopback Worker에 토큰으로 연결해 **14종·가격 8종·미확인 6종**, 동일 버전·상품 구성·금액/출처/관측시각·신선도 검증 통과. DB 접근 없음.
- `npm run test:wrangler`: 실제 `wrangler dev --local` 설정으로 14종/8가격, 미인증 HTTP 401, 토큰 로그 숨김 통과. 정리 경로 보강 후 다시 통과했고 임시 `.dev.vars` 잔존 없음.
- 개인 설정과 실제 토큰은 변경하거나 출력하지 않았다. 이번 단계에는 frontend 수정이 없어서 검사를 반복하지 않았다. 이전 체크포인트의 frontend 134개·lint·build는 통과했다.

변경 범위의 whitespace·Secret/빌드 파일 Git 제외를 확인했다. 기존 사용자 README·Vite 변경은 보존했다. **최초 v1 로컬 검증 당시에는 실제 MySQL·일반 bootRun/Flyway·두 실제 기기 연결을 실행하지 않았다.** Cloudflare 로그인·운영 토큰 등록·최초 배포·Java HTTPS 검증은 아래 승인 후 결과로 구분한다. 이후 v2의 MySQL 적용·재시작 결과는 문서 마지막에 따로 기록했다. 로컬 `workerd` 응답·타입 검사·번들 검사를 실제 Cloudflare 응답이나 무료 플랜의 CPU 계측으로 기록하지 않는다.

실제 배포 승인 범위는 **사용자 소유 Cloudflare 계정의 Workers Free·제공기 1개·기본 `workers.dev` HTTPS·팀 Secret 1개·현재 승인 자료**였으며 이 범위대로 배포했다. 인프라 비용은 무료 한도 내 월 $0이다. 유료 플랜·자체 도메인·DB·KV/D1/R2·Cron·Containers를 추가하지 않았다. 계정 관리 콘솔의 Workers plans에서 Free / $0 / Current plan을 확인했다. 두 실제 기기의 동일 조회는 사용자의 다음 검수 항목이다. [무료 요금과 한도](https://developers.cloudflare.com/workers/platform/pricing/)

현재 Wrangler의 `secret put`도 즉시 배포를 일으키므로 배포 승인 전 실행하지 않는다. [Secret 배포 동작](https://developers.cloudflare.com/workers/configuration/secrets/)

### 최초 v1 실제 배포 승인 후 진행 상태

사용자가 검증 결과를 확인하고 위 무료 배포 범위를 승인했다. 2026-10-10 최초 Wrangler 연결 요청은 시간 초과됐으나 새 요청 뒤 `whoami --json`의 `loggedIn=true`로 로그인·연결 허용 완료를 확인했다. 브라우저의 `ERR_CONNECTION_REFUSED` 화면만으로 인증 실패를 판단하지 않는다. 이후 본인이 가입 이메일 인증을 완료했고 대시보드의 인증 요구가 사라졌다.

읽기 전용 사전 검사에서 같은 이름의 서비스 조회는 HTTP 404·Cloudflare 코드 `10090`으로 기존 서비스가 없음을 확인했다. Workers 계정 설정 조회는 성공했으나 `default_usage_model=standard` 자체를 무료 플랜의 근거로 사용하지 않는다. 가입 이메일 인증 뒤 대시보드 **Workers plans → Free / $0 / Current plan**으로 무료 플랜을 명시적으로 확인했고, Workers & Pages의 계정 주소는 **joony1024.workers.dev**다. 결제·유료 플랜 전환은 하지 않았다.

구독 조회의 HTTP 403은 Billing 권한 제한이며 무료 플랜 여부는 위 대시보드로 확인했다. workers.dev 주소 API의 HTTP 403은 초기 OAuth에 `workers_scripts:write`를 누락한 권한 문제였다. 설치된 Wrangler 4.149와 [공식 subdomain 조회 권한](https://developers.cloudflare.com/api/resources/workers/subresources/subdomains/methods/get/)·[등록 권한](https://developers.cloudflare.com/api/resources/workers/subresources/subdomains/methods/update/)을 대조했다. `workers:write` 설명만으로 해당 API 권한이 충분하다고 판단하지 않는다. 최초 권한 확대 시도는 자동 승인 검토가 차단했으며, 사용자가 필수 Workers Scripts 권한 추가를 명시적으로 승인하고 새 연결 창에서 Allow를 직접 완료했다. 최종 OAuth 요청 범위는 `account:read user:read workers_scripts:write workers_tail:read`이며, 이후 subdomain 조회가 HTTP 200으로 성공했다.

계정 인증 뒤 Workers Free·동일 이름 서비스 없음·기본 workers.dev 주소를 읽기 전용으로 확인했다. 첫 업로드는 Wrangler `deploy --config wrangler.jsonc --strict --minify --secrets-file .env.production-secrets.local`로 코드와 Secret을 함께 반영했다. 선택 계정은 프로세스의 `CLOUDFLARE_ACCOUNT_ID`로만 지정했다. 계정 관리용 인증과 팀 조회 토큰은 별개다.

실제 배포 결과는 다음과 같다.

- 배포 시각: **2026-10-10 15:41:35 KST** (`2026-10-10T06:41:35.148313Z`).
- 운영 origin: **https://pc-upgrade-shared-prices.joony1024.workers.dev**. 루트 주소를 브라우저로 열어서는 인증된 가격 조회를 할 수 없으며 로컬 backend가 팀 토큰 헤더로 API 경로를 조회한다.
- Cloudflare version: `b32e13a6-1f38-468d-8490-a84f2726259a`, deployment: `371d9396-7763-459d-adb1-1575a8ba9692`, 트래픽 100%, 대시보드 Ready.
- 설정 API: compatibility date `2026-10-06`, Secret 바인딩 `SHARED_PRICES_API_TOKEN` 1개, workers.dev 활성화, preview URL 비활성화. 별도 데이터 저장소 바인딩 없음.
- 실제 Java HTTPS client: **14종·8가격·NO_PRICE 6종**, 고정 버전·구성·원 금액/출처/관측시각·정책 검증 통과. 인증 누락·오류 각각 HTTP 401, 최대 100개 조회(미등록 86개 포함)·`no-store`·`nosniff` 검증 통과. DB 접근 0.
- Cloudflare 대시보드 초기 **5회** 지표: 계정 CPU 합계 **6.2ms**, 활성 버전 CPU 중앙값 **1.19ms**, CPU P99 **2.24ms**, 실행 오류·CPU 한도 초과 **0건**, subrequest **0건**. 무료 10ms CPU 한도 안에서 실제 최대100개 조회도 성공했다. 소규모 초기 표본이며 장기 사용·동시 접속 부하 검증으로 해석하지 않는다. 로그·trace 저장은 비활성 상태다.
- 공개 승인 자료의 8종 관측은 2026-10-10 01:27:54.952~01:29:31.430 KST다. 배포 시점에는 8종 모두 FRESH이며 배포가 관측시각을 갱신하지 않았다. 새 승인 관측이 없으면 10월 12일 해당 시각부터 경고, 10월 17일 해당 시각부터 합계 제외다.

명시적 `verifyDeployedPrices` 작업은 위 운영 URL로 실행해 **BUILD SUCCESSFUL**을 확인했다. 이 작업은 승인된 `pc-upgrade-shared-prices.<계정주소>.workers.dev` origin과 개인 환경 토큰을 요구한다. 일반 test/build에서는 실행하지 않고 Spring·DB·Flyway를 시작하지 않는다.

```powershell
.\backend\gradlew.bat --project-dir .\backend verifyDeployedPrices -PdeployedPricesBaseUrl=https://pc-upgrade-shared-prices.joony1024.workers.dev
```

명령에 토큰을 넣지 않는다. 실제 `CATALOG_SHARED_PRICES_API_TOKEN`은 개인 실행 환경에서만 제공한다. 계정 인증을 본인의 터미널에서 할 경우 설치된 Wrangler를 사용하며, 다음 명령 자체는 배포를 하지 않는다.

```powershell
Set-Location workers/catalog-prices
node node_modules/wrangler/bin/wrangler.js login --scopes account:read user:read workers_scripts:write workers_tail:read
```

## 최초 v2 전체 전환 기록 (75종 가격)

사용자가 전체 307종 중앙 조회·승인 75종 가격·로컬 공통 ID 293개 연결·Workers Free 재배포·backend 재시작을 승인한 뒤 완료했다.

- 재배포 시각: **2026-10-10 18:25:45 KST** (`2026-10-10T09:25:45.216455Z`). 운영 origin과 팀 Secret은 기존과 같다.
- Cloudflare version: `71504364-1cef-40ec-9bff-96a584991895`, deployment: `2065fb60-1bf7-408b-b310-22b01dd0ea1b`.
- 실제 Java HTTPS 검증: **v2 307종·75가격·232 `NO_PRICE`**, 인증 실패 HTTP 401·최대100개 조회 통과. v1 호환은 14종·8가격을 유지한다.
- 홈페이지 `5173/api` 프록시의 실제 조회: `SHARED=307`, `OK=75`, `NO_PRICE=232`, `FRESH=75`, `LOCAL=0`, `UNAVAILABLE=0`, 합계 포함 75종. Dell 상세의 976,500원·원 관측시각 `2026-10-10T08:41:21.151Z`도 확인했다.
- 실제 MySQL: 기존 293종에 공통 ID·검토 기록만 연결했다. 29개 테이블 보존 대조로 제품 ID·제원·기존 가격·PC 연결·분류와 pilot 14종 보존을 확인했다. 가격 쓰기·제품 생성·모델 생성은 0건이다.
- backend 재시작 완료. Flyway V14까지 기존 구조가 유지됐으며 새 스키마 변경은 없다. 중앙 조회 환경변수 세 개와 개인 DB/OAuth 설정을 유지했다.

실제 적용·보존·조회 결과는 [운영 반영 보고서](../data/catalog-review/all-catalog-central-rollout-2026-10-10.json)에 저장했다. 기본 active 가격 선택 관련 `SharedFullCatalogTests` 12개도 실패 없이 통과했다.

## 승인한 5종 가격 갱신 결과

- 재배포 시각: **2026-10-10 19:38:44 KST** (`2026-10-10T10:38:44.649971Z`). 기존 Workers Free·origin·팀 Secret과 트래픽 100%를 유지했다.
- Cloudflare version: `a12e2ae2-4431-4024-bd9b-7f72e11f1563`, deployment: `63016ff8-d994-4152-8034-4b3c4d914385`.
- 기존 75종 가격과 307종 식별 정보를 보존하고 CPU 3종·GPU 1종·보드 1종의 승인 가격을 추가했다. `catalogVersion`은 그대로이며 `priceVersion`만 `prices-v1-7262b6764824fb79d2c8ea952808e814637558c30749366f923b3d4c791167bc`로 변경했다.
- Java 자료 생성·대조(v1 기준 응답 53개·v2 491개), Worker **554개 테스트**·TypeScript 검사·번들 생성 통과. 운영 HTTPS 4개 묶음에서 **307종·80가격·227 `NO_PRICE`·FRESH 80종**, v1 14종·8가격과 무인증 401을 확인했다.
- 홈페이지 `5173/api` 프록시 7페이지·추가 5종 상세 검증: `SHARED=307`, `OK=80`, `NO_PRICE=227`, `FRESH=80`, `LOCAL=0`, `UNAVAILABLE=0`, 합계 포함 80종.
- **DB 쓰기·제품/제원 변경·환경변수/팀 토큰 변경·backend 재시작 0건.** backend 전체 테스트 재실행과 브라우저 UI 직접 검수는 하지 않았다.

[5종 승인·운영 반영 보고서](../data/catalog-review/remaining232-price-publication-2026-10-10.json), [232종 전체 조사 판정](../data/catalog-review/remaining232-price-review-2026-10-10.json). 이미 중앙 연결을 완료한 기기는 화면에서 가격을 새로 조회하면 된다.

전체 운영 계약을 다시 확인하는 명시적 읽기 전용 명령은 다음과 같다. 개인 환경 토큰이 필요하며 DB·Spring·Flyway를 시작하지 않는다.

```powershell
.\backend\gradlew.bat --project-dir .\backend verifyDeployedPrices -PdeployedPricesBaseUrl=https://pc-upgrade-shared-prices.joony1024.workers.dev -PdeployedPricesFullCatalog=true
```

현재 227종의 `NO_PRICE`는 중앙 조회에 성공했지만 정확한 판매 상품 가격이 아직 등록되지 않은 상태다. 기존 로컬 가격으로 대체하지 않으며 합계에 넣지 않는다. 후속 가격 후보의 승인·정기 수집과 두 실제 기기의 화면 검수는 별도 작업이다.

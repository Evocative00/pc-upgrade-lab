# 프로젝트 작업 안내

## 구조와 기준
- `frontend/`: React 19·TypeScript·Vite 8. 기능 코드는 `src/features/`, 테스트는 `tests/`.
- `backend/`: Java 21·Spring Boot 4.1.1·JPA·Flyway. Gradle Wrapper 9.7.1을 사용한다.
- `collector/windows/`: Windows PowerShell 수집기. 스크립트의 UTF-8 BOM을 유지한다.
- `docs/README.md`에서 문서를 찾고, 실행은 `docs/week1-setup.md`, PC 규격은 `docs/week1-contract.md`, 인증은 `docs/week2-auth-pc-contract.md`와 제공자별 `docs/week2-{google,kakao,naver}-login-setup.md`, 상품가 준비는 `docs/catalog-current-prices.md`를 확인한다.
- 의존성과 명령은 `frontend/package.json`, `package-lock.json`, `backend/build.gradle`, Wrapper 설정이 기준이다. 루트·하위 README의 초기 설명과 코드가 다르면 현재 코드와 관련 docs를 대조한다.

## 개발환경과 검증
- Node.js 24.x·npm·JDK 21이 필요하다. `JAVA_HOME`과 IDE의 Gradle JVM도 JDK 21로 맞춘다. Gradle/Vite 전역 설치는 필요 없다.
- PowerShell에서 프론트엔드 검증:
  ```powershell
  cd frontend
  npm.cmd ci
  npm.cmd test
  npm.cmd run lint
  npm.cmd run build
  ```
- 프로젝트 루트에서 백엔드 일반 테스트:
  ```powershell
  .\backend\gradlew.bat --project-dir .\backend test
  ```
  새 실행 결과가 필요하면 `--rerun-tasks`를 추가한다. 일반 `test`/`build`는 H2를 사용하도록 구성되어 MySQL·DB 비밀번호가 필요 없다. `mysqlTest`는 별도 작업이다. 결과는 `backend/build/reports/tests/test/index.html`에서 확인한다.
- `local` 기능이 필요한 H2 테스트는 `@ActiveProfiles({"local", "test"})`처럼 `test`를 마지막에 적용한다. 순서가 반대면 개인 local 설정의 MySQL URL이 H2 URL을 덮어쓸 수 있다. 개인 설정을 지워서 우회하지 않는다.
- 프론트 개발 서버는 `npm.cmd run dev`, 고정 포트 5173(`strictPort: true`). `/api`, `/oauth2`, `/login/oauth2`를 `127.0.0.1:8080`으로 프록시한다. 소셜 로그인은 `http://127.0.0.1:5173`을 사용한다.
- 실제 서버 실행에는 MySQL 8.4, 개인 `backend/src/main/resources/application-local.properties`, `SPRING_PROFILES_ACTIVE=local`, `DB_PASSWORD`가 필요하다. 개인 파일이 없을 때만 예제를 복사하고 기존 설정은 보존한다. `.env`는 자동으로 읽히지 않는다. 카탈로그 식별·저장장치 구조를 추가하는 V13·V14는 일반 `bootRun` 시작 시 Flyway로 적용된다. 기존 JDK 21·local·DB_PASSWORD·사용 중인 OAuth 설정을 유지하며 이번 변경용 새 환경변수나 실행 구성은 필요 없다. 후보 78종이나 모델·슬롯 자료를 자동 적재하지 않는다.
- 소셜 로그인은 제공자별 개인 OAuth 설정과 `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`, `KAKAO_CLIENT_ID`/`KAKAO_CLIENT_SECRET`, `NAVER_CLIENT_ID`/`NAVER_CLIENT_SECRET` 중 해당 키가 필요하다. `CLIENT` 철자를 지킨다. 콜백은 `http://127.0.0.1:5173/login/oauth2/code/{google|kakao|naver}`이며 개발자 콘솔에 정확히 등록한다. 실제 로그인에서는 `DEV_USER_ID`를 설정하지 않고 개발 헤더도 끈다. 개인 properties·IntelliJ 실행 구성·환경변수는 pull로 공유되지 않는다.
- 상품가도 각 PC의 DB 데이터이며 pull이나 화면 새로고침으로 적재되지 않는다. 기존 300종 카탈로그와 V14까지 준비된 DB에서 `importCatalogPrices`는 검토한 72종 스냅샷을 기본 미리보기로 검사하고, `-PapplyPrices=true`를 명시할 때만 반영한다. 프로젝트 루트 명령은 `.\backend\gradlew.bat --project-dir .\backend importCatalogPrices`다. 개인 DB 설정과 실행 환경의 `DB_PASSWORD`는 필요하지만 OAuth 키는 필요 없다. 이 작업은 웹 서버·Flyway·다른 seed를 실행하지 않는다. 최신 코드로 처음 실행하거나 기존 DB가 V14 이전이면, 승인된 DB 변경 범위에서 일반 `bootRun`을 재시작해 최신 마이그레이션을 적용한 뒤 가져오기를 실행한다. JPA 검증은 가격 테이블 외 식별·저장장치 테이블도 요구한다.
- 카탈로그가 비었거나 미완성이면 별도 `setupDevCatalog` 작업으로 승인된 300종·호환 근거·가격 72종을 준비한다. 기본 실행은 DB·`DB_PASSWORD`·OAuth 키 없이 자료를 검증하는 미리보기다. `-PapplyDevCatalog=true`를 명시할 때만 V14까지 준비된 로컬 DB에 연결해 한 트랜잭션으로 반영하며 이때는 개인 DB 설정과 `DB_PASSWORD`가 필요하다. 두 경로 모두 OAuth 키와 웹 서버·Flyway 실행은 필요 없다. 처음 만든 DB와 V14 이전의 기존 DB는 실제 반영 전에 개인 DB 설정으로 일반 `bootRun`을 재시작해 최신 마이그레이션을 적용한다. pull·일반 `bootRun`·build/test가 카탈로그나 가격을 자동 적재하게 만들지 않는다.
- 첫 14종 보강은 별도 `importPilotCatalog` 작업을 사용한다. 기본 실행은 DB 없는 자료 검증이며, `-PcheckPilotDb=true`는 읽기 전용 실제 DB 대조, `-PapplyPilotCatalog=true`는 승인된 신규 7종·모델 13개·상품 연결 14종·부분 슬롯 19개·가격 관측 8종의 단일 트랜잭션 반영이다. DB 경로에는 기존 300종·호환 근거·가격 72종과 V14, 개인 DB 설정·실행 환경의 `DB_PASSWORD`가 필요하며 OAuth·웹 서버·Flyway·다른 seed는 실행하지 않는다. 같은 관측의 재실행은 중복을 만들지 않는다. 상품을 자동 활성화하거나 미확인 SKU 가격을 생성하지 않는다. 실행법과 보류 근거는 `docs/catalog-pilot-review-2026-10-10.md`를 따른다.

## 변경 규칙과 작업 범위
- 시작과 종료에 브랜치·`git status`를 확인하고 기존 미커밋 변경을 보존한다. 요청 없이 브랜치를 전환하거나 커밋·push하지 않는다.
- 기본 환경 점검은 의존성 설치와 위 검증까지다. 실제 DB 변경(`importCatalogPrices -PapplyPrices=true`, `setupDevCatalog -PapplyDevCatalog=true`, `importPilotCatalog -PapplyPilotCatalog=true` 포함), `mysqlTest`, `Verify-*.ps1`, seed 실행, 수집기 설치·삭제는 명시적으로 요청된 경우에만 수행한다. `bootRun`도 시작 시 Flyway로 실제 DB를 변경할 수 있다.
- 적용된 Flyway 마이그레이션은 수정하지 않는다. 스키마 변경은 다음 버전 파일을 추가하며 `clean`, 임의 `repair`, DB 초기화를 하지 않는다.
- 개인 설정·비밀번호·OAuth 비밀키·스캔 토큰을 출력하거나 저장소에 추가하지 않는다. `node_modules`, `dist`, `build`, 캐시도 커밋 대상이 아니다.
- PC 소유자는 서버의 `CurrentUser`로 정한다. `/api/pcs/**`는 로그인 필요, 다른 회원의 PC는 404다. CSRF와 세션 규칙을 유지하고 로그인 후 초안을 자동 저장하지 않는다.
- `PartInput`/`ScanDtos`, 프론트 타입, 수집기와 규격 문서를 함께 맞춘다. `quantity`는 장치 개수, `capacityBytes`는 장치 하나의 bytes 용량이다. 미확인은 `null`, `UNMATCHED`는 카탈로그 미연결이다. RAM·저장장치를 임의로 합치거나 이름만으로 제품을 확정하지 않는다.
- 자동 수집 결과는 DB 저장과 별개다. 폼 반영 시 `MANUAL` 항목을 보존하고 편집된 `AUTO` 값을 덮어쓸 때 확인 흐름을 유지한다.
- 변경 범위에 맞는 검증을 실행하고 성공·실패·미실행 및 남은 로컬 설정을 구분해 보고한다. H2·자동 테스트 통과를 실제 MySQL·Google 로그인·Windows 수집기 통합 검증으로 기록하지 않는다.

# 1주차 실행·팀원 개발 시작 안내

최초 1주차 안내 기준: 2026-09-24, PR #3·#4가 반영된 dev 커밋 `8563c15d592dde46f61c9b6618da0bc9c46b2852`.
팀원별 로그인·상품가 준비 안내 추가: 2026-10-08. 아래 실행 방법은 현재 `dev`의 코드와 설정을 기준으로 한다.
Java 21, 프로젝트 Gradle Wrapper 9.7.1, Spring Boot 4.1.1, Node.js 24, React·TypeScript·Vite, MySQL 8.4를 사용한다.

현재 dev에는 PC 구성 화면, 회원별 PC 등록·조회·수정, 자동 인식, 소셜 로그인과 국내 신품 상품가 조회가 포함돼 있다.
코드가 처음이라면 [문서 첫 화면의 읽는 순서](README.md)와 [공통 규격](week1-contract.md)을 함께 확인한다.

## 1. 최신 코드 받기

처음 받는 팀원은 원하는 상위 폴더에서 아래 명령을 실행한다. 이미 저장소가 있으면 복제 단계를 건너뛴다.

```powershell
git clone --branch dev https://github.com/Evocative00/pc-upgrade-lab.git
cd pc-upgrade-lab
```

이미 저장소가 있다면 프로젝트 루트의 PowerShell에서 상태를 확인하고 최신 dev를 받는다.
미완료 작업이 있으면 본인 작업 브랜치에서 먼저 정리한다. 개인 변경을 초기화하거나 강제로 덮어쓰지 않는다.

```powershell
git status -sb
git switch dev
git pull --ff-only origin dev
```

1주차 기반 코드와 MySQL 검증 도구는 이미 dev에 반영돼 있으므로 **과거 전달 패치를 다시 적용하지 않는다**.
브랜치 전환이나 pull이 거절되면 그 상태에서 중단하고 출력 내용을 공유한다.

새 작업은 담당 기능의 브랜치에서 시작한다. 아래는 PC API 작업의 예시이며, 화면 작업은 `feature/pc-form`처럼 구분한다.

```powershell
git switch -c feature/pc-api
```

이미 작업 브랜치가 있다면 새로 만들지 않고 `git switch 브랜치이름`으로 이동한다.
이 경우 dev를 갱신한 것만으로 기존 작업 브랜치에 새 코드가 합쳐지지는 않으므로, 그 브랜치에서 `git merge dev`로 반영한다.
충돌이 나면 파일을 임의로 버리지 말고 충돌 내용을 확인한다.

IntelliJ에서 프로젝트를 열고 오른쪽 Gradle 창에서 다시 로드한다. 기존 build/bootRun 실행 구성이 있다면 유지한다.
처음 환경을 준비한다면 [루트의 개발환경 안내](../README.md)로 JDK·MySQL·IDE를 준비하고, 1주차 코드의 실행·테스트 방식은 아래 내용을 따른다.

**Git pull로 팀원의 MySQL 데이터나 개인 실행 설정이 복사되지는 않는다.** 각자의 `application-local.properties`, IntelliJ 환경변수와 DB는 별도로 준비한다. 카탈로그 제품이 보이는데 상품가만 없으면 아래 **2.2 상품가 가져오기**를 확인한다. 소셜 로그인 버튼 준비는 **2.1**을 따른다.

2026-10-10 첫 14종 중앙 가격 공유 코드는 **기본으로 꺼져 있어 기존 backend 실행 구성을 바꿀 필요가 없다.** 중앙 조회를 검수할 때만 별도 `sharedCatalogPrices` 제공 서버와 `CATALOG_SHARED_PRICES_ENABLED=true`, `CATALOG_SHARED_PRICES_BASE_URL=http://127.0.0.1:8081` 설정을 사용한다. [실행 안내](catalog-shared-prices-2026-10-10.md)에 순서와 장애 확인 방법을 정리했다. 실제 중앙 배포·자동 가격 수집은 별도 단계다.

## 2. DB와 서버

JPA 엔티티는 `backend/src/main/java/com/pcupgradelab/pc/`, SQL은 `backend/src/main/resources/db/migration/V1__create_pc_configuration.sql`에 있다.

Flyway가 시작 시 적용되지 않은 SQL을 실행하고 이력을 기록한다. JPA는 `ddl-auto=validate`로 테이블을 확인한다.
`spring.sql.init.mode=never`는 유지한다. 스키마 변경은 Flyway가 담당하고, JPA는 구조 일치 여부를 검사한다.

2026-10-09 카탈로그 확장 코드는 일반 `backend [bootRun]`을 재시작할 때 Flyway로 **V13·V14 구조**를 적용한다. JDK 21·`SPRING_PROFILES_ACTIVE=local`·`DB_PASSWORD`와 사용 중인 OAuth 설정을 그대로 유지하면 되며, 이번 변경용 새 환경변수나 실행 구성은 필요 없다. 기존 제품 UUID·가격·PC 연결을 보존하고 모델·공통 ID·SSD·보드 슬롯을 저장할 구조만 추가한다. **최신 부품 후보 78종과 모델·제원·슬롯 자료는 자동으로 적재되지 않는다.** 세부 범위는 [구현·미리보기 안내](catalog-expansion-implementation-2026-10-09.md)를 확인한다.

1. MySQL 서비스를 실행한다. 각자의 PC에 `pc_upgrade_lab` DB와 접속 가능한 앱 계정이 준비돼 있어야 한다.
2. 개인 설정이 없으면 아래 명령으로 예제를 복사한다. 이미 있다면 DB URL·계정·환경 변수 참조를 보존한다.
3. 개인 파일에 `server.address=127.0.0.1`이 있는지 확인하고, 없을 때만 추가한다.
4. IntelliJ의 `backend [bootRun]`에서 `SPRING_PROFILES_ACTIVE=local`, `DB_PASSWORD=본인의 앱 계정 비밀번호`를 설정한다.
5. 백엔드를 실행하고 시작 로그의 Flyway 성공과 `/actuator/health`의 DB 상태를 확인한다.

프로젝트 루트에서 개인 설정을 처음 만드는 명령:

```powershell
if (-not (Test-Path .\backend\src\main\resources\application-local.properties)) {
    Copy-Item .\backend\src\main\resources\application-local.example.properties .\backend\src\main\resources\application-local.properties
}
```

예제의 `${DB_PASSWORD}`는 실제 비밀번호로 바꾸지 않는다. 개인 설정·비밀번호는 Git에 올리지 않는다.
`/api/health`는 기본 통신 응답만 확인한다. DB 연결은 `http://localhost:8080/actuator/health`의 `components.db.status`가 `UP`인지 확인한다.

처음 실행하면 V1 테이블이 생성되고, 이미 적용했다면 이력을 검사한다. 별도 테이블/마이그레이션이 있으면 이력을 먼저 확인한다.
**적용된 V1 파일은 주석만 추가하는 경우에도 변경하지 않는다.** 체크섬 불일치 오류가 날 수 있다.
새 DB 구조 변경은 V2 이후 파일로 추가하며, 기존 이력을 repair로 임의 수정하거나 테이블을 삭제하지 않는다.

독립 테스트는 H2의 MySQL 모드에서 같은 V1 SQL과 JPA를 검사한다. 실제 MySQL 검증과는 구분한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend test
```

위 명령도 프로젝트 루트에서 실행한다. 일반 `test`/`build`의 DB 검사는 H2를 사용하므로 개인 MySQL 서비스·비밀번호가 필요 없다.
실제 MySQL 저장 검사는 별도의 [Verify-MySql 실행 안내](week1-mysql-verification.md)를 따른다. 일반 build에서 자동 실행되지 않는다.

### 2.1 팀원별 소셜 로그인 설정

`backend/src/main/resources/application-local.properties`와 `.idea/workspace.xml`의 개인 실행 구성은 Git 제외 대상이다. 다른 팀원이 pull해도 이 PC의 OAuth 키나 IntelliJ 환경변수가 전달되지 않는다. 각자 사용할 제공자의 키를 발급하거나, 팀에서 승인한 개발용 키를 안전하게 전달받아 **자신의 backend 실행 구성**에 설정한다. `.env` 파일을 만드는 것만으로 Spring Boot나 Gradle이 자동으로 읽지는 않는다.

| 로그인 제공자 | backend 실행 구성에 넣을 환경변수 | 개발자 콘솔에 등록할 콜백 | 상세 안내 |
| --- | --- | --- | --- |
| Google | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `http://127.0.0.1:5173/login/oauth2/code/google` | [Google 설정](week2-google-login-setup.md) |
| Kakao | `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` | `http://127.0.0.1:5173/login/oauth2/code/kakao` | [카카오 설정](week2-kakao-login-setup.md) |
| Naver | `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` | `http://127.0.0.1:5173/login/oauth2/code/naver` | [네이버 설정](week2-naver-login-setup.md) |

환경변수 이름은 `CLIENT`다. `CILENT`처럼 철자가 다르면 연결되지 않는다. Kakao는 REST API 키와 활성화한 Client Secret을 사용하고 OpenID Connect·닉네임 동의를 설정한다. Naver도 OpenID Connect 설정이 필요하다. 각 콘솔의 웹/서비스 주소는 `http://127.0.0.1:5173`을 사용한다.

키가 준비된 제공자만 `application-local.example.properties`의 해당 OAuth 설정을 개인 파일에 추가한다. Google은 3줄, Kakao와 Naver는 각각 7줄이며, `client-id`와 `client-secret`은 `${...}` 환경변수 참조를 유지한다. 기존 DB 설정을 예제로 덮어쓰지 않는다. 키를 준비하지 않은 제공자의 설정은 주석으로 남긴다.

`SPRING_PROFILES_ACTIVE=local`, `DB_PASSWORD`를 유지하고 백엔드를 다시 실행한 뒤 `GET http://127.0.0.1:8080/api/auth/providers`를 확인한다. Kakao/Naver는 등록 설정이 있어야 응답과 로그인 화면에 나타난다. Google은 기본 표시되므로 버튼이 보인다는 것만으로 키가 유효하다는 뜻은 아니다. 실제 화면은 `http://127.0.0.1:5173/#/login`을 사용하고 `DEV_USER_ID`를 설정하지 않으며 `app.auth.dev-header.enabled`는 `false`로 둔다. 실제 계정 로그인 완료는 개발자 콘솔의 콜백·동의 설정까지 별도로 확인한다.

### 2.2 팀원 DB에 상품가 가져오기

상품가는 각자의 MySQL에 저장된 관측값이다. 코드와 검토한 관측 파일은 pull로 받지만, 다른 PC에서 적용한 가격 행은 전달되지 않는다. 화면의 **가격 새로고침**은 내 백엔드의 저장값을 다시 읽으며 판매처를 실시간으로 수집하지 않는다.

가격 미리보기와 실제 DB 반영에는 **V14까지 마이그레이션된 DB**가 필요하며 준비 작업 자체는 Flyway를 실행하지 않는다. 현재 코드의 JPA 검증은 가격 테이블뿐 아니라 식별·저장장치 테이블도 검사한다. 처음 만든 MySQL DB와 V14 이전의 기존 DB는 앞의 개인 DB 설정을 확인하고, 승인된 DB 변경 범위에서 일반 `backend [bootRun]`을 재시작해 최신 마이그레이션을 적용한 뒤 종료한다. 그다음 `importCatalogPrices`나 `setupDevCatalog -PapplyDevCatalog=true`를 실행한다. 기존 DB는 소유자·마이그레이션 이력을 확인하고, 적용된 마이그레이션을 수정하거나 DB를 초기화하지 않는다. 아래 `setupDevCatalog`의 기본 자료 미리보기는 DB 없이도 실행된다.

카탈로그가 비어 있거나 300종 준비가 끝나지 않았다면 **새 DB·미완성 카탈로그 준비**, 이미 기존 300종이 있다면 **가격만 가져오기**를 따른다. 일반 `bootRun`, pull, build/test는 카탈로그나 가격을 자동으로 적재하지 않는다.

#### 새 DB·미완성 카탈로그 준비

JDK 21을 준비하고 프로젝트 루트에서 사용할 검토 자료의 건수를 먼저 확인한다. 기본 실행은 DB에 연결하지 않으며 `DB_PASSWORD`와 OAuth 키가 필요 없다.

```powershell
.\backend\gradlew.bat --project-dir .\backend setupDevCatalog
```

`CATALOG PREVIEW: reviewedProducts=300, reviewedPrices=72, databaseWrites=0`을 확인한다. 이 미리보기는 기존 제품과의 충돌을 검사하지 않는다. **내 개발 DB를 준비하기로 한 경우에만** V14까지 적용된 DB, 개인 DB URL·계정과 **실행할 PowerShell/IntelliJ 구성의 `DB_PASSWORD`**를 준비한다. `bootRun`의 환경변수는 다른 실행 구성이나 터미널에 자동 전달되지 않는다. OAuth 키는 반영에도 필요 없다. 다음 명령으로 기존 데이터와 대조하고 승인된 카탈로그 300종·호환 근거·상품가 72종을 한 트랜잭션으로 반영한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend setupDevCatalog -PapplyDevCatalog=true
```

기존 제품 식별과 충돌하는 경우 실행을 중단하며, 이전 PC나 개인 설정을 초기화하지 않는다. 준비가 끝나면 일반 백엔드와 프런트엔드를 다시 실행한다. 카탈로그가 이미 완성된 팀원은 이 전체 준비 대신 아래 가격 작업만 실행한다.

#### 기존 300종 카탈로그에 가격만 가져오기

이 가격 작업은 제품을 생성하거나 호환 근거를 적재하지 않는다.

1. 최신 `dev`를 받고 JDK 21과 개인 DB URL·계정 설정을 확인한다.
2. 실행할 PowerShell 또는 IntelliJ의 **importCatalogPrices 실행 구성**에 `DB_PASSWORD`를 설정한다. `bootRun` 구성에 넣은 환경변수는 별도 터미널이나 다른 실행 구성에 자동 전달되지 않는다. 이 가격 작업에는 OAuth 키가 필요 없다.
3. 프로젝트 루트에서 미리보기를 실행한다. 이는 DB의 제품 식별·판매 단위·기존 관측과 비교하며 가격 행을 저장하지 않는다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importCatalogPrices
```

4. `PREVIEW`의 `checked=72`, `newMappings`, `newObservations`, `unchanged`와 오류 유무를 확인한다. 확인한 배치를 **내 DB에 반영하기로 한 경우에만** 다음 명령을 실행한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend importCatalogPrices -PapplyPrices=true
```

5. `COMMITTED`를 확인한 뒤 일반 백엔드와 프런트엔드를 실행하고 상품가를 다시 조회한다. 같은 배치 재실행은 중복 관측을 추가하지 않으며, 이미 적용됐다면 미리보기의 `newMappings=0`, `newObservations=0`, `unchanged=72`가 나온다.

가져오는 자료는 2026-10-06·2026-10-08에 확인한 **72종의 저장된 가격 스냅샷**이다. 300종 중 나머지 228종은 상품가 미확인으로 `null`이며, 정상적으로 가져왔어도 모든 부품에 가격이 생기지는 않는다. 새 판매가 수집과 검토는 별도 작업이다. 제품 ID는 각 DB의 식별 결과를 사용하므로 다른 사람의 내부 ID를 복사하지 않는다. 자세한 검증·재실행 규칙은 [국내 신품 상품가](catalog-current-prices.md)를 참고한다.

## 3. Windows 보조 프로그램 설치

**노트북과 데스크톱, Windows 사용자 계정마다 각각 설치한다.** Git pull만으로 설치되지는 않는다.
현재 개발 버전의 '보조 프로그램 실행'은 설치된 수집기를 여는 링크다. 자동 다운로드 기능은 아직 없으며 아래 설치 스크립트를 사용한다.

프로젝트 루트의 PowerShell에서 아래를 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\collector\windows\Install-Collector.ps1
```

현재 사용자 계정에만 `pcupgradelab://` 실행 규칙을 등록한다. 파일은 `%LOCALAPPDATA%\PcUpgradeLab\Collector`에 복사된다.
**수집기 수정이나 관련 파일을 포함한 pull/패치 적용 후에는 설치 명령을 다시 실행한다.** 브라우저는 저장소 원본이 아니라 설치된 복사본을 실행한다.

PowerShell 파일의 한국어 주석은 Windows PowerShell 5.1에서 읽을 수 있도록 UTF-8 BOM으로 저장한다. 편집 후에도 이 인코딩을 유지한다.

Git으로 받은 소스 기준이다. 인터넷에서 받은 ZIP은 Windows가 스크립트를 차단할 수 있다. 이 경우 실행 정책을 전역으로 바꾸지 말고 파일 속성의 차단 상태와 프로젝트 출처를 확인한다. 조직 정책에 의해 차단되면 정책을 우회하지 않는다.

삭제:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\collector\windows\Uninstall-Collector.ps1
```

설치·삭제는 각자의 Windows PC에서 실행한다. 설치한 계정과 다른 Windows 사용자에게는 자동 등록되지 않는다.

### 실행되지 않을 때

프로젝트 루트에서 설치를 다시 실행한 뒤, 다음 명령으로 설치 파일·버전·링크 등록·8080 서버 연결을 확인한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\collector\windows\Test-CollectorSetup.ps1
```

- `[FAIL] 설치 파일` / `수집기 등록`: 이 PC의 현재 Windows 계정에서 설치 명령을 실행한다.
- `[FAIL] 최신 설치본` / `실행 경로`: pull 또는 패치 적용 후 설치 명령을 다시 실행한다.
- `[FAIL] 백엔드 연결`: 같은 PC의 IntelliJ에서 백엔드를 켜고 8080 포트인지 확인한다.
- `[FAIL] 검사 준비`: `SPRING_PROFILES_ACTIVE=local`로 백엔드를 다시 실행한다.
- 설치 명령부터 실행 정책·서명 오류로 차단되면 `Get-ExecutionPolicy -List` 결과와 오류를 확인한다. 조직 정책은 우회하지 않는다.
- 진단이 통과해도 브라우저가 프로그램 열기를 취소·차단하면 실행되지 않는다. 화면에서 취소 후 새 검사를 시작하고 실행 확인을 허용한다.

진단 도구는 하드웨어 수집 없이 빈 검사 세션 하나만 만든다. 이 세션은 2분 뒤 만료되며 실제 화면에서는 새 검사를 시작한다.
새 설치본은 실행 중 오류가 발생하면 안내와 함께 Enter 입력을 기다린다. 오류 안내를 기록해 원인을 구분한다.
설정 파일·DB 비밀번호·검사 실행 링크/토큰은 공유하지 않는다.

## 4. 화면에서 실제 사양 읽기

1. 같은 PC의 백엔드를 8080 포트에서 실행한다.
2. 별도 PowerShell 창에서 프로젝트의 `frontend` 폴더로 이동해 `npm ci`, `npm run dev`를 실행한다.
3. `http://127.0.0.1:5173/#/pcs/new`에서 **내 PC 불러오기**를 클릭한다.
4. **보조 프로그램 실행**을 클릭하고 브라우저의 열기 확인을 허용한다.
5. CPU·GPU·RAM과 가능한 메인보드·저장장치가 화면에 도착하는지 확인한다.

명시적인 링크 클릭으로 브라우저의 사용자 실행 동작을 유지한다. 프로그램 설치 여부를 웹에서 확정할 수 없으므로 미설치/실행 취소 시 2분 뒤 시간 초과 안내가 나온다.

개발 서버는 5173 포트를 고정 사용한다. 이미 사용 중이면 자동으로 다른 포트로 바뀌지 않으므로 기존 실행을 확인한다.
화면의 '화면에서 취소'는 대기·결과 표시만 초기화한다. 이미 실행된 수집기를 종료하거나 서버 세션을 취소하는 동작은 아니다.
현재 PC 등록·수정 폼의 자동 인식 패널은 `onApply`로 연결돼 있다. 결과를 확인하고 **입력란에 반영**을 눌러 폼에 가져온 뒤 저장한다. 자동 인식만으로 회원의 PC가 저장되지는 않는다.

이번 프로그램은 **같은 PC의 `http://127.0.0.1:8080`에만 전송**한다. 다른 웹 서버 배포용 수집기나 자동 업데이터는 아직 아니다. 운영 서버 연결 시 고정 HTTPS 목적지, 인증, 서명된 설치 프로그램 등을 별도 구현해야 한다.

WMI가 보고하는 GPU 이름은 판매 제품의 정확한 모델과 다를 수 있다. RAM 납땜·가상 GPU·일부 드라이버·가상/외장 디스크에 따라 값이 달라질 수 있다. VRAM은 현재 null이며, 파워·케이스·쿨러·모니터는 수동 입력 대상이다.

## 5. 실제 Windows 검증표

- 작업 관리자/시스템 정보와 CPU·GPU 이름 비교.
- RAM 2개가 두 항목으로 오는지, 1개당 용량과 슬롯이 맞는지 확인.
- 저장장치가 여러 개라면 모두 남는지 확인.
- 브라우저 탭 두 개에서 서로 다른 검사를 시작해 결과가 섞이지 않는지 확인.
- 프로그램 실행을 취소하면 시간 초과가 표시되는지 확인.
- 백엔드를 끄면 성공으로 표시하지 않는지 확인.
- 수집하지 못한 항목의 경고와 다른 항목의 정상 결과가 함께 표시되는지 확인.

샘플 기반 수집기 검사:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\collector\windows\Test-Inventory.ps1
```

실제 Windows 실행 결과는 [검증 현황](week1-progress.md)에 PC별로 구분해 기록한다. fixture 성공을 실제 하드웨어 검증으로 기록하지 않는다.

## 6. 통합 인계

- 문경민: `week1-contract.md`의 Repository와 DTO 규격으로 PC API 구현.
- 김재훈: 자동 인식 결과 반영, 9개 부품 입력·보완, 목록·상세·수정 화면 연결.
- 김민성: 공통 PC 샘플로 정상/빈 이름/수량 0/없는 ID/수정 후 중복 여부 검증.
- 고상준: 팀 API·화면 연결, 실제 Windows 추가 검증, 데이터 원천 공동 선정. 저장 계층의 실제 MySQL 검사는 성공 기록이 있으며 전체 API·화면 통합은 별도로 확인한다.

협업은 Issue 기반 작업 브랜치와 Conventional Commit을 사용하고 PR 대상은 dev로 한다. dev 최종 병합은 고상준이 담당한다.
작업 완료 시 어떤 파일을 연결했고 어떤 명령·화면으로 확인했는지 PR에 적는다.

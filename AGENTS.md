# 프로젝트 작업 안내

## 구조와 기준
- `frontend/`: React 19·TypeScript·Vite 8. 기능 코드는 `src/features/`, 테스트는 `tests/`.
- `backend/`: Java 21·Spring Boot 4.1.1·JPA·Flyway. Gradle Wrapper 9.7.1을 사용한다.
- `collector/windows/`: Windows PowerShell 수집기. 스크립트의 UTF-8 BOM을 유지한다.
- `docs/README.md`에서 문서를 찾고, 실행은 `docs/week1-setup.md`, PC 규격은 `docs/week1-contract.md`, 인증은 `docs/week2-auth-pc-contract.md`와 `docs/week2-google-login-setup.md`를 확인한다.
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
- 프론트 개발 서버는 `npm.cmd run dev`, 고정 포트 5173(`strictPort: true`). `/api`, `/oauth2`, `/login/oauth2`를 `127.0.0.1:8080`으로 프록시한다. Google 로그인은 `http://127.0.0.1:5173`을 사용한다.
- 실제 서버 실행에는 MySQL 8.4, 개인 `backend/src/main/resources/application-local.properties`, `SPRING_PROFILES_ACTIVE=local`, `DB_PASSWORD`가 필요하다. 개인 파일이 없을 때만 예제를 복사하고 기존 설정은 보존한다. `.env`는 자동으로 읽히지 않는다.
- Google 로그인에만 개인 OAuth 설정과 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`이 추가로 필요하다. 콜백은 `http://127.0.0.1:5173/login/oauth2/code/google`. 실제 로그인에서는 `DEV_USER_ID`를 설정하지 않고 개발 헤더도 끈다.

## 변경 규칙과 작업 범위
- 시작과 종료에 브랜치·`git status`를 확인하고 기존 미커밋 변경을 보존한다. 요청 없이 브랜치를 전환하거나 커밋·push하지 않는다.
- 기본 환경 점검은 의존성 설치와 위 검증까지다. 실제 DB 변경, `mysqlTest`, `Verify-*.ps1`, seed 실행, 수집기 설치·삭제는 명시적으로 요청된 경우에만 수행한다. `bootRun`도 시작 시 Flyway로 실제 DB를 변경할 수 있다.
- 적용된 Flyway 마이그레이션은 수정하지 않는다. 스키마 변경은 다음 버전 파일을 추가하며 `clean`, 임의 `repair`, DB 초기화를 하지 않는다.
- 개인 설정·비밀번호·OAuth 비밀키·스캔 토큰을 출력하거나 저장소에 추가하지 않는다. `node_modules`, `dist`, `build`, 캐시도 커밋 대상이 아니다.
- PC 소유자는 서버의 `CurrentUser`로 정한다. `/api/pcs/**`는 로그인 필요, 다른 회원의 PC는 404다. CSRF와 세션 규칙을 유지하고 로그인 후 초안을 자동 저장하지 않는다.
- `PartInput`/`ScanDtos`, 프론트 타입, 수집기와 규격 문서를 함께 맞춘다. `quantity`는 장치 개수, `capacityBytes`는 장치 하나의 bytes 용량이다. 미확인은 `null`, `UNMATCHED`는 카탈로그 미연결이다. RAM·저장장치를 임의로 합치거나 이름만으로 제품을 확정하지 않는다.
- 자동 수집 결과는 DB 저장과 별개다. 폼 반영 시 `MANUAL` 항목을 보존하고 편집된 `AUTO` 값을 덮어쓸 때 확인 흐름을 유지한다.
- 변경 범위에 맞는 검증을 실행하고 성공·실패·미실행 및 남은 로컬 설정을 구분해 보고한다. H2·자동 테스트 통과를 실제 MySQL·Google 로그인·Windows 수집기 통합 검증으로 기록하지 않는다.

# 고상준의 데스크톱 수집기 복구 · PC API 통합 검증

작성: 2026-09-28. 작업자는 고상준이다. 팀원 원본 커밋을 가져온 뒤 본인 브랜치에서 수정·검증한다.
기준: dev `30a1821`, 문경민 API `70b079b`, 김민성 검증 요청 `7aa6986`.

2026-09-28 실행 결과: 고상준 데스크톱에서 실제 사양 7항목 표시를 확인했고, PC API의 등록·조회·수정·실패 요청·백엔드 재시작 후 조회를 검증했다. 검증용 PC는 ID `1`이다. 화면/응답 확인과 사용자 보고의 범위는 [PC API 검증 기록](pc-api-validation.md)에 정리했다. 이 문서의 설치·패치 적용 절차는 재현용이며 이미 적용했다면 반복 적용하지 않는다.

## 1. 적용 파일과 작업 순서

- `desktop-collector-fix-20260928.patch`: 수집기 설치 안내, 설치 진단, 실행 오류 창 유지. 현재 dev에 적용할 수 있다.
- `pc-api-integration-fix-20260928.patch`: 페이지 범위 오류 수정·회귀 테스트, 검증 요청 N09/E10, 검증 안내. 아래 두 팀원 브랜치를 합친 뒤 적용한다.

수집기부터 확인하고 그다음 API를 통합한다. 모든 명령은 프로젝트 루트의 PowerShell에서 실행한다.
기존 개인 README.md 수정은 아래 커밋 대상에 포함하지 않는다. `git add .`, 강제 초기화, force push는 필요 없다.
명령이 실패하면 뒤의 명령을 계속 실행하지 않고 해당 출력을 확인한다.

## 2. 데스크톱 수집기 패치 적용

두 패치 파일을 다운로드 폴더에 저장한다. 먼저 현재 작업 상태를 확인하고 자신의 통합 브랜치를 만든다.
다른 코드의 미완료 변경이 있으면 먼저 해당 작업을 정리한다. 같은 이름의 브랜치를 이미 만들었다면 새로 만들지 않고 그 브랜치로 전환한다.

```powershell
git status -sb
git fetch origin
git switch -c fix/week1-integration origin/dev
git apply --check "$env:USERPROFILE\Downloads\desktop-collector-fix-20260928.patch"
git apply "$env:USERPROFILE\Downloads\desktop-collector-fix-20260928.patch"
```

`git apply --check`가 오류 없이 끝난 경우에만 다음 적용 명령을 실행한다. 성공 시 출력이 없는 것이 정상이다.

현재 버전은 자동 다운로드 설치기가 아니라 프로젝트에 들어 있는 PowerShell 수집기를 사용한다.
노트북에 설치했어도 데스크톱의 Windows 사용자 계정에는 별도 설치가 필요하다. 패치나 pull만으로 설치본은 갱신되지 않는다.

1. 같은 PC의 백엔드를 `local` 프로필·8080 포트로 실행한다. 개인 MySQL 설정과 `DB_PASSWORD`는 기존 값을 사용한다.
2. 아래 설치와 진단 명령을 실행한다. 관리자 권한은 필요하지 않다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\collector\windows\Install-Collector.ps1
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\collector\windows\Test-CollectorSetup.ps1
```

3. 프런트를 실행하고 `http://localhost:5173`을 새로고침한다.
4. 기존 검사가 남았다면 **화면에서 취소 → 내 PC 불러오기 → 보조 프로그램 실행** 순서로 새 링크를 만든다.
5. 브라우저의 프로그램 열기 확인을 허용한다. 오류가 나면 창의 안내를 읽고 Enter로 닫는다.

진단의 `[FAIL]` 항목과 실제 화면 결과를 구분해 기록한다. 진단 통과만으로 브라우저 실행·실제 CIM 수집 성공으로 처리하지 않는다.
설치 스크립트 자체가 실행 정책/서명 오류로 막히면 오류 문구와 `Get-ExecutionPolicy -List` 결과를 확인한다. 정책을 전역 완화하거나 조직 정책을 우회하지 않는다.
비밀번호, 실제 설정 파일 내용, 실행 링크나 검사 토큰은 공유하지 않는다.

데스크톱 수집이 확인되면 이 파일들만 커밋한다.

```powershell
git add -- collector/windows/Install-Collector.ps1 collector/windows/Invoke-Scan.ps1 collector/windows/Test-CollectorSetup.ps1 frontend/src/features/pc-scan/PcScanPanel.tsx frontend/src/features/pc-scan/pc-scan.css docs/week1-setup.md
git commit -m "fix: improve collector setup and launch diagnostics"
```

## 3. 팀원 코드 가져오기와 API 패치

IntelliJ에서 백엔드를 잠시 멈추고 같은 `fix/week1-integration` 브랜치에서 진행한다.
두 원본 작업 브랜치를 가져오므로 팀원들이 만든 커밋 기록도 유지된다. GitHub PR 병합은 아직 하지 않는다.

```powershell
git fetch origin
git merge --no-edit origin/feature/7-pc-crud-api
git merge --no-edit origin/chore/6-pc-api-requests
git apply --check "$env:USERPROFILE\Downloads\pc-api-integration-fix-20260928.patch"
git apply "$env:USERPROFILE\Downloads\pc-api-integration-fix-20260928.patch"
```

기준 코드에서는 두 브랜치와 데스크톱 패치의 파일 충돌이 없다. 이후 팀원 코드가 바뀌어 충돌이 생기면 충돌 파일을 확인하고, 내용을 버리는 옵션을 사용하지 않는다.

페이지 번호가 양수여도 `page × size`가 2147483647을 넘으면 JPA에서 처리할 수 없다.
수정본은 계산 전에 long으로 변환하고, 범위 초과를 `400 INVALID_INPUT`으로 반환한다.
조회 가능한 범위에 데이터가 없으면 기존처럼 200과 빈 목록을 반환한다.

## 4. 일반 테스트와 실제 MySQL 검증

먼저 프로젝트 루트에서 일반 테스트를 실행한다.

```powershell
.\backend\gradlew.bat --project-dir .\backend test
```

기준 수정본은 테스트 16개다. 일반 테스트는 H2이므로 실제 MySQL 확인과 구분한다.
기존 저장 계층의 실제 MySQL 검사도 다시 실행할 수 있다. 비밀번호는 가려진 입력으로 받는다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\backend\Verify-MySql.ps1
```

이 명령은 기존 저장 계층 검사다. **이번 PC API의 HTTP 검증은 아래 절차를 따로 실행한다.**

1. 실제 MySQL이 실행 중인지 확인한다. 개인 `application-local.properties`의 URL은 기존 MySQL DB를 가리켜야 한다.
2. 백엔드를 `local` 프로필로 시작하고 `http://localhost:8080/actuator/health`에서 DB가 UP인지 확인한다.
3. IntelliJ에서 `backend/http/pc-api.http`를 연다. 요청 옆 실행 버튼으로 하나씩 실행한다.
4. H01/H02 → N01 순서로 실행한다. N01 응답의 실제 id를 파일 위쪽 `@pcId`에 입력한다.
5. N02~N09를 실행한다. 같은 PC ID, RAM 2개·저장장치 2개·총 11항목, 변경된 이름/수량을 확인한다.
6. E01~E10을 실행한다. 실패한 수정 이후 E05에서 기존 데이터가 그대로인지 확인한다. E10은 500 대신 400이어야 한다.
7. pcId를 기록한 상태에서 **백엔드 프로세스를 완전히 정지하고 같은 설정으로 다시 실행한다.** MySQL 데이터는 유지한다.
8. 새 PC를 등록하지 말고 R01을 실행한다. 같은 id와 수정한 값이 남아 있어야 한다.
9. 실행 날짜, 코드 커밋, Windows/MySQL 환경, 사용한 id, 각 결과를 `docs/pc-api-validation.md` 표에 기록한다.

원래 20개 요청에 N09/E10을 추가해 총 22개다. `@pcId`는 검증 후 공유하기 전에 0으로 되돌린다.
현재 DELETE API는 없으므로 검증용 PC ID는 기록해 둔다. 기존 데이터를 지우거나 테이블을 초기화하지 않는다.

## 5. 이번 수정본에서 확인한 것과 남은 것

| 구분 | 결과 |
| --- | --- |
| 일반 백엔드 테스트 | Java 21 / Gradle 9.7.1, 총 16개 성공 |
| 별도 HTTP 서버 검사 | H2 파일 DB에서 22개 요청 시나리오와 추가 경계값 등 70개 확인 항목 성공 |
| 재시작 검사 | 별도 JVM 서버 프로세스 종료·재실행 후 H2 파일 DB의 동일 PC 조회 성공 |
| 프런트 | npm run build / npm run lint 성공 |
| PowerShell 구문과 BOM | 6개 수집기 파일 파싱 성공, Windows PowerShell 5.1용 UTF-8 BOM 유지 |
| 수집기 fixture | 가짜 장치 데이터 검사 13개 성공 |
| 실행기 흐름 | PowerShell 7.5.4 / Linux 모의 서버·모듈로 잘못된 링크, 만료, 성공, 수집 실패, 전송 실패의 5개 경우 확인. 토큰 미출력·오류 대기 확인 |
| 실제 데스크톱 설치·브라우저 링크·CIM 조회 | 2026-09-28 고상준 PC에서 정상 수집·표시 확인. CPU 1/GPU 1/RAM 2/MOTHERBOARD 1/STORAGE 2, 총 7항목 |
| 실제 MySQL에서 HTTP 등록·수정·프로세스 재시작 후 조회 | 2026-09-28 고상준의 기존 local/MySQL 환경에서 실행. ID=1, 11항목과 수정값 유지. 상태 코드·본문 확인 범위는 PC API 검증 기록 참고 |

현재 남은 범위는 전체 프런트엔드의 등록·목록·수정 화면 연결, 실제 Windows에서 수집 중 취소·만료·다중 탭 및 오류 경로 확인, 여러 PC의 정렬·동시 수정 확장 검사다. 이번 정상 수집과 API 기본 흐름의 검증 범위를 구분한다.

H2의 MySQL 호환 모드를 실제 MySQL 실행으로 기록하지 않는다.
Linux에서 검증한 PowerShell 흐름을 Windows 레지스트리 등록이나 실제 브라우저 실행 검증으로 기록하지 않는다.

## 6. 검증 후 PR 처리

실제 결과를 기록하고 `backend/http/pc-api.http`의 `@pcId`를 공유용 `0`으로 되돌린 뒤 아래 파일들을 커밋한다. 로컬 PC ID `1`은 검증 문서에 남겨 둔다. IntelliJ가 자동 저장한 HTTP 응답 파일은 커밋 대상에 추가하지 않는다.

```powershell
git add -- backend/src/main/java/com/pcupgradelab/pc/PcService.java backend/src/test/java/com/pcupgradelab/pc/PcControllerTests.java backend/http/pc-api.http docs/week1-contract.md docs/pc-api-validation.md docs/week1-integration-fix.md docs/week1-progress.md
git --no-pager diff --cached --stat
git commit -m "fix: integrate PC APIs and record Windows verification"
git push -u origin fix/week1-integration
```

고상준의 `fix/week1-integration` → `dev` 통합 PR을 하나 만든다. 두 팀원 작업을 포함하고 PR #8을 보완·대체하는 PR임을 본문에 적는다.
실제 확인한 결과와 아직 확인하지 않은 결과를 나눠 기록하고 검토 후 병합한다.
**기존 PR #8은 통합 PR과 중복으로 병합하지 않는다.** 통합 PR이 dev에 반영된 것을 확인한 뒤 대체 PR 링크를 남기고 닫는다.
이슈 #6·#7은 각 완료 기준과 실제 MySQL 결과가 충족되면 닫는다. 전체 입력 화면 연결은 별도 작업이다.

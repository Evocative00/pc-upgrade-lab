# 1주차 실행·팀원 개발 시작 안내

안내 갱신: 2026-09-24. 기능 기준: PR #3·#4가 반영된 dev 커밋 `8563c15d592dde46f61c9b6618da0bc9c46b2852`.
Java 21, 프로젝트 Gradle Wrapper 9.7.1, Spring Boot 4.1.1, Node.js 24, React·TypeScript·Vite, MySQL 8.4를 사용한다.

현재 dev에 저장 계층과 자동 인식 모듈이 포함돼 있다. PC 등록·조회·수정 API는 문경민, 전체 입력 화면은 김재훈이 연결한다.
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

## 2. DB와 서버

JPA 엔티티는 `backend/src/main/java/com/pcupgradelab/pc/`, SQL은 `backend/src/main/resources/db/migration/V1__create_pc_configuration.sql`에 있다.

Flyway가 시작 시 적용되지 않은 SQL을 실행하고 이력을 기록한다. JPA는 `ddl-auto=validate`로 테이블을 확인한다.
`spring.sql.init.mode=never`는 유지한다. 스키마 변경은 Flyway가 담당하고, JPA는 구조 일치 여부를 검사한다.

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

## 3. Windows 보조 프로그램 설치

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

## 4. 화면에서 실제 사양 읽기

1. 같은 PC의 백엔드를 8080 포트에서 실행한다.
2. 별도 PowerShell 창에서 프로젝트의 `frontend` 폴더로 이동해 `npm ci`, `npm run dev`를 실행한다.
3. `http://localhost:5173`에서 **내 PC 불러오기**를 클릭한다.
4. **보조 프로그램 실행**을 클릭하고 브라우저의 열기 확인을 허용한다.
5. CPU·GPU·RAM과 가능한 메인보드·저장장치가 화면에 도착하는지 확인한다.

명시적인 링크 클릭으로 브라우저의 사용자 실행 동작을 유지한다. 프로그램 설치 여부를 웹에서 확정할 수 없으므로 미설치/실행 취소 시 2분 뒤 시간 초과 안내가 나온다.

개발 서버는 5173 포트를 고정 사용한다. 이미 사용 중이면 자동으로 다른 포트로 바뀌지 않으므로 기존 실행을 확인한다.
화면의 '화면에서 취소'는 대기·결과 표시만 초기화한다. 이미 실행된 수집기를 종료하거나 서버 세션을 취소하는 동작은 아니다.
현재 App에는 결과 확인용 패널이 있다. 폼에 결과를 반영하는 버튼은 부모 화면에서 `onApply`를 전달했을 때 표시된다.

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

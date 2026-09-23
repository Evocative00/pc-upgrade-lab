# 실제 MySQL 저장 계층 검증

PR #3 병합 커밋 `ebb0b84aed842dce7df338ab26d83f9a9dba45e7` 이후 작업이다.
PC API·전체 입력 화면을 연결하기 전에 고상준 담당 JPA 저장 계층을 실제 MySQL에서 확인한다.

## 확인하는 동작

선택 실행하는 Gradle `mysqlTest` 작업을 추가했다. 기존 `test`와 `build`는 H2 테스트를 계속 사용한다.

1. `local` 설정으로 MySQL에 연결하고 기존 V1 테이블 구조를 검증한다.
2. 실행마다 다른 ownerKey로 검증용 PC와 RAM 2개·저장장치 2개를 저장한다.
3. 별도 트랜잭션에서 재조회하고, 같은 PC ID를 유지하며 부품 목록을 교체한다.
4. Spring 애플리케이션 컨텍스트와 DB 연결 풀을 종료한다.
5. 새 애플리케이션 컨텍스트에서 수정된 PC를 재조회한다. 원문·미연결 제품·64비트 용량·JSON null과 부품 행 수를 확인한다.
6. 이번 실행에서 만든 PC ID와 ownerKey가 모두 일치하는 데이터만 삭제하고 자식 행 정리를 확인한다.

MySQL 검사는 H2를 실행 경로에 포함하지 않고 실제 DB 제품명도 확인한다. 매번 실행되며 이전 Gradle 성공 결과를 재사용하지 않는다.
스키마 생성·변경은 수행하지 않으므로 V1 테이블이 먼저 준비돼 있어야 한다.

이 검증은 **Spring 컨텍스트 재시작 후 DB 데이터 유지**까지 확인한다. HTTP PC API와 전체 화면 시연, 별도로 실행 중인 `bootRun` 프로세스의 재시작 검증은 팀원 연동 단계에 진행한다.

## Windows에서 실행

MySQL 서비스를 켜고 기존 `backend/src/main/resources/application-local.properties`를 유지한다.
V1 테이블은 PR #3의 백엔드를 실행해 생성해 둔 상태여야 한다.
이 검사는 웹 서버를 열지 않으므로 이미 실행 중인 백엔드·프런트엔드를 종료할 필요가 없다.

프로젝트 루트 PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\backend\Verify-MySql.ps1
```

`MySQL pc_upgrade_app password:`가 나오면 IntelliJ의 `DB_PASSWORD`에 설정한 앱 계정 비밀번호를 입력한다.
입력은 화면에 노출되지 않으며 명령 기록이나 파일에 저장하지 않는다. 현재 PowerShell 환경에 `DB_PASSWORD`가 있으면 그 값을 사용한다.

IntelliJ에서 직접 실행하려면 기존 Gradle `backend [build]` 실행 구성을 복제하고, 작업을 `mysqlTest`로 바꾼다. 기존 JDK 21·환경 변수 설정을 유지한다.

## 결과 확인

성공 시 `MySqlPersistenceTests > persistsUpdatedPartsAcrossApplicationRestart() PASSED`와 `BUILD SUCCESSFUL`이 표시된다.
애플리케이션 시작·종료 로그가 반복되는 것은 재시작 검증과 테스트 데이터 정리 과정이다.

- 상세 결과: `backend/build/reports/tests/mysqlTest/index.html`
- 실패하면 비밀번호가 포함되지 않은 Gradle 오류 부분을 확인한다.
- 검사 도중 프로세스를 강제로 종료하거나 DB 연결이 끊기면 정리 단계가 완료되지 않을 수 있다. `mysql-verification-` ownerKey와 해당 검증용 PC ID를 확인한 뒤 그 데이터만 정리한다.

개발 환경에서 기존 테스트 9개 재통과, 새 검사 코드 컴파일, MySQL 드라이버 포함·H2 제외, 일반 build에서 mysqlTest 제외를 확인했다. Windows 실행 도구는 PowerShell 7.5.4 파서로 문법을 확인했다.

## Windows 실행 결과 (2026-09-23)

사용자가 Windows 노트북에서 `Verify-MySql.ps1`을 실행했고, Gradle 9.7.1에서 아래 결과를 확인했다.

```text
MySqlPersistenceTests > persistsUpdatedPartsAcrossApplicationRestart() PASSED
BUILD SUCCESSFUL in 20s
```

실제 MySQL을 사용하는 통합 테스트 1개가 통과했다. 검증용 PC 저장·수정, 새 Spring 컨텍스트에서 재조회, 원문·미연결 제품·64비트 용량·JSON null 유지와 테스트 데이터 정리까지 확인했다.
웹에서 자동 인식 결과를 PC API로 저장하는 전체 흐름과 별도 `bootRun` 프로세스 재시작은 팀원 연동 단계에서 확인한다.

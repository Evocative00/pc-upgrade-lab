# 상준 1주차 개발 적용·실행

기준: dev 커밋 `3b91d9e8117f479a6eefc37b46c14a0d6fcad495`. Java 21, Spring Boot 4.1.1, Gradle 9.7.1, React·TypeScript·Vite, MySQL 8.4를 유지했다.

이 작업은 저장 계층과 자동 인식 모듈이다. 등록·조회·수정 API는 문경민, 전체 입력 화면은 김재훈이 연결한다. 전체 1주차 통합 시연 완료로 간주하지 않는다.

## 1. 코드 적용

전달한 `week1-foundation.patch`는 프로젝트 루트에서 적용한다. 기존 변경을 먼저 확인한다.

```powershell
git status -sb
```

수정 파일이 없을 때:

```powershell
git switch dev
git pull --ff-only origin dev
git switch -c feature/week1-foundation
git apply --check "$env:USERPROFILE\Downloads\week1-foundation.patch"
git apply "$env:USERPROFILE\Downloads\week1-foundation.patch"
git status -sb
```

다운로드 위치가 다르면 patch 경로만 바꾼다. `--check`에서 충돌하면 적용하지 말고 출력 내용을 확인한다. 이미 같은 브랜치를 만들었다면 새로 만들지 않고 기존 브랜치를 사용한다. 로컬 변경을 초기화하거나 강제로 덮어쓰지 않는다.

IntelliJ 오른쪽 Gradle 창에서 다시 로드한다. 기존 build/bootRun 실행 구성을 그대로 사용한다.

## 2. DB와 서버

JPA 엔티티는 `backend/src/main/java/com/pcupgradelab/pc/`, SQL은 `backend/src/main/resources/db/migration/V1__create_pc_configuration.sql`에 있다.

Flyway가 시작 시 SQL을 한 번 적용하고 이력을 기록한다. JPA는 `ddl-auto=validate`로 테이블을 확인한다. `spring.sql.init.mode=never`는 유지한다. Hibernate 자동 생성과 별도 SQL 초기화를 함께 쓰지 않는다.

1. 기존 MySQL 서비스를 실행한다.
2. 기존 `application-local.properties`의 DB URL·계정·환경 변수 참조를 유지한다.
3. 같은 파일에 `server.address=127.0.0.1`을 추가한다. 이미 있다면 중복 추가하지 않는다.
4. IntelliJ의 `backend [bootRun]`에서 `SPRING_PROFILES_ACTIVE=local`, 기존 `DB_PASSWORD`를 사용한다.
5. 시작 로그의 Flyway 성공과 `/actuator/health`의 DB 상태를 확인한다.

초기 업무 테이블이 없는 DB를 전제로 한다. 이미 별도 테이블/마이그레이션이 있으면 이력을 먼저 확인한다. baseline이나 repair로 오류를 자동 무시하거나 테이블을 삭제하지 않는다. 새 변경은 V2 이후 파일을 추가한다.

독립 테스트는 H2의 MySQL 모드에서 같은 V1 SQL과 JPA를 검사한다. 실제 MySQL 검증과는 구분한다.

```powershell
cd backend
.\gradlew.bat test
```

테스트는 `test` 프로필을 사용하므로 개인 DB 비밀번호가 필요 없다. 기존 `build` 실행 구성에서도 수행할 수 있다.

## 3. Windows 보조 프로그램 설치

프로젝트 루트의 PowerShell에서 아래를 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\collector\windows\Install-Collector.ps1
```

현재 사용자 계정에만 `pcupgradelab://` 실행 규칙을 등록한다. 관리자 권한·상주 서버·외부 프로그램 의존성은 필요 없다. 실행 파일은 `%LOCALAPPDATA%\PcUpgradeLab\Collector`에 복사된다. 수집기를 수정한 뒤에는 설치 명령을 다시 실행한다.

Git으로 받은 소스 기준이다. 인터넷에서 받은 ZIP은 Windows가 스크립트를 차단할 수 있다. 이 경우 실행 정책을 전역으로 바꾸지 말고 파일 속성의 차단 상태와 프로젝트 출처를 확인한다. 조직 정책에 의해 차단되면 정책을 우회하지 않는다.

삭제:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\collector\windows\Uninstall-Collector.ps1
```

설치·삭제는 개발자가 직접 확인할 작업이다. 이번 작성 환경에서 Windows 레지스트리를 변경한 것은 아니다.

## 4. 화면에서 실제 사양 읽기

1. 같은 PC의 백엔드를 8080 포트에서 실행한다.
2. `frontend`에서 `npm ci`, `npm run dev`를 실행한다.
3. `http://localhost:5173`에서 **내 PC 불러오기**를 클릭한다.
4. **보조 프로그램 실행**을 클릭하고 브라우저의 열기 확인을 허용한다.
5. CPU·GPU·RAM과 가능한 메인보드·저장장치가 화면에 도착하는지 확인한다.

명시적인 링크 클릭으로 브라우저의 사용자 실행 동작을 유지한다. 프로그램 설치 여부를 웹에서 확정할 수 없으므로 미설치/실행 취소 시 2분 뒤 시간 초과 안내가 나온다.

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

실제 Windows 실행 결과는 이 문서 아래에 PC별로 기록한다. fixture 성공을 실제 하드웨어 검증으로 기록하지 않는다.

## 6. 통합 인계

- 문경민: `week1-contract.md`의 Repository와 DTO 규격으로 PC API 구현.
- 김재훈: 자동 인식 결과 반영, 9개 부품 입력·보완, 목록·상세·수정 화면 연결.
- 김민성: 공통 PC 샘플로 정상/빈 이름/수량 0/없는 ID/수정 후 중복 여부 검증.
- 고상준: MySQL 저장 후 서버 재시작·재조회, 팀 API·화면 연결, 실제 Windows 검증, 데이터 원천 공동 선정.

협업은 Issue 기반 작업 브랜치와 Conventional Commit을 사용하고 PR 대상은 dev로 한다. dev 최종 병합은 고상준이 담당한다. 이 코드 전달 자체가 GitHub 병합이나 원격 반영을 뜻하지 않는다.

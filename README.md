# PC 업그레이드 실험실 · pc-upgrade-lab

사용자의 기존 PC 구성과 예산을 바탕으로 **부품 호환성을 확인하고 업그레이드 대안을 비교**하는 웹 서비스를 만드는 프로젝트

2026-2 산학협력캡스톤디자인 1
    
현재 목표는 프런트엔드·백엔드·DB의 연결과 협업 준비고 서비스 기능과 업무용 테이블은 이후 개발 단계에서 추가

저장소: [Evocative00/pc-upgrade-lab](https://github.com/Evocative00/pc-upgrade-lab)

> 이 문서는 프런트엔드 초기 구성과 MySQL 연결 설정이 포함된 `dev` 브랜치를 기준으로 합니다. 아래에서 안내하는 파일이 없다면, 환경설정 PR이 `dev`에 병합되었는지 먼저 확인해 주세요.

## 기본적으로 할 것

1. Git, JDK 21, Node.js 24, MySQL 8.4, IntelliJ IDEA를 준비
2. 저장소의 `dev` 브랜치를 내 PC에 복제
3. **내 PC의 MySQL**에 프로젝트 DB와 전용 계정 생성
4. 예제 설정 파일을 복사하고 IntelliJ에 개인 환경 변수를 설정
5. 백엔드 빌드를 확인한 뒤 서버를 실행
6. 프런트엔드 의존성을 설치하고 개발 서버를 실행
7. 화면의 API 연결과 백엔드의 DB 연결 상태를 각각 확인

이미 마친 단계는 다시 할 필요 X 특히 정상적으로 사용하는 DB나 계정을 삭제하거나 새로 만들기 X

## 기술 스택과 폴더 안내

|구분|사용하는 기술|역할|
|-|-|-|
|프론트엔드|React, TypeScript, Vite|브라우저에서 보이는 화면|
|백엔드|Java 21, Spring Boot 4.1.1|API 요청 처리와 DB 연결|
|백엔드 빌드|Gradle Wrapper|프로젝트에 맞는 Gradle 실행|
|데이터베이스|MySQL 8.4|프로젝트 데이터 저장|
|협업|Git, GitHub, Pull Request|코드 변경 기록과 검토|

|경로|내용|
|-|-|
|`frontend/`|React 화면과 Vite 설정|
|`backend/`|Spring Boot 프로젝트|
|`backend/src/main/resources/`|Spring Boot 설정 파일|
|`docs/`|요구사항, 설계, 회의 기록|
|`README.md`|이 문서. 팀원이 처음 읽는 실행 안내|

프론트엔드 패키지의 정확한 버전은 `frontend/package-lock.json`, 백엔드 의존성은 `backend/build.gradle`을 기준으로 합니다. Gradle과 Vite를 별도로 전역 설치할 필요는 없습니다.

## 1\. 설치 준비

아래 안내는 **Windows PowerShell과 한글 IntelliJ IDEA** 기준. IntelliJ 버전에 따라 메뉴 번역이나 위치가 조금 다를 수 있음.

|프로그램|맞출 버전|확인 방법|
|-|-|-|
|Git|2.x|`git --version`|
|JDK|21|`java -version`|
|Node.js|24.x|`node -v`|
|npm|Node.js와 함께 설치|`npm -v`|
|MySQL Server|8.4.x|아래 DB 접속 명령으로 확인|
|IntelliJ IDEA|JDK 21을 사용할 수 있는 버전|**도움말 → 정보**|

초기 설정 PC에서는 MySQL 8.4.11과 Vite 8.3.0으로 연결 및 프런트엔드 빌드를 확인. 개개인은 저장소에 있는 설정과 잠금 파일을 그대로 사용해 주세요.

MySQL은 데이터베이스 서버가 실제로 설치되어 있어야 함. Workbench만 설치한 경우에는 MySQL Server 설치도 필요.

IntelliJ Ultimate는 선택 사항입니다. 이 문서의 Gradle 실행 방식은 Ultimate 전용 실행 구성에 의존하지 않습니다.

설치 후 새 PowerShell 창을 열고 다음 명령을 한 줄씩 실행합니다.

```powershell
git --version
java -version
node -v
npm -v
```

명령을 찾을 수 없다는 메시지가 나오면 설치 여부와 PATH를 확인하고 터미널을 다시 열어 주세요.

## 2\. GitHub 저장소 가져오기

**처음 복제하는 팀원만** PowerShell에서 실행합니다.

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\\Projects" | Out-Null
cd "$env:USERPROFILE\\Projects"
git clone --branch dev https://github.com/Evocative00/pc-upgrade-lab.git
cd .\\pc-upgrade-lab
git status -sb
```

`## dev...origin/dev`가 표시되면 `dev` 브랜치를 가져온 것입니다. 이후 이 문서에서 **프로젝트 루트**는 `frontend`, `backend`, `docs`가 들어 있는 `pc-upgrade-lab` 폴더를 뜻합니다.

이미 복제한 팀원은 기존 폴더를 사용합니다. 커밋하지 않은 변경이 없는지 `git status`로 확인한 뒤 최신 내용을 받습니다.

```powershell
git switch dev
git pull --ff-only origin dev
```

수정한 파일이 있거나 Git이 오류를 출력하면 그 상태에서 확인해 주세요. 작업 내용을 지우는 명령이나 강제 덮어쓰기로 해결하지 않습니다.

## 3\. 내 PC의 MySQL 준비

팀원마다 자기 PC에 DB를 만듭니다. DB 이름과 계정 이름은 맞추고, **비밀번호는 각자 다르게 설정**합니다. 팀장의 DB 비밀번호를 받을 필요가 없습니다.

`localhost`는 명령을 실행하는 **본인의 컴퓨터**입니다. GitHub에서 코드를 받는다고 다른 팀원의 DB나 데이터가 함께 내려오는 것은 아닙니다.

### 3-1. MySQL 서버 실행 확인

Windows에서 `Win + R`을 누르고 `services.msc`를 실행합니다. MySQL 서비스(예: `MySQL84`)가 실행 중인지 확인합니다. 서비스 이름은 설치할 때 지정한 이름에 따라 달라질 수 있습니다.

### 3-2. 관리자 계정으로 접속

PowerShell에서 실행합니다. 아래 경로는 기본 설치 경로이므로 다르게 설치했다면 실제 경로에 맞춰 주세요.

```powershell
\& "C:\\Program Files\\MySQL\\MySQL Server 8.4\\bin\\mysql.exe" -h localhost -P 3306 -u root -p
```

`Enter password:`가 나오면 **본인이 MySQL 설치 시 지정한 root 비밀번호**를 입력합니다. 명령 뒤에 비밀번호를 붙이지 않습니다. `mysql>`가 표시되면 접속 성공입니다.

### 3-3. DB와 프로젝트 전용 계정 만들기

이 단계의 SQL은 PowerShell이 아니라, 접속 후 표시된 **`mysql>` 화면**에서 실행합니다. 코드 블록 안의 명령만 복사하세요.

먼저 DB를 만듭니다.

```sql
CREATE DATABASE pc\_upgrade\_lab
  CHARACTER SET utf8mb4
  COLLATE utf8mb4\_0900\_ai\_ci;
```

다음으로 전용 계정을 만듭니다. 예제 비밀번호를 그대로 사용하는 실수를 막기 위해 MySQL이 개인별 비밀번호를 생성하도록 합니다.

```sql
CREATE USER 'pc\_upgrade\_app'@'localhost'
  IDENTIFIED BY RANDOM PASSWORD;
```

결과의 **`generated password` 열에 나온 값**을 본인만 확인할 수 있는 비밀번호 관리자 등에 보관하세요. 이후 IntelliJ의 `DB\_PASSWORD`에도 같은 값을 입력합니다. 이 결과에는 실제 비밀번호가 있으므로 캡처나 GitHub에 올리지 않습니다. 표의 구분선과 양옆 공백은 비밀번호에 포함되지 않습니다.

이어서 해당 DB의 권한을 부여합니다.

```sql
GRANT ALL PRIVILEGES ON `pc\\\_upgrade\\\_lab`.\*
  TO 'pc\_upgrade\_app'@'localhost';

exit;
```

위 권한은 로컬 개발용 프로젝트 DB 범위에 부여합니다. `GRANT`의 역슬래시는 DB 이름에 있는 밑줄을 문자 그대로 취급하기 위한 표기이므로 그대로 사용합니다.

이미 DB나 계정이 존재한다는 오류가 나오면, 기존 설정이 있는지 먼저 확인합니다. 접속이 되는 계정이라면 다시 만들지 않아도 됩니다.

MySQL의 비밀번호 생성 동작은 [MySQL 8.4 공식 문서](https://dev.mysql.com/doc/refman/8.4/en/password-management.html#random-password-generation)를 참고

### 3-4. 전용 계정으로 접속 확인

다시 PowerShell에서 실행합니다.

```powershell
\& "C:\\Program Files\\MySQL\\MySQL Server 8.4\\bin\\mysql.exe" -h localhost -P 3306 -u pc\_upgrade\_app -p -D pc\_upgrade\_lab -e "SELECT VERSION() AS mysql\_version, DATABASE() AS database\_name, CURRENT\_USER() AS db\_account;"
```

이번에는 **pc\_upgrade\_app 계정의 비밀번호**를 입력합니다. root 비밀번호와 혼동하지 마세요.

|결과 항목|기대하는 값|
|-|-|
|`mysql\_version`|설치한 MySQL 8.4.x 버전|
|`database\_name`|`pc\_upgrade\_lab`|
|`db\_account`|`pc\_upgrade\_app@localhost`|

### 선택: 내 비밀번호를 직접 정하고 싶을 때

계정 생성과 접속 확인을 마친 뒤, PowerShell에서 다음 명령을 실행합니다.

```powershell
\& "C:\\Program Files\\MySQL\\MySQL Server 8.4\\bin\\mysqladmin.exe" -h localhost -P 3306 -u pc\_upgrade\_app -p password
```

현재 비밀번호, 새 비밀번호, 새 비밀번호 확인을 순서대로 입력합니다. 변경 후에는 IntelliJ의 `build`, `bootRun` 실행 구성에 있는 `DB\_PASSWORD`도 모두 새 값으로 바꿔 주세요. 이미 실행 중인 백엔드는 재시작합니다.

## 4\. 개인용 Spring Boot 설정 만들기

IntelliJ에서 **파일 → 열기**로 `pc-upgrade-lab` 폴더를 엽니다.

아래 예제 파일을 복사해 같은 폴더에 개인 설정 파일을 만듭니다.

|구분|경로|GitHub 공유|
|-|-|-|
|원본 예제|`backend/src/main/resources/application-local.example.properties`|공유함|
|개인 설정|`backend/src/main/resources/application-local.properties`|공유하지 않음|

처음 설정할 때는 프로젝트 루트의 PowerShell에서 다음 명령으로 복사할 수 있습니다. 이미 개인 설정이 있다면 복사 단계를 건너뜁니다.

```powershell
if (-not (Test-Path ".\\backend\\src\\main\\resources\\application-local.properties")) {
    Copy-Item ".\\backend\\src\\main\\resources\\application-local.example.properties" ".\\backend\\src\\main\\resources\\application-local.properties"
}
```

개인 설정 파일의 내용은 다음과 같습니다.

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/pc\_upgrade\_lab
spring.datasource.username=pc\_upgrade\_app
spring.datasource.password=${DB\_PASSWORD}

spring.sql.init.mode=never

management.endpoints.web.exposure.include=health
management.endpoint.health.show-components=always
```

**`${DB\_PASSWORD}`는 그대로 둡니다.** 실제 비밀번호는 다음 단계의 IntelliJ 환경 변수에 입력합니다. `.env` 파일을 만들기만 해서는 현재 Spring Boot 실행 구성에 자동으로 적용되지 않습니다.

`local`은 내 PC용 설정을 선택하는 프로필 이름입니다. `SPRING\_PROFILES\_ACTIVE=local`을 설정해야 `application-local.properties`가 적용됩니다. `application-local.example.properties`는 복사용 예제이며 이 이름만으로는 `local` 설정 파일을 대신하지 않습니다.

`spring.sql.init.mode=never`는 시작할 때 SQL 초기화 스크립트를 자동 실행하지 않도록 하는 설정입니다. 현재는 서비스 테이블이나 샘플 데이터를 자동으로 만드는 단계까지 구성하지 않았습니다.

설정 방식은 [Spring Boot 프로필 안내](https://docs.spring.io/spring-boot/reference/features/profiles.html)와 [SQL 데이터베이스 연결 안내](https://docs.spring.io/spring-boot/reference/data/sql.html)를 참고하세요.

## 5\. IntelliJ에서 백엔드 설정하기

### 5-1. Gradle 프로젝트와 JDK 연결

1. 왼쪽 프로젝트 창에서 `backend/build.gradle`을 찾습니다.
2. Gradle 프로젝트로 인식되지 않았다면 파일을 우클릭하고 **Gradle 프로젝트 연결** 또는 **Gradle 프로젝트로 추가** 항목을 선택합니다.
3. **파일 → 프로젝트 구조 → 프로젝트 → SDK**에서 JDK 21을 선택합니다. 목록에 없다면 JDK 21을 추가합니다.
4. **파일 → 설정 → 빌드, 실행, 배포 → 빌드 도구 → Gradle**에서 `backend` 프로젝트의 **Gradle JVM**을 JDK 21로 지정합니다. Gradle 배포 방식은 프로젝트의 **Wrapper**를 사용합니다.
5. 오른쪽 **Gradle** 도구 창에서 **모든 Gradle 프로젝트 다시 로드**를 눌러 동기화가 끝날 때까지 기다립니다.

메뉴를 찾기 어려우면 `Ctrl + Shift + A`로 작업 검색을 열어 `Gradle`을 검색할 수 있습니다. 첫 동기화에서는 의존성을 내려받느라 시간이 걸릴 수 있습니다.

### 5-2. 빌드 실행 구성 만들기

1. 상단 \*\*실행 → 구성 편집…\*\*을 선택합니다.
2. 왼쪽에 `backend \[build]`가 있으면 선택합니다. 없으면 왼쪽 위 **+ → Gradle**로 새로 만듭니다.
3. 아래 값을 지정합니다.

|항목|입력할 값|
|-|-|
|이름|`backend \[build]`|
|실행|`build`|
|Gradle 프로젝트|현재 저장소의 `backend` 폴더|

4. **환경 변수** 오른쪽 편집 아이콘을 눌러 아래 두 항목을 **각각 한 행씩** 추가합니다. 환경 변수 항목이 숨겨져 있다면 **옵션 수정 → 환경 변수**를 표시합니다.

|이름|값|
|-|-|
|`SPRING\_PROFILES\_ACTIVE`|`local`|
|`DB\_PASSWORD`|본인이 만든 pc\_upgrade\_app 계정의 실제 비밀번호|

값 칸에는 설명 문구가 아니라 본인 비밀번호를 입력하며 따옴표를 덧붙이지 않습니다. 개별 행으로 입력하면 비밀번호에 특수문자가 있어도 한 줄 구분자와 혼동하지 않을 수 있습니다.

5. **프로젝트 파일로 저장**은 체크하지 않습니다. 이 실행 구성에는 개인 비밀번호가 포함됩니다.
6. 환경 변수 창의 **확인**, 실행 구성 창의 **적용 → 확인**을 누릅니다.
7. 상단 실행 구성 목록에서 `backend \[build]`를 선택하고 초록색 **▶ 실행**을 누릅니다.

마지막에 \*\*`BUILD SUCCESSFUL`\*\*이 나오면 빌드와 해당 빌드에 포함된 테스트가 성공한 것입니다. 이 단계에서도 MySQL이 실행 중이어야 합니다. 현재 설정에서는 Spring 컨텍스트를 여는 테스트가 로컬 DB 설정을 사용합니다.

`build`는 빌드와 테스트를 실행하는 작업입니다. 완료 후 종료되는 것이 정상이며, 웹 서버를 계속 실행하려면 다음 `bootRun` 구성이 필요합니다.

### 5-3. 서버 실행 구성 만들기

1. 다시 \*\*실행 → 구성 편집…\*\*으로 들어갑니다.
2. `backend \[build]`를 선택한 뒤, 왼쪽 위의 **구성 복사** 아이콘(종이 두 장이 겹친 모양)을 누릅니다.
3. 이름을 `backend \[bootRun]`, **실행** 값을 `bootRun`으로 바꿉니다.
4. Gradle 프로젝트가 `backend`인지, 환경 변수 두 개가 복사되었는지 확인합니다.
5. **프로젝트 파일로 저장**을 체크하지 않은 상태로 **적용 → 확인**합니다.
6. 상단에서 `backend \[bootRun]`을 선택하고 **▶ 실행**합니다.

로그에서 `local` 프로필 활성화와 애플리케이션 시작을 확인합니다. 이 실행 창은 서버를 사용하는 동안 켜 둡니다. 같은 백엔드를 다른 실행 구성으로 중복 실행하면 8080 포트 충돌이 날 수 있습니다.

Gradle 실행 구성은 [IntelliJ 공식 안내](https://www.jetbrains.com/help/idea/run-debug-gradle.html)를 참고할 수 있습니다.

## 6\. 백엔드와 DB 연결 확인

브라우저에서 다음 두 주소를 각각 엽니다.

|주소|무엇을 확인하나요?|성공 기준|
|-|-|-|
|[백엔드 API 확인](http://localhost:8080/api/health)|서버가 기본 API 요청에 응답하는지|`status`가 `UP`, `service`가 `pc-upgrade-lab`|
|[DB 포함 상태 확인](http://localhost:8080/actuator/health)|Spring Boot가 DB에 연결할 수 있는지|전체 `status`와 `components.db.status`가 모두 `UP`|

`/api/health`는 기본 응답 확인용 API입니다. 이 응답만으로 DB 연결 성공까지 판단하지는 않습니다.

`/actuator/health`의 응답에는 여러 항목이 나올 수 있습니다. 아래는 확인할 부분만 남긴 예시입니다.

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    }
  }
}
```

`db` 항목이 없다면 `local` 프로필과 설정 파일, JDBC·Actuator 의존성이 적용되었는지 확인합니다. 전체 상태의 `UP`만 보고 DB도 확인되었다고 판단하지 않습니다.

Actuator의 상태 점검 동작은 [Spring Boot 공식 안내](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)를 참고하세요.

## 7\. 프런트엔드 실행

백엔드를 켜 둔 채 **별도의 PowerShell 창**을 엽니다. 이 문서의 기본 경로로 복제했다면 다음 명령을 실행합니다.

```powershell
cd "$env:USERPROFILE\\Projects\\pc-upgrade-lab\\frontend"
npm ci
npm run dev
```

다른 폴더에 복제했다면 첫 줄의 경로만 실제 위치로 바꿉니다.

`npm ci`는 저장소의 `package-lock.json`을 기준으로 의존성을 설치합니다. 처음 복제했을 때 또는 의존성이 변경되었을 때 실행합니다. 패키지 설치 기준은 [npm ci 공식 안내](https://docs.npmjs.com/cli/v11/commands/npm-ci)를 참고하세요.

터미널에 표시된 **Local 주소**를 엽니다. 기본 주소는 [http://localhost:5173](http://localhost:5173)입니다. 기본 포트가 사용 중이면 다른 포트가 표시될 수 있으므로 실제 출력된 주소를 우선합니다.

화면에서 **백엔드 연결 확인** 버튼을 눌러 다음을 확인합니다.

* `백엔드 연결 성공` 메시지
* 응답의 `status`가 `UP`
* 응답의 `service`가 `pc-upgrade-lab`

프런트엔드에서 호출하는 `/api` 요청의 개발 서버 연결 설정은 `frontend/vite.config.ts`에서 확인할 수 있습니다. 개발 서버의 프록시는 [Vite 공식 안내](https://vite.dev/config/server-options.html#server-proxy)를 참고하세요.

화면 연결과 DB 연결은 각각 확인합니다. DB는 앞 단계의 \*\*8080 포트 `/actuator/health`\*\*에서 확인해 주세요.

### 프런트엔드 빌드 확인

같은 터미널을 쓰려면 `Ctrl + C`로 개발 서버를 종료한 뒤 실행합니다. 개발 서버를 계속 켜 두려면 `frontend` 폴더에서 새 터미널을 엽니다.

```powershell
npm run build
```

TypeScript 검사와 Vite 빌드가 오류 없이 완료되어야 합니다. 결과는 `frontend/dist`에 생성됩니다. 빌드는 실행 가능한 결과물을 만드는 과정이며, 그 자체로 외부에 배포되지는 않습니다.

다시 화면을 열려면 `npm run dev`를 실행합니다.

## 8\. 매일 실행하는 순서

최초 설치를 마친 후에는 보통 아래 순서면 됩니다.

1. MySQL 서비스가 실행 중인지 확인합니다.
2. IntelliJ에서 `backend \[bootRun]`을 실행합니다.
3. `frontend` 폴더의 터미널에서 `npm run dev`를 실행합니다.
4. 브라우저에서 화면을 열고 작업합니다.

종료할 때는 프런트엔드 터미널에서 `Ctrl + C`, IntelliJ의 백엔드 실행 창에서 **■ 중지**를 누릅니다.

IntelliJ 실행 구성에 입력한 환경 변수는 해당 실행에서 사용됩니다. 별도의 PowerShell에서 곧바로 `gradlew.bat build`를 실행하면 그 변수가 자동으로 전달되는 것은 아닙니다. 이 안내를 따를 때는 설정해 둔 `backend \[build]`를 사용해 주세요.

## 9\. GitHub 협업 방법

### 합류와 권한

제가 레포의 \*\*Settings(설정) → Collaborators(공동 작업자) → Add people(사용자 추가)\*\*에서 초대할게요.

### 브랜치 역할

|브랜치|용도|
|-|-|
|`main`|발표·배포용으로 검증한 버전|
|`dev`|팀 개발 내용 통합|
|`chore/\*`|환경설정, 문서, 관리 작업|
|`feature/\*`|기능 개발|
|`fix/\*`|오류 수정|

개인 작업 브랜치를 `dev`에서 만들고, 작업을 마치면 \*\*Pull Request(PR)의 병합 대상도 `dev`\*\*로 지정합니다. PR은 다른 팀원이 변경 내용을 검토한 뒤 통합할 수 있도록 요청하는 기능입니다.

### 작업 시작 예시

프로젝트 루트에서 기존 작업이 정리된 상태인지 확인한 후 실행합니다. 아래 `chore/setup-docs`는 문서 작업 브랜치의 예시이며, 실제 작업에 맞는 이름을 정합니다.

```powershell
git status -sb
git switch dev
git pull --ff-only origin dev
git switch -c chore/setup-docs
```

작업을 마치면 변경 파일을 확인하고 필요한 파일만 추가합니다. 아래는 README만 수정한 예시입니다.

```powershell
git diff
git add README.md
git diff --cached --stat
git diff --cached
git commit -m "docs: improve team setup guide"
git push -u origin chore/setup-docs
```

GitHub에서 **Pull requests → New pull request**를 열고 **base: dev**, **compare: 본인 작업 브랜치**를 선택합니다. PR 설명에는 변경 이유, 바뀐 내용, 직접 확인한 실행·빌드 결과를 적습니다.

### 공유할 파일과 개인에게 남길 파일

|Git에 포함|Git에서 제외|
|-|-|
|소스 코드, `build.gradle`|개인 비밀번호와 토큰|
|`gradlew`, `gradlew.bat`, `gradle/wrapper/`|`.gradle/`, `backend/build/`|
|`package.json`, `package-lock.json`|`node\_modules/`, `frontend/dist/`|
|`application-local.example.properties`|`application-local.properties`|
|README와 설계 문서|`.idea/`, 개인 환경 변수 파일|

개인 설정이 제외되는지 프로젝트 루트에서 확인할 수 있습니다.

```powershell
git check-ignore -v backend/src/main/resources/application-local.properties
```

출력에 `.gitignore`의 해당 규칙이 나오면 무시 규칙이 적용된 것입니다. 커밋 전에 `git diff --cached --name-only`로 실제 포함 파일도 확인합니다. 비밀번호를 넣은 실행 구성을 별도 프로젝트 파일로 저장했다면 그 파일도 공유 대상에 넣지 않습니다.

## 10\. 자주 막히는 부분

|증상|확인할 내용|
|-|-|
|MySQL `ERROR 1045 Access denied`|전용 계정이 생성되었는지, 계정 이름·비밀번호·접속 호스트가 맞는지 확인합니다. root 비밀번호와 앱 계정 비밀번호를 구분합니다.|
|`Unknown database 'pc\_upgrade\_lab'`|현재 접속한 MySQL 서버에 DB를 만들었는지 확인합니다.|
|`Communications link failure` 또는 MySQL 접속 거부|MySQL 서비스 실행 상태와 `localhost:3306` 설정을 확인합니다.|
|`${DB\_PASSWORD}` 관련 오류|실제 실행한 Gradle 구성에 `DB\_PASSWORD`가 있는지, 철자와 대소문자가 맞는지 확인합니다.|
|`Failed to configure a DataSource`|`application-local.properties` 파일과 `SPRING\_PROFILES\_ACTIVE=local`을 확인합니다.|
|서버 실행은 되지만 빌드 테스트가 실패|`backend \[build]`에도 `local`과 `DB\_PASSWORD`가 있는지 확인합니다. 두 실행 구성은 따로 저장됩니다.|
|8080 포트가 이미 사용 중|이전에 켠 백엔드가 남아 있는지 확인하고, 본인이 실행한 중복 서버를 종료합니다.|
|화면은 뜨지만 백엔드 연결 실패|백엔드가 실행 중인지 먼저 8080의 `/api/health`로 확인하고 Vite 프록시 설정을 확인합니다.|
|`/actuator/health`가 404|최신 환경설정 코드, Actuator 의존성, 설정 파일 적용 여부를 확인합니다.|
|`/actuator/health`에 `db`가 없음|JDBC 의존성과 datasource 설정이 적용되었는지 확인합니다.|
|`npm ci`에서 package-lock 오류|`frontend` 폴더인지, 팀에서 커밋한 `package.json`과 `package-lock.json`을 함께 받았는지 확인합니다.|
|PowerShell이 `npm.ps1` 실행을 차단|같은 폴더에서 `npm.cmd ci`, `npm.cmd run dev`, `npm.cmd run build`를 사용해 볼 수 있습니다.|
|Java 버전 관련 빌드 오류|프로젝트 SDK와 Gradle JVM이 모두 JDK 21인지 확인합니다.|
|Git push 권한 오류|공동 작업자 초대 수락 여부와 Git이 로그인한 GitHub 계정을 확인합니다.|
|예제 설정 파일이나 frontend/package.json이 없음|환경설정 PR의 `dev` 반영 여부를 확인하고, 로컬 변경을 정리한 뒤 `dev`를 갱신합니다.|

오류를 공유할 때는 **어느 단계에서 어떤 명령을 실행했는지**, **오류 메시지 전체**, **현재 브랜치**를 함께 알려 주세요. 비밀번호와 토큰은 가리고, 오류가 난 SQL이나 명령의 주변 내용이 보이도록 캡처하면 확인하기 쉽습니다.

## 11\. 설정 완료 확인

* \[ ] 저장소의 `dev` 브랜치를 가져왔다.
* \[ ] JDK 21과 Node.js 24를 확인했다.
* \[ ] 내 PC의 MySQL에 DB와 전용 계정을 만들고 로그인했다.
* \[ ] 예제 파일을 개인용 `application-local.properties`로 복사했다.
* \[ ] `build`와 `bootRun` 실행 구성에 환경 변수 두 개를 설정했다.
* \[ ] 백엔드 빌드에서 `BUILD SUCCESSFUL`을 확인했다.
* \[ ] `/api/health`의 응답을 확인했다.
* \[ ] `/actuator/health`의 `components.db.status`가 `UP`이다.
* \[ ] 프런트엔드 화면의 백엔드 연결 버튼이 성공한다.
* \[ ] `npm run build`가 성공한다.
* \[ ] 개인 비밀번호와 설정 파일이 커밋에 포함되지 않았다.
* \[ ] 공동 작업자 초대를 수락했다.

## 선택: IntelliJ Ultimate 학생 라이선스

현재 학생 자격을 충족하면 JetBrains 교육용 라이선스를 신청할 수 있으며, 계속 재학 중이면 1년 단위로 갱신할 수 있습니다. 이전에 받았던 계정이 있다면 [JetBrains Account](https://account.jetbrains.com/)에 로그인하여 **Renew my Education Pack**을 먼저 확인하세요. [교육용 라이선스 갱신 안내](https://sales.jetbrains.com/hc/en-gb/articles/11564534574994-How-can-I-renew-my-free-educational-license-for-another-year)

처음 신청하거나 신청 경로가 필요하면 [학생 라이선스 신청](https://www.jetbrains.com/shop/eform/students)을 이용합니다. 현재 공식 안내는 학교 이메일, GitHub Student Developer Pack, ISIC/ITIC를 통한 확인 방법을 제공합니다. 팀원은 각자 본인 자격으로 신청합니다. [신청 방법](https://sales.jetbrains.com/hc/en-gb/articles/11558649766674-How-do-I-apply-for-a-free-educational-license)

Ultimate는 Spring 프로젝트 분석과 IDE 안에서 MySQL 쿼리를 실행·편집하는 작업에 도움이 됩니다. [Spring 개발 지원](https://www.jetbrains.com/help/idea/spring-support.html), [데이터베이스 도구](https://www.jetbrains.com/help/idea/relational-databases.html)

IntelliJ IDEA 2026.1부터는 기본 JavaScript·TypeScript·React 지원도 무료 기능에 포함됩니다. 이 문서의 환경설정과 실행 절차는 학생 라이선스 발급을 기다리는 동안에도 진행할 수 있습니다. [무료 웹 개발 기능 안내](https://blog.jetbrains.com/idea/2026/03/js-ts-free-support/)


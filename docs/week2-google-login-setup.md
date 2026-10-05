# Google 로그인 로컬 실행 안내 (이슈 #22)

## 현재 구현과 범위

- Google OpenID Connect로 로그인한다. Google이 부여한 `sub` 값으로 `social_accounts`를 찾거나 새 회원을 만든다. 이메일만 같다는 이유로 계정을 합치지 않는다.
- 로그인 성공 시 세션의 `LOGIN_USER_ID`에 `users.id`를 저장한다. `/api/pcs/**`는 기존 `CurrentUser`를 통해 이 ID를 사용한다.
- `/api/auth/me`, `/api/auth/logout`, `/api/auth/providers`와 Vite의 인증 경로 프록시를 제공한다.
- Google 클라이언트 설정이 없는 동안에는 OAuth 시작 기능만 비활성이고, 기존 H2 테스트·서버 실행은 가능하다. `/api/auth/providers`는 합의된 규격대로 `google`을 표시하므로 실제 로그인 테스트 전에는 아래 설정을 마쳐야 한다.
- Kakao 로그인과 실제 Google 계정 두 개로 하는 수동 검증은 이 문서만으로 완료되지 않는다.

## 1. 적용 전 DB 확인

V11은 `users`, `social_accounts` 및 `pc_configuration.user_id` 외래 키를 추가한다. 기존 `user_id IS NULL`인 local-dev PC는 그대로 남고 누구에게도 자동 귀속되지 않는다.

실제 MySQL에 적용하기 전 다음 **조회만** 수행한다.

```sql
SELECT COUNT(*) AS assigned_pc_count
FROM pc_configuration
WHERE user_id IS NOT NULL;
```

0이 아니면 숫자 `user_id`가 들어 있는 개발 데이터가 있다는 뜻이다. V11 외래 키가 실패할 수 있으므로 해당 행의 실제 소유자를 고상준·경민과 확인한 뒤 이관 방법을 결정한다. 임의로 첫 회원에게 연결하거나 삭제하지 않는다. V11 마이그레이션은 고상준 검토 후 적용한다.

## 2. Google Cloud 설정

1. Google Cloud에서 OAuth 동의 화면을 설정한다.
2. OAuth 클라이언트 유형은 **웹 애플리케이션**으로 만든다.
3. 승인된 리디렉션 URI에 아래 주소를 정확히 등록한다.

```text
http://127.0.0.1:5173/login/oauth2/code/google
```

`localhost`와 `127.0.0.1`, 포트, 마지막 `/` 유무는 서로 다르다. 이 프로젝트의 개발 주소는 `127.0.0.1:5173`으로 통일한다. Google 설정의 URI와 앱이 보낸 URI가 다르면 `redirect_uri_mismatch`가 난다.

## 3. 개인 설정 (Git에 올리지 않음)

`backend/src/main/resources/application-local.properties`에 아래 세 줄을 추가한다. 이 파일은 개인 로컬 파일이며 커밋하지 않는다.

```properties
spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID}
spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET}
spring.security.oauth2.client.registration.google.redirect-uri=${app.frontend.base-url}/login/oauth2/code/google
```

IntelliJ의 **backend 실행 구성**에 환경변수 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`을 설정한다. 기존 `DB_PASSWORD`와 `SPRING_PROFILES_ACTIVE=local`도 유지한다. 실제 값은 GitHub Issue, PR, 채팅, 화면 캡처에 올리지 않는다.

환경변수를 아직 준비하지 않았다면 위 세 줄도 추가하지 말고 기존처럼 실행한다. Google 로그인만 동작하지 않는다.

## 4. 실행 순서

1. 실제 MySQL의 기존 데이터와 V11 적용 가능 여부를 확인한다.
2. 백엔드를 `local` 프로필로 실행한다(8080).
3. 프런트엔드 디렉터리에서 `npm.cmd run dev`를 실행한다(5173). 실제 로그인 확인 시 `DEV_USER_ID`는 설정하지 않는다.
4. 일반 Chrome 또는 Edge에서 `http://127.0.0.1:5173/#/login`을 연다.
5. Google 버튼을 누른다. 인증 시작은 `/oauth2/authorization/google`, 콜백은 `/login/oauth2/code/google`이며 둘 다 Vite가 백엔드로 전달한다.
6. 성공 후 `/#/login/success`로 돌아오면 `/api/auth/me`의 회원 정보와 PC 목록을 확인한다. 로그아웃 후에는 `/api/auth/me`가 401이어야 한다.

## 5. 확인 기준

- Google 계정 A로 로그인 → PC 저장 → 로그아웃 → A 재로그인 시 같은 PC 조회
- Google 계정 B로 로그인 → A의 PC는 목록에 없고 ID 직접 조회·수정·삭제는 404
- 같은 회원의 PC 이름 중복은 409, 다른 회원은 같은 이름 사용 가능
- 비로그인 `/api/pcs`는 302가 아닌 401 JSON
- 세션 쿠키는 HttpOnly, `XSRF-TOKEN` 쿠키를 읽어 쓴 `X-XSRF-TOKEN` 헤더가 없으면 브라우저의 쓰기 요청은 403
- Windows 수집기의 `/api/scan-sessions/{id}/start|result|failure`는 별도의 검사 토큰으로 검증하므로 CSRF에서 제외

자동 검사는 H2·가짜 Google 클라이언트 설정만 사용하므로 실제 Google 승인과 실제 MySQL 영속성은 위 순서로 별도 확인해야 한다.
